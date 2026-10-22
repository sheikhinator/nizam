package com.nizam.app.feature.joytime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.nav.ReAuth
import com.nizam.app.ui.nav.Routes
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private const val SEP = "\u0001"
private fun enc(s: String) = s.replace("|", "¦").replace(SEP, " ")
private fun dec(s: String) = s.replace("¦", "|")

private fun activityOf(c: Context): Activity? { var x = c; while (x is ContextWrapper) { if (x is Activity) return x; x = x.baseContext }; return null }

private data class RTheme(val bg: Color, val fg: Color, val dim: Color)
private val THEMES = linkedMapOf(
    "Paper" to RTheme(Color(0xFFF7F3EA), Color(0xFF23201B), Color(0xFF7A7468)),
    "Sepia" to RTheme(Color(0xFFF0E2C4), Color(0xFF4A3920), Color(0xFF8A7650)),
    "Night" to RTheme(Color(0xFF14161B), Color(0xFFD9D5CC), Color(0xFF8A8F99)),
    "Black" to RTheme(Color(0xFF000000), Color(0xFFC9C5BD), Color(0xFF6E6A63)),
)

/** Joytime: a story reader. The stack of screens is kept as text so a rotation or a quick app switch does not lose your place. */
@Composable
fun JoytimeApp(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val prefs = remember { c.getSharedPreferences("joytime", 0) }
    var ok18 by remember { mutableStateOf(prefs.getBoolean("ok18", false)) }
    var unlocked by remember { mutableStateOf(!prefs.getBoolean("lock", true)) }
    var stackRaw by rememberSaveable { mutableStateOf("home") }
    val stack = stackRaw.split(SEP)
    fun push(s: String) { stackRaw = stackRaw + SEP + s }
    fun pop() { if (stack.size > 1) stackRaw = stack.dropLast(1).joinToString(SEP) }
    fun replaceTop(s: String) { stackRaw = (stack.dropLast(1) + s).joinToString(SEP) }
    val exit = { open(Routes.TODAY) }

    BackHandler(enabled = stack.size > 1) { pop() }
    // optional: keep Joytime out of screenshots and the recent-apps preview
    val secure = prefs.getBoolean("secure", true)
    DisposableEffect(secure) {
        val w = activityOf(c)?.window
        if (secure) w?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { if (!unlocked && ok18) ReAuth.prompt(c) { ok -> if (ok) unlocked = true else open(Routes.TODAY) } } }

    when {
        !ok18 -> Gate(onAccept = { prefs.edit().putBoolean("ok18", true).apply(); ok18 = true; if (!unlocked) ReAuth.prompt(c) { ok -> if (ok) unlocked = true else open(Routes.TODAY) } }, onExit = { exit() })
        !unlocked -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Joytime is locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { ReAuth.prompt(c) { ok -> if (ok) unlocked = true } }) { Text("Unlock") }
            TextButton(onClick = { exit() }) { Text("Leave") }
        }
        else -> {
            val parts = stack.last().split("|")
            when (parts[0]) {
                "cat" -> ListingScreen(c, dec(parts[1]), dec(parts.getOrElse(2) { "" }), onBack = { pop() }, onExit = { exit() },
                    onEntry = { e -> if (e.isDir) push("story|${enc(e.url)}|${enc(JoyNet.pretty(e.name))}|${enc(JoyNet.pretty(dec(parts.getOrElse(2) { "" })))}") else push("story|${enc(e.url)}|${enc(JoyNet.pretty(e.name))}|${enc(JoyNet.pretty(dec(parts.getOrElse(2) { "" })))}") })
                "story" -> StoryScreen(c, dec(parts[1]), dec(parts.getOrElse(2) { "" }), dec(parts.getOrElse(3) { "" }), onBack = { pop() }, onExit = { exit() },
                    onRead = { ch -> push("read|${enc(parts[1])}|$ch") }, onFolder = { e -> push("cat|${enc(e.url)}|${enc(JoyNet.pretty(e.name))}") })
                "read" -> ReaderScreen(c, dec(parts[1]), parts.getOrElse(2) { "0" }.toIntOrNull() ?: 0, onBack = { pop() }, onExit = { exit() },
                    onChapter = { ch -> replaceTop("read|${parts[1]}|$ch") })
                else -> HomeScreen(c, onExit = { exit() },
                    onCategory = { e -> push("cat|${enc(e.url)}|${enc(JoyNet.pretty(e.name))}") },
                    onResume = { url, ch -> push("read|${enc(url)}|$ch") })
            }
        }
    }
}

