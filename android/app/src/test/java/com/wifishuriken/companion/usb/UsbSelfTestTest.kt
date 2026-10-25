package com.wifishuriken.companion.usb

import com.wifishuriken.companion.core.Nmea
import com.wifishuriken.companion.core.parseModuleFix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSelfTestTest {
  @Test
  fun voidRmcIsSendableButCarriesNoFix() {
    val s = UsbSelfTest.voidRmc()
    assertTrue(Nmea.isForwardable(s))
    assertFalse(s.contains(",A,"))
    assertNull(parseModuleFix(s))
  }
}
