package dev.pocketlab

import dev.pocketlab.data.AppConfig
import dev.pocketlab.data.Lab
import dev.pocketlab.data.ProxmoxCluster
import dev.pocketlab.data.SecureStore
import dev.pocketlab.data.SshHost
import dev.pocketlab.ssh.HostStatsProbe
import dev.pocketlab.ui.screens.hostForNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStoreException
import javax.crypto.spec.SecretKeySpec

class ReviewFixesTest {
    private val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
    private val dir = File.createTempFile("store", "").apply { delete(); mkdirs() }
    private val file = File(dir, "config.enc")

    @After fun tearDown() { dir.deleteRecursively(); PocketlabApp.labOverride = null }

    private fun seeded(): AppConfig {
        val cfg = AppConfig(clusters = listOf(ProxmoxCluster(name = "lab", baseUrl = "https://x:8006", tokenId = "a@pve!b", tokenSecret = "s")))
        SecureStore(file) { key }.update { cfg }
        return cfg
    }

    @Test fun roundTrip() {
        val cfg = seeded()
        val s = SecureStore(file) { key }
        assertEquals(cfg, s.config.value)
        assertNull(s.problem.value)
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("a@pve!b")) // never plaintext on disk
    }

    @Test fun tamperedFileIsSetAsideNotSilentlyLost() {
        seeded()
        val bytes = file.readBytes(); bytes[bytes.size - 1] = (bytes.last() + 1).toByte(); file.writeBytes(bytes)
        val s = SecureStore(file) { key }
        assertEquals(AppConfig(), s.config.value)
        assertNotNull(s.problem.value)
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("config.enc.unreadable-") })
        // A second failure must not overwrite the first backup.
        s.update { it.copy(hosts = listOf(SshHost(name = "n", address = "a", username = "u"))) }
        val b2 = file.readBytes(); b2[b2.size - 1] = (b2.last() + 1).toByte(); file.writeBytes(b2)
        Thread.sleep(5)
        SecureStore(file) { key }
        assertEquals(2, dir.listFiles()!!.count { it.name.startsWith("config.enc.unreadable-") })
    }

    @Test fun transientKeystoreErrorKeepsFileAndRefusesWrites() {
        seeded()
        val before = file.readBytes()
        val s = SecureStore(file) { throw KeyStoreException("keystore not ready") }
        assertNotNull(s.problem.value)
        s.update { AppConfig() } // must be ignored, not overwrite recoverable settings
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(0, dir.listFiles()!!.count { it.name.contains("unreadable") })
        // Next launch with a working keystore gets everything back.
        assertEquals("lab", SecureStore(file) { key }.config.value.clusters.single().name)
    }

    @Test fun statsParserToleratesMissingProc() {
        val s = HostStatsProbe.parse(listOf("garbage", "", "uptime ", "load "))
        assertNull(s.cpu); assertNull(s.memTotal); assertNull(s.uptimeSec); assertNull(s.load); assertNull(s.memFraction)
        val ok = HostStatsProbe.parse(listOf("cpu  10 0 10 80 0 0 0", "cpu  20 0 20 160 0 0 0", "MemTotal: 1000 kB", "MemAvailable: 250 kB", "uptime 123.4", "load 0.1 0.2 0.3"))
        assertEquals(0.2, ok.cpu!!, 1e-9); assertEquals(0.75, ok.memFraction!!, 1e-9); assertEquals(123L, ok.uptimeSec); assertEquals("0.1 0.2 0.3", ok.load)
    }

    @Test fun nodeConsoleNeverGoesToAnotherClustersNode() {
        val store = SecureStore(file) { key }
        val a = ProxmoxCluster(id = "A", name = "home", baseUrl = "https://a", tokenId = "x@pve!t", tokenSecret = "s")
        val b = ProxmoxCluster(id = "B", name = "office", baseUrl = "https://b", tokenId = "x@pve!t", tokenSecret = "s")
        val hostA = SshHost(id = "ha", name = "pve", address = "10.0.0.1", username = "root", proxmoxNode = "pve", proxmoxClusterId = "A")
        val unlinked = SshHost(id = "hx", name = "pve", address = "10.0.0.9", username = "root")
        store.update { AppConfig(clusters = listOf(a, b), hosts = listOf(hostA, unlinked)) }
        PocketlabApp.labOverride = Lab(store)
        assertEquals("ha", hostForNode("A", "pve")?.id)
        assertNull("ambiguous with two clusters: must not guess", hostForNode("B", "pve"))
        // With a single cluster, the name match is unambiguous and still works.
        store.update { it.copy(clusters = listOf(a), hosts = listOf(unlinked)) }
        assertEquals("hx", hostForNode("A", "pve")?.id)
    }
}
