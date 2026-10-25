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
import java.time.Instant

data class SyncReport(val archived: Int, val duplicates: Int, val failed: Int, val skipped: Int, val heldBack: String?)

/** Copies finished logs from the card into the archive and the database (design §8.2). */
class ArchiveSyncer(private val store: ArchiveStore, private val ingestor: Ingestor, private val db: CompanionDb) {

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
            ingestor.ingestFile(file.name, dest.readBytes())
            archived++
          }
          FetchedDecision.DuplicateContent -> {
            tmp.delete()
            store.recordDuplicate(entry)
            duplicates++
          }
        }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        tmp.delete()
        failed++
      }
    }
    return SyncReport(archived, duplicates, failed, plan.skipped.size, plan.heldBack?.name)
  }

  /** Re-ingests archived files that have no trip (database lost or wiped while the archive survived). */
  suspend fun reingestOrphans(): Int {
    val known = db.dao().allTripLogNames().toSet()
    var count = 0
    for (name in store.index().entries.map { it.name }.distinct()) {
      if (name in known || ShurikenLogName.parse(name) == null) continue
      val file = store.archivedFile(name)
      if (!file.exists()) continue // duplicates are recorded without a kept file
      ingestor.ingestFile(name, file.readBytes())
      count++
    }
    return count
  }
}
