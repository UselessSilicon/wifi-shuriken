package com.wifishuriken.companion.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class TripMode { DRIVE, WALK, BIKE }

/**
 * Guesses how a trip was made from the median segment speed of its track (design §6.2):
 * under 8 km/h walk, under 30 km/h bike, otherwise drive. Null with fewer than 3 points.
 */
fun inferTripMode(points: List<TrackPoint>): TripMode? {
  if (points.size < 3) return null
  val speeds = points.zipWithNext().mapNotNull { (a, b) ->
    val s = (b.tMs - a.tMs) / 1000.0
    if (s <= 0) null else Geo.haversineM(a.lat, a.lon, b.lat, b.lon) / s * 3.6
  }.sorted()
  if (speeds.isEmpty()) return null
  val median = speeds[speeds.size / 2]
  return when {
    median < 8.0 -> TripMode.WALK
    median < 30.0 -> TripMode.BIKE
    else -> TripMode.DRIVE
  }
}

private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Seconds between two `yyyy-MM-dd HH:mm:ss` UTC times, or null if either is missing/invalid. */
fun durationSeconds(startUtc: String, endUtc: String?): Long? {
  if (endUtc == null) return null
  return runCatching {
    Duration.between(LocalDateTime.parse(startUtc, UTC_FORMAT), LocalDateTime.parse(endUtc, UTC_FORMAT)).seconds
  }.getOrNull()
}

/** Whole percent of [part] in [total]; 0 when total is 0. */
fun percentOf(part: Int, total: Int): Int = if (total <= 0) 0 else part * 100 / total
