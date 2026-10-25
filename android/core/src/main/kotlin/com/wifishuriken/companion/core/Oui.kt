package com.wifishuriken.companion.core

/** IEEE MA-L (24-bit) vendor lookup from asset lines `AABBCC<TAB>Vendor`. */
class OuiTable(lines: Sequence<String>) {
  private val vendors: Map<String, String> = lines.mapNotNull { line ->
    val tab = line.indexOf('\t')
    if (tab != 6) return@mapNotNull null
    val prefix = line.substring(0, 6).uppercase()
    if (!prefix.all { it in '0'..'9' || it in 'A'..'F' }) null else prefix to line.substring(7).trim()
  }.toMap()

  fun vendor(mac: String): String? {
    val normalized = Mac.normalize(mac) ?: return null
    return vendors[normalized.replace(":", "").substring(0, 6)]
  }
}
