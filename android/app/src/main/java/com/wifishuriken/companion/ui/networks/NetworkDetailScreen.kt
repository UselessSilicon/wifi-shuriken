package com.wifishuriken.companion.ui.networks

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifishuriken.companion.core.Band
import com.wifishuriken.companion.core.SecurityClass
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.NetworkEntity
import com.wifishuriken.companion.data.SightingWithTrip
import com.wifishuriken.companion.map.MapEstimate
import com.wifishuriken.companion.map.MapNetwork
import com.wifishuriken.companion.map.OfflineMap
import com.wifishuriken.companion.ui.components.cardModifier
import com.wifishuriken.companion.ui.components.formatCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private data class Detail(val network: NetworkEntity?, val sightings: List<SightingWithTrip>)

/** One network (design §6.4): sightings map with estimate circle, properties and history. */
@Composable
fun NetworkDetailScreen(mac: String, mapFile: File?) {
  val context = LocalContext.current
  val detail by produceState<Detail?>(null, mac) {
    value = withContext(Dispatchers.IO) {
      val dao = CompanionDb.get(context).dao()
      Detail(dao.networkDetail(mac), dao.sightingsWithTrip(mac))
    }
  }
  val d = detail ?: return
  val n = d.network
  if (n == null) {
    Text("Network not found", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
    return
  }
  val sec = securityOf(n.security)
  val dots = d.sightings.filter { it.lat != null && it.lon != null }.map { s ->
    MapNetwork(n.mac, s.lat!!, s.lon!!, securityOf(s.security) ?: sec ?: SecurityClass.OPEN_OR_OWE, false)
  }
  val estimate = if (n.estLat != null && n.estLon != null) MapEstimate(n.estLat, n.estLon, n.estRadiusM ?: 30.0) else null

  LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item {
      Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(sec?.color() ?: MaterialTheme.colorScheme.outline, CircleShape))
        Text(n.ssid.ifEmpty { "(hidden)" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
      }
    }
    item {
      OfflineMap(
        modifier = Modifier.fillMaxWidth().height(240.dp), mapFile = mapFile, dark = isSystemInDarkTheme(),
        followUser = false, headingUp = false, track = emptyList(), networks = dots, userLocation = null,
        bearingDeg = null, onNetworkClick = {}, estimate = estimate,
      )
    }
    item {
      Column(cardModifier(Modifier.fillMaxWidth()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Prop("Security", listOfNotNull(sec?.label(), cipherOf(n.authMode)).joinToString(", "))
        Prop("AuthMode", n.authMode, mono = true)
        Prop("Band", n.band?.let { b -> runCatching { Band.valueOf(b).label }.getOrDefault(b) } ?: "Unknown")
        Prop("Channels", n.channels.ifEmpty { "Unknown" })
        Prop("Vendor", n.vendor ?: "Unknown")
        Prop("MAC", n.mac + if (n.randomized) " (randomized)" else "", mono = true)
        Prop("First seen", "${n.firstSeenUtc} UTC")
        Prop("Last seen", "${n.lastSeenUtc} UTC")
        Prop("Strongest", n.bestRssi?.let { "$it dBm" } ?: "Unknown")
        Prop("Sightings", formatCount(n.sightings))
        Prop("Drives", formatCount(n.trips))
        Prop("Position", positionText(n))
      }
    }
    item { Text("History", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
    items(d.sightings.asReversed()) { s ->
      Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          Text(s.tsUtc, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
          Text(listOfNotNull(s.channel?.let { "ch $it" }, s.rssi?.let { "$it dBm" }, if (s.lat == null) "no GPS" else null).joinToString(" · "), fontSize = 12.sp)
        }
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun Prop(label: String, value: String, mono: Boolean = false) {
  Row {
    Text(label, Modifier.padding(end = 8.dp).weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    Text(value, Modifier.weight(0.65f), fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
  }
}

private fun cipherOf(authMode: String): String? {
  val a = authMode.uppercase()
  return listOfNotNull("CCMP".takeIf { it in a }, "TKIP".takeIf { it in a }).joinToString("+").ifEmpty { null }
}

private fun positionText(n: NetworkEntity): String = when (n.estMethod) {
  "MOBILE" -> String.format(Locale.US, "Moves (seen %.1f km apart)", (n.maxSpreadM ?: 0.0) / 1000)
  "NONE" -> "No GPS sightings"
  else -> String.format(Locale.US, "Estimated within %.0f m (%s)", n.estRadiusM ?: 0.0, (n.estConfidence ?: "").lowercase())
}
