package dev.pocketlab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketlab.proxmox.GuestType
import dev.pocketlab.proxmox.PveGuest
import dev.pocketlab.proxmox.RrdPoint
import dev.pocketlab.proxmox.Snapshot
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.LocalSnackbar
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.components.ConfirmSheet
import dev.pocketlab.ui.components.Divider
import dev.pocketlab.ui.components.ErrorCard
import dev.pocketlab.ui.components.KeyValueRow
import dev.pocketlab.ui.components.LabCard
import dev.pocketlab.ui.components.NavRow
import dev.pocketlab.ui.components.SectionTitle
import dev.pocketlab.ui.components.Sheet
import dev.pocketlab.ui.components.Sparkline
import dev.pocketlab.ui.components.StatusLabel
import dev.pocketlab.ui.components.bytes
import dev.pocketlab.ui.components.pct
import dev.pocketlab.ui.components.uptime
import dev.pocketlab.ui.theme.LocalTones
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class PowerAction(val api: String, val label: String, val explain: String, val destructive: Boolean)

private fun actionsFor(g: PveGuest): List<PowerAction> {
    val vm = g.type == GuestType.QEMU
    val what = if (vm) "VM" else "container"
    return buildList {
        if (!g.running && g.status != "paused") add(PowerAction("start", "Start", "Boots the $what.", false))
        if (g.status == "paused" || g.status == "suspended") add(PowerAction("resume", "Resume", "Resumes the paused $what.", false))
        if (g.running) {
            add(PowerAction("reboot", "Restart", "Asks the guest OS to reboot cleanly.", true))
            add(PowerAction("shutdown", "Shut down", "Asks the guest OS to shut down cleanly" + (if (vm) " (ACPI or guest agent)." else "."), true))
            add(PowerAction("stop", "Stop", "Stops the $what immediately, like pulling the power cord. Unsaved data in the guest is lost.", true))
            if (vm) {
                add(PowerAction("reset", "Reset", "Hard-resets the VM, like pressing the reset button. Unsaved data in the guest is lost.", true))
                add(PowerAction("suspend", "Pause", "Freezes the VM in memory.", false))
            }
        }
    }
}

