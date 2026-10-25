package com.wifishuriken.companion.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DbExportTest {
  @Test
  fun exportContainsRecentRows() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbFile = File(context.cacheDir, "export-test.db").apply { delete() }
    val db = Room.databaseBuilder(context, CompanionDb::class.java, dbFile.absolutePath)
      .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
      .allowMainThreadQueries()
      .build()
    try {
      val text = "WigleWifi-1.4,appRelease=0.2\n" +
        "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n" +
        (1..3).joinToString("") { "AA:BB:CC:DD:EE:0$it,\"n\",[ESS],2026-01-01 12:00:0$it,6,-60,1.0,1.0,10.0,5.0,WIFI\n" }
      Ingestor(db, null).ingestFile("shuriken_20260101120000.csv", text.toByteArray())
      assertTrue("rows should still be in the WAL", File(dbFile.path + "-wal").length() > 0)

      val copy = exportDatabase(db, File(context.cacheDir, "export-out"))
      SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { ro ->
        ro.rawQuery("SELECT COUNT(*) FROM sightings", null).use { c ->
          c.moveToFirst()
          assertEquals(3, c.getInt(0))
        }
      }
    } finally {
      db.close()
    }
  }
}
