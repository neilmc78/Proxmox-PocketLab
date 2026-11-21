package dev.pocketlab.ssh

import android.os.Looper
import androidx.compose.ui.graphics.Color
import dev.pocketlab.data.SshHost
import dev.pocketlab.data.SshKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

sealed interface TermState {
    data object Connecting : TermState
    data object Connected : TermState
    data class HostKey(val error: HostKeyException) : TermState
    data class Closed(val reason: String?) : TermState
}

/**
 * One interactive SSH session bound to a terminal emulator. Lives in [TerminalSessions], not in a
 * screen, so it survives navigation and rotation. Uses its own SSH connection so a dropped pooled
 * connection never kills an open shell (and vice versa).
 */
class TerminalSession(
    val title: String,
    val host: SshHost,
    private val keys: List<SshKey>,
    /** Command to run instead of a login shell (e.g. `qm terminal 202`). */
    val command: String?,
    fg: Color, bg: Color,
) {
    val id: String = UUID.randomUUID().toString()
    private val _state = MutableStateFlow<TermState>(TermState.Connecting)
    val state: StateFlow<TermState> = _state.asStateFlow()

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var client: SSHClient? = null
    @Volatile private var shell: Session.Shell? = null
    @Volatile private var command_: Session.Command? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var cols = 80
    @Volatile private var rows = 24

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        looper = Looper.getMainLooper(),
        initialRows = rows, initialCols = cols,
        defaultForeground = fg, defaultBackground = bg,
        onKeyboardInput = { bytes -> send(bytes) },
        onResize = { d ->
            cols = d.columns; rows = d.rows
            writer.execute {
                runCatching { shell?.changeWindowDimensions(d.columns, d.rows, 0, 0) }
                runCatching { (command_ as? Session.Shell)?.changeWindowDimensions(d.columns, d.rows, 0, 0) }
            }
        },
        autoDetectUrls = true,
    )

    fun send(bytes: ByteArray) {
        writer.execute {
            try { out?.apply { write(bytes); flush() } } catch (_: Exception) { }
        }
    }

    fun connect(scope: CoroutineScope) {
        _state.value = TermState.Connecting
        scope.launch(Dispatchers.IO) {
            try {
                val c = SshPool.open(host, keys)
                client = c
                val s = c.startSession()
                s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
                val input = if (command == null) {
                    val sh = s.startShell(); shell = sh; out = sh.outputStream; sh.inputStream
                } else {
                    val cmd = s.exec(command); command_ = cmd; out = cmd.outputStream; cmd.inputStream
                }
                _state.value = TermState.Connected
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    emulator.writeInput(buf, 0, n)
                }
                _state.value = TermState.Closed("Session ended")
            } catch (e: HostKeyException) {
                _state.value = TermState.HostKey(e)
            } catch (e: Exception) {
                _state.value = TermState.Closed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { client?.close() }
                client = null; shell = null; command_ = null; out = null
            }
        }
    }

    fun close() {
        writer.execute { runCatching { client?.close() } }
        _state.update { if (it is TermState.Closed) it else TermState.Closed("Disconnected") }
    }
}

object TerminalSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    fun open(title: String, host: SshHost, keys: List<SshKey>, command: String?, fg: Color, bg: Color): TerminalSession {
        val s = TerminalSession(title, host, keys, command, fg, bg)
        _sessions.update { it + s }
        s.connect(scope)
        return s
    }

    fun reconnect(s: TerminalSession) = s.connect(scope)

    fun get(id: String) = _sessions.value.firstOrNull { it.id == id }

    fun close(id: String) {
        _sessions.value.firstOrNull { it.id == id }?.close()
        _sessions.update { list -> list.filterNot { it.id == id } }
    }
}
