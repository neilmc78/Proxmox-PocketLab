package dev.pocketlab.ssh

import dev.pocketlab.data.SshAuthType
import dev.pocketlab.data.SshHost
import dev.pocketlab.data.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.Config
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

fun hostKeyFingerprint(key: PublicKey): String {
    val sha = MessageDigest.getInstance("SHA-256").digest(sshPublicKeyBlob(key))
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha)
}

/**
 * The key in SSH wire format, as `ssh-keygen -lf` hashes it. Ed25519 is built by hand when sshj
 * doesn't recognise the key object (some providers name the algorithm differently): its blob is
 * just "ssh-ed25519" plus the 32 raw key bytes, the tail of the X.509 encoding.
 */
internal fun sshPublicKeyBlob(key: PublicKey): ByteArray = try {
    Buffer.PlainBuffer().putPublicKey(key).compactData
} catch (e: UnsupportedOperationException) {
    val enc = key.encoded
    val ed25519Spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70)
    if (enc == null || enc.size != 44 || !enc.copyOfRange(0, 9).contentEquals(ed25519Spki)) throw e
    Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(enc.copyOfRange(12, 44)).compactData
}

/** The server's host key is unknown (first connect) or does not match what we pinned. */
class HostKeyException(val fingerprint: String, val mismatch: Boolean) : IOException(
    if (mismatch) "HOST KEY CHANGED: server now presents $fingerprint. This may be a man-in-the-middle attack."
    else "Unknown host key $fingerprint"
)

private class PinnedVerifier(private val expected: String?) : HostKeyVerifier {
    @Volatile var seen: String? = null
    override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
        val fp = hostKeyFingerprint(key)
        seen = fp
        return expected != null && expected == fp
    }
    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
}

data class ExecResult(val exit: Int?, val stdout: String, val stderr: String) {
    val ok get() = exit == 0
}

/** A command ran past its deadline; the channel was closed to unblock it. */
class SshTimeoutException(message: String) : IOException(message)

object SshPool {
    private class Pooled(val client: SSHClient, val signature: Int)
    private val clients = ConcurrentHashMap<String, Pooled>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ssh-watchdog").apply { isDaemon = true } }

    /** Max bytes kept from one exec's stdout/stderr, and max length of one streamed log line. */
    const val MAX_EXEC_OUTPUT = 4 * 1024 * 1024
    const val MAX_LINE = 16 * 1024

    /** Opens a fresh, authenticated client. Throws [HostKeyException] when the key isn't pinned. */
    fun open(host: SshHost, keys: List<SshKey>, config: Config = HardenedSshConfig()): SSHClient {
        SlimCryptoProvider.install()
        // Real keep-alive requests (not fire-and-forget heartbeats): a dead peer is detected after
        // ~5 missed replies and the connection is torn down, which unblocks any reads on it.
        config.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        val verifier = PinnedVerifier(host.hostKeyFingerprint)
        val c = SSHClient(config)
        c.addHostKeyVerifier(verifier)
        c.connectTimeout = 10_000
        c.timeout = 30_000
        try {
            c.connect(host.address, host.port)
        } catch (e: Exception) {
            runCatching { c.close() }
            val seen = verifier.seen
            if (seen != null && seen != host.hostKeyFingerprint) {
                throw HostKeyException(seen, mismatch = host.hostKeyFingerprint != null)
            }
            throw e
        }
        try {
            when (host.authType) {
                SshAuthType.PASSWORD, SshAuthType.PROMPT -> {
                    // PROMPT hosts get the password typed just before connecting (never stored).
                    val pw = host.password ?: throw IOException("${host.name} asks for its password each time; none was given")
                    // "password" and keyboard-interactive: many servers (PAM) only offer the latter.
                    val finder = object : net.schmizz.sshj.userauth.password.PasswordFinder {
                        override fun reqPassword(resource: net.schmizz.sshj.userauth.password.Resource<*>?) = pw.toCharArray()
                        override fun shouldRetry(resource: net.schmizz.sshj.userauth.password.Resource<*>?) = false
                    }
                    c.auth(host.username,
                        net.schmizz.sshj.userauth.method.AuthPassword(finder),
                        net.schmizz.sshj.userauth.method.AuthKeyboardInteractive(net.schmizz.sshj.userauth.method.PasswordResponseProvider(finder)))
                }
                SshAuthType.KEY -> {
                    val k = keys.firstOrNull { it.id == host.keyId } ?: throw IOException("No SSH key selected for ${host.name}")
                    val finder = k.passphrase?.takeIf { it.isNotEmpty() }?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    c.authPublickey(host.username, c.loadKeys(k.privateKey, k.publicKey, finder))
                }
            }
        } catch (e: Exception) {
            runCatching { c.close() }
            throw e
        }
        c.connection.keepAlive.keepAliveInterval = 15
        return c
    }

