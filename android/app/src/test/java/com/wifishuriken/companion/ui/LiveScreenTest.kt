package com.wifishuriken.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wifishuriken.companion.core.CounterSnapshot
import com.wifishuriken.companion.core.ModuleFix
import com.wifishuriken.companion.live.LiveState
import com.wifishuriken.companion.ui.live.LiveScreen
import com.wifishuriken.companion.ui.theme.CompanionTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveScreenTest {
  @get:Rule val compose = createComposeRule()

  private val state = LiveState(
    connected = true,
    activeLog = "shuriken_20260101120000.csv",
    counters = CounterSnapshot(1101, 2202, 3303, 404, 505, 606, 707, listOf(3, 5, 8)),
    moduleFix = ModuleFix(true, 9, 0.9),
    feeding = true,
    phoneBatteryPct = 82,
  )

  @Test
  fun liveScreenShowsSixCounters() {
    compose.setContent {
      CompanionTheme { LiveScreen(state = state, map = { Box(it) }, onToggleHeading = {}, onConnect = {}, onStop = {}) }
    }
    for ((label, value) in listOf(
      "2.4 GHz" to "1,101", "5 GHz" to "2,202", "With GPS" to "3,303",
      "No GPS" to "404", "New" to "505", "Seen before" to "606",
    )) {
      compose.onNodeWithText(label).assertExists()
      compose.onNodeWithText(value).assertExists()
    }
    compose.onNodeWithText("Phone GPS on").assertExists()
    compose.onNodeWithText("GPS 9").assertExists()
    compose.onAllNodes(hasText("alert", substring = true, ignoreCase = true)).assertCountEquals(0)
    compose.onAllNodes(hasText("warning", substring = true, ignoreCase = true)).assertCountEquals(0)
  }

  @Test
  fun noPhoneGpsChipWhenNotFeeding() {
    compose.setContent {
      CompanionTheme { LiveScreen(state = state.copy(feeding = false), map = { Box(it) }, onToggleHeading = {}, onConnect = {}, onStop = {}) }
    }
    compose.onAllNodes(hasText("Phone GPS on")).assertCountEquals(0)
  }

  @Test
  fun stopButtonEndsSessionWhenConnected() {
    var stopped = false
    compose.setContent {
      CompanionTheme { LiveScreen(state = state, map = { Box(it) }, onToggleHeading = {}, onConnect = {}, onStop = { stopped = true }) }
    }
    compose.onNodeWithText("Stop").performClick()
    assertTrue(stopped)
  }
}
