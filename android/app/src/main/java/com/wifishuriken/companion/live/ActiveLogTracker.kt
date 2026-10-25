package com.wifishuriken.companion.live

import com.wifishuriken.companion.core.CardFile
import com.wifishuriken.companion.core.CsvRecord

/**
 * Decides which log is live. When plugging in powers the Shuriken on, the newest file on the card
 * is still the previous trip's finished log, so the first file seen (an empty listing while the SD
 * card mounts does not count) is live only once it grows; a newer file is live immediately.
 *
 * Records read from that first file before it grows are held, not dropped: if it turns out to be
 * the log being written (the Shuriken was already running), Live still covers the whole log.
 */
class ActiveLogTracker {
  private var baseline: CardFile? = null
  private var live: String? = null
  private val held = ArrayList<CsvRecord>()

  fun observe(newest: CardFile?): String? {
    if (newest == null) return live
    val base = baseline
    when {
      base == null -> baseline = newest
      newest.name != base.name || newest.size > base.size -> {
        if (newest.name != base.name) held.clear()
        baseline = newest
        live = newest.name
      }
    }
    return live
  }

  /** Records of [name] to ingest now: held ones first once [name] is live; others are held or dropped. */
  fun accept(name: String, records: List<CsvRecord>): List<CsvRecord> = when (name) {
    live -> (held + records).also { held.clear() }
    baseline?.name -> {
      held += records
      emptyList()
    }
    else -> emptyList()
  }
}