    /** Identity of everything that affects a connection, so an edited host never reuses a stale one. */
    private fun signature(host: SshHost, keys: List<SshKey>) =
        listOf(host.address, host.port, host.username, host.authType, host.password, host.hostKeyFingerprint,
            keys.firstOrNull { it.id == host.keyId }).hashCode()

    suspend fun client(host: SshHost, keys: List<SshKey>): SSHClient = withContext(Dispatchers.IO) {
        val sig = signature(host, keys)
        locks.getOrPut(host.id) { Mutex() }.withLock {
            clients[host.id]?.let { p ->
                if (p.signature == sig && p.client.isConnected && p.client.isAuthenticated) return@withLock p.client
            }
            clients.remove(host.id)?.let { runCatching { it.client.close() } }
            open(host, keys).also { clients[host.id] = Pooled(it, sig) }
        }
    }

    fun drop(hostId: String) { clients.remove(hostId)?.let { runCatching { it.client.close() } } }

    private fun dropIfSame(hostId: String, c: SSHClient) {
        clients.computeIfPresent(hostId) { _, p -> if (p.client === c) null else p }
        runCatching { c.close() }
    }
    fun dropAll() { clients.keys.toList().forEach(::drop) }

    /**
     * Runs one command. A hard deadline closes the channel if the command (or the network) hangs,
     * so a wedged `docker ps` can never stall polling. Retries once only if the failure happened
     * before the command was sent (stale pooled connection), never after: actions must not run twice.
     */
    suspend fun exec(host: SshHost, keys: List<SshKey>, command: String, timeoutSec: Long = 30): ExecResult =
        withContext(Dispatchers.IO) {
            var sent = false
            val attempt: suspend () -> ExecResult = {
                val c = client(host, keys)
                c.startSession().use { s ->
                    val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
                    val cmd = s.exec(command)
                    sent = true
                    val guard = watchdog.schedule({
                        timedOut.set(true)
                        // Closing just the channel waits for the server's reply, which a wedged command
                        // never sends. Tearing down the connection errors every blocked read on it.
                        dropIfSame(host.id, c)
                    }, timeoutSec, TimeUnit.SECONDS)
                    try {
                        coroutineScope {
                            val err = async(Dispatchers.IO) { cmd.errorStream.readCapped(MAX_EXEC_OUTPUT) }
                            val out = cmd.inputStream.readCapped(MAX_EXEC_OUTPUT)
                            val errText = err.await()
                            cmd.join(5, TimeUnit.SECONDS)
                            if (timedOut.get()) throw SshTimeoutException("Timed out after ${timeoutSec}s: ${command.take(40)}")
                            ExecResult(cmd.exitStatus, out, errText)
                        }
                    } catch (e: Exception) {
                        if (timedOut.get() && e !is SshTimeoutException) throw SshTimeoutException("Timed out after ${timeoutSec}s")
                        throw e
                    } finally { guard.cancel(false) }
                }
            }
            try { attempt() } catch (e: HostKeyException) { throw e } catch (e: Exception) {
                drop(host.id)
                if (sent) throw e
                // Pooled connection was dead before we got to send anything (phone slept, Wi-Fi changed).
                attempt()
            }
        }

    /** Streams combined output of a long-running command line by line; cancelling the flow closes the channel. */
    fun stream(host: SshHost, keys: List<SshKey>, command: String): Flow<String> = callbackFlow {
        val c = client(host, keys)
        val session = c.startSession()
        val cmd = session.exec("$command 2>&1")
        val reader = Thread {
            try {
                // trySendBlocking: back-pressure instead of silently dropping lines when the UI is behind.
                cmd.inputStream.forEachCappedLine(MAX_LINE) { trySendBlocking(it) }
                close()
            } catch (e: Exception) { close(e) }
        }.apply { isDaemon = true; start() }
        awaitClose {
            runCatching { cmd.close() }
            runCatching { session.close() }
            reader.interrupt()
        }
    }.buffer(1024).flowOn(Dispatchers.IO)
}

private fun InputStream.readCapped(max: Int): String {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (out.size() < max) out.write(buf, 0, minOf(n, max - out.size()))
        // keep draining past the cap so the remote side isn't blocked on a full window
    }
    return out.toByteArray().decodeToString()
}

/** Splits on '\n'; any line longer than [max] bytes is cut, and the rest of it is discarded. */
private fun InputStream.forEachCappedLine(max: Int, action: (String) -> Unit) {
    val line = java.io.ByteArrayOutputStream()
    var truncated = false
    val buf = ByteArray(8192)
    fun flush() {
        var bytes = line.toByteArray()
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes = bytes.copyOf(bytes.size - 1)
        action(bytes.decodeToString() + if (truncated) " …[truncated]" else "")
        line.reset(); truncated = false
    }
    while (true) {
        val n = read(buf)
        if (n < 0) break
        for (i in 0 until n) {
            val b = buf[i]
            if (b == '\n'.code.toByte()) flush()
            else if (line.size() < max) line.write(b.toInt()) else truncated = true
        }
    }
    if (line.size() > 0) flush()
}

