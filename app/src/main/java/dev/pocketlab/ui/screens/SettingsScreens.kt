package dev.pocketlab.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import dev.pocketlab.ui.Routes
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketlab.data.SshKey
import dev.pocketlab.data.ThemeMode
import dev.pocketlab.ssh.generateEd25519
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.LocalSnackbar
import dev.pocketlab.ui.components.Divider
import dev.pocketlab.ui.components.LabCard
import dev.pocketlab.ui.components.NavRow
import dev.pocketlab.ui.components.SectionTitle
import dev.pocketlab.ui.components.TopBar
import dev.pocketlab.ui.theme.Accents
import dev.pocketlab.ui.theme.LocalTones
import dev.pocketlab.ui.theme.Palettes
import dev.pocketlab.ui.theme.Tones
import dev.pocketlab.ui.theme.isDark
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState

/**
 * Settings → How to set up: the whole setup on one screen. Each step has a button that goes there,
 * and step 2 can copy a ready-made command that installs this phone's public key.
 */
@Composable
fun GuideScreen() {
    val nav = LocalNav.current
    val t = LocalTones.current
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val key = labConfig().keys.lastOrNull { it.publicKey != null }

    @Composable
    fun Step(n: Int, title: String, body: String, actions: @Composable () -> Unit = {}) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("$n", Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(top = 2.dp),
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = t.textDim)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { actions() }
            }
        }
    }
    @Composable
    fun Go(label: String, onClick: () -> Unit) =
        TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp)) { Text(label, style = MaterialTheme.typography.labelLarge) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TopBar("How to set up", onBack = { nav.popBackStack() })
        Step(1, "Add Proxmox",
            "Address: https://<node IP>:8006. Open \"First time? Create the token\", copy the commands into the node's Shell " +
                "(Proxmox web UI → node → Shell), paste the secret it prints, Test and save, then Trust the certificate.") {
            Go("Add cluster") { nav.navigate(Routes.editCluster()) }
        }
        Step(2, "Make an SSH key and install it",
            "Generate a key on this phone, then run the install command in the node's Shell. The private key never leaves the phone.") {
            Go(if (key == null) "Generate key" else "SSH keys") { nav.navigate("keys") }
            if (key != null) Go("Copy install command") {
                val cmd = "mkdir -p ~/.ssh && echo '${key.publicKey}' >> ~/.ssh/authorized_keys"
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("install key", cmd))
                scope.launch { snackbar.showSnackbar("Copied: paste it in the node's Shell") }
            }
        }
        Step(3, "Add the node as an SSH host",
            "Address: the node's IP, user root, your key. The Proxmox node is picked for you. Test and save, check the fingerprint " +
                "against ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub on the node, then Trust. " +
                "You get the node shell and a console for every VM and LXC.") {
            Go("Add SSH host") { nav.navigate(Routes.editHost()) }
        }
        Step(4, "Docker",
            "In an LXC: open it and turn on \"Docker in this container\". It runs through the node's SSH, nothing to install. " +
                "In a VM: add the VM as its own SSH host (install the key in the VM too), named like the VM, with Docker on. One per VM.") {
            Go("Workloads") { nav.navigate(Routes.workloads()) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    val t = LocalTones.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val cfg = labConfig()
    val ctx = LocalContext.current
    val sessions by dev.pocketlab.ssh.TerminalSessions.sessions.collectAsState()
    var confirmClose by remember { mutableStateOf(false) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    RefreshList(onRefresh = {}) {
        item {
            Text("Settings", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 16.dp))
            LabCard {
                NavRow("How to set up", "Proxmox, SSH keys, consoles and Docker, step by step",
                    icon = Icons.AutoMirrored.Outlined.HelpOutline, onClick = { nav.navigate("guide") })
                Divider()
                NavRow("Appearance", "${cfg.appearance.mode.name.lowercase().replaceFirstChar { it.uppercase() }} · ${cfg.appearance.theme} · ${cfg.appearance.accent}",
                    icon = Icons.Outlined.Palette, onClick = { nav.navigate("appearance") })
                Divider()
                NavRow("SSH keys", "${cfg.keys.size} stored", icon = Icons.Outlined.Key, onClick = { nav.navigate("keys") })
                Divider()
                // An action, not a screen: no chevron, and it asks first.
                NavRow("Close all connections",
                    if (sessions.isEmpty()) "Closes background SSH connections" else "Ends ${sessions.size} terminal session(s) and closes SSH connections",
                    icon = Icons.Outlined.LinkOff, onClick = { confirmClose = true }, trailing = {})
            }
            SectionTitle("Security")
            LabCard {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Tokens, passwords and keys are stored in one file encrypted with AES-256-GCM under a hardware-backed Android Keystore key, excluded from backups. " +
                            "The app only talks to the servers you add: no analytics, no cloud. Proxmox certificates and SSH host keys are pinned on first use and any change is refused unless you approve it.",
                        color = t.textDim, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            SectionTitle("About")
            Text("Pocketlab $version · open source, built for you.", color = t.textDim)
            Text("Terminal: ConnectBot termlib (Apache 2.0) · SSH: sshj (Apache 2.0)", color = t.textDim, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false },
        title = { Text("Close all connections?") },
        text = { Text((if (sessions.isEmpty()) "" else "This ends ${sessions.size} open terminal session(s); anything running in them in the foreground stops. ") +
            "Background connections reopen on the next refresh.") },
        confirmButton = { TextButton(onClick = {
            confirmClose = false
            sessions.forEach { dev.pocketlab.ssh.TerminalSessions.close(it.id) }
            lab.forgetConnections()
            scope.launch { snackbar.showSnackbar("All connections closed") }
        }) { Text("Close all", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } })
}

@Composable
fun AppearanceScreen() {
    val nav = LocalNav.current
    val cfg = labConfig()
    val a = cfg.appearance
    val t = LocalTones.current
    val dark = isDark(a)
    fun set(f: (dev.pocketlab.data.Appearance) -> dev.pocketlab.data.Appearance) = lab.store.update { it.copy(appearance = f(it.appearance)) }

    RefreshList(onRefresh = {}) {
        item {
            TopBar("Appearance", onBack = { nav.popBackStack() })
            LabCard {
                Column(Modifier.padding(16.dp)) {
                    Header("Mode", a.mode.name.lowercase().replaceFirstChar { it.uppercase() })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeMode.entries.forEach { m ->
                            val preview = Palettes.first { it.name == a.theme }
                            Choice(m.name.lowercase().replaceFirstChar { it.uppercase() }, a.mode == m, Modifier.weight(1f), onClick = { set { it.copy(mode = m) } }) {
                                when (m) {
                                    // Two half-width previews: half the ratio each, so the card is as tall as Light and Dark.
                                    ThemeMode.SYSTEM -> Row { Preview(preview.light, Modifier.weight(1f), ratio = 0.75f); Preview(preview.dark, Modifier.weight(1f), ratio = 0.75f) }
                                    ThemeMode.LIGHT -> Preview(preview.light, Modifier.fillMaxWidth())
                                    ThemeMode.DARK -> Preview(preview.dark, Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Header("Theme", a.theme)
                    Palettes.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                            row.forEach { p ->
                                Choice(p.name, a.theme == p.name, Modifier.weight(1f), onClick = { set { it.copy(theme = p.name) } }) {
                                    val tones = if (dark) p.dark else p.light
                                    Row { Preview(tones, Modifier.weight(1f), swatch = false); Preview(tones, Modifier.weight(1f), swatch = true) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Header("Accent", a.accent)
                    // Each swatch gets an equal share of the row, capped at 44dp and kept square: fixed 44dp
                    // circles overflowed narrow phones and the last one was squeezed into a pill.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Accents.forEach { (name, color) ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                Box(Modifier.widthIn(max = 44.dp).fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(color)
                                    .border(if (a.accent == name) 3.dp else 0.dp, t.text, CircleShape)
                                    .clickable { set { it.copy(accent = name) } }, contentAlignment = Alignment.Center) {
                                    if (a.accent == name) Icon(Icons.Outlined.Check, name, tint = Color.White)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Header("Terminal text", "${a.terminalFontSp} sp")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { set { it.copy(terminalFontSp = (it.terminalFontSp - 1).coerceAtLeast(7)) } }) { Text("Smaller") }
                        OutlinedButton(onClick = { set { it.copy(terminalFontSp = (it.terminalFontSp + 1).coerceAtMost(28)) } }) { Text("Larger") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit, preview: @Composable () -> Unit) {
    val t = LocalTones.current
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else t.outline, RoundedCornerShape(16.dp))
            .padding(4.dp)) { preview() }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, color = if (selected) t.text else t.textDim, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun Preview(tones: Tones, modifier: Modifier, swatch: Boolean = false, ratio: Float = 1.5f) {
    Column(modifier.aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(tones.surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        val colors = if (swatch) tones.swatch else listOf(tones.text, tones.textDim, tones.swatch.last())
        colors.forEachIndexed { i, c ->
            Box(Modifier.fillMaxWidth(listOf(0.8f, 0.6f, 0.4f)[i]).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c))
        }
    }
}

@Composable
fun KeysScreen() {
    val nav = LocalNav.current
    val t = LocalTones.current
    val ctx = LocalContext.current
    val cfg = labConfig()
    var showGen by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<SshKey?>(null) }
    fun copy(text: String) = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("public key", text))

    RefreshList(onRefresh = {}) {
        item {
            TopBar("SSH keys", onBack = { nav.popBackStack() })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showGen = true }, Modifier.weight(1f)) { Text("Generate Ed25519") }
                OutlinedButton(onClick = { showImport = true }, Modifier.weight(1f)) { Text("Import") }
            }
            Text("Generate a key here, copy the public key into ~/.ssh/authorized_keys on your servers. The private key never leaves the phone.",
                color = t.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            LabCard {
                if (cfg.keys.isEmpty()) Text("No keys.", Modifier.padding(16.dp), color = t.textDim)
                cfg.keys.forEachIndexed { i, k ->
                    if (i > 0) Divider()
                    NavRow(k.name, k.publicKey?.take(40)?.plus("…") ?: "imported private key", icon = Icons.Outlined.Key, onClick = { view = k })
                }
            }
        }
    }

    if (showGen) {
        var name by remember { mutableStateOf("pocketlab-phone") }
        AlertDialog(onDismissRequest = { showGen = false }, title = { Text("Generate key") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name / comment") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val (priv, pub) = generateEd25519(name.trim().ifBlank { "pocketlab" })
                val k = SshKey(name = name.trim().ifBlank { "pocketlab" }, privateKey = priv, publicKey = pub)
                lab.store.update { it.copy(keys = it.keys + k) }
                showGen = false; view = k
            }) { Text("Generate") } },
            dismissButton = { TextButton(onClick = { showGen = false }) { Text("Cancel") } })
    }
    if (showImport) {
        var name by remember { mutableStateOf("") }
        var priv by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showImport = false }, title = { Text("Import private key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(priv, { priv = it }, label = { Text("Private key (OpenSSH / PEM)") }, minLines = 4, maxLines = 8,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = RawTextKeyboard)
                    SecretField(pass, { pass = it }, "Passphrase (if any)", Modifier)
                }
            },
            confirmButton = { TextButton(enabled = priv.contains("PRIVATE KEY"), onClick = {
                lab.store.update { it.copy(keys = it.keys + SshKey(name = name.ifBlank { "imported" }, privateKey = priv.trim() + "\n", passphrase = pass.ifBlank { null })) }
                showImport = false
            }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } })
    }
    view?.let { k ->
        val inUse = cfg.hosts.filter { it.keyId == k.id }
        AlertDialog(onDismissRequest = { view = null }, title = { Text(k.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (k.publicKey != null) {
                        Text("Public key — add to ~/.ssh/authorized_keys:")
                        SelectionContainer { Text(k.publicKey, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    } else Text("Imported key (public half not stored).")
                    if (inUse.isNotEmpty()) Text("Used by: " + inUse.joinToString { it.name }, color = t.textDim)
                }
            },
            confirmButton = { if (k.publicKey != null) TextButton(onClick = { copy(k.publicKey) }) { Text("Copy public key") } },
            dismissButton = { TextButton(enabled = inUse.isEmpty(), onClick = {
                lab.store.update { c -> c.copy(keys = c.keys.filterNot { it.id == k.id }) }; view = null
            }) { Text("Delete") } })
    }
}
