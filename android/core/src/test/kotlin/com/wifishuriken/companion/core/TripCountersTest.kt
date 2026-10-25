package com.wifishuriken.companion.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TripCountersTest {
  private fun s(
    mac: String,
    channel: Int = 6,
    auth: String = "[WPA2-PSK-CCMP][ESS]",
    lat: Double? = 1.0,
    lon: Double? = 1.0,
  ) = ApSighting(mac, "net", auth, "2026-01-01 12:00:00", channel, -60, lat, lon, null, null)

  @Test
  fun uniqueMacCountedOnce() {
    val c = TripCounters(knownBefore = { false })
    c.add(s("AA:BB:CC:DD:EE:01"), 0)
    c.add(s("AA:BB:CC:DD:EE:01"), 1_000)
    c.add(s("AA:BB:CC:DD:EE:02", channel = 36), 2_000)
    val snap = c.snapshot()
    assertEquals(1, snap.ghz24)
    assertEquals(1, snap.ghz5)
    assertEquals(2, snap.withGps)
  }

  @Test
  fun gpsUpgradeMovesBetweenCounters() {
    val c = TripCounters(knownBefore = { false })
    c.add(s("AA:BB:CC:DD:EE:01", lat = null, lon = null), 0)
    assertEquals(0, c.snapshot().withGps)
    assertEquals(1, c.snapshot().noGps)
    c.add(s("AA:BB:CC:DD:EE:01"), 1_000)
    assertEquals(1, c.snapshot().withGps)
    assertEquals(0, c.snapshot().noGps)
    c.add(s("AA:BB:CC:DD:EE:01", lat = null, lon = null), 2_000)
    assertEquals(1, c.snapshot().withGps)
  }

  @Test
  fun newVsSeenBefore() {
    val known = setOf("AA:BB:CC:DD:EE:01")
    val c = TripCounters(knownBefore = { it in known })
    c.add(s("AA:BB:CC:DD:EE:01"), 0)
    c.add(s("AA:BB:CC:DD:EE:02"), 0)
    c.add(s("AA:BB:CC:DD:EE:03"), 0)
    assertEquals(2, c.snapshot().new)
    assertEquals(1, c.snapshot().seenBefore)
  }

  @Test
  fun openCountsOpenOrOwe() {
    val c = TripCounters(knownBefore = { false })
    c.add(s("AA:BB:CC:DD:EE:01", auth = "[OPEN][ESS]"), 0)
    c.add(s("AA:BB:CC:DD:EE:02", auth = "[ESS]"), 0)
    c.add(s("AA:BB:CC:DD:EE:03", auth = "[WEP][ESS]"), 0)
    assertEquals(2, c.snapshot().open)
  }

  @Test
  fun perMinuteBuckets() {
    val c = TripCounters(knownBefore = { false })
    c.add(s("AA:BB:CC:DD:EE:01"), 0)
    c.add(s("AA:BB:CC:DD:EE:02"), 30_000)
    c.add(s("AA:BB:CC:DD:EE:01"), 50_000)
    c.add(s("AA:BB:CC:DD:EE:03"), 61_000)
    c.add(s("AA:BB:CC:DD:EE:04"), 185_000)
    assertEquals(listOf(2, 1, 0, 1), c.snapshot().perMinute)
  }

  @Test
  fun perMinuteKeepsLast30Buckets() {
    val c = TripCounters(knownBefore = { false })
    for (i in 0 until 40) c.add(s("AA:BB:CC:DD:EE:%02X".format(i)), i * 60_000L)
    assertEquals(30, c.snapshot().perMinute.size)
  }

  @Test
  fun distanceSkipsGlitch() {
    val a = TrackPoint(0, 1.0, 1.0)
    val glitch = TrackPoint(1_000, 1.0, 1.0025) // ~278 m in 1 s = ~1,000 km/h
    val c = TrackPoint(60_000, 1.001, 1.0)
    val direct = Geo.haversineM(a.lat, a.lon, c.lat, c.lon)
    assertEquals(direct, trackDistanceM(listOf(a, glitch, c)), 0.01)
    assertEquals(0.0, trackDistanceM(listOf(a)), 0.0)
  }
}