/** Generates an Ed25519 key pair in OpenSSH format. */
fun generateEd25519(comment: String): Pair<String, String> {
    val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
    val kp = gen.generateKeyPair()
    val priv = OpenSSHPrivateKeyUtil.encodePrivateKey(kp.private as Ed25519PrivateKeyParameters)
    val pub = OpenSSHPublicKeyUtil.encodePublicKey(kp.public as Ed25519PublicKeyParameters)
    val b64 = Base64.getEncoder().encodeToString(priv).chunked(70).joinToString("\n")
    val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    val pubLine = "ssh-ed25519 " + Base64.getEncoder().encodeToString(pub) + " " + comment
    return pem to pubLine
}

/**
 * Host and optional port from whatever was typed: strips a scheme ("http://", "ssh://"), a "user@",
 * a path, and an IPv6 address's brackets; a trailing ":2222" becomes the port. Bare IPv6 stays whole.
 */
fun parseHostAddress(input: String): Pair<String, Int?> {
    var s = input.trim().substringAfter("://")
    s = s.substringAfterLast('@').substringBefore('/').trim()
    if (s.startsWith("[")) {                                   // [fe80::1]:2222
        val host = s.substringAfter('[').substringBefore(']')
        return host to s.substringAfter("]:", "").toIntOrNull()?.takeIf { it in 1..65535 }
    }
    if (s.count { it == ':' } == 1) {                          // host:port (more colons = bare IPv6)
        val port = s.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 }
        if (port != null) return s.substringBefore(':') to port
    }
    return s to null
}

/** Plain-language reason an SSH connect or login failed, with what to check. */
fun sshErrorMessage(e: Throwable, host: String, port: Int): String {
    var t: Throwable? = e
    while (t != null) {
        when (t) {
            is java.net.UnknownHostException ->
                return "Can't find \"$host\". Enter just the IP address or hostname, e.g. 192.168.1.10."
            is java.net.ConnectException ->
                return "Nothing accepted a connection on $host:$port. Check the address and port, and that SSH is running."
            is java.net.SocketTimeoutException, is SshTimeoutException ->
                return "No reply from $host:$port. Check the address, and that the phone is on the same network (or VPN)."
            is java.net.NoRouteToHostException ->
                return "Can't reach $host from this phone's network."
            is net.schmizz.sshj.userauth.UserAuthException ->
                return "Connected, but the login was refused. Check the username, and that the public key is in ~/.ssh/authorized_keys (or the password is right)."
        }
        t = t.cause
    }
    return e.message ?: e.javaClass.simpleName
}

/** Unix user names we'll install a key for (also keeps them out of any shell parsing). */
val UNIX_USER = Regex("[a-z_][a-z0-9_.-]{0,31}")

/**
 * Command, run on a Proxmox node, that adds [publicKey] to [user]'s authorized_keys inside LXC [vmid]
 * (via `pct exec`), creating ~/.ssh with the right modes and owner, and not adding it twice. The key
 * works for that user only. Prints "no-sshd" if the container has no SSH server to log in to.
 * User and key go in as positional arguments, never spliced into the script.
 */
fun lxcInstallKeyCommand(vmid: Int, user: String, publicKey: String, sudo: Boolean): String {
    require(UNIX_USER.matches(user)) { "Not a valid user name: $user" }
    return (if (sudo) "sudo -n " else "") + "pct exec $vmid -- sh -c ${shq(installKeyScript())} sh ${shq(user)} ${shq(publicKey.trim())}"
}

/** The script [lxcInstallKeyCommand] runs inside the container; `sh -c <script> sh <user> <key>`. */
internal fun installKeyScript(passwd: String = "/etc/passwd") = "u=\"\$1\"; k=\"\$2\"; " +
    "h=\$(awk -F: -v u=\"\$u\" '\$1==u {print \$6}' $passwd); " +
    "[ -n \"\$h\" ] || { echo \"No user \$u in this container\" >&2; exit 3; }; " +
    "mkdir -p \"\$h/.ssh\" && chmod 700 \"\$h/.ssh\" && touch \"\$h/.ssh/authorized_keys\" && chmod 600 \"\$h/.ssh/authorized_keys\" && " +
    "{ grep -qxF \"\$k\" \"\$h/.ssh/authorized_keys\" || printf '%s\\n' \"\$k\" >> \"\$h/.ssh/authorized_keys\"; } && " +
    "chown -R \"\$u\": \"\$h/.ssh\" || exit 4; " +
    "command -v sshd >/dev/null 2>&1 || [ -x /usr/sbin/sshd ] || echo no-sshd"

/** Quote a value for a POSIX shell. */
fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
