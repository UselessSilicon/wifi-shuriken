package com.wifishuriken.companion.live

import com.wifishuriken.companion.core.CardFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveLogTrackerTest {
  @Test
  fun newestFileIsLiveOnlyOnceItGrows() {
    val t = ActiveLogTracker()
    val previousTrip = "shuriken_20260101000000.csv"
    assertNull(t.observe(CardFile(previousTrip, 100, 1)))
    assertNull(t.observe(CardFile(previousTrip, 100, 1)))
    assertEquals(previousTrip, t.observe(CardFile(previousTrip, 140, 1)))
    assertEquals(previousTrip, t.observe(CardFile(previousTrip, 140, 1)))
  }

  @Test
  fun newerFileAppearingBecomesLive() {
    val t = ActiveLogTracker()
    assertNull(t.observe(CardFile("shuriken_20260101000000.csv", 500, 1)))
    assertEquals("shuriken_20260102000000.csv", t.observe(CardFile("shuriken_20260102000000.csv", 50, 2)))
  }

  @Test
  fun noFileYet() {
    val t = ActiveLogTracker()
    assertNull(t.observe(null))
    assertEquals("shuriken_20260102000000.csv", t.observe(CardFile("shuriken_20260102000000.csv", 50, 2)))
  }
}
