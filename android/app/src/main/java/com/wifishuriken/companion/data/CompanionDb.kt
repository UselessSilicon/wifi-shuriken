package com.wifishuriken.companion.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    TripEntity::class, SightingEntity::class, RejectedRowEntity::class, NetworkEntity::class,
    TrackPointEntity::class, ArchivedFileEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
abstract class CompanionDb : RoomDatabase() {
  abstract fun dao(): CompanionDao

  companion object {
    const val FILE_NAME = "companion.db"

    @Volatile private var instance: CompanionDb? = null

    fun get(context: Context): CompanionDb = instance ?: synchronized(this) {
      instance ?: Room.databaseBuilder(context.applicationContext, CompanionDb::class.java, FILE_NAME).build()
        .also { instance = it }
    }
  }
}
