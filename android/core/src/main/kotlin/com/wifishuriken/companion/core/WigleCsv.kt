package com.wifishuriken.companion.core

/** One WIFI row from a WiGLE CSV log written by the controller. */
data class ApSighting(
  val mac: String,
  val ssid: String,
  val authMode: String,
  val firstSeen: String,
  val channel: Int?,
  val rssi: Int?,
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

  /** A WIFI row that failed validation (design §8.3); counted as rejected. */
  data class Rejected(val line: String, val reason: String) : WigleRecord
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
    return validate(line, fields)
  }

  private val FIRST_SEEN = Regex("""^(\d{4})-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""")

  private fun validate(line: String, fields: List<String>): WigleRecord {
    fun reject(reason: String) = WigleRecord.Rejected(line, reason)
    val mac = Mac.normalize(fields[0]) ?: return reject("invalid MAC")
    val firstSeen = fields[3].trim()
    val year = FIRST_SEEN.matchEntire(firstSeen)?.groupValues?.get(1)?.toInt()
      ?: return reject("invalid FirstSeen")
    if (year !in 2000..2079) return reject("FirstSeen year out of range")
    val channel = optionalInt(fields[4]) ?: return reject("invalid channel")
    if (channel.value != null && channel.value !in 0..196) return reject("channel out of range")
    val rssi = optionalInt(fields[5]) ?: return reject("invalid RSSI")
    if (rssi.value != null && rssi.value !in -150..0) return reject("RSSI out of range")
    var lat = fields[6].trim().toDoubleOrNull()
    var lon = fields[7].trim().toDoubleOrNull()
    if (fields[6].isNotBlank() && lat == null || fields[7].isNotBlank() && lon == null) {
      return reject("invalid coordinates")
    }
    if (lat == null || lon == null) {
      lat = null
      lon = null
    } else if (lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
      (kotlin.math.abs(lat) < 1e-6 && kotlin.math.abs(lon) < 1e-6)
    ) {
      return reject("invalid coordinates")
    }
    return WigleRecord.Wifi(
      ApSighting(
        mac = mac,
        ssid = fields[1].replace("\u0000", ""),
        authMode = fields[2],
        firstSeen = firstSeen,
        channel = channel.value,
        rssi = rssi.value,
        latitude = lat,
        longitude = lon,
        altitudeMeters = fields[8].toDoubleOrNull(),
        accuracyMeters = fields[9].toDoubleOrNull(),
      ),
    )
  }

  /** Empty means "absent" (value null); unparseable means invalid (returns null). */
  private class OptInt(val value: Int?)

  private fun optionalInt(field: String): OptInt? {
    val t = field.trim()
    if (t.isEmpty()) return OptInt(null)
    return t.toIntOrNull()?.let { OptInt(it) }
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
