package com.wifishuriken.companion.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Writes a consistent single-file copy of [db] into [dir] with `VACUUM INTO`, which includes
 * rows still in the WAL. Returns the copy; the caller streams it out and deletes it.
 */
suspend fun exportDatabase(db: CompanionDb, dir: File): File = withContext(Dispatchers.IO) {
  dir.mkdirs()
  val out = File(dir, "shuriken-companion-export.db")
  out.delete()
  db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(out.absolutePath))
  out
}

/** Exports [db] to the document [target] the owner picked (system file picker); nothing leaves the phone otherwise. */
suspend fun exportDatabaseTo(context: Context, db: CompanionDb, target: Uri): Result<Long> = withContext(Dispatchers.IO) {
  runCatching {
    val copy = exportDatabase(db, File(context.cacheDir, "export"))
    try {
      val stream = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot open the chosen file")
      stream.use { o -> copy.inputStream().use { it.copyTo(o) } }
    } finally {
      copy.delete()
    }
  }
}
