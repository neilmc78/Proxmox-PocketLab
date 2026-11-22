package dev.pocketlab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketlab.proxmox.GuestType
import dev.pocketlab.proxmox.PveGuest
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.components.Divider
import dev.pocketlab.ui.components.ErrorCard
import dev.pocketlab.ui.components.Health
import dev.pocketlab.ui.components.LabCard
import dev.pocketlab.ui.components.StatusLabel
import dev.pocketlab.ui.components.pct
import dev.pocketlab.ui.theme.LocalTones

@Composable
fun WorkloadsScreen(initialFilter: String) {
    val nav = LocalNav.current
    val snap = labSnapshot()
    val cfg = labConfig()
    val t = LocalTones.current
    var query by rememberSaveable { mutableStateOfString("") }
    var filter by rememberSaveable { mutableStateOfString(initialFilter) }
    val q = query.trim().lowercase()

    fun guestMatches(g: PveGuest) = (filter == "All" || (filter == "VM" && g.type == GuestType.QEMU) || (filter == "LXC" && g.type == GuestType.LXC)) &&
        (q.isEmpty() || g.name.lowercase().contains(q) || g.vmid.toString().contains(q) || g.node.lowercase().contains(q) || g.tags.any { it.lowercase().contains(q) })

    RefreshList {
        item {
            Text("Workloads", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 16.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search by name, VMID, node or tag") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, "Clear") } },
                shape = RoundedCornerShape(16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "VM", "LXC", "Docker").forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                }
            }
        }

        if (filter != "Docker") cfg.clusters.forEach { cl ->
            val st = snap.clusters[cl.id] ?: return@forEach
            val visible = st.guests.filter(::guestMatches)
            item(key = "cl-${cl.id}") {
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cl.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    CountPill(visible.size)
                }
                Text("Proxmox · ${cl.baseUrl.removePrefix("https://").substringBefore('/')}", color = t.textDim,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
                if (st.error != null) ErrorCard(st.error, "Fix" to { nav.navigate(Routes.editCluster(cl.id)) })
                // Nodes visible but no guests at all almost always means the token lacks VM.Audit on /vms.
                else if (st.loaded && st.nodes.isNotEmpty() && st.guests.isEmpty())
                    ErrorCard("No VMs or containers visible. If this node has some, the API token can't see them (it needs VM.Audit on /vms). " +
                        "Fix in the node Shell: pveum role modify PocketlabPower --append 1 --privs VM.Audit")
            }
            st.nodes.forEach { node ->
                val ng = visible.filter { it.node == node.name }
                if (ng.isEmpty() && q.isNotEmpty()) return@forEach
                item(key = "n-${cl.id}-${node.name}") {
                    LabCard(Modifier.padding(bottom = 12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.node(cl.id, node.name)) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            StatusLabel(if (node.online) Health.OK else Health.BAD, if (node.online) "Online" else "Offline")
                        }
                        ng.forEach { g ->
                            Divider()
                            WorkloadRow(
                                title = g.name,
                                subtitle = buildString {
                                    append("${g.type.label} ${g.vmid}")
                                    if (g.template) append(" · template")
                                    g.tags.forEach { append(" · $it") }
                                },
                                metrics = if (g.running) "CPU ${pct(g.cpu)}   Mem ${pct(g.mem.toDouble() / g.maxMem.coerceAtLeast(1))}" else null,
                                health = g.health(), status = g.statusText(),
                            ) { nav.navigate(Routes.guest(g.clusterId, g.node, g.type.api, g.vmid)) }
                        }
                    }
                }
            }
        }

        if (filter == "All" || filter == "Docker") cfg.hosts.filter { it.dockerEnabled }.forEach { h ->
            val st = snap.hosts[h.id] ?: return@forEach
            val cs = st.containers.filter { c ->
                q.isEmpty() || c.name.lowercase().contains(q) || c.image.lowercase().contains(q) || (c.composeProject?.lowercase()?.contains(q) == true)
            }
            if (cs.isEmpty() && (q.isNotEmpty() || st.dockerError == null && st.error == null)) return@forEach
            item(key = "h-${h.id}") {
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    CountPill(cs.size)
                }
                Text("Docker · over SSH", color = t.textDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
                (st.error ?: st.dockerError)?.let { ErrorCard(it) }
                if (cs.isNotEmpty()) LabCard(Modifier.padding(bottom = 12.dp)) {
                    cs.forEachIndexed { i, c ->
                        if (i > 0) Divider()
                        WorkloadRow(
                            title = c.name,
                            subtitle = listOfNotNull("Container ${c.shortId}", c.composeProject?.let { "$it / ${c.composeService}" }).joinToString(" · "),
                            metrics = c.image, health = c.healthState(), status = c.statusText(),
                        ) { nav.navigate(Routes.container(h.id, c.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountPill(n: Int) {
    Text("$n", Modifier.clip(CircleShape).background(LocalTones.current.surfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
}

@Composable
fun WorkloadRow(title: String, subtitle: String, metrics: String?, health: Health, status: String, onClick: () -> Unit) {
    val t = LocalTones.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLabel(health, status)
            }
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (metrics != null) Text(metrics, style = MaterialTheme.typography.bodySmall, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = t.textDim)
    }
}

private fun mutableStateOfString(v: String) = androidx.compose.runtime.mutableStateOf(v)
