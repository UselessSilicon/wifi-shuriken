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
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wifishuriken.companion.databinding.ActivityMainBinding
import com.wifishuriken.companion.live.LiveSessionRepository
import com.wifishuriken.companion.live.LiveSessionService
import com.wifishuriken.companion.live.LiveState
import com.wifishuriken.companion.usb.ShurikenUsb
import com.wifishuriken.companion.usb.UsbSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point: USB attach (or Connect) -> USB permission -> location/notification permission ->
 * start the live session service. The live session service must be started from here, while the
 * app is in the foreground. The screen is an interim text view, replaced by the Compose UI.
 */
class MainActivity : AppCompatActivity() {
  private lateinit var binding: ActivityMainBinding
  private val usbManager by lazy { getSystemService(UsbManager::class.java) }
  private var pendingDevice: UsbDevice? = null

  private val runtimePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    val device = pendingDevice ?: return@registerForActivityResult
    if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocation()) {
      startSession(device)
    } else {
      showMessage("Location permission is needed to record the trip and feed GPS to the Shuriken.")
    }
  }

  private val usbReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action != ACTION_USB_PERMISSION) return
      val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
      if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
        ensureRuntimePermissions(device)
      } else {
        showMessage("USB permission denied")
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    applySystemBarInsets(binding.root)
    binding.connectButton.setOnClickListener { findAndConnect() }
    if (BuildConfig.DEBUG) {
      // Debug builds only: long-press Connect runs the USB self-test (see UsbSelfTest).
      binding.connectButton.setOnLongClickListener { runUsbSelfTest(); true }
    }
    ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        LiveSessionRepository.state.collect { render(it) }
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
    val device = ShurikenUsb.findDevice(usbManager)
    if (device == null) {
      showMessage("Plug in the Shuriken (USB OTG)")
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

  private fun render(s: LiveState) {
    binding.statusText.text = if (s.connected) "Connected" else "Not connected"
    binding.connectButton.visibility = if (s.connected) View.GONE else View.VISIBLE
    binding.fileText.text = listOfNotNull(s.activeLog ?: "Waiting for the Shuriken's log", s.syncProgress).joinToString("\n")
    val c = s.counters
    binding.statsText.text = "2.4 GHz ${c.ghz24}   5 GHz ${c.ghz5}   With GPS ${c.withGps}   No GPS ${c.noGps}\n" +
      "New ${c.new}   Seen before ${c.seenBefore}   Open ${c.open}\n" +
      "Shuriken GPS: ${s.moduleFix?.let { if (it.fix) "fix, ${it.satellites ?: 0} satellites" else "no fix" } ?: "unknown"}" +
      (if (s.feeding) "   Phone GPS on" else "")
    s.message?.let { showMessage(it) }
  }

  private fun runUsbSelfTest() {
    val device = ShurikenUsb.findDevice(usbManager)
    if (device == null || !usbManager.hasPermission(device)) {
      showMessage("Self-test: plug in the Shuriken and grant USB permission first (tap Connect).")
      return
    }
    showMessage("Self-test running...")
    lifecycleScope.launch(Dispatchers.IO) {
      val report = runCatching { UsbSelfTest.run(applicationContext, device) }.getOrElse { "Self-test failed: ${it.message}" }
      withContext(Dispatchers.Main) { showMessage(report) }
    }
  }

  private fun showMessage(text: String) {
    binding.messageText.text = text
    binding.messageText.visibility = View.VISIBLE
  }

  private fun applySystemBarInsets(root: View) {
    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
      insets
    }
  }

  private companion object {
    const val ACTION_USB_PERMISSION = "com.wifishuriken.companion.USB_PERMISSION"
  }
}
