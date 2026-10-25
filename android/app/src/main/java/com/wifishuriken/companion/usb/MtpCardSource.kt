package com.wifishuriken.companion.usb

import com.wifishuriken.companion.core.CardFile
import com.wifishuriken.companion.core.CardSource
import com.wifishuriken.companion.core.RootFileSource
import java.io.IOException
import java.io.OutputStream

/** The device kept failing; its endpoints may be stalled after an abandoned transfer. Reopen the connection. */
class MtpStalledException(message: String) : IOException(message)

/**
 * Read-only access to the card over MTP. Calls must be made from one thread at a time. The firmware
 * answers "busy" while the SD card is in use, which Android reports as a plain failure, so every call
 * is retried a few times before giving up.
 */
class MtpCardSource(private val conn: ShurikenConnection) : RootFileSource, CardSource {
  private val mtp get() = conn.mtp

  override fun listRootHandles(): IntArray =
    retry("list card") { mtp.getObjectHandles(conn.storageId, 0, ROOT_PARENT) }

  override fun info(handle: Int): RootFileSource.Info? {
    val i = retryOrNull { mtp.getObjectInfo(handle) } ?: return null
    return RootFileSource.Info(i.name, i.compressedSizeLong)
  }

  override fun read(handle: Int, offset: Long, buffer: ByteArray, length: Int): Int =
    retry("read") { mtp.getPartialObject(handle, offset, length.toLong(), buffer).takeIf { it >= 0 } }.toInt()

  override fun listCard(): List<CardFile> = listRootHandles().toList().mapNotNull { h ->
    info(h)?.let { CardFile(it.name, it.size, h) }
  }

  override fun copyTo(handle: Int, size: Long, out: OutputStream) {
    val buf = ByteArray(CHUNK)
    var offset = 0L
    while (offset < size) {
      val want = minOf(CHUNK.toLong(), size - offset).toInt()
      val got = read(handle, offset, buf, want)
      if (got <= 0) throw MtpStalledException("No data at offset $offset of $size")
      out.write(buf, 0, got)
      offset += got
    }
  }

  private fun <T : Any> retryOrNull(block: () -> T?): T? {
    repeat(ATTEMPTS) { attempt ->
      block()?.let { return it }
      if (attempt < ATTEMPTS - 1) Thread.sleep(BACKOFF_MS)
    }
    return null
  }

  private fun <T : Any> retry(what: String, block: () -> T?): T =
    retryOrNull(block) ?: throw MtpStalledException("MTP $what failed $ATTEMPTS times")

  private companion object {
    const val ROOT_PARENT = -1
    const val CHUNK = 16 * 1024
    const val ATTEMPTS = 5
    const val BACKOFF_MS = 200L
  }
}
