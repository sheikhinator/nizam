package com.nizam.app.feature.deen.library

import android.content.Context
import android.speech.tts.TextToSpeech
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val TABS = listOf("Today", "Quran", "Hadith", "Search", "Talks", "Library")

/** The Islamic library: the daily path, Quran with tafsir, hadith books, search and talks, all offline once downloaded. */
@Composable
fun LibraryScreen(container: AppContainer, startTab: Int = 0) {
    val c = LocalContext.current
    var tab by remember { mutableIntStateOf(startTab) }
    var version by remember { mutableIntStateOf(0) }       // bumps when packs change
    val progress by DeenLibrary.progress.collectAsState()
    val installed = remember(version, progress.size) { DeenLibrary.installed(c) }
    val urduPref = remember { mutableStateOf(Locale.getDefault().language == "ur" || installed.any { it.lang == "Urdu" } && installed.none { it.lang == "English" && it.kind == DeenLibrary.Kind.QURAN }) }
    val reader = rememberReader(c)

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp)) { ModuleTitle("Islamic Library", "Quran, tafsir and hadith on your phone, and a daily path") }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp, containerColor = androidx.compose.ui.graphics.Color.Transparent, divider = {}) {
            TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        if (installed.isEmpty() && tab != 5) {
            EmptyLibrary(onOpen = { tab = 5 }, onEssentials = { DeenLibrary.download(c, DeenLibrary.ESSENTIALS); tab = 5 })
            return@Column
        }
        when (tab) {
            0 -> TodayTab(container, urduPref.value, { urduPref.value = it }, reader)
            1 -> QuranTab(c)
            2 -> HadithTab(c)
            3 -> SearchTab(c)
            4 -> TalksTab(container, urduPref.value, reader)
            else -> LibraryTab(c, installed, progress) { version++ }
        }
    }
}

@Composable
private fun EmptyLibrary(onOpen: () -> Unit, onEssentials: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📚", fontSize = 48.sp)
        Spacer(Modifier.height(8.dp))
        Text("Your library is empty", style = MaterialTheme.typography.titleMedium)
        Text("Download the Quran, hadith books and tafsirs once. After that everything works with no internet.",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = onEssentials) { Text("Get the essentials (about 60 MB)") }
        TextButton(onClick = onOpen) { Text("Choose books myself") }
    }
}

// ------------------------------------------------------------------ Today

