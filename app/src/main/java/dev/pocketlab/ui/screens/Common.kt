package dev.pocketlab.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketlab.PocketlabApp
import dev.pocketlab.docker.Container
import dev.pocketlab.proxmox.PveGuest
import dev.pocketlab.ui.components.Health
import kotlinx.coroutines.launch

val lab get() = PocketlabApp.lab

@Composable
fun labSnapshot() = lab.snapshot.collectAsStateWithLifecycle().value

@Composable
fun labConfig() = lab.store.config.collectAsStateWithLifecycle().value

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshList(modifier: Modifier = Modifier, onRefresh: suspend () -> Unit = { lab.refresh(wait = true) }, content: LazyListScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { onRefresh() } finally { refreshing = false } }
    }, modifier = modifier) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), content = content)
    }
}

fun PveGuest.health() = when (status) {
    "running" -> Health.OK
    "paused", "suspended" -> Health.PAUSED
    "stopped" -> Health.OFF
    else -> Health.WARN
}

fun PveGuest.statusText() = status.replaceFirstChar { it.uppercase() }

fun Container.healthState() = when {
    health == "unhealthy" -> Health.BAD
    state == "running" && health == "health: starting" -> Health.WARN
    state == "running" -> Health.OK
    state == "paused" -> Health.PAUSED
    state == "restarting" -> Health.WARN
    state == "exited" && !status.startsWith("Exited (0)") -> Health.BAD
    else -> Health.OFF
}

fun Container.statusText(): String {
    val base = state.replaceFirstChar { it.uppercase() }
    return health?.let { "$base · ${it.removePrefix("health: ")}" } ?: base
}

