package com.wifishuriken.companion.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrivesDaoTest {
  private lateinit var db: CompanionDb

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDb::class.java)
      .allowMainThreadQueries().build()
  }

  @After
  fun tearDown() = db.close()

  private fun row(mac: String, ts: String, lat: String = "1.0000000") =
    "$mac,\"net\",[WPA2-PSK-CCMP][ESS],$ts,6,-60,$lat,1.0000000,10.0,5.0,WIFI\n"

  @Test
  fun tripSummaryFields() = runBlocking {
    val text = "WigleWifi-1.4,appRelease=0.2\nMAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n" +
      row("AA:BB:CC:DD:EE:01", "2026-01-01 12:00:10") +
      row("AA:BB:CC:DD:EE:02", "2026-01-01 12:00:20", lat = "") +
      row("AA:BB:CC:DD:EE:01", "2026-01-01 12:30:00", lat = "1.0010000") +
      row("AA:BB:CC:DD:EE:03", "2026-01-01 12:45:00")
    Ingestor(db, null).ingestFile("shuriken_20260101120000.csv", text.toByteArray())
    val t = db.dao().tripsByStartDesc().first().single()
    assertEquals("2026-01-01 12:00:00", t.startUtc)
    assertEquals("2026-01-01 12:45:00", t.endUtc)
    assertEquals(4, t.rows)
    assertEquals(3, t.uniqueMacs)
    assertEquals(3, t.positionedRows)
    assertEquals(3, t.newCount)
    val positions = db.dao().positionsForTrip(t.id)
    assertEquals(3, positions.size)
    assertEquals("2026-01-01 12:00:10", positions.first().tsUtc)
    db.dao().setTripMode(t.id, "WALK")
    assertEquals("WALK", db.dao().tripsByStartDesc().first().single().mode)
  }
}
