package com.wifishuriken.companion.ui.networks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifishuriken.companion.core.Band
import com.wifishuriken.companion.core.SecurityClass
import com.wifishuriken.companion.data.ChannelCount
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.NameCount
import com.wifishuriken.companion.data.NetworkEntity
import com.wifishuriken.companion.data.NetworkFilter
import com.wifishuriken.companion.data.NetworkStats
import com.wifishuriken.companion.ui.components.cardModifier
import com.wifishuriken.companion.ui.components.formatCount
import com.wifishuriken.companion.ui.theme.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class StatsData(
  val stats: NetworkStats,
  val channels: List<ChannelCount>,
  val vendors: List<NameCount>,
  val names: List<NameCount>,
  val mobile: List<NetworkEntity>,
  val mobileCount: Int,
)

/** Informational stats views (design §6.3): channels per band, vendors, names, WPA3 adoption, moving networks. */
@Composable
fun StatsScreen(tripId: Long?, onOpenNetwork: (String) -> Unit) {
  val context = LocalContext.current
  val data by produceState<StatsData?>(null, tripId) {
    value = withContext(Dispatchers.IO) {
      val dao = CompanionDb.get(context).dao()
      StatsData(
        dao.networkStats(NetworkFilter(tripId = tripId)), dao.channelUsage(tripId), dao.topVendors(tripId, 10),
        dao.topNames(tripId, 10), dao.mobileNetworks(tripId, 20), dao.mobileCount(tripId),
      )
    }
  }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("Stats", style = MaterialTheme.typography.headlineSmall)
    Text(if (tripId == null) "All drives" else "This drive", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    val d = data ?: return@Column
    Section("Security") {
      SecurityMixBar(d.stats.bySecurity)
      SecurityClass.entries.forEach { c ->
        val n = d.stats.bySecurity[c.name] ?: 0
        if (n > 0) BarRow(c.label(), n, d.stats.total, c.color())
      }
      Text(
        "WPA3 adoption: ${d.stats.percentWpa3Capable}% (WPA3 ${d.stats.bySecurity["WPA3"] ?: 0}, WPA2/3 ${d.stats.bySecurity["WPA2_WPA3"] ?: 0})",
        fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
      )
    }
    Band.entries.forEach { band ->
      val rows = d.channels.filter { it.band == band.name }
      if (rows.isNotEmpty()) {
        Section("Channels, ${band.label}") {
          val max = rows.maxOf { it.n }
          val color = if (band == Band.GHZ_2_4) Accents.Band24 else Accents.Band5
          rows.forEach { BarRow("ch ${it.channel}", it.n, max, color) }
        }
      }
    }
    if (d.vendors.isNotEmpty()) Section("Top vendors") { d.vendors.forEach { BarRow(it.name, it.n, d.vendors.first().n, MaterialTheme.colorScheme.primary) } }
    if (d.names.isNotEmpty()) Section("Most common names") { d.names.forEach { BarRow(it.name, it.n, d.names.first().n, MaterialTheme.colorScheme.tertiary) } }
    Section("Networks that moved (${formatCount(d.mobileCount)})") {
      Text("Seen more than 1.5 km apart; likely phone hotspots or vehicles.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      d.mobile.forEach { n ->
        Row(Modifier.fillMaxWidth().clickable { onOpenNetwork(n.mac) }.padding(top = 4.dp)) {
          Text(n.ssid.ifEmpty { "(hidden)" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(String.format(Locale.US, "%.1f km", (n.maxSpreadM ?: 0.0) / 1000), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
      }
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(cardModifier(Modifier.fillMaxWidth()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(title, fontWeight = FontWeight.SemiBold)
    content()
  }
}

@Composable
private fun BarRow(label: String, n: Int, max: Int, color: Color) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.width(110.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
      Box(Modifier.fillMaxHeight().fillMaxWidth(if (max == 0) 0f else n.toFloat() / max).background(color))
    }
    Text(formatCount(n), Modifier.width(56.dp).padding(start = 6.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
  }
}
