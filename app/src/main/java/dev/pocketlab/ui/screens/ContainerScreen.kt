package dev.pocketlab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketlab.docker.Container
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.LocalSnackbar
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.components.ConfirmSheet
import dev.pocketlab.ui.components.Divider
import dev.pocketlab.ui.components.KeyValueRow
import dev.pocketlab.ui.components.LabCard
import dev.pocketlab.ui.components.NavRow
import dev.pocketlab.ui.components.Sheet
import dev.pocketlab.ui.components.StatusLabel
import dev.pocketlab.ui.components.TopBar
import dev.pocketlab.ui.theme.LocalTones
import kotlinx.coroutines.launch

private data class CAction(val cmd: String, val label: String, val explain: String, val confirm: Boolean)

private fun actionsFor(c: Container) = buildList {
    when (c.state) {
        "running" -> {
            add(CAction("restart", "Restart", "Docker stops the container and starts it again. Anything connected to it disconnects briefly.", true))
            add(CAction("stop", "Stop", "Docker asks the container to stop and forces it after its stop timeout. Anything connected to it disconnects.", true))
            add(CAction("pause", "Pause", "Freezes every process in the container.", false))
        }
        "paused" -> add(CAction("unpause", "Unpause", "Resumes the container's processes.", false))
        else -> add(CAction("start", "Start", "Starts the container.", false))
    }
}

@Composable
fun ContainerScreen(hostId: String, id: String) {
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val t = LocalTones.current
    val scope = rememberCoroutineScope()
    val host = labConfig().hosts.firstOrNull { it.id == hostId }
    val c = labSnapshot().hosts[hostId]?.containers?.firstOrNull { it.id == id }
    var sheet by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<CAction?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(a: CAction) = scope.launch {
        busy = true
        val msg = try { lab.docker(hostId)!!.action(id, a.cmd); "${a.label}: done" } catch (e: Exception) { "${a.label} failed: ${e.message}" }
        lab.refreshHost(hostId)
        busy = false
        snackbar.showSnackbar(msg)
    }

    RefreshList(onRefresh = { lab.refreshHost(hostId) }) {
        item {
            TopBar(c?.name ?: "Container", onBack = { nav.popBackStack() })
            if (c == null || host == null) { Text("Not found (yet). Pull to refresh.", color = t.textDim); return@item }
            Text("Container ${c.shortId} · ${host.name} · Docker", color = t.textDim)
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusLabel(c.healthState(), c.statusText())
                if (busy) { Spacer(Modifier.width(10.dp)); CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp) }
            }
            Spacer(Modifier.height(8.dp))
            LabCard { NavRow("Power and actions", actionsFor(c).joinToString(" or ") { it.label.lowercase() }.replaceFirstChar { it.uppercase() }, onClick = { sheet = true }) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { nav.navigate(Routes.logs(hostId, id)) }, Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Outlined.Notes, null); Text("  Logs") }
                OutlinedButton(onClick = { openTerminal(nav, t, "${c.name} · ${host.name}", host, lab.docker(hostId)!!.execCommand(id)) },
                    Modifier.weight(1f), enabled = c.running) { Icon(Icons.Outlined.Terminal, null); Text("  Shell") }
            }
            Spacer(Modifier.height(12.dp))
            LabCard {
                KeyValueRow("Image", c.image, mono = true)
                c.composeProject?.let { Divider(); KeyValueRow("Compose", "$it / ${c.composeService}") }
                if (c.ports.isNotBlank()) { Divider(); KeyValueRow("Ports", c.ports.split(", ").joinToString("\n") { it.replace("->", " → ") }, mono = true) }
                Divider()
                KeyValueRow("Status", c.status)
                Divider()
                KeyValueRow("ID", c.id, mono = true)
            }
        }
    }

    if (c == null) return
    if (sheet) Sheet({ sheet = false }) {
        Text("Power and actions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LabCard {
            actionsFor(c).forEachIndexed { i, a ->
                if (i > 0) Divider()
                NavRow(a.label, a.explain, onClick = { sheet = false; if (a.confirm) confirm = a else run(a) })
            }
        }
    }
    confirm?.let { a ->
        ConfirmSheet("${a.label} ${c.name}?", c.name, "Container ${c.shortId} · ${host?.name} · Docker", a.explain,
            "${a.label} ${c.name}", destructive = a.cmd == "stop", onConfirm = { run(a) }, onDismiss = { confirm = null })
    }
}
