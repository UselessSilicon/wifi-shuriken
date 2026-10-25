package com.wifishuriken.companion.live

import com.wifishuriken.companion.core.IndexEntry
import com.wifishuriken.companion.core.Md5
import com.wifishuriken.companion.core.WigleCsv
import com.wifishuriken.companion.core.WigleRecord
import com.wifishuriken.companion.data.ArchivedFileEntity
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Ingestor
import java.time.Instant

/** Result of importing one file the owner picked. */
sealed interface ImportOutcome {
  val name: String

  data class Imported(override val name: String, val rows: Int, val rejected: Int) : ImportOutcome
  data class AlreadyImported(override val name: String) : ImportOutcome
  data class Conflict(override val name: String) : ImportOutcome
  data class NotShurikenCsv(override val name: String) : ImportOutcome
  data class NoRows(override val name: String) : ImportOutcome
  data class TooLarge(override val name: String) : ImportOutcome
}

/**
 * Imports Shuriken WiGLE CSV files the owner picks by hand (older logs, copies kept elsewhere) into
 * the same archive and database as card files, so they are deduplicated the same way and a lost
 * database can be rebuilt from the archive.
 */
class ManualImporter(private val store: ArchiveStore, private val ingestor: Ingestor, private val db: CompanionDb) {

  suspend fun import(displayName: String, bytes: ByteArray): ImportOutcome {
    if (bytes.size > MAX_BYTES) return ImportOutcome.TooLarge(displayName)
    val lines = String(bytes, Charsets.UTF_8).lineSequence().take(2).toList()
    if (lines.size < 2 || !lines[0].startsWith("WigleWifi") || !lines[1].startsWith(SHURIKEN_COLUMNS)) {
      return ImportOutcome.NotShurikenCsv(displayName)
    }
    val name = shurikenNameIn(displayName) ?: nameFromFirstRow(bytes) ?: return ImportOutcome.NoRows(displayName)
    val md5 = Md5.hex(bytes)
    if (store.index().containsMd5(md5)) return ImportOutcome.AlreadyImported(name)

    val existing = store.archivedFile(name)
    if (existing.isFile) {
      val old = existing.readBytes()
      when {
        old.size < bytes.size && bytes.startsWith(old) -> Unit // a longer copy of the same log: extend it
        bytes.size <= old.size && old.startsWith(bytes) -> return ImportOutcome.AlreadyImported(name)
        else -> return ImportOutcome.Conflict(name)
      }
    }
    val tmp = store.newTempFile()
    tmp.writeBytes(bytes)
    val dest = store.commitArchive(tmp, IndexEntry(md5, name, bytes.size.toLong()))
    db.dao().insertArchivedFile(ArchivedFileEntity(md5, name, bytes.size.toLong(), dest.path, Instant.now().toString()))
    val result = ingestor.ingestFile(name, bytes)
    return ImportOutcome.Imported(name, result.rowsInserted, result.rejected)
  }

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    prefix.size <= size && prefix.indices.all { this[it] == prefix[it] }

  companion object {
    private const val MAX_BYTES = 64 * 1024 * 1024
    private const val SHURIKEN_COLUMNS = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,"
    private val SHURIKEN_NAME = Regex("""shuriken_\d{14}(?:_\d{2})?\.csv""", RegexOption.IGNORE_CASE)

    /** Suffix marking a log named from its first row because the file name carried no log name. */
    private const val IMPORTED_SUFFIX = "90"

    /** The Shuriken log name inside [displayName] (copies are often prefixed, e.g. `<hash>_shuriken_...csv`). */
    fun shurikenNameIn(displayName: String): String? = SHURIKEN_NAME.find(displayName)?.value?.lowercase()

    private fun nameFromFirstRow(bytes: ByteArray): String? {
      val first = String(bytes, Charsets.UTF_8).lineSequence()
        .map { WigleCsv.parseRecord(it) }
        .firstNotNullOfOrNull { (it as? WigleRecord.Wifi)?.sighting?.firstSeen } ?: return null
      val digits = first.filter { it.isDigit() }
      return if (digits.length == 14) "shuriken_${digits}_$IMPORTED_SUFFIX.csv" else null
    }
  }
}
