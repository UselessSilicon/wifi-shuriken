package com.wifishuriken.companion.core

import java.io.ByteArrayOutputStream

/**
 * Splits an append-only byte stream into complete CSV records.
 *
 * The firmware quotes every SSID (see csvEscape in lib/log_format), and an SSID
 * may legally contain a newline, so a record ends only at a '\n' that is outside
 * quotes. Bytes are buffered until a record is complete, which also keeps
 * multi-byte UTF-8 sequences intact across chunk boundaries.
 *
 * A record that grows past [maxRecordBytes] without terminating (an unbalanced
 * quote from a corrupted write) is dropped, and the splitter resynchronises at
 * the next newline so one bad row cannot swallow the rest of the file.
 */
class CsvRecordSplitter(private val maxRecordBytes: Int = DEFAULT_MAX_RECORD_BYTES) {
  private val pending = ByteArrayOutputStream()
  private var inQuotes = false
  private var resyncing = false

  /** Number of oversized records discarded so far. */
  var droppedRecords: Int = 0
    private set

  /** Feeds [length] bytes from [data] and returns the records completed by them. */
  fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<String> {
    val records = ArrayList<String>()
    for (i in offset until offset + length) {
      val b = data[i]
      if (resyncing) {
        if (b == NEWLINE) {
          resyncing = false
        }
        continue
      }
      if (b == QUOTE) {
        inQuotes = !inQuotes
      } else if (b == NEWLINE && !inQuotes) {
        emit(records)
        continue
      }
      pending.write(b.toInt())
      if (pending.size() > maxRecordBytes) {
        pending.reset()
        inQuotes = false
        resyncing = true
        droppedRecords++
      }
    }
    return records
  }

  /** Bytes buffered for a record whose terminating newline has not arrived yet. */
  fun pendingBytes(): Int = pending.size()

  fun reset() {
    pending.reset()
    inQuotes = false
    resyncing = false
    droppedRecords = 0
  }

  private fun emit(out: MutableList<String>) {
    var bytes = pending.toByteArray()
    pending.reset()
    if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CARRIAGE_RETURN) {
      bytes = bytes.copyOf(bytes.size - 1)
    }
    if (bytes.isNotEmpty()) {
      out.add(String(bytes, Charsets.UTF_8))
    }
  }

  private companion object {
    const val DEFAULT_MAX_RECORD_BYTES = 4096
    const val QUOTE = '"'.code.toByte()
    const val NEWLINE = '\n'.code.toByte()
    const val CARRIAGE_RETURN = '\r'.code.toByte()
  }
}
