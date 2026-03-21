package com.marinenavigator.ui.map

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.marinenavigator.data.database.AppDatabase
import com.marinenavigator.data.models.*
import com.marinenavigator.services.AnchorAlarmService
import com.marinenavigator.services.NavigationService
import com.marinenavigator.utils.NavigationUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val context = application

    // GPS en tiempo real (del servicio)
    val gpsData: StateFlow<GpsData> = NavigationService.gpsData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GpsData())

    val isRecording: StateFlow<Boolean> = NavigationService.isRecording
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Rutas guardadas
    val allRoutes: LiveData<List<Route>> = db.routeDao().getAllRoutes()

    // Puntos de pesca
    val fishingPoints: LiveData<List<FishingPoint>> = db.fishingPointDao().getAllFishingPoints()

    // Waypoints guardados
    val waypoints: LiveData<List<Waypoint>> = db.waypointDao().getAllWaypoints()

    // Estado de navegación activa
    private val _navigationState = MutableLiveData<NavigationState>()
    val navigationState: LiveData<NavigationState> = _navigationState

    // Alerta de fondeo
    private val _anchorAlarmConfig = MutableLiveData<AnchorAlarmConfig>()
    val anchorAlarmConfig: LiveData<AnchorAlarmConfig> = _anchorAlarmConfig

    // Puntos del track activo (en memoria para mostrar en el mapa)
    private val _currentTrackPoints = MutableLiveData<List<TrackPoint>>(emptyList())
    val currentTrackPoints: LiveData<List<TrackPoint>> = _currentTrackPoints

    private var activeRouteId: Long? = null
    private var recordingStartTime: Long = 0

    // ─────────────────────────────────────────────────────────
    // Actualizar estado de navegación al cambiar el GPS
    // ─────────────────────────────────────────────────────────
    init {
        viewModelScope.launch {
            NavigationService.gpsData.collect { gps ->
                val current = _navigationState.value ?: return@collect
                if (current.isNavigating && current.destination != null && gps.isValid) {
                    val dist = NavigationUtils.distanceMeters(
                        gps.latitude, gps.longitude,
                        current.destination.latitude, current.destination.longitude
                    )
                    val bearing = NavigationUtils.bearingDegrees(
                        gps.latitude, gps.longitude,
                        current.destination.latitude, current.destination.longitude
                    )
                    val eta = NavigationUtils.etaSeconds(dist, gps.speedKnots)
                    val headingError = ((bearing - gps.bearing + 540) % 360) - 180
                    _navigationState.postValue(
                        current.copy(
                            currentLat = gps.latitude,
                            currentLon = gps.longitude,
                            speedKnots = gps.speedKnots,
                            bearingDegrees = gps.bearing,
                            distanceToDestMeters = dist,
                            bearingToDestDegrees = bearing,
                            etaSeconds = eta,
                            headingError = headingError
                        )
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // GRABACIÓN DE RUTAS
    // ─────────────────────────────────────────────────────────
    fun startRecording(name: String) {
        viewModelScope.launch {
            db.routeDao().deactivateAllRoutes()
            val route = Route(
                name = name.ifBlank {
                    "Ruta ${SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
                        .format(Date())}"
                },
                isActive = true,
                startTime = System.currentTimeMillis()
            )
            val routeId = db.routeDao().insert(route)
            activeRouteId = routeId
            recordingStartTime = System.currentTimeMillis()

            // Iniciar servicio de grabación
            val intent = Intent(context, NavigationService::class.java).apply {
                action = NavigationService.ACTION_START_RECORDING
                putExtra(NavigationService.EXTRA_ROUTE_ID, routeId)
            }
            context.startService(intent)
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            val routeId = activeRouteId ?: return@launch
            val points = db.trackPointDao().getPointsForRoute(routeId)

            // Calcular estadísticas
            val pairs = points.map { Pair(it.latitude, it.longitude) }
            val totalDist = NavigationUtils.totalDistanceMeters(pairs)
            val durationSec = (System.currentTimeMillis() - recordingStartTime) / 1000
            val avgSpeed = if (durationSec > 0)
                NavigationUtils.msToKnots((totalDist / durationSec).toFloat()) else 0f
            val maxSpeed = points.maxOfOrNull {
                NavigationUtils.msToKnots(it.speed)
            } ?: 0f

            val route = db.routeDao().getRouteById(routeId)?.copy(
                isActive = false,
                endTime = System.currentTimeMillis(),
                totalDistanceMeters = totalDist,
                totalDurationSeconds = durationSec,
                avgSpeedKnots = avgSpeed,
                maxSpeedKnots = maxSpeed
            )
            route?.let { db.routeDao().update(it) }
            activeRouteId = null

            val intent = Intent(context, NavigationService::class.java).apply {
                action = NavigationService.ACTION_STOP_RECORDING
            }
            context.startService(intent)
            _currentTrackPoints.postValue(emptyList())
        }
    }

    fun loadRouteOnMap(routeId: Long) {
        viewModelScope.launch {
            val points = db.trackPointDao().getPointsForRoute(routeId)
            _currentTrackPoints.postValue(points)
        }
    }

    fun deleteRoute(route: Route) {
        viewModelScope.launch { db.routeDao().delete(route) }
    }

    // ─────────────────────────────────────────────────────────
    // NAVEGACIÓN
    // ─────────────────────────────────────────────────────────
    fun startNavigationTo(destination: Waypoint) {
        val gps = gpsData.value
        val dist = if (gps.isValid) NavigationUtils.distanceMeters(
            gps.latitude, gps.longitude, destination.latitude, destination.longitude
        ) else 0.0
        val bearing = if (gps.isValid) NavigationUtils.bearingDegrees(
            gps.latitude, gps.longitude, destination.latitude, destination.longitude
        ) else 0f
        _navigationState.value = NavigationState(
            isNavigating = true,
            destination = destination,
            currentLat = gps.latitude,
            currentLon = gps.longitude,
            speedKnots = gps.speedKnots,
            bearingDegrees = gps.bearing,
            distanceToDestMeters = dist,
            bearingToDestDegrees = bearing,
            etaSeconds = NavigationUtils.etaSeconds(dist, gps.speedKnots)
        )
    }

    fun stopNavigation() {
        _navigationState.value = NavigationState(isNavigating = false)
    }

    // ─────────────────────────────────────────────────────────
    // WAYPOINTS
    // ─────────────────────────────────────────────────────────
    fun saveWaypoint(name: String, lat: Double, lon: Double, notes: String = "") {
        viewModelScope.launch {
            db.waypointDao().insert(Waypoint(name = name, latitude = lat, longitude = lon, notes = notes))
        }
    }

    fun deleteWaypoint(waypoint: Waypoint) {
        viewModelScope.launch { db.waypointDao().delete(waypoint) }
    }

    // ─────────────────────────────────────────────────────────
    // PUNTOS DE PESCA
    // ─────────────────────────────────────────────────────────
    fun saveFishingPoint(name: String, lat: Double, lon: Double, notes: String = "", depth: Double? = null) {
        viewModelScope.launch {
            db.fishingPointDao().insert(
                FishingPoint(name = name, latitude = lat, longitude = lon, notes = notes, depth = depth)
            )
        }
    }

    fun deleteFishingPoint(point: FishingPoint) {
        viewModelScope.launch { db.fishingPointDao().delete(point) }
    }

    // ─────────────────────────────────────────────────────────
    // ALERTA DE FONDEO
    // ─────────────────────────────────────────────────────────
    fun activateAnchorAlarm(radiusMeters: Double) {
        val gps = gpsData.value
        if (!gps.isValid) return
        val cfg = AnchorAlarmConfig(
            isActive = true,
            centerLat = gps.latitude,
            centerLon = gps.longitude,
            radiusMeters = radiusMeters
        )
        _anchorAlarmConfig.value = cfg

        val intent = Intent(context, AnchorAlarmService::class.java).apply {
            action = AnchorAlarmService.ACTION_START_ALARM
            putExtra(AnchorAlarmService.EXTRA_CENTER_LAT, gps.latitude)
            putExtra(AnchorAlarmService.EXTRA_CENTER_LON, gps.longitude)
            putExtra(AnchorAlarmService.EXTRA_RADIUS, radiusMeters)
        }
        context.startForegroundService(intent)
    }

    fun deactivateAnchorAlarm() {
        _anchorAlarmConfig.value = AnchorAlarmConfig(isActive = false)
        context.startService(Intent(context, AnchorAlarmService::class.java).apply {
            action = AnchorAlarmService.ACTION_STOP_ALARM
        })
    }
}
