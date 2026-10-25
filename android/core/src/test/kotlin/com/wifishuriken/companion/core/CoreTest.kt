package com.wifishuriken.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PRE_HEADER =
  "WigleWifi-1.4,appRelease=0.2,model=WiFi-Shuriken,release=0.2,device=WiFi-Shuriken," +
    "display=none,board=WiFi-Shuriken,brand=CoD_Segfault\n"
private const val COLUMNS =
  "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude," +
    "AltitudeMeters,AccuracyMeters,Type\n"

private fun row(
  mac: String,
  ssid: String,
  rssi: Int,
  channel: Int = 6,
  auth: String = "[WPA2-PSK-CCMP][ESS]",
  time: String = "2026-10-15 12:00:00",
  lat: String = "51.5000000",
  lon: String = "-0.1200000",
): String {
  val quoted = "\"" + ssid.replace("\"", "\"\"") + "\""
  return "$mac,$quoted,$auth,$time,$channel,$rssi,$lat,$lon,12.0,3.5,WIFI\n"
}

class CsvRecordSplitterTest {
  @Test
  fun splitsOnNewlinesOutsideQuotesOnly() {
    val splitter = CsvRecordSplitter()
    val text = row("AA:BB:CC:DD:EE:01", "multi\nline", -50) + row("AA:BB:CC:DD:EE:02", "b", -60)
    val records = splitter.feed(text.toByteArray())
    assertEquals(2, records.size)
    assertTrue(records[0].contains("multi\nline"))
  }

  @Test
  fun buffersPartialRecordsAcrossChunks() {
    val splitter = CsvRecordSplitter()
    val bytes = (row("AA:BB:CC:DD:EE:01", "café 日本", -50)).toByteArray()
    val out = ArrayList<String>()
    // Feed one byte at a time so multi-byte UTF-8 sequences are split.
    for (b in bytes) {
      out += splitter.feed(byteArrayOf(b))
    }
    assertEquals(1, out.size)
    val parsed = WigleCsv.parseRecord(out[0]) as WigleRecord.Wifi
    assertEquals("café 日本", parsed.sighting.ssid)
    assertEquals(0, splitter.pendingBytes())
  }

  @Test
  fun stripsCarriageReturn() {
    val records = CsvRecordSplitter().feed("a,b\r\n".toByteArray())
    assertEquals(listOf("a,b"), records)
  }

  @Test
  fun resynchronisesAfterUnterminatedQuote() {
    val splitter = CsvRecordSplitter(maxRecordBytes = 200)
    val garbage = "\"" + "x".repeat(300) + "\n"
    val good = row("AA:BB:CC:DD:EE:03", "ok", -40)
    val records = splitter.feed((garbage + "tail of bad record\n" + good).toByteArray())
    assertEquals(1, splitter.droppedRecords)
    // The oversized record is dropped up to the next newline; what follows is
    // split normally, leaving one junk record and the intact row after it.
    assertEquals(2, records.size)
    assertTrue(WigleCsv.parseRecord(records[0]) is WigleRecord.Unrecognized)
    assertTrue(WigleCsv.parseRecord(records[1]) is WigleRecord.Wifi)
  }
}

class WigleCsvTest {
  @Test
  fun parsesHeadersAndRows() {
    val pre = WigleCsv.parseRecord(PRE_HEADER.trimEnd()) as WigleRecord.PreHeader
    assertEquals("WigleWifi-1.4", pre.fields["format"])
    assertEquals("0.2", pre.fields["appRelease"])
    assertEquals(WigleRecord.ColumnHeader, WigleCsv.parseRecord(COLUMNS.trimEnd()))

    val wifi = WigleCsv.parseRecord(row("aa:bb:cc:dd:ee:01", "Say \"hi\", ok", -71, channel = 149).trimEnd())
      as WigleRecord.Wifi
    with(wifi.sighting) {
      assertEquals("AA:BB:CC:DD:EE:01", mac)
      assertEquals("Say \"hi\", ok", ssid)
      assertEquals(149, channel)
      assertEquals(-71, rssi)
      assertEquals(51.5, latitude!!, 1e-9)
      assertEquals(3.5, accuracyMeters!!, 1e-9)
    }
  }

