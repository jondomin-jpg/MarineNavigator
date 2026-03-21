package com.marinenavigator.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.marinenavigator.data.models.FishingPoint
import com.marinenavigator.data.models.Route
import com.marinenavigator.data.models.TrackPoint
import com.marinenavigator.data.models.Waypoint

@Database(
    entities = [Route::class, TrackPoint::class, Waypoint::class, FishingPoint::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun waypointDao(): WaypointDao
    abstract fun fishingPointDao(): FishingPointDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "marine_navigator.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
