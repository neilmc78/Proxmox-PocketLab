package dev.pocketlab.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketlab.ssh.TermState
import dev.pocketlab.ssh.TerminalSession
import dev.pocketlab.ssh.TerminalSessions
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.components.Divider
import dev.pocketlab.ui.components.LabCard
import dev.pocketlab.ui.components.NavRow
import dev.pocketlab.ui.components.SectionTitle
import dev.pocketlab.ui.components.TopBar
import dev.pocketlab.ui.components.TrustDialog
import dev.pocketlab.ui.theme.LocalTones
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

@Composable
fun TerminalTab() {
    val nav = LocalNav.current
    val t = LocalTones.current
    val sessions by TerminalSessions.sessions.collectAsState()
    val cfg = labConfig()
    RefreshList(onRefresh = {}) {
        item {
            Text("Terminal", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            if (sessions.isNotEmpty()) {
                SectionTitle("Open sessions")
                LabCard {
                    sessions.forEachIndexed { i, s ->
                        if (i > 0) Divider()
                        val st by s.state.collectAsState()
                        NavRow(s.title, (s.command ?: "${s.host.username}@${s.host.address}") + " · " + when (st) {
                            TermState.Connected -> "connected"; TermState.Connecting -> "connecting"; else -> "closed"
                        }, icon = Icons.Outlined.Terminal, onClick = { nav.navigate(Routes.term(s.id)) }, trailing = {
                            IconButton(onClick = { TerminalSessions.close(s.id) }) { Icon(Icons.Outlined.Close, "Close") }
                        })
                    }
                }
            }
            SectionTitle("New session")
            LabCard {
                if (cfg.hosts.isEmpty()) NavRow("Add an SSH host", "Then open a shell here", onClick = { nav.navigate(Routes.editHost()) })
                cfg.hosts.forEachIndexed { i, h ->
                    if (i > 0) Divider()
                    NavRow(h.name, "${h.username}@${h.address}" + (if (h.port != 22) ":${h.port}" else ""), icon = Icons.Outlined.Terminal,
                        onClick = { openTerminal(nav, t, h.name, h) })
                }
            }
        }
    }
}

private class StickyModifiers : ModifierManager {
    // 0 = off, 1 = next key only, 2 = locked
    var ctrl by mutableIntStateOf(0)
    var alt by mutableIntStateOf(0)
    override fun isCtrlActive() = ctrl > 0
    override fun isAltActive() = alt > 0
    override fun isShiftActive() = false
    override fun clearTransients() { if (ctrl == 1) ctrl = 0; if (alt == 1) alt = 0 }
    fun mask() = (if (alt > 0) 2 else 0) or (if (ctrl > 0) 4 else 0)
}

@Composable
fun TermScreen(sessionId: String) {
    val nav = LocalNav.current
    val t = LocalTones.current
    val cfg = labConfig()
    val session = TerminalSessions.get(sessionId)
    if (session == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Session closed", onBack = { nav.popBackStack() })
        }
        return
    }
    val state by session.state.collectAsState()
    val mods = remember { StickyModifiers() }
    var fontSp by remember { mutableIntStateOf(cfg.appearance.terminalFontSp) }
    var menu by remember { mutableStateOf(false) }
    var trustOpen by remember { mutableStateOf(true) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().background(t.bg).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        TopBar(session.title, onBack = { nav.popBackStack() }) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(when (state) {
                TermState.Connected -> t.ok; TermState.Connecting -> t.warn; else -> t.bad
            }))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Menu") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Paste") }, onClick = {
                        menu = false
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)
                            ?.let { session.emulator.pasteText(it.toString()) }
                    })
                    DropdownMenuItem({ Text("Larger text") }, onClick = { fontSp = (fontSp + 1).coerceAtMost(28) })
                    DropdownMenuItem({ Text("Smaller text") }, onClick = { fontSp = (fontSp - 1).coerceAtLeast(7) })
                    DropdownMenuItem({ Text("Reconnect") }, onClick = { menu = false; session.close(); TerminalSessions.reconnect(session) })
                    DropdownMenuItem({ Text("Close session") }, onClick = { menu = false; TerminalSessions.close(session.id); nav.popBackStack() })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.runtime.key(fontSp) {
                Terminal(
                    terminalEmulator = session.emulator,
                    modifier = Modifier.fillMaxSize(),
                    typeface = Typeface.MONOSPACE,
                    initialFontSize = fontSp.sp,
                    backgroundColor = t.bg, foregroundColor = t.text,
                    keyboardEnabled = true, showSoftKeyboard = true,
                    modifierManager = mods,
                )
            }
            when (val s = state) {
                TermState.Connecting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is TermState.Closed -> Column(Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(MaterialTheme.shapes.large)
                    .background(t.surfaceHigh).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.reason ?: "Disconnected", color = t.text)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { TerminalSessions.reconnect(session) }) { Text("Reconnect") }
                }
                is TermState.HostKey -> if (trustOpen) TrustDialog(
                    if (s.error.mismatch) "Host key changed" else "Trust host key?",
                    "${session.host.address} presents this host key:", s.error.fingerprint, s.error.mismatch,
                    onTrust = { trustHostAndReopen(nav, session, s.error.fingerprint, t) },
                    onDismiss = { trustOpen = false },
                ) else Button(onClick = { trustOpen = true }, Modifier.align(Alignment.Center)) { Text("Review host key") }
                TermState.Connected -> {}
            }
        }
        ExtraKeys(session, mods)
    }
}

