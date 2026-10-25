package com.wifishuriken.companion.ui.drives

import com.wifishuriken.companion.core.TrackPoint
import com.wifishuriken.companion.data.TripSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRowCacheTest {
  private fun trip(id: Long, rows: Int) = TripSummary(id, "shuriken_2026010${id}120000.csv", "2026-01-0$id 12:00:00", null, rows, 1, 1, 1, null)

  @Test
  fun unchangedTripsAreNotRecomputed() = runBlocking {
    val cache = DriveRowCache()
    var computed = 0
    val enrich: suspend (TripSummary) -> DriveRow = { t -> computed++; DriveRow(t, 0.0, null, 0, null, emptyList()) }
    cache.rows(listOf(trip(1, 10), trip(2, 20)), enrich)
    assertEquals(2, computed)
    // A live batch updates trip 2 only.
    cache.rows(listOf(trip(1, 10), trip(2, 25)), enrich)
    assertEquals(3, computed)
  }

  @Test
  fun downsampleKeepsEndsAndBound() {
    val track = (0 until 10_000).map { TrackPoint(it.toLong(), it * 1e-5, 0.0) }
    val d = downsample(track, 500)
    assertTrue(d.size <= 500)
    assertEquals(track.first(), d.first())
    assertEquals(track.last(), d.last())
    assertEquals(track.take(3), downsample(track.take(3), 500))
  }
}
