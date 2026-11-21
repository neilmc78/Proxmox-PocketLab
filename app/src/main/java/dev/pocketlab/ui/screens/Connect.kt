package dev.pocketlab.ui.screens

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import dev.pocketlab.data.SshHost
import dev.pocketlab.ssh.TerminalSessions
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.theme.Tones

fun openTerminal(nav: NavHostController, tones: Tones, title: String, host: SshHost, command: String? = null) {
    val s = TerminalSessions.open(title, host, lab.config.keys, command, tones.text, tones.bg)
    nav.navigate(Routes.term(s.id))
}

/**
 * SSH host that *is* a given Proxmox node. An explicit (cluster, node) link wins. Links without a
 * cluster, and plain name matches, only count when there's a single cluster: with several, a node
 * called "pve" could be any of them and a console command must never go to the wrong machine.
 */
fun hostForNode(clusterId: String, node: String): SshHost? {
    val cfg = lab.config
    cfg.hosts.firstOrNull { it.proxmoxClusterId == clusterId && it.proxmoxNode.equals(node, true) }?.let { return it }
    if (cfg.clusters.size > 1) return null
    return cfg.hosts.firstOrNull { it.proxmoxClusterId == null && it.proxmoxNode.equals(node, true) }
        ?: cfg.hosts.firstOrNull { it.proxmoxNode == null && it.name.equals(node, true) }
}

/** SSH host for a guest: an SSH host whose name is exactly the VM/LXC name. */
fun hostForGuest(name: String): SshHost? = lab.config.hosts.firstOrNull { it.name.equals(name, true) }

fun sudoIfNeeded(host: SshHost, cmd: String) = if (host.username == "root") cmd else "sudo $cmd"

@Suppress("unused") @Composable private fun unused() {}
