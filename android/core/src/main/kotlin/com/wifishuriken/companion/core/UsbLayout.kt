package com.wifishuriken.companion.core

/** One USB interface of the Shuriken, in descriptor order. Class codes: 2 CDC control, 10 CDC data, 6 still image (MTP). */
data class IfaceDesc(val number: Int, val classCode: Int, val name: String?)

data class ShurikenInterfaces(val mtp: Int, val nmeaControl: Int, val nmeaData: Int)

private const val CLASS_CDC = 2
private const val CLASS_CDC_DATA = 10
private const val CLASS_STILL_IMAGE = 6

/**
 * Finds the MTP interface and the NMEA serial port (design §5). Each CDC port is a control/data
 * pair whose name sits on the control interface; the unnamed data interface follows it. The
 * Console pair is never returned.
 */
fun findInterfaces(ifaces: List<IfaceDesc>): ShurikenInterfaces? {
  val mtp = ifaces.firstOrNull { it.classCode == CLASS_STILL_IMAGE && it.name == "MTP" } ?: return null
  val controlIndex = ifaces.indexOfFirst { it.classCode == CLASS_CDC && it.name == "NMEA" }
  if (controlIndex < 0) return null
  val data = ifaces.getOrNull(controlIndex + 1)?.takeIf { it.classCode == CLASS_CDC_DATA } ?: return null
  return ShurikenInterfaces(mtp.number, ifaces[controlIndex].number, data.number)
}
