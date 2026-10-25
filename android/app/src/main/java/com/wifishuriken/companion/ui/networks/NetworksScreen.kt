package com.wifishuriken.companion.ui.networks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wifishuriken.companion.core.Band
import com.wifishuriken.companion.core.SecurityClass
import com.wifishuriken.companion.data.NetworkEntity
import com.wifishuriken.companion.data.NetworkSort
import com.wifishuriken.companion.data.NetworkStats
import com.wifishuriken.companion.ui.components.cardModifier
import com.wifishuriken.companion.ui.components.formatCount
import com.wifishuriken.companion.ui.drives.DateRange
import com.wifishuriken.companion.ui.drives.label

/** Networks browse (design §6.3): header stats, search, sort, filters, paged list. */
@Composable
fun NetworksScreen(tripId: Long?, onOpenNetwork: (String) -> Unit, onOpenStats: (Long?) -> Unit, vm: NetworksViewModel = viewModel()) {
  LaunchedEffect(tripId) { vm.setScope(tripId) }
  val items by vm.items.collectAsStateWithLifecycle()
  val stats by vm.stats.collectAsStateWithLifecycle()
  val filter by vm.filter.collectAsStateWithLifecycle()
  val sort by vm.sort.collectAsStateWithLifecycle()
  val range by vm.range.collectAsStateWithLifecycle()
  var more by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 10 } }
  LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.isNotEmpty()) vm.loadMore() }

  Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Networks", style = MaterialTheme.typography.headlineSmall)
        Text(if (tripId == null) "All drives" else "This drive", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
      }
      TextButton(onClick = { onOpenStats(tripId) }) { Text("Stats") }
    }
    StatsHeader(stats)
    OutlinedTextField(
      value = filter.query.orEmpty(), onValueChange = { q -> vm.update { it.copy(query = q.ifEmpty { null }) } },
      label = { Text("Search name or MAC") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      SortMenu(sort, vm::setSort)
      SecurityClass.entries.forEach { c ->
        FilterChip(
          selected = c in filter.security,
          onClick = { vm.update { f -> f.copy(security = if (c in f.security) f.security - c else f.security + c) } },
          label = { Text(c.label()) },
          leadingIcon = { Box(Modifier.size(8.dp).background(c.color(), CircleShape)) },
        )
      }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Band.entries.forEach { b ->
        FilterChip(selected = filter.band == b, onClick = { vm.update { it.copy(band = if (it.band == b) null else b) } }, label = { Text(b.label) })
      }
      FilterChip(selected = filter.hiddenOnly, onClick = { vm.update { it.copy(hiddenOnly = !it.hiddenOnly) } }, label = { Text("Hidden") })
      FilterChip(selected = filter.randomizedOnly, onClick = { vm.update { it.copy(randomizedOnly = !it.randomizedOnly) } }, label = { Text("Randomized MAC") })
      FilterChip(selected = more, onClick = { more = !more }, label = { Text("More filters") })
    }
    if (more) {
      Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
          value = filter.vendor.orEmpty(), onValueChange = { v -> vm.update { it.copy(vendor = v.ifEmpty { null }) } },
          label = { Text("Vendor") }, singleLine = true, modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
          value = filter.channel?.toString().orEmpty(), onValueChange = { v -> vm.update { it.copy(channel = v.toIntOrNull()) } },
          label = { Text("Channel") }, singleLine = true, modifier = Modifier.weight(1f),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }
      Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DateRange.entries.forEach { r -> FilterChip(selected = range == r, onClick = { vm.setRange(r) }, label = { Text("Seen " + r.label().lowercase()) }) }
      }
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
      items(items, key = { it.mac }) { n -> NetworkRow(n) { onOpenNetwork(n.mac) } }
      if (items.isEmpty()) item { Text("No networks match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
    }
  }
}

@Composable
private fun StatsHeader(stats: NetworkStats) {
  Column(cardModifier(Modifier.fillMaxWidth().padding(top = 8.dp)).padding(10.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      HeaderStat("Networks", formatCount(stats.total))
      HeaderStat("Open", "${stats.percentOpen}%")
      HeaderStat("WPA3-capable", "${stats.percentWpa3Capable}%")
    }
    SecurityMixBar(stats.bySecurity, Modifier.padding(top = 8.dp))
  }
}

@Composable
private fun HeaderStat(label: String, value: String) {
  Column {
    Text(value, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
  }
}

@Composable
private fun SortMenu(sort: NetworkSort, onSort: (NetworkSort) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    FilterChip(selected = true, onClick = { open = true }, label = { Text("Sort: " + sort.label()) })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      NetworkSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label()) }, onClick = { onSort(s); open = false }) }
    }
  }
}

fun NetworkSort.label() = when (this) {
  NetworkSort.MOST_SEEN -> "Most seen"
  NetworkSort.NEWEST -> "Newest"
  NetworkSort.STRONGEST -> "Strongest"
  NetworkSort.NAME -> "Name"
  NetworkSort.SECURITY -> "Security"
  NetworkSort.CHANNEL -> "Channel"
  NetworkSort.VENDOR -> "Vendor"
  NetworkSort.MOST_TRIPS -> "Most drives"
}

@Composable
private fun NetworkRow(n: NetworkEntity, onClick: () -> Unit) {
  val sec = securityOf(n.security)
  Row(cardModifier(Modifier.fillMaxWidth()).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).background(sec?.color() ?: MaterialTheme.colorScheme.outline, CircleShape))
    Column(Modifier.weight(1f).padding(start = 10.dp)) {
      Text(n.ssid.ifEmpty { "(hidden)" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        listOfNotNull(n.mac, n.vendor).joinToString(" · "),
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
      Text(
        listOfNotNull(sec?.label(), n.channel?.let { "ch $it" }, "${formatCount(n.sightings)} seen", "${n.trips} drives").joinToString(" · "),
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    n.bestRssi?.let { Text("$it dBm", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
  }
}
