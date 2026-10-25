package com.wifishuriken.companion.usb

import android.mtp.MtpDevice
import com.wifishuriken.companion.core.RootFileSource
import java.io.IOException

/**
 * [RootFileSource] backed by Android's MTP host API.
 *
 * Uses only the operations the controller implements (controller_mtp_storage.cpp):
 * GetObjectHandles, GetObjectInfo and GetPartialObject. Android's
 * getPartialObject64 issues a vendor extension the firmware does not support,
 * so the 32-bit getPartialObject is used; log files stay far below 4 GiB.
 */
class MtpRootFileSource(
  private val device: MtpDevice,
  private val storageId: Int,
) : RootFileSource {

  override fun listRootHandles(): IntArray =
    device.getObjectHandles(storageId, 0, ROOT_PARENT)
      ?: throw IOException("GetObjectHandles failed")

  override fun info(handle: Int): RootFileSource.Info? {
    val info = device.getObjectInfo(handle) ?: return null
    return RootFileSource.Info(info.name, info.compressedSizeLong)
  }

  override fun read(handle: Int, offset: Long, buffer: ByteArray, length: Int): Int =
    device.getPartialObject(handle, offset, length.toLong(), buffer).toInt()

  private companion object {
    // MTP's "storage root" parent; the firmware also accepts 0.
    const val ROOT_PARENT = -1
  }
}
