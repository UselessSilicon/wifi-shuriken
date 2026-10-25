package com.wifishuriken.companion.data

import androidx.room.withTransaction
import com.wifishuriken.companion.core.Band
import com.wifishuriken.companion.core.CsvRecord
import com.wifishuriken.companion.core.CsvRecordSplitter
import com.wifishuriken.companion.core.Mac
import com.wifishuriken.companion.core.OuiTable
import com.wifishuriken.companion.core.ShurikenLogName
import com.wifishuriken.companion.core.SightingPoint
import com.wifishuriken.companion.core.WigleCsv
import com.wifishuriken.companion.core.WigleRecord
import com.wifishuriken.companion.core.classifySecurity
import com.wifishuriken.companion.core.estimatePosition
import java.io.InputStream

data class IngestResult(val rowsInserted: Int, val rejected: Int, val macsTouched: Set<String>)

/**
 * Writes log rows into the database. Rows are keyed by (trip, byte offset), so the live path and a
 * later import of the finished file never double count; trip totals and network aggregates are
 * recomputed from the tables after every batch (design §8.2).
 */
class Ingestor(private val db: CompanionDb, private val oui: OuiTable?) {
  private val dao = db.dao()

  /** Imports a whole log file; a final line without a newline (power lost mid-write) is not imported. */
  suspend fun ingestFile(logName: String, bytes: ByteArray): IngestResult = ingestStream(logName, bytes.inputStream())

