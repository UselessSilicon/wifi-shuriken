package com.wifishuriken.companion.ui

import com.wifishuriken.companion.live.ImportOutcome
import com.wifishuriken.companion.ui.settings.summarize
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSummaryTest {
  @Test
  fun summaryCountsEachOutcome() {
    val s = summarize(
      listOf(
        ImportOutcome.Imported("a", 1200, 0), ImportOutcome.Imported("b", 34, 1),
        ImportOutcome.AlreadyImported("c"), ImportOutcome.NotShurikenCsv("d.kml"),
      ),
    )
    assertEquals("Imported 2 (1,234 rows), 1 already imported, 1 not Shuriken CSV.", s)
  }
}
