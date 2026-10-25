package com.wifishuriken.companion.ui.networks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wifishuriken.companion.core.SecurityClass
import com.wifishuriken.companion.map.SECURITY_COLORS

fun SecurityClass.label() = when (this) {
  SecurityClass.OPEN_OR_OWE -> "Open/OWE"
  SecurityClass.WEP -> "WEP"
  SecurityClass.WPA -> "WPA/TKIP"
  SecurityClass.WPA2 -> "WPA2"
  SecurityClass.WPA2_WPA3 -> "WPA2/3"
  SecurityClass.WPA3 -> "WPA3"
  SecurityClass.ENTERPRISE -> "Enterprise"
}

fun securityOf(name: String): SecurityClass? = runCatching { SecurityClass.valueOf(name) }.getOrNull()

fun SecurityClass.color(): Color = Color(0xFF000000 or (SECURITY_COLORS[this] ?: "#A0A0A0").removePrefix("#").toLong(16))

/** One bar split by security class, in the order of [SecurityClass]. */
@Composable
fun SecurityMixBar(bySecurity: Map<String, Int>, modifier: Modifier = Modifier) {
  val parts = SecurityClass.entries.mapNotNull { c -> bySecurity[c.name]?.takeIf { it > 0 }?.let { c to it } }
  Row(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
    if (parts.isEmpty()) Box(Modifier.fillMaxWidth().fillMaxHeight().background(Color.Gray.copy(alpha = 0.3f)))
    parts.forEach { (c, n) -> Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(c.color())) }
  }
}