// ───────────────────────── gate ─────────────────────────

@Composable
private fun Gate(onAccept: () -> Unit, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Joytime", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text("Stories for adults. Some are explicit. By continuing you confirm you are 18 or older and that reading this is legal where you live.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))
        Text("Stories involving characters under 18 are never shown here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAccept) { Text("I am 18 or older") }
        TextButton(onClick = onExit) { Text("Leave") }
    }
}

// ───────────────────────── shared bits ─────────────────────────

@Composable
private fun Bar(title: String, onBack: (() -> Unit)?, onExit: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineSmall) } else Spacer(Modifier.height(1.dp).padding(start = 12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
        // quick exit: straight back to Today
        TextButton(onClick = onExit) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
}

// ───────────────────────── home: browse, library, settings ─────────────────────────

@Composable
private fun HomeScreen(c: Context, onExit: () -> Unit, onCategory: (JoyNet.Entry) -> Unit, onResume: (String, Int) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Browse") }
    Column(Modifier.fillMaxSize()) {
        Bar("Joytime", null, onExit)
        Row(Modifier.padding(horizontal = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Browse", "Library", "Settings").forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) }) }
        }
        when (tab) {
            "Library" -> LibraryTab(c, onResume)
            "Settings" -> SettingsTab(c)
            else -> {
                val last = remember { JoyStore.list(c).firstOrNull { !it.optBoolean("done") && it.optInt("pct") in 1..99 } }
                if (last != null) Card(Modifier.padding(14.dp), onClick = { onResume(last.optString("url"), last.optInt("ch")) }) {
                    Text("Continue reading", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(last.optString("title"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { last.optInt("pct") / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                ListingBody(c, JoyNet.base(c), "", onEntry = onCategory)
            }
        }
    }
}

// ───────────────────────── a folder page ─────────────────────────

@Composable
private fun ListingScreen(c: Context, url: String, title: String, onBack: () -> Unit, onExit: () -> Unit, onEntry: (JoyNet.Entry) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Bar(title.ifBlank { "Stories" }, onBack, onExit)
        ListingBody(c, url, title, onEntry)
    }
}

@Composable
private fun ListingBody(c: Context, url: String, title: String, onEntry: (JoyNet.Entry) -> Unit) {
    var loading by remember(url) { mutableStateOf(true) }
    var entries by remember(url) { mutableStateOf<List<JoyNet.Entry>>(emptyList()) }
    var err by remember(url) { mutableStateOf("") }
    var hidden by remember(url) { mutableStateOf(0) }
    var reload by remember { mutableStateOf(0) }
    var query by rememberSaveable(url) { mutableStateOf("") }
    LaunchedEffect(url, reload) { com.nizam.app.ui.design.guard {
        loading = true; err = ""
        val r = JoyNet.fetch(c, url, if (reload > 0) 0L else 6 * 3_600_000L)
        val all = JoyNet.parseListing(r.text, url)
        val ok = all.filter { !JoySafety.blockedName(it.name, it.note) }
        hidden = all.size - ok.size
        entries = ok.sortedWith(Comparator<JoyNet.Entry> { a, b -> if (a.isDir != b.isDir) (if (a.isDir) -1 else 1) else JoyNet.natural.compare(a.name, b.name) })
        if (all.isEmpty()) err = if (r.error.isNotBlank()) "Couldn't load this page: ${r.error}" else "Nothing was found on this page. Open Settings › Connection test to see what the site returned."
        loading = false
    } }
    val shown = remember(entries, query) { if (query.isBlank()) entries else entries.filter { (it.name + " " + it.note).contains(query, true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { OutlinedTextField(query, { query = it }, label = { Text("Search this list") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (loading) item { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (err.isNotBlank()) item { Card { Text(err, style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { reload++ }) { Text("Try again") } } }
        items(shown, key = { it.url }) { e ->
            Card(onClick = { onEntry(e) }) {
                Text(JoyNet.pretty(e.name), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (hidden > 0) item { Text("$hidden item${if (hidden == 1) "" else "s"} hidden because they involve characters under 18.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

// ───────────────────────── a story: chapters ─────────────────────────

@Composable
private fun StoryScreen(c: Context, url: String, title: String, cat: String, onBack: () -> Unit, onExit: () -> Unit, onRead: (Int) -> Unit, onFolder: (JoyNet.Entry) -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var loading by remember(url) { mutableStateOf(true) }
    var chapters by remember(url) { mutableStateOf<List<JoyNet.Entry>>(emptyList()) }
    var folders by remember(url) { mutableStateOf<List<JoyNet.Entry>>(emptyList()) }
    var err by remember(url) { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var dl by remember { mutableStateOf("") }
    LaunchedEffect(url) { com.nizam.app.ui.design.guard {
        loading = true; err = ""
        if (!url.endsWith("/")) { chapters = listOf(JoyNet.Entry(title, url, false, "")) }
        else {
            val r = JoyNet.fetch(c, url, 6 * 3_600_000L)
            val all = JoyNet.parseListing(r.text, url).filter { !JoySafety.blockedName(it.name, it.note) }
            val junk = Regex("(?i)\\.(jpe?g|png|gif|css|js|ico|zip|gz|pdf)$|^(index|readme)")
            chapters = all.filter { !it.isDir && !junk.containsMatchIn(it.name) }.sortedWith(Comparator<JoyNet.Entry> { a, b -> JoyNet.natural.compare(a.url, b.url) })
            folders = all.filter { it.isDir }
            if (chapters.isEmpty() && folders.isEmpty()) err = if (r.error.isNotBlank()) "Couldn't load this story: ${r.error}" else "No chapters were found here."
        }
        if (chapters.isNotEmpty()) JoyStore.touch(c, url, title, cat, chapters.map { it.url })
        loading = false
    } }
    val entry = remember(tick, chapters) { JoyStore.get(c, url) }
    val lastCh = entry?.optInt("ch") ?: 0
    Column(Modifier.fillMaxSize()) {
        Bar(title, onBack, onExit)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) item { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (err.isNotBlank()) item { Card { Text(err) } }
            if (chapters.isNotEmpty()) item {
                Card {
                    if (cat.isNotBlank()) Text(cat, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${chapters.size} chapter${if (chapters.size == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
                    val pct = entry?.optInt("pct") ?: 0
                    if (pct in 1..99) LinearProgressIndicator(progress = { pct / 100f }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onRead(if (pct in 1..99) lastCh.coerceIn(0, chapters.size - 1) else 0) }) { Text(if (pct in 1..99) "Continue" else "Start reading") }
                        TextButton(onClick = { JoyStore.setFav(c, url, !(entry?.optBoolean("fav") ?: false)); tick++ }) { Text(if (entry?.optBoolean("fav") == true) "★ Saved" else "☆ Save") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            scope.launch {
                                chapters.forEachIndexed { i, ch -> dl = "Downloading ${i + 1} of ${chapters.size}…"; JoyNet.fetch(c, ch.url, Long.MAX_VALUE / 4) }
                                JoyStore.update(c, url) { it.put("dl", true) }; dl = "Saved for offline reading."; tick++
                            }
                        }) { Text(if (entry?.optBoolean("dl") == true) "Downloaded ✓" else "Download for offline") }
                        if (dl.isNotBlank()) Text(dl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (folders.isNotEmpty() && chapters.isEmpty()) items(folders, key = { it.url }) { f -> Card(onClick = { onFolder(f) }) { Text(JoyNet.pretty(f.name), style = MaterialTheme.typography.titleMedium) } }
            itemsIndexed2(chapters) { i, ch ->
                val read = entry != null && (i < lastCh || entry.optBoolean("done"))
                Card(onClick = { onRead(i) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(JoyNet.pretty(ch.name), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        if (JoyNet.isCached(c, ch.url)) Text("⬇", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed2(list: List<T>, content: @Composable (Int, T) -> Unit) {
    items(list.size) { i -> content(i, list[i]) }
}

// ───────────────────────── the reader ─────────────────────────

@OptIn(FlowPreview::class)
@Composable
private fun ReaderScreen(c: Context, storyUrl: String, chapterIdx: Int, onBack: () -> Unit, onExit: () -> Unit, onChapter: (Int) -> Unit) {
    val prefs = remember { c.getSharedPreferences("joytime", 0) }
    val entry = remember(storyUrl) { JoyStore.get(c, storyUrl) }
    val chapterUrls = remember(entry) { entry?.optJSONArray("chapters")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf(storyUrl) }
    val idx = chapterIdx.coerceIn(0, chapterUrls.size - 1)
    val chUrl = chapterUrls[idx]
    val title = entry?.optString("title").orEmpty().ifBlank { JoyNet.pretty(storyUrl) }
    var parsed by remember(chUrl) { mutableStateOf<JoyText.Parsed?>(null) }
    var blocked by remember(chUrl) { mutableStateOf(false) }
    var err by remember(chUrl) { mutableStateOf("") }
    var bar by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(prefs.getFloat("rsize", 19f)) }
    var line by remember { mutableStateOf(prefs.getFloat("rline", 1.55f)) }
    var margin by remember { mutableStateOf(prefs.getFloat("rmargin", 22f)) }
    var bright by remember { mutableStateOf(prefs.getFloat("rbright", -1f)) }
    var font by remember { mutableStateOf(prefs.getString("rfont", "Serif").orEmpty()) }
    var themeName by remember { mutableStateOf(prefs.getString("rtheme", "Night").orEmpty()) }
    var awake by remember { mutableStateOf(prefs.getBoolean("rawake", true)) }
    var reading by remember { mutableStateOf(false) }
    var readIdx by remember { mutableStateOf(0) }
    var bmTick by remember { mutableStateOf(0) }
    val theme = THEMES[themeName] ?: THEMES.getValue("Night")
    val list = rememberLazyListState()
    val view = LocalView.current

    LaunchedEffect(chUrl) { com.nizam.app.ui.design.guard {
        val r = JoyNet.fetch(c, chUrl, Long.MAX_VALUE / 4)
        if (r.text.isBlank()) { err = if (r.error.isNotBlank()) "Couldn't load this chapter: ${r.error}" else "This chapter is empty."; return@LaunchedEffect }
        if (JoySafety.blockedText(r.text)) { blocked = true; return@LaunchedEffect }
        parsed = JoyText.parse(r.text)
        // read ahead: the next chapter is fetched quietly so it opens instantly (and works offline)
        chapterUrls.getOrNull(idx + 1)?.let { n -> launch { JoyNet.fetch(c, n, Long.MAX_VALUE / 4) } }
    } }
    // return to where you stopped
    LaunchedEffect(parsed) { com.nizam.app.ui.design.guard {
        val p = parsed ?: return@LaunchedEffect
        val e = JoyStore.get(c, storyUrl)
        if (e != null && e.optInt("ch") == idx && e.optInt("para") in 1 until p.paragraphs.size) list.scrollToItem(e.optInt("para"))
    } }
    LaunchedEffect(parsed) { com.nizam.app.ui.design.guard {
        val p = parsed ?: return@LaunchedEffect
        snapshotFlow { list.firstVisibleItemIndex }.debounce(700).collect { at ->
            val inChapter = if (p.paragraphs.isEmpty()) 0f else at.toFloat() / p.paragraphs.size
            JoyStore.progress(c, storyUrl, idx, at, (((idx + inChapter) / chapterUrls.size) * 100).toInt().coerceIn(1, 100))
        }
    } }
    DisposableEffect(bright) {
        val w = activityOf(c)?.window
        if (w != null) { val lp = w.attributes; lp.screenBrightness = if (bright < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else bright.coerceIn(0.02f, 1f); w.attributes = lp }
        onDispose { val w2 = activityOf(c)?.window; if (w2 != null) { val lp = w2.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; w2.attributes = lp } }
    }
    DisposableEffect(awake) { view.keepScreenOn = awake; onDispose { view.keepScreenOn = false } }

    // read aloud, paragraph by paragraph, with the page following along
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        var ref: TextToSpeech? = null
        ref = TextToSpeech(c) { }
        ref.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { handler.post { readIdx++ } }
            @Deprecated("") override fun onError(id: String?) { handler.post { reading = false } }
        })
        tts.value = ref
        onDispose { runCatching { ref.stop(); ref.shutdown() } }
    }
    LaunchedEffect(reading, readIdx, parsed) { com.nizam.app.ui.design.guard {
        val p = parsed ?: return@LaunchedEffect
        if (!reading) { tts.value?.stop(); return@LaunchedEffect }
        if (readIdx >= p.paragraphs.size) { reading = false; return@LaunchedEffect }
        list.animateScrollToItem(readIdx)
        tts.value?.speak(p.paragraphs[readIdx], TextToSpeech.QUEUE_FLUSH, null, "joy$readIdx")
    } }

    val family = when (font) { "Sans" -> FontFamily.SansSerif; "Mono" -> FontFamily.Monospace; else -> FontFamily.Serif }
    Box(Modifier.fillMaxSize().background(theme.bg)) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(bar) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹", color = theme.fg, style = MaterialTheme.typography.headlineSmall) }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = theme.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("Chapter ${idx + 1} of ${chapterUrls.size}", color = theme.dim, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { JoyStore.toggleBookmark(c, storyUrl, idx, list.firstVisibleItemIndex); bmTick++ }) {
                        val on = remember(bmTick, list.firstVisibleItemIndex) { JoyStore.bookmarks(c, storyUrl).any { it.first == idx && kotlin.math.abs(it.second - list.firstVisibleItemIndex) < 3 } }
                        Text(if (on) "🔖" else "🏷", color = theme.fg)
                    }
                    TextButton(onClick = { if (reading) reading = false else { readIdx = list.firstVisibleItemIndex; reading = true } }) { Text(if (reading) "⏸" else "🔊", color = theme.fg) }
                    TextButton(onClick = { panel = !panel }) { Text("Aa", color = theme.fg, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onExit) { Text("✕", color = theme.dim) }
                }
            }
            val p = parsed
            when {
                blocked -> Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text("This chapter appears to involve a character under 18, so Joytime won't open it.", color = theme.fg, style = MaterialTheme.typography.bodyLarge)
                }
                err.isNotBlank() -> Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { Text(err, color = theme.fg) }
                p == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Loading…", color = theme.dim) }
                else -> SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { bar = !bar },
                        state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = margin.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy((size * 0.6f).dp)) {
                        items(p.paragraphs.size) { i ->
                            Text(p.paragraphs[i], color = theme.fg, fontFamily = family, fontSize = size.sp, lineHeight = (size * line).sp,
                                modifier = if (reading && i == readIdx) Modifier.background(theme.fg.copy(alpha = 0.07f)) else Modifier)
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (idx + 1 < chapterUrls.size) Button(onClick = { onChapter(idx + 1) }) { Text("Next chapter ›") }
                                else { Text("The end", color = theme.dim); LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { JoyStore.update(c, storyUrl) { it.put("done", true).put("pct", 100) } } } }
                                if (idx > 0) TextButton(onClick = { onChapter(idx - 1) }) { Text("‹ Previous chapter", color = theme.fg) }
                            }
                        }
                    }
                }
            }
            if (p != null && bar) {
                val frac = if (p.paragraphs.isEmpty()) 0f else (list.firstVisibleItemIndex.toFloat() / p.paragraphs.size).coerceIn(0f, 1f)
                val words = p.paragraphs.sumOf { it.split(" ").size }
                val left = (words * (1f - frac) / 230f).toInt()
                LinearProgressIndicator(progress = { frac }, Modifier.fillMaxWidth(), color = theme.fg.copy(alpha = 0.6f), trackColor = theme.fg.copy(alpha = 0.12f))
                Text("${(frac * 100).toInt()}%  ·  about $left min left in this chapter", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = theme.dim, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (panel) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                THEMES.keys.forEach { t -> FilterChip(selected = themeName == t, onClick = { themeName = t; prefs.edit().putString("rtheme", t).apply() }, label = { Text(t) }) }
                listOf("Serif", "Sans", "Mono").forEach { f -> FilterChip(selected = font == f, onClick = { font = f; prefs.edit().putString("rfont", f).apply() }, label = { Text(f) }) }
            }
            Text("Text size ${size.toInt()}", style = MaterialTheme.typography.labelMedium)
            Slider(size, { size = it }, valueRange = 13f..32f, onValueChangeFinished = { prefs.edit().putFloat("rsize", size).apply() })
            Text("Line spacing ${"%.2f".format(line)}", style = MaterialTheme.typography.labelMedium)
            Slider(line, { line = it }, valueRange = 1.2f..2.1f, onValueChangeFinished = { prefs.edit().putFloat("rline", line).apply() })
            Text("Side margins ${margin.toInt()}", style = MaterialTheme.typography.labelMedium)
            Slider(margin, { margin = it }, valueRange = 8f..48f, onValueChangeFinished = { prefs.edit().putFloat("rmargin", margin).apply() })
            Text(if (bright < 0f) "Brightness: system" else "Brightness ${(bright * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(if (bright < 0f) 0.5f else bright, { bright = it }, valueRange = 0.02f..1f, onValueChangeFinished = { prefs.edit().putFloat("rbright", bright).apply() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen on", Modifier.weight(1f)); Switch(awake, { awake = it; prefs.edit().putBoolean("rawake", it).apply() })
            }
            Row { TextButton(onClick = { bright = -1f; prefs.edit().putFloat("rbright", -1f).apply() }) { Text("Use system brightness") }; TextButton(onClick = { panel = false }) { Text("Done") } }
        }
    }
}

// ───────────────────────── library ─────────────────────────

@Composable
private fun LibraryTab(c: Context, onResume: (String, Int) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("Reading") }
    var tick by remember { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(tick) { JoyStore.list(c) }
    val shown = all.filter {
        (query.isBlank() || (it.optString("title") + " " + it.optString("cat")).contains(query, true)) && when (filter) {
            "Reading" -> !it.optBoolean("done") && it.optInt("pct") > 0
            "Saved" -> it.optBoolean("fav")
            "Downloaded" -> it.optBoolean("dl")
            "Finished" -> it.optBoolean("done")
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Reading", "Saved", "Downloaded", "Finished", "All").forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
            }
        }
        item { OutlinedTextField(query, { query = it }, label = { Text("Search your library") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (shown.isEmpty()) item { Text("Nothing here yet. Open a story from Browse and it appears in your library.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown, key = { it.optString("url") }) { e ->
            Card(onClick = { onResume(e.optString("url"), e.optInt("ch")) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.optString("title"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(e.optString("cat") + if (e.optBoolean("done")) "  ·  finished" else if (e.optInt("pct") > 0) "  ·  ${e.optInt("pct")}%" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { JoyStore.setFav(c, e.optString("url"), !e.optBoolean("fav")); tick++ }) { Text(if (e.optBoolean("fav")) "★" else "☆") }
                    TextButton(onClick = { JoyStore.remove(c, e.optString("url")); tick++ }) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (e.optInt("pct") in 1..99) LinearProgressIndicator(progress = { e.optInt("pct") / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

// ───────────────────────── settings and the connection test ─────────────────────────

@Composable
private fun SettingsTab(c: Context) {
    val prefs = remember { c.getSharedPreferences("joytime", 0) }
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var lock by remember { mutableStateOf(prefs.getBoolean("lock", true)) }
    var secure by remember { mutableStateOf(prefs.getBoolean("secure", true)) }
    var base by remember { mutableStateOf(JoyNet.base(c)) }
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var cacheMb by remember { mutableStateOf(JoyNet.cacheBytes(c) / 1_048_576.0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Lock Joytime"); Text("Ask for your fingerprint or screen lock to open it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(lock, { lock = it; prefs.edit().putBoolean("lock", it).apply() }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Block screenshots"); Text("Hides Joytime from screenshots and the recent-apps preview", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(secure, { secure = it; prefs.edit().putBoolean("secure", it).apply() }) }
                Text("Joytime is kept out of Recent, search suggestions, the activity log and the AI's memory.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card {
                Text("Source", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(base, { base = it }, label = { Text("Archive address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { JoyNet.setBase(c, base); base = JoyNet.base(c) }) { Text("Save") }
                    TextButton(onClick = { JoyNet.setBase(c, JoyNet.DEFAULT_BASE); base = JoyNet.DEFAULT_BASE }) { Text("Reset") }
                }
                Button(enabled = !busy, onClick = { busy = true; report = "Checking…"; scope.launch { report = JoyNet.diagnose(c, JoyNet.base(c)); busy = false } }) { Text("Connection test") }
                if (report.isNotBlank()) SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            Card {
                Text("Storage", style = MaterialTheme.typography.titleSmall)
                Text("%.1f MB of saved pages and chapters".format(cacheMb), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { JoyNet.clearCache(c); cacheMb = 0.0 }) { Text("Clear saved pages") }
            }
        }
        item {
            Card(onClick = { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(JoyNet.SUPPORT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                Text("Support the archive", style = MaterialTheme.typography.titleSmall)
                Text("The stories are written by volunteers and the archive runs on donations. Tap to open its support page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Text("Stories are the authors' work. Joytime only reads them on this phone and keeps a private copy for offline reading; it does not republish anything.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

object JoytimeSpecs {
    val all = listOf(
        ScreenSpec("joytime", "Joytime", "A private story reader: browse, read, save and read offline", Icons.Outlined.AutoStories, Hub.Media, "") { c, open -> JoytimeApp(c, open) },
    )
}
