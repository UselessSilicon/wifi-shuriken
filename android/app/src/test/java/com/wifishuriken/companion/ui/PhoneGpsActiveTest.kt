package com.wifishuriken.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneGpsActiveTest {
  @Test
  fun screenReadsGpsOnlyWhenVisibleAndNoSession() {
    assertEquals(true, phoneGpsActive(connected = false, canLocate = true, visible = true))
    assertEquals(false, phoneGpsActive(connected = false, canLocate = true, visible = false))
    assertEquals(false, phoneGpsActive(connected = true, canLocate = true, visible = true))
    assertEquals(false, phoneGpsActive(connected = false, canLocate = false, visible = true))
  }
}
