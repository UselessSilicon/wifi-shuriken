package com.wifishuriken.companion.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

object ShurikenUsb {
  /** USB_VID / USB_PID of the controller firmware (Seeed XIAO RP2350, "WiFi Shuriken"). */
  const val VENDOR_ID = 0x2886
  const val PRODUCT_ID = 0x0058

  fun isShurikenIds(vendorId: Int, productId: Int): Boolean = vendorId == VENDOR_ID && productId == PRODUCT_ID

  fun isShuriken(device: UsbDevice): Boolean = isShurikenIds(device.vendorId, device.productId)

  /** The attached Shuriken, if any. Other MTP devices (cameras, phones) are never used. */
  fun findDevice(manager: UsbManager): UsbDevice? = manager.deviceList.values.firstOrNull(::isShuriken)

  /**
   * The attached Shuriken named by [claimed] (from an attach intent), looked up in the system's own
   * device list so a device object handed over in an intent is never trusted as is.
   */
  fun resolve(manager: UsbManager, claimed: UsbDevice): UsbDevice? =
    manager.deviceList[claimed.deviceName]?.takeIf(::isShuriken)
}
