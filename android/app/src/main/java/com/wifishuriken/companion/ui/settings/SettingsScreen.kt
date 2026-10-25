package com.wifishuriken.companion.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Settings
import com.wifishuriken.companion.data.Units
import com.wifishuriken.companion.data.exportDatabaseTo
import com.wifishuriken.companion.map.MapFileStore
import com.wifishuriken.companion.ui.components.cardModifier
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

const val ABOUT_TEXT =
  "This app was written entirely by an AI (Claude, by Anthropic), not by the owner of this copy, " +
    "who did not write or design it. It is unofficial: not associated with or endorsed by the " +
    "WiFi-Shuriken project. A personal one-off. It has no network permission; all data stays on this phone."

/** Settings (design §6.5): map file, export, phone GPS feed, units, theme note, About. */
@Composable
fun SettingsScreen(mapStore: MapFileStore, onMapImported: () -> Unit, extras: @Composable () -> Unit = {}) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings = remember { Settings(context) }
  var feedGps by remember { mutableStateOf(settings.feedPhoneGps) }
  var units by remember { mutableStateOf(settings.units) }
  var hasMap by remember { mutableStateOf(mapStore.hasMap()) }
  var progress by remember { mutableStateOf<Float?>(null) }
  var copying by remember { mutableStateOf(false) }
  var mapMessage by remember { mutableStateOf<String?>(null) }
  var exportMessage by remember { mutableStateOf<String?>(null) }

  val pickMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    copying = true
    mapMessage = null
    scope.launch {
      val result = mapStore.importFrom(context, uri) { copied, size -> progress = size?.let { copied.toFloat() / it } }
      copying = false
      progress = null
      hasMap = mapStore.hasMap()
      mapMessage = result.fold({ "Map file copied." }, { "Map import failed: ${it.message}" })
      if (result.isSuccess) onMapImported()
    }
  }
  val exportDb = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    exportMessage = "Exporting..."
    scope.launch {
      exportMessage = exportDatabaseTo(context, CompanionDb.get(context), uri)
        .fold({ "Exported ${humanBytes(it)}." }, { "Export failed: ${it.message}" })
    }
  }

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("Settings", style = MaterialTheme.typography.headlineSmall)

    Section("Offline map") {
      Text(
        if (hasMap) "Map file present (${humanBytes(mapStore.file.length())})." else "No map file. Copy a .pmtiles file to the phone, then choose it here.",
        fontSize = 13.sp,
      )
      if (copying) {
        val p = progress
        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      mapMessage?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      Button(onClick = { pickMap.launch(arrayOf("*/*")) }, enabled = !copying) { Text(if (hasMap) "Replace map file" else "Choose map file") }
    }

    Section("Live session") {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Feed phone GPS to the Shuriken", fontSize = 14.sp)
          Text("Used by the Shuriken only when its own GPS has no fix.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = feedGps, onCheckedChange = { feedGps = it; settings.feedPhoneGps = it })
      }
    }

    Section("Units") {
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(Units.METRIC to "Metric (km)", Units.IMPERIAL to "Imperial (mi)").forEachIndexed { i, (u, label) ->
          SegmentedButton(selected = units == u, onClick = { units = u; settings.units = u }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
        }
      }
      Text("Theme follows the system light/dark setting.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Section("Backup") {
      Text("Save a copy of the app's database through the system file picker. Nothing is uploaded.", fontSize = 13.sp)
      exportMessage?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      OutlinedButton(onClick = { exportDb.launch("shuriken-companion-${LocalDate.now()}.db") }) { Text("Export database") }
    }

    Section("About") {
      Text(ABOUT_TEXT, fontSize = 13.sp)
    }

    extras()
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(cardModifier(Modifier.fillMaxWidth()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, fontWeight = FontWeight.SemiBold)
    content()
  }
}

fun humanBytes(n: Long): String = when {
  n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / (1L shl 30).toDouble())
  n >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", n / (1L shl 20).toDouble())
  n >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", n / (1L shl 10).toDouble())
  else -> "$n B"
}
