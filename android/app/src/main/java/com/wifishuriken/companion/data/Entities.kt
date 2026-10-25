package com.wifishuriken.companion.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One trip = one Shuriken log (one boot). Totals are recomputed from the tables, never incremented. */
@Entity(tableName = "trips", indices = [Index(value = ["logName"], unique = true), Index("startUtc")])
data class TripEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val logName: String,
  val startUtc: String,
  val endUtc: String? = null,
  val rows: Int = 0,
  val rejected: Int = 0,
  val uniqueMacs: Int = 0,
  val positionedRows: Int = 0,
  val newCount: Int = 0,
  /** DRIVE, WALK or BIKE; null = not set by the owner. */
  val mode: String? = null,
)

/** One logged row. (tripId, byteOffset) is unique so live-tailed and later imported rows coincide. */
@Entity(
  tableName = "sightings",
  indices = [Index(value = ["tripId", "byteOffset"], unique = true), Index(value = ["mac", "tsUtc"]), Index("tripId")],
)
data class SightingEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val tripId: Long,
  val byteOffset: Long,
  val mac: String,
  val ssid: String,
  val authMode: String,
  val security: String,
  val channel: Int?,
  val band: String?,
  val rssi: Int?,
  val tsUtc: String,
  val lat: Double?,
  val lon: Double?,
  val altM: Double?,
  val accuracyM: Double?,
)

@Entity(tableName = "rejected_rows", indices = [Index(value = ["tripId", "byteOffset"], unique = true)])
data class RejectedRowEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val tripId: Long,
  val byteOffset: Long,
  val reason: String,
)

/** Per-network aggregate across all trips; firstTripId is the chronologically first trip (drives "new"). */
@Entity(
  tableName = "networks",
  indices = [Index("security"), Index("lastSeenUtc"), Index("firstTripId"), Index("sightings"), Index("trips")],
)
data class NetworkEntity(
  @PrimaryKey val mac: String,
  val ssid: String,
  val security: String,
  val authMode: String,
  val band: String?,
  val channel: Int?,
  val channels: String,
  val firstSeenUtc: String,
  val lastSeenUtc: String,
  val firstTripId: Long,
  val sightings: Int,
  val trips: Int,
  val bestRssi: Int?,
  val vendor: String?,
  val randomized: Boolean,
  val estLat: Double?,
  val estLon: Double?,
  val estMethod: String,
  val estConfidence: String?,
  val estRadiusM: Double?,
  val maxSpreadM: Double?,
)

@Entity(tableName = "track_points", indices = [Index("tripId")])
data class TrackPointEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val tripId: Long,
  val tMs: Long,
  val lat: Double,
  val lon: Double,
)

@Entity(tableName = "archived_files")
data class ArchivedFileEntity(
  @PrimaryKey val md5: String,
  val name: String,
  val size: Long,
  val path: String,
  val importedAtUtc: String,
)

/** Row for the Drives list: stored per-trip totals only. */
data class TripSummary(
  val id: Long,
  val logName: String,
  val startUtc: String,
  val endUtc: String?,
  val rows: Int,
  val uniqueMacs: Int,
  val positionedRows: Int,
  val newCount: Int,
  val mode: String?,
)

/** A sighting together with its trip's start time, for aggregate recomputation. */
data class SightingWithTrip(
  val tripId: Long,
  val tripStartUtc: String,
  val ssid: String,
  val authMode: String,
  val security: String,
  val channel: Int?,
  val band: String?,
  val rssi: Int?,
  val tsUtc: String,
  val lat: Double?,
  val lon: Double?,
)

/** A positioned sighting time and place (fallback track for trips without a phone track). */
data class PositionRow(val tsUtc: String, val lat: Double, val lon: Double)
