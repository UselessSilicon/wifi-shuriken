package com.wifishuriken.companion.core

/**
 * NMEA handling for the Shuriken's NMEA port (design §7). Only GGA and RMC are ever sent to the
 * device: the firmware parses those for position, HDOP and time.
 */
object Nmea {
  private val FORWARDABLE_TYPES = setOf("GGA", "RMC")

  fun checksumOk(sentence: String): Boolean {
    val t = sentence.trim()
    if (!t.startsWith("$")) return false
    val star = t.lastIndexOf('*')
    if (star < 1 || star + 3 != t.length) return false
    val given = t.substring(star + 1).toIntOrNull(16) ?: return false
    val sum = t.substring(1, star).fold(0) { acc, c -> acc xor c.code }
    return sum == given
  }

  fun isForwardable(sentence: String): Boolean {
    if (!checksumOk(sentence)) return false
    val type = sentence.trim().drop(1).substringBefore(',')
    return type.length == 5 && type.substring(2) in FORWARDABLE_TYPES
  }

  /** ASCII bytes of the trimmed sentence terminated by exactly one CR LF. */
  fun frame(sentence: String): ByteArray = (sentence.trim() + "\r\n").toByteArray(Charsets.US_ASCII)
}

data class ModuleFix(val fix: Boolean, val satellites: Int?, val hdop: Double?)

/**
 * Turns the device's NMEA byte stream into lines. The first line after creation is discarded:
 * it is usually the tail of a sentence that started before we connected.
 */
class NmeaLineAssembler {
  private val pending = StringBuilder()
  private var synced = false

  fun feed(bytes: ByteArray, len: Int): List<String> {
    val out = ArrayList<String>()
    for (i in 0 until len) {
      val c = (bytes[i].toInt() and 0xFF).toChar()
      when (c) {
        '\r' -> Unit
        '\n' -> {
          val line = pending.toString()
          pending.setLength(0)
          if (!synced) {
            synced = true
          } else if (line.isNotEmpty() && line.length <= MAX_LINE) {
            out.add(line)
          }
        }
        else -> if (pending.length <= MAX_LINE) pending.append(c)
      }
    }
    return out
  }

  private companion object {
    const val MAX_LINE = 120
  }
}

/** Fix state from a module GGA sentence; null for other sentences or bad checksums. */
fun parseModuleFix(ggaSentence: String): ModuleFix? {
  if (!Nmea.checksumOk(ggaSentence)) return null
  val body = ggaSentence.trim().drop(1).substringBefore('*')
  val f = body.split(',')
  if (f.isEmpty() || f[0].length != 5 || !f[0].endsWith("GGA") || f.size < 9) return null
  val quality = f[6].toIntOrNull() ?: 0
  return ModuleFix(quality > 0, f[7].toIntOrNull(), f[8].toDoubleOrNull())
}
