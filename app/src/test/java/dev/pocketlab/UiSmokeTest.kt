package dev.pocketlab

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithText
import dev.pocketlab.data.Appearance
import dev.pocketlab.data.AppConfig
import dev.pocketlab.data.ClusterState
import dev.pocketlab.data.HostState
import dev.pocketlab.data.Lab
import dev.pocketlab.data.LabSnapshot
import dev.pocketlab.data.ProxmoxCluster
import dev.pocketlab.data.SecureStore
import dev.pocketlab.data.SshHost
import dev.pocketlab.data.SshKey
import dev.pocketlab.data.ThemeMode
import dev.pocketlab.docker.Container
import dev.pocketlab.proxmox.GuestType
import dev.pocketlab.proxmox.PveGuest
import dev.pocketlab.proxmox.PveNode
import dev.pocketlab.ssh.HostStats
import dev.pocketlab.ui.PocketlabRoot
import dev.pocketlab.ui.Routes
import dev.pocketlab.ui.theme.PocketlabTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** Renders every main screen with realistic data. Catches runtime crashes; writes PNGs for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h860dp-xhdpi")
class UiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var lab: Lab
    private val outDir = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }

    private val cluster = ProxmoxCluster(id = "c1", name = "homelab", baseUrl = "https://127.0.0.1:1", tokenId = "pocketlab@pve!phone", tokenSecret = "x")
    private val key = SshKey(id = "k1", name = "phone", privateKey = "x", publicKey = "ssh-ed25519 AAAA phone")
    private val dockerHost = SshHost(id = "h1", name = "docker-vm", address = "127.0.0.1", port = 1, username = "neil", keyId = "k1")
    private val pveHost = SshHost(id = "h2", name = "pve2", address = "127.0.0.1", port = 1, username = "root", keyId = "k1", proxmoxNode = "pve2", dockerEnabled = false)

    @Before fun setUp() {
        val f = File.createTempFile("cfg", ".enc").apply { delete() }
        val store = SecureStore(f) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        store.update { AppConfig(listOf(cluster), listOf(dockerHost, pveHost), listOf(key), Appearance(mode = ThemeMode.DARK)) }
        lab = Lab(store)
        PocketlabApp.labOverride = lab
        fun g(node: String, id: Int, name: String, type: GuestType, status: String, mem: Double, cpu: Double = 0.02) =
            PveGuest("c1", node, type, id, name, status, cpu, 2, (2147483648 * mem).toLong(), 2147483648, 1209600, listOf("lab"), false)
        lab.setSnapshotForTest(LabSnapshot(
            clusters = mapOf("c1" to ClusterState(
                nodes = listOf(
                    PveNode("c1", "pve1", true, 0.01, 8, 5_600_000_000, 16_000_000_000, 900000),
                    PveNode("c1", "pve2", true, 0.04, 8, 7_700_000_000, 16_000_000_000, 900000),
                    PveNode("c1", "pve3", false, 0.0, 8, 0, 16_000_000_000, 0),
                ),
                guests = listOf(
                    g("pve1", 201, "linux", GuestType.QEMU, "running", 0.4),
                    g("pve1", 204, "snaptest", GuestType.QEMU, "stopped", 0.0, 0.0),
                    g("pve2", 202, "docker-vm", GuestType.QEMU, "running", 0.63),
                    g("pve2", 301, "pihole", GuestType.LXC, "running", 0.1),
                ),
                loaded = true,
            )),
            hosts = mapOf(
                "h1" to HostState(HostStats(0.03, 450_000_000, 2_000_000_000, 1_200_000, "0.03 0.05 0.01"), listOf(
                    Container("h1", "9b24f266c05f00", "lab-web", "nginx:stable-alpine", "running", "Up 4 days (healthy)", "0.0.0.0:8080->80/tcp", "lab", "web"),
                    Container("h1", "aaaabbbbcccc00", "lab-logger", "busybox", "running", "Up 2 weeks", "", "lab", "logger"),
                    Container("h1", "ddddeeeeffff00", "backup", "restic/restic", "exited", "Exited (1) 2 hours ago", "", null, null),
                ), loaded = true),
                "h2" to HostState(HostStats(0.04, 7_700_000_000, 16_000_000_000, 900000, "0.2 0.1 0.1"), loaded = true),
            ),
            updatedAt = System.currentTimeMillis(),
        ))
    }

    private fun shoot(name: String, start: String = "overview", open: String? = null) {
        compose.setContent {
            val cfg by lab.store.config.collectAsStateWithLifecycle()
            PocketlabTheme(cfg.appearance) { PocketlabRoot(startRoute = start, poll = false, openRoute = open) }
        }
        snap(name)
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun overview() {
        shoot("1-overview")
        compose.onNodeWithText("Needs attention").assertExists()
        // pve3 offline + exited(1) container should be flagged.
        assertTrue(compose.onAllNodesWithText("Node offline").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun workloads() = shoot("2-workloads", start = "workloads")

    /** Tabs always open their main page, and the highlight follows the tab you're in. */
    @Test fun tabNavigation() {
        fun tab(name: String) = compose.onNodeWithContentDescription(name)
        shoot("18a-nav-workloads", start = "workloads")
        compose.onAllNodesWithText("linux")[0].performClick()                 // into a guest
        compose.waitForIdle()
        tab("Workloads").assertIsSelected()
        tab("Settings").performClick(); compose.waitForIdle()
        tab("Settings").assertIsSelected()
        tab("Workloads").performClick(); compose.waitForIdle()               // back to the list, not the guest
        compose.onNodeWithText("Search by name", substring = true).assertExists()
        tab("Workloads").assertIsSelected()
        tab("Overview").performClick(); compose.waitForIdle()                // Overview, not Hosts
        compose.onNodeWithText("Needs attention").assertExists()
        tab("Overview").assertIsSelected()
        tab("Hosts").assertIsNotSelected()
    }
    @Test fun guest() {
        // A fake Proxmox API (self-signed cert, pinned) so the detail screen shows real config, graphs and snapshots.
        val cert = okhttp3.tls.HeldCertificate.Builder().commonName("pve2").build()
        val server = mockwebserver3.MockWebServer()
        server.useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        val now = System.currentTimeMillis() / 1000
        val rrd = (0 until 60).joinToString(",") { i ->
            val cpu = 0.02 + 0.015 * kotlin.math.sin(i / 6.0) + (if (i in 38..42) 0.2 else 0.0)
            """{"time":${now - (60 - i) * 60},"cpu":$cpu,"mem":${1.2e9 + i * 4e6},"maxmem":2147483648}"""
        }
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                val path = request.url.encodedPath
                val body = when {
                    path.endsWith("/config") -> """{"data":{"cores":2,"sockets":1,"cpu":"host","memory":"2048","balloon":"0","ostype":"l26",
                        "scsi0":"lab-nvme:vm-202-disk-0,size=32G","net0":"virtio=BC:24:11:00:00:01,bridge=vmbr0,firewall=1",
                        "serial0":"socket","agent":"1","onboot":"1","boot":"order=scsi0"}}"""
                    path.endsWith("/rrddata") -> """{"data":[$rrd]}"""
                    path.endsWith("/snapshot") -> """{"data":[{"name":"current","running":1},
                        {"name":"before_upgrade","description":"Debian 13 point release","snaptime":${now - 86400 * 3}},
                        {"name":"fresh_install","snaptime":${now - 86400 * 40}}]}"""
                    path.endsWith("/status/current") -> """{"data":{"qmpstatus":"running"}}"""
                    else -> return mockwebserver3.MockResponse.Builder().code(404).build()
                }
                return mockwebserver3.MockResponse.Builder().body(body).build()
            }
        }
        server.start()
        val pin = java.security.MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString(":") { "%02X".format(it) }
        lab.store.update { c -> c.copy(clusters = listOf(cluster.copy(baseUrl = "https://127.0.0.1:${server.port}", pinnedCertSha256 = pin))) }
        try {
            compose.setContent {
                val cfg by lab.store.config.collectAsStateWithLifecycle()
                PocketlabTheme(cfg.appearance) { PocketlabRoot(poll = false, openRoute = Routes.guest("c1", "pve2", "qemu", 202)) }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("2 cores", substring = true).fetchSemanticsNodes().isNotEmpty() }
            // Graph history loads separately from the config; both charts must show data.
            compose.waitUntil(10_000) { compose.onAllNodesWithText("tap for values", substring = true).fetchSemanticsNodes().size == 2 }
            compose.onNodeWithText("Failed", substring = true).assertDoesNotExist()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(outDir, "3-guest.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { server.close() }
    }
    @Test fun container() = shoot("4-container", open = Routes.container("h1", "9b24f266c05f00"))
    @Test fun hosts() = shoot("5-hosts", start = "hosts")
    @Test fun terminalTab() = shoot("6-terminal", start = "terminal")
    @Test fun settings() = shoot("7-settings", start = "settings")

    /** "Close all connections" asks first instead of acting on tap. */
    @Test fun closeConnectionsAsksFirst() {
        shoot("7a-settings", start = "settings")
        compose.onNodeWithText("Close all connections").performClick()
        snap("7b-settings-close-confirm")
        compose.onNodeWithText("Close all connections?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Close all connections?").assertDoesNotExist()
    }

    /** Token sees the node but no guests (missing VM.Audit on /vms): say so and how to fix it. */
    @Test fun noGuestsVisibleHint() {
        val s = lab.snapshot.value
        lab.setSnapshotForTest(s.copy(clusters = s.clusters.mapValues { (_, c) -> c.copy(guests = emptyList()) }))
        shoot("2b-workloads-no-guests", start = "workloads")
        compose.onNodeWithText("No VMs or containers visible", substring = true).assertExists()
    }
    @Test fun appearance() = shoot("8-appearance", open = "appearance")
    /** Small phone: the accent swatches must stay round, not get squeezed. */
    @Config(qualifiers = "w320dp-h700dp-xhdpi")
    @Test fun appearanceNarrow() {
        shoot("8b-appearance-narrow", open = "appearance")
        compose.onNodeWithText("Terminal text", substring = true).performScrollTo()  // past the accents, clear of the tab bar
        snap("8c-appearance-narrow-accents")
    }
    @Test fun editCluster() = shoot("9-add-cluster", open = Routes.editCluster())
    @Test fun editHost() = shoot("10-add-host", open = Routes.editHost())
    @Test fun keys() = shoot("11-keys", open = "keys")
    @Test fun logs() = shoot("15-logs", open = Routes.logs("h1", "9b24f266c05f00"))
    @Test fun secretRevealAndCopyCommands() {
        shoot("16-edit-cluster", open = Routes.editCluster("c1"))
        compose.onNodeWithContentDescription("Show API token secret").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide API token secret").assertExists()
        // Token name drives both the token ID and the copied commands; shell-unsafe characters are dropped.
        compose.onNodeWithText("Token name").performTextReplacement("tab let;rm -rf")
        compose.waitForIdle()
        compose.onNodeWithText("API token ID: pocketlab@pve!tabletrm-rf", substring = true).assertExists()
        compose.onNodeWithText("First time?", substring = true).performScrollTo().performClick()
        compose.waitForIdle()
        snap("16b-edit-cluster-setup-open")
        compose.onNodeWithText("Copy commands", substring = true).performScrollTo().performClick()
        compose.waitForIdle()
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val clip = (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        val copied = clip?.getItemAt(0)?.text?.toString()
        org.junit.Assert.assertEquals(dev.pocketlab.ui.screens.pveTokenSetup("pocketlab@pve", "tabletrm-rf"), copied)
        assertTrue(copied!!.endsWith("pveum user token add pocketlab@pve tabletrm-rf --privsep 0"))
    }

    @Test fun customTokenId() {
        shoot("17a-edit-cluster", open = Routes.editCluster("c1"))
        compose.onNodeWithText("Use a different user or token").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("API token ID").performTextReplacement("root@pam!tab let;")
        compose.onNodeWithText("First time?", substring = true).performScrollTo().performClick()
        snap("17-edit-cluster-custom")
        compose.onNodeWithText("Copy commands", substring = true).performScrollTo().performClick()
        compose.waitForIdle()
        val clip = (org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()!!
        // Username in every line, token name in the last; no "user add" for a @pam user.
        org.junit.Assert.assertEquals(dev.pocketlab.ui.screens.pveTokenSetup("root@pam", "tablet"), clip)
        assertTrue(clip.endsWith("pveum user token add root@pam tablet --privsep 0"))
        assertTrue(clip.contains("--users root@pam --roles PVEAuditor"))
        assertTrue(!clip.contains("user add"))
    }

    @Test fun node() = shoot("12-node", open = Routes.node("c1", "pve2"))

    @Test fun guestPowerSheetAndConfirm() {
        shoot("13-guest-power", open = Routes.guest("c1", "pve2", "qemu", 202))
        compose.onNodeWithText("Power and actions").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stop").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stop docker-vm").assertExists()
    }

    @Test fun lightTheme() {
        lab.store.update { it.copy(appearance = Appearance(mode = ThemeMode.LIGHT, theme = "Catppuccin", accent = "Purple")) }
        shoot("14-light-catppuccin")
    }

    @Test fun emptyState() {
        lab.store.update { AppConfig() }
        lab.setSnapshotForTest(LabSnapshot())
        shoot("0-first-run")
        compose.onNodeWithText("Connect your lab").assertExists()
    }
}
