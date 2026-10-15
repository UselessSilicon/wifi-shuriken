package com.wifishuriken.companion.core

/**
 * Identifies the controller's scan logs among the files on the SD card root.
 *
 * The firmware names each boot's log `shuriken_<YYYYMMDDHHMMSS>.csv` from the
 * first valid GNSS time, adding `_01`..`_99` when that name already exists
 * (Logger::selectCsvLogPath in lib/pico_logging). The file being written is
 * therefore the one with the latest timestamp and, within it, the highest
 * suffix. Other files (RESETLOG.CSV, firmware images) are ignored.
 */
data class ShurikenLogName(val fileName: String, val timestamp: String, val suffix: Int) :
  Comparable<ShurikenLogName> {
  override fun compareTo(other: ShurikenLogName): Int =
    compareValuesBy(this, other, ShurikenLogName::timestamp, ShurikenLogName::suffix)

  companion object {
    private val PATTERN = Regex("""^shuriken_(\d{14})(?:_(\d{2}))?\.csv$""", RegexOption.IGNORE_CASE)

    fun parse(fileName: String): ShurikenLogName? {
      val match = PATTERN.matchEntire(fileName) ?: return null
      val suffix = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
      return ShurikenLogName(fileName, match.groupValues[1], suffix)
    }

    /** Returns the newest log among [fileNames], or null if there is none. */
    fun newest(fileNames: Iterable<String>): ShurikenLogName? =
      fileNames.mapNotNull(::parse).maxOrNull()
  }
}
