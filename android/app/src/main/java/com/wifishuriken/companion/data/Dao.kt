package com.wifishuriken.companion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Networks header stats: security mix plus the shares the design asks for. */
data class NetworkStats(val bySecurity: Map<String, Int>) {
  val total = bySecurity.values.sum()
  val percentOpen = pct(bySecurity["OPEN_OR_OWE"] ?: 0)
  val percentWpa3Capable = pct((bySecurity["WPA3"] ?: 0) + (bySecurity["WPA2_WPA3"] ?: 0))

  private fun pct(n: Int) = if (total == 0) 0 else Math.round(n * 100.0 / total).toInt()
}

@Dao
interface CompanionDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertTrip(trip: TripEntity): Long

  @Query("SELECT * FROM trips WHERE logName = :logName")
  suspend fun tripByLogName(logName: String): TripEntity?

  @Query("SELECT logName FROM trips")
  suspend fun allTripLogNames(): List<String>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertSighting(s: SightingEntity): Long

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertRejected(r: RejectedRowEntity): Long

  @Insert
  suspend fun insertTrackPoints(points: List<TrackPointEntity>)

  @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY tMs")
  suspend fun trackFor(tripId: Long): List<TrackPointEntity>

  @Upsert
  suspend fun upsertNetwork(n: NetworkEntity)

  @Query("DELETE FROM networks WHERE mac = :mac")
  suspend fun deleteNetwork(mac: String)

  @Query("SELECT DISTINCT mac FROM sightings")
  suspend fun allMacs(): List<String>

  @Query(
    "SELECT s.tripId, t.startUtc AS tripStartUtc, s.ssid, s.authMode, s.security, s.channel, s.band, s.rssi," +
      " s.tsUtc, s.lat, s.lon FROM sightings s JOIN trips t ON t.id = s.tripId WHERE s.mac = :mac ORDER BY s.tsUtc",
  )
  suspend fun sightingsWithTrip(mac: String): List<SightingWithTrip>

  @Query("SELECT COUNT(*) FROM sightings WHERE tripId = :tripId")
  suspend fun sightingCountForTrip(tripId: Long): Int

  @Query("SELECT COUNT(*) FROM rejected_rows WHERE tripId = :tripId")
  suspend fun rejectedCountForTrip(tripId: Long): Int

  @Query("SELECT COUNT(DISTINCT mac) FROM sightings WHERE tripId = :tripId")
  suspend fun uniqueMacsForTrip(tripId: Long): Int

  @Query("SELECT COUNT(*) FROM sightings WHERE tripId = :tripId AND lat IS NOT NULL")
  suspend fun positionedRowsForTrip(tripId: Long): Int

  @Query("SELECT MAX(tsUtc) FROM sightings WHERE tripId = :tripId")
  suspend fun lastRowTimeForTrip(tripId: Long): String?

  @Query("SELECT COUNT(*) FROM networks WHERE firstTripId = :tripId")
  suspend fun newCountForTrip(tripId: Long): Int

  @Query(
    "UPDATE trips SET rows = :rows, rejected = :rejected, uniqueMacs = :uniqueMacs, positionedRows = :positioned," +
      " newCount = :newCount, endUtc = :endUtc WHERE id = :tripId",
  )
  suspend fun updateTripTotals(tripId: Long, rows: Int, rejected: Int, uniqueMacs: Int, positioned: Int, newCount: Int, endUtc: String?)

  @Query("SELECT tsUtc, lat, lon FROM sightings WHERE tripId = :tripId AND lat IS NOT NULL ORDER BY tsUtc")
  suspend fun positionsForTrip(tripId: Long): List<PositionRow>

  @Query("SELECT * FROM trips WHERE id = :tripId")
  suspend fun tripById(tripId: Long): TripEntity?

  @Query("UPDATE trips SET mode = :mode WHERE id = :tripId")
  suspend fun setTripMode(tripId: Long, mode: String?)

  @Query(SQL_TRIPS_BY_START_DESC)
  fun tripsByStartDesc(): Flow<List<TripSummary>>

  @RawQuery(observedEntities = [NetworkEntity::class])
  suspend fun networksRaw(query: SupportSQLiteQuery): List<NetworkEntity>

  suspend fun networksPage(filter: NetworkFilter, sort: NetworkSort, limit: Int, offset: Int): List<NetworkEntity> =
    networksRaw(buildNetworksQuery(filter, sort, limit, offset).toSupport())

  @RawQuery(observedEntities = [NetworkEntity::class])
  suspend fun securityMixRaw(query: SupportSQLiteQuery): List<SecurityCount>

  /** Header stats for the networks matching [filter]. */
  suspend fun networkStats(filter: NetworkFilter): NetworkStats =
    NetworkStats(securityMixRaw(buildSecurityMixQuery(filter).toSupport()).associate { it.security to it.n })

  @Query(
    "SELECT n.band AS band, n.channel AS channel, COUNT(*) AS n FROM networks n" +
      " WHERE n.band IS NOT NULL AND n.channel IS NOT NULL AND $TRIP_SCOPE GROUP BY n.band, n.channel ORDER BY n.band, n.channel",
  )
  suspend fun channelUsage(tripId: Long?): List<ChannelCount>

  @Query(
    "SELECT n.vendor AS name, COUNT(*) AS n FROM networks n WHERE n.vendor IS NOT NULL AND $TRIP_SCOPE" +
      " GROUP BY n.vendor ORDER BY n DESC, n.vendor LIMIT :limit",
  )
  suspend fun topVendors(tripId: Long?, limit: Int): List<NameCount>

  @Query(
    "SELECT n.ssid AS name, COUNT(*) AS n FROM networks n WHERE n.ssid != '' AND $TRIP_SCOPE" +
      " GROUP BY n.ssid ORDER BY n DESC, n.ssid LIMIT :limit",
  )
  suspend fun topNames(tripId: Long?, limit: Int): List<NameCount>

  @Query("SELECT COUNT(*) FROM networks n WHERE n.estMethod = 'MOBILE' AND $TRIP_SCOPE")
  suspend fun mobileCount(tripId: Long?): Int

  @Query("SELECT * FROM networks n WHERE n.estMethod = 'MOBILE' AND $TRIP_SCOPE ORDER BY n.maxSpreadM DESC, n.mac LIMIT :limit")
  suspend fun mobileNetworks(tripId: Long?, limit: Int): List<NetworkEntity>

  @Query("SELECT * FROM networks WHERE mac = :mac")
  suspend fun networkDetail(mac: String): NetworkEntity?

  @Query(SQL_SIGHTINGS_FOR)
  suspend fun sightingsFor(mac: String): List<SightingEntity>

  @Query(
    "SELECT EXISTS (SELECT 1 FROM sightings s JOIN trips t ON t.id = s.tripId" +
      " WHERE s.mac = :mac AND t.startUtc < :tripStartUtc)",
  )
  suspend fun isKnownBefore(mac: String, tripStartUtc: String): Boolean

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertArchivedFile(f: ArchivedFileEntity): Long

  companion object {
    const val SQL_TRIPS_BY_START_DESC =
      "SELECT id, logName, startUtc, endUtc, rows, uniqueMacs, positionedRows, newCount, mode FROM trips ORDER BY startUtc DESC"
    const val SQL_SIGHTINGS_FOR = "SELECT * FROM sightings WHERE mac = :mac ORDER BY tsUtc"

    /** Restricts `networks n` to one trip when :tripId is not null. */
    const val TRIP_SCOPE = "(:tripId IS NULL OR EXISTS (SELECT 1 FROM sightings s WHERE s.mac = n.mac AND s.tripId = :tripId))"
  }
}