@Composable
private fun TodayTab(container: AppContainer, urdu: Boolean, setUrdu: (Boolean) -> Unit, reader: Reader) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val theme = remember { DailyPath.themeFor() }
    var src by remember { mutableStateOf<DailyPath.Sources?>(null) }
    var lesson by remember { mutableStateOf(DailyPath.cachedLesson(c)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(DailyPath.isDone(c)) }
    var note by remember { mutableStateOf(DailyPath.reflection(c)) }
    var showJournal by remember { mutableStateOf(false) }

    LaunchedEffect(urdu) { com.nizam.app.ui.design.guard { src = withContext(Dispatchers.IO) { DailyPath.sources(c, theme, urdu) } } }

    fun write(refresh: Boolean) {
        val s = src ?: return
        busy = true; error = null
        scope.launch {
            runCatching { DailyPath.lesson(c, container.settingsRepository, s, urdu, refresh) }
                .onSuccess { lesson = it }.onFailure { error = it.message ?: "Couldn't write the lesson" }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Day ${DailyPath.dayIndex().mod(DailyPath.THEMES.size) + 1} of ${DailyPath.THEMES.size} · 🔥 ${DailyPath.streak(c)} day streak", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(theme.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Surah ${Surahs.get(theme.s).name} · ${theme.s}:${theme.from}${if (theme.to != theme.from) "–${theme.to}" else ""}", style = MaterialTheme.typography.bodySmall)
                }
                FilterChip(selected = urdu, onClick = { setUrdu(!urdu) }, label = { Text(if (urdu) "اردو" else "English") })
            }
        }
        src?.let { s ->
            if (s.arabic.isNotEmpty()) item { Panel { s.arabic.forEach { (a, t) -> ArabicLine(t, a) } } }
            if (s.translation.isNotEmpty()) item {
                Panel(s.translationName) { s.translation.forEach { (a, t) -> Body("($a) $t", rtl = urdu) } }
            }
            s.tafsir?.let { t -> item { Expandable("Tafsir · ${s.tafsirName}", t, rtl = s.tafsirName != null && urdu) } }
            s.hadith?.let { h -> item {
                Panel("From the Sunnah · ${s.hadithName} ${h.n}") {
                    Body(h.text.take(1200), rtl = h.text.any { it in '؀'..'ۿ' })
                    if (h.grade.isNotBlank()) Text(h.grade, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            } }
            if (s.arabic.isEmpty() && s.translation.isEmpty()) item {
                Text("Download a Quran edition in the Library tab to see today's passage.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("🎯 Today's mission", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(theme.action, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            Panel("Lesson") {
                when {
                    lesson != null -> {
                        Body(lesson!!, rtl = urdu)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { reader.toggle(lesson!!) }) { Text(if (reader.speaking) "⏹ Stop" else "🔊 Listen") }
                            TextButton(onClick = { write(true) }, enabled = !busy) { Text("Rewrite") }
                        }
                    }
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp); Text("  Preparing today's lesson from these texts…") }
                    else -> {
                        Text("A short lesson from today's ayahs, tafsir and hadith, with questions to ask yourself and a dua to carry.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { write(false) }, enabled = src != null, modifier = Modifier.padding(top = 8.dp)) { Text("Teach me today's lesson") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Lessons are reminders written from the texts above, not rulings. For fiqh, ask a qualified scholar.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Panel("Reflection") {
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text("What did this passage change in you today?") }, minLines = 2)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { DailyPath.markDone(c, note); done = true }) { Text(if (done) "✓ Mission done · update" else "I did today's mission") }
                    OutlinedButton(onClick = { showJournal = !showJournal }) { Text("Journal") }
                }
            }
        }
        if (showJournal) {
            val entries = DailyPath.journal(c)
            if (entries.isEmpty()) item { Text("Your completed days and reflections appear here.", style = MaterialTheme.typography.bodySmall) }
            items(entries) { (d, t, n) ->
                Panel("$d · ${t.title}") { Body(n.ifBlank { "(no note)" }, rtl = n.any { it in '؀'..'ۿ' }) }
            }
        }
        item { ReadingPlan(c) }
    }
}

@Composable
private fun ReadingPlan(c: Context) {
    var pos by remember { mutableIntStateOf(DailyPath.planPosition(c)) }
    var per by remember { mutableIntStateOf(DailyPath.planPerDay(c)) }
    val (s, a) = Surahs.locate(pos)
    Panel("📖 Khatam plan") {
        Text("Next: ${Surahs.label(s)}, ayah $a · $per ayahs a day · ${DailyPath.khatams(c)} khatam(s) completed", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { pos / 6236f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Text("At this pace you finish in ${((6236 - pos) + per - 1) / per} days.", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10, 20, 50, 100, 208).forEach { n ->
                FilterChip(selected = per == n, onClick = { DailyPath.setPlanPerDay(c, n); per = n }, label = { Text(if (n == 208) "Khatam in 30 days" else "$n/day") })
            }
        }
        Button(onClick = { DailyPath.advancePlan(c); pos = DailyPath.planPosition(c) }, modifier = Modifier.padding(top = 6.dp)) { Text("I read today's portion") }
    }
}

// ------------------------------------------------------------------ Quran

@Composable
private fun QuranTab(c: Context) {
    var surah by remember { mutableIntStateOf(0) }
    if (surah == 0) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(Surahs.ALL.withIndex().toList()) { (i, s) ->
                Row(Modifier.fillMaxWidth().clickable { surah = i + 1 }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(36.dp), color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${s.english} · ${if (s.makki) "Makki" else "Madani"} · ${s.ayahs} ayahs", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(s.arabic, fontSize = 18.sp)
                }
            }
        }
        return
    }
    SurahReader(c, surah, onBack = { surah = 0 }, onGo = { surah = it })
}

