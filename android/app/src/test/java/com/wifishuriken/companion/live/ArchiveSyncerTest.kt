package com.wifishuriken.companion.live

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wifishuriken.companion.core.CardFile
import com.wifishuriken.companion.core.CardSource
import com.wifishuriken.companion.core.HashIndex
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Ingestor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.IOException
import java.io.OutputStream

private const val PRE = "WigleWifi-1.4,appRelease=0.2,model=WiFi-Shuriken\n"
private const val COLS = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n"
private fun log(mac: String, ts: String) =
  (PRE + COLS + "$mac,\"net\",[WPA2-PSK-CCMP][ESS],$ts,6,-60,1.0000000,1.0000000,10.0,5.0,WIFI\n").toByteArray()

/** A fake card: name -> bytes; optionally fails partway through copying one file. */
private class FakeCard(val files: Map<String, ByteArray>, var failOn: String? = null) : CardSource {
  var copies = 0
  private val handles = files.keys.sorted()
  override fun listCard() = handles.mapIndexed { i, n -> CardFile(n, files.getValue(n).size.toLong(), i + 1) }
  override fun copyTo(handle: Int, size: Long, out: OutputStream) {
    copies++
    val name = handles[handle - 1]
    val bytes = files.getValue(name)
    if (name == failOn) {
      out.write(bytes, 0, bytes.size / 2)
      throw IOException("stalled")
    }
    out.write(bytes)
  }
}

@RunWith(AndroidJUnit4::class)
class ArchiveSyncerTest {
  @get:Rule val tmp = TemporaryFolder()
  private lateinit var db: CompanionDb
  private lateinit var store: ArchiveStore
  private lateinit var syncer: ArchiveSyncer

  private val a = "shuriken_20260101000000.csv"
  private val b = "shuriken_20260102000000.csv"
  private val newest = "shuriken_20260103000000.csv"

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDb::class.java)
      .allowMainThreadQueries().build()
    store = ArchiveStore(tmp.newFolder("archive"))
    syncer = ArchiveSyncer(store, Ingestor(db, oui = null), db)
  }

  @After
  fun tearDown() = db.close()

  private fun card() = FakeCard(
    mapOf(a to log("AA:BB:CC:DD:EE:01", "2026-01-01 00:00:05"), b to log("AA:BB:CC:DD:EE:02", "2026-01-02 00:00:05"),
      newest to log("AA:BB:CC:DD:EE:03", "2026-01-03 00:00:05")),
  )

  @Test
  fun syncOnceArchivesAndIngestsOnce() = runBlocking {
    val report = syncer.syncOnce(card(), activeLog = null)
    assertEquals(2, report.archived)
    assertEquals(2, store.index().entries.size)
    assertTrue(store.archivedFile(a).exists())
    assertNotNull(db.dao().tripByLogName(a))
    assertNotNull(db.dao().tripByLogName(b))
    assertEquals(null, db.dao().tripByLogName(newest))
    val second = card()
    syncer.syncOnce(second, activeLog = null)
    assertEquals(0, second.copies)
  }

  @Test
  fun syncOnceFailedCopyLeavesNothing() = runBlocking {
    val failing = card().apply { failOn = a }
    val report = syncer.syncOnce(failing, activeLog = null)
    assertEquals(1, report.failed)
    assertFalse(store.archivedFile(a).exists())
    assertFalse(store.index().hasNameAndSize(a, failing.files.getValue(a).size.toLong()))
    assertTrue(store.tmpDir.listFiles().isNullOrEmpty())
    assertTrue(store.archivedFile(b).exists())
    val retry = card()
    syncer.syncOnce(retry, activeLog = null)
    assertEquals(1, retry.copies)
    assertTrue(store.archivedFile(a).exists())
  }

  @Test
  fun duplicateContentRecordedNotKept() = runBlocking {
    val same = log("AA:BB:CC:DD:EE:01", "2026-01-01 00:00:05")
    val dupName = "shuriken_20260101000000_01.csv"
    syncer.syncOnce(FakeCard(mapOf(a to same, dupName to same, newest to log("AA:BB:CC:DD:EE:03", "2026-01-03 00:00:05"))), null)
    assertEquals(1, store.archiveDir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }!!.size)
    assertEquals(2, store.index().entries.size)
    val again = FakeCard(mapOf(a to same, dupName to same, newest to log("AA:BB:CC:DD:EE:03", "2026-01-03 00:00:05")))
    syncer.syncOnce(again, null)
    assertEquals(0, again.copies)
  }

  @Test
  fun activeLogIsHeldBackToo() = runBlocking {
    val c = card()
    syncer.syncOnce(c, activeLog = b)
    assertNotNull(db.dao().tripByLogName(a))
    assertEquals(null, db.dao().tripByLogName(b))
  }

  @Test
  fun archivedFilesWithoutTripAreReingested() = runBlocking {
    syncer.syncOnce(card(), activeLog = null)
    db.clearAllTables()
    val n = syncer.reingestOrphans()
    assertEquals(2, n)
    assertNotNull(db.dao().tripByLogName(a))
    assertNotNull(db.dao().tripByLogName(b))
  }

  @Test
  fun indexSurvivesRestart() {
    store.recordDuplicate(com.wifishuriken.companion.core.IndexEntry("abc", a, 10))
    assertTrue(ArchiveStore(store.archiveDir).index().hasNameAndSize(a, 10))
    assertEquals(HashIndex.parse(store.indexFile.readText()).entries, ArchiveStore(store.archiveDir).index().entries)
  }
}