@Composable
fun GuestScreen(clusterId: String, node: String, type: String, vmid: Int) {
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val t = LocalTones.current
    val scope = rememberCoroutineScope()
    val snap = labSnapshot()
    val cluster = labConfig().clusters.firstOrNull { it.id == clusterId }
    val g = snap.clusters[clusterId]?.guests?.firstOrNull { it.vmid == vmid && it.type.api == type }
    var config by remember { mutableStateOf<Map<String, String>?>(null) }
    var rrd by remember { mutableStateOf<List<RrdPoint>>(emptyList()) }
    var snaps by remember { mutableStateOf<List<Snapshot>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var powerSheet by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PowerAction?>(null) }
    var connectSheet by remember { mutableStateOf(false) }
    var snapSheet by remember { mutableStateOf(false) }
    var rollbackTo by remember { mutableStateOf<Snapshot?>(null) }

    suspend fun reload(guest: PveGuest) {
        val c = lab.client(clusterId) ?: return
        try {
            config = c.config(guest)
            rrd = runCatching { c.rrd(guest) }.getOrDefault(emptyList())
            snaps = runCatching { c.snapshots(guest) }.getOrNull()
            loadError = null
        } catch (e: Exception) { loadError = e.message }
    }

    LaunchedEffect(g?.key) { if (g != null && config == null) reload(g) }

    fun runTask(label: String, block: suspend () -> String) {
        scope.launch {
            busy = label
            val msg = try {
                val r = block()
                if (r == "OK") "$label: done" else "$label: $r"
            } catch (e: Exception) { "$label failed: ${e.message}" }
            busy = null
            lab.refreshCluster(clusterId)
            g?.let { reload(it) }
            snackbar.showSnackbar(msg)
        }
    }

    Box(Modifier.fillMaxSize()) {
        RefreshList(onRefresh = { lab.refreshCluster(clusterId); g?.let { reload(it) } }) {
            item {
                dev.pocketlab.ui.components.TopBar(g?.name ?: "#$vmid", onBack = { nav.popBackStack() })
                if (g == null) { Text("Loading…", color = t.textDim); return@item }
                Text("${g.type.label} ${g.vmid} · ${g.node} · Proxmox · ${cluster?.name}", color = t.textDim, style = MaterialTheme.typography.bodyMedium)
                if (g.tags.isNotEmpty()) Text("Tags · " + g.tags.joinToString(" · "), color = t.textDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusLabel(g.health(), g.statusText())
                    if (busy != null) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp); Text("  $busy…", color = t.textDim) }
                }
                if (g.running) Text(uptime(g.uptime), color = t.textDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                LabCard { NavRow("Power and actions", actionsFor(g).joinToString(", ") { it.label }, onClick = { powerSheet = true }) }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("CPU", if (g.running) pct(g.cpu) else "–", rrd.mapNotNull { it.cpu }, 1.0, Modifier.weight(1f))
                    val memSeries = rrd.mapNotNull { p -> p.mem?.let { m -> p.maxMem?.takeIf { it > 0 }?.let { m / it } } }
                    MetricCard("Memory", if (g.running) pct(g.mem.toDouble() / g.maxMem.coerceAtLeast(1)) else "–", memSeries, 1.0, Modifier.weight(1f))
                }
                if (g.type == GuestType.QEMU && g.running && g.maxMem > 0 && g.mem >= g.maxMem * 0.99)
                    Text("Memory at 100% usually means the QEMU guest agent / ballooning isn't reporting; Proxmox then counts all assigned RAM as used.",
                        color = t.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                loadError?.let { Spacer(Modifier.height(12.dp)); ErrorCard(it) }
            }
            config?.let { cfg ->
                item {
                    SectionTitle("Settings")
                    Text("Read only.", color = t.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    LabCard {
                        val rows = summarizeConfig(cfg, g?.type ?: GuestType.QEMU)
                        rows.forEachIndexed { i, (k, v) ->
                            if (i > 0) Divider()
                            KeyValueRow(k, v)
                        }
                    }
                }
            }
            snaps?.let { list ->
                item {
                    SectionTitle("Snapshots") { TextButton(onClick = { snapSheet = true }) { Icon(Icons.Outlined.PhotoCamera, null); Text("  Take") } }
                    LabCard {
                        if (list.isEmpty()) Text("No snapshots.", Modifier.padding(16.dp), color = t.textDim)
                        list.forEachIndexed { i, s ->
                            if (i > 0) Divider()
                            NavRow(s.name, listOfNotNull(s.time?.let { DateFormat.getDateTimeInstance().format(Date(it * 1000)) }, s.description.takeIf { it.isNotBlank() }).joinToString(" · "),
                                onClick = { rollbackTo = s }, trailing = { Text("Roll back", color = MaterialTheme.colorScheme.primary) })
                        }
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
        if (g != null) ExtendedFloatingActionButton(
            onClick = { connectSheet = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            icon = { Icon(Icons.Outlined.Terminal, null) }, text = { Text("Connect") },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }

    if (g == null) return

    if (powerSheet) Sheet({ powerSheet = false }) {
        Text("Power and actions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LabCard {
            actionsFor(g).forEachIndexed { i, a ->
                if (i > 0) Divider()
                NavRow(a.label, a.explain, onClick = {
                    powerSheet = false
                    if (a.destructive) confirm = a else runTask(a.label) { lab.client(clusterId)!!.power(g, a.api) }
                })
            }
        }
    }

    confirm?.let { a ->
        ConfirmSheet(
            title = "${a.label} ${g.name}?", target = g.name,
            targetSub = "${g.type.label} ${g.vmid} · ${g.node} · Proxmox",
            explanation = a.explain, confirmLabel = "${a.label} ${g.name}", destructive = a.api in setOf("stop", "reset"),
            onConfirm = { runTask(a.label) { lab.client(clusterId)!!.power(g, a.api) } }, onDismiss = { confirm = null },
        )
    }

    rollbackTo?.let { s ->
        ConfirmSheet(
            title = "Roll back ${g.name}?", target = "${g.name} → ${s.name}", targetSub = "${g.type.label} ${g.vmid} · ${g.node}",
            explanation = "Everything changed since snapshot \"${s.name}\" is discarded. This can't be undone.",
            confirmLabel = "Roll back to ${s.name}", destructive = true,
            onConfirm = { runTask("Rollback") { lab.client(clusterId)!!.rollback(g, s.name) } }, onDismiss = { rollbackTo = null },
        )
    }

    if (snapSheet) Sheet({ snapSheet = false }) {
        var name by remember { mutableStateOf("pocketlab_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm").format(Date())) }
        var desc by remember { mutableStateOf("") }
        var ram by remember { mutableStateOf(false) }
        Text("Take snapshot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name, { v -> name = v.filter { it.isLetterOrDigit() || it == '_' || it == '-' } }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Description") })
        if (g.type == GuestType.QEMU && g.running) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ram, { ram = it }); Text("Include RAM (slower, resumes exactly where it was)")
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { snapSheet = false; runTask("Snapshot") { lab.client(clusterId)!!.createSnapshot(g, name, desc, ram) } },
            enabled = name.isNotBlank() && name.first().isLetter(), modifier = Modifier.fillMaxWidth()) { Text("Take snapshot") }
    }

    if (connectSheet) Sheet({ connectSheet = false }) {
        Text("Connect to ${g.name}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        val guestHost = hostForGuest(g.name)
        val nodeHost = hostForNode(g.node)
        val consoleCmd = if (g.type == GuestType.QEMU) "qm terminal ${g.vmid}" else "pct enter ${g.vmid}"
        LabCard {
            NavRow("SSH into the ${g.type.label}", guestHost?.let { "${it.username}@${it.address}" } ?: "No SSH host named ${g.name}. Tap to add one.",
                onClick = {
                    connectSheet = false
                    if (guestHost != null) openTerminal(nav, t, g.name, guestHost) else nav.navigate(Routes.editHost())
                }, trailing = { if (guestHost != null) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) })
            Divider()
            NavRow(if (g.type == GuestType.QEMU) "Serial console over SSH" else "Container shell over SSH",
                if (nodeHost != null) "$consoleCmd on ${nodeHost.name}" + (if (g.type == GuestType.QEMU) " · needs serial0 in the VM; exit with Ctrl+O" else "")
                else "$consoleCmd · no SSH host set up for ${g.node}",
                onClick = {
                    connectSheet = false
                    if (nodeHost != null) openTerminal(nav, t, "${g.name} console", nodeHost, sudoIfNeeded(nodeHost, consoleCmd))
                    else nav.navigate(Routes.editHost())
                })
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, series: List<Double>, max: Double?, modifier: Modifier) {
    val t = LocalTones.current
    LabCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = t.textDim, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Sparkline(series, Modifier.fillMaxWidth().height(36.dp), max = max)
            Text("Last hour", color = t.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun summarizeConfig(c: Map<String, String>, type: GuestType): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val cores = c["cores"]?.toIntOrNull() ?: 1
    val sockets = c["sockets"]?.toIntOrNull() ?: 1
    out += "CPU" to (if (sockets > 1) "$sockets × $cores cores" else "$cores cores") + (c["cpu"]?.let { " · ${it.substringBefore(',')}" } ?: "")
    c["memory"]?.toLongOrNull()?.let { mem ->
        val balloon = c["balloon"]
        val extra = when {
            type == GuestType.LXC -> c["swap"]?.let { " · swap ${bytes(it.toLong() * 1024 * 1024)}" } ?: ""
            balloon == "0" -> " · balloon off"
            balloon != null -> " · balloon min ${bytes(balloon.toLong() * 1024 * 1024)}"
            else -> ""
        }
        out += "Memory" to bytes(mem * 1024 * 1024) + extra
    }
    c["ostype"]?.let { out += "OS type" to it }
    val diskKey = Regex("^(scsi|virtio|sata|ide|efidisk|tpmstate|rootfs|mp)\\d*$")
    val netKey = Regex("^net\\d+$")
    c.keys.filter { diskKey.matches(it) }.sorted().forEach { k ->
        val v = c.getValue(k)
        if (v.contains("media=cdrom")) out += k to "CD-ROM · ${v.substringBefore(',')}"
        else {
            val storage = v.substringBefore(':')
            val size = Regex("size=([^,]+)").find(v)?.groupValues?.get(1)
            out += k to listOfNotNull(storage, size).joinToString(" · ")
        }
    }
    c.keys.filter { netKey.matches(it) }.sorted().forEach { k ->
        val v = c.getValue(k)
        val parts = v.split(',').associate { p -> p.substringBefore('=') to p.substringAfter('=', "") }
        val bridge = parts["bridge"] ?: "?"
        val vlan = parts["tag"]?.let { "VLAN $it" } ?: "no VLAN"
        val fw = if (parts["firewall"] == "1") "firewall on" else "firewall off"
        val ip = parts["ip"]?.let { " · $it" } ?: ""
        out += k to "$bridge · $vlan · $fw$ip"
    }
    listOf("onboot" to "Start at boot", "serial0" to "serial0", "agent" to "Guest agent", "hostname" to "Hostname",
        "unprivileged" to "Unprivileged", "features" to "Features", "boot" to "Boot order", "description" to "Notes")
        .forEach { (k, label) -> c[k]?.let { out += label to if (it == "1") "yes" else if (it == "0") "no" else it.trim() } }
    return out
}
