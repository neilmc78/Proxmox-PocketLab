package dev.pocketlab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketlab.ui.LocalNav
import dev.pocketlab.ui.components.TopBar
import dev.pocketlab.ui.theme.LocalTones
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class LogLine(val time: String?, val text: String)
private val timeFmt = DateTimeFormatter.ofPattern("h:mm:ss a").withZone(ZoneId.systemDefault())
private const val MAX_LINES = 5000

private fun parse(raw: String): LogLine {
    val sp = raw.indexOf(' ')
    if (sp in 20..40) {
        val ts = runCatching { Instant.parse(raw.substring(0, sp)) }.getOrNull()
        if (ts != null) return LogLine(timeFmt.format(ts), raw.substring(sp + 1))
    }
    return LogLine(null, raw)
}

@Composable
fun LogsScreen(hostId: String, id: String) {
    val nav = LocalNav.current
    val t = LocalTones.current
    val scope = rememberCoroutineScope()
    val c = labSnapshot().hosts[hostId]?.containers?.firstOrNull { it.id == id }
    val host = labConfig().hosts.firstOrNull { it.id == hostId }
    val lines = remember { mutableStateListOf<LogLine>() }
    var error by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var onlyMatches by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }
    var seenCount by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(hostId, id) {
        try {
            lab.docker(hostId)!!.logs(id).collect { raw ->
                lines.add(parse(raw))
                if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
            }
            ended = true
        } catch (e: Exception) { error = e.message }
    }

    val q = query.trim()
    val shown = if (onlyMatches && q.isNotEmpty()) lines.filter { it.text.contains(q, true) } else lines
    val matchIdx = remember(shown.size, q) { if (q.isEmpty()) emptyList() else shown.indices.filter { shown[it].text.contains(q, true) } }
    val atBottom by remember { derivedStateOf { val info = listState.layoutInfo; info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 2 } ?: true } }
    val following = atBottom && q.isEmpty()

    LaunchedEffect(shown.size, following) {
        if (following && shown.isNotEmpty()) { listState.scrollToItem(shown.lastIndex); seenCount = shown.size }
    }
    LaunchedEffect(q) {
        if (matchIdx.isNotEmpty()) { current = matchIdx.lastIndex; listState.scrollToItem(matchIdx[current]) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("${c?.name ?: "logs"} · ${host?.name ?: ""}", onBack = { nav.popBackStack() })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            placeholder = { Text("Search logs") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, "Clear") } },
            shape = RoundedCornerShape(16.dp))
        if (q.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (matchIdx.isEmpty()) "No matches" else "${current + 1} of ${matchIdx.size}", color = t.textDim, modifier = Modifier.weight(1f))
            TextButton(onClick = { onlyMatches = !onlyMatches }) { Text(if (onlyMatches) "All lines" else "Only matches") }
            IconButton(enabled = matchIdx.isNotEmpty(), onClick = {
                current = (current - 1 + matchIdx.size) % matchIdx.size; scope.launch { listState.animateScrollToItem(matchIdx[current]) }
            }) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous") }
            IconButton(enabled = matchIdx.isNotEmpty(), onClick = {
                current = (current + 1) % matchIdx.size; scope.launch { listState.animateScrollToItem(matchIdx[current]) }
            }) { Icon(Icons.Outlined.KeyboardArrowDown, "Next") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    itemsIndexed(shown) { i, line ->
                        val isCurrent = matchIdx.getOrNull(current) == i
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            if (isCurrent) Box(Modifier.width(3.dp).padding(end = 0.dp).background(MaterialTheme.colorScheme.primary).padding(vertical = 18.dp))
                            Column(Modifier.padding(start = if (isCurrent) 8.dp else 0.dp)) {
                                line.time?.let { Text(it, color = t.textDim, fontSize = 11.sp) }
                                Text(highlight(line.text, q, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, color = t.bad, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when { ended -> "Stream ended"; following -> "Following"; else -> "Paused" }, color = t.textDim, modifier = Modifier.weight(1f))
            if (!following && shown.isNotEmpty()) Button(onClick = {
                query = ""; onlyMatches = false
                scope.launch { listState.scrollToItem(lines.lastIndex.coerceAtLeast(0)) }
            }) {
                val fresh = (lines.size - seenCount).coerceAtLeast(0)
                Text(if (fresh > 0) "Jump to latest ($fresh new)" else "Jump to latest")
            }
        }
    }
}

private fun highlight(text: String, q: String, bg: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (true) {
            val j = text.indexOf(q, i, ignoreCase = true)
            if (j < 0) { append(text.substring(i)); break }
            append(text.substring(i, j))
            withStyle(SpanStyle(background = bg)) { append(text.substring(j, j + q.length)) }
            i = j + q.length
        }
    }
}
