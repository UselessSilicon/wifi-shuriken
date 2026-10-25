package com.wifishuriken.companion.map

import com.wifishuriken.companion.core.SecurityClass

/** A network dot on the map. */
data class MapNetwork(
  val mac: String,
  val lat: Double,
  val lon: Double,
  val security: SecurityClass,
  val newThisTrip: Boolean,
)
