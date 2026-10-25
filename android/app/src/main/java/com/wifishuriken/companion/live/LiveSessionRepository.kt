package com.wifishuriken.companion.live

import com.wifishuriken.companion.core.CounterSnapshot
import com.wifishuriken.companion.core.ModuleFix
import com.wifishuriken.companion.core.TrackPoint
import com.wifishuriken.companion.map.MapNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

val EMPTY_COUNTERS = CounterSnapshot(0, 0, 0, 0, 0, 0, 0, emptyList())

data class LiveState(
  val connected: Boolean = false,
  val activeLog: String? = null,
  val tripStartMs: Long? = null,
  val counters: CounterSnapshot = EMPTY_COUNTERS,
  val moduleFix: ModuleFix? = null,
  val feeding: Boolean = false,
  /** The owner's "feed phone GPS" setting, so the UI can say why nothing is fed yet. */
  val feedEnabled: Boolean = false,
  val phoneBatteryPct: Int? = null,
  val userLocation: TrackPoint? = null,
  val bearingDeg: Float? = null,
  val speedKmh: Float? = null,
  val track: List<TrackPoint> = emptyList(),
  val networks: List<MapNetwork> = emptyList(),
  val syncProgress: String? = null,
  val message: String? = null,
)

/** Process-wide live session state, written by LiveSessionService and read by the UI. */
object LiveSessionRepository {
  private val _state = MutableStateFlow(LiveState())
  val state: StateFlow<LiveState> = _state.asStateFlow()

  fun update(transform: (LiveState) -> LiveState) = _state.update(transform)

  fun reset() {
    _state.value = LiveState()
  }
}
