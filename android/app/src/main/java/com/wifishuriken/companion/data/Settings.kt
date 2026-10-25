package com.wifishuriken.companion.data

import android.content.Context

enum class Units { METRIC, IMPERIAL }

/** Owner preferences (SharedPreferences). Nothing here is ever uploaded. */
class Settings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  /** Send the phone's GPS to the Shuriken during a live session (design §7). */
  var feedPhoneGps: Boolean
    get() = prefs.getBoolean(KEY_FEED, true)
    set(value) = prefs.edit().putBoolean(KEY_FEED, value).apply()

  var units: Units
    get() = runCatching { Units.valueOf(prefs.getString(KEY_UNITS, Units.METRIC.name)!!) }.getOrDefault(Units.METRIC)
    set(value) = prefs.edit().putString(KEY_UNITS, value.name).apply()

  private companion object {
    const val KEY_FEED = "feed_phone_gps"
    const val KEY_UNITS = "units"
  }
}
