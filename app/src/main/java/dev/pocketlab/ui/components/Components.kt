package dev.pocketlab.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketlab.ui.theme.LocalTones

val CardShape = RoundedCornerShape(22.dp)

@Composable
fun LabCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTones.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(t.surface)
            .border(1.dp, t.outline.copy(alpha = 0.6f), CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

enum class Health { OK, WARN, BAD, OFF, PAUSED }

@Composable
fun StatusLabel(health: Health, text: String, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    val (icon, color) = when (health) {
        Health.OK -> Icons.Outlined.CheckCircle to t.ok
        Health.WARN -> Icons.Outlined.WarningAmber to t.warn
        Health.BAD -> Icons.Outlined.ErrorOutline to t.bad
        Health.OFF -> Icons.Outlined.RadioButtonUnchecked to t.textDim
        Health.PAUSED -> Icons.Outlined.PauseCircle to t.warn
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    val color = when {
        fraction > 0.92f -> t.bad
        fraction > 0.8f -> t.warn
        else -> MaterialTheme.colorScheme.primary
    }
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.surfaceHigh)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f).let { if (it in 0.001f..0.02f) 0.02f else it }).height(6.dp)
            .clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
fun Metric(label: String, fraction: Float?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row {
            Text("$label ", style = MaterialTheme.typography.labelMedium, color = LocalTones.current.textDim)
            Text(fraction?.let { pct(it.toDouble()) } ?: "–", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        UsageBar(fraction ?: 0f, Modifier.fillMaxWidth())
    }
}

@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, max: Double? = null, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val top = (max ?: values.max()).coerceAtLeast(1e-9)
        val stepX = size.width / (values.size - 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v / top).toFloat().coerceIn(0f, 1f) * (size.height - 2f) - 1f
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawLine(color.copy(alpha = 0.15f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
    }
}

@Composable
fun NavRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.textDim)
        }
        trailing?.invoke() ?: Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LocalTones.current.textDim)
    }
}

@Composable
fun KeyValueRow(key: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(key, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.textDim,
                fontFamily = if (mono) FontFamily.Monospace else null)
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = LocalTones.current.outline.copy(alpha = 0.6f))

@Composable
fun ErrorCard(text: String, action: Pair<String, () -> Unit>? = null) {
    val t = LocalTones.current
    LabCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = t.bad)
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalTones.current.surface, contentColor = LocalTones.current.text) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), content = content)
    }
}

/** Confirmation sheet for anything that changes state on a server. */
@Composable
fun ConfirmSheet(
    title: String, target: String, targetSub: String, explanation: String,
    confirmLabel: String, destructive: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    val t = LocalTones.current
    Sheet(onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        LabCard {
            Column(Modifier.padding(16.dp)) {
                Text(target, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(targetSub, style = MaterialTheme.typography.bodyMedium, color = t.textDim)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(explanation, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(18.dp))
        Button(onClick = { onConfirm(); onDismiss() }, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = if (destructive) ButtonDefaults.buttonColors(containerColor = t.bad, contentColor = Color.Black) else ButtonDefaults.buttonColors()) {
            Text(confirmLabel, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
fun TrustDialog(title: String, what: String, fingerprint: String, warning: Boolean, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(what)
                SelectionContainer { Text(fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Text(
                    if (warning) "This does NOT match what you trusted before. Only continue if you know why it changed (reinstall, new certificate). Otherwise someone may be intercepting the connection."
                    else "Check it against the server (Proxmox: Datacenter → node → Certificates; SSH: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub). Pocketlab will refuse any other one from now on.",
                    color = if (warning) LocalTones.current.bad else LocalTones.current.textDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onTrust(); onDismiss() }) { Text(if (warning) "Replace and trust" else "Trust") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun pct(f: Double) = "${(f * 100).toInt()}%"

fun bytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var v = b.toDouble() / 1024; var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 10 || v % 1.0 == 0.0) "${v.toInt()} ${units[i]}" else "%.1f %s".format(v, units[i])
}

fun uptime(sec: Long): String = when {
    sec <= 0 -> "–"
    sec < 3600 -> "Up ${sec / 60} min"
    sec < 86400 -> "Up ${sec / 3600} h"
    else -> "Up ${sec / 86400} days"
}

fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
    if (ts == 0L) return "never"
    val s = (now - ts) / 1000
    return when { s < 10 -> "just now"; s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60} min ago"; else -> "${s / 3600} h ago" }
}
