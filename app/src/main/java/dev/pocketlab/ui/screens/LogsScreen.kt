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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class LogLine(val seq: Long, val time: String?, val text: String)
private val timeFmt = DateTimeFormatter.ofPattern("h:mm:ss a").withZone(ZoneId.systemDefault())
private const val MAX_LINES = 5000

private fun parse(seq: Long, raw: String): LogLine {
    val sp = raw.indexOf(' ')
    if (sp in 20..40) {
        val ts = runCatching { Instant.parse(raw.substring(0, sp)) }.getOrNull()
        if (ts != null) return LogLine(seq, timeFmt.format(ts), raw.substring(sp + 1))
    }
    return LogLine(seq, null, raw)
}

@Composable
fun LogsScreen(hostId: String, id: String) {
    val nav = LocalNav.current
    val t = LocalTones.current
    val scope = rememberCoroutineScope()
    val c = labSnapshot().hosts[hostId]?.containers?.firstOrNull { it.id == id }
    val host = labConfig().allHosts().firstOrNull { it.id == hostId }
    val lines = remember { mutableStateListOf<LogLine>() }
    var received by remember { mutableLongStateOf(0L) }   // seq of the newest line
    var seenSeq by remember { mutableLongStateOf(0L) }    // newest line the user has had on screen
    var error by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var onlyMatches by remember { mutableStateOf(false) }
    var currentSeq by remember { mutableStateOf<Long?>(null) }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Lines arrive on a background thread; hand them to the UI in batches (10x/s), not one recomposition per line.
    val pending = remember { java.util.ArrayList<String>() }
    LaunchedEffect(hostId, id) {
        launch(Dispatchers.IO) {
            try {
                lab.docker(hostId)!!.logs(id).collect { raw -> synchronized(pending) { pending.add(raw) } }
                ended = true
            } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        }
        while (true) {
            delay(100)
            val batch = synchronized(pending) { if (pending.isEmpty()) null else ArrayList(pending).also { pending.clear() } } ?: continue
            var seq = received
            lines.addAll(batch.map { parse(++seq, it) })
            if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
            received = seq
        }
    }

    val q = query.trim()
    val shown by remember { derivedStateOf { if (onlyMatches && q.isNotEmpty()) lines.filter { it.text.contains(q, true) } else lines.toList() } }
    val matches by remember { derivedStateOf { if (q.isEmpty()) emptyList() else shown.filter { it.text.contains(q, true) }.map { it.seq } } }
    val atBottom by remember { derivedStateOf { val info = listState.layoutInfo; info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 2 } ?: true } }

    // Follow mode changes only on a real user scroll: drag away to pause, drag back to the end to resume.
    LaunchedEffect(listState) {
        var userScrolling = false
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> userScrolling = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> if (userScrolling) {
                    userScrolling = false
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                    follow = atBottom && q.isEmpty()
                }
            }
        }
    }
    LaunchedEffect(shown.size, received, follow) {
        if (follow && shown.isNotEmpty()) { listState.scrollToItem(shown.lastIndex); seenSeq = received }
    }

    fun goTo(seq: Long) {
        currentSeq = seq
        follow = false
        val idx = shown.indexOfFirst { it.seq == seq }
        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
    }
    LaunchedEffect(q, onlyMatches) {
        if (q.isEmpty()) { currentSeq = null; return@LaunchedEffect }
        matches.lastOrNull()?.let(::goTo)
    }
    val currentIdx = currentSeq?.let { cs -> matches.indexOf(cs).takeIf { it >= 0 } ?: matches.indexOfFirst { it > cs }.takeIf { it >= 0 } }

    Column(Modifier.fillMaxSize()) {
        TopBar("${c?.name ?: "logs"} · ${host?.name ?: ""}", onBack = { nav.popBackStack() })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            placeholder = { Text("Search logs") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; onlyMatches = false }) { Icon(Icons.Outlined.Clear, "Clear") } },
            shape = RoundedCornerShape(16.dp))
        if (q.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (matches.isEmpty()) "No matches" else "${(currentIdx ?: matches.lastIndex) + 1} of ${matches.size}", color = t.textDim, modifier = Modifier.weight(1f))
            TextButton(onClick = { onlyMatches = !onlyMatches }) { Text(if (onlyMatches) "All lines" else "Only matches") }
            IconButton(enabled = matches.isNotEmpty(), onClick = {
                val i = currentIdx ?: matches.size
                goTo(matches[(i - 1 + matches.size) % matches.size])
            }) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous") }
            IconButton(enabled = matches.isNotEmpty(), onClick = {
                val i = currentIdx ?: -1
                goTo(matches[(i + 1) % matches.size])
            }) { Icon(Icons.Outlined.KeyboardArrowDown, "Next") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(shown, key = { it.seq }) { line ->
                        val isCurrent = line.seq == currentSeq
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
                            if (isCurrent) Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
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
            Text(when { ended -> "Stream ended"; follow -> "Following"; else -> "Paused" }, color = t.textDim, modifier = Modifier.weight(1f))
            if (!follow && lines.isNotEmpty()) Button(onClick = {
                query = ""; onlyMatches = false; currentSeq = null; follow = true
            }) {
                val fresh = (received - seenSeq).coerceAtLeast(0)
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
