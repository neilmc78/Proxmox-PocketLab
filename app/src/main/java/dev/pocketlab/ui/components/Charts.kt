package dev.pocketlab.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pocketlab.ui.theme.LocalTones
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

fun clockTime(epochSec: Long): String = HHMM.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

/**
 * A time series you can touch: tap or drag across it to read the value at that moment. The caption
 * shows "14:32 · 23%" for the selected point (tap the caption to clear) and [idleCaption] otherwise.
 * [times] are epoch seconds, [values] are plotted against [max] (or the series' own max).
 */
@Composable
fun TimeChart(
    times: List<Long>,
    values: List<Double>,
    describe: (Int) -> String,
    idleCaption: String,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    max: Double? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val t = LocalTones.current
    val n = minOf(times.size, values.size)
    var selected by remember { mutableStateOf<Int?>(null) }
    val sel = selected?.takeIf { it < n }

    fun indexAt(x: Float, width: Int): Int =
        if (n < 2 || width <= 0) 0 else ((x / width) * (n - 1)).roundToInt().coerceIn(0, n - 1)

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).padding(vertical = 4.dp)
                .pointerInput(n) { detectTapGestures { selected = indexAt(it.x, size.width) } }
                .pointerInput(n) {
                    detectHorizontalDragGestures(
                        onDragStart = { selected = indexAt(it.x, size.width) },
                        onHorizontalDrag = { change, _ -> selected = indexAt(change.position.x, size.width) },
                    )
                },
        ) {
            if (n < 2) return@Canvas
            val top = (max ?: values.take(n).max()).coerceAtLeast(1e-9)
            val stepX = size.width / (n - 1)
            fun y(v: Double) = size.height - (v / top).toFloat().coerceIn(0f, 1f) * (size.height - 2f) - 1f
            val line = Path()
            for (i in 0 until n) { val x = i * stepX; if (i == 0) line.moveTo(x, y(values[i])) else line.lineTo(x, y(values[i])) }
            val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(fill, color.copy(alpha = 0.12f))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawLine(color.copy(alpha = 0.15f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
            sel?.let { i ->
                val x = i * stepX
                drawLine(t.textDim, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                drawCircle(color, 4.dp.toPx(), Offset(x, y(values[i])))
            }
        }
        if (sel != null) Text("${clockTime(times[sel])} · ${describe(sel)}", style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { selected = null })
        else Text(if (n >= 2) "$idleCaption · tap the graph for values" else idleCaption, color = t.textDim,
            style = MaterialTheme.typography.bodySmall)
    }
}
