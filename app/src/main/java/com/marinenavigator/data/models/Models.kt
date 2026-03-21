package com.marinenavigator.data.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
// Punto GPS genérico (usado en rutas grabadas y navegación)
// ─────────────────────────────────────────────────────────────
@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = Route::class,
        parentColumns = ["id"],
        childColumns = ["routeId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("routeId")]
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val speed: Float = 0f,          // m/s
    val bearing: Float = 0f,        // grados 0-360
    val accuracy: Float = 0f,       // metros
    val timestamp: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────
// Ruta grabada
// ─────────────────────────────────────────────────────────────
@Entity(tableName = "routes")
data class Route(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val totalDistanceMeters: Double = 0.0,
    val totalDurationSeconds: Long = 0,
    val maxSpeedKnots: Float = 0f,
    val avgSpeedKnots: Float = 0f,
    val isActive: Boolean = false    // true mientras está grabando
)

// ─────────────────────────────────────────────────────────────
// Waypoint / Destino de navegación
// ─────────────────────────────────────────────────────────────
@Entity(tableName = "waypoints")
data class Waypoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────
// Punto de pesca
// ─────────────────────────────────────────────────────────────
@Entity(tableName = "fishing_points")
data class FishingPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val depth: Double? = null,      // profundidad en metros si se conoce
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val iconColor: Int = 0xFF0077CC.toInt()  // color del marcador
)

// ─────────────────────────────────────────────────────────────
// Configuración de alerta de fondeo
// ─────────────────────────────────────────────────────────────
data class AnchorAlarmConfig(
    val isActive: Boolean = false,
    val centerLat: Double = 0.0,
    val centerLon: Double = 0.0,
    val radiusMeters: Double = 50.0
)

// ─────────────────────────────────────────────────────────────
// Estado de navegación activa (en memoria, no persistido)
// ─────────────────────────────────────────────────────────────
data class NavigationState(
    val isNavigating: Boolean = false,
    val destination: Waypoint? = null,
    val currentLat: Double = 0.0,
    val currentLon: Double = 0.0,
    val speedKnots: Float = 0f,
    val bearingDegrees: Float = 0f,
    val distanceToDestMeters: Double = 0.0,
    val bearingToDestDegrees: Float = 0f,
    val etaSeconds: Long = 0,
    val headingError: Float = 0f    // desviación del rumbo ideal
)

// ─────────────────────────────────────────────────────────────
// Información GPS en tiempo real
// ─────────────────────────────────────────────────────────────
data class GpsData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speedKnots: Float = 0f,
    val speedMs: Float = 0f,
    val bearing: Float = 0f,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val isValid: Boolean = false
)
