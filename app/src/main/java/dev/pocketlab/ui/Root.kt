package dev.pocketlab.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pocketlab.PocketlabApp
import dev.pocketlab.ui.screens.AppearanceScreen
import dev.pocketlab.ui.screens.ContainerScreen
import dev.pocketlab.ui.screens.EditClusterScreen
import dev.pocketlab.ui.screens.EditHostScreen
import dev.pocketlab.ui.screens.GuestScreen
import dev.pocketlab.ui.screens.HostScreen
import dev.pocketlab.ui.screens.HostsScreen
import dev.pocketlab.ui.screens.KeysScreen
import dev.pocketlab.ui.screens.LogsScreen
import dev.pocketlab.ui.screens.NodeScreen
import dev.pocketlab.ui.screens.OverviewScreen
import dev.pocketlab.ui.screens.SettingsScreen
import dev.pocketlab.ui.screens.TermScreen
import dev.pocketlab.ui.screens.TerminalTab
import dev.pocketlab.ui.screens.WorkloadsScreen
import dev.pocketlab.ui.theme.LocalTones
import kotlinx.coroutines.delay

val LocalNav = staticCompositionLocalOf<NavHostController> { error("no nav") }
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("no snackbar") }
/** False in UI tests (PocketlabRoot(poll = false)): screens fetch once instead of polling forever. */
val LocalLivePolling = staticCompositionLocalOf { true }

object Routes {
    fun enc(s: String): String = Uri.encode(s)
    fun guest(clusterId: String, node: String, type: String, vmid: Int) = "guest/${enc(clusterId)}/${enc(node)}/$type/$vmid"
    fun node(clusterId: String, node: String) = "node/${enc(clusterId)}/${enc(node)}"
    fun host(id: String) = "host/${enc(id)}"
    fun container(hostId: String, id: String) = "container/${enc(hostId)}/${enc(id)}"
    fun logs(hostId: String, id: String) = "logs/${enc(hostId)}/${enc(id)}"
    fun term(id: String) = "term/${enc(id)}"
    fun editHost(id: String? = null, name: String = "", address: String = "", user: String = "", keyId: String = "") =
        "edit-host?id=${id?.let(::enc) ?: ""}&name=${enc(name)}&addr=${enc(address)}&user=${enc(user)}&key=${enc(keyId)}"
    fun editCluster(id: String? = null) = "edit-cluster?id=${id?.let(::enc) ?: ""}"
    fun workloads(filter: String = "All") = "workloads?filter=$filter"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("overview", "Overview", Icons.Outlined.Dashboard),
    Tab("hosts", "Hosts", Icons.Outlined.Dns),
    Tab("workloads", "Workloads", Icons.Outlined.ViewInAr),
    Tab("terminal", "Terminal", Icons.Outlined.Terminal),
    Tab("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun PocketlabRoot(startRoute: String = "overview", poll: Boolean = true, openRoute: String? = null) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val lab = PocketlabApp.lab
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Poll while the app is visible; stop in the background (no battery drain, no idle SSH traffic).
    if (poll) LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                lab.refresh()
                delay(15_000)
            }
        }
    }

    LaunchedEffect(Unit) { (openRoute ?: startRoute.takeIf { it != "overview" })?.let { nav.navigate(it) } }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val showBar = !route.startsWith("term/") && !route.startsWith("edit-") && route != "keys"
    // The highlighted tab is the one you're in: the last tab root you visited. Detail screens keep it,
    // so a VM opened from Overview leaves Overview highlighted instead of jumping to Workloads.
    // Held here (not in the bar) so it survives screens that hide the bar.
    var lastTab by remember { mutableStateOf("overview") }
    val tabHere = tabs.firstOrNull { route == it.route || route.startsWith(it.route + "?") }?.route
    val currentTab = tabHere ?: lastTab
    SideEffect { if (tabHere != null) lastTab = tabHere }

    CompositionLocalProvider(LocalNav provides nav, LocalSnackbar provides snackbar, LocalLivePolling provides poll) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                NavHost(nav, startDestination = "overview", modifier = Modifier.weight(1f)) {
                    composable("overview") { OverviewScreen() }
                    composable("hosts") { HostsScreen() }
                    composable("workloads?filter={filter}") { OverviewArg(it.arguments?.getString("filter")) }
                    composable("workloads") { WorkloadsScreen("All") }
                    composable("terminal") { TerminalTab() }
                    composable("settings") { SettingsScreen() }
                    composable("appearance") { AppearanceScreen() }
                    composable("keys") { KeysScreen() }
                    composable("guide") { dev.pocketlab.ui.screens.GuideScreen() }
                    composable("guest/{c}/{n}/{t}/{v}") {
                        val a = it.arguments!!
                        GuestScreen(a.getString("c")!!, a.getString("n")!!, a.getString("t")!!, a.getString("v")!!.toInt())
                    }
                    composable("node/{c}/{n}") { NodeScreen(it.arguments!!.getString("c")!!, it.arguments!!.getString("n")!!) }
                    composable("host/{h}") { HostScreen(it.arguments!!.getString("h")!!) }
                    composable("container/{h}/{id}") { ContainerScreen(it.arguments!!.getString("h")!!, it.arguments!!.getString("id")!!) }
                    composable("logs/{h}/{id}") { LogsScreen(it.arguments!!.getString("h")!!, it.arguments!!.getString("id")!!) }
                    composable("term/{id}") { TermScreen(it.arguments!!.getString("id")!!) }
                    composable("edit-host?id={id}&name={name}&addr={addr}&user={user}&key={key}") {
                        val a = it.arguments
                        EditHostScreen(a?.getString("id")?.ifBlank { null }, dev.pocketlab.ui.screens.HostPrefill(
                            a?.getString("name").orEmpty(), a?.getString("addr").orEmpty(), a?.getString("user").orEmpty(), a?.getString("key").orEmpty()))
                    }
                    composable("edit-cluster?id={id}") { EditClusterScreen(it.arguments?.getString("id")?.ifBlank { null }) }
                }
                if (showBar) BottomBar(nav, currentTab)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
        }
        dev.pocketlab.ui.screens.PasswordPromptDialog()
    }
}

