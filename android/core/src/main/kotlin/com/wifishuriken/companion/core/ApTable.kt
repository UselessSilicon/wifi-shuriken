package com.wifishuriken.companion.core

/** Aggregated view of one access point across all of its logged rows. */
data class ApEntry(
  val mac: String,
  val ssid: String,
  val authMode: String,
  val channel: Int,
  val lastRssi: Int,
  val bestRssi: Int,
  val firstSeen: String,
  val lastSeen: String,
  val sightings: Int,
  val bestLatitude: Double?,
  val bestLongitude: Double?,
  /** Monotonic counter of the last update, used to sort by recency. */
  val updateSequence: Long,
) {
  val isOpen: Boolean
    get() = authMode.contains("[OPEN]") || authMode.isBlank()

  val band: Band?
    get() = Band.fromChannel(channel)
}

data class ApStats(
  val rows: Int,
  val uniqueAps: Int,
  val openAps: Int,
  val aps24: Int,
  val aps5: Int,
  val rowsWithoutLocation: Int,
  val lastLatitude: Double?,
  val lastLongitude: Double?,
  val lastRowTime: String?,
)

/**
 * Accumulates sightings keyed by BSSID. The controller already dedupes, but
 * its table is spatial (an AP is logged again once it falls out of the FIFO),
 * so the same BSSID can appear many times in one log.
 */
class ApTable {
  private val entries = LinkedHashMap<String, ApEntry>()
  private var sequence = 0L
  private var rows = 0
  private var rowsWithoutLocation = 0
  private var lastLatitude: Double? = null
  private var lastLongitude: Double? = null
  private var lastRowTime: String? = null

  fun add(s: ApSighting) {
    rows++
    sequence++
    lastRowTime = s.firstSeen
    if (s.hasLocation) {
      lastLatitude = s.latitude
      lastLongitude = s.longitude
    } else {
      rowsWithoutLocation++
    }

    val existing = entries[s.mac]
    if (existing == null) {
      entries[s.mac] = ApEntry(
        mac = s.mac,
        ssid = s.ssid,
        authMode = s.authMode,
        channel = s.channel ?: 0,
        lastRssi = s.rssi ?: MISSING_RSSI,
        bestRssi = s.rssi ?: MISSING_RSSI,
        firstSeen = s.firstSeen,
        lastSeen = s.firstSeen,
        sightings = 1,
        bestLatitude = s.latitude,
        bestLongitude = s.longitude,
        updateSequence = sequence,
      )
      return
    }

    val rssi = s.rssi ?: MISSING_RSSI
    val stronger = rssi > existing.bestRssi
    // Keep the location of the strongest sighting as the best AP position
    // estimate, but fill it in if the earlier, stronger row had no fix.
    val useNewLocation = s.hasLocation && (stronger || existing.bestLatitude == null)
    entries[s.mac] = existing.copy(
      ssid = s.ssid.ifEmpty { existing.ssid },
      authMode = s.authMode,
      channel = s.channel ?: existing.channel,
      lastRssi = rssi,
      bestRssi = maxOf(existing.bestRssi, rssi),
      lastSeen = s.firstSeen,
      sightings = existing.sightings + 1,
      bestLatitude = if (useNewLocation) s.latitude else existing.bestLatitude,
      bestLongitude = if (useNewLocation) s.longitude else existing.bestLongitude,
      updateSequence = sequence,
    )
  }

  fun clear() {
    entries.clear()
    sequence = 0
    rows = 0
    rowsWithoutLocation = 0
    lastLatitude = null
    lastLongitude = null
    lastRowTime = null
  }

  /** All entries, most recently updated first. */
  fun snapshot(): List<ApEntry> = entries.values.sortedByDescending { it.updateSequence }

  fun stats(): ApStats {
    var open = 0
    var b24 = 0
    var b5 = 0
    for (e in entries.values) {
      if (e.isOpen) open++
      if (e.band == Band.GHZ_2_4) b24++ else if (e.band == Band.GHZ_5) b5++
    }
    return ApStats(
      rows = rows,
      uniqueAps = entries.size,
      openAps = open,
      aps24 = b24,
      aps5 = b5,
      rowsWithoutLocation = rowsWithoutLocation,
      lastLatitude = lastLatitude,
      lastLongitude = lastLongitude,
      lastRowTime = lastRowTime,
    )
  }
}

/** Used for display only when a row carried no RSSI (the firmware always writes one). */
private const val MISSING_RSSI = -100
