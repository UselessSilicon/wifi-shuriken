package com.wifishuriken.companion.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import com.wifishuriken.companion.core.IfaceDesc
import com.wifishuriken.companion.core.findInterfaces
import java.io.IOException

/**
 * One USB connection to the Shuriken carrying MTP (card access) and, when present, the NMEA port.
 * MtpDevice.close() and a failed MtpDevice.open() also close the shared connection, so recovering
 * from a stall means opening a whole new ShurikenConnection.
 */
class ShurikenConnection private constructor(
  val device: UsbDevice,
  private val usb: UsbDeviceConnection,
  val mtp: MtpDevice,
  val storageId: Int,
  val nmea: NmeaPort?,
) : AutoCloseable {

  private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

  /** Idempotent: the session and the service may both close it. Order: NMEA, then MTP, then USB. */
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    try {
      runCatching { nmea?.stop() }
    } finally {
      runCatching { mtp.close() }
      runCatching { usb.close() }
    }
  }

  companion object {
    fun open(context: Context, device: UsbDevice): ShurikenConnection {
      val manager = context.getSystemService(UsbManager::class.java)
      val ifaces = (0 until device.interfaceCount).map { device.getInterface(it) }
      val layout = findInterfaces(ifaces.map { IfaceDesc(it.id, it.interfaceClass, it.name) })
      val usb = manager.openDevice(device) ?: throw IOException("Could not open the USB device")
      val mtp = MtpDevice(device)
      if (!mtp.open(usb)) {
        usb.close()
        throw IOException("MTP open failed. Another app may hold the device; unplug, replug and choose this app.")
      }
      val storage = mtp.storageIds?.firstOrNull()
      if (storage == null) {
        mtp.close()
        throw IOException("The Shuriken reported no storage (SD card missing?)")
      }
      val nmea = layout?.let { l ->
        ifaces.firstOrNull { it.id == l.nmeaData }?.takeIf { usb.claimInterface(it, true) }?.let { NmeaPort(usb, it) }
      }
      return ShurikenConnection(device, usb, mtp, storage, nmea)
    }
  }
}