  @Test
  fun blankLocationFieldsAreNull() {
    val wifi = WigleCsv.parseRecord(row("AA:BB:CC:DD:EE:01", "", -80, lat = "", lon = "").trimEnd())
      as WigleRecord.Wifi
    assertNull(wifi.sighting.latitude)
    assertTrue(!wifi.sighting.hasLocation)
  }

  @Test
  fun rejectsMalformedRows() {
    assertTrue(WigleCsv.parseRecord("garbage") is WigleRecord.Unrecognized)
    // An 11-field WIFI row that fails validation (bad MAC, time, channel) is rejected, not unrecognized.
    assertTrue(
      WigleCsv.parseRecord("AA,\"s\",[OPEN],t,x,-1,,,,,WIFI") is WigleRecord.Rejected,
    )
  }
}

class ShurikenLogNameTest {
  @Test
  fun picksLatestTimestampThenSuffix() {
    val names = listOf(
      "RESETLOG.CSV",
      "controller.applied.bin",
      "shuriken_20261014090000.csv",
      "shuriken_20261015120000.csv",
      "shuriken_20261015120000_02.csv",
      "shuriken_20261015120000_01.csv",
      "notes.txt",
    )
    assertEquals("shuriken_20261015120000_02.csv", ShurikenLogName.newest(names)?.fileName)
  }

  @Test
  fun ignoresNonLogFiles() {
    assertNull(ShurikenLogName.newest(listOf("RESETLOG.CSV", "shuriken_2026.csv", "scanner.bin")))
  }
}

class ApTableTest {
  @Test
  fun aggregatesByBssid() {
    val table = ApTable()
    fun add(r: String) = table.add((WigleCsv.parseRecord(r.trimEnd()) as WigleRecord.Wifi).sighting)
    add(row("AA:BB:CC:DD:EE:01", "home", -70, lat = "1.0", lon = "1.0"))
    add(row("AA:BB:CC:DD:EE:02", "cafe", -60, channel = 36, auth = "[OPEN][ESS]"))
    add(row("AA:BB:CC:DD:EE:01", "home", -40, lat = "2.0", lon = "2.0"))
    add(row("AA:BB:CC:DD:EE:01", "home", -90, lat = "", lon = ""))

    val snapshot = table.snapshot()
    assertEquals(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"), snapshot.map { it.mac })
    val home = snapshot[0]
    assertEquals(3, home.sightings)
    assertEquals(-40, home.bestRssi)
    assertEquals(-90, home.lastRssi)
    assertEquals(2.0, home.bestLatitude!!, 1e-9)

    val stats = table.stats()
    assertEquals(4, stats.rows)
    assertEquals(2, stats.uniqueAps)
    assertEquals(1, stats.openAps)
    assertEquals(1, stats.aps24)
    assertEquals(1, stats.aps5)
    assertEquals(1, stats.rowsWithoutLocation)
    assertEquals(2.0, stats.lastLatitude!!, 1e-9)
  }
}

/** In-memory stand-in for the controller's MTP storage. */
private class FakeSource : RootFileSource {
  val files = LinkedHashMap<Int, Pair<String, StringBuilder>>()
  var reads = 0
  var failInfo = false

  fun put(handle: Int, name: String, content: String = "") {
    files[handle] = name to StringBuilder(content)
  }

  fun append(handle: Int, text: String) {
    files.getValue(handle).second.append(text)
  }

  override fun listRootHandles(): IntArray = files.keys.toIntArray()

  override fun info(handle: Int): RootFileSource.Info? =
    if (failInfo) null else files[handle]?.let { RootFileSource.Info(it.first, it.second.toString().toByteArray().size.toLong()) }

  override fun read(handle: Int, offset: Long, buffer: ByteArray, length: Int): Int {
    reads++
    val bytes = files.getValue(handle).second.toString().toByteArray()
    val n = minOf(length.toLong(), bytes.size - offset).toInt()
    System.arraycopy(bytes, offset.toInt(), buffer, 0, n)
    return n
  }
}

class LogTailerTest {
  @Test
  fun noLogUntilFileExists() {
    val source = FakeSource()
    source.put(1, "RESETLOG.CSV", "TimestampUTC,ResetReason,ResetCode,AppVersion\n")
    assertEquals(PollOutcome.NoLogFile, LogTailer(source).poll())
  }

