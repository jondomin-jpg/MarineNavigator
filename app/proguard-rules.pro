# OSMDroid
-keep class org.osmdroid.** { *; }
# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
# Models
-keep class com.marinenavigator.data.models.** { *; }