@Composable
private fun OverviewArg(filter: String?) = WorkloadsScreen(filter ?: "All")

@Composable
private fun BottomBar(nav: NavHostController, current: String) {
    val t = LocalTones.current

    // One label size for all tabs: the largest (up to labelMedium) at which the longest label fits a
    // tab's width, so narrow phones get slightly smaller labels instead of clipped ones.
    val labelStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val tabWidthPx = with(density) { ((maxWidth - 12.dp) / tabs.size - 6.dp).toPx() }
        val labelSize = remember(tabWidthPx, labelStyle) {
            var size = labelStyle.fontSize.value
            fun widest(sz: Float) = tabs.maxOf {
                measurer.measure(it.label, labelStyle.copy(fontSize = sz.sp, fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false).size.width
            }
            while (size > 8f && widest(size) > tabWidthPx) size -= 0.5f
            size.sp
        }
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(36.dp)).background(t.surface).border(1.dp, t.outline, RoundedCornerShape(36.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            tabs.forEach { tab ->
                val isSelected = tab.route == current
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(30.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else t.surface)
                        .semantics { selected = isSelected }
                        .clickable {
                            // A tab always opens its main page. No saved per-tab stacks: restoring them brought
                            // back whatever detail screen you'd left (and "overview", the start destination,
                            // restored another tab's stack).
                            if (tab.route == "overview") nav.popBackStack("overview", inclusive = false)
                            else nav.navigate(tab.route) {
                                popUpTo("overview")
                                launchSingleTop = true
                            }
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val color = if (isSelected) MaterialTheme.colorScheme.primary else t.textDim
                    Icon(tab.icon, tab.label, tint = color)
                    Spacer(Modifier.height(2.dp))
                    Text(tab.label, color = color, style = labelStyle.copy(fontSize = labelSize), maxLines = 1, softWrap = false,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
