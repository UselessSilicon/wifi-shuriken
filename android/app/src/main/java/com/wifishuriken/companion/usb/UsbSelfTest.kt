package com.wifishuriken.companion.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import com.wifishuriken.companion.core.HashIndex
import com.wifishuriken.companion.core.planSync
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-build hardware check: copies the largest finished log while the NMEA port is read and
 * written at the same time (the shared-connection case). The sentence sent is a void RMC (no time,
 * no position), which the firmware parses but never uses as a fix, so the real log is unaffected.
 */
object UsbSelfTest {
  private const val VOID_RMC_BODY = "GPRMC,,V,,,,,,,,,,N"

  fun voidRmc(): String = "$" + VOID_RMC_BODY + "*" + "%02X".format(VOID_RMC_BODY.fold(0) { a, c -> a xor c.code })

  fun run(context: Context, device: UsbDevice): String {
    ShurikenConnection.open(context, device).use { conn ->
      val source = MtpCardSource(conn)
      val target = planSync(source.listCard(), HashIndex(emptyList())).toFetch.maxByOrNull { it.size }
        ?: return "No finished log on the card to copy."
      val lines = AtomicInteger()
      val ggaLines = AtomicInteger()
      val sendsOk = AtomicInteger()
      val sendsTried = AtomicInteger()
      conn.nmea?.start { line ->
        lines.incrementAndGet()
        if (line.length > 6 && line.substring(3, 6) == "GGA") ggaLines.incrementAndGet()
      }
      val sending = AtomicBoolean(true)
      val sender = Thread {
        while (sending.get()) {
          sendsTried.incrementAndGet()
          if (conn.nmea?.send(voidRmc()) == true) sendsOk.incrementAndGet()
          Thread.sleep(1000)
        }
      }.apply { isDaemon = true; start() }
      val started = System.nanoTime()
      val tmp = File.createTempFile("selftest", ".csv", context.cacheDir)
      val md = MessageDigest.getInstance("MD5")
      try {
        DigestOutputStream(tmp.outputStream(), md).use { source.copyTo(target.handle, target.size, it) }
        val seconds = (System.nanoTime() - started) / 1e9
        if (seconds < 3) Thread.sleep(((3 - seconds) * 1000).toLong())
      } finally {
        sending.set(false)
        sender.join(1500)
        tmp.delete()
      }
      val md5 = md.digest().joinToString("") { "%02x".format(it) }
      return buildString {
        appendLine("Copied ${target.name}: ${target.size} bytes, MD5 $md5")
        appendLine("NMEA lines received during copy: ${lines.get()} (GGA ${ggaLines.get()})")
        appendLine("Void RMC sends accepted: ${sendsOk.get()} of ${sendsTried.get()}")
        if (conn.nmea == null) appendLine("NMEA port not found on this device.")
      }
    }
  }
}
