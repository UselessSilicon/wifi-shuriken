package com.wifishuriken.companion.live

import com.wifishuriken.companion.core.IndexEntry
import com.wifishuriken.companion.core.Md5
import com.wifishuriken.companion.core.WigleCsv
import com.wifishuriken.companion.core.WigleRecord
import com.wifishuriken.companion.data.ArchivedFileEntity
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Ingestor
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
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

  /** Archived, but the rows could not be read now; the next session retries it a few times. */
  data class ImportFailed(override val name: String) : ImportOutcome
}

/**
 * Imports Shuriken WiGLE CSV files the owner picks by hand (older logs, copies kept elsewhere) into
 * the same archive and database as card files, so they are deduplicated the same way and a lost
 * database can be rebuilt from the archive. Files are streamed from disk, never held in memory whole.
 */
class ManualImporter(
  private val store: ArchiveStore,
  private val ingestor: Ingestor,
  private val db: CompanionDb,
  val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

  /** Convenience for small inputs (tests): writes [bytes] to a temp file and imports it. */
  suspend fun import(displayName: String, bytes: ByteArray): ImportOutcome {
    val tmp = store.newTempFile()
    try {
      tmp.writeBytes(bytes)
      return importFile(displayName, tmp)
    } finally {
      tmp.delete()
    }
  }

  /**
   * Copies [input] to a temp file (stopping at [maxBytes]) and imports it. The temp file is moved
   * into the archive when imported and deleted otherwise.
   */
  suspend fun importStream(displayName: String, input: InputStream): ImportOutcome {
    val tmp = store.newTempFile()
    try {
      var total = 0L
      tmp.outputStream().use { out ->
        val buf = ByteArray(64 * 1024)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          total += n
          if (total > maxBytes) return ImportOutcome.TooLarge(displayName)
          out.write(buf, 0, n)
        }
      }
      return importFile(displayName, tmp)
    } finally {
      tmp.delete()
    }
  }

  /** Imports [file]; on success it is moved into the archive. */
  suspend fun importFile(displayName: String, file: File): ImportOutcome {
    if (file.length() > maxBytes) return ImportOutcome.TooLarge(displayName)
    val lines = file.bufferedReader().use { r -> listOfNotNull(r.readLine(), r.readLine()) }
    if (lines.size < 2 || !lines[0].startsWith("WigleWifi") || !lines[1].startsWith(SHURIKEN_COLUMNS)) {
      return ImportOutcome.NotShurikenCsv(displayName)
    }
    val name = shurikenNameIn(displayName) ?: nameFromFirstRow(file) ?: return ImportOutcome.NoRows(displayName)
    val md5 = file.inputStream().use { Md5.hexOfStream(it) }
    if (store.index().containsMd5(md5)) return ImportOutcome.AlreadyImported(name)

    val existing = store.archivedFile(name)
    if (existing.isFile) {
      when {
        existing.length() < file.length() && isPrefix(existing, file) -> Unit // a longer copy of the same log: extend it
        file.length() <= existing.length() && isPrefix(file, existing) -> return ImportOutcome.AlreadyImported(name)
        else -> return ImportOutcome.Conflict(name)
      }
    }
    val size = file.length()
    val dest = store.commitArchive(file, IndexEntry(md5, name, size))
    db.dao().insertArchivedFile(ArchivedFileEntity(md5, name, size, dest.path, Instant.now().toString()))
    return try {
      val result = dest.inputStream().use { ingestor.ingestStream(name, it) }
      store.clearFailedImport(name)
      ImportOutcome.Imported(name, result.rowsInserted, result.rejected)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      store.recordFailedImport(name)
      ImportOutcome.ImportFailed(name)
    } catch (e: OutOfMemoryError) {
      store.recordFailedImport(name)
      ImportOutcome.ImportFailed(name)
    }
  }

  /** True when [shorter]'s bytes are the start of [longer]'s, compared as streams. */
  private fun isPrefix(shorter: File, longer: File): Boolean {
    shorter.inputStream().buffered().use { a ->
      longer.inputStream().buffered().use { b ->
        while (true) {
          val x = a.read()
          if (x < 0) return true
          if (x != b.read()) return false
        }
      }
    }
  }

  companion object {
    const val DEFAULT_MAX_BYTES = 128L * 1024 * 1024
    private const val SHURIKEN_COLUMNS = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,"
    private val SHURIKEN_NAME = Regex("""shuriken_\d{14}(?:_\d{2})?\.csv""", RegexOption.IGNORE_CASE)

    /** Suffix marking a log named from its first row because the file name carried no log name. */
    private const val IMPORTED_SUFFIX = "90"

    /** The Shuriken log name inside [displayName] (copies are often prefixed, e.g. `<hash>_shuriken_...csv`). */
    fun shurikenNameIn(displayName: String): String? = SHURIKEN_NAME.find(displayName)?.value?.lowercase()

    private fun nameFromFirstRow(file: File): String? {
      val first = file.bufferedReader().useLines { lines ->
        lines.map { WigleCsv.parseRecord(it) }.firstNotNullOfOrNull { (it as? WigleRecord.Wifi)?.sighting?.firstSeen }
      } ?: return null
      val digits = first.filter { it.isDigit() }
      return if (digits.length == 14) "shuriken_${digits}_$IMPORTED_SUFFIX.csv" else null
    }
  }
}
