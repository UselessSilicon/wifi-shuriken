package com.wifishuriken.companion.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShurikenMatchTest {
  @Test
  fun onlyTheShurikenIdsMatch() {
    assertTrue(ShurikenUsb.isShurikenIds(0x2886, 0x0058))
    assertFalse(ShurikenUsb.isShurikenIds(0x2886, 0x0057))
    assertFalse(ShurikenUsb.isShurikenIds(0x04E8, 0x6860)) // a phone exposing MTP
  }

  @Test
  fun attachFilterNamesOnlyTheShuriken() {
    val xml = File("src/main/res/xml/device_filter.xml").readText()
    assertTrue(xml.contains("vendor-id=\"10374\"") && xml.contains("product-id=\"88\""))
    assertFalse(Regex("""<usb-device\s+class=""").containsMatchIn(xml))
  }
}
