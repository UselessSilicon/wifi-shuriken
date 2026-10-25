package com.wifishuriken.companion.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

object ShurikenUsb {
  /** USB_DEVICE_PRODUCT_NAME in include/Configuration.h. */
  private const val PRODUCT_NAME = "WiFi Shuriken"

  fun hasMtpInterface(device: UsbDevice): Boolean =
    (0 until device.interfaceCount).any {
      device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
    }

  fun isShuriken(device: UsbDevice): Boolean =
    hasMtpInterface(device) && device.productName?.contains(PRODUCT_NAME, ignoreCase = true) == true

  /**
   * Prefers a device whose product string matches the firmware. Some hosts do
   * not expose the product string, so any MTP device is the fallback.
   */
  fun findDevice(manager: UsbManager): UsbDevice? {
    val devices = manager.deviceList.values
    return devices.firstOrNull(::isShuriken) ?: devices.firstOrNull(::hasMtpInterface)
  }
}
