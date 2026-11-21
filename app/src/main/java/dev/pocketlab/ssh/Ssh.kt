package dev.pocketlab.ssh

import dev.pocketlab.data.SshAuthType
import dev.pocketlab.data.SshHost
import dev.pocketlab.data.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.Config
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
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

fun hostKeyFingerprint(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val sha = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha)
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

object SshPool {
    private val clients = ConcurrentHashMap<String, SSHClient>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Opens a fresh, authenticated client. Throws [HostKeyException] when the key isn't pinned. */
    fun open(host: SshHost, keys: List<SshKey>, config: Config = HardenedSshConfig()): SSHClient {
        SlimCryptoProvider.install()
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
                SshAuthType.PASSWORD -> c.authPassword(host.username, host.password.orEmpty())
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
        c.connection.keepAlive.keepAliveInterval = 20
        return c
    }

    suspend fun client(host: SshHost, keys: List<SshKey>): SSHClient = withContext(Dispatchers.IO) {
        locks.getOrPut(host.id) { Mutex() }.withLock {
            clients[host.id]?.let { if (it.isConnected && it.isAuthenticated) return@withLock it }
            clients.remove(host.id)?.let { runCatching { it.close() } }
            open(host, keys).also { clients[host.id] = it }
        }
    }

    fun drop(hostId: String) { clients.remove(hostId)?.let { runCatching { it.close() } } }
    fun dropAll() { clients.keys.toList().forEach(::drop) }

    suspend fun exec(host: SshHost, keys: List<SshKey>, command: String, timeoutSec: Long = 30): ExecResult =
        withContext(Dispatchers.IO) {
            val attempt: suspend () -> ExecResult = {
                val c = client(host, keys)
                c.startSession().use { s ->
                    val cmd = s.exec(command)
                    coroutineScope {
                        val err = async(Dispatchers.IO) { cmd.errorStream.readBytes().decodeToString() }
                        val out = cmd.inputStream.readBytes().decodeToString()
                        cmd.join(timeoutSec, TimeUnit.SECONDS)
                        ExecResult(cmd.exitStatus, out, err.await())
                    }
                }
            }
            try { attempt() } catch (e: HostKeyException) { throw e } catch (e: Exception) {
                // A pooled connection may have died (phone slept, wifi changed): reconnect once.
                drop(host.id)
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
                cmd.inputStream.bufferedReader().forEachLine { trySend(it) }
                close()
            } catch (e: Exception) { close(e) }
        }.apply { isDaemon = true; start() }
        awaitClose {
            runCatching { cmd.close() }
            runCatching { session.close() }
            reader.interrupt()
        }
    }.flowOn(Dispatchers.IO)
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

/** Quote a value for a POSIX shell. */
fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
