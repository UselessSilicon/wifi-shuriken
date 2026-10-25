package com.wifishuriken.companion.ui.drives

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wifishuriken.companion.core.TripMode
import com.wifishuriken.companion.data.Settings
import com.wifishuriken.companion.data.Units
import com.wifishuriken.companion.map.OfflineMap
import com.wifishuriken.companion.ui.components.cardModifier
import com.wifishuriken.companion.ui.components.formatCount
import com.wifishuriken.companion.ui.theme.Accents
import java.io.File
import java.util.Locale

/** Drives list with mode and date filters and an "All on map" view (design §6.2). */
@Composable
fun DrivesScreen(mapFile: File?, onOpenDrive: (Long) -> Unit, vm: DrivesViewModel = viewModel()) {
  val rows by vm.visible.collectAsStateWithLifecycle()
  val mode by vm.modeFilter.collectAsStateWithLifecycle()
  val range by vm.range.collectAsStateWithLifecycle()
  var onMap by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
    Text("Drives", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
    Text(
      "${rows.size} drives, ${formatCount(rows.sumOf { it.summary.rows })} sightings",
      color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
      listOf(false to "List", true to "All on map").forEachIndexed { i, (value, label) ->
        SegmentedButton(selected = onMap == value, onClick = { onMap = value }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
      }
    }
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      DateRange.entries.forEach { r ->
        FilterChip(selected = range == r, onClick = { vm.range.value = r }, label = { Text(r.label()) })
      }
    }
    Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      FilterChip(selected = mode == null, onClick = { vm.modeFilter.value = null }, label = { Text("Any") })
      TripMode.entries.forEach { m ->
        FilterChip(selected = mode == m, onClick = { vm.modeFilter.value = m }, label = { Text(m.label()) })
      }
    }
    if (onMap) {
      val networks by vm.allNetworks.collectAsStateWithLifecycle()
      LaunchedEffect(Unit) { vm.loadAllNetworks() }
      OfflineMap(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp), mapFile = mapFile, dark = isSystemInDarkTheme(),
        followUser = false, headingUp = false, track = emptyList(), networks = networks, userLocation = null,
        bearingDeg = null, onNetworkClick = {}, extraTracks = rows.map { it.track },
      )
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(rows, key = { it.summary.id }) { row -> DriveRowItem(row) { onOpenDrive(row.summary.id) } }
      }
    }
  }
}

@Composable
private fun DriveRowItem(row: DriveRow, onClick: () -> Unit) {
  val s = row.summary
  val context = LocalContext.current
  val units = remember { Settings(context).units }
  Column(cardModifier(Modifier.fillMaxWidth()).clickable(onClick = onClick).padding(10.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("${s.startUtc} UTC", fontWeight = FontWeight.SemiBold)
      Text("${formatCount(s.newCount)} new", color = Accents.New, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
    Text(
      listOfNotNull(
        row.mode?.label(),
        row.durationS?.let { "%d min".format(it / 60) },
        formatDistance(row.distanceKm, units),
        "${formatCount(s.rows)} sightings",
        "${formatCount(s.uniqueMacs)} networks",
        "${row.positionedPct}% with GPS",
      ).joinToString(" · "),
      color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
    )
  }
}

fun TripMode.label() = when (this) {
  TripMode.DRIVE -> "Drive"
  TripMode.WALK -> "Walk"
  TripMode.BIKE -> "Bike"
}

fun DateRange.label() = when (this) {
  DateRange.ALL -> "All time"
  DateRange.WEEK -> "This week"
  DateRange.MONTH -> "This month"
}

fun formatDistance(km: Double, units: Units): String = when (units) {
  Units.METRIC -> String.format(Locale.US, "%.1f km", km)
  Units.IMPERIAL -> String.format(Locale.US, "%.1f mi", km * 0.621371)
}
