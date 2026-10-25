package com.wifishuriken.companion.ui

import com.wifishuriken.companion.ui.live.phoneGpsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneGpsStatusTest {
  @Test
  fun statusCoversEveryState() {
    assertEquals("Phone GPS on", phoneGpsStatus(connected = true, feeding = true, feedEnabled = true, phoneFix = true))
    assertEquals("Phone GPS: waiting for fix", phoneGpsStatus(connected = true, feeding = false, feedEnabled = true, phoneFix = false))
    assertNull(phoneGpsStatus(connected = true, feeding = false, feedEnabled = false, phoneFix = true))
    assertEquals("Phone GPS: fix", phoneGpsStatus(connected = false, feeding = false, feedEnabled = true, phoneFix = true))
    assertEquals("Phone GPS: no fix", phoneGpsStatus(connected = false, feeding = false, feedEnabled = true, phoneFix = false))
  }
}
