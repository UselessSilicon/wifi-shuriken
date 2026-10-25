package com.wifishuriken.companion.ui.drives

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wifishuriken.companion.core.SecurityClass
import com.wifishuriken.companion.core.TripMode
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Settings
import com.wifishuriken.companion.data.NetworkFilter
import com.wifishuriken.companion.data.NetworkSort
import com.wifishuriken.companion.map.MapNetwork
import com.wifishuriken.companion.map.OfflineMap
import com.wifishuriken.companion.ui.components.CounterTile
import com.wifishuriken.companion.ui.theme.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One drive: its map, stats, mode and a link to its networks (design §6.2). */
@Composable
fun DriveDetailScreen(tripId: Long, mapFile: File?, onOpenNetworks: (Long) -> Unit, vm: DrivesViewModel = viewModel()) {
  val rows by vm.visible.collectAsStateWithLifecycle()
  val row = rows.firstOrNull { it.summary.id == tripId }
  val context = LocalContext.current
  var networks by remember { mutableStateOf<List<MapNetwork>>(emptyList()) }
  LaunchedEffect(tripId) {
    networks = withContext(Dispatchers.IO) {
      CompanionDb.get(context).dao().networksPage(NetworkFilter(tripId = tripId), NetworkSort.NEWEST, 3_000, 0)
        .filter { it.estLat != null && it.estLon != null }
        .map { MapNetwork(it.mac, it.estLat!!, it.estLon!!, SecurityClass.valueOf(it.security), it.firstTripId == tripId) }
    }
  }
  Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (row == null) {
      Text("Drive not found", style = MaterialTheme.typography.titleMedium)
      return@Column
    }
    val s = row.summary
    Text("${s.startUtc} UTC", style = MaterialTheme.typography.titleLarge)
    Text(
      listOfNotNull(row.durationS?.let { "%d min".format(it / 60) }, formatDistance(row.distanceKm, remember { Settings(context).units }), "${row.positionedPct}% with GPS")
        .joinToString(" · "),
      color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
    )
    OfflineMap(
      modifier = Modifier.fillMaxWidth().height(260.dp), mapFile = mapFile, dark = isSystemInDarkTheme(),
      followUser = false, headingUp = false, track = row.track, networks = networks, userLocation = null,
      bearingDeg = null, onNetworkClick = {},
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      CounterTile("Sightings", s.rows, modifier = Modifier.weight(1f))
      CounterTile("Networks", s.uniqueMacs, modifier = Modifier.weight(1f))
      CounterTile("New", s.newCount, Accents.New, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      TripMode.entries.forEach { m ->
        FilterChip(selected = row.mode == m, onClick = { vm.setMode(tripId, m) }, label = { Text(m.label()) })
      }
    }
    Button(onClick = { onOpenNetworks(tripId) }, modifier = Modifier.fillMaxWidth()) { Text("Networks in this drive") }
  }
}
