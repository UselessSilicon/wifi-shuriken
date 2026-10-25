package com.wifishuriken.companion.live

import com.wifishuriken.companion.core.CardFile
import com.wifishuriken.companion.core.CsvRecord
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
  fun firstEmptyListingDoesNotMakeOldLogLive() {
    // SD card not mounted yet on the first listing: the previous trip's log must still wait to grow.
    val t = ActiveLogTracker()
    val previousTrip = "shuriken_20260101000000.csv"
    assertNull(t.observe(null))
    assertNull(t.observe(CardFile(previousTrip, 100, 1)))
    assertNull(t.observe(CardFile(previousTrip, 100, 1)))
    assertEquals(previousTrip, t.observe(CardFile(previousTrip, 120, 1)))
  }

  @Test
  fun recordsReadBeforePromotionAreReleased() {
    // Plugging into a Shuriken that is already running: rows read while the log was the baseline
    // are held and released once it grows, so Live covers the whole current log.
    val t = ActiveLogTracker()
    val log = "shuriken_20260101000000.csv"
    t.observe(CardFile(log, 100, 1))
    assertEquals(emptyList<CsvRecord>(), t.accept(log, listOf(CsvRecord(0, "a"), CsvRecord(10, "b"))))
    t.observe(CardFile(log, 120, 1))
    assertEquals(listOf(CsvRecord(0, "a"), CsvRecord(10, "b"), CsvRecord(100, "c")), t.accept(log, listOf(CsvRecord(100, "c"))))
    assertEquals(listOf(CsvRecord(120, "d")), t.accept(log, listOf(CsvRecord(120, "d"))))
  }

  @Test
  fun heldRecordsOfAFinishedLogAreDropped() {
    val t = ActiveLogTracker()
    t.observe(CardFile("shuriken_20260101000000.csv", 100, 1))
    t.accept("shuriken_20260101000000.csv", listOf(CsvRecord(0, "old")))
    t.observe(CardFile("shuriken_20260102000000.csv", 10, 2))
    assertEquals(listOf(CsvRecord(0, "new")), t.accept("shuriken_20260102000000.csv", listOf(CsvRecord(0, "new"))))
    assertEquals(emptyList<CsvRecord>(), t.accept("shuriken_20260101000000.csv", listOf(CsvRecord(50, "late"))))
  }
}