  @Test
  fun readsOnlyAppendedBytes() {
    val source = FakeSource()
    source.put(1, "RESETLOG.CSV")
    source.put(2, "shuriken_20261015120000.csv", PRE_HEADER + COLUMNS)
    val tailer = LogTailer(source, chunkBytes = 64)

    var out = tailer.poll() as PollOutcome.Tailing
    assertEquals(0, out.newRows)
    assertTrue(out.caughtUp)
    assertEquals("0.2", tailer.preHeader["appRelease"])

    // A row that arrives in two flushes is counted once, when complete.
    val r = row("AA:BB:CC:DD:EE:01", "home", -50)
    source.append(2, r.substring(0, 20))
    out = tailer.poll() as PollOutcome.Tailing
    assertEquals(0, out.newRows)
    source.append(2, r.substring(20) + row("AA:BB:CC:DD:EE:02", "x", -60))
    out = tailer.poll() as PollOutcome.Tailing
    assertEquals(2, out.newRows)
    assertEquals(2, tailer.table.stats().uniqueAps)

    val readsBefore = source.reads
    out = tailer.poll() as PollOutcome.Tailing
    assertEquals(0, out.newRows)
    assertEquals(readsBefore, source.reads)
  }

  @Test
  fun switchesToNewerLogAndResets() {
    val source = FakeSource()
    source.put(2, "shuriken_20261015120000.csv", PRE_HEADER + COLUMNS + row("AA:BB:CC:DD:EE:01", "a", -50))
    val tailer = LogTailer(source)
    tailer.poll()
    assertEquals(1, tailer.table.stats().rows)

    source.put(3, "shuriken_20261015130000.csv", PRE_HEADER + COLUMNS)
    val out = tailer.poll() as PollOutcome.Tailing
    assertTrue(out.switchedFile)
    assertEquals("shuriken_20261015130000.csv", out.fileName)
    assertEquals(0, tailer.table.stats().rows)
  }

  @Test
  fun capsBytesPerPollDuringCatchUp() {
    val source = FakeSource()
    val body = StringBuilder(PRE_HEADER + COLUMNS)
    repeat(200) { body.append(row("AA:BB:CC:DD:%02X:%02X".format(it / 256, it % 256), "n$it", -50)) }
    source.put(1, "shuriken_20261015120000.csv", body.toString())
    val tailer = LogTailer(source, chunkBytes = 512, maxBytesPerPoll = 4096)

    var polls = 0
    var outcome: PollOutcome.Tailing
    do {
      outcome = tailer.poll() as PollOutcome.Tailing
      polls++
    } while (!outcome.caughtUp)
    assertTrue(polls > 1)
    assertEquals(200, tailer.table.stats().uniqueAps)
  }

  @Test
  fun restartsWhenFileShrinks() {
    val source = FakeSource()
    source.put(1, "shuriken_20261015120000.csv", PRE_HEADER + COLUMNS + row("AA:BB:CC:DD:EE:01", "a", -50))
    val tailer = LogTailer(source)
    tailer.poll()
    source.put(1, "shuriken_20261015120000.csv", PRE_HEADER + COLUMNS)
    val out = tailer.poll() as PollOutcome.Tailing
    assertTrue(out.caughtUp)
    assertEquals(0, tailer.table.stats().rows)
  }

  @Test
  fun transientInfoFailureKeepsState() {
    val source = FakeSource()
    source.put(1, "shuriken_20261015120000.csv", PRE_HEADER + COLUMNS + row("AA:BB:CC:DD:EE:01", "a", -50))
    val tailer = LogTailer(source)
    tailer.poll()

    source.failInfo = true
    try {
      tailer.poll()
      throw AssertionError("expected IOException")
    } catch (expected: java.io.IOException) {
    }
    source.failInfo = false

    source.append(1, row("AA:BB:CC:DD:EE:02", "b", -60))
    val out = tailer.poll() as PollOutcome.Tailing
    assertTrue(!out.switchedFile)
    assertEquals(1, out.newRows)
    assertEquals(2, tailer.table.stats().rows)
  }
}
