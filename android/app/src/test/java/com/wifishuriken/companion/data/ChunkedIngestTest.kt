package com.wifishuriken.companion.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChunkedIngestTest {
  private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDb::class.java).allowMainThreadQueries().build()

  @Test
  fun chunkedIngestMatchesWholeFile() = runBlocking {
    val text = "WigleWifi-1.4,appRelease=0.2\n" +
      "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n" +
      (1..40).joinToString("") { i ->
        "AA:BB:CC:DD:EE:%02X,\"net, %d\",[WPA2-PSK-CCMP][ESS],2026-01-01 12:%02d:00,6,-%d,1.%04d,1.0,10.0,5.0,WIFI\n".format(i % 7, i, i, 40 + i, i)
      } + "AA:BB:CC:DD:EE:09,\"cut" // truncated final line
    val name = "shuriken_20260101120000.csv"
    val whole = db()
    val chunked = db()
    val a = Ingestor(whole, null).ingestFile(name, text.toByteArray())
    val b = Ingestor(chunked, null).ingestStream(name, text.byteInputStream(), chunkBytes = 37)
    assertEquals(a.rowsInserted, b.rowsInserted)
    assertEquals(40, b.rowsInserted)
    for (mac in (0..6).map { "AA:BB:CC:DD:EE:%02X".format(it) }) {
      assertEquals(whole.dao().networkDetail(mac), chunked.dao().networkDetail(mac))
    }
    val t1 = whole.dao().tripByLogName(name)!!
    val t2 = chunked.dao().tripByLogName(name)!!
    assertEquals(t1.copy(id = 0), t2.copy(id = 0))
    whole.close(); chunked.close()
  }
}
