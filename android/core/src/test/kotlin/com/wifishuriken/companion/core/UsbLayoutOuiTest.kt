package com.wifishuriken.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbLayoutOuiTest {
  // Firmware layout: Console CDC pair, MTP, NMEA CDC pair (names only on the control interfaces).
  private val layout = listOf(
    IfaceDesc(0, 2, "Console"),
    IfaceDesc(1, 10, null),
    IfaceDesc(2, 6, "MTP"),
    IfaceDesc(3, 2, "NMEA"),
    IfaceDesc(4, 10, null),
  )

  @Test
  fun pairsNmeaDataAfterControl() {
    assertEquals(ShurikenInterfaces(mtp = 2, nmeaControl = 3, nmeaData = 4), findInterfaces(layout))
  }

  @Test
  fun neverPicksConsoleData() {
    // NMEA control present but no data interface after it: must not fall back to Console's data.
    val broken = layout.filter { it.number != 4 }
    assertNull(findInterfaces(broken))
  }

  @Test
  fun missingNmeaReturnsNull() {
    assertNull(findInterfaces(layout.filter { it.name != "NMEA" && it.number != 4 }))
    assertNull(findInterfaces(layout.filter { it.name != "MTP" }))
  }

  @Test
  fun ouiLookupNormalizesMac() {
    val table = OuiTable(sequenceOf("AABBCC\tExample Vendor", "malformed line", "00005E\tIANA Example"))
    assertEquals("Example Vendor", table.vendor("aa-bb-cc-dd-ee-01"))
    assertEquals("IANA Example", table.vendor("00:00:5E:00:53:01"))
  }

  @Test
  fun ouiUnknownPrefix() {
    assertNull(OuiTable(sequenceOf("AABBCC\tExample Vendor")).vendor("11:22:33:44:55:66"))
    assertNull(OuiTable(emptySequence()).vendor("not a mac"))
  }
}
