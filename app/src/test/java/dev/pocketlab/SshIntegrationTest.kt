package dev.pocketlab

import dev.pocketlab.data.SshAuthType
import dev.pocketlab.data.SshHost
import dev.pocketlab.data.SshKey
import dev.pocketlab.docker.DockerClient
import dev.pocketlab.ssh.HardenedSshConfig
import dev.pocketlab.ssh.HostKeyException
import dev.pocketlab.ssh.HostStatsProbe
import dev.pocketlab.ssh.SlimCryptoProvider
import dev.pocketlab.ssh.SshPool
import dev.pocketlab.ssh.generateEd25519
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.Security

/**
 * Runs against a real OpenSSH server. Set POCKETLAB_TEST_SSHD to a directory holding
 * `authorized_keys`, `hostkey{,_rsa,_ecdsa}.pub` and `userkeys/`, with sshd on 127.0.0.1:2222
 * and a fake `docker` on its PATH. Only the slim provider is installed (no full BouncyCastle),
 * exactly as in the app.
 */
class SshIntegrationTest {
    private val dir = System.getenv("POCKETLAB_TEST_SSHD")?.let(::File)
    private val user = System.getProperty("user.name")

    @Before fun setUp() {
        assumeTrue("POCKETLAB_TEST_SSHD not set", dir != null)
        SlimCryptoProvider.install()
        assertNull("full BouncyCastle must not be registered", Security.getProvider("BC"))
    }

    private fun fp(pub: String) = ProcessBuilder("ssh-keygen", "-lf", File(dir, pub).path).start()
        .inputStream.bufferedReader().readText().split(" ")[1]

    private fun authorize(vararg pubs: String) = File(dir, "authorized_keys").writeText(pubs.joinToString("\n") + "\n")

    private fun host(key: SshKey, fp: String? = null) =
        SshHost(name = "local", address = "127.0.0.1", port = 2222, username = user, authType = SshAuthType.KEY, keyId = key.id, hostKeyFingerprint = fp)

    private fun config(kex: String? = null, cipher: String? = null, hostKeyAlg: String? = null) = HardenedSshConfig().apply {
        if (kex != null) keyExchangeFactories = keyExchangeFactories.filter { it.name == kex || it.name.startsWith("ext-info") }
        if (cipher != null) cipherFactories = cipherFactories.filter { it.name == cipher }
        // Forced host-key type first; ssh-ed25519 kept after it so the Ed25519 *user* key can still sign.
        if (hostKeyAlg != null) keyAlgorithms = keyAlgorithms.filter { it.name == hostKeyAlg } +
            keyAlgorithms.filter { it.name == "ssh-ed25519" && it.name != hostKeyAlg }
    }

    private fun newKey(): SshKey {
        val (priv, pub) = generateEd25519("t")
        authorize(pub)
        return SshKey(name = "t", privateKey = priv, publicKey = pub)
    }

    @Test fun everyKexAndCipherWorks() {
        val key = newKey()
        val h = host(key, fp("hostkey.pub"))
        val ciphers = config().cipherFactories.map { it.name }
        // chacha20-poly1305 survives sshj's availability probe only if the slim provider works.
        assertTrue("ciphers: $ciphers", "chacha20-poly1305@openssh.com" in ciphers && "aes256-gcm@openssh.com" in ciphers)
        val kexes = listOf(
            "curve25519-sha256", "curve25519-sha256@libssh.org", "ecdh-sha2-nistp256", "ecdh-sha2-nistp384",
            "ecdh-sha2-nistp521", "diffie-hellman-group16-sha512", "diffie-hellman-group14-sha256",
            "diffie-hellman-group-exchange-sha256",
        )
        for (kex in kexes) {
            val c = runCatching { SshPool.open(h, listOf(key), config(kex = kex)) }.getOrElse { throw AssertionError("kex $kex", it) }
            c.use {
                it.startSession().use { s -> assertEquals(kex, "ok\n", s.exec("echo ok").inputStream.readBytes().decodeToString()) }
            }
        }
        for (cipher in listOf("chacha20-poly1305@openssh.com", "aes256-gcm@openssh.com", "aes128-gcm@openssh.com", "aes256-ctr", "aes128-ctr")) {
            val c = runCatching { SshPool.open(h, listOf(key), config(cipher = cipher)) }.getOrElse { throw AssertionError("cipher $cipher", it) }
            c.use {
                it.startSession().use { s ->
                    val out = s.exec("head -c 200000 /dev/zero | wc -c").inputStream.readBytes().decodeToString().trim()
                    assertEquals(cipher, "200000", out)
                }
            }
        }
    }

