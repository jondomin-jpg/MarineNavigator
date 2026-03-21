package com.marinenavigator.data.database

import androidx.lifecycle.LiveData
import androidx.room.*
import com.marinenavigator.data.models.FishingPoint
import com.marinenavigator.data.models.Route
import com.marinenavigator.data.models.TrackPoint
import com.marinenavigator.data.models.Waypoint

// ─────────────────────────────────────────────────────────────
// DAO - Rutas
// ─────────────────────────────────────────────────────────────
@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY startTime DESC")
    fun getAllRoutes(): LiveData<List<Route>>

    @Query("SELECT * FROM routes WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveRoute(): Route?

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun getRouteById(id: Long): Route?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: Route): Long

    @Update
    suspend fun update(route: Route)

    @Delete
    suspend fun delete(route: Route)

    @Query("UPDATE routes SET isActive = 0")
    suspend fun deactivateAllRoutes()
}

// ─────────────────────────────────────────────────────────────
// DAO - Puntos de ruta (track)
// ─────────────────────────────────────────────────────────────
@Dao
interface TrackPointDao {
    @Query("SELECT * FROM track_points WHERE routeId = :routeId ORDER BY timestamp ASC")
    suspend fun getPointsForRoute(routeId: Long): List<TrackPoint>

    @Query("SELECT * FROM track_points WHERE routeId = :routeId ORDER BY timestamp ASC")
    fun getPointsForRouteLive(routeId: Long): LiveData<List<TrackPoint>>

    @Insert
    suspend fun insert(point: TrackPoint): Long

    @Query("DELETE FROM track_points WHERE routeId = :routeId")
    suspend fun deletePointsForRoute(routeId: Long)

    @Query("SELECT COUNT(*) FROM track_points WHERE routeId = :routeId")
    suspend fun countPointsForRoute(routeId: Long): Int
}

// ─────────────────────────────────────────────────────────────
// DAO - Waypoints / Destinos
// ─────────────────────────────────────────────────────────────
@Dao
interface WaypointDao {
    @Query("SELECT * FROM waypoints ORDER BY name ASC")
    fun getAllWaypoints(): LiveData<List<Waypoint>>

    @Query("SELECT * FROM waypoints ORDER BY name ASC")
    suspend fun getAllWaypointsList(): List<Waypoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(waypoint: Waypoint): Long

    @Update
    suspend fun update(waypoint: Waypoint)

    @Delete
    suspend fun delete(waypoint: Waypoint)
}

// ─────────────────────────────────────────────────────────────
// DAO - Puntos de pesca
// ─────────────────────────────────────────────────────────────
@Dao
interface FishingPointDao {
    @Query("SELECT * FROM fishing_points ORDER BY name ASC")
    fun getAllFishingPoints(): LiveData<List<FishingPoint>>

    @Query("SELECT * FROM fishing_points ORDER BY name ASC")
    suspend fun getAllFishingPointsList(): List<FishingPoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: FishingPoint): Long

    @Update
    suspend fun update(point: FishingPoint)

    @Delete
    suspend fun delete(point: FishingPoint)
}
