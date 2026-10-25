package com.wifishuriken.companion.live

import com.wifishuriken.companion.core.CardFile

/**
 * Decides which log is live. When plugging in powers the Shuriken on, the newest file on the card
 * is still the previous trip's finished log, so the file seen at the first observation only counts
 * as live once it grows; a file that appears (or a newer one) after that is live immediately.
 */
class ActiveLogTracker {
  private var observed = false
  private var baseline: CardFile? = null
  private var live: String? = null

  fun observe(newest: CardFile?): String? {
    val base = baseline
    if (newest != null) {
      when {
        !observed -> baseline = newest
        base == null || newest.name != base.name || newest.size > base.size -> {
          baseline = newest
          live = newest.name
        }
      }
    }
    observed = true
    return live
  }
}
