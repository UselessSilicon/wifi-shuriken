package com.wifishuriken.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionEstimatorTest {
  private fun p(lat: Double, lon: Double, rssi: Int? = -70, trip: Long = 1) = SightingPoint(lat, lon, rssi, trip)

  @Test
  fun haversineKnownDistance() {
    // 0.001 degree of latitude is about 111.2 m anywhere.
    assertEquals(111.19, Geo.haversineM(1.0, 1.0, 1.001, 1.0), 0.05)
  }

  @Test
  fun noPoints() {
    val e = estimatePosition(emptyList(), Band.GHZ_2_4)
    assertEquals(EstimateMethod.NONE, e.method)
    assertNull(e.lat)
  }

  @Test
  fun singleSightingRadiusByBand() {
    val one = listOf(p(1.0, 1.0))
    val e24 = estimatePosition(one, Band.GHZ_2_4)
    assertEquals(EstimateMethod.EDGE_OF_RANGE, e24.method)
    assertEquals(Confidence.LOW, e24.confidence)
    assertEquals(150.0, e24.radiusM!!, 0.0)
    assertEquals(80.0, estimatePosition(one, Band.GHZ_5).radiusM!!, 0.0)
    assertEquals(150.0, estimatePosition(one, null).radiusM!!, 0.0)
  }

  @Test
  fun spreadOver1500mIsMobile() {
    val e = estimatePosition(listOf(p(1.0, 1.0), p(1.0, 1.02)), Band.GHZ_2_4)
    assertEquals(EstimateMethod.MOBILE, e.method)
    assertEquals(Confidence.NONE, e.confidence)
    assertEquals(1.0, e.lat!!, 0.0)
    assertEquals(1.02, e.lon!!, 0.0)
    assertNull(e.radiusM)
    assertTrue(e.maxSpreadM!! > 1500.0)
  }

  @Test
  fun weightedCentroidFavoursStrongSignal() {
    val e = estimatePosition(listOf(p(1.0, 1.0, -40), p(1.0, 1.001, -80)), Band.GHZ_2_4)
    assertEquals(EstimateMethod.RSSI_CENTROID, e.method)
    assertTrue("centroid lon ${e.lon}", e.lon!! < 1.0002)
  }

  @Test
  fun missingRssiWeightedAsMinus90() {
    val withNull = estimatePosition(listOf(p(1.0, 1.0, -60), p(1.0, 1.001, null)), Band.GHZ_5)
    val with90 = estimatePosition(listOf(p(1.0, 1.0, -60), p(1.0, 1.001, -90)), Band.GHZ_5)
    assertEquals(with90.lon!!, withNull.lon!!, 1e-12)
  }

  @Test
  fun confidenceRules() {
    val n = p(1.001, 1.0, trip = 1)
    val e = p(1.0, 1.001, trip = 2)
    val s = p(0.999, 1.0, trip = 3)
    val w = p(1.0, 0.999, trip = 1)
    assertEquals(Confidence.HIGH, estimatePosition(listOf(n, e, s, w), Band.GHZ_2_4).confidence)
    assertEquals(
      Confidence.MEDIUM,
      estimatePosition(listOf(n, e.copy(tripId = 2), s.copy(tripId = 1), w), Band.GHZ_2_4).confidence,
    )
    assertEquals(
      Confidence.LOW,
      estimatePosition(listOf(n, e.copy(tripId = 1), s.copy(tripId = 1), w), Band.GHZ_2_4).confidence,
    )
  }

  @Test
  fun radiusFloor30m() {
    val e = estimatePosition(listOf(p(1.0, 1.0), p(1.0, 1.00001)), Band.GHZ_2_4)
    assertEquals(30.0, e.radiusM!!, 0.0)
  }

  @Test
  fun maxPairwiseUsesBoundingBoxAbove300Points() {
    val pts = (0 until 301).map { Pair(1.0, 1.0 + it * 0.00001) }
    val direct = Geo.haversineM(1.0, 1.0, 1.0, 1.003)
    assertEquals(direct, Geo.maxPairwiseM(pts), 0.5)
  }
}
