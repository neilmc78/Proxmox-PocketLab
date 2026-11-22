package dev.pocketlab.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.components.Divider
import dev.pocketlab.ui.components.Health
import dev.pocketlab.ui.components.LabCard
import dev.pocketlab.ui.components.Metric
import dev.pocketlab.ui.components.NavRow
import dev.pocketlab.ui.components.SectionTitle
import dev.pocketlab.ui.components.Sheet
import dev.pocketlab.ui.components.StatusLabel
import dev.pocketlab.ui.components.ago
import dev.pocketlab.ui.theme.LocalTones

private data class Attention(val title: String, val detail: String, val health: Health, val route: String)

@Composable
fun OverviewScreen() {
    val nav = LocalNav.current
    val snap = labSnapshot()
    val cfg = labConfig()
    val t = LocalTones.current
    var addSheet by remember { mutableStateOf(false) }

    val allNodes = snap.clusters.values.flatMap { it.nodes }
    val guests = snap.clusters.values.flatMap { it.guests }.filter { !it.template }
    val containers = snap.hosts.values.flatMap { it.containers }
    val reporting = allNodes.count { it.online } + snap.hosts.values.count { it.stats != null }
    val totalHosts = allNodes.size + cfg.hosts.size
    val running = guests.count { it.running } + containers.count { it.running }

    val attention = buildList {
        cfg.clusters.forEach { cl ->
            val st = snap.clusters[cl.id]
            if (st?.error != null) add(Attention(cl.name, st.error, Health.BAD, Routes.editCluster(cl.id)))
            st?.nodes?.filter { !it.online }?.forEach { add(Attention(it.name, "Node offline", Health.BAD, Routes.node(cl.id, it.name))) }
            st?.nodes?.filter { it.online && it.maxMem > 0 && it.mem.toDouble() / it.maxMem > 0.92 }
                ?.forEach { add(Attention(it.name, "Memory above 92%", Health.WARN, Routes.node(cl.id, it.name))) }
        }
        cfg.allHosts().forEach { h ->
            val st = snap.hosts[h.id]
            // LXC Docker hosts have no host page of their own: their problems open Workloads → Docker.
            val page = if (h.pctVmid != null) Routes.workloads("Docker") else Routes.host(h.id)
            if (st?.error != null) add(Attention(h.name, st.error, Health.BAD, page))
            else if (st?.dockerError != null) add(Attention(h.name, "Docker: ${st.dockerError}", Health.WARN, page))
            st?.containers?.filter { it.healthState() == Health.BAD }?.forEach {
                add(Attention(it.name, it.status, Health.BAD, Routes.container(h.id, it.id)))
            }
        }
    }

    RefreshList {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("Pocketlab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { addSheet = true }) { Icon(Icons.Outlined.Add, "Add") }
            }
            Text("Overview", fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text(
                if (totalHosts == 0) "Nothing connected yet" else "$reporting of $totalHosts hosts reporting · Updated ${ago(snap.updatedAt)}",
                color = t.textDim, style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MonitorHeart, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("$running", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("  workloads running", color = t.textDim)
            }
        }

        lab.store.problem.value?.let { msg -> item { Spacer(Modifier.height(16.dp)); dev.pocketlab.ui.components.ErrorCard(msg) } }

        if (cfg.clusters.isEmpty() && cfg.hosts.isEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                LabCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Connect your lab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Add a Proxmox cluster with an API token, and SSH hosts for terminals and Docker. Everything is stored encrypted on this phone and talks to your servers directly; there is no cloud service.",
                            color = t.textDim)
                        TextButton(onClick = { nav.navigate("guide") }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.HelpOutline, null); Text("  How to set up, step by step")
                        }
                        Button(onClick = { nav.navigate(Routes.editCluster()) }, Modifier.fillMaxWidth()) { Text("Add Proxmox cluster") }
                        OutlinedButton(onClick = { nav.navigate(Routes.editHost()) }, Modifier.fillMaxWidth()) { Text("Add SSH host") }
                    }
                }
            }
            return@RefreshList
        }

        item {
            SectionTitle("Needs attention") {
                TextButton(onClick = { nav.navigate("guide") }) { Text("How to set up"); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
            }
            // Proxmox nodes with no SSH host yet: the next setup step, until it's done.
            val noSsh = cfg.clusters.flatMap { cl -> snap.clusters[cl.id]?.nodes.orEmpty().filter { cfg.nodeHost(cl.id, it.name) == null } }
            if (noSsh.isNotEmpty()) {
                LabCard(Modifier.padding(bottom = 10.dp)) {
                    noSsh.forEachIndexed { i, n ->
                        if (i > 0) Divider()
                        NavRow("Now set up SSH for ${n.name}", "Node shell, VM/LXC consoles and Docker in LXCs",
                            icon = Icons.Outlined.Terminal, onClick = { nav.navigate(Routes.editHost()) })
                    }
                }
            }
            if (attention.isEmpty()) Text("Nothing needs attention right now.", color = t.textDim)
            else LabCard {
                attention.forEachIndexed { i, a ->
                    if (i > 0) Divider()
                    NavRow(a.title, a.detail, onClick = { nav.navigate(a.route) },
                        trailing = { StatusLabel(a.health, "") })
                }
            }
        }

        item {
            SectionTitle("Hosts") { ViewAll { nav.navigate("hosts") } }
            // Same order as the Hosts tab: Proxmox clusters first, then SSH hosts.
            cfg.clusters.forEach { cl ->
                val st = snap.clusters[cl.id]
                Text("${cl.name} · Proxmox", style = MaterialTheme.typography.labelLarge, color = t.textDim, modifier = Modifier.padding(top = if (cl == cfg.clusters.first()) 0.dp else 16.dp, bottom = 8.dp))
                LabCard {
                    if (st?.nodes.isNullOrEmpty()) Text(if (st?.error != null) st.error else "Loading…", Modifier.padding(16.dp), color = t.textDim)
                    st?.nodes?.forEachIndexed { i, n ->
                        if (i > 0) Divider()
                        HostRow(n.name, if (n.online) Health.OK else Health.BAD, if (n.online) "Online" else "Offline",
                            n.cpu.toFloat(), n.mem.toFloat() / n.maxMem.coerceAtLeast(1)) { nav.navigate(Routes.node(cl.id, n.name)) }
                    }
                }
            }
            if (cfg.hosts.isNotEmpty()) {
                Text("SSH hosts", style = MaterialTheme.typography.labelLarge, color = t.textDim, modifier = Modifier.padding(top = if (cfg.clusters.isEmpty()) 0.dp else 16.dp, bottom = 8.dp))
                LabCard {
                    cfg.hosts.forEachIndexed { i, h ->
                        if (i > 0) Divider()
                        val st = snap.hosts[h.id]
                        val health = when { st == null || !st.loaded || st.promptOnly -> Health.OFF; st.error != null -> Health.BAD; else -> Health.OK }
                        HostRow(h.name, health, when { st == null || !st.loaded -> "Connecting…"; st.promptOnly -> "Password on connect"; st.error != null -> "Unreachable"; else -> "Connected" },
                            st?.stats?.cpu?.toFloat(), st?.stats?.memFraction?.toFloat()) { nav.navigate(Routes.host(h.id)) }
                    }
                }
            }
        }

        if (containers.isNotEmpty()) item {
            SectionTitle("Services") { ViewAll { nav.navigate(Routes.workloads("Docker")) } }
            LabCard {
                val shown = containers.sortedBy { it.healthState().ordinal }.take(6)
                shown.forEachIndexed { i, c ->
                    if (i > 0) Divider()
                    NavRow(c.name, c.composeProject?.let { "$it / ${c.composeService}" } ?: c.image,
                        onClick = { nav.navigate(Routes.container(c.hostId, c.id)) },
                        trailing = { StatusLabel(c.healthState(), c.statusText()) })
                }
            }
        }
    }

    if (addSheet) Sheet({ addSheet = false }) {
        Text("Add", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LabCard {
            NavRow("Proxmox cluster", "API token, VMs and containers", onClick = { addSheet = false; nav.navigate(Routes.editCluster()) })
            Divider()
            NavRow("SSH host", "Terminal, Docker, node consoles", onClick = { addSheet = false; nav.navigate(Routes.editHost()) })
        }
    }
}

@Composable
private fun ViewAll(onClick: () -> Unit) = TextButton(onClick = onClick) {
    Text("View all"); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
}

@Composable
fun HostRow(name: String, health: Health, status: String, cpu: Float?, mem: Float?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            StatusLabel(health, status)
        }
        Metric("CPU", cpu, Modifier.width(72.dp))
        Spacer(Modifier.width(12.dp))
        Metric("Mem", mem, Modifier.width(72.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LocalTones.current.textDim)
    }
}
