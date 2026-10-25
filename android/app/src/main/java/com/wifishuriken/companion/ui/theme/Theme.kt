package com.wifishuriken.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Accent colours shared by tiles and charts (design §6, mockup B1). */
object Accents {
  val Gps = Color(0xFF22C55E)
  val NoGps = Color(0xFFF59E0B)
  val New = Color(0xFFA855F7)
  val Band24 = Color(0xFF3B82F6)
  val Band5 = Color(0xFF06B6D4)
}

private val Dark = darkColorScheme(
  primary = Color(0xFF4EA1FF),
  onPrimary = Color(0xFF0B1420),
  background = Color(0xFF0F1318),
  onBackground = Color(0xFFE8EDF2),
  surface = Color(0xFF0F1318),
  onSurface = Color(0xFFE8EDF2),
  surfaceVariant = Color(0xFF171D24),
  onSurfaceVariant = Color(0xFF93A1B0),
  surfaceContainer = Color(0xFF171D24),
  outlineVariant = Color(0x14FFFFFF),
)

private val Light = lightColorScheme(
  primary = Color(0xFF1A73E8),
  onPrimary = Color.White,
  background = Color(0xFFF4F6F9),
  onBackground = Color(0xFF121820),
  surface = Color(0xFFF4F6F9),
  onSurface = Color(0xFF121820),
  surfaceVariant = Color.White,
  onSurfaceVariant = Color(0xFF5B6876),
  surfaceContainer = Color.White,
  outlineVariant = Color(0x14000000),
)

@Composable
fun CompanionTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
