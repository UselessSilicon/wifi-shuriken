package com.wifishuriken.companion.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.wifishuriken.companion.core.Nmea
import com.wifishuriken.companion.core.NmeaLineAssembler
import com.wifishuriken.companion.core.ReadAction
import com.wifishuriken.companion.core.ReadFailurePolicy

/**
 * The Shuriken's NMEA serial port (CDC data interface). Reads the module's own NMEA and sends phone
 * GGA/RMC sentences. Uses only blocking bulkTransfer on its own thread: Android's MtpDevice uses the
 * same connection's asynchronous request queue, so UsbRequest/requestWait must never be used here.
 * No control requests are ever sent (no DTR, no line coding: 1200 baud would reboot the board).
 */
class NmeaPort(private val conn: UsbDeviceConnection, private val dataIface: UsbInterface) {
  private val inEp: UsbEndpoint? = endpoint(UsbConstants.USB_DIR_IN)
  private val outEp: UsbEndpoint? = endpoint(UsbConstants.USB_DIR_OUT)
  private val sendLock = Any()

  /** bulkTransfer result of the last write (diagnostics). */
  @Volatile var lastWriteResult = 0
    private set

  @Volatile private var running = false
  private var reader: Thread? = null

  private fun endpoint(dir: Int): UsbEndpoint? = (0 until dataIface.endpointCount)
    .map { dataIface.getEndpoint(it) }
    .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == dir }

  fun start(onLine: (String) -> Unit) {
    val ep = inEp ?: return
    if (running) return
    running = true
    reader = Thread({
      val assembler = NmeaLineAssembler()
      val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(64) * 4)
      val policy = ReadFailurePolicy(timeoutMs = READ_TIMEOUT_MS.toLong())
      while (running) {
        val t0 = System.nanoTime()
        val n = conn.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS)
        if (n > 0) assembler.feed(buf, n).forEach(onLine)
        // A read that fails at once means the device is gone: back off, and stop if it persists.
        when (val action = policy.onResult(n, (System.nanoTime() - t0) / 1_000_000)) {
          ReadAction.Continue -> Unit
          is ReadAction.Sleep -> Thread.sleep(action.ms)
          ReadAction.Stop -> break
        }
      }
    }, "shuriken-nmea").apply { isDaemon = true; start() }
  }

  /** Sends one sentence; only checksum-valid GGA/RMC sentences are ever written to the device. */
  fun send(sentence: String): Boolean {
    val ep = outEp ?: return false
    if (!Nmea.isForwardable(sentence)) return false
    val bytes = Nmea.frame(sentence)
    synchronized(sendLock) {
      val n = conn.bulkTransfer(ep, bytes, bytes.size, WRITE_TIMEOUT_MS)
      lastWriteResult = n
      return n == bytes.size
    }
  }

  fun stop() {
    running = false
    reader?.join(READ_TIMEOUT_MS + 500L)
    reader = null
    conn.releaseInterface(dataIface)
  }

  private companion object {
    const val READ_TIMEOUT_MS = 500
    const val WRITE_TIMEOUT_MS = 500
  }
}
