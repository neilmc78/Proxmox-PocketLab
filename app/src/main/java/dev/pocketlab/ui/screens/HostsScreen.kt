package dev.pocketlab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.components.Divider
import dev.pocketlab.ui.components.Health
import dev.pocketlab.ui.components.LabCard
import dev.pocketlab.ui.components.NavRow
import dev.pocketlab.ui.components.SectionTitle
import dev.pocketlab.ui.components.StatusLabel
import dev.pocketlab.ui.theme.LocalTones

@Composable
fun HostsScreen() {
    val nav = LocalNav.current
    val t = LocalTones.current
    val cfg = labConfig()
    val snap = labSnapshot()
    RefreshList {
        item {
            Text("Hosts", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { nav.navigate(Routes.editCluster()) }, Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null); Text(" Proxmox") }
                OutlinedButton(onClick = { nav.navigate(Routes.editHost()) }, Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null); Text(" SSH host") }
            }
        }
        cfg.clusters.forEach { cl ->
            item(key = cl.id) {
                val st = snap.clusters[cl.id]
                SectionTitle(cl.name)
                LabCard {
                    NavRow("Cluster settings", "${cl.tokenId} · ${cl.baseUrl}", icon = Icons.Outlined.Hub,
                        onClick = { nav.navigate(Routes.editCluster(cl.id)) },
                        trailing = { StatusLabel(if (st?.error == null) Health.OK else Health.BAD, if (st?.error == null) "OK" else "Error") })
                    st?.nodes?.forEach { n ->
                        Divider()
                        HostRow(n.name, if (n.online) Health.OK else Health.BAD, if (n.online) "Online" else "Offline",
                            n.cpu.toFloat(), n.mem.toFloat() / n.maxMem.coerceAtLeast(1)) { nav.navigate(Routes.node(cl.id, n.name)) }
                    }
                }
            }
        }
        item {
            SectionTitle("SSH hosts")
            LabCard {
                if (cfg.hosts.isEmpty()) Text("None yet. SSH hosts give you terminals, Docker containers and node consoles.", Modifier.padding(16.dp), color = t.textDim)
                cfg.hosts.forEachIndexed { i, h ->
                    if (i > 0) Divider()
                    val st = snap.hosts[h.id]
                    HostRow(h.name, when { st == null || st.promptOnly -> Health.OFF; st.error != null -> Health.BAD; else -> Health.OK },
                        "${h.username}@${h.address}", st?.stats?.cpu?.toFloat(), st?.stats?.memFraction?.toFloat()) {
                        nav.navigate(Routes.host(h.id))
                    }
                }
            }
        }
    }
}
