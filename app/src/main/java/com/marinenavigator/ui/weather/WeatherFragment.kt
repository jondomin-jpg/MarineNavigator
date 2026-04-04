package com.marinenavigator.ui.weather

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.marinenavigator.databinding.FragmentWeatherBinding
import com.marinenavigator.ui.map.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class WeatherFragment : Fragment() {

    private var _binding: FragmentWeatherBinding? = null
    private val binding get() = _binding!!
    private val mapViewModel: MapViewModel by activityViewModels()

    // ─────────────────────────────────────────────────────────
    // Data models
    // ─────────────────────────────────────────────────────────
    data class TideExtreme(
        val dt: Long,
        val height: Double,
        val type: String   // "High" or "Low"
    )

    data class MarineHour(
        val timeLabel: String,
        val waveHeight: Double,
        val wavePeriod: Double,
        val waveDirection: Double,
        val swellHeight: Double,
        val swellDirection: Double
    )

    data class MarineData(
        val currentWaveHeight: Double,
        val currentWavePeriod: Double,
        val currentWaveDirection: Double,
        val currentSwellHeight: Double,
        val currentSwellPeriod: Double,
        val currentSwellDirection: Double,
        val hourlyForecast: List<MarineHour>
    )

    data class TideData(
        val currentHeight: Double,
        val isRising: Boolean,
        val nextHigh: TideExtreme?,
        val nextLow: TideExtreme?
    )

    // ─────────────────────────────────────────────────────────
    // Fragment lifecycle
    // ─────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWeatherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRetry.setOnClickListener { loadAllData() }
        binding.btnSaveKey.setOnClickListener { saveApiKey() }

        loadAllData()
    }

    override fun onResume() {
        super.onResume()
        loadAllData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────
    private fun loadAllData() {
        val gps = mapViewModel.gpsData.value
        if (!gps.isValid || (gps.latitude == 0.0 && gps.longitude == 0.0)) {
            showGpsWaiting()
            return
        }
        val lat = gps.latitude
        val lon = gps.longitude

        showLoading("Cargando datos meteorológicos...")
        loadMarineData(lat, lon)
        loadTideData(lat, lon)
    }

    // ─────────────────────────────────────────────────────────
    // Open-Meteo Marine API
    // ─────────────────────────────────────────────────────────
    private fun loadMarineData(lat: Double, lon: Double) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { fetchMarineData(lat, lon) }
                if (_binding == null) return@launch
                updateMarineUI(data)
            } catch (e: Exception) {
                Timber.e(e, "Error loading marine data")
                if (_binding == null) return@launch
                showMarineError("Error al cargar parte de mar: ${e.message}")
            }
        }
    }

    private fun fetchMarineData(lat: Double, lon: Double): MarineData {
        val url = "https://marine-api.open-meteo.com/v1/marine" +
            "?latitude=$lat&longitude=$lon" +
            "&hourly=wave_height,wave_direction,wave_period,swell_wave_height," +
            "swell_wave_direction,swell_wave_period" +
            "&timezone=auto&forecast_days=2"

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "MarineNavigator/1.0")

        return try {
            val json = conn.inputStream.bufferedReader().readText()
            parseMarineResponse(json)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseMarineResponse(json: String): MarineData {
        val root = JSONObject(json)
        val hourly = root.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val waveHeights = hourly.getJSONArray("wave_height")
        val wavePeriods = hourly.getJSONArray("wave_period")
        val waveDirections = hourly.getJSONArray("wave_direction")
        val swellHeights = hourly.getJSONArray("swell_wave_height")
        val swellDirections = hourly.getJSONArray("swell_wave_direction")
        val swellPeriods = hourly.getJSONArray("swell_wave_period")

        // Find current hour index
        val nowCal = Calendar.getInstance()
        val nowHour = String.format(
            Locale.getDefault(), "%04d-%02d-%02dT%02d:00",
            nowCal.get(Calendar.YEAR),
            nowCal.get(Calendar.MONTH) + 1,
            nowCal.get(Calendar.DAY_OF_MONTH),
            nowCal.get(Calendar.HOUR_OF_DAY)
        )

        var currentIdx = 0
        for (i in 0 until times.length()) {
            if (times.getString(i) == nowHour) { currentIdx = i; break }
        }

        fun safeDouble(arr: org.json.JSONArray, idx: Int): Double =
            if (arr.isNull(idx)) 0.0 else arr.optDouble(idx, 0.0)

        // Build hourly forecast for next 12 hours
        val hourlyForecast = mutableListOf<MarineHour>()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())

        for (i in currentIdx until minOf(currentIdx + 13, times.length())) {
            val timeStr = times.getString(i)
            val label = try {
                val d = dateFmt.parse(timeStr)
                if (d != null) timeFmt.format(d) else timeStr.takeLast(5)
            } catch (e: Exception) { timeStr.takeLast(5) }

            hourlyForecast.add(
                MarineHour(
                    timeLabel = label,
                    waveHeight = safeDouble(waveHeights, i),
                    wavePeriod = safeDouble(wavePeriods, i),
                    waveDirection = safeDouble(waveDirections, i),
                    swellHeight = safeDouble(swellHeights, i),
                    swellDirection = safeDouble(swellDirections, i)
                )
            )
        }

        return MarineData(
            currentWaveHeight = safeDouble(waveHeights, currentIdx),
            currentWavePeriod = safeDouble(wavePeriods, currentIdx),
            currentWaveDirection = safeDouble(waveDirections, currentIdx),
            currentSwellHeight = safeDouble(swellHeights, currentIdx),
            currentSwellPeriod = safeDouble(swellPeriods, currentIdx),
            currentSwellDirection = safeDouble(swellDirections, currentIdx),
            hourlyForecast = hourlyForecast
        )
    }

    // ─────────────────────────────────────────────────────────
    // WorldTides API
    // ─────────────────────────────────────────────────────────
    private fun loadTideData(lat: Double, lon: Double) {
        val apiKey = getWorldTidesKey()
        if (apiKey.isNullOrBlank()) {
            showNoKeyPanel()
            return
        }

        binding.layoutTideLoading.visibility = View.VISIBLE
        binding.layoutTideData.visibility = View.GONE
        binding.tvTideError.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { fetchTideData(lat, lon, apiKey) }
                if (_binding == null) return@launch
                updateTideUI(data)
            } catch (e: Exception) {
                Timber.e(e, "Error loading tide data")
                if (_binding == null) return@launch
                showTideError("Error al cargar mareas: ${e.message}")
            }
        }
    }

    private fun fetchTideData(lat: Double, lon: Double, apiKey: String): TideData {
        val now = System.currentTimeMillis() / 1000
        val url = "https://www.worldtides.info/api/v3" +
            "?extremes&heights" +
            "&lat=$lat&lon=$lon" +
            "&key=$apiKey" +
            "&start=$now" +
            "&length=172800"

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "MarineNavigator/1.0")

        return try {
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                throw Exception("HTTP $responseCode")
            }
            val json = conn.inputStream.bufferedReader().readText()
            parseTideResponse(json, now)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTideResponse(json: String, nowSeconds: Long): TideData {
        val root = JSONObject(json)

        // Parse extremes
        val extremesArr = root.optJSONArray("extremes")
        val extremes = mutableListOf<TideExtreme>()
        if (extremesArr != null) {
            for (i in 0 until extremesArr.length()) {
                val e = extremesArr.getJSONObject(i)
                extremes.add(
                    TideExtreme(
                        dt = e.getLong("dt"),
                        height = e.getDouble("height"),
                        type = e.getString("type")
                    )
                )
            }
        }

        // Parse heights for current level
        val heightsArr = root.optJSONArray("heights")
        var currentHeight = 0.0
        var prevHeight = 0.0

        if (heightsArr != null && heightsArr.length() > 0) {
            // Find entry closest to now
            var bestIdx = 0
            var bestDiff = Long.MAX_VALUE
            for (i in 0 until heightsArr.length()) {
                val h = heightsArr.getJSONObject(i)
                val dt = h.getLong("dt")
                val diff = Math.abs(dt - nowSeconds)
                if (diff < bestDiff) { bestDiff = diff; bestIdx = i }
            }
            currentHeight = heightsArr.getJSONObject(bestIdx).getDouble("height")
            if (bestIdx > 0) {
                prevHeight = heightsArr.getJSONObject(bestIdx - 1).getDouble("height")
            } else if (heightsArr.length() > 1) {
                prevHeight = heightsArr.getJSONObject(1).getDouble("height")
            }
        }

        val isRising = currentHeight >= prevHeight

        // Find next high and low
        val futureExtremes = extremes.filter { it.dt >= nowSeconds }
        val nextHigh = futureExtremes.firstOrNull { it.type == "High" }
        val nextLow = futureExtremes.firstOrNull { it.type == "Low" }

        return TideData(
            currentHeight = currentHeight,
            isRising = isRising,
            nextHigh = nextHigh,
            nextLow = nextLow
        )
    }

    // ─────────────────────────────────────────────────────────
    // UI update helpers
    // ─────────────────────────────────────────────────────────
    private fun updateMarineUI(data: MarineData) {
        binding.layoutMarineLoading.visibility = View.GONE
        binding.tvMarineError.visibility = View.GONE
        binding.layoutMarineData.visibility = View.VISIBLE

        binding.tvWaveHeight.text = "%.1f m".format(data.currentWaveHeight)
        binding.tvWavePeriod.text = "%.0f s".format(data.currentWavePeriod)
        binding.tvWaveDirection.text = bearingToText(data.currentWaveDirection)
        binding.tvSwellHeight.text = "%.1f m".format(data.currentSwellHeight)
        binding.tvSwellPeriod.text = "%.0f s".format(data.currentSwellPeriod)
        binding.tvSwellDirection.text = bearingToText(data.currentSwellDirection)

        // Build hourly rows
        binding.layoutHourlyRows.removeAllViews()
        val context = requireContext()
        val inflater = LayoutInflater.from(context)

        for (hour in data.hourlyForecast) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 2.dpToPx() }
                setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
            }

            val waveColor = waveHeightColor(hour.waveHeight)

            rowLayout.addView(makeCell(context, hour.timeLabel, Color.WHITE, 1))
            rowLayout.addView(makeCell(context, "%.1f m".format(hour.waveHeight), waveColor, 1, gravity = android.view.Gravity.CENTER))
            rowLayout.addView(makeCell(context, "%.1f m".format(hour.swellHeight), Color.parseColor("#00BCD4"), 1, gravity = android.view.Gravity.CENTER))
            rowLayout.addView(makeCell(context, bearingToText(hour.waveDirection), Color.parseColor("#88AABB"), 1, gravity = android.view.Gravity.END))

            binding.layoutHourlyRows.addView(rowLayout)
        }

        updateLastUpdated()
        showContent()
    }

    private fun makeCell(
        context: android.content.Context,
        text: String,
        color: Int,
        weight: Int,
        gravity: Int = android.view.Gravity.START
    ): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight.toFloat()
            )
            this.gravity = gravity
        }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    private fun updateTideUI(data: TideData) {
        binding.layoutTideLoading.visibility = View.GONE
        binding.layoutNoKey.visibility = View.GONE
        binding.tvTideError.visibility = View.GONE
        binding.layoutTideData.visibility = View.VISIBLE
        binding.btnConfigureKey.visibility = View.VISIBLE

        binding.tvCurrentTide.text = "%.1f m".format(data.currentHeight)
        binding.tvTideDirection.text = if (data.isRising) "↑" else "↓"
        binding.tvTideDirection.setTextColor(
            if (data.isRising) Color.parseColor("#4CAF50") else Color.parseColor("#2196F3")
        )

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        if (data.nextHigh != null) {
            val time = timeFmt.format(Date(data.nextHigh.dt * 1000))
            binding.tvNextHighTime.text = time
            binding.tvNextHighHeight.text = "%.1f m".format(data.nextHigh.height)
        } else {
            binding.tvNextHighTime.text = "--:--"
            binding.tvNextHighHeight.text = "-- m"
        }

        if (data.nextLow != null) {
            val time = timeFmt.format(Date(data.nextLow.dt * 1000))
            binding.tvNextLowTime.text = time
            binding.tvNextLowHeight.text = "%.1f m".format(data.nextLow.height)
        } else {
            binding.tvNextLowTime.text = "--:--"
            binding.tvNextLowHeight.text = "-- m"
        }

        binding.btnConfigureKey.setOnClickListener {
            binding.layoutTideData.visibility = View.GONE
            binding.layoutNoKey.visibility = View.VISIBLE
        }
    }

    // ─────────────────────────────────────────────────────────
    // API key management
    // ─────────────────────────────────────────────────────────
    private fun getWorldTidesKey(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val key = prefs.getString("worldtides_api_key", null)
        return if (key.isNullOrBlank()) null else key
    }

    private fun saveApiKey() {
        val key = binding.etApiKey.text?.toString()?.trim() ?: ""
        if (key.isBlank()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit().putString("worldtides_api_key", key).apply()

        binding.layoutNoKey.visibility = View.GONE
        binding.layoutTideLoading.visibility = View.VISIBLE

        val gps = mapViewModel.gpsData.value
        if (gps.isValid) {
            loadTideData(gps.latitude, gps.longitude)
        }
    }

    // ─────────────────────────────────────────────────────────
    // Visibility state helpers
    // ─────────────────────────────────────────────────────────
    private fun showLoading(message: String) {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.tvLoadingMessage.text = message
        binding.layoutError.visibility = View.GONE
        binding.layoutGpsWaiting.visibility = View.GONE
        binding.scrollContent.visibility = View.GONE
    }

    private fun showContent() {
        binding.layoutLoading.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.layoutGpsWaiting.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE
    }

    private fun showGpsWaiting() {
        binding.layoutLoading.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.layoutGpsWaiting.visibility = View.VISIBLE
        binding.scrollContent.visibility = View.GONE
    }

    private fun showNoKeyPanel() {
        binding.layoutTideLoading.visibility = View.GONE
        binding.layoutTideData.visibility = View.GONE
        binding.tvTideError.visibility = View.GONE
        binding.layoutNoKey.visibility = View.VISIBLE
        showContent()
    }

    private fun showMarineError(message: String) {
        binding.layoutMarineLoading.visibility = View.GONE
        binding.layoutMarineData.visibility = View.GONE
        binding.tvMarineError.visibility = View.VISIBLE
        binding.tvMarineError.text = message
        showContent()
    }

    private fun showTideError(message: String) {
        binding.layoutTideLoading.visibility = View.GONE
        binding.layoutTideData.visibility = View.GONE
        binding.layoutNoKey.visibility = View.GONE
        binding.tvTideError.visibility = View.VISIBLE
        binding.tvTideError.text = message
    }

    private fun updateLastUpdated() {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.tvLastUpdated.text = "Actualizado: ${timeFmt.format(Date())}"
        binding.tvLastUpdated.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────────────────
    // Utility: bearing degrees → compass text (Spanish)
    // ─────────────────────────────────────────────────────────
    private fun bearingToText(degrees: Double): String {
        val normalized = ((degrees % 360) + 360) % 360
        return when {
            normalized < 22.5  -> "N"
            normalized < 67.5  -> "NE"
            normalized < 112.5 -> "E"
            normalized < 157.5 -> "SE"
            normalized < 202.5 -> "S"
            normalized < 247.5 -> "SO"
            normalized < 292.5 -> "O"
            normalized < 337.5 -> "NO"
            else               -> "N"
        }
    }

    // ─────────────────────────────────────────────────────────
    // Utility: wave height → Beaufort color
    // ─────────────────────────────────────────────────────────
    private fun waveHeightColor(heightM: Double): Int {
        // Approximate Beaufort from wave height
        return when {
            heightM < 0.5  -> Color.parseColor("#4CAF50")   // 0-3 green
            heightM < 1.25 -> Color.parseColor("#4CAF50")   // 3-4 green
            heightM < 2.5  -> Color.parseColor("#FFEB3B")   // 4-5 yellow
            heightM < 4.0  -> Color.parseColor("#FF9800")   // 6-7 orange
            else           -> Color.parseColor("#FF5722")   // 8+ red
        }
    }
}
