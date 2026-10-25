package com.wifishuriken.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSyncTest {
  private var nextHandle = 1
  private fun f(name: String, size: Long = 100) = CardFile(name, size, nextHandle++)

  @Test
  fun holdsBackNewestAndIgnoresNonLogs() {
    val older = f("shuriken_20260101000000.csv")
    val newest = f("shuriken_20260102000000.csv")
    val plan = planSync(listOf(f("RESETLOG.CSV"), f("controller.applied.bin"), older, newest), HashIndex(emptyList()))
    assertEquals("shuriken_20260102000000.csv", plan.heldBack?.name)
    assertEquals(listOf(older), plan.toFetch)
  }

  @Test
  fun suffixOrdering() {
    val base = f("shuriken_20260102000000.csv")
    val suffixed = f("shuriken_20260102000000_02.csv")
    val plan = planSync(listOf(suffixed, base), HashIndex(emptyList()))
    assertEquals(suffixed, plan.heldBack)
    assertEquals(listOf(base), plan.toFetch)
  }

  @Test
  fun nameAndSizeMatchSkippedWithoutFetch() {
    val known = f("shuriken_20260101000000.csv", 500)
    val grown = f("shuriken_20260101010000.csv", 900)
    val index = HashIndex(listOf(IndexEntry("aa", known.name, 500), IndexEntry("bb", grown.name, 800)))
    val plan = planSync(listOf(known, grown, f("shuriken_20260109000000.csv")), index)
    assertEquals(listOf(known), plan.skipped)
    assertEquals(listOf(grown), plan.toFetch)
  }

  @Test
  fun sameContentDifferentNameIsDuplicate() {
    val index = HashIndex(listOf(IndexEntry("abc123", "shuriken_20260101000000.csv", 100)))
    assertEquals(FetchedDecision.DuplicateContent, decideFetched("abc123", index))
    assertEquals(FetchedDecision.Archive, decideFetched("def456", index))
    val other = f("shuriken_20260103000000.csv", 100)
    val updated = index.plus(IndexEntry("abc123", other.name, 100))
    val plan = planSync(listOf(other, f("shuriken_20260109000000.csv")), updated)
    assertEquals(listOf(other), plan.skipped)
    assertTrue(plan.toFetch.isEmpty())
  }

  @Test
  fun unchangedIndexReplansSameFile() {
    val older = f("shuriken_20260101000000.csv")
    val card = listOf(older, f("shuriken_20260102000000.csv"))
    val index = HashIndex(emptyList())
    assertEquals(planSync(card, index).toFetch, planSync(card, index).toFetch)
    assertEquals(listOf(older), planSync(card, index).toFetch)
  }

  @Test
  fun indexRoundTripAndMalformedLines() {
    val index = HashIndex(listOf(IndexEntry("0123abcd", "shuriken_20260101000000.csv", 42)))
    val parsed = HashIndex.parse(index.serialize() + "only\ttwo\n\n")
    assertEquals(index.serialize(), parsed.serialize())
    assertTrue(parsed.containsMd5("0123abcd"))
    assertTrue(parsed.hasNameAndSize("shuriken_20260101000000.csv", 42))
    assertFalse(parsed.hasNameAndSize("shuriken_20260101000000.csv", 43))
    assertEquals("0123abcd\t42\tshuriken_20260101000000.csv\n", index.serialize())
  }

  @Test
  fun md5KnownValue() {
    assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc".toByteArray()))
    assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hexOfStream("abc".byteInputStream()))
  }
}
