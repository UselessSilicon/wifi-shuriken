package com.wifishuriken.companion.core

data class CounterSnapshot(
  val ghz24: Int,
  val ghz5: Int,
  val withGps: Int,
  val noGps: Int,
  val new: Int,
  val seenBefore: Int,
  val open: Int,
  val perMinute: List<Int>,
)

/**
 * Live counters for the current trip (design §6.1): unique networks this trip, split by band,
 * by whether any sighting this trip had a position, by new vs seen on an earlier trip, plus the
 * open count and new unique networks per minute.
 */
class TripCounters(
  private val knownBefore: (mac: String) -> Boolean,
  private val bucketMs: Long = 60_000,
) {
  private class Entry(val band: Band?, val open: Boolean, val new: Boolean, var positioned: Boolean)

  private val entries = HashMap<String, Entry>()
  private val buckets = ArrayList<Int>()
  private var startMs: Long? = null

  fun add(s: ApSighting, nowMs: Long) {
    val existing = entries[s.mac]
    if (existing != null) {
      if (s.hasLocation) existing.positioned = true
      return
    }
    entries[s.mac] = Entry(
      band = Band.fromChannel(s.channel),
      open = classifySecurity(s.authMode) == SecurityClass.OPEN_OR_OWE,
      new = !knownBefore(s.mac),
      positioned = s.hasLocation,
    )
    val start = startMs ?: nowMs.also { startMs = it }
    val index = ((nowMs - start) / bucketMs).toInt().coerceAtLeast(0)
    while (buckets.size <= index) buckets.add(0)
    buckets[index]++
  }

  fun snapshot(): CounterSnapshot {
    val all = entries.values
    val withGps = all.count { it.positioned }
    val new = all.count { it.new }
    return CounterSnapshot(
      ghz24 = all.count { it.band == Band.GHZ_2_4 },
      ghz5 = all.count { it.band == Band.GHZ_5 },
      withGps = withGps,
      noGps = all.size - withGps,
      new = new,
      seenBefore = all.size - new,
      open = all.count { it.open },
      perMinute = buckets.takeLast(MAX_BUCKETS).toList(),
    )
  }

  private companion object {
    const val MAX_BUCKETS = 30
  }
}
