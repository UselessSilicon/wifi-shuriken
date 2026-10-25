package com.wifishuriken.companion.live

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/**
 * The phone's own GPS: 1 s location updates (track, map, speed) and the platform's NMEA sentences,
 * which are only delivered while a GPS location request is active (design §7).
 */
class PhoneLocation(private val context: Context) {
  private val manager = context.getSystemService(LocationManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private var locationListener: LocationListener? = null
  private var nmeaListener: OnNmeaMessageListener? = null

  @SuppressLint("MissingPermission") // the service is only started after ACCESS_FINE_LOCATION is granted
  fun start(onLocation: (Location) -> Unit, onNmea: (String) -> Unit) {
    val l = LocationListener { onLocation(it) }
    val n = OnNmeaMessageListener { message, _ -> onNmea(message) }
    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, l, Looper.getMainLooper())
    manager.addNmeaListener(n, handler)
    locationListener = l
    nmeaListener = n
  }

  fun stop() {
    locationListener?.let { manager.removeUpdates(it) }
    nmeaListener?.let { manager.removeNmeaListener(it) }
    locationListener = null
    nmeaListener = null
  }

  fun batteryPercent(): Int? {
    val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else null
  }
}
