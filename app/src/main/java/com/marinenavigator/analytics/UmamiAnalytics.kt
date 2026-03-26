package com.marinenavigator.analytics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente de Umami Analytics para eventos de la app.
 *
 * Configuración:
 *  1. Crea un website en tu instancia de Umami
 *  2. Sustituye SERVER_URL con la URL de tu servidor Umami
 *  3. Sustituye WEBSITE_ID con el ID del website en Umami
 *
 * Doc API: https://umami.is/docs/api/sending-stats
 */
object UmamiAnalytics {

    // ── CONFIGURA ESTOS DOS VALORES ───────────────────────────
    private const val SERVER_URL = "https://YOUR_UMAMI_URL"   // ej: https://analytics.tudominio.com
    private const val WEBSITE_ID = "YOUR_WEBSITE_ID"          // ej: a1b2c3d4-e5f6-...
    // ─────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO)

    /** Nombres de eventos estándar de la app */
    object Event {
        const val APP_OPEN              = "app_open"
        const val ROUTE_CALCULATED      = "route_calculated"
        const val WAYPOINT_SAVED        = "waypoint_saved"
        const val FISHING_POINT_SAVED   = "fishing_point_saved"
        const val ANCHOR_ALARM_ON       = "anchor_alarm_activated"
        const val ANCHOR_ALARM_OFF      = "anchor_alarm_deactivated"
        const val TRACK_STARTED         = "track_started"
        const val TRACK_STOPPED         = "track_stopped"
    }

    /**
     * Envía un evento a Umami. Las llamadas son fire-and-forget:
     * nunca bloquean la UI ni propagan errores.
     *
     * @param event  Nombre del evento (usar constantes de [Event])
     * @param props  Propiedades adicionales opcionales (clave-valor)
     */
    fun track(event: String, props: Map<String, String> = emptyMap()) {
        scope.launch {
            runCatching { send(event, props) }
                .onFailure { Timber.w(it, "Umami: error enviando evento '$event'") }
        }
    }

    private fun send(event: String, props: Map<String, String>) {
        val payload = JSONObject().apply {
            put("website", WEBSITE_ID)
            put("url", "/$event")
            put("hostname", "marinenavigator")
            put("name", event)
            if (props.isNotEmpty()) {
                put("data", JSONObject(props as Map<*, *>))
            }
        }
        val body = JSONObject().apply {
            put("type", "event")
            put("payload", payload)
        }.toString().toByteArray()

        val conn = URL("$SERVER_URL/api/send").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", "MarineNavigator/1.0")
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            Timber.d("Umami '$event' → HTTP $code")
        } finally {
            conn.disconnect()
        }
    }
}
