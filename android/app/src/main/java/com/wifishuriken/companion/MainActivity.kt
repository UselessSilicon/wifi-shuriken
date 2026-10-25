package com.wifishuriken.companion

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.wifishuriken.companion.live.LiveSessionRepository
import com.wifishuriken.companion.live.LiveSessionService
import com.wifishuriken.companion.map.MapFileStore
import com.wifishuriken.companion.ui.CompanionApp
import com.wifishuriken.companion.ui.theme.CompanionTheme
import com.wifishuriken.companion.usb.ShurikenUsb
import com.wifishuriken.companion.usb.UsbSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Entry point: USB attach (or Connect) -> USB permission -> location/notification permission ->
 * start the live session service, which must be started from here while the app is in the
 * foreground.
 */
class MainActivity : ComponentActivity() {
  private val usbManager by lazy { getSystemService(UsbManager::class.java) }
  private var pendingDevice: UsbDevice? = null
  private var selfTestReport by mutableStateOf<String?>(null)

  private val runtimePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    val device = pendingDevice ?: return@registerForActivityResult
    if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocation()) {
      startSession(device)
    } else {
      note("Location permission is needed to record the trip and feed GPS to the Shuriken.")
    }
  }

  private val usbReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action != ACTION_USB_PERMISSION) return
      val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
      if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) ensureRuntimePermissions(device)
      else note("USB permission denied")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
    val mapStore = MapFileStore(File(filesDir, "map"))
    setContent {
      CompanionTheme {
        CompanionApp(
          mapStore = mapStore,
          onConnect = ::findAndConnect,
          settingsExtras = {
            if (BuildConfig.DEBUG) {
              Column(Modifier.padding(16.dp)) {
                OutlinedButton(onClick = ::runUsbSelfTest) { Text("Run USB self-test (debug build)") }
                selfTestReport?.let { Text(it, Modifier.padding(top = 8.dp)) }
              }
            }
          },
        )
      }
    }
    if (savedInstanceState == null) handleIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  override fun onDestroy() {
    unregisterReceiver(usbReceiver)
    super.onDestroy()
  }

  private fun note(text: String) = LiveSessionRepository.update { it.copy(message = text) }

  private fun handleIntent(intent: Intent?) {
    if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
      IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.let {
        connectWithPermission(it)
        return
      }
    }
    findAndConnect()
  }

  private fun findAndConnect() {
    if (LiveSessionRepository.state.value.connected) return
    val device = ShurikenUsb.findDevice(usbManager) ?: run {
      note("Plug in the Shuriken (USB OTG)")
      return
    }
    connectWithPermission(device)
  }

  private fun connectWithPermission(device: UsbDevice) {
    if (usbManager.hasPermission(device)) {
      ensureRuntimePermissions(device)
      return
    }
    // UsbManager adds extras to this intent, so it must be mutable; the explicit package keeps a
    // mutable PendingIntent allowed.
    val permissionIntent = PendingIntent.getBroadcast(
      this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE,
    )
    usbManager.requestPermission(device, permissionIntent)
  }

  private fun hasFineLocation() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

  private fun ensureRuntimePermissions(device: UsbDevice) {
    val notifications = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    if (hasFineLocation() && notifications) {
      startSession(device)
      return
    }
    pendingDevice = device
    runtimePermissions.launch(
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS),
    )
  }

  private fun startSession(device: UsbDevice) {
    ContextCompat.startForegroundService(
      this, Intent(this, LiveSessionService::class.java).putExtra(LiveSessionService.EXTRA_DEVICE, device),
    )
  }

  private fun runUsbSelfTest() {
    val device = ShurikenUsb.findDevice(usbManager)
    if (device == null || !usbManager.hasPermission(device)) {
      selfTestReport = "Plug in the Shuriken and grant USB permission first; stop the live session before testing."
      return
    }
    selfTestReport = "Self-test running..."
    lifecycleScope.launch(Dispatchers.IO) {
      val report = runCatching { UsbSelfTest.run(applicationContext, device) }.getOrElse { "Self-test failed: ${it.message}" }
      withContext(Dispatchers.Main) { selfTestReport = report }
    }
  }

  private companion object {
    const val ACTION_USB_PERMISSION = "com.wifishuriken.companion.USB_PERMISSION"
  }
}
