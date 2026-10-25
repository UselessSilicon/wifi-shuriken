package com.wifishuriken.companion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.wifishuriken.companion.core.ApStats
import com.wifishuriken.companion.databinding.ActivityMainBinding
import com.wifishuriken.companion.usb.ShurikenUsb
import com.wifishuriken.companion.usb.UsbSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {
  private lateinit var binding: ActivityMainBinding
  private val viewModel: ShurikenViewModel by viewModels()
  private val adapter = ApAdapter()
  private val usbManager by lazy { getSystemService(UsbManager::class.java) }

  private val usbReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        ?: return
      when (intent.action) {
        ACTION_USB_PERMISSION ->
          if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            viewModel.connect(device)
          } else {
            viewModel.permissionDenied()
          }
        UsbManager.ACTION_USB_DEVICE_DETACHED -> viewModel.onDetached(device)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    applySystemBarInsets(binding.root)

    binding.apList.layoutManager = LinearLayoutManager(this)
    binding.apList.adapter = adapter
    binding.connectButton.setOnClickListener { findAndConnect() }
    if (BuildConfig.DEBUG) {
      // Debug builds only: long-press Connect runs the USB self-test (see UsbSelfTest).
      binding.connectButton.setOnLongClickListener { runUsbSelfTest(); true }
    }

    val filter = IntentFilter().apply {
      addAction(ACTION_USB_PERMISSION)
      addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }
    ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.state.collect { render(it) }
      }
    }

    if (savedInstanceState == null) {
      handleIntent(intent)
    }
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
      val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
      if (device != null) {
        connectWithPermission(device)
        return
      }
    }
    findAndConnect()
  }

  private fun runUsbSelfTest() {
    val device = ShurikenUsb.findDevice(usbManager)
    if (device == null || !usbManager.hasPermission(device)) {
      showMessage("Self-test: plug in the Shuriken and grant USB permission first (tap Connect).")
      return
    }
    showMessage("Self-test running...")
    lifecycleScope.launch(Dispatchers.IO) {
      val report = runCatching { UsbSelfTest.run(applicationContext, device) }
        .getOrElse { "Self-test failed: ${it.message}" }
      withContext(Dispatchers.Main) { showMessage(report) }
    }
  }

  private fun showMessage(text: String) {
    binding.messageText.text = text
    binding.messageText.visibility = View.VISIBLE
  }

  private fun findAndConnect() {
    val device = ShurikenUsb.findDevice(usbManager)
    if (device == null) {
      viewModel.showNoDevice()
      return
    }
    connectWithPermission(device)
  }

  private fun connectWithPermission(device: UsbDevice) {
    if (viewModel.isActiveFor(device)) return
    if (usbManager.hasPermission(device)) {
      viewModel.connect(device)
      return
    }
    viewModel.awaitingPermission(device)
    // UsbManager adds extras to this intent, so it must be mutable; the
    // explicit package keeps a mutable PendingIntent allowed on Android 14+.
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val permissionIntent = PendingIntent.getBroadcast(
      this,
      0,
      Intent(ACTION_USB_PERMISSION).setPackage(packageName),
      flags,
    )
    usbManager.requestPermission(device, permissionIntent)
  }

  private fun render(state: UiState) {
    binding.statusText.text = when (state.connection) {
      Connection.NO_DEVICE -> getString(R.string.status_no_device)
      Connection.AWAITING_PERMISSION -> getString(R.string.status_permission, state.deviceName)
      Connection.CONNECTING -> getString(R.string.status_connecting, state.deviceName)
      Connection.CONNECTED -> getString(R.string.status_connected, state.deviceName)
      Connection.ERROR -> getString(R.string.status_error)
    }
    binding.connectButton.visibility =
      if (state.connection == Connection.CONNECTED || state.connection == Connection.CONNECTING) {
        View.GONE
      } else {
        View.VISIBLE
      }

    binding.messageText.text = state.message.orEmpty()
    binding.messageText.visibility = if (state.message.isNullOrEmpty()) View.GONE else View.VISIBLE

    binding.fileText.text = state.fileName?.let { name ->
      val progress = if (state.bytesRead < state.fileSize) {
        getString(R.string.file_catching_up, state.bytesRead * 100 / state.fileSize.coerceAtLeast(1))
      } else {
        ""
      }
      val age = (SystemClock.elapsedRealtime() - state.lastPollElapsedMs) / 1000
      getString(R.string.file_line, name, formatBytes(state.fileSize), age, progress)
    }.orEmpty()

    binding.statsText.text = state.stats?.let(::formatStats).orEmpty()
    adapter.submitList(state.aps)
  }

  private fun formatStats(s: ApStats): String = buildString {
    append(getString(R.string.stats_counts, s.uniqueAps, s.rows, s.openAps))
    append('\n')
    append(getString(R.string.stats_bands, s.aps24, s.aps5, s.rowsWithoutLocation))
    if (s.lastLatitude != null && s.lastLongitude != null) {
      append('\n')
      append(
        getString(
          R.string.stats_position,
          String.format(Locale.US, "%.5f, %.5f", s.lastLatitude, s.lastLongitude),
          s.lastRowTime.orEmpty(),
        ),
      )
    }
  }

  private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f kB", bytes / 1024.0)
    else -> "$bytes B"
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
