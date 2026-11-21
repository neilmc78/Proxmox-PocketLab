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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.os.Handler
import java.util.concurrent.atomic.AtomicInteger
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

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "term-writer").apply { isDaemon = true } }

    /** One live connection attempt. A new connect or a close bumps [generation]; stale attempts tear themselves down. */
    private class Conn(val client: SSHClient, val resize: (Int, Int) -> Unit, val out: OutputStream)
    private val generation = AtomicInteger(0)
    @Volatile private var conn: Conn? = null
    @Volatile private var job: Job? = null
    @Volatile private var disposed = false
    @Volatile private var cols = 80
    @Volatile private var rows = 24

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        looper = Looper.getMainLooper(),
        initialRows = rows, initialCols = cols,
        defaultForeground = fg, defaultBackground = bg,
        onKeyboardInput = { bytes -> send(bytes) },
        onResize = { d ->
            cols = d.columns; rows = d.rows
            exec { conn?.resize?.invoke(d.columns, d.rows) }
        },
        autoDetectUrls = true,
    )

    private fun exec(block: () -> Unit) {
        if (disposed) return
        runCatching { writer.execute { runCatching(block) } }
    }

    fun send(bytes: ByteArray) = exec { conn?.out?.apply { write(bytes); flush() } }

    fun connect(scope: CoroutineScope) {
        if (disposed) return
        val myGen = generation.incrementAndGet()
        job?.cancel()
        conn?.client?.let { c -> exec { c.close() } }
        conn = null
        _state.value = TermState.Connecting
        job = scope.launch(Dispatchers.IO) {
            var client: SSHClient? = null
            try {
                val c = SshPool.open(host, keys)
                client = c
                if (generation.get() != myGen) return@launch // closed or superseded while connecting
                val s = c.startSession()
                s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
                val input: java.io.InputStream
                if (command == null) {
                    val sh = s.startShell()
                    conn = Conn(c, { w, h -> sh.changeWindowDimensions(w, h, 0, 0) }, sh.outputStream)
                    input = sh.inputStream
                } else {
                    val cmd = s.exec(command)
                    conn = Conn(c, { w, h -> (cmd as? Session.Shell)?.changeWindowDimensions(w, h, 0, 0) }, cmd.outputStream)
                    input = cmd.inputStream
                }
                if (generation.get() != myGen) return@launch
                _state.value = TermState.Connected
                val buf = ByteArray(16 * 1024)
                while (generation.get() == myGen) {
                    val n = input.read(buf)
                    if (n < 0) break
                    emulator.writeInput(buf, 0, n)
                }
                if (generation.get() == myGen) _state.value = TermState.Closed("Session ended")
            } catch (e: HostKeyException) {
                if (generation.get() == myGen) _state.value = TermState.HostKey(e)
            } catch (e: Exception) {
                if (generation.get() == myGen) _state.value = TermState.Closed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { client?.close() }
                if (generation.get() == myGen) conn = null
            }
        }
    }

    fun close() {
        generation.incrementAndGet()
        job?.cancel()
        val c = conn?.client
        conn = null
        // Closing the client unblocks the reader; do it off the main thread.
        if (c != null) Thread { runCatching { c.close() } }.apply { isDaemon = true }.start()
        _state.update { if (it is TermState.Closed) it else TermState.Closed("Disconnected") }
    }

    /** Final teardown: connection, writer thread, and the emulator's native state. */
    fun dispose() {
        close()
        disposed = true
        writer.shutdown()
        // Let any screen still showing this emulator leave composition before freeing native memory.
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { emulator.close() } }, 2_000)
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
        _sessions.value.firstOrNull { it.id == id }?.dispose()
        _sessions.update { list -> list.filterNot { it.id == id } }
    }
}
