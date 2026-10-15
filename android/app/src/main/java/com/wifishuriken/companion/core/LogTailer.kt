package com.wifishuriken.companion.core

import java.io.IOException

/** Minimal read-only view of the controller's MTP storage (SD card root). */
interface RootFileSource {
  data class Info(val name: String, val size: Long)

  /** Handles of the root directory entries. Throws on transport failure. */
  @Throws(IOException::class)
  fun listRootHandles(): IntArray

  /** Name and current size of [handle], or null if it no longer resolves. */
  @Throws(IOException::class)
  fun info(handle: Int): Info?

  /** Reads up to [length] bytes at [offset] into [buffer]; returns bytes read. */
  @Throws(IOException::class)
  fun read(handle: Int, offset: Long, buffer: ByteArray, length: Int): Int
}

sealed interface PollOutcome {
  /** SD card not ready, or no `shuriken_*.csv` yet (the firmware waits for GNSS time). */
  data object NoLogFile : PollOutcome

  data class Tailing(
    val fileName: String,
    val bytesRead: Long,
    val fileSize: Long,
    val newRows: Int,
    /** True when a new log file was selected on this poll and prior state was cleared. */
    val switchedFile: Boolean,
  ) : PollOutcome {
    val caughtUp: Boolean
      get() = bytesRead >= fileSize
  }
}

/**
 * Follows the newest scan log on the controller by reading only bytes appended
 * since the previous poll (MTP GetPartialObject).
 *
 * The controller flushes the active CSV whenever its object info is requested
 * or it is read, so each poll sees every row logged up to that moment.
 *
 * Handles are the firmware's FAT directory indexes plus one, which stay stable
 * while files are only being added, so names are cached per handle and only
 * the active file's info is requested on each poll.
 */
class LogTailer(
  private val source: RootFileSource,
  private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
  private val maxBytesPerPoll: Long = DEFAULT_MAX_BYTES_PER_POLL,
) {
  val table = ApTable()
  var preHeader: Map<String, String> = emptyMap()
    private set
  var unrecognizedRecords: Int = 0
    private set

  private val names = HashMap<Int, String>()
  private val splitter = CsvRecordSplitter()
  private val buffer = ByteArray(chunkBytes)
  private var activeHandle: Int? = null
  private var activeName: String? = null
  private var offset = 0L

  @Throws(IOException::class)
  fun poll(): PollOutcome {
    val handles = source.listRootHandles()
    names.keys.retainAll(handles.toSet())
    for (handle in handles) {
      if (handle !in names) {
        source.info(handle)?.let { names[handle] = it.name }
      }
    }

    val newest = ShurikenLogName.newest(names.values) ?: return PollOutcome.NoLogFile
    val handle = names.entries.first { it.value == newest.fileName }.key

    val switched = handle != activeHandle || newest.fileName != activeName
    if (switched) {
      startFile(handle, newest.fileName)
    }

    // The handle is still listed, so a missing info is a transient failure
    // (e.g. the controller answered DEVICE_BUSY), not a deleted file.
    val info = source.info(handle) ?: throw IOException("Object info unavailable for ${newest.fileName}")
    if (info.name != newest.fileName) {
      // The handle now points at a different directory entry; rebuild the
      // name cache on the next poll.
      names.clear()
      activeHandle = null
      activeName = null
      return PollOutcome.NoLogFile
    }
    if (info.size < offset) {
      // Shorter than what was already read: not the same file any more.
      startFile(handle, info.name)
    }

    var rows = 0
    var readThisPoll = 0L
    while (offset < info.size && readThisPoll < maxBytesPerPoll) {
      val want = minOf(chunkBytes.toLong(), info.size - offset, maxBytesPerPoll - readThisPoll).toInt()
      val got = source.read(handle, offset, buffer, want)
      if (got <= 0) {
        break
      }
      offset += got
      readThisPoll += got
      for (record in splitter.feed(buffer, 0, got)) {
        rows += consume(record)
      }
    }

    return PollOutcome.Tailing(
      fileName = info.name,
      bytesRead = offset,
      fileSize = info.size,
      newRows = rows,
      switchedFile = switched,
    )
  }

  private fun startFile(handle: Int, name: String) {
    activeHandle = handle
    activeName = name
    offset = 0
    splitter.reset()
    table.clear()
    preHeader = emptyMap()
    unrecognizedRecords = 0
  }

  private fun consume(record: String): Int {
    when (val parsed = WigleCsv.parseRecord(record)) {
      is WigleRecord.Wifi -> {
        table.add(parsed.sighting)
        return 1
      }
      is WigleRecord.PreHeader -> preHeader = parsed.fields
      WigleRecord.ColumnHeader -> Unit
      is WigleRecord.Unrecognized -> unrecognizedRecords++
    }
    return 0
  }

  companion object {
    // The controller holds its SD lock for the whole GetPartialObject transfer,
    // which pauses CSV writes, so reads are kept short. Its MTP endpoint buffer
    // is 16 KiB (CFG_TUD_MTP_EP_BUFSIZE).
    const val DEFAULT_CHUNK_BYTES = 16 * 1024
    const val DEFAULT_MAX_BYTES_PER_POLL = 256L * 1024
  }
}
