package com.wifishuriken.companion.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wifishuriken.companion.core.Band
import com.wifishuriken.companion.core.SecurityClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val HEAD = "WigleWifi-1.4,appRelease=0.2\n" +
  "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n"

private fun row(mac: String, ssid: String, auth: String, channel: Int, rssi: Int, ts: String = "2026-01-01 12:00:10") =
  "$mac,\"$ssid\",$auth,$ts,$channel,$rssi,1.0000000,1.0000000,10.0,5.0,WIFI\n"

private const val WPA2 = "[WPA2-PSK-CCMP][ESS]"
private const val WPA3 = "[RSN-PSK-CCMP][ESS]" // firmware logs WPA3 as RSN
private const val OPEN = "[ESS]"

@RunWith(AndroidJUnit4::class)
class NetworksDaoTest {
  private lateinit var db: CompanionDb
  private var tripA = 0L
  private var tripB = 0L

  @Before
  fun setUp() = runBlocking {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDb::class.java)
      .allowMainThreadQueries().build()
    val ing = Ingestor(db, null)
    ing.ingestFile(
      "shuriken_20260101120000.csv",
      (HEAD + row("AA:BB:CC:DD:EE:01", "home", WPA2, 6, -40) + row("AA:BB:CC:DD:EE:02", "cafe", OPEN, 36, -80) +
        row("AA:BB:CC:DD:EE:03", "home", WPA3, 1, -60)).toByteArray(),
    )
    ing.ingestFile(
      "shuriken_20260102120000.csv",
      (HEAD + row("AA:BB:CC:DD:EE:04", "shop", WPA2, 149, -55, "2026-01-02 12:00:10") +
        row("AA:BB:CC:DD:EE:01", "home", WPA2, 6, -45, "2026-01-02 12:00:20")).toByteArray(),
    )
    tripA = db.dao().tripByLogName("shuriken_20260101120000.csv")!!.id
    tripB = db.dao().tripByLogName("shuriken_20260102120000.csv")!!.id
  }

  @After
  fun tearDown() = db.close()

  private fun page(filter: NetworkFilter = NetworkFilter(), sort: NetworkSort = NetworkSort.NAME, limit: Int = 100, offset: Int = 0) =
    runBlocking { db.dao().networksPage(filter, sort, limit, offset).map { it.mac.takeLast(2) } }

  @Test
  fun filterBySecurityAndBand() {
    assertEquals(listOf("01", "04"), page(NetworkFilter(security = setOf(SecurityClass.WPA2)), NetworkSort.MOST_SEEN).sorted())
    assertEquals(listOf("02", "04"), page(NetworkFilter(band = Band.GHZ_5)).sorted())
    assertEquals(listOf("04"), page(NetworkFilter(security = setOf(SecurityClass.WPA2), band = Band.GHZ_5)))
  }

  @Test
  fun searchMatchesNameOrMac() {
    assertEquals(listOf("01", "03"), page(NetworkFilter(query = "hom")).sorted())
    assertEquals(listOf("04"), page(NetworkFilter(query = "dd:ee:04")))
  }

  @Test
  fun sortStrongestDescending() {
    assertEquals(listOf("01", "04", "03", "02"), page(sort = NetworkSort.STRONGEST))
  }

  @Test
  fun scopeToTrip() {
    assertEquals(listOf("01", "04"), page(NetworkFilter(tripId = tripB)).sorted())
    assertEquals(listOf("01", "02", "03"), page(NetworkFilter(tripId = tripA)).sorted())
  }

  @Test
  fun pagingStableOrder() {
    val all = page(sort = NetworkSort.SECURITY)
    val paged = page(sort = NetworkSort.SECURITY, limit = 2, offset = 0) + page(sort = NetworkSort.SECURITY, limit = 2, offset = 2)
    assertEquals(all, paged)
    assertEquals(4, paged.toSet().size)
  }

  @Test
  fun statsSecurityMixAndShares() = runBlocking {
    val s = db.dao().networkStats(NetworkFilter())
    assertEquals(4, s.total)
    assertEquals(mapOf("WPA2" to 2, "OPEN_OR_OWE" to 1, "WPA3" to 1), s.bySecurity)
    assertEquals(25, s.percentOpen)
    assertEquals(25, s.percentWpa3Capable)
    assertEquals(2, db.dao().networkStats(NetworkFilter(tripId = tripB)).total)
  }

  @Test
  fun statsChannelsVendorsNamesMobile() = runBlocking {
    val dao = db.dao()
    assertEquals(listOf(ChannelCount("GHZ_2_4", 6, 1), ChannelCount("GHZ_2_4", 1, 1)).toSet(),
      dao.channelUsage(null).filter { it.band == "GHZ_2_4" }.toSet())
    assertEquals(NameCount("home", 2), dao.topNames(null, 10).first())
    assertEquals(0, dao.mobileCount(null))
    assertEquals(emptyList<NameCount>(), dao.topVendors(null, 10))
  }
}