private fun trustHostAndReopen(nav: androidx.navigation.NavHostController, s: TerminalSession, fp: String, t: dev.pocketlab.ui.theme.Tones) {
    lab.store.update { c -> c.copy(hosts = c.hosts.map { if (it.id == s.host.id) it.copy(hostKeyFingerprint = fp) else it }) }
    lab.forgetConnections()
    val updated = lab.host(s.host.id) ?: return
    TerminalSessions.close(s.id)
    nav.popBackStack()
    openTerminal(nav, t, s.title, updated, s.command)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtraKeys(s: TerminalSession, mods: StickyModifiers) {
    val t = LocalTones.current
    var more by remember { mutableStateOf(false) }
    fun key(k: Int) { s.emulator.dispatchKey(mods.mask(), k); mods.clearTransients() }
    fun ch(c: Char) { s.emulator.dispatchCharacter(mods.mask(), c); mods.clearTransients() }

    @Composable
    fun K(label: String, active: Int = 0, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        Box(
            Modifier.widthIn(min = 44.dp).height(42.dp)
                .background(when (active) { 2 -> MaterialTheme.colorScheme.primary; 1 -> MaterialTheme.colorScheme.primaryContainer; else -> t.surface })
                .combinedClickable(onClick = onClick, onLongClick = onLong)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (active == 2) MaterialTheme.colorScheme.onPrimary else t.text, fontWeight = FontWeight.Medium)
        }
    }

    Column(Modifier.fillMaxWidth().background(t.surface)) {
        if (more) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            K("Home") { key(VTermKey.HOME) }; K("End") { key(VTermKey.END) }
            K("PgUp") { key(VTermKey.PAGEUP) }; K("PgDn") { key(VTermKey.PAGEDOWN) }
            K("Del") { key(VTermKey.DEL) }
            listOf('-', '/', '~', '`', '\\', '$', '&', '*', '>', '<', '"', '\'', '[', ']', '{', '}').forEach { c -> K(c.toString()) { ch(c) } }
            (1..12).forEach { n -> K("F$n") { key(VTermKey.FUNCTION_0 + n) } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            K("Esc") { key(VTermKey.ESCAPE) }
            K("Tab") { key(VTermKey.TAB) }
            K("Ctrl", mods.ctrl, onLong = { mods.ctrl = 2 }) { mods.ctrl = if (mods.ctrl == 0) 1 else 0 }
            K("Alt", mods.alt, onLong = { mods.alt = 2 }) { mods.alt = if (mods.alt == 0) 1 else 0 }
            K("↑") { key(VTermKey.UP) }; K("↓") { key(VTermKey.DOWN) }
            K("←") { key(VTermKey.LEFT) }; K("→") { key(VTermKey.RIGHT) }
            K("|") { ch('|') }
            K(if (more) "Less" else "More", if (more) 1 else 0) { more = !more }
        }
    }
}

