package com.wifishuriken.companion.core

import java.io.ByteArrayOutputStream

/** One complete CSV record and the file offset of its first byte. */
data class CsvRecord(val offset: Long, val text: String)

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
class CsvRecordSplitter(
  private val maxRecordBytes: Int = DEFAULT_MAX_RECORD_BYTES,
  startOffset: Long = 0,
) {
  private val pending = ByteArrayOutputStream()
  private var inQuotes = false
  private var resyncing = false

  /** File offset of the next byte to be fed. */
  private var position = startOffset
  private var recordStart = startOffset

  /** Number of oversized records discarded so far. */
  var droppedRecords: Int = 0
    private set

  /** Feeds [length] bytes from [data] and returns the records completed by them, with their file offsets. */
  fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<CsvRecord> {
    val records = ArrayList<CsvRecord>()
    for (i in offset until offset + length) {
      val b = data[i]
      val here = position++
      if (resyncing) {
        if (b == NEWLINE) {
          resyncing = false
        }
        continue
      }
      if (pending.size() == 0) {
        recordStart = here
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

  fun reset(startOffset: Long = 0) {
    pending.reset()
    inQuotes = false
    resyncing = false
    droppedRecords = 0
    position = startOffset
    recordStart = startOffset
  }

  private fun emit(out: MutableList<CsvRecord>) {
    var bytes = pending.toByteArray()
    pending.reset()
    if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CARRIAGE_RETURN) {
      bytes = bytes.copyOf(bytes.size - 1)
    }
    if (bytes.isNotEmpty()) {
      out.add(CsvRecord(recordStart, String(bytes, Charsets.UTF_8)))
    }
  }

  private companion object {
    const val DEFAULT_MAX_RECORD_BYTES = 4096
    const val QUOTE = '"'.code.toByte()
    const val NEWLINE = '\n'.code.toByte()
    const val CARRIAGE_RETURN = '\r'.code.toByte()
  }
}
