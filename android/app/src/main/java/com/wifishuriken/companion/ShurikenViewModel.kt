package com.wifishuriken.companion

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifishuriken.companion.core.ApEntry
import com.wifishuriken.companion.core.ApStats
import com.wifishuriken.companion.core.LogTailer
import com.wifishuriken.companion.core.PollOutcome
import com.wifishuriken.companion.usb.MtpRootFileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

enum class Connection { NO_DEVICE, AWAITING_PERMISSION, CONNECTING, CONNECTED, ERROR }

data class UiState(
  val connection: Connection = Connection.NO_DEVICE,
  val deviceName: String? = null,
  /** Null while connected but no scan log exists yet. */
  val fileName: String? = null,
  val bytesRead: Long = 0,
  val fileSize: Long = 0,
  val stats: ApStats? = null,
  val aps: List<ApEntry> = emptyList(),
  val lastPollElapsedMs: Long = 0,
  val message: String? = null,
)

/**
 * Owns the USB/MTP session so polling survives configuration changes. All MTP
 * calls run sequentially on one coroutine on the IO dispatcher.
 */
class ShurikenViewModel(app: Application) : AndroidViewModel(app) {
  private val usbManager = app.getSystemService(UsbManager::class.java)
  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private var session: Job? = null
  private var sessionDevice: String? = null

  fun isActiveFor(device: UsbDevice): Boolean =
    session?.isActive == true && sessionDevice == device.deviceName

  fun showNoDevice() {
    if (session?.isActive != true) {
      _state.value = UiState(message = "Plug in the Shuriken (USB OTG)")
    }
  }

  fun awaitingPermission(device: UsbDevice) {
    _state.update {
      it.copy(connection = Connection.AWAITING_PERMISSION, deviceName = label(device), message = null)
    }
  }

  fun permissionDenied() {
    _state.update { it.copy(connection = Connection.ERROR, message = "USB permission denied") }
  }

  fun onDetached(device: UsbDevice) {
    if (sessionDevice == device.deviceName) {
      session?.cancel()
      session = null
      sessionDevice = null
      _state.update { it.copy(connection = Connection.NO_DEVICE, message = "Device disconnected") }
    }
  }

  fun connect(device: UsbDevice) {
    if (isActiveFor(device)) return
    val previous = session
    sessionDevice = device.deviceName
    _state.value = UiState(connection = Connection.CONNECTING, deviceName = label(device))
    session = viewModelScope.launch(Dispatchers.IO) {
      // Let the previous session close its MTP handle before reopening.
      previous?.cancelAndJoin()
      runSession(device)
    }
  }

  private suspend fun runSession(device: UsbDevice) {
    val connection = usbManager.openDevice(device)
    if (connection == null) {
      fail("Could not open USB device")
      return
    }
    val mtp = MtpDevice(device)
    if (!mtp.open(connection)) {
      connection.close()
      fail("MTP open failed. Another app (e.g. Files) may hold the device; unplug, replug and pick this app.")
      return
    }
    try {
      val storageId = mtp.storageIds?.firstOrNull() ?: throw IOException("No MTP storage reported")
      val tailer = LogTailer(MtpRootFileSource(mtp, storageId))
      var consecutiveFailures = 0
      while (currentCoroutineContext().isActive) {
        val outcome = try {
          tailer.poll().also { consecutiveFailures = 0 }
        } catch (e: IOException) {
          if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) throw e
          null
        }
        if (outcome != null) publish(outcome, tailer)
        val catchingUp = outcome is PollOutcome.Tailing && !outcome.caughtUp
        delay(if (catchingUp) CATCH_UP_INTERVAL_MS else POLL_INTERVAL_MS)
      }
    } catch (e: IOException) {
      // A detach cancels the session and usually fails the in-flight transfer;
      // keep the "disconnected" state in that case.
      if (currentCoroutineContext().isActive) {
        fail("MTP error: ${e.message}")
      }
    } finally {
      mtp.close()
      connection.close()
    }
  }

  private fun publish(outcome: PollOutcome, tailer: LogTailer) {
    val now = SystemClock.elapsedRealtime()
    when (outcome) {
      PollOutcome.NoLogFile -> _state.update {
        it.copy(
          connection = Connection.CONNECTED,
          fileName = null,
          bytesRead = 0,
          fileSize = 0,
          stats = null,
          aps = emptyList(),
          lastPollElapsedMs = now,
          message = "No scan log yet. The controller creates one after it has GNSS time and an SD card.",
        )
      }
      is PollOutcome.Tailing -> _state.update {
        val changed = outcome.newRows > 0 || outcome.switchedFile || it.fileName != outcome.fileName
        it.copy(
          connection = Connection.CONNECTED,
          fileName = outcome.fileName,
          bytesRead = outcome.bytesRead,
          fileSize = outcome.fileSize,
          stats = if (changed || it.stats == null) tailer.table.stats() else it.stats,
          aps = if (changed) tailer.table.snapshot().take(MAX_LIST_ITEMS) else it.aps,
          lastPollElapsedMs = now,
          message = null,
        )
      }
    }
  }

  private fun fail(message: String) {
    _state.update { it.copy(connection = Connection.ERROR, message = message) }
  }

  private fun label(device: UsbDevice): String = device.productName ?: device.deviceName

  private companion object {
    const val POLL_INTERVAL_MS = 2_000L
    const val CATCH_UP_INTERVAL_MS = 50L
    const val MAX_CONSECUTIVE_FAILURES = 5
    const val MAX_LIST_ITEMS = 1_000
  }
}
