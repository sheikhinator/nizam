package com.nizam.app.ui.screens.spaces

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.Capture
import com.nizam.app.feature.nx.Noticer
import com.nizam.app.ui.design.pressable
import com.nizam.app.ui.design.wash
import kotlinx.coroutines.launch

/** A single background download or job, shown on the pill until it finishes. */
object Activity {
    private var state = mutableStateOf<Pair<String, Float?>?>(null)
    val current get() = state.value
    fun start(label: String) { state.value = label to 0f }
    fun progress(label: String, p: Float) { state.value = label to p }
    fun done() { state.value = null }
}

/** A small pill under the header: shows what Atlas is doing right now (a download, a build). Tap-through, never blocks. */
@Composable
fun StatusIsland(modifier: Modifier = Modifier) {
    val a = Activity.current
    AnimatedVisibility(a != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        if (a != null) {
            val (label, p) = a
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp)).background(wash(MaterialTheme.colorScheme.primary, 0.14f))
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Downloading, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
    }
}

/** A row of bubbles across the top of a space: what needs attention right now, each opening the tool that helps. */
@Composable
fun StoryRail(notices: List<Noticer.Notice>, open: (String) -> Unit) {
    if (notices.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(notices.take(8), key = { it.id }) { n ->
            val urgent = n.level >= 2
            val ring = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Column(Modifier.width(72.dp).pressable { open(n.route) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(wash(ring, 0.30f), wash(ring, 0.10f))))
                    .padding(2.dp), contentAlignment = Alignment.Center) {
                    Text(n.title.take(2).uppercase(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ring)
                }
                Text(n.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** One floating "+" that stays on screen everywhere: type anything and Atlas files it as a task, note or event. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureFab(container: AppContainer, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box(modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).pressable { open = true },
        contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Add, "Quick capture", tint = MaterialTheme.colorScheme.onPrimary)
    }
    if (open) {
        val sheet = rememberModalBottomSheetState()
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Throw anything in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(wash(MaterialTheme.colorScheme.onSurface, 0.06f)).padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner -> if (text.isEmpty()) Text("A thought, a task, an event…", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() })
                    }
                    com.nizam.app.feature.voice.DictateButton(onPartial = { text = it }, onDone = { text = it })
                }
                if (result.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    com.nizam.app.feature.voice.SpeakButton(result)
                }
                Row(Modifier.fillMaxWidth().pressable(enabled = text.isNotBlank() && !busy) {
                    busy = true
                    scope.launch {
                        val o = Capture.understand(container, text)
                        Capture.store(c).add(o)
                        result = "Saved as a ${o.optString("kind", "note")}: ${o.optString("title").ifBlank { text.take(40) }}"
                        text = ""; busy = false
                    }
                }.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary).padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center) {
                    Text(if (busy) "Filing it…" else "Save", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
