package com.wifishuriken.companion.ui.drives

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifishuriken.companion.core.SecurityClass
import com.wifishuriken.companion.core.TrackPoint
import com.wifishuriken.companion.core.TripMode
import com.wifishuriken.companion.core.durationSeconds
import com.wifishuriken.companion.core.inferTripMode
import com.wifishuriken.companion.core.percentOf
import com.wifishuriken.companion.core.trackDistanceM
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.NetworkFilter
import com.wifishuriken.companion.data.NetworkSort
import com.wifishuriken.companion.data.TripSummary
import com.wifishuriken.companion.live.LiveSessionService
import com.wifishuriken.companion.map.MapNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class DateRange(private val days: Long?) {
  ALL(null), WEEK(7), MONTH(30);

  /** Earliest included time as a log timestamp (UTC), or null for all time. */
  fun cutoffUtc(): String? = days?.let {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.now().minusSeconds(it * 86_400))
  }
}

data class DriveRow(
  val summary: TripSummary,
  val distanceKm: Double,
  val durationS: Long?,
  val positionedPct: Int,
  val mode: TripMode?,
  val track: List<TrackPoint>,
)

/** Drives list (design §6.2): stored per-trip totals plus distance and mode from the trip's track. */
class DrivesViewModel(app: Application) : AndroidViewModel(app) {
  private val dao = CompanionDb.get(app).dao()
  val modeFilter = MutableStateFlow<TripMode?>(null)
  val range = MutableStateFlow(DateRange.ALL)

  private val rows = dao.tripsByStartDesc().map { trips -> trips.map { enrich(it) } }.flowOn(Dispatchers.IO)

  val visible: StateFlow<List<DriveRow>> = combine(rows, modeFilter, range) { all, mode, r ->
    val cutoff = r.cutoffUtc()
    all.filter { (mode == null || it.mode == mode) && (cutoff == null || it.summary.startUtc >= cutoff) }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val allNetworks = MutableStateFlow<List<MapNetwork>>(emptyList())

  fun loadAllNetworks() = viewModelScope.launch(Dispatchers.IO) {
    allNetworks.value = dao.networksPage(NetworkFilter(), NetworkSort.MOST_SEEN, 5_000, 0)
      .filter { it.estLat != null && it.estLon != null }
      .map { MapNetwork(it.mac, it.estLat!!, it.estLon!!, SecurityClass.valueOf(it.security), false) }
  }

  fun setMode(tripId: Long, mode: TripMode?) = viewModelScope.launch(Dispatchers.IO) { dao.setTripMode(tripId, mode?.name) }

  private suspend fun enrich(t: TripSummary): DriveRow {
    val phone = dao.trackFor(t.id).map { TrackPoint(it.tMs, it.lat, it.lon) }
    val track = phone.ifEmpty {
      dao.positionsForTrip(t.id).mapNotNull { p -> LiveSessionService.epochMs(p.tsUtc)?.let { TrackPoint(it, p.lat, p.lon) } }
    }
    val mode = t.mode?.let { runCatching { TripMode.valueOf(it) }.getOrNull() } ?: inferTripMode(track)
    return DriveRow(t, trackDistanceM(track) / 1000.0, durationSeconds(t.startUtc, t.endUtc), percentOf(t.positionedRows, t.rows), mode, track)
  }
}
