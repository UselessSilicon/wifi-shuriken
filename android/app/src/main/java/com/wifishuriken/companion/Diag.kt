package com.wifishuriken.companion

import android.util.Log

/** Session diagnostics for `adb logcat -s ShurikenDiag`: steps, counts and errors only, never positions or MACs. */
object Diag {
  private const val TAG = "ShurikenDiag"

  fun i(message: String) {
    Log.i(TAG, message)
  }

  fun w(message: String, error: Throwable? = null) {
    Log.w(TAG, message, error)
  }
}
