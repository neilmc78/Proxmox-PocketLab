package dev.pocketlab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.pocketlab.data.SshAuthType
import dev.pocketlab.data.SshHost
import dev.pocketlab.ui.theme.LocalTones
import dev.pocketlab.ssh.TerminalSessions
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.theme.Tones

fun openTerminal(nav: NavHostController, tones: Tones, title: String, host: SshHost, command: String? = null) {
    // "Ask each time" hosts: the password is asked for first, before the console opens, and kept only
    // in this session's memory (so Reconnect works) — never stored.
    if (host.authType == SshAuthType.PROMPT && host.password == null) {
        PasswordPrompt.ask(host) { pw -> openTerminal(nav, tones, title, host.copy(password = pw), command) }
        return
    }
    val s = TerminalSessions.open(title, host, lab.config.keys, command, tones.text, tones.bg)
    nav.navigate(Routes.term(s.id))
}

/** One pending "password for user@host" request, shown as a dialog by the app root. */
object PasswordPrompt {
    data class Request(val host: SshHost, val onPassword: (String) -> Unit)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Request?>(null)
    fun ask(host: SshHost, onPassword: (String) -> Unit) { pending.value = Request(host, onPassword) }
}

/** Asks for an "ask each time" host's password before its console opens. Lives at the app root. */
@Composable
fun PasswordPromptDialog() {
    val req by PasswordPrompt.pending.collectAsState()
    val r = req ?: return
    var pw by remember(r) { mutableStateOf("") }
    fun done(ok: Boolean) { PasswordPrompt.pending.value = null; if (ok) r.onPassword(pw) }
    AlertDialog(onDismissRequest = { done(false) },
        title = { Text("Log in to ${r.host.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${r.host.username}@${r.host.address}. The password is used for this session only and isn't saved.",
                    style = MaterialTheme.typography.bodySmall, color = LocalTones.current.textDim)
                SecretField(pw, { pw = it }, "Password")
            }
        },
        confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { done(true) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { done(false) }) { Text("Cancel") } })
}

/** SSH host that *is* a given Proxmox node; see [dev.pocketlab.data.AppConfig.nodeHost]. */
fun hostForNode(clusterId: String, node: String): SshHost? = lab.config.nodeHost(clusterId, node)

/** SSH host for a guest: an SSH host whose name is exactly the VM/LXC name. */
fun hostForGuest(name: String): SshHost? = lab.config.hosts.firstOrNull { it.name.equals(name, true) }

fun sudoIfNeeded(host: SshHost, cmd: String) = if (host.username == "root") cmd else "sudo $cmd"

@Suppress("unused") @Composable private fun unused() {}
