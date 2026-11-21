package dev.pocketlab

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
    @Test fun guest() = shoot("3-guest", open = Routes.guest("c1", "pve2", "qemu", 202))
    @Test fun container() = shoot("4-container", open = Routes.container("h1", "9b24f266c05f00"))
    @Test fun hosts() = shoot("5-hosts", start = "hosts")
    @Test fun terminalTab() = shoot("6-terminal", start = "terminal")
    @Test fun settings() = shoot("7-settings", start = "settings")
    @Test fun appearance() = shoot("8-appearance", open = "appearance")
    @Test fun editCluster() = shoot("9-add-cluster", open = Routes.editCluster())
    @Test fun editHost() = shoot("10-add-host", open = Routes.editHost())
    @Test fun keys() = shoot("11-keys", open = "keys")
    @Test fun logs() = shoot("15-logs", open = Routes.logs("h1", "9b24f266c05f00"))
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
