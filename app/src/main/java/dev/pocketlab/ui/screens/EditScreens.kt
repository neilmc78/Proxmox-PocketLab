package dev.pocketlab.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketlab.data.ProxmoxCluster
import dev.pocketlab.data.SshAuthType
import dev.pocketlab.data.SshHost
import dev.pocketlab.net.untrustedCert
import dev.pocketlab.proxmox.ProxmoxClient
import dev.pocketlab.ssh.HostKeyException
import dev.pocketlab.ssh.SshPool
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.LocalSnackbar
import dev.pocketlab.ui.components.TopBar
import dev.pocketlab.ui.components.TrustDialog
import dev.pocketlab.ui.theme.LocalTones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun Form(title: String, content: @Composable () -> Unit) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        TopBar(title, onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** Password keyboard with autocorrect off: the IME must not learn, suggest or cloud-sync secrets. */
val SecretKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

/** For pasted key material: no learning, no autocorrect, no "smart" punctuation. */
val RawTextKeyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false)

/** Hidden by default; the eye icon shows what was typed. Screenshots stay blocked either way (FLAG_SECURE). */
@Composable
fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(),
                placeholder: String? = null) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValueChange, modifier, label = { Text(label) }, singleLine = true,
        placeholder = placeholder?.let { { Text(it) } },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = SecretKeyboard,
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (shown) "Hide $label" else "Show $label")
            }
        })
}

/** Commands to run on a Proxmox node to create a least-privilege user and API token. */
const val PVE_TOKEN_SETUP = """pveum user add pocketlab@pve
pveum acl modify / --users pocketlab@pve --roles PVEAuditor
pveum role add PocketlabPower --privs "VM.PowerMgmt VM.Snapshot VM.Snapshot.Rollback"
pveum acl modify /vms --users pocketlab@pve --roles PocketlabPower
pveum user token add pocketlab@pve phone --privsep 0"""

/** Selectable monospace block with a copy button (the screen is FLAG_SECURE, so it can't be screenshotted). */
@Composable
fun CopyableCode(code: String, clipLabel: String) {
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
            Text(code, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false)
        }
        OutlinedButton(onClick = {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(clipLabel, code))
            scope.launch { snackbar.showSnackbar("Copied") }
        }) { Icon(Icons.Outlined.ContentCopy, null); Text("  Copy commands") }
    }
}

@Composable
private fun Help(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.textDim)

