package com.wifishuriken.companion.live

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wifishuriken.companion.core.IndexEntry
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Ingestor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.IOException

private const val HEAD = "WigleWifi-1.4,appRelease=0.2\n" +
  "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n"
private const val ROW = "AA:BB:CC:DD:EE:01,\"n\",[ESS],2026-01-01 12:00:01,6,-60,1.0,1.0,10.0,5.0,WIFI\n"

@RunWith(AndroidJUnit4::class)
class OrphanIngestTest {
  @get:Rule val tmp = TemporaryFolder()
  private lateinit var db: CompanionDb
  private lateinit var store: ArchiveStore

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDb::class.java).allowMainThreadQueries().build()
    store = ArchiveStore(tmp.newFolder("archive"))
    for (name in listOf("shuriken_20260101120000.csv", "shuriken_20260102120000.csv")) {
      val f = store.newTempFile().apply { writeText(HEAD + ROW) }
      store.commitArchive(f, IndexEntry(name.hashCode().toString(16).padStart(32, '0'), name, f.length()))
    }
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun aFailingFileDoesNotBlockTheOthers() = runBlocking {
    val ingestor = Ingestor(db, null)
    val syncer = ArchiveSyncer(store, ingestor, db, ingest = { name, file ->
      if (name == "shuriken_20260101120000.csv") throw IOException("unreadable") else file.inputStream().use { ingestor.ingestStream(name, it) }
    })
    val r = syncer.reingestOrphans()
    assertEquals(OrphanReport(ingested = 1, failed = 1, quarantined = 0), r)
    assertNotNull(db.dao().tripByLogName("shuriken_20260102120000.csv"))
  }

  @Test
  fun aFileThatKeepsFailingIsSetAsideAfterThreeAttempts() = runBlocking {
    var attempts = 0
    val syncer = ArchiveSyncer(store, Ingestor(db, null), db, ingest = { name, _ ->
      if (name == "shuriken_20260101120000.csv") { attempts++; throw OutOfMemoryError("too big") }
      Ingestor(db, null).ingestFile(name, (HEAD + ROW).toByteArray())
    })
    repeat(3) { syncer.reingestOrphans() }
    assertEquals(3, attempts)
    assertEquals(OrphanReport(ingested = 0, failed = 0, quarantined = 1), syncer.reingestOrphans())
    assertEquals(3, attempts)
    // The count survives a restart: a new syncer over the same archive also skips it.
    assertEquals(1, ArchiveSyncer(store, Ingestor(db, null), db).reingestOrphans().quarantined)
  }
}
