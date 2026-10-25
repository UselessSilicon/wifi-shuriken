package com.wifishuriken.companion.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
  private const val EARTH_RADIUS_M = 6_371_000.0

  fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
  }

  /** Initial bearing from point 1 to point 2, 0-360 degrees clockwise from north. */
  fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
  }

  /** Largest distance between any two points; above 300 points the bounding-box diagonal (an upper bound). */
  fun maxPairwiseM(points: List<Pair<Double, Double>>): Double {
    if (points.size < 2) return 0.0
    if (points.size > 300) {
      return haversineM(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second })
    }
    var best = 0.0
    for (i in points.indices) {
      for (j in i + 1 until points.size) {
        best = maxOf(best, haversineM(points[i].first, points[i].second, points[j].first, points[j].second))
      }
    }
    return best
  }
}
