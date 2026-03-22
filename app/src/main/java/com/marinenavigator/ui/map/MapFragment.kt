package com.marinenavigator.ui.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.marinenavigator.R
import com.marinenavigator.data.models.FishingPoint
import com.marinenavigator.data.models.Waypoint
import com.marinenavigator.databinding.FragmentMapBinding
import com.marinenavigator.services.NavigationService
import com.marinenavigator.utils.NavigationUtils
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.*
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MapViewModel by activityViewModels()

    private lateinit var mapView: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private var routeOverlay: Polyline? = null   // ruta de navegación calculada
    private var trackOverlay: Polyline? = null   // track grabado en tiempo real
    private var destinationMarker: Marker? = null
    private var originMarker: Marker? = null
    private var anchorCircleOverlay: Overlay? = null
    private var anchorMarkerOverlay: Marker? = null
    private val fishingMarkers = mutableListOf<Marker>()
    private val waypointMarkers = mutableListOf<Marker>()
    private var followLocation = true
    private var showNauticalCharts = true
    private var mapEventsOverlay: MapEventsOverlay? = null
    private var scaleBarOverlay: ScaleBarOverlay? = null

    // Sensor para brújula física
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var smoothedCompassBearing = 0f
    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            val raw = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
            // Filtro paso bajo para suavizar la lectura
            var diff = raw - smoothedCompassBearing
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            smoothedCompassBearing = (smoothedCompassBearing + 0.15f * diff + 360f) % 360f
            _binding?.compassView?.setBearing(smoothedCompassBearing)
            if (::mapView.isInitialized) {
                _binding?.northMapCompass?.setBearing(mapView.mapOrientation)
                mapView.postInvalidate()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            initializeMap()
            startNavigationService()
        } else {
            Toast.makeText(context, "Se requiere permiso de ubicación para navegar", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────────────────────────────────
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        Configuration.getInstance().userAgentValue = requireContext().packageName
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        checkPermissionsAndInit()
        setupButtons()
        observeViewModel()
    }

    // ─────────────────────────────────────────────────────────
    // PERMISOS
    // ─────────────────────────────────────────────────────────
    private fun checkPermissionsAndInit() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            initializeMap()
            startNavigationService()
        } else {
            permissionLauncher.launch(perms)
        }
    }

    // ─────────────────────────────────────────────────────────
    // INICIALIZAR MAPA CON CARTAS NÁUTICAS
    // ─────────────────────────────────────────────────────────
    private fun initializeMap() {
        mapView = binding.mapView
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)

        // Base: OpenStreetMap (fiable, sin API key)
        mapView.setTileSource(TileSourceFactory.MAPNIK)

        // Overlay de rotación de mapa
        val rotationOverlay = RotationGestureOverlay(mapView)
        rotationOverlay.isEnabled = true
        mapView.overlays.add(rotationOverlay)

        // Overlay de marcas náuticas OpenSeaMap (boyas, luces, puertos) + curvas batimétricas
        addNauticalChartOverlay()
        addDefaultDepthContours()

        // Indicador de Norte: View fija en el layout, se actualiza con mapView.mapOrientation
        binding.northMapCompass.setBearing(0f)

        // Overlay de posición
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        locationOverlay.runOnFirstFix {
            requireActivity().runOnUiThread {
                mapView.controller.setZoom(15.0)
                mapView.controller.animateTo(locationOverlay.myLocation)
            }
        }
        mapView.overlays.add(locationOverlay)

        // Overlay para tap en el mapa
        mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                showLongPressMenu(p)
                return true
            }
        })
        mapView.overlays.add(mapEventsOverlay!!)

        // Escala
        scaleBarOverlay = ScaleBarOverlay(mapView).apply {
            setUnitsOfMeasure(ScaleBarOverlay.UnitsOfMeasure.nautical)
            setAlignRight(true)
        }
        mapView.overlays.add(scaleBarOverlay!!)

        mapView.invalidate()
    }

    private fun addNauticalChartOverlay() {
        // Capa 1: IHM — Cartas náuticas ENC oficiales (Instituto Hidrográfico de la Marina)
        // WMS: http://ideihm.covam.es/wms/cartaENCp5
        val ihmWmsSource = object : OnlineTileSourceBase(
            "IHM_ENC", 3, 18, 256, ".png",
            arrayOf("http://ideihm.covam.es/wms/cartaENCp5")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex).toInt()
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val n = Math.pow(2.0, z.toDouble())
                val minLon = x / n * 360.0 - 180.0
                val maxLon = (x + 1) / n * 360.0 - 180.0
                val maxLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))))
                val minLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 1) / n))))
                val bbox = "$minLon,$minLat,$maxLon,$maxLat"
                return "$baseUrl?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap" +
                    "&LAYERS=MS:ENC&STYLES=&SRS=EPSG:4326" +
                    "&BBOX=$bbox&WIDTH=256&HEIGHT=256" +
                    "&FORMAT=image/png&TRANSPARENT=TRUE"
            }
        }
        val ihmOverlay = TilesOverlay(
            org.osmdroid.tileprovider.MapTileProviderBasic(context, ihmWmsSource), context
        )
        ihmOverlay.loadingBackgroundColor = Color.TRANSPARENT
        ihmOverlay.loadingLineColor = Color.TRANSPARENT
        mapView.overlays.add(ihmOverlay)

        // Capa 2: OpenSeaMap — marcas de navegación (boyas, luces, puertos, peligros)
        val openSeaMapSource = object : OnlineTileSourceBase(
            "OpenSeaMap", 3, 18, 256, ".png",
            arrayOf("https://tiles.openseamap.org/seamark/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                baseUrl +
                    MapTileIndex.getZoom(pMapTileIndex) + "/" +
                    MapTileIndex.getX(pMapTileIndex) + "/" +
                    MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
        }
        val seamarkOverlay = TilesOverlay(
            org.osmdroid.tileprovider.MapTileProviderBasic(context, openSeaMapSource), context
        )
        seamarkOverlay.loadingBackgroundColor = Color.TRANSPARENT
        seamarkOverlay.loadingLineColor = Color.TRANSPARENT
        mapView.overlays.add(seamarkOverlay)
    }

    // Curvas batimétricas EMODnet por defecto (líneas de profundidad como en OpenSeaMap)
    private fun addDefaultDepthContours() {
        val emodnetSource = object : OnlineTileSourceBase(
            "EMODnet_Default", 3, 18, 256, ".png",
            arrayOf("https://ows.emodnet-bathymetry.eu/wms")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex).toInt()
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val n = Math.pow(2.0, z.toDouble())
                val minLon = x / n * 360.0 - 180.0
                val maxLon = (x + 1) / n * 360.0 - 180.0
                val maxLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))))
                val minLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 1) / n))))
                val bbox = "$minLon,$minLat,$maxLon,$maxLat"
                return "$baseUrl?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap" +
                    "&LAYERS=emodnet:contours&STYLES=&SRS=EPSG:4326" +
                    "&BBOX=$bbox&WIDTH=256&HEIGHT=256" +
                    "&FORMAT=image/png&TRANSPARENT=TRUE"
            }
        }
        val contourOverlay = TilesOverlay(
            org.osmdroid.tileprovider.MapTileProviderBasic(context, emodnetSource), context
        )
        contourOverlay.loadingBackgroundColor = Color.TRANSPARENT
        contourOverlay.loadingLineColor = Color.TRANSPARENT
        mapView.overlays.add(contourOverlay)
    }

    // Fuente de tiles de satélite (Google) con URL correcta
    private fun createSatelliteSource() = object : OnlineTileSourceBase(
        "GoogleSat", 2, 20, 256, ".jpg",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=s&hl=en&",
            "https://mt1.google.com/vt/lyrs=s&hl=en&",
            "https://mt2.google.com/vt/lyrs=s&hl=en&"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}x=$x&y=$y&z=$z"
        }
    }

    // Capa de batimetría GEBCO (WMS oficial) + curvas EMODnet + peligros náuticos
    private fun addBathymetryAndHazardOverlays() {
        // GEBCO WMS oficial — profundidades oceánicas globales como mapa de colores
        val gebcoSource = object : OnlineTileSourceBase(
            "GEBCO_WMS", 2, 14, 256, ".png",
            arrayOf("https://www.gebco.net/data_and_products/gebco_web_services/web_map_service/mapserv")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex).toInt()
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val n = Math.pow(2.0, z.toDouble())
                val minLon = x / n * 360.0 - 180.0
                val maxLon = (x + 1) / n * 360.0 - 180.0
                val maxLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))))
                val minLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 1) / n))))
                val bbox = "$minLon,$minLat,$maxLon,$maxLat"
                return "$baseUrl?service=WMS&request=GetMap&version=1.1.1" +
                    "&layers=GEBCO_LATEST&styles=&format=image/png&transparent=true" +
                    "&SRS=EPSG:4326&bbox=$bbox&width=256&height=256"
            }
        }
        val gebcoOverlay = TilesOverlay(
            org.osmdroid.tileprovider.MapTileProviderBasic(context, gebcoSource), context
        )
        gebcoOverlay.loadingBackgroundColor = Color.TRANSPARENT
        gebcoOverlay.loadingLineColor = Color.TRANSPARENT
        mapView.overlays.add(gebcoOverlay)

        // EMODnet Bathymetry WMS — curvas batimétricas (isobaras de profundidad) para aguas europeas
        val emodnetSource = object : OnlineTileSourceBase(
            "EMODnet_Contours", 3, 18, 256, ".png",
            arrayOf("https://ows.emodnet-bathymetry.eu/wms")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex).toInt()
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val n = Math.pow(2.0, z.toDouble())
                val minLon = x / n * 360.0 - 180.0
                val maxLon = (x + 1) / n * 360.0 - 180.0
                val maxLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))))
                val minLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 1) / n))))
                val bbox = "$minLon,$minLat,$maxLon,$maxLat"
                return "$baseUrl?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap" +
                    "&LAYERS=emodnet:contours&STYLES=&SRS=EPSG:4326" +
                    "&BBOX=$bbox&WIDTH=256&HEIGHT=256" +
                    "&FORMAT=image/png&TRANSPARENT=TRUE"
            }
        }
        val emodnetOverlay = TilesOverlay(
            org.osmdroid.tileprovider.MapTileProviderBasic(context, emodnetSource), context
        )
        emodnetOverlay.loadingBackgroundColor = Color.TRANSPARENT
        emodnetOverlay.loadingLineColor = Color.TRANSPARENT
        mapView.overlays.add(emodnetOverlay)

        // OpenSeaMap — marcas de navegación (boyas, luces, peligros puntuales)
        val openSeaMapSource = object : OnlineTileSourceBase(
            "OpenSeaMap", 3, 18, 256, ".png",
            arrayOf("https://tiles.openseamap.org/seamark/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                baseUrl +
                    MapTileIndex.getZoom(pMapTileIndex) + "/" +
                    MapTileIndex.getX(pMapTileIndex) + "/" +
                    MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
        }
        val seamarkOverlay = TilesOverlay(
            org.osmdroid.tileprovider.MapTileProviderBasic(context, openSeaMapSource), context
        )
        seamarkOverlay.loadingBackgroundColor = Color.TRANSPARENT
        seamarkOverlay.loadingLineColor = Color.TRANSPARENT
        mapView.overlays.add(seamarkOverlay)
    }

    // Restaura todos los overlays no-tile tras cambiar capa base
    private fun restoreNonTileOverlays() {
        mapView.overlays.add(locationOverlay)
        fishingMarkers.forEach { mapView.overlays.add(it) }
        waypointMarkers.forEach { mapView.overlays.add(it) }
        routeOverlay?.let { mapView.overlays.add(it) }
        trackOverlay?.let { mapView.overlays.add(it) }
        destinationMarker?.let { mapView.overlays.add(it) }
        originMarker?.let { mapView.overlays.add(it) }
        anchorCircleOverlay?.let { mapView.overlays.add(it) }
        anchorMarkerOverlay?.let { mapView.overlays.add(it) }
        mapEventsOverlay?.let { mapView.overlays.add(it) }
        scaleBarOverlay?.let { mapView.overlays.add(it) }
        mapView.invalidate()
    }

    // ─────────────────────────────────────────────────────────
    // MENÚ CONTEXTUAL AL MANTENER PULSADO
    // ─────────────────────────────────────────────────────────
    private fun showLongPressMenu(point: GeoPoint) {
        val options = arrayOf(
            "Navegar aquí",
            "Establecer como origen de ruta",
            "Guardar como waypoint",
            "Guardar punto de pesca"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("%.5f, %.5f".format(point.latitude, point.longitude))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showNavigateToDialog(point)
                    1 -> {
                        viewModel.setOriginPoint(point.latitude, point.longitude)
                        originMarker?.let { mapView.overlays.remove(it) }
                        originMarker = Marker(mapView).apply {
                            position = point
                            title = "Origen"
                            snippet = "Punto de partida de la ruta"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        mapView.overlays.add(originMarker!!)
                        mapView.invalidate()
                        Toast.makeText(context, "Origen establecido", Toast.LENGTH_SHORT).show()
                    }
                    2 -> showSaveWaypointDialog(point)
                    3 -> showSaveFishingPointDialog(point)
                }
            }
            .show()
    }

    private fun showNavigateToDialog(point: GeoPoint) {
        val editText = TextInputEditText(requireContext()).apply {
            hint = "Nombre del destino"
            setPadding(48, 16, 48, 16)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#88AABB"))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Navegar a")
            .setView(editText)
            .setPositiveButton("Navegar") { _, _ ->
                val name = editText.text.toString().ifBlank { "Destino" }
                val wp = Waypoint(name = name, latitude = point.latitude, longitude = point.longitude)
                viewModel.startNavigationTo(wp)
                setDestinationMarker(point, name)
                // Calcular ruta marina evitando tierra
                Toast.makeText(context, "Calculando ruta marina...", Toast.LENGTH_SHORT).show()
                viewModel.calculateMarineRoute(point.latitude, point.longitude)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSaveWaypointDialog(point: GeoPoint) {
        val editText = TextInputEditText(requireContext()).apply {
            hint = "Nombre del waypoint"
            setPadding(48, 16, 48, 16)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#88AABB"))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Guardar waypoint")
            .setView(editText)
            .setPositiveButton("Guardar") { _, _ ->
                val name = editText.text.toString().ifBlank { "WP ${System.currentTimeMillis() / 1000}" }
                viewModel.saveWaypoint(name, point.latitude, point.longitude)
                Toast.makeText(context, "Waypoint '$name' guardado", Toast.LENGTH_SHORT).show()
                addWaypointMarker(Waypoint(name = name, latitude = point.latitude, longitude = point.longitude))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSaveFishingPointDialog(point: GeoPoint) {
        val editText = TextInputEditText(requireContext()).apply {
            hint = "Nombre del punto de pesca"
            setPadding(48, 16, 48, 16)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#88AABB"))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Guardar punto de pesca")
            .setView(editText)
            .setPositiveButton("Guardar") { _, _ ->
                val name = editText.text.toString().ifBlank { "Pesca ${System.currentTimeMillis() / 1000}" }
                viewModel.saveFishingPoint(name, point.latitude, point.longitude)
                Toast.makeText(context, "Punto de pesca '$name' guardado", Toast.LENGTH_SHORT).show()
                addFishingMarker(FishingPoint(name = name, latitude = point.latitude, longitude = point.longitude))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAnchorAlarmDialog(point: GeoPoint? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_anchor_alarm, null)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Alerta de fondeo")
            .setView(view)
            .setPositiveButton("Activar") { _, _ ->
                val radiusInput = view.findViewById<TextInputEditText>(R.id.etRadius)
                val radius = radiusInput.text.toString().toDoubleOrNull() ?: 50.0
                viewModel.activateAnchorAlarm(radius)
                Toast.makeText(context, "Alerta de fondeo activada (${radius.toInt()} m)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────
    // BOTONES DE LA PANTALLA
    // ─────────────────────────────────────────────────────────
    private fun setupButtons() {
        // Centrar en posición
        binding.fabCenter.setOnClickListener {
            if (!::mapView.isInitialized) return@setOnClickListener
            followLocation = true
            locationOverlay.enableFollowLocation()
            locationOverlay.myLocation?.let {
                mapView.controller.animateTo(it)
                mapView.controller.setZoom(15.0)
            }
        }

        // Activar/desactivar cartas náuticas
        binding.fabLayers.setOnClickListener {
            showLayersMenu()
        }

        // Grabación de ruta
        binding.fabRecord.setOnClickListener {
            if (viewModel.isRecording.value) {
                viewModel.stopRecording()
                binding.fabRecord.setImageResource(R.drawable.ic_record)
                binding.recordingIndicator.isVisible = false
                Toast.makeText(context, "Ruta guardada", Toast.LENGTH_SHORT).show()
            } else {
                showStartRecordingDialog()
            }
        }

        // Alerta de fondeo desde botón
        binding.btnAnchor.setOnClickListener {
            if (viewModel.anchorAlarmConfig.value?.isActive == true) {
                viewModel.deactivateAnchorAlarm()
                clearAnchorCircle()
                binding.btnAnchor.setColorFilter(Color.WHITE)
                Toast.makeText(context, "Alerta de fondeo desactivada", Toast.LENGTH_SHORT).show()
            } else {
                showAnchorAlarmDialog()
            }
        }

        // Parar navegación
        binding.btnStopNav.setOnClickListener {
            viewModel.stopNavigation()
            if (::mapView.isInitialized) {
                destinationMarker?.let { mapView.overlays.remove(it) }
                routeOverlay?.let { mapView.overlays.remove(it) }
                mapView.invalidate()
            }
            binding.navigationPanel.isVisible = false
        }
    }

    private fun showStartRecordingDialog() {
        val editText = TextInputEditText(requireContext()).apply {
            hint = "Nombre de la ruta (opcional)"
            setPadding(48, 16, 48, 16)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#88AABB"))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Grabar ruta")
            .setView(editText)
            .setPositiveButton("Iniciar") { _, _ ->
                val name = editText.text.toString()
                viewModel.startRecording(name)
                binding.fabRecord.setImageResource(R.drawable.ic_stop)
                binding.recordingIndicator.isVisible = true
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLayersMenu() {
        val options = arrayOf(
            "OpenSeaMap",
            "Satélite"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Capa del mapa")
            .setItems(options) { _, which ->
                mapView.overlays.clear()
                val rot = RotationGestureOverlay(mapView)
                rot.isEnabled = true
                mapView.overlays.add(rot)

                when (which) {
                    0 -> { mapView.setTileSource(TileSourceFactory.MAPNIK); addBathymetryAndHazardOverlays() }
                    1 -> { mapView.setTileSource(createSatelliteSource()); addBathymetryAndHazardOverlays() }
                }
                restoreNonTileOverlays()
            }
            .show()
    }

    // ─────────────────────────────────────────────────────────
    // OBSERVAR VIEWMODEL
    // ─────────────────────────────────────────────────────────
    private fun observeViewModel() {
        // GPS en tiempo real
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gpsData.collect { gps ->
                if (!gps.isValid) return@collect
                updateGpsDisplay(gps)
            }
        }

        // Estado de navegación
        viewModel.navigationState.observe(viewLifecycleOwner) { state ->
            if (state.isNavigating && state.destination != null) {
                binding.navigationPanel.isVisible = true
                updateNavigationPanel(state)
            } else {
                binding.navigationPanel.isVisible = false
            }
        }

        // Ruta marina calculada por Brouter
        viewModel.marineRoutePoints.observe(viewLifecycleOwner) { points ->
            if (!::mapView.isInitialized) return@observe
            if (points.isNotEmpty()) {
                drawMarineRoute(points)
            }
        }

        // Puntos de pesca
        viewModel.fishingPoints.observe(viewLifecycleOwner) { points ->
            if (!::mapView.isInitialized) return@observe
            fishingMarkers.forEach { mapView.overlays.remove(it) }
            fishingMarkers.clear()
            points.forEach { addFishingMarker(it) }
            mapView.invalidate()
        }

        // Waypoints
        viewModel.waypoints.observe(viewLifecycleOwner) { wps ->
            if (!::mapView.isInitialized) return@observe
            waypointMarkers.forEach { mapView.overlays.remove(it) }
            waypointMarkers.clear()
            wps.forEach { addWaypointMarker(it) }
            mapView.invalidate()
        }

        // Track activo en el mapa (en tiempo real durante grabación)
        viewModel.currentTrackPoints.observe(viewLifecycleOwner) { points ->
            if (!::mapView.isInitialized) return@observe
            drawLiveTrack(points.map { GeoPoint(it.latitude, it.longitude) })
        }

        // Estado grabación
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isRecording.collect { recording ->
                binding.fabRecord.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
                binding.recordingIndicator.isVisible = recording
            }
        }

        // Alerta de fondeo
        viewModel.anchorAlarmConfig.observe(viewLifecycleOwner) { cfg ->
            if (!::mapView.isInitialized) return@observe
            if (cfg.isActive) {
                binding.btnAnchor.setColorFilter(Color.RED)
                drawAnchorCircle(cfg.radiusMeters, GeoPoint(cfg.centerLat, cfg.centerLon))
            } else {
                binding.btnAnchor.setColorFilter(Color.WHITE)
                clearAnchorCircle()
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // ACTUALIZAR DISPLAY GPS
    // ─────────────────────────────────────────────────────────
    private fun updateGpsDisplay(gps: com.marinenavigator.data.models.GpsData) {
        binding.tvLat.text = NavigationUtils.formatLatitude(gps.latitude)
        binding.tvLon.text = NavigationUtils.formatLongitude(gps.longitude)
        binding.tvSpeed.text = "%.1f kt".format(gps.speedKnots)
        binding.tvBearing.text = "%.0f° %s".format(gps.bearing, NavigationUtils.bearingName(gps.bearing))
    }

    private fun updateNavigationPanel(state: com.marinenavigator.data.models.NavigationState) {
        binding.tvDestName.text = state.destination?.name ?: "Destino"
        binding.tvDistToDest.text = NavigationUtils.formatDistance(state.distanceToDestMeters)
        binding.tvEta.text = NavigationUtils.formatDuration(state.etaSeconds)
        binding.tvBearingToDest.text = "%.0f° %s".format(
            state.bearingToDestDegrees,
            NavigationUtils.bearingName(state.bearingToDestDegrees)
        )
        // Indicador de desviación de rumbo
        val error = state.headingError
        binding.tvHeadingError.text = when {
            error > 5 -> "↻ %.0f°".format(error)
            error < -5 -> "↺ %.0f°".format(-error)
            else -> "En rumbo ✓"
        }
    }

    // ─────────────────────────────────────────────────────────
    // OVERLAYS EN EL MAPA
    // ─────────────────────────────────────────────────────────
    private fun setDestinationMarker(point: GeoPoint, name: String) {
        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = Marker(mapView).apply {
            position = point
            title = name
            snippet = "Destino"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(destinationMarker!!)
        mapView.invalidate()
    }

    // Dibuja ruta marina calculada por Brouter (evita tierra y zonas de poco fondo)
    private fun drawMarineRoute(points: List<GeoPoint>) {
        routeOverlay?.let { mapView.overlays.remove(it) }
        routeOverlay = Polyline(mapView).apply {
            setPoints(points)
            outlinePaint.color = Color.CYAN
            outlinePaint.strokeWidth = 6f
            outlinePaint.style = Paint.Style.STROKE
        }
        mapView.overlays.add(routeOverlay!!)
        mapView.invalidate()
    }

    // Fallback: línea recta punteada si Brouter no está disponible
    private fun drawRouteLine(state: com.marinenavigator.data.models.NavigationState) {
        val dest = state.destination ?: return
        routeOverlay?.let { mapView.overlays.remove(it) }
        routeOverlay = Polyline(mapView).apply {
            setPoints(listOf(
                GeoPoint(state.currentLat, state.currentLon),
                GeoPoint(dest.latitude, dest.longitude)
            ))
            outlinePaint.color = Color.CYAN
            outlinePaint.strokeWidth = 4f
            outlinePaint.style = Paint.Style.STROKE
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }
        mapView.overlays.add(routeOverlay!!)
        mapView.invalidate()
    }

    private fun drawLiveTrack(points: List<GeoPoint>) {
        trackOverlay?.let { mapView.overlays.remove(it) }
        trackOverlay = null
        if (points.size < 2) { mapView.invalidate(); return }
        trackOverlay = Polyline(mapView).apply {
            setPoints(points)
            outlinePaint.color = Color.parseColor("#00FF88")  // verde neón para distinguir de la ruta
            outlinePaint.strokeWidth = 5f
            outlinePaint.style = Paint.Style.STROKE
        }
        mapView.overlays.add(trackOverlay!!)
        mapView.invalidate()
    }

    private fun drawSavedRoute(points: List<GeoPoint>) {
        routeOverlay?.let { mapView.overlays.remove(it) }
        if (points.size < 2) return
        routeOverlay = Polyline(mapView).apply {
            setPoints(points)
            outlinePaint.color = Color.rgb(255, 140, 0)
            outlinePaint.strokeWidth = 6f
        }
        mapView.overlays.add(routeOverlay!!)
        // Centrar en la ruta
        val box = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
        mapView.zoomToBoundingBox(box, true, 80)
        mapView.invalidate()
    }

    private fun markerIcon(drawableRes: Int, tintColor: Int): BitmapDrawable {
        val px = (44 * resources.displayMetrics.density).toInt()
        val d = ContextCompat.getDrawable(requireContext(), drawableRes)!!.mutate()
        DrawableCompat.setTint(d, tintColor)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, px, px)
        d.draw(canvas)
        return BitmapDrawable(resources, bmp)
    }

    private fun addFishingMarker(point: FishingPoint) {
        val marker = Marker(mapView).apply {
            position = GeoPoint(point.latitude, point.longitude)
            title = point.name
            snippet = if (point.depth != null) "Prof: %.1f m\n%s".format(point.depth, point.notes) else point.notes
            icon = markerIcon(R.drawable.ic_fishing, Color.parseColor("#00FF88"))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        fishingMarkers.add(marker)
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    private fun addWaypointMarker(wp: Waypoint) {
        val marker = Marker(mapView).apply {
            position = GeoPoint(wp.latitude, wp.longitude)
            title = wp.name
            snippet = wp.notes
            icon = markerIcon(R.drawable.ic_waypoint, Color.parseColor("#00CFFF"))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        waypointMarkers.add(marker)
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    private fun drawAnchorCircle(radiusMeters: Double, center: GeoPoint? = null) {
        clearAnchorCircle()
        val gps = viewModel.gpsData.value
        val c = center ?: if (gps.isValid) GeoPoint(gps.latitude, gps.longitude) else return

        // Construir puntos del círculo como GeoPoints
        val circlePoints = ArrayList<GeoPoint>(73)
        for (i in 0..360 step 5) {
            val angle = Math.toRadians(i.toDouble())
            val dLat = (radiusMeters / 111320.0) * Math.cos(angle)
            val dLon = (radiusMeters / (111320.0 * Math.cos(Math.toRadians(c.latitude)))) * Math.sin(angle)
            circlePoints.add(GeoPoint(c.latitude + dLat, c.longitude + dLon))
        }

        // Usar Polyline (funcionamiento probado con las rutas de navegación)
        val circleLine = Polyline(mapView).apply {
            setPoints(circlePoints)
            outlinePaint.color = Color.rgb(255, 40, 40)
            outlinePaint.strokeWidth = 8f
            outlinePaint.isAntiAlias = true
            outlinePaint.style = Paint.Style.STROKE
        }
        anchorCircleOverlay = circleLine
        mapView.overlays.add(circleLine)

        anchorMarkerOverlay = Marker(mapView).apply {
            position = c
            icon = markerIcon(R.drawable.ic_anchor, Color.parseColor("#FF4444"))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Fondeo (%.0f m)".format(radiusMeters)
        }
        mapView.overlays.add(anchorMarkerOverlay!!)
        mapView.invalidate()
    }

    private fun clearAnchorCircle() {
        anchorCircleOverlay?.let { mapView.overlays.remove(it) }
        anchorCircleOverlay = null
        anchorMarkerOverlay?.let { mapView.overlays.remove(it) }
        anchorMarkerOverlay = null
        mapView.invalidate()
    }

    // ─────────────────────────────────────────────────────────
    // SERVICIO GPS
    // ─────────────────────────────────────────────────────────
    private fun startNavigationService() {
        val intent = Intent(requireContext(), NavigationService::class.java).apply {
            action = NavigationService.ACTION_START
        }
        requireContext().startForegroundService(intent)
    }

    // ─────────────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        rotationSensor?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) mapView.onPause()
        sensorManager?.unregisterListener(sensorListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