    @Test fun legacyAlgorithmsAreNotOffered() {
        val c = config()
        val kex = c.keyExchangeFactories.map { it.name }
        val macs = c.macFactories.map { it.name }
        val keys = c.keyAlgorithms.map { it.name }
        assertTrue(kex.none { it == "diffie-hellman-group1-sha1" || it == "diffie-hellman-group-exchange-sha1" })
        assertTrue(macs.none { "md5" in it || "ripemd" in it || it.endsWith("-96") })
        assertTrue(keys.none { it == "ssh-dss" })
        assertEquals(HardenedSshConfig.PREFERRED_CIPHERS, c.cipherFactories.map { it.name })
        assertEquals("ssh-rsa", keys.last())
    }

    @Test fun everyHostKeyTypeIsPinnedCorrectly() {
        val key = newKey()
        for ((alg, file) in listOf("ssh-ed25519" to "hostkey.pub", "rsa-sha2-512" to "hostkey_rsa.pub",
            "rsa-sha2-256" to "hostkey_rsa.pub", "ecdsa-sha2-nistp256" to "hostkey_ecdsa.pub")) {
            val e = runCatching { SshPool.open(host(key), listOf(key), config(hostKeyAlg = alg)) }.exceptionOrNull()
            assertTrue("$alg: $e", e is HostKeyException)
            assertEquals(alg, fp(file), (e as HostKeyException).fingerprint)
            SshPool.open(host(key, e.fingerprint), listOf(key), config(hostKeyAlg = alg)).close()
        }
    }

    @Test fun importedSshKeygenKeysAuthenticate() {
        val ud = File(dir, "userkeys")
        val cases = listOf(
            Triple("ed_pass", "hunter2", "OpenSSH Ed25519, passphrase (bcrypt KDF)"),
            Triple("rsa_openssh_pass", "pw", "OpenSSH RSA, passphrase"),
            Triple("rsa_pem", null, "PEM RSA (BEGIN RSA PRIVATE KEY)"),
            Triple("ecdsa", null, "OpenSSH ECDSA P-384"),
        )
        authorize(*cases.map { File(ud, it.first + ".pub").readText().trim() }.toTypedArray())
        for ((file, pass, label) in cases) {
            val key = SshKey(name = label, privateKey = File(ud, file).readText(), passphrase = pass)
            val r = runCatching { runBlocking { SshPool.exec(host(key, fp("hostkey.pub")), listOf(key), "echo $file") } }
                .getOrElse { throw AssertionError(label, it) }
            assertEquals(label, "$file\n", r.stdout)
            SshPool.dropAll()
        }
    }

    @Test fun endToEnd() = runBlocking {
        val key = newKey()
        assertTrue(key.publicKey!!.startsWith("ssh-ed25519 "))
        val keys = listOf(key)
        var host = host(key)

        // Unknown host key is refused, with the fingerprint OpenSSH itself reports.
        val first = runCatching { SshPool.open(host, keys) }.exceptionOrNull()
        assertTrue("expected HostKeyException, got $first", first is HostKeyException)
        first as HostKeyException
        assertEquals(fp("hostkey.pub"), first.fingerprint)
        assertEquals(false, first.mismatch)

        // A wrong pinned key is a mismatch (MITM warning), not silently accepted.
        val wrong = runCatching { SshPool.open(host.copy(hostKeyFingerprint = "SHA256:AAAA"), keys) }.exceptionOrNull()
        assertTrue(wrong is HostKeyException && wrong.mismatch)

        host = host.copy(hostKeyFingerprint = first.fingerprint)
        val r = SshPool.exec(host, keys, "echo hello; echo oops >&2; exit 3")
        assertEquals("hello\n", r.stdout); assertEquals("oops\n", r.stderr); assertEquals(3, r.exit)

        val stats = HostStatsProbe.probe(host, keys)
        assertTrue(stats.memTotal > 0 && stats.cpu in 0.0..1.0)

        val docker = DockerClient(host, keys)
        val cs = docker.list()
        assertEquals(2, cs.size)
        val web = cs.first { it.name == "lab-web" }
        assertEquals("healthy", web.health); assertEquals("lab", web.composeProject); assertEquals("web", web.composeService)
        docker.action(web.id, "restart")
        val logs = docker.logs(web.id).take(2).toList()
        assertTrue(logs[0].contains("404"))

        SshPool.dropAll()
        assertNotNull(SshPool.exec(host, keys, "true").exit)
        SshPool.dropAll()
    }
}
