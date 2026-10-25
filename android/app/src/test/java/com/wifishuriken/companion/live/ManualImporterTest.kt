package com.wifishuriken.companion.live

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Ingestor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

private const val HEAD = "WigleWifi-1.4,appRelease=0.2,model=WiFi-Shuriken,release=0.2,device=WiFi-Shuriken\n" +
  "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n"

private fun row(n: Int, ts: String = "2026-01-01 12:00:0$n") =
  "AA:BB:CC:DD:EE:0$n,\"net\",[WPA2-PSK-CCMP][ESS],$ts,6,-60,1.0000000,1.0000000,10.0,5.0,WIFI\n"

@RunWith(AndroidJUnit4::class)
class ManualImporterTest {
  @get:Rule val tmp = TemporaryFolder()
  private lateinit var db: CompanionDb
  private lateinit var store: ArchiveStore
  private lateinit var importer: ManualImporter

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDb::class.java).allowMainThreadQueries().build()
    store = ArchiveStore(tmp.newFolder("archive"))
    importer = ManualImporter(store, Ingestor(db, null), db)
  }

  @After
  fun tearDown() = db.close()

  private fun import(name: String, text: String) = runBlocking { importer.import(name, text.toByteArray()) }

  @Test
  fun importsShurikenLogUnderItsOwnNameAndArchivesIt() = runBlocking {
    val r = import("a1b2c3d4e5f6_shuriken_20260101120000.csv", HEAD + row(1) + row(2))
    assertEquals(ImportOutcome.Imported("shuriken_20260101120000.csv", 2, 0), r)
    assertTrue(store.archivedFile("shuriken_20260101120000.csv").isFile)
    assertTrue(store.index().hasNameAndSize("shuriken_20260101120000.csv", (HEAD + row(1) + row(2)).length.toLong()))
    assertEquals(2, db.dao().tripByLogName("shuriken_20260101120000.csv")!!.rows)
  }

  @Test
  fun sameContentTwiceIsAlreadyImported() {
    import("shuriken_20260101120000.csv", HEAD + row(1))
    assertEquals(ImportOutcome.AlreadyImported("shuriken_20260101120000.csv"), import("copy_shuriken_20260101120000.csv", HEAD + row(1)))
  }

  @Test
  fun longerVersionOfALogExtendsIt() = runBlocking {
    import("shuriken_20260101120000.csv", HEAD + row(1))
    val r = import("shuriken_20260101120000.csv", HEAD + row(1) + row(2) + row(3))
    assertEquals(ImportOutcome.Imported("shuriken_20260101120000.csv", 2, 0), r)
    val t = db.dao().tripByLogName("shuriken_20260101120000.csv")!!
    assertEquals(3, db.dao().sightingCountForTrip(t.id))
    assertEquals((HEAD + row(1) + row(2) + row(3)).length.toLong(), store.archivedFile(t.logName).length())
  }

  @Test
  fun olderPartialCopyIsAlreadyImported() {
    import("shuriken_20260101120000.csv", HEAD + row(1) + row(2))
    assertEquals(ImportOutcome.AlreadyImported("shuriken_20260101120000.csv"), import("shuriken_20260101120000.csv", HEAD + row(1)))
  }

  @Test
  fun sameNameDifferentContentIsAConflict() {
    import("shuriken_20260101120000.csv", HEAD + row(1))
    assertEquals(ImportOutcome.Conflict("shuriken_20260101120000.csv"), import("shuriken_20260101120000.csv", HEAD + row(2)))
  }

  @Test
  fun filesThatAreNotShurikenCsvAreRefused() {
    assertEquals(ImportOutcome.NotShurikenCsv("notes.txt"), import("notes.txt", "hello\nworld\n"))
    val wigleApp = "WigleWifi-1.6,appRelease=2.70\nMAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude," +
      "AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type\n"
    assertEquals(ImportOutcome.NotShurikenCsv("WigleWifi_20260101.csv"), import("WigleWifi_20260101.csv", wigleApp))
  }

  @Test
  fun otherFileNamesAreNamedFromTheFirstRowTime() {
    val r = import("my walk.csv", HEAD + row(5) + row(6))
    assertEquals(ImportOutcome.Imported("shuriken_20260101120005_90.csv", 2, 0), r)
    assertEquals(ImportOutcome.NoRows("empty.csv"), import("empty.csv", HEAD))
  }
}