@Composable
fun EditClusterScreen(id: String?) {
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val existing = labConfig().clusters.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.baseUrl ?: "https://") }
    var tokenId by remember { mutableStateOf(existing?.tokenId ?: "") }
    var secret by remember { mutableStateOf(existing?.tokenSecret ?: "") }
    var pin by remember { mutableStateOf(existing?.pinnedCertSha256) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var trust by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun normalizedUrl(): String {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("https://")) u = "https://" + u.removePrefix("http://")
        if (Regex("^https://[^/:]+$").matches(u)) u += ":8006"
        return u
    }

    // A pin belongs to an address: pointing the cluster somewhere else starts a fresh first-use
    // check instead of a scary (and wrong) "certificate changed" warning.
    fun effectivePin(): String? =
        if (existing != null && pin == existing.pinnedCertSha256 && existing.baseUrl != normalizedUrl()) null else pin

    fun build() = ProxmoxCluster(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim().ifBlank { "Proxmox" },
        baseUrl = normalizedUrl(), tokenId = tokenId.trim(), tokenSecret = secret.trim(), pinnedCertSha256 = effectivePin())

    fun testAndSave() = scope.launch {
        busy = true; error = null
        val c = build()
        try {
            val v = ProxmoxClient(c).version()
            lab.store.update { cfg -> cfg.copy(clusters = cfg.clusters.filterNot { it.id == c.id } + c) }
            lab.refreshCluster(c.id)
            snackbar.showSnackbar("Connected to Proxmox VE $v")
            nav.popBackStack()
        } catch (e: Exception) {
            val u = e.untrustedCert()
            if (u != null) trust = u.fingerprint to u.subject else error = e.message ?: e.javaClass.simpleName
        } finally { busy = false }
    }

    Form(if (existing == null) "Add Proxmox cluster" else "Edit ${existing.name}") {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, placeholder = { Text("homelab") })
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Address") }, singleLine = true,
            placeholder = { Text("https://192.168.1.10:8006") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Help("Any one node of the cluster. Port 8006 is added if you leave it out.")
        OutlinedTextField(tokenId, { tokenId = it }, Modifier.fillMaxWidth(), label = { Text("API token ID") }, singleLine = true,
            placeholder = { Text("pocketlab@pve!phone") })
        SecretField(secret, { secret = it }, "API token secret", placeholder = "xxxxxxxx-xxxx-…")
        Help("Create a dedicated user and token on a node shell, e.g.:")
        CopyableCode(PVE_TOKEN_SETUP, "Proxmox token setup")
        Help("Run them in the node's Shell (Proxmox web UI → node → Shell). The last one prints the token ID (pocketlab@pve!phone) and the secret; the secret is shown only once.")
        Help("Read-only is enough to browse; power and snapshot buttons need the second role. Revoke the token in Proxmox any time.")
        if (pin != null) Help("Pinned certificate: $pin")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { testAndSave() }, enabled = !busy && tokenId.contains('!') && secret.isNotBlank() && url.length > 8, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Test and save")
        }
        if (existing != null) TextButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) { Text("Remove cluster", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
    }

    trust?.let { (fp, subject) ->
        val changed = effectivePin() != null
        TrustDialog(if (changed) "Certificate changed" else "Trust this certificate?",
            "${normalizedUrl()} presents a certificate that isn't signed by a public CA (normal for Proxmox).\n$subject\nSHA-256:", fp, changed,
            onTrust = { pin = fp; testAndSave() }, onDismiss = { trust = null })
    }
    if (confirmDelete && existing != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Remove ${existing.name}?") }, text = { Text("This only removes it from Pocketlab. Revoke the API token in Proxmox too.") },
        confirmButton = { TextButton(onClick = {
            lab.store.update { c -> c.copy(clusters = c.clusters.filterNot { it.id == existing.id }) }
            scope.launch { lab.refresh() }
            nav.popBackStack()
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
fun EditHostScreen(id: String?) {
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val cfg = labConfig()
    val existing = cfg.hosts.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(existing?.username ?: "root") }
    var auth by remember { mutableStateOf(existing?.authType ?: if (cfg.keys.isEmpty()) SshAuthType.PASSWORD else SshAuthType.KEY) }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var keyId by remember { mutableStateOf(existing?.keyId ?: cfg.keys.firstOrNull()?.id) }
    var docker by remember { mutableStateOf(existing?.dockerEnabled ?: true) }
    var dockerSudo by remember { mutableStateOf(existing?.dockerSudo ?: false) }
    var node by remember { mutableStateOf(existing?.proxmoxNode ?: "") }
    var nodeCluster by remember { mutableStateOf(existing?.proxmoxClusterId) }
    var fp by remember { mutableStateOf(existing?.hostKeyFingerprint) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var trust by remember { mutableStateOf<HostKeyException?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clusterNames = cfg.clusters.associate { it.id to it.name }
    val knownNodes = labSnapshot().clusters.values.flatMap { it.nodes.map { n -> n.clusterId to n.name } }.distinct()

    fun sameEndpoint() = existing != null && existing.address == address.trim() && existing.port == (port.toIntOrNull() ?: 22)
    // A host key belongs to an address: a changed address/port means a fresh first-use check, not a MITM warning.
    fun effectiveFp(): String? = if (existing != null && fp == existing.hostKeyFingerprint && !sameEndpoint()) null else fp

    fun build() = SshHost(
        id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim().ifBlank { address.trim() },
        address = address.trim(), port = port.toIntOrNull() ?: 22, username = user.trim(), authType = auth,
        password = password.takeIf { auth == SshAuthType.PASSWORD }, keyId = keyId.takeIf { auth == SshAuthType.KEY },
        hostKeyFingerprint = effectiveFp(), dockerEnabled = docker, dockerSudo = dockerSudo, proxmoxNode = node.trim().ifBlank { null },
        proxmoxClusterId = nodeCluster.takeIf { node.isNotBlank() },
    )

    fun testAndSave() = scope.launch {
        busy = true; error = null
        val h = build()
        try {
            withContext(Dispatchers.IO) { SshPool.open(h, lab.config.keys).close() }
            lab.store.update { c -> c.copy(hosts = c.hosts.filterNot { it.id == h.id } + h) }
            SshPool.drop(h.id)
            lab.refreshHost(h.id)
            snackbar.showSnackbar("Connected to ${h.name}")
            nav.popBackStack()
        } catch (e: HostKeyException) {
            trust = e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally { busy = false }
    }

    Form(if (existing == null) "Add SSH host" else "Edit ${existing.name}") {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Help("Name it like the Proxmox node or VM it is (e.g. pve1, docker-vm) and Pocketlab links them automatically.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(address, { address = it }, Modifier.weight(1f), label = { Text("Address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.weight(0.35f), label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(auth == SshAuthType.KEY, { auth = SshAuthType.KEY }, label = { Text("SSH key") })
            FilterChip(auth == SshAuthType.PASSWORD, { auth = SshAuthType.PASSWORD }, label = { Text("Password") })
        }
        if (auth == SshAuthType.PASSWORD) {
            SecretField(password, { password = it }, "Password")
        } else {
            if (cfg.keys.isEmpty()) Help("No keys yet.")
            cfg.keys.forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(keyId == k.id, { keyId = k.id }, label = { Text(k.name) })
                }
            }
            OutlinedButton(onClick = { nav.navigate("keys") }) { Text("Manage keys (generate / import)") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Docker"); Help("List containers on this host") }
            Switch(docker, { docker = it })
        }
        if (docker) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Use sudo for docker"); Help("Only if the user isn't in the docker group (needs passwordless sudo)") }
            Switch(dockerSudo, { dockerSudo = it })
        }
        OutlinedTextField(node, { node = it; nodeCluster = null }, Modifier.fillMaxWidth(), label = { Text("Proxmox node (optional)") }, singleLine = true)
        if (knownNodes.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            knownNodes.forEach { (cid, n) ->
                val selected = node == n && nodeCluster == cid
                val label = if (clusterNames.size > 1) "$n · ${clusterNames[cid]}" else n
                FilterChip(selected, { if (selected) { node = ""; nodeCluster = null } else { node = n; nodeCluster = cid } }, label = { Text(label) })
            }
        }
        Help("Set this if the host is a Proxmox node: enables node shells and VM/LXC consoles (qm terminal / pct enter).")
        fp?.let { Help("Trusted host key: $it") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { testAndSave() }, enabled = !busy && address.isNotBlank() && user.isNotBlank() &&
            (auth == SshAuthType.PASSWORD || keyId != null), modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Test and save")
        }
        if (existing != null) TextButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) { Text("Remove host", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
    }

    trust?.let { e ->
        TrustDialog(if (e.mismatch) "Host key changed" else "Trust host key?", "${address.trim()} presents this host key:", e.fingerprint, e.mismatch,
            onTrust = { fp = e.fingerprint; testAndSave() }, onDismiss = { trust = null })
    }
    if (confirmDelete && existing != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Remove ${existing.name}?") }, text = { Text("Removes the host from Pocketlab. Keys stay in the key list.") },
        confirmButton = { TextButton(onClick = {
            lab.store.update { c -> c.copy(hosts = c.hosts.filterNot { it.id == existing.id }) }
            SshPool.drop(existing.id)
            scope.launch { lab.refresh() }
            nav.popBackStack("overview", false)
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
