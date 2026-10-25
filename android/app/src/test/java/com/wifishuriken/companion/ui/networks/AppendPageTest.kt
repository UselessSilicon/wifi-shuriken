package com.wifishuriken.companion.ui.networks

import org.junit.Assert.assertEquals
import org.junit.Test

class AppendPageTest {
  @Test
  fun networkThatMovedAcrossPageBoundaryAppearsOnce() {
    // Live ingest re-sorted the list between two page loads, so "02" comes back on page two too.
    val page1 = listOf("01", "02", "03")
    val page2 = listOf("02", "04")
    assertEquals(listOf("01", "02", "03", "04"), appendPage(page1, page2) { it })
  }
}
