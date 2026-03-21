package com.marinenavigator.utils

import kotlin.math.*

object NavigationUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    const val MS_TO_KNOTS = 1.94384f
    const val KNOTS_TO_MS = 0.514444f

    // ─────────────────────────────────────────────────────────
    // Distancia entre dos puntos (Haversine) en metros
    // ─────────────────────────────────────────────────────────
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    // ─────────────────────────────────────────────────────────
    // Rumbo inicial entre dos puntos (0-360°, N=0, E=90)
    // ─────────────────────────────────────────────────────────
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val lat1R = Math.toRadians(lat1)
        val lat2R = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(lat2R)
        val x = cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x)).toFloat()
        return (bearing + 360f) % 360f
    }

    // ─────────────────────────────────────────────────────────
    // ETA en segundos dada distancia y velocidad en nudos
    // ─────────────────────────────────────────────────────────
    fun etaSeconds(distanceMeters: Double, speedKnots: Float): Long {
        if (speedKnots < 0.5f) return Long.MAX_VALUE  // sin movimiento
        val speedMs = speedKnots * KNOTS_TO_MS
        return (distanceMeters / speedMs).toLong()
    }

    // ─────────────────────────────────────────────────────────
    // Formatea segundos a string legible "2h 34m" / "45m" / etc
    // ─────────────────────────────────────────────────────────
    fun formatDuration(seconds: Long): String {
        if (seconds == Long.MAX_VALUE || seconds < 0) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    // ─────────────────────────────────────────────────────────
    // Formatea distancia a string legible
    // ─────────────────────────────────────────────────────────
    fun formatDistance(meters: Double): String {
        val nm = meters / 1852.0  // millas náuticas
        return when {
            nm >= 1.0 -> "%.1f mn".format(nm)
            else      -> "%.0f m".format(meters)
        }
    }

    // ─────────────────────────────────────────────────────────
    // Convierte velocidad m/s a nudos
    // ─────────────────────────────────────────────────────────
    fun msToKnots(ms: Float) = ms * MS_TO_KNOTS

    // ─────────────────────────────────────────────────────────
    // Distancia total de una lista de puntos lat/lon
    // ─────────────────────────────────────────────────────────
    fun totalDistanceMeters(points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distanceMeters(
                points[i].first, points[i].second,
                points[i + 1].first, points[i + 1].second
            )
        }
        return total
    }

    // ─────────────────────────────────────────────────────────
    // Formatea coordenadas a grados/minutos/segundos
    // ─────────────────────────────────────────────────────────
    fun formatLatitude(lat: Double): String {
        val dir = if (lat >= 0) "N" else "S"
        val abs = abs(lat)
        val deg = abs.toInt()
        val min = (abs - deg) * 60
        return "%02d°%06.3f'%s".format(deg, min, dir)
    }

    fun formatLongitude(lon: Double): String {
        val dir = if (lon >= 0) "E" else "O"
        val abs = abs(lon)
        val deg = abs.toInt()
        val min = (abs - deg) * 60
        return "%03d°%06.3f'%s".format(deg, min, dir)
    }

    // ─────────────────────────────────────────────────────────
    // Punto medio entre dos coordenadas
    // ─────────────────────────────────────────────────────────
    fun midpoint(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val lat1R = Math.toRadians(lat1)
        val lat2R = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val bx = cos(lat2R) * cos(dLon)
        val by = cos(lat2R) * sin(dLon)
        val lat3 = atan2(
            sin(lat1R) + sin(lat2R),
            sqrt((cos(lat1R) + bx).pow(2) + by.pow(2))
        )
        val lon3 = Math.toRadians(lon1) + atan2(by, cos(lat1R) + bx)
        return Pair(Math.toDegrees(lat3), Math.toDegrees(lon3))
    }

    // ─────────────────────────────────────────────────────────
    // Nombre del rumbo en texto (N, NE, E, ...)
    // ─────────────────────────────────────────────────────────
    fun bearingName(degrees: Float): String {
        val dirs = arrayOf("N","NNE","NE","ENE","E","ESE","SE","SSE",
            "S","SSO","SO","OSO","O","ONO","NO","NNO")
        val idx = ((degrees + 11.25) / 22.5).toInt() % 16
        return dirs[idx]
    }
}
