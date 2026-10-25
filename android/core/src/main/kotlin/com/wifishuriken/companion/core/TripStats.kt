package com.wifishuriken.companion.core

data class TrackPoint(val tMs: Long, val lat: Double, val lon: Double)

private const val MAX_PLAUSIBLE_KMH = 200.0

/** Track length in metres, skipping points that imply more than 200 km/h (GPS glitches). */
fun trackDistanceM(points: List<TrackPoint>): Double {
  var total = 0.0
  var last: TrackPoint? = null
  for (p in points) {
    val prev = last
    if (prev == null) {
      last = p
      continue
    }
    val d = Geo.haversineM(prev.lat, prev.lon, p.lat, p.lon)
    val seconds = (p.tMs - prev.tMs) / 1000.0
    val kmh = if (seconds > 0) d / seconds * 3.6 else if (d > 0) Double.MAX_VALUE else 0.0
    if (kmh > MAX_PLAUSIBLE_KMH) continue
    total += d
    last = p
  }
  return total
}
