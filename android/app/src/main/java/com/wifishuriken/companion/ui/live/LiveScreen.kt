package com.wifishuriken.companion.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifishuriken.companion.core.trackDistanceM
import com.wifishuriken.companion.live.LiveState
import com.wifishuriken.companion.ui.components.CounterTile
import com.wifishuriken.companion.ui.components.RateGraph
import com.wifishuriken.companion.ui.components.StatusChip
import com.wifishuriken.companion.ui.components.formatCount
import com.wifishuriken.companion.ui.theme.Accents
import java.util.Locale

/**
 * Live screen, layout B1 (design §6.1): map on top with status chips, six counter tiles, the
 * networks-per-minute graph and a trip strip. Stateless; the map is a slot so tests can omit it.
 * No alerts or warnings by design.
 */
@Composable
fun LiveScreen(
  state: LiveState,
  map: @Composable (Modifier) -> Unit,
  onToggleHeading: () -> Unit,
  onConnect: () -> Unit,
  onStop: () -> Unit,
  headingUp: Boolean = false,
  nowMs: Long = System.currentTimeMillis(),
) {
  Column(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxWidth().weight(0.55f)) {
      map(Modifier.fillMaxSize())
      Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val fix = state.moduleFix
        when {
          !state.connected -> StatusChip("Not connected", MaterialTheme.colorScheme.onSurfaceVariant)
          fix == null -> StatusChip("Shuriken GPS: waiting", Accents.NoGps)
          fix.fix -> StatusChip("GPS ${fix.satellites ?: 0}", Accents.Gps)
          else -> StatusChip("No fix", Accents.NoGps)
        }
        val phoneFix = state.userLocation?.let { nowMs - it.tMs < PHONE_FIX_FRESH_MS } == true
        phoneGpsStatus(state.connected, state.feeding, state.feedEnabled, phoneFix)?.let {
          StatusChip(it, if (state.feeding || (!state.connected && phoneFix)) Accents.Gps else Accents.NoGps)
        }
        state.phoneBatteryPct?.let { StatusChip("Battery $it%") }
      }
      (state.message ?: state.syncProgress)?.let {
        StatusChip(it, modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 56.dp))
      }
      Column(
        Modifier.align(Alignment.BottomEnd).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        FilledTonalButton(onClick = onToggleHeading) { Text(if (headingUp) "Heading" else "North") }
        if (state.connected) FilledTonalButton(onClick = onStop) { Text("Stop") } else FilledTonalButton(onClick = onConnect) { Text("Connect") }
      }
    }
    Column(
      Modifier.fillMaxWidth().weight(0.45f).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      val c = state.counters
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CounterTile("2.4 GHz", c.ghz24, modifier = Modifier.weight(1f))
        CounterTile("5 GHz", c.ghz5, modifier = Modifier.weight(1f))
        CounterTile("New", c.new, Accents.New, Modifier.weight(1f))
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CounterTile("With GPS", c.withGps, Accents.Gps, Modifier.weight(1f))
        CounterTile("No GPS", c.noGps, Accents.NoGps, Modifier.weight(1f))
        CounterTile("Seen before", c.seenBefore, modifier = Modifier.weight(1f))
      }
      RateGraph(c.perMinute, Modifier.fillMaxWidth())
      TripStrip(state, nowMs)
      state.message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
  }
}

@Composable
private fun TripStrip(state: LiveState, nowMs: Long) {
  val elapsed = state.tripStartMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) }
  val km = trackDistanceM(state.track) / 1000.0
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    StripItem("Time", elapsed?.let { "%d:%02d".format(it / 3600, (it % 3600) / 60) } ?: "-")
    StripItem("Distance", String.format(Locale.US, "%.1f km", km))
    StripItem("Speed", state.speedKmh?.let { "${it.toInt()} km/h" } ?: "-")
    StripItem("Open", formatCount(state.counters.open))
  }
}

@Composable
private fun StripItem(label: String, value: String) {
  Column {
    Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

private const val PHONE_FIX_FRESH_MS = 10_000L

/**
 * The phone GPS chip: while connected, whether the phone is feeding the Shuriken (it needs its own
 * fix first); while not connected, whether the phone has a fix.
 */
fun phoneGpsStatus(connected: Boolean, feeding: Boolean, feedEnabled: Boolean, phoneFix: Boolean): String? = when {
  connected && feeding -> "Phone GPS on"
  connected && feedEnabled -> "Phone GPS: waiting for fix"
  connected -> null
  phoneFix -> "Phone GPS: fix"
  else -> "Phone GPS: no fix"
}
