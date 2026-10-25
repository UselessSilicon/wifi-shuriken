package com.wifishuriken.companion.live

import com.wifishuriken.companion.core.HashIndex
import com.wifishuriken.companion.core.IndexEntry
import java.io.File

/**
 * The app's own copy of every Shuriken log plus the imported-hash list (owner's design, §8.2).
 * The index is rewritten atomically (temp file + rename) so a crash never leaves it half-written.
 */
class ArchiveStore(val archiveDir: File) {
  val indexFile = File(archiveDir, "index.tsv")
  val tmpDir = File(archiveDir, "tmp")

  init {
    archiveDir.mkdirs()
    tmpDir.mkdirs()
  }

  fun index(): HashIndex = if (indexFile.exists()) HashIndex.parse(indexFile.readText()) else HashIndex(emptyList())

  fun archivedFile(name: String): File = File(archiveDir, name)

  fun newTempFile(): File = File.createTempFile("fetch", ".part", tmpDir)

  /** Moves a fetched file into the archive and records it. */
  @Synchronized
  fun commitArchive(tmp: File, entry: IndexEntry): File {
    val dest = archivedFile(entry.name)
    if (!tmp.renameTo(dest)) {
      tmp.copyTo(dest, overwrite = true)
      tmp.delete()
    }
    writeIndex(index().plus(entry))
    return dest
  }

  /** Records a file whose content is already archived under another name; the copy is not kept. */
  @Synchronized
  fun recordDuplicate(entry: IndexEntry) = writeIndex(index().plus(entry))

  private val attemptsFile = File(archiveDir, "attempts.tsv")

  /** Failed import attempts per archived log, kept across restarts (name -> count). */
  @Synchronized
  fun importAttempts(): Map<String, Int> =
    if (!attemptsFile.exists()) emptyMap()
    else attemptsFile.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[1] to (it[0].toIntOrNull() ?: 0) } }.toMap()

  @Synchronized
  fun recordFailedImport(name: String): Int {
    val next = (importAttempts()[name] ?: 0) + 1
    writeAtomically(attemptsFile, (importAttempts() + (name to next)).entries.joinToString("") { "${it.value}\t${it.key}\n" })
    return next
  }

  @Synchronized
  fun clearFailedImport(name: String) {
    val a = importAttempts()
    if (name in a) writeAtomically(attemptsFile, (a - name).entries.joinToString("") { "${it.value}\t${it.key}\n" })
  }

  private fun writeAtomically(target: File, text: String) {
    val tmp = File(archiveDir, target.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(target)) {
      target.writeText(text)
      tmp.delete()
    }
  }

  private fun writeIndex(index: HashIndex) {
    val tmp = File(archiveDir, "index.tsv.tmp")
    tmp.writeText(index.serialize())
    if (!tmp.renameTo(indexFile)) {
      indexFile.writeText(index.serialize())
      tmp.delete()
    }
  }
}
