package com.wifishuriken.companion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat

private val NUMBER = NumberFormat.getIntegerInstance()
fun formatCount(n: Int): String = NUMBER.format(n)

private val CardShape = RoundedCornerShape(14.dp)

@Composable
fun cardModifier(modifier: Modifier = Modifier): Modifier = modifier
  .background(MaterialTheme.colorScheme.surfaceVariant, CardShape)
  .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)

@Composable
fun CounterTile(label: String, value: Int, accent: Color? = null, modifier: Modifier = Modifier) {
  Column(cardModifier(modifier).padding(horizontal = 10.dp, vertical = 8.dp)) {
    Text(
      formatCount(value),
      fontSize = 22.sp,
      fontWeight = FontWeight.Bold,
      color = accent ?: MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
    )
    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
  }
}

@Composable
fun StatusChip(text: String, dot: Color? = null, modifier: Modifier = Modifier) {
  Row(
    modifier
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f), RoundedCornerShape(50))
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
      .padding(horizontal = 10.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
  }
}

/** A small line chart of new networks per minute. */
@Composable
fun RateGraph(perMinute: List<Int>, modifier: Modifier = Modifier) {
  val color = MaterialTheme.colorScheme.primary
  Column(cardModifier(modifier).padding(horizontal = 10.dp, vertical = 8.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Networks per minute", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(formatCount(perMinute.lastOrNull() ?: 0), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    Canvas(Modifier.fillMaxWidth().height(34.dp).padding(top = 4.dp)) {
      if (perMinute.size < 2) return@Canvas
      val max = (perMinute.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
      val step = size.width / (perMinute.size - 1)
      val path = Path()
      perMinute.forEachIndexed { i, v ->
        val p = Offset(i * step, size.height - v / max * size.height)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
      }
      drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
  }
}
