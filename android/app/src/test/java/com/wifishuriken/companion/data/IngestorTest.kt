package com.wifishuriken.companion.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wifishuriken.companion.core.CsvRecordSplitter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val PRE = "WigleWifi-1.4,appRelease=0.2,model=WiFi-Shuriken,release=0.2,device=WiFi-Shuriken\n"
private const val COLS = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n"

private fun row(
  mac: String,
  ts: String = "2026-01-01 12:00:00",
  rssi: Int = -70,
  lat: String = "1.0000000",
  lon: String = "1.0000000",
  auth: String = "[WPA2-PSK-CCMP][ESS]",
  channel: Int = 6,
) = "$mac,\"net\",$auth,$ts,$channel,$rssi,$lat,$lon,10.0,5.0,WIFI\n"

@RunWith(AndroidJUnit4::class)
class IngestorTest {
  private lateinit var db: CompanionDb
  private lateinit var ingestor: Ingestor

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDb::class.java)
      .allowMainThreadQueries()
      .build()
    ingestor = Ingestor(db, oui = null)
  }

  @After
  fun tearDown() = db.close()

  private val dao get() = db.dao()

  private fun trip(name: String) = runBlocking { dao.tripByLogName(name)!! }

  @Test
  fun ingestCountsAndClassifies() = runBlocking {
    val text = PRE + COLS + row("AA:BB:CC:DD:EE:01") + row("AA:BB:CC:DD:EE:02") +
      row("AA:BB:CC:DD:EE:01", ts = "2026-01-01 12:00:05") + row("AA:BB:CC:DD:EE:03", rssi = 5)
    val r = ingestor.ingestFile("shuriken_20260101120000.csv", text.toByteArray())
    assertEquals(3, r.rowsInserted)
    assertEquals(1, r.rejected)
    assertEquals("WPA2", dao.networkDetail("AA:BB:CC:DD:EE:01")!!.security)
    val t = trip("shuriken_20260101120000.csv")
    assertEquals(3, t.rows)
    assertEquals(1, t.rejected)
    assertEquals(2, t.uniqueMacs)
    assertEquals("2026-01-01 12:00:00", t.startUtc)
  }

  @Test
  fun liveThenFinishedCountsOnce() = runBlocking {
    val name = "shuriken_20260101120000.csv"
    val text = PRE + COLS + row("AA:BB:CC:DD:EE:01") + row("AA:BB:CC:DD:EE:02") + row("AA:BB:CC:DD:EE:03")
    val bytes = text.toByteArray()
    val cut = (PRE + COLS + row("AA:BB:CC:DD:EE:01")).length + 7 // in the middle of the second row
    val splitter = CsvRecordSplitter()
    ingestor.ingestRecords(name, splitter.feed(bytes, 0, cut))
    ingestor.ingestRecords(name, splitter.feed(bytes, cut, bytes.size - cut))
    ingestor.ingestFile(name, bytes)
    val t = trip(name)
    assertEquals(3, dao.sightingCountForTrip(t.id))
    assertEquals(3, t.rows)
  }

  @Test
  fun reimportSameFileNoDuplicates() = runBlocking {
    val name = "shuriken_20260101120000.csv"
    val text = PRE + COLS + row("AA:BB:CC:DD:EE:01") + row("AA:BB:CC:DD:EE:02") + row("AA:BB:CC:DD:EE:03", rssi = 9)
    ingestor.ingestFile(name, text.toByteArray())
    ingestor.ingestFile(name, text.toByteArray())
    val t = trip(name)
    assertEquals(2, dao.sightingCountForTrip(t.id))
    assertEquals(2, t.rows)
    assertEquals(1, t.rejected)
  }

  @Test
  fun queriesUseIndexes() = runBlocking {
    ingestor.ingestFile("shuriken_20260101120000.csv", (PRE + COLS + row("AA:BB:CC:DD:EE:01")).toByteArray())
    val sql = db.openHelper.readableDatabase
    fun plan(query: String, vararg args: Any?): String {
      sql.query("EXPLAIN QUERY PLAN $query", args).use { c ->
        val out = StringBuilder()
        while (c.moveToNext()) out.append(c.getString(c.getColumnIndexOrThrow("detail"))).append('\n')
        return out.toString()
      }
    }
    val networks = buildNetworksQuery(NetworkFilter(), NetworkSort.MOST_SEEN, 100, 0)
    for (p in listOf(
      plan(CompanionDao.SQL_TRIPS_BY_START_DESC),
      plan(CompanionDao.SQL_SIGHTINGS_FOR, "AA:BB:CC:DD:EE:01"),
      plan(networks.sql, *networks.args),
    )) {
      assertFalse("full scan of sightings: $p", p.contains("SCAN sightings"))
    }
  }

  @Test
  fun ingestTruncatedAndHeaderOnlyFiles() = runBlocking {
    val truncated = PRE + COLS + row("AA:BB:CC:DD:EE:01") + row("AA:BB:CC:DD:EE:02").trimEnd()
    ingestor.ingestFile("shuriken_20260101120000.csv", truncated.toByteArray())
    assertEquals(1, trip("shuriken_20260101120000.csv").rows)
    ingestor.ingestFile("shuriken_20260102120000.csv", (PRE + COLS).toByteArray())
    assertEquals(0, trip("shuriken_20260102120000.csv").rows)
  }

  @Test
  fun olderLogImportedLaterShiftsNewCredit() = runBlocking {
    val mac = "AA:BB:CC:DD:EE:01"
    ingestor.ingestFile("shuriken_20260102000000.csv", (PRE + COLS + row(mac, ts = "2026-01-02 00:00:10")).toByteArray())
    val b = trip("shuriken_20260102000000.csv")
    assertEquals(1, dao.newCountForTrip(b.id))
    ingestor.ingestFile("shuriken_20260101000000.csv", (PRE + COLS + row(mac, ts = "2026-01-01 00:00:10")).toByteArray())
    val a = trip("shuriken_20260101000000.csv")
    assertEquals(a.id, dao.networkDetail(mac)!!.firstTripId)
    assertEquals(0, dao.newCountForTrip(b.id))
    assertEquals(0, trip("shuriken_20260102000000.csv").newCount)
    assertEquals(1, trip("shuriken_20260101000000.csv").newCount)
    assertTrue(dao.isKnownBefore(mac, b.startUtc))
    assertFalse(dao.isKnownBefore(mac, a.startUtc))
  }

  @Test
  fun positionEstimateStored() = runBlocking {
    val text = PRE + COLS + row("AA:BB:CC:DD:EE:01", rssi = -50) +
      row("AA:BB:CC:DD:EE:01", ts = "2026-01-01 12:00:05", rssi = -60, lon = "1.0005000")
    ingestor.ingestFile("shuriken_20260101120000.csv", text.toByteArray())
    val n = dao.networkDetail("AA:BB:CC:DD:EE:01")
    assertNotNull(n)
    assertEquals("RSSI_CENTROID", n!!.estMethod)
    assertEquals(2, n.sightings)
    assertEquals(1, n.trips)
  }
}
