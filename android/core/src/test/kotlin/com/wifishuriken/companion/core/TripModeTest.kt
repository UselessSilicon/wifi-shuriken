package com.wifishuriken.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripModeTest {
  /** Points 10 s apart moving north by [metresPerStep]. */
  private fun track(metresPerStep: Double, steps: Int = 20): List<TrackPoint> =
    (0..steps).map { TrackPoint(it * 10_000L, 1.0 + it * metresPerStep / 111_195.0, 1.0) }

  @Test
  fun modeFromMedianSpeed() {
    assertEquals(TripMode.WALK, inferTripMode(track(14.0)))   // ~5 km/h
    assertEquals(TripMode.BIKE, inferTripMode(track(55.0)))   // ~20 km/h
    assertEquals(TripMode.DRIVE, inferTripMode(track(140.0))) // ~50 km/h
  }

  @Test
  fun tooFewPointsIsUnknown() {
    assertNull(inferTripMode(track(14.0, steps = 1)))
    assertNull(inferTripMode(emptyList()))
  }

  @Test
  fun durationAndPercent() {
    assertEquals(3_661L, durationSeconds("2026-01-01 12:00:00", "2026-01-01 13:01:01"))
    assertNull(durationSeconds("2026-01-01 12:00:00", null))
    assertEquals(75, percentOf(3, 4))
    assertEquals(0, percentOf(0, 0))
  }
}
