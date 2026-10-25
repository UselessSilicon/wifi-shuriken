package com.wifishuriken.companion.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The offline map file, copied into app storage because MapLibre cannot open the content:// address
 * the system file picker returns (design §9). The copy goes to a temp file and is renamed into place
 * only when complete, so a failed import keeps the previous map.
 */
class MapFileStore(
  val dir: File,
  private val freeBytes: () -> Long = { dir.usableSpace },
  private val reserveBytes: Long = 200L * 1024 * 1024,
  private val checkEveryBytes: Long = 64L * 1024 * 1024,
) {
  val file = File(dir, "basemap.pmtiles")

  init {
    dir.mkdirs()
  }

  fun hasMap(): Boolean = file.isFile && file.length() > 0

  suspend fun importFrom(context: Context, uri: Uri, onProgress: (Long, Long?) -> Unit): Result<Unit> {
    val size = runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
      }
    }.getOrNull()
    return importFrom({ context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the picked file") }, size, onProgress)
  }

  suspend fun importFrom(open: () -> InputStream, size: Long?, onProgress: (Long, Long?) -> Unit): Result<Unit> =
    withContext(Dispatchers.IO) {
      val tmp = File(dir, "basemap.pmtiles.part")
      runCatching {
        if (size != null && freeBytes() < size + reserveBytes) {
          throw IOException("Not enough free space for the map file")
        }
        open().use { input ->
          tmp.outputStream().use { out ->
            val buf = ByteArray(256 * 1024)
            var copied = 0L
            var sinceCheck = 0L
            while (true) {
              val n = input.read(buf)
              if (n < 0) break
              out.write(buf, 0, n)
              copied += n
              sinceCheck += n
              if (size == null && sinceCheck >= checkEveryBytes) {
                sinceCheck = 0
                if (freeBytes() < reserveBytes) throw IOException("Ran out of free space while copying the map file")
              }
              onProgress(copied, size)
            }
          }
        }
        if (!tmp.renameTo(file)) throw IOException("Could not move the map file into place")
        Unit
      }.onFailure { tmp.delete() }
    }
}
