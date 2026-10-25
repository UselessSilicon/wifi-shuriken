package com.wifishuriken.companion.core

/** One WIFI row from a WiGLE CSV log written by the controller. */
data class ApSighting(
  val mac: String,
  val ssid: String,
  val authMode: String,
  val firstSeen: String,
  val channel: Int,
  val rssi: Int,
  val latitude: Double?,
  val longitude: Double?,
  val altitudeMeters: Double?,
  val accuracyMeters: Double?,
) {
  val hasLocation: Boolean
    get() = latitude != null && longitude != null
}

sealed interface WigleRecord {
  /** First line: `WigleWifi-1.4,appRelease=...,model=...`. */
  data class PreHeader(val fields: Map<String, String>) : WigleRecord

  /** Second line: the column names. */
  data object ColumnHeader : WigleRecord

  data class Wifi(val sighting: ApSighting) : WigleRecord

  data class Unrecognized(val line: String) : WigleRecord
}

/**
 * Parser for the WiGLE CSV 1.4 rows produced by buildCsvWifiRow in
 * lib/log_format/log_format.cpp:
 * MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,
 * AltitudeMeters,AccuracyMeters,Type
 */
object WigleCsv {
  private const val COLUMN_COUNT = 11

  fun parseRecord(line: String): WigleRecord {
    if (line.startsWith("WigleWifi")) {
      return WigleRecord.PreHeader(parsePreHeader(line))
    }
    val fields = splitFields(line)
    if (fields.firstOrNull() == "MAC") {
      return WigleRecord.ColumnHeader
    }
    if (fields.size != COLUMN_COUNT || fields[10] != "WIFI") {
      return WigleRecord.Unrecognized(line)
    }
    val channel = fields[4].toIntOrNull() ?: return WigleRecord.Unrecognized(line)
    val rssi = fields[5].toIntOrNull() ?: return WigleRecord.Unrecognized(line)
    return WigleRecord.Wifi(
      ApSighting(
        mac = fields[0].uppercase(),
        ssid = fields[1],
        authMode = fields[2],
        firstSeen = fields[3],
        channel = channel,
        rssi = rssi,
        latitude = fields[6].toDoubleOrNull(),
        longitude = fields[7].toDoubleOrNull(),
        altitudeMeters = fields[8].toDoubleOrNull(),
        accuracyMeters = fields[9].toDoubleOrNull(),
      ),
    )
  }

  /** RFC 4180 field split for a single, already newline-delimited record. */
  fun splitFields(line: String): List<String> {
    val fields = ArrayList<String>(COLUMN_COUNT)
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
      val c = line[i]
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < line.length && line[i + 1] == '"') {
            current.append('"')
            i++
          } else {
            inQuotes = false
          }
        } else {
          current.append(c)
        }
      } else {
        when (c) {
          '"' -> inQuotes = true
          ',' -> {
            fields.add(current.toString())
            current.setLength(0)
          }
          else -> current.append(c)
        }
      }
      i++
    }
    fields.add(current.toString())
    return fields
  }

  private fun parsePreHeader(line: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    val parts = line.split(',')
    result["format"] = parts.first()
    for (part in parts.drop(1)) {
      val eq = part.indexOf('=')
      if (eq > 0) {
        result[part.substring(0, eq)] = part.substring(eq + 1)
      }
    }
    return result
  }
}
