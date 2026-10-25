package com.wifishuriken.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
  private fun csvRow(
    mac: String = "AA:BB:CC:DD:EE:01",
    ssid: String = "net",
    auth: String = "[WPA2-PSK-CCMP][ESS]",
    time: String = "2026-01-01 12:00:00",
    channel: String = "6",
    rssi: String = "-70",
    lat: String = "1.0000000",
    lon: String = "1.0000000",
  ): String {
    val quoted = "\"" + ssid.replace("\"", "\"\"") + "\""
    return "$mac,$quoted,$auth,$time,$channel,$rssi,$lat,$lon,12.0,3.5,WIFI"
  }

  private fun parse(row: String) = WigleCsv.parseRecord(row)

  @Test
  fun normalizesMacForms() {
    assertEquals("AA:BB:CC:DD:EE:01", Mac.normalize("aa-bb-cc-dd-ee-01"))
    assertEquals("AA:BB:CC:DD:EE:01", Mac.normalize("aabbccddee01"))
    assertEquals("AA:BB:CC:DD:EE:01", Mac.normalize("  aa:bb:cc:dd:ee:01 "))
    assertNull(Mac.normalize("00:00:00:00:00:00"))
    assertNull(Mac.normalize("zz:bb:cc:dd:ee:01"))
    assertNull(Mac.normalize("AA:BB:CC:DD:EE"))
  }

  @Test
  fun randomizedBit() {
    assertTrue(Mac.isRandomized("AA:BB:CC:DD:EE:01"))
    assertFalse(Mac.isRandomized("00:00:5E:00:53:01"))
  }

  @Test
  fun securityClasses() {
    assertEquals(SecurityClass.ENTERPRISE, classifySecurity("[WPA2-EAP-CCMP][ESS]"))
    assertEquals(SecurityClass.WPA2_WPA3, classifySecurity("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
    assertEquals(SecurityClass.WPA3, classifySecurity("[RSN-PSK-CCMP][ESS]"))
    assertEquals(SecurityClass.WPA2, classifySecurity("[WPA2-PSK-CCMP][ESS]"))
    assertEquals(SecurityClass.WPA, classifySecurity("[WPA-PSK-TKIP][ESS]"))
    assertEquals(SecurityClass.WEP, classifySecurity("[WEP][ESS]"))
    assertEquals(SecurityClass.OPEN_OR_OWE, classifySecurity("[OPEN][ESS]"))
    assertEquals(SecurityClass.OPEN_OR_OWE, classifySecurity(""))
  }

  @Test
  fun bandFromChannel() {
    assertEquals(Band.GHZ_2_4, Band.fromChannel(1))
    assertEquals(Band.GHZ_2_4, Band.fromChannel(14))
    assertEquals(Band.GHZ_5, Band.fromChannel(32))
    assertEquals(Band.GHZ_5, Band.fromChannel(177))
    assertNull(Band.fromChannel(0))
    assertNull(Band.fromChannel(20))
    assertNull(Band.fromChannel(null))
  }

  @Test
  fun rejectsBadRows() {
    val bad = listOf(
      csvRow(channel = "197"),
      csvRow(rssi = "1"),
      csvRow(rssi = "-151"),
      csvRow(time = "2026/01/01 12:00:00"),
      csvRow(time = "1999-01-01 12:00:00"),
      csvRow(lat = "91.0"),
      csvRow(lon = "-181.0"),
      csvRow(lat = "0.0", lon = "0.0"),
      csvRow(mac = "00:00:00:00:00:00"),
      csvRow(mac = "nonsense"),
    )
    for (row in bad) {
      assertTrue("expected rejection: $row", parse(row) is WigleRecord.Rejected)
    }
    assertTrue(parse(csvRow(channel = "0")) is WigleRecord.Wifi)
    assertTrue(parse(csvRow(rssi = "-150")) is WigleRecord.Wifi)
    assertTrue(parse(csvRow(time = "2079-12-31 23:59:59")) is WigleRecord.Wifi)
  }

  @Test
  fun emptyCoordinatesKeptWithoutPosition() {
    val wifi = parse(csvRow(lat = "", lon = "")) as WigleRecord.Wifi
    assertNull(wifi.sighting.latitude)
    assertNull(wifi.sighting.longitude)
    assertFalse(wifi.sighting.hasLocation)
  }

  @Test
  fun macNormalizedInSighting() {
    val wifi = parse(csvRow(mac = "aa-bb-cc-dd-ee-01")) as WigleRecord.Wifi
    assertEquals("AA:BB:CC:DD:EE:01", wifi.sighting.mac)
  }

  @Test
  fun ssidNulRemovedAndEmptyIsHidden() {
    val nul = parse(csvRow(ssid = "a\u0000b")) as WigleRecord.Wifi
    assertEquals("ab", nul.sighting.ssid)
    val hidden = parse(csvRow(ssid = "")) as WigleRecord.Wifi
    assertEquals("", hidden.sighting.ssid)
  }
}
