package com.wifishuriken.companion.core

import kotlin.math.floor
import kotlin.math.pow

data class SightingPoint(val lat: Double, val lon: Double, val rssi: Int?, val tripId: Long)

enum class EstimateMethod { NONE, MOBILE, EDGE_OF_RANGE, RSSI_CENTROID }

enum class Confidence { NONE, LOW, MEDIUM, HIGH }

data class PositionEstimate(
  val lat: Double?,
  val lon: Double?,
  val method: EstimateMethod,
  val confidence: Confidence?,
  val radiusM: Double?,
  val maxSpreadM: Double?,
)

private const val MOBILE_DISTANCE_M = 1_500.0
private const val RADIUS_24_M = 150.0
private const val RADIUS_5_M = 80.0
private const val MIN_RADIUS_M = 30.0
private const val MISSING_RSSI_DBM = -90
private const val QUADRANT_MIN_DISTANCE_M = 10.0

/** Same method as the desktop pipeline (design §8.4). [points] are positioned sightings in time order. */
fun estimatePosition(points: List<SightingPoint>, band: Band?): PositionEstimate {
  if (points.isEmpty()) return PositionEstimate(null, null, EstimateMethod.NONE, null, null, null)
  val spread = Geo.maxPairwiseM(points.map { it.lat to it.lon })
  if (spread > MOBILE_DISTANCE_M) {
    val last = points.last()
    return PositionEstimate(last.lat, last.lon, EstimateMethod.MOBILE, Confidence.NONE, null, spread)
  }
  if (points.size == 1) {
    val radius = if (band == Band.GHZ_5) RADIUS_5_M else RADIUS_24_M
    return PositionEstimate(points[0].lat, points[0].lon, EstimateMethod.EDGE_OF_RANGE, Confidence.LOW, radius, 0.0)
  }
  val weights = points.map { 10.0.pow((it.rssi ?: MISSING_RSSI_DBM) / 20.0) }
  val total = weights.sum()
  val lat = points.indices.sumOf { points[it].lat * weights[it] } / total
  val lon = points.indices.sumOf { points[it].lon * weights[it] } / total
  val trips = points.map { it.tripId }.toSet().size
  val quadrants = points
    .filter { Geo.haversineM(lat, lon, it.lat, it.lon) > QUADRANT_MIN_DISTANCE_M }
    .map { floor(Geo.bearingDeg(lat, lon, it.lat, it.lon) / 90.0).toInt() }
    .toSet().size
  val confidence = when {
    trips >= 3 && quadrants >= 3 -> Confidence.HIGH
    trips >= 2 -> Confidence.MEDIUM
    else -> Confidence.LOW
  }
  val meanDistance = points.indices.sumOf { Geo.haversineM(lat, lon, points[it].lat, points[it].lon) * weights[it] } / total
  return PositionEstimate(lat, lon, EstimateMethod.RSSI_CENTROID, confidence, maxOf(MIN_RADIUS_M, meanDistance), spread)
}
