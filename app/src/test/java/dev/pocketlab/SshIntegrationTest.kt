package dev.pocketlab

import dev.pocketlab.data.SshAuthType
import dev.pocketlab.data.SshHost
import dev.pocketlab.data.SshKey
import dev.pocketlab.docker.DockerClient
import dev.pocketlab.ssh.HostKeyException
import dev.pocketlab.ssh.HostStatsProbe
import dev.pocketlab.ssh.SshPool
import dev.pocketlab.ssh.generateEd25519
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.Security

/**
 * Runs against a real OpenSSH server. Set POCKETLAB_TEST_SSHD to a directory holding
 * `authorized_keys` and `hostkey.pub`, with sshd on 127.0.0.1:2222 and a fake `docker` on its PATH.
 */
class SshIntegrationTest {
    private val dir = System.getenv("POCKETLAB_TEST_SSHD")?.let(::File)

    init { if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider()) }

    @Test fun endToEnd() = runBlocking {
        assumeTrue("POCKETLAB_TEST_SSHD not set", dir != null)
        val (priv, pub) = generateEd25519("pocketlab-test")
        assertTrue(pub.startsWith("ssh-ed25519 "))
        File(dir, "authorized_keys").writeText(pub + "\n")
        val key = SshKey(name = "t", privateKey = priv, publicKey = pub)
        val keys = listOf(key)
        var host = SshHost(name = "local", address = "127.0.0.1", port = 2222, username = System.getProperty("user.name"),
            authType = SshAuthType.KEY, keyId = key.id)

        // 1. Unknown host key is refused, with the fingerprint OpenSSH itself reports.
        val expectedFp = ProcessBuilder("ssh-keygen", "-lf", File(dir, "hostkey.pub").path).start()
            .inputStream.bufferedReader().readText().split(" ")[1]
        val first = runCatching { SshPool.open(host, keys) }.exceptionOrNull()
        assertTrue("expected HostKeyException, got $first", first is HostKeyException)
        first as HostKeyException
        assertEquals(expectedFp, first.fingerprint)
        assertEquals(false, first.mismatch)

        // 2. A wrong pinned key is a mismatch (MITM warning), not silently accepted.
        val wrong = runCatching { SshPool.open(host.copy(hostKeyFingerprint = "SHA256:AAAA"), keys) }.exceptionOrNull()
        assertTrue(wrong is HostKeyException && wrong.mismatch)

        // 3. Pinned correctly: generated Ed25519 key authenticates and exec works.
        host = host.copy(hostKeyFingerprint = first.fingerprint)
        val r = SshPool.exec(host, keys, "echo hello; echo oops >&2; exit 3")
        assertEquals("hello\n", r.stdout); assertEquals("oops\n", r.stderr); assertEquals(3, r.exit)

        // 4. Host stats probe parses /proc.
        val stats = HostStatsProbe.probe(host, keys)
        assertTrue(stats.memTotal > 0 && stats.cpu in 0.0..1.0)

        // 5. Docker over SSH: parsing, health, compose labels, actions, log streaming.
        val docker = DockerClient(host, keys)
        val cs = docker.list()
        assertEquals(2, cs.size)
        val web = cs.first { it.name == "lab-web" }
        assertEquals("healthy", web.health); assertEquals("lab", web.composeProject); assertEquals("web", web.composeService)
        assertTrue(web.running)
        docker.action(web.id, "restart")
        val logs = docker.logs(web.id).take(2).toList()
        assertTrue(logs[0].contains("404"))

        // 6. Dropped pooled connection is transparently re-established.
        SshPool.dropAll()
        assertNotNull(SshPool.exec(host, keys, "true").exit)
        SshPool.dropAll()
    }
}