@Composable
private fun SurahReader(c: Context, s: Int, onBack: () -> Unit, onGo: (Int) -> Unit) {
    val qp = remember { DeenLibrary.quranPacks(c) }
    val tp = remember { DeenLibrary.tafsirPacks(c) }
    var shown by remember { mutableStateOf(qp.take(3).map { it.slug }.toSet()) }
    var tafsirEd by remember { mutableStateOf(tp.firstOrNull()?.slug) }
    var open by remember { mutableStateOf<Int?>(null) }
    var marks by remember { mutableStateOf(DailyPath.bookmarks(c)) }
    val ayahs = remember(s, shown) { DeenLibrary.surah(c, s, qp.filter { it.slug in shown }) }
    val list = rememberLazyListState()
    LaunchedEffect(s) { com.nizam.app.ui.design.guard { list.scrollToItem(0) } }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Surahs") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onGo(s - 1) }, enabled = s > 1) { Text("‹") }
                Text(Surahs.label(s), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onGo(s + 1) }, enabled = s < 114) { Text("›") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                qp.forEach { p -> FilterChip(selected = p.slug in shown, onClick = { shown = if (p.slug in shown) shown - p.slug else shown + p.slug }, label = { Text("${p.lang}: ${p.author.ifBlank { p.name }}".take(32)) }) }
            }
            if (tp.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tafsir:", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
                tp.forEach { p -> FilterChip(selected = tafsirEd == p.slug, onClick = { tafsirEd = p.slug }, label = { Text("${p.name} (${p.lang})".take(36)) }) }
            }
            if (s != 1 && s != 9) Text("بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ", Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center, fontSize = 24.sp)
        }
        items(ayahs, key = { it.a }) { ay ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${ay.s}:${ay.a}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { DailyPath.bookmark(c, ay.s, ay.a); marks = DailyPath.bookmarks(c) }) { Text(if ("${ay.s}:${ay.a}" in marks) "★" else "☆") }
                        if (tafsirEd != null) TextButton(onClick = { open = if (open == ay.a) null else ay.a }) { Text(if (open == ay.a) "Hide tafsir" else "Tafsir") }
                    }
                    ay.texts.forEach { (p, t) -> if (p.lang == "Arabic" && !p.slug.contains("-la")) ArabicLine(t, null) else Body(t, rtl = p.rtl) }
                    if (open == ay.a) tafsirEd?.let { ed ->
                        val t = remember(ed, ay.s, ay.a) { DeenLibrary.tafsir(c, ed, ay.s, ay.a) }
                        Box(Modifier.padding(top = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp)) {
                            if (t == null) Text("No tafsir for this ayah in this edition yet.", style = MaterialTheme.typography.bodySmall)
                            else Column {
                                if (t.first != ay.a) Text("Explained with ayah ${t.first}:", style = MaterialTheme.typography.labelSmall)
                                Body(t.second, rtl = tp.firstOrNull { it.slug == ed }?.rtl == true)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Hadith

@Composable
private fun HadithTab(c: Context) {
    val hp = remember { DeenLibrary.hadithPacks(c) }
    var ed by remember { mutableStateOf<String?>(null) }
    var book by remember { mutableStateOf<DeenLibrary.HBook?>(null) }
    if (hp.isEmpty()) { Text("Download a hadith book in the Library tab.", Modifier.padding(16.dp)); return }
    val pack = hp.firstOrNull { it.slug == ed }
    when {
        pack == null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hp) { p ->
                Card(Modifier.fillMaxWidth().clickable { ed = p.slug }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("${p.lang} · ${DeenLibrary.hadithCount(c, p.slug)} hadith", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        book == null -> {
            val books = remember(pack.slug) { DeenLibrary.hadithBooks(c, pack.slug) }
            LazyColumn(Modifier.fillMaxSize()) {
                item { TextButton(onClick = { ed = null }) { Text("← ${pack.name}") } }
                items(books) { b ->
                    Row(Modifier.fillMaxWidth().clickable { book = b }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("${b.book}", Modifier.width(40.dp), color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.bodyLarge)
                            if (b.first.isNotBlank()) Text("Hadith ${b.first}–${b.last}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (books.isEmpty()) items(listOf(0)) {
                    // collections like the Forty have no chapters
                    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { book = DeenLibrary.HBook(0, pack.name, "", "") } }
                }
            }
        }
        else -> {
            val list = remember(pack.slug, book) { DeenLibrary.hadiths(c, pack.slug, book!!.book) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { TextButton(onClick = { book = null; if (DeenLibrary.hadithBooks(c, pack.slug).isEmpty()) ed = null }) { Text("← ${book!!.name}") } }
                items(list, key = { it.ord }) { h -> HadithCard(pack, h) }
            }
        }
    }
}

@Composable
private fun HadithCard(pack: DeenLibrary.Pack, h: DeenLibrary.Hadith) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${pack.name} ${h.n}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Body(h.text, rtl = pack.rtl)
            if (h.grade.isNotBlank()) Text(h.grade, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ------------------------------------------------------------------ Search

@Composable
private fun SearchTab(c: Context) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<DeenLibrary.Hit>>(emptyList()) }
    LaunchedEffect(q) { com.nizam.app.ui.design.guard {
        kotlinx.coroutines.delay(250)
        hits = withContext(Dispatchers.IO) { DeenLibrary.search(c, q) }
    } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search the Quran and hadith: patience, صبر, mother…") }, singleLine = true)
        Text(if (q.isBlank()) "Searches every translation and hadith book you've downloaded, offline." else "${hits.size} results",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits) { h ->
                val title = if (h.ref.startsWith("q:")) h.ref.split(":").let { "Quran ${it[1]}:${it[2]} · ${Surahs.get(it[1].toInt()).name}" }
                else DeenLibrary.hadithAt(c, h.ed, h.ref.removePrefix("h:").toInt())?.let { "${DeenLibrary.packName(c, h.ed)} ${it.n}" } ?: h.ed
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (h.ref.startsWith("q:")) Text(DeenLibrary.packName(c, h.ed), style = MaterialTheme.typography.labelSmall)
                        Body(h.snippet, rtl = h.snippet.any { it in '؀'..'ۿ' })
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Talks

@Composable
private fun TalksTab(container: AppContainer, urdu: Boolean, reader: Reader) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var text by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var topic by remember { mutableStateOf("") }

    fun run(t: String, block: suspend () -> String) {
        busy = true; error = null; title = t
        scope.launch {
            runCatching { block() }.onSuccess { text = it }.onFailure { error = it.message ?: "Couldn't prepare the talk" }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel("🕌 This week's khutbah") {
                Text("A Jumu'ah-style talk tying together this week's passages, their tafsir and hadith. Read it, or listen with the screen off.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { run("This week's khutbah") { DailyPath.khutbah(c, container.settingsRepository, urdu) } }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Prepare the khutbah") }
            }
        }
        item {
            Panel("🎙️ A lecture on anything") {
                OutlinedTextField(topic, { topic = it }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. anxiety, rights of a wife, honesty in business, death") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("patience", "parents", "forgiveness", "anger", "gratitude", "death", "repentance", "honesty").forEach { t ->
                        AssistChip(onClick = { topic = t }, label = { Text(t) })
                    }
                }
                Button(onClick = { val t = topic.trim(); run("On $t") { DailyPath.lecture(c, container.settingsRepository, t, urdu) } },
                    enabled = !busy && topic.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Give me the lecture") }
                Text("Built only from the ayahs and hadith your offline search finds, each one cited.", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp); Text("  Preparing $title…") } }
        error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        text?.let { t ->
            item {
                Panel(title) {
                    Row { TextButton(onClick = { reader.toggle(t) }) { Text(if (reader.speaking) "⏹ Stop" else "🔊 Listen") } }
                    Body(t, rtl = urdu)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Library (downloads)

@Composable
private fun LibraryTab(c: Context, installed: List<DeenLibrary.Pack>, progress: Map<String, DeenLibrary.Progress>, changed: () -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var catalog by remember { mutableStateOf<List<DeenLibrary.Pack>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(DeenLibrary.Kind.TAFSIR) }
    var lang by remember { mutableStateOf("Urdu") }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { runCatching { DeenLibrary.catalog(c) }.onSuccess { catalog = it }.onFailure { error = it.message } } }
    LaunchedEffect(progress.size) { com.nizam.app.ui.design.guard { changed() } }
    val have = installed.map { it.id }.toSet()
    val langs = catalog.filter { it.kind == kind }.map { it.lang }.distinct().sortedBy { when (it) { "Urdu" -> 0; "English" -> 1; "Arabic" -> 2; else -> 3 } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val missing = DeenLibrary.ESSENTIALS.filter { it.id !in have }
            if (missing.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Essentials", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Quran in Arabic, Urdu and English · Bukhari and Muslim in Urdu and English · Nawawi's 40 · Hadith Qudsi · Ibn Kathir (Urdu, English) · Maarif-ul-Quran", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { DeenLibrary.download(c, missing) }, modifier = Modifier.padding(top = 8.dp)) { Text("Download ${missing.size} books") }
                }
            }
        }
        if (progress.isNotEmpty()) {
            item { Text("Downloading", style = MaterialTheme.typography.titleSmall) }
            items(progress.values.toList(), key = { "p" + it.packId }) { p ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    if (p.error != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        val pack = (catalog + DeenLibrary.ESSENTIALS).firstOrNull { it.id == p.packId }
                        if (pack != null) TextButton(onClick = { DeenLibrary.download(c, listOf(pack)) }) { Text("Retry") }
                    } else if (p.fraction > 0f) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (installed.isNotEmpty()) {
            item { Text("On your phone · works offline", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(installed, key = { "i" + it.id }) { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${p.kind.label} · ${p.lang}${if (p.author.isNotBlank() && p.author != p.name) " · ${p.author}" else ""}", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { DeenLibrary.remove(c, p); withContext(Dispatchers.Main) { changed() } } }) { Text("Remove") }
                }
            }
        }
        item {
            Text("All books", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DeenLibrary.Kind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                langs.forEach { l -> FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(l) }) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (catalog.isEmpty() && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        items(catalog.filter { it.kind == kind && it.lang == lang }, key = { "c" + it.id }) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    if (p.author.isNotBlank() && p.author != p.name) Text(p.author, style = MaterialTheme.typography.labelSmall)
                }
                when {
                    p.id in have -> Text("✓", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                    p.id in progress -> TextButton(onClick = { DeenLibrary.cancel(p) }) { Text("Cancel") }
                    else -> TextButton(onClick = { DeenLibrary.download(c, listOf(p)) }) { Text("Get") }
                }
            }
        }
        item {
            Text("Texts come from open datasets (fawazahmed0 quran-api and hadith-api, spa5k tafsir_api) and are stored on your phone.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Panel(title: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp)) }
            content()
        }
    }
}

@Composable
private fun ArabicLine(text: String, n: Int?) {
    Text(text + (n?.let { " ﴿$it﴾" } ?: ""), Modifier.fillMaxWidth().padding(vertical = 4.dp), fontSize = 24.sp, lineHeight = 44.sp,
        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl), textAlign = TextAlign.Right)
}

@Composable
private fun Body(text: String, rtl: Boolean) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 3.dp),
        style = MaterialTheme.typography.bodyMedium.copy(textDirection = if (rtl) TextDirection.Rtl else TextDirection.Content, lineHeight = if (rtl) 28.sp else 22.sp),
        fontSize = if (rtl) 17.sp else 15.sp, textAlign = if (rtl) TextAlign.Right else TextAlign.Start)
}

@Composable
private fun Expandable(title: String, text: String, rtl: Boolean) {
    var open by remember { mutableStateOf(false) }
    Panel(title) {
        Body(if (open) text else text.take(500) + if (text.length > 500) "…" else "", rtl)
        if (text.length > 500) TextButton(onClick = { open = !open }) { Text(if (open) "Show less" else "Read the full tafsir") }
    }
}

/** Reads long texts aloud in pieces so lessons and khutbahs play all the way through. */
private class Reader(c: Context) {
    var speaking by mutableStateOf(false)
    private var ready = false
    private val tts = TextToSpeech(c.applicationContext) { ready = it == TextToSpeech.SUCCESS }.also { t ->
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == "last") speaking = false }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking = false }
        })
    }

    fun toggle(text: String) {
        if (speaking) { tts.stop(); speaking = false; return }
        if (!ready) return
        tts.language = if (text.count { it in '؀'..'ۿ' } > text.length / 4) Locale("ur", "PK") else Locale.ENGLISH
        val clean = text.replace(Regex("[#*_`>]"), "")
        val parts = clean.split(Regex("(?<=[.!?۔\\n])")).map { it.trim() }.filter { it.isNotEmpty() }
            .fold(mutableListOf<String>()) { acc, p -> if (acc.isNotEmpty() && acc.last().length + p.length < 3000) acc[acc.lastIndex] = acc.last() + " " + p else acc += p; acc }
        parts.forEachIndexed { i, p -> tts.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) "last" else "p$i") }
        speaking = parts.isNotEmpty()
    }

    fun release() = runCatching { tts.stop(); tts.shutdown() }
}

@Composable
private fun rememberReader(c: Context): Reader {
    val r = remember { Reader(c) }
    DisposableEffect(Unit) { onDispose { r.release() } }
    return r
}
