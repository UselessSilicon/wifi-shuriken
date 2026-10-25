package com.wifishuriken.companion.live

import com.wifishuriken.companion.core.CardSource
import com.wifishuriken.companion.core.FetchedDecision
import com.wifishuriken.companion.core.IndexEntry
import com.wifishuriken.companion.core.ShurikenLogName
import com.wifishuriken.companion.core.decideFetched
import com.wifishuriken.companion.core.planSync
import com.wifishuriken.companion.data.ArchivedFileEntity
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Ingestor
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.Instant

/**
 * [failed]: copies that failed (not archived, retried on the next connect). [importFailed]: archived
 * but not imported; the orphan pass retries those a few times.
 */
data class SyncReport(
  val archived: Int,
  val duplicates: Int,
  val failed: Int,
  val skipped: Int,
  val heldBack: String?,
  val importFailed: Int = 0,
)

/** Result of one orphan pass: imported now, failed now, and set aside after too many failures. */
data class OrphanReport(val ingested: Int, val failed: Int, val quarantined: Int)

/** Copies finished logs from the card into the archive and the database (design §8.2). */
class ArchiveSyncer(
  private val store: ArchiveStore,
  private val ingestor: Ingestor,
  private val db: CompanionDb,
  /** Imports one archived file (replaceable in tests). Streams the file so long logs fit in memory. */
  private val ingest: suspend (String, File) -> Unit = { name, file -> file.inputStream().use { ingestor.ingestStream(name, it) } },
) {

  /**
   * One pass: plan, then copy each file to a temp file while hashing, decide, and archive + ingest
   * or record as duplicate. A failure on one file deletes its temp file, leaves the index unchanged
   * (so it is retried next time) and moves on. [activeLog] is held back as well as the newest file.
   */
  suspend fun syncOnce(source: CardSource, activeLog: String?): SyncReport {
    val plan = planSync(source.listCard(), store.index())
    var archived = 0
    var duplicates = 0
    var failed = 0
    var importFailed = 0
    for (file in plan.toFetch) {
      if (file.name == activeLog) continue
      val tmp = store.newTempFile()
      try {
        val md = MessageDigest.getInstance("MD5")
        DigestOutputStream(tmp.outputStream(), md).use { source.copyTo(file.handle, file.size, it) }
        val md5 = md.digest().joinToString("") { "%02x".format(it) }
        val entry = IndexEntry(md5, file.name, file.size)
        when (decideFetched(md5, store.index())) {
          FetchedDecision.Archive -> {
            val dest = store.commitArchive(tmp, entry)
            db.dao().insertArchivedFile(ArchivedFileEntity(md5, file.name, file.size, dest.path, Instant.now().toString()))
            archived++
            if (!tryIngest(file.name, dest)) importFailed++
          }
          FetchedDecision.DuplicateContent -> {
            tmp.delete()
            store.recordDuplicate(entry)
            duplicates++
          }
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        tmp.delete()
        failed++
      }
    }
    return SyncReport(archived, duplicates, failed, plan.skipped.size, plan.heldBack?.name, importFailed)
  }

  /** Re-ingests archived files that have no trip (database lost or wiped while the archive survived). */
  /**
   * Imports archived logs that have no trip (database lost, or an earlier import failed). Each file
   * is isolated: one that fails is counted and retried on later passes, and set aside after
   * [MAX_IMPORT_ATTEMPTS] failures so a bad file can never block every session.
   */
  suspend fun reingestOrphans(): OrphanReport {
    val known = db.dao().allTripLogNames().toSet()
    val attempts = store.importAttempts()
    var ingested = 0
    var failed = 0
    var quarantined = 0
    for (name in store.index().entries.map { it.name }.distinct()) {
      if (name in known || ShurikenLogName.parse(name) == null) continue
      val file = store.archivedFile(name)
      if (!file.exists()) continue // duplicates are recorded without a kept file
      if ((attempts[name] ?: 0) >= MAX_IMPORT_ATTEMPTS) {
        quarantined++
        continue
      }
      if (tryIngest(name, file)) ingested++ else failed++
    }
    return OrphanReport(ingested, failed, quarantined)
  }

  /** Imports one archived file; a failure (including running out of memory) is recorded, not thrown. */
  private suspend fun tryIngest(name: String, file: File): Boolean = try {
    ingest(name, file)
    store.clearFailedImport(name)
    true
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    store.recordFailedImport(name)
    false
  } catch (e: OutOfMemoryError) {
    store.recordFailedImport(name)
    false
  }

  companion object {
    const val MAX_IMPORT_ATTEMPTS = 3
  }
}
