package com.marinenavigator.services

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.marinenavigator.MainActivity
import com.marinenavigator.R
import com.marinenavigator.data.database.AppDatabase
import com.marinenavigator.data.models.GpsData
import com.marinenavigator.data.models.TrackPoint
import com.marinenavigator.utils.NavigationUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class NavigationService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "navigation_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_NAVIGATION"
        const val ACTION_STOP = "ACTION_STOP_NAVIGATION"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val EXTRA_ROUTE_ID = "extra_route_id"

        // Estado compartido accesible desde cualquier parte de la app
        private val _gpsData = MutableStateFlow(GpsData())
        val gpsData: StateFlow<GpsData> = _gpsData

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _currentRouteId = MutableStateFlow<Long?>(null)
        val currentRouteId: StateFlow<Long?> = _currentRouteId
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var db: AppDatabase? = null
    private var recordingRouteId: Long? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopSelf()
            ACTION_START_RECORDING -> {
                val routeId = intent.getLongExtra(EXTRA_ROUTE_ID, -1L)
                if (routeId != -1L) startRecording(routeId)
            }
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        startForeground(NOTIFICATION_ID, buildNotification("GPS activo"))
        startLocationUpdates()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val speedKnots = NavigationUtils.msToKnots(location.speed)
                    val gps = GpsData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = location.altitude,
                        speedKnots = speedKnots,
                        speedMs = location.speed,
                        bearing = location.bearing,
                        accuracy = location.accuracy,
                        timestamp = location.time,
                        isValid = location.accuracy < 50f
                    )
                    _gpsData.value = gps

                    // Grabar punto si está en modo grabación
                    if (_isRecording.value && recordingRouteId != null) {
                        saveTrackPoint(gps)
                    }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Timber.e(e, "Sin permiso de ubicación")
        }
    }

    private fun startRecording(routeId: Long) {
        recordingRouteId = routeId
        _isRecording.value = true
        _currentRouteId.value = routeId
        updateNotification("Grabando ruta...")
    }

    private fun stopRecording() {
        _isRecording.value = false
        recordingRouteId = null
        _currentRouteId.value = null
        updateNotification("GPS activo")
    }

    private fun saveTrackPoint(gps: GpsData) {
        val routeId = recordingRouteId ?: return
        lifecycleScope.launch {
            try {
                db?.trackPointDao()?.insert(
                    TrackPoint(
                        routeId = routeId,
                        latitude = gps.latitude,
                        longitude = gps.longitude,
                        altitude = gps.altitude,
                        speed = gps.speedMs,
                        bearing = gps.bearing,
                        accuracy = gps.accuracy,
                        timestamp = gps.timestamp
                    )
                )
                // Actualizar distancia en la ruta
                if (lastLat != null && lastLon != null) {
                    val dist = NavigationUtils.distanceMeters(
                        lastLat!!, lastLon!!, gps.latitude, gps.longitude
                    )
                    // Se actualizará en el ViewModel al parar
                }
                lastLat = gps.latitude
                lastLon = gps.longitude
            } catch (e: Exception) {
                Timber.e(e, "Error guardando punto GPS")
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MarineNavigator")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_anchor)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navegación GPS",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Canal para seguimiento GPS activo"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        _isRecording.value = false
        super.onDestroy()
    }
}
