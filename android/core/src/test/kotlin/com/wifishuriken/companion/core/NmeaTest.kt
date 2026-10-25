package com.wifishuriken.companion.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {
  /** Builds a sentence with a correct checksum (synthetic coordinates only). */
  private fun s(body: String): String {
    val sum = body.fold(0) { acc, c -> acc xor c.code }
    return "$" + body + "*" + "%02X".format(sum)
  }

  private val gga = s("GNGGA,120000.00,0100.000,N,00100.000,E,1,09,0.9,10.0,M,0.0,M,,")
  private val rmc = s("GPRMC,120000.00,A,0100.000,N,00100.000,E,0.0,0.0,010126,,,A")

  @Test
  fun onlyGgaAndRmcForwarded() {
    assertTrue(Nmea.isForwardable(gga))
    assertTrue(Nmea.isForwardable(rmc))
    assertTrue(Nmea.isForwardable(s("GLGGA,120000.00,,,,,0,00,,,M,,M,,")))
    assertFalse(Nmea.isForwardable(s("GPGSV,1,1,01,01,40,083,46")))
    assertFalse(Nmea.isForwardable(s("GPTXT,01,01,02,ANTSTATUS=OPEN")))
    assertFalse(Nmea.isForwardable(gga.dropLast(2) + "00"))
    assertFalse(Nmea.isForwardable("GNGGA,no-dollar"))
  }

  @Test
  fun checksumValidation() {
    assertTrue(Nmea.checksumOk(rmc))
    assertFalse(Nmea.checksumOk(rmc.replace("A,0100", "V,0100")))
    assertFalse(Nmea.checksumOk("\$GPRMC,no-checksum"))
  }

  @Test
  fun frameAddsCrLf() {
    val framed = Nmea.frame("  $rmc\r\n")
    assertArrayEquals((rmc + "\r\n").toByteArray(Charsets.US_ASCII), framed)
    assertEquals(1, framed.count { it == 13.toByte() })
    assertEquals(1, framed.count { it == 10.toByte() })
  }

  @Test
  fun assemblerDropsFirstPartialLineAndSplits() {
    val a = NmeaLineAssembler()
    val first = "PARTIAL\r\n$gga\r\n\$GN".toByteArray()
    val second = (rmc.removePrefix("\$GN").let { "RMC" + it.removePrefix("\$GPRMC") }).toByteArray()
    val out = a.feed(first, first.size) + a.feed("${second.decodeToString()}\r\n".toByteArray(), second.size + 2)
    assertEquals(listOf(gga, "\$GNRMC" + rmc.removePrefix("\$GPRMC")), out)
  }

  @Test
  fun assemblerDiscardsOverlongLines() {
    val a = NmeaLineAssembler()
    val junk = "x".repeat(200)
    val bytes = "\n$junk\n$gga\n".toByteArray()
    assertEquals(listOf(gga), a.feed(bytes, bytes.size))
  }

  @Test
  fun moduleFixFromGga() {
    assertEquals(ModuleFix(true, 9, 0.9), parseModuleFix(gga))
    assertEquals(ModuleFix(false, null, null), parseModuleFix(s("GNGGA,120000.00,,,,,0,,,,M,,M,,")))
    assertNull(parseModuleFix(rmc))
    assertNull(parseModuleFix(gga.dropLast(2) + "00"))
  }
}
