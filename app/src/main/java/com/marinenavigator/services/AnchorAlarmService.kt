package com.marinenavigator.services

import android.app.*
import android.content.Intent
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.marinenavigator.MainActivity
import com.marinenavigator.R
import com.marinenavigator.data.models.AnchorAlarmConfig
import com.marinenavigator.utils.NavigationUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

class AnchorAlarmService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "anchor_alarm_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_ALARM = "ACTION_START_ANCHOR_ALARM"
        const val ACTION_STOP_ALARM = "ACTION_STOP_ANCHOR_ALARM"
        const val EXTRA_CENTER_LAT = "extra_center_lat"
        const val EXTRA_CENTER_LON = "extra_center_lon"
        const val EXTRA_RADIUS = "extra_radius_meters"
    }

    private var config: AnchorAlarmConfig? = null
    private var alarmTriggered = false
    private lateinit var vibrator: Vibrator

    override fun onCreate() {
        super.onCreate()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_ALARM -> {
                val lat = intent.getDoubleExtra(EXTRA_CENTER_LAT, 0.0)
                val lon = intent.getDoubleExtra(EXTRA_CENTER_LON, 0.0)
                val radius = intent.getDoubleExtra(EXTRA_RADIUS, 50.0)
                config = AnchorAlarmConfig(
                    isActive = true,
                    centerLat = lat,
                    centerLon = lon,
                    radiusMeters = radius
                )
                alarmTriggered = false
                startForeground(NOTIFICATION_ID, buildNotification(radius, false))
                startMonitoring()
            }
            ACTION_STOP_ALARM -> {
                stopAlarm()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        lifecycleScope.launch {
            NavigationService.gpsData.collectLatest { gps ->
                val cfg = config ?: return@collectLatest
                if (!gps.isValid) return@collectLatest

                val distance = NavigationUtils.distanceMeters(
                    cfg.centerLat, cfg.centerLon,
                    gps.latitude, gps.longitude
                )

                if (distance > cfg.radiusMeters) {
                    if (!alarmTriggered) {
                        alarmTriggered = true
                        triggerAlarm(distance, cfg.radiusMeters)
                    }
                } else {
                    if (alarmTriggered) {
                        alarmTriggered = false
                        stopAlarm()
                        updateNotification(cfg.radiusMeters, false)
                    }
                }
            }
        }
    }

    private fun triggerAlarm(distance: Double, radius: Double) {
        Timber.w("¡ALARMA DE FONDEO! Distancia: %.0f m (límite: %.0f m)".format(distance, radius))
        // Vibración continua
        val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        // Notificación de alarma
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildAlarmNotification(distance))
    }

    private fun stopAlarm() {
        vibrator.cancel()
    }

    private fun buildNotification(radius: Double, alarming: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (alarming) "⚠️ ¡ALERTA DE FONDEO!" else "Alerta de fondeo activa")
            .setContentText(if (alarming) "La embarcación salió del área de fondeo"
            else "Monitoreando radio: %.0f m".format(radius))
            .setSmallIcon(R.drawable.ic_anchor)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(if (alarming) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildAlarmNotification(distance: Double): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠️ ¡ALERTA DE FONDEO!")
            .setContentText("La embarcación está a %.0f m del punto de fondeo".format(distance))
            .setSmallIcon(R.drawable.ic_anchor)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(alarmUri)
            .build()
    }

    private fun updateNotification(radius: Double, alarming: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(radius, alarming))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alerta de Fondeo",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertas cuando la embarcación sale del área de fondeo"
            enableVibration(true)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }
}
