package com.wifishuriken.companion.core

/** BSSID normalisation, matching the desktop pipeline's rules (design §8.3). */
object Mac {
  private val PATTERN = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
  private val BARE = Regex("^[0-9A-F]{12}$")

  /** Upper-case, colon-separated form, or null if invalid or all zeros. */
  fun normalize(raw: String): String? {
    var s = raw.trim().uppercase().replace('-', ':')
    if (BARE.matches(s)) {
      s = s.chunked(2).joinToString(":")
    }
    if (!PATTERN.matches(s) || s == "00:00:00:00:00:00") return null
    return s
  }

  /** True when the locally-administered bit of the first octet is set (randomized or virtual BSSID). */
  fun isRandomized(mac: String): Boolean {
    val first = mac.take(2).toIntOrNull(16) ?: return false
    return first and 0x02 != 0
  }
}
