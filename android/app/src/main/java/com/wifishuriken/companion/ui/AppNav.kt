package com.wifishuriken.companion.ui

import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wifishuriken.companion.core.TrackPoint
import com.wifishuriken.companion.live.PhoneLocation
import kotlinx.coroutines.delay
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import com.wifishuriken.companion.ui.drives.DriveDetailScreen
import com.wifishuriken.companion.ui.drives.DrivesScreen
import com.wifishuriken.companion.ui.networks.NetworkDetailScreen
import com.wifishuriken.companion.ui.networks.NetworksScreen
import com.wifishuriken.companion.ui.networks.StatsScreen
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wifishuriken.companion.live.LiveSessionRepository
import com.wifishuriken.companion.map.MapFileStore
import com.wifishuriken.companion.map.OfflineMap
import com.wifishuriken.companion.ui.settings.SettingsScreen
import com.wifishuriken.companion.ui.live.LiveScreen
import java.io.File

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
  Tab("live", "Live", Icons.Filled.Place),
  Tab("drives", "Drives", Icons.Filled.List),
  Tab("networks?tripId=-1", "Networks", Icons.Filled.Search),
  Tab("settings", "Settings", Icons.Filled.Settings),
)

/** App shell: bottom navigation Live, Drives, Networks, Settings (design §6). */
@Composable
fun CompanionApp(mapStore: MapFileStore, onConnect: () -> Unit, onStop: () -> Unit, settingsExtras: @Composable () -> Unit = {}) {
  val nav = rememberNavController()
  var mapFile by remember { mutableStateOf(mapStore.file.takeIf { mapStore.hasMap() }) }
  val current by nav.currentBackStackEntryAsState()
  Scaffold(
    bottomBar = {
      NavigationBar {
        TABS.forEach { tab ->
          NavigationBarItem(
            selected = current?.destination?.route?.substringBefore('?') == tab.route.substringBefore('?'),
            onClick = {
              nav.navigate(tab.route) {
                popUpTo("live") { saveState = true }
                launchSingleTop = true
                restoreState = true
              }
            },
            icon = { Icon(tab.icon, contentDescription = null) },
            label = { Text(tab.label) },
          )
        }
      }
    },
  ) { padding ->
    NavHost(nav, startDestination = "live", modifier = Modifier.padding(padding)) {
      composable("live") { LiveRoute(mapFile, onConnect, onStop) }
      composable("drives") { DrivesScreen(mapFile, onOpenDrive = { nav.navigate("drive/$it") }) }
      composable("drive/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
        DriveDetailScreen(entry.arguments!!.getLong("id"), mapFile, onOpenNetworks = { nav.navigate("networks?tripId=$it") })
      }
      composable(
        "networks?tripId={tripId}",
        arguments = listOf(navArgument("tripId") { type = NavType.LongType; defaultValue = -1L }),
      ) { entry ->
        NetworksScreen(
          tripId = entry.arguments!!.getLong("tripId").takeIf { it >= 0 },
          onOpenNetwork = { nav.navigate("network/" + Uri.encode(it)) },
          onOpenStats = { nav.navigate("stats?tripId=${it ?: -1}") },
        )
      }
      composable("stats?tripId={tripId}", arguments = listOf(navArgument("tripId") { type = NavType.LongType; defaultValue = -1L })) { entry ->
        StatsScreen(entry.arguments!!.getLong("tripId").takeIf { it >= 0 }, onOpenNetwork = { nav.navigate("network/" + Uri.encode(it)) })
      }
      composable("network/{mac}") { entry -> NetworkDetailScreen(entry.arguments!!.getString("mac")!!, mapFile) }
      composable("settings") {
        SettingsScreen(mapStore, onMapImported = { mapFile = mapStore.file.takeIf { mapStore.hasMap() } }, extras = settingsExtras)
      }
    }
  }
}

/** The Live screen reads the phone's GPS itself only while visible and without a session. */
fun phoneGpsActive(connected: Boolean, canLocate: Boolean, visible: Boolean): Boolean = !connected && canLocate && visible

@Composable
private fun LiveRoute(mapFile: File?, onConnect: () -> Unit, onStop: () -> Unit) {
  val session by LiveSessionRepository.state.collectAsStateWithLifecycle()
  var headingUp by remember { mutableStateOf(false) }
  val dark = isSystemInDarkTheme()
  val context = LocalContext.current
  // Re-checked on resume so the position appears right after the permission is granted.
  val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
  val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
  val canLocate = remember(lifecycleState) {
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
  }
  // Without a session the screen reads the phone's GPS itself (only while visible); during a
  // session the service does, for the track and the feed to the Shuriken.
  var phone by remember { mutableStateOf<TrackPoint?>(null) }
  var phoneBearing by remember { mutableStateOf<Float?>(null) }
  val readGps = phoneGpsActive(session.connected, canLocate, visible)
  DisposableEffect(readGps) {
    if (!readGps) return@DisposableEffect onDispose {}
    val location = PhoneLocation(context)
    location.start(onLocation = { l ->
      phone = TrackPoint(l.time, l.latitude, l.longitude)
      if (l.hasBearing()) phoneBearing = l.bearing
    }, onNmea = {})
    onDispose { location.stop() }
  }
  val now by produceState(System.currentTimeMillis(), visible) {
    while (visible) {
      delay(1_000)
      value = System.currentTimeMillis()
    }
  }
  val state = if (session.connected) session else session.copy(userLocation = phone, bearingDeg = phoneBearing)
  LiveScreen(
    nowMs = now,
    state = state,
    map = { modifier ->
      OfflineMap(
        modifier = modifier, mapFile = mapFile, dark = dark, followUser = true, headingUp = headingUp,
        track = state.track, networks = state.networks, userLocation = state.userLocation,
        bearingDeg = state.bearingDeg, onNetworkClick = {},
      )
    },
    onToggleHeading = { headingUp = !headingUp },
    onConnect = onConnect,
    onStop = onStop,
    headingUp = headingUp,
  )
}