  /**
   * Imports a log from [input] in chunks of [chunkBytes]: one transaction per chunk for the rows, then
   * one for the network and trip aggregates, so a long log never has to fit in memory at once.
   */
  suspend fun ingestStream(logName: String, input: InputStream, chunkBytes: Int = DEFAULT_CHUNK_BYTES): IngestResult {
    val splitter = CsvRecordSplitter()
    val buf = ByteArray(chunkBytes)
    var inserted = 0
    var rejected = 0
    val touched = HashSet<String>()
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      if (n == 0) continue
      val records = splitter.feed(buf, 0, n)
      if (records.isEmpty()) continue
      db.withTransaction {
        val r = insertRecords(ensureTrip(logName), records)
        inserted += r.rowsInserted
        rejected += r.rejected
        touched += r.macsTouched
      }
    }
    db.withTransaction {
      val tripId = ensureTrip(logName)
      (recomputeNetworksInternal(touched) + tripId).forEach { recomputeTrip(it) }
    }
    return IngestResult(inserted, rejected, touched)
  }

  /** Imports complete records of [logName] (the live path, fed by the log tailer). */
  suspend fun ingestRecords(logName: String, records: List<CsvRecord>): IngestResult = db.withTransaction {
    val tripId = ensureTrip(logName)
    val r = insertRecords(tripId, records)
    val affectedTrips = recomputeNetworksInternal(r.macsTouched) + tripId
    affectedTrips.forEach { recomputeTrip(it) }
    r
  }

  /** Inserts the sightings and rejected rows of [records]; the caller recomputes aggregates. */
  private suspend fun insertRecords(tripId: Long, records: List<CsvRecord>): IngestResult {
    var inserted = 0
    var rejected = 0
    val touched = HashSet<String>()
    for (record in records) {
      when (val parsed = WigleCsv.parseRecord(record.text)) {
        is WigleRecord.Wifi -> {
          val s = parsed.sighting
          val id = dao.insertSighting(
            SightingEntity(
              tripId = tripId,
              byteOffset = record.offset,
              mac = s.mac,
              ssid = s.ssid,
              authMode = s.authMode,
              security = classifySecurity(s.authMode).name,
              channel = s.channel,
              band = Band.fromChannel(s.channel)?.name,
              rssi = s.rssi,
              tsUtc = s.firstSeen,
              lat = s.latitude,
              lon = s.longitude,
              altM = s.altitudeMeters,
              accuracyM = s.accuracyMeters,
            ),
          )
          if (id != -1L) {
            inserted++
            touched += s.mac
          }
        }
        is WigleRecord.Rejected -> {
          rejected++
          dao.insertRejected(RejectedRowEntity(tripId = tripId, byteOffset = record.offset, reason = parsed.reason))
        }
        else -> Unit // pre-header, column header, unrecognized lines
      }
    }
    return IngestResult(inserted, rejected, touched)
  }

  /** Recomputes aggregates and position estimates for [macs] (all networks when null). */
  suspend fun recomputeNetworks(macs: Collection<String>? = null) = db.withTransaction {
    val affected = recomputeNetworksInternal(macs ?: dao.allMacs())
    affected.forEach { recomputeTrip(it) }
  }

  /** Recomputes one trip's stored totals from the tables (idempotent). */
  suspend fun recomputeTrip(tripId: Long) {
    dao.updateTripTotals(
      tripId = tripId,
      rows = dao.sightingCountForTrip(tripId),
      rejected = dao.rejectedCountForTrip(tripId),
      uniqueMacs = dao.uniqueMacsForTrip(tripId),
      positioned = dao.positionedRowsForTrip(tripId),
      newCount = dao.newCountForTrip(tripId),
      endUtc = dao.lastRowTimeForTrip(tripId),
    )
  }

  private suspend fun ensureTrip(logName: String): Long {
    dao.tripByLogName(logName)?.let { return it.id }
    dao.insertTrip(TripEntity(logName = logName, startUtc = startUtcOf(logName)))
    return dao.tripByLogName(logName)!!.id
  }

  /** Returns the trips whose "new" count may have changed (old and new first trips). */
  private suspend fun recomputeNetworksInternal(macs: Collection<String>): Set<Long> {
    val affected = HashSet<Long>()
    for (mac in macs) {
      val old = dao.networkDetail(mac)
      old?.let { affected += it.firstTripId }
      val rows = dao.sightingsWithTrip(mac)
      if (rows.isEmpty()) {
        dao.deleteNetwork(mac)
        continue
      }
      val first = rows.minWith(compareBy<SightingWithTrip>({ it.tripStartUtc }, { it.tripId }))
      val latest = rows.last()
      val named = rows.lastOrNull { it.ssid.isNotEmpty() }
      val band = latest.band?.let { b -> Band.entries.firstOrNull { it.name == b } }
      val estimate = estimatePosition(
        rows.filter { it.lat != null && it.lon != null }.map { SightingPoint(it.lat!!, it.lon!!, it.rssi, it.tripId) },
        band,
      )
      dao.upsertNetwork(
        NetworkEntity(
          mac = mac,
          ssid = named?.ssid ?: "",
          security = latest.security,
          authMode = latest.authMode,
          band = latest.band,
          channel = latest.channel,
          channels = rows.mapNotNull { it.channel }.distinct().sorted().joinToString(","),
          firstSeenUtc = rows.first().tsUtc,
          lastSeenUtc = latest.tsUtc,
          firstTripId = first.tripId,
          sightings = rows.size,
          trips = rows.map { it.tripId }.distinct().size,
          bestRssi = rows.mapNotNull { it.rssi }.maxOrNull(),
          vendor = oui?.vendor(mac),
          randomized = Mac.isRandomized(mac),
          estLat = estimate.lat,
          estLon = estimate.lon,
          estMethod = estimate.method.name,
          estConfidence = estimate.confidence?.name,
          estRadiusM = estimate.radiusM,
          maxSpreadM = estimate.maxSpreadM,
        ),
      )
      affected += first.tripId
    }
    return affected
  }

  companion object {
    const val DEFAULT_CHUNK_BYTES = 256 * 1024

    /** `shuriken_20260101120000.csv` -> `2026-01-01 12:00:00` (the firmware names logs from GNSS UTC). */
    fun startUtcOf(logName: String): String {
      val ts = ShurikenLogName.parse(logName)?.timestamp ?: return "1970-01-01 00:00:00"
      return "${ts.substring(0, 4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)} " +
        "${ts.substring(8, 10)}:${ts.substring(10, 12)}:${ts.substring(12, 14)}"
    }
  }
}
