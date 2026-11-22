package dev.pocketlab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketlab.proxmox.RrdPoint
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.components.Divider
import dev.pocketlab.ui.components.ErrorCard
import dev.pocketlab.ui.components.Health
import dev.pocketlab.ui.components.KeyValueRow
import dev.pocketlab.ui.components.LabCard
import dev.pocketlab.ui.components.SectionTitle
import dev.pocketlab.ui.components.StatusLabel
import dev.pocketlab.ui.components.TimeChart
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import dev.pocketlab.ui.components.TopBar
import dev.pocketlab.ui.components.TrustDialog
import dev.pocketlab.ui.components.bytes
import dev.pocketlab.ui.components.pct
import dev.pocketlab.ui.components.uptime
import dev.pocketlab.ui.theme.LocalTones

/**
 * Proxmox RRD history for a screen, refetched every minute (the "hour" series has one point per
 * minute) while the app is in the foreground, so the graph moves with time.
 */
@Composable
internal fun rememberLiveRrd(vararg keys: Any?, fetch: suspend () -> List<RrdPoint>?): List<RrdPoint> {
    var rrd by remember(*keys) { mutableStateOf<List<RrdPoint>>(emptyList()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live = dev.pocketlab.ui.LocalLivePolling.current
    LaunchedEffect(*keys) {
        if (!live) { runCatching { fetch() }.getOrNull()?.let { rrd = it }; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { fetch() }.getOrNull()?.let { rrd = it }
                delay(60_000)
            }
        }
    }
    return rrd
}

/**
 * Times and values from [rrd] via [pick], plus the live value from the 15 s poll as the newest point
 * when it's newer than the last RRD sample, so the right-hand end tracks what's happening now.
 */
internal fun liveSeries(rrd: List<RrdPoint>, now: Long, live: Double?, pick: (RrdPoint) -> Double?): Pair<List<Long>, List<Double>> {
    val pts = rrd.mapNotNull { p -> pick(p)?.let { p.time to it } }.toMutableList()
    if (live != null && (pts.lastOrNull()?.first ?: 0) < now - 15) pts += now to live
    return pts.map { it.first } to pts.map { it.second }
}

@Composable
fun NodeScreen(clusterId: String, nodeName: String) {
    val nav = LocalNav.current
    val t = LocalTones.current
    val st = labSnapshot().clusters[clusterId]
    val node = st?.nodes?.firstOrNull { it.name == nodeName }
    val guests = st?.guests?.filter { it.node == nodeName }.orEmpty()
    val rrd = rememberLiveRrd(clusterId, nodeName) { lab.client(clusterId)?.nodeRrd(nodeName) }
    val sshHost = hostForNode(clusterId, nodeName)

    RefreshList(onRefresh = { lab.refreshCluster(clusterId) }) {
        item {
            TopBar(nodeName, onBack = { nav.popBackStack() })
            if (node == null) { Text("Loading…", color = t.textDim); return@item }
            Text("Proxmox node · ${node.maxCpu} CPUs · ${bytes(node.maxMem)} RAM", color = t.textDim)
            StatusLabel(if (node.online) Health.OK else Health.BAD, if (node.online) "Online · ${uptime(node.uptime)}" else "Offline", Modifier.padding(vertical = 6.dp))
            Spacer(Modifier.height(8.dp))
            LabCard {
                Column(Modifier.padding(16.dp)) {
                    val now = System.currentTimeMillis() / 1000
                    val memFrac = node.mem.toDouble() / node.maxMem.coerceAtLeast(1)
                    val cpu = liveSeries(rrd, now, node.cpu) { it.cpu }
                    val mem = liveSeries(rrd, now, memFrac) { p -> p.mem?.let { m -> p.maxMem?.takeIf { it > 0 }?.let { m / it } } }
                    Text("CPU ${pct(node.cpu)}", fontWeight = FontWeight.Bold)
                    TimeChart(cpu.first, cpu.second, { pct(cpu.second[it]) }, "Last hour", Modifier.fillMaxWidth(), height = 56.dp, max = 1.0)
                    Spacer(Modifier.height(10.dp))
                    Text("Memory ${pct(memFrac)} · ${bytes(node.mem)} of ${bytes(node.maxMem)}", fontWeight = FontWeight.Bold)
                    TimeChart(mem.first, mem.second, { "${pct(mem.second[it])} · ${bytes((mem.second[it] * node.maxMem).toLong())}" },
                        "Last hour", Modifier.fillMaxWidth(), height = 56.dp, max = 1.0)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (sshHost != null) Button(onClick = { openTerminal(nav, t, nodeName, sshHost) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Terminal, null); Text("  Node shell (${sshHost.username}@${sshHost.address})")
            } else OutlinedButton(onClick = { nav.navigate(Routes.editHost()) }, Modifier.fillMaxWidth()) {
                Text("Add an SSH host for this node to get a shell")
            }
        }
        item {
            SectionTitle("Guests")
            LabCard {
                if (guests.isEmpty()) Text("No VMs or containers.", Modifier.padding(16.dp), color = t.textDim)
                guests.forEachIndexed { i, g ->
                    if (i > 0) Divider()
                    WorkloadRow(g.name, "${g.type.label} ${g.vmid}" + g.tags.joinToString("") { " · $it" },
                        if (g.running) "CPU ${pct(g.cpu)}   Mem ${pct(g.mem.toDouble() / g.maxMem.coerceAtLeast(1))}" else null,
                        g.health(), g.statusText()) { nav.navigate(Routes.guest(clusterId, nodeName, g.type.api, g.vmid)) }
                }
            }
        }
    }
}

@Composable
fun HostScreen(hostId: String) {
    val nav = LocalNav.current
    val t = LocalTones.current
    val host = labConfig().hosts.firstOrNull { it.id == hostId }
    val st = labSnapshot().hosts[hostId]
    LaunchedEffect(hostId) { if (st == null) lab.refreshHost(hostId) }
    if (host == null) { TopBar("Host", onBack = { nav.popBackStack() }); return }

    RefreshList(onRefresh = { lab.refreshHost(hostId) }) {
        item {
            TopBar(host.name, onBack = { nav.popBackStack() }) {
                IconButton(onClick = { nav.navigate(Routes.editHost(hostId)) }) { Icon(Icons.Outlined.Edit, "Edit") }
            }
            Text("${host.username}@${host.address}:${host.port} · SSH", color = t.textDim)
            when {
                st == null || !st.loaded -> StatusLabel(Health.OFF, "Connecting…", Modifier.padding(vertical = 6.dp))
                st.error != null -> StatusLabel(Health.BAD, "Unreachable", Modifier.padding(vertical = 6.dp))
                else -> StatusLabel(Health.OK, "Connected" + (st.stats?.uptimeSec?.let { " · ${uptime(it)}" } ?: ""), Modifier.padding(vertical = 6.dp))
            }
            st?.hostKey?.let { hk ->
                var show by remember { mutableStateOf(false) }
                ErrorCard(if (hk.mismatch) "Host key changed!" else "Host key not trusted yet", "Review" to { show = true })
                if (show) TrustDialog(if (hk.mismatch) "Host key changed" else "Trust host key?", "${host.address} presents:", hk.fingerprint, hk.mismatch,
                    onTrust = {
                        lab.store.update { c -> c.copy(hosts = c.hosts.map { if (it.id == hostId) it.copy(hostKeyFingerprint = hk.fingerprint) else it }) }
                        lab.forgetConnections()
                    }, onDismiss = { show = false })
            }
            if (st?.error != null && st.hostKey == null) ErrorCard(st.error)
            st?.stats?.let { s ->
                Spacer(Modifier.height(8.dp))
                LabCard {
                    KeyValueRow("CPU", (s.cpu?.let(::pct) ?: "–") + (s.load?.let { "   (load $it)" } ?: ""))
                    Divider()
                    KeyValueRow("Memory", if (s.memUsed != null && s.memTotal != null)
                        "${bytes(s.memUsed)} of ${bytes(s.memTotal)} · ${pct(s.memFraction ?: 0.0)}" else "–")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { openTerminal(nav, t, host.name, host) }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Terminal, null); Text("  Open terminal") }
        }
        if (host.dockerEnabled) item {
            SectionTitle("Containers")
            st?.dockerError?.let { ErrorCard(it) }
            val cs = st?.containers.orEmpty()
            LabCard {
                if (cs.isEmpty()) Text(if (st?.dockerError != null) "Docker unavailable." else "No containers.", Modifier.padding(16.dp), color = t.textDim)
                cs.forEachIndexed { i, c ->
                    if (i > 0) Divider()
                    WorkloadRow(c.name, listOfNotNull("Container ${c.shortId}", c.composeProject?.let { "$it / ${c.composeService}" }).joinToString(" · "),
                        c.image, c.healthState(), c.statusText()) { nav.navigate(Routes.container(hostId, c.id)) }
                }
            }
        }
    }
}

