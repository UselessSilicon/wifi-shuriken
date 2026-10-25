package com.wifishuriken.companion.ui

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
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
import com.wifishuriken.companion.map.OfflineMap
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
fun CompanionApp(mapFile: File?, onConnect: () -> Unit, settingsExtras: @Composable () -> Unit = {}) {
  val nav = rememberNavController()
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
      composable("live") { LiveRoute(mapFile, onConnect) }
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
      composable("settings") { Column { Placeholder("Settings"); settingsExtras() } }
    }
  }
}

@Composable
private fun LiveRoute(mapFile: File?, onConnect: () -> Unit) {
  val state by LiveSessionRepository.state.collectAsStateWithLifecycle()
  var headingUp by remember { mutableStateOf(false) }
  val dark = isSystemInDarkTheme()
  LiveScreen(
    state = state,
    map = { modifier ->
      OfflineMap(
        modifier = modifier, mapFile = mapFile, dark = dark, followUser = true, headingUp = headingUp,
        track = state.track, networks = state.networks, userLocation = state.userLocation,
        bearingDeg = state.bearingDeg, onNetworkClick = {},
      )
    },
    onToggleHeading = { headingUp = !headingUp },
    onRecentre = { if (!state.connected) onConnect() },
    headingUp = headingUp,
  )
}

@Composable
private fun Placeholder(title: String) {
  Box(Modifier.padding(16.dp)) {
    Text(title, style = MaterialTheme.typography.titleLarge)
  }
}
