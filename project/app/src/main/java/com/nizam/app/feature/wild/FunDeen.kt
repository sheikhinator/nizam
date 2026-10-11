package com.nizam.app.feature.wild

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.AtlasCard
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos

object FunDeen {
    val all: List<Spec> = listOf(
        ScreenSpec("radiohost", "Your radio show", "An AI host between your songs: news, weather, your day", Icons.Outlined.Radio, Hub.Media, "radio host dj show music news between songs") { c, _ -> RadioHostScreen(c) },
        ScreenSpec("palace", "Memory palace", "Walk through rooms where your notes live", Icons.Outlined.Castle, Hub.Memory, "memory palace rooms notes walk 3d remember") { c, _ -> PalaceScreen(c) },
        ScreenSpec("bosses", "Life RPG bosses", "Big goals become bosses your daily wins defeat", Icons.Outlined.SportsKabaddi, Hub.Memory, "rpg boss fight goal game level health quest") { c, _ -> BossScreen(c) },
        ScreenSpec("socialdigest", "Silent social digest", "Instagram, X and groups — summarised, without opening them", Icons.Outlined.MarkChatRead, Hub.Social, "social digest instagram twitter x whatsapp groups summary notifications") { c, _ -> SocialDigestScreen(c) },
        ScreenSpec("masjidlive", "Masjid jamaat times", "Jamaat times at masjids near you, shared by the community", Icons.Outlined.Mosque, Hub.Deen, "masjid mosque jamaat times near me prayer congregation") { c, _ -> MasjidScreen(c) },
        ScreenSpec("tajweed", "Recitation coach", "Recite an ayah; Atlas checks every word", Icons.Outlined.MenuBook, Hub.Deen, "tajweed recitation quran check hifz coach ayah") { _, _ -> TajweedScreen() },
    )
}

// ─────────────────────────────── Radio host ───────────────────────────────
@Composable
fun RadioHostScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf("Desi RJ") }
    var live by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf("") }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) { var t: TextToSpeech? = null; t = TextToSpeech(c) { if (it == TextToSpeech.SUCCESS) tts = t }; onDispose { live = false; t?.shutdown() } }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        val tracks = runCatching { com.nizam.app.feature.music.MusicLibrary.device(c) }.getOrDefault(emptyList()).shuffled().take(30)
        if (tracks.isEmpty()) { now = "No songs on this phone — add some music first."; live = false; return@LaunchedEffect }
        var i = 0
        while (isActive && live && i < tracks.size) {
            val next = tracks[i]
            val tasks = runCatching { container.taskRepository.all().first().filter { it.completedAt == null && it.deletedAt == null }.take(3).map { it.title } }.getOrDefault(emptyList())
            val weather = runCatching { JSONObject(c.getSharedPreferences("weather_pro", 0).getString("last", null)!!).getJSONObject("current").let { "${it.optDouble("temperature_2m").toInt()}°" } }.getOrDefault("")
            val bit = runCatching { Ai.generate(container.settingsRepository,
                "Time: ${LocalTime.now().withSecond(0).withNano(0)}. Weather: $weather. Next prayer: ${com.nizam.app.ui.design.Island.nextPrayer?.first ?: ""}. " +
                    "Listener's open tasks: ${tasks.joinToString()}. Next song: ${next.title} by ${next.artist}. " + (if (i % 3 == 0) "Include one real headline from today." else ""),
                "You are a $style radio host for one listener. Say 2–3 lively spoken sentences, then introduce the next song. No emojis, no stage directions.", 250, useWeb = i % 3 == 0) }
                .getOrElse { "And now, ${next.title}." }
            now = bit
            com.nizam.app.feature.music.MusicPlayback.player?.pause()
            tts?.speak(bit, TextToSpeech.QUEUE_FLUSH, null, "host")
            delay(800); while (tts?.isSpeaking == true && live) delay(300)
            if (!live) break
            now = "▶ ${next.title} — ${next.artist}"
            com.nizam.app.feature.music.MusicPlayback.play(c, listOf(next), 0)
            delay(3000)
            while (live && com.nizam.app.feature.music.MusicPlayback.player?.isPlaying == true) delay(1000)
            i++
        }
        live = false
    }
    AtlasScreen(title = "Your radio show", subtitle = if (live) "On air" else "Your songs, with a host", hub = Hub.Media) {
        item("a") { Column(Modifier.padding(horizontal = Space.page)) {
            Art3D("microphone", 110.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Desi RJ", "Calm night", "Morning show", "BBC newsreader", "Sports commentator").forEach { s -> Capsule(s, style == s) { style = s } } }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!live) Button(onClick = { live = true }) { Text("🔴 Go live") } else OutlinedButton(onClick = { live = false; tts?.stop(); com.nizam.app.feature.music.MusicPlayback.player?.pause() }) { Text("Stop") }
            }
            if (now.isNotBlank()) Text(now, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        } }
    }
}

// ─────────────────────────────── Memory palace ───────────────────────────────
@Composable
fun PalaceScreen(container: AppContainer) {
    val notes by container.noteRepository.all().collectAsStateCompat()
    val rooms = remember(notes) {
        notes.groupBy { n -> n.title.substringBefore('/', "").ifBlank { Regex("#(\\w+)").find(n.body)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() } ?: "Hall" } }
            .toList().sortedByDescending { it.second.size }.take(8)
    }
    var room by remember { mutableIntStateOf(0) }
    var wall by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<com.nizam.app.feature.notes.Note?>(null) }
    val turn = remember { Animatable(0f) }
    LaunchedEffect(wall) { turn.snapTo(if (turn.value >= 0) -60f else 60f); turn.animateTo(0f, tween(450)) }
    open?.let { n -> AlertDialog(onDismissRequest = { open = null }, confirmButton = { TextButton(onClick = { open = null }) { Text("Close") } },
        title = { Text(n.title) }, text = { Text(n.body.take(1500)) }) }
    val colours = listOf(Color(0xFF7C3AED), Color(0xFF0EA5E9), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF6366F1), Color(0xFF14B8A6), Color(0xFFEF4444))
    Column(Modifier.fillMaxSize()) {
        Text("Memory palace", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rooms.forEachIndexed { i, (name, l) -> Capsule("🚪 $name (${l.size})", room == i) { room = i; wall = 0 } } }
        if (rooms.isEmpty()) { Text("Write a few notes and your palace builds itself — one room per folder or #tag.", modifier = Modifier.padding(18.dp)); return }
        val (name, list) = rooms[room.coerceIn(0, rooms.lastIndex)]
        val walls = list.chunked(6)
        val shown = walls.getOrElse(wall.coerceIn(0, (walls.size - 1).coerceAtLeast(0))) { emptyList() }
        val tint = colours[room % colours.size]
        Box(Modifier.weight(1f).fillMaxWidth().padding(18.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.35f), Color(0xFF1F1A17), Color(0xFF3B2F2A))))
            .pointerInput(walls.size) { detectHorizontalDragGestures { ch, d -> ch.consume()
                if (d < -30 && wall < walls.size - 1) wall++ else if (d > 30 && wall > 0) wall-- } }) {
            // floor and the far wall, drawn in perspective, turning as you walk
            Column(Modifier.fillMaxSize().graphicsLayer { rotationY = turn.value; cameraDistance = 10f * density }.padding(22.dp)) {
                Text("$name · wall ${wall + 1} of ${walls.size.coerceAtLeast(1)}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp))
                shown.chunked(2).forEachIndexed { shelf, pair ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { n -> Column(Modifier.weight(1f).height(110.dp).graphicsLayer { rotationX = 6f - shelf * 4f }.clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFFFF7E6)).border(3.dp, Color(0xFF8B5E3C), RoundedCornerShape(14.dp)).clickable { open = n }.padding(10.dp)) {
                            Text(n.title, color = Color(0xFF3B2F2A), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Text(n.body.take(90), color = Color(0xFF6B5A4E), fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        } }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF8B5E3C)))          // the shelf
                }
            }
            Text(if (walls.size > 1) "← swipe to turn to the next wall →" else "", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.collectAsStateCompat() = collectAsState(initial = emptyList())
private fun Modifier.noop() = this

@Composable private fun Spacer(m: Modifier) = androidx.compose.foundation.layout.Spacer(m)

// ─────────────────────────────── Life RPG bosses ───────────────────────────────
object Bosses {
    fun store(c: Context) = JsonStore(c, "bosses")
    val SOURCES = linkedMapOf("tasks" to "Every task finished (10 dmg)", "prayers" to "Every prayer on time (8 dmg)", "habits" to "Every habit done (6 dmg)",
        "saving" to "Every Rs 1,000 not spent vs last month (5 dmg)", "manual" to "Hits you log yourself")
    /** Damage dealt since the boss appeared, worked out from what you actually did. */
    suspend fun damage(c: Context, container: AppContainer, b: JSONObject): Int {
        val since = b.optLong("created"); val sinceDate = Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val src = (b.optJSONArray("src") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
        var d = b.optInt("manual")
        if ("tasks" in src) d += runCatching { container.taskRepository.all().first().count { (it.completedAt ?: 0) > since && (b.optString("kw").isBlank() || it.title.contains(b.optString("kw"), true)) } }.getOrDefault(0) * 10
        if ("habits" in src) d += runCatching { container.habitRepository.entriesSince(sinceDate).first().size }.getOrDefault(0) * 6
        if ("prayers" in src) d += runCatching { com.nizam.app.feature.progress.Xp.summarise(container).let { 0 } }.getOrDefault(0) +
            c.getSharedPreferences("bosses_prayer", 0).getInt(b.optString("id"), 0) * 8
        if ("saving" in src) d += runCatching {
            val now = LocalDate.now(); val txns = container.financeRepository.since(now.minusMonths(1).withDayOfMonth(1).toString()).first().filter { it.kind == "EXPENSE" }
            val last = txns.filter { it.localDate < now.withDayOfMonth(1).toString() && it.localDate <= now.minusMonths(1).toString() }.sumOf { it.amount }
            val cur = txns.filter { it.localDate >= now.withDayOfMonth(1).toString() }.sumOf { it.amount }
            ((last - cur) / 1000).toInt().coerceAtLeast(0) * 5 }.getOrDefault(0)
        return d
    }
}

@Composable
fun BossScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick) { Bosses.store(c).all() }
    val dmg = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    LaunchedEffect(tick) { list.forEach { b -> dmg[b.optString("id")] = runCatching { Bosses.damage(c, container, b) }.getOrDefault(0) } }
    var name by remember { mutableStateOf("") }; var goal by remember { mutableStateOf("") }; var kw by remember { mutableStateOf("") }
    var hp by remember { mutableStateOf("500") }; var src by remember { mutableStateOf(setOf("tasks", "habits")) }
    val shake = remember { Animatable(0f) }
    AtlasScreen(title = "Life RPG bosses", subtitle = "Real progress is the only weapon", hub = Hub.Memory) {
        list.forEach { b -> item(b.optString("id") + tick) {
            val max = b.optInt("hp", 500); val d = (dmg[b.optString("id")] ?: 0).coerceAtMost(max); val left = max - d
            Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.graphicsLayer { translationX = shake.value }) { Art3D(if (left <= 0) "trophy" else b.optString("art", "dragon"), 72.dp) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(if (left <= 0) "${b.optString("name")} — DEFEATED" else b.optString("name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = if (left <= 0) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurface)
                        Text(b.optString("goal"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LinearProgressIndicator({ left.toFloat() / max }, Modifier.fillMaxWidth().height(12.dp).padding(top = 8.dp).clip(RoundedCornerShape(6.dp)), color = Color(0xFFEF4444))
                Text("$left / $max HP · you've dealt $d", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Capsule("⚔️ Log a hit (+15)", false) { Bosses.store(c).update(b.optString("id")) { it.put("manual", it.optInt("manual") + 15) }
                        Haptics.play(c, "done", force = true); scope.launch { repeat(4) { i -> shake.animateTo(if (i % 2 == 0) 12f else -12f, tween(50)) }; shake.animateTo(0f) }; tick++ }
                    if ("prayers" in (0 until (b.optJSONArray("src")?.length() ?: 0)).map { b.optJSONArray("src")!!.optString(it) })
                        Capsule("🕌 Prayed on time", false) { val p = c.getSharedPreferences("bosses_prayer", 0); p.edit().putInt(b.optString("id"), p.getInt(b.optString("id"), 0) + 1).apply(); tick++ }
                    Capsule("✕", false) { Bosses.store(c).remove(b.optString("id")); tick++ }
                }
            }
        } }
        item("new") { SectionHeader("Summon a new boss") }
        item("f") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Boss name") }, placeholder = { Text("The Debt Dragon") })
            OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text("The real goal") }, placeholder = { Text("Pay off my 2 lakh loan by March") })
            OutlinedTextField(hp, { hp = it.filter { ch -> ch.isDigit() } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Health points") })
            Text("Damage comes from:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Bosses.SOURCES.forEach { (k, l) -> Row(verticalAlignment = Alignment.CenterVertically) { Switch(k in src, { src = if (it) src + k else src - k }); Text("  $l", style = MaterialTheme.typography.bodySmall) } }
            if ("tasks" in src) OutlinedTextField(kw, { kw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Only tasks containing (optional)") })
            Button(onClick = { Bosses.store(c).add(JSONObject().put("name", name).put("goal", goal).put("hp", hp.toIntOrNull() ?: 500).put("src", JSONArray(src.toList()))
                .put("kw", kw.trim()).put("created", System.currentTimeMillis()).put("art", listOf("dragon", "robot", "ghost", "alien_monster", "ogre").random()))
                name = ""; goal = ""; tick++ }, enabled = name.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Summon") }
        } }
    }
}

// ─────────────────────────────── Silent social digest ───────────────────────────────
object SocialDigest {
    val APPS = mapOf("com.instagram.android" to "Instagram", "com.twitter.android" to "X", "com.whatsapp" to "WhatsApp", "com.facebook.katana" to "Facebook",
        "com.facebook.orca" to "Messenger", "com.zhiliaoapp.musically" to "TikTok", "com.snapchat.android" to "Snapchat", "com.linkedin.android" to "LinkedIn",
        "org.telegram.messenger" to "Telegram", "com.reddit.frontpage" to "Reddit", "com.google.android.youtube" to "YouTube", "com.discord" to "Discord")
    private fun p(c: Context) = c.getSharedPreferences("social_digest", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    fun store(c: Context) = JsonStore(c, "social_inbox")
    fun collect(c: Context, pkg: String, title: String, text: String) {
        if (!on(c)) return
        val app = APPS[pkg] ?: return
        val s = store(c)
        s.save((s.all() + JSONObject().put("app", app).put("title", title.take(80)).put("text", text.take(400)).put("t", System.currentTimeMillis())).takeLast(600))
    }
    suspend fun digest(c: Context, container: AppContainer): String {
        val items = store(c).all().filter { it.optLong("t") > System.currentTimeMillis() - 24 * 3_600_000L }
        if (items.isEmpty()) return "Nothing came in from your social apps in the last day."
        return Ai.generate(container.settingsRepository, items.groupBy { it.optString("app") }.entries.joinToString("\n\n") { (a, l) ->
            "$a:\n" + l.takeLast(80).joinToString("\n") { "${it.optString("title")}: ${it.optString("text")}" } },
            "Write a calm daily digest of the user's social apps from their notifications. Per app: who reached out personally (reply-worthy first), " +
                "what groups talked about, anything time-sensitive. Skip likes/spam/promos. Markdown, short.", 1200)
    }
}

@Composable
fun SocialDigestScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(SocialDigest.on(c)) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val count = remember(busy) { SocialDigest.store(c).all().count { it.optLong("t") > System.currentTimeMillis() - 86_400_000L } }
    AtlasScreen(title = "Silent social digest", subtitle = "$count notifications gathered today", hub = Hub.Social) {
        item("t") { Column(Modifier.padding(horizontal = Space.page)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(on, { on = it; SocialDigest.setOn(c, it) }); Text("  Gather social notifications quietly") }
            Text("Uses notification access (already used by Atlas). Stays on this phone. Tip: then mute those apps and read one digest a day.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { busy = true; scope.launch { text = runCatching { SocialDigest.digest(c, container) }.getOrElse { Feedback.friendly(it) }; busy = false } },
                enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text(if (busy) "Reading…" else "Today's digest") }
        } }
        if (text.isNotBlank()) item("d") { Group(Modifier.padding(Space.page)) { Md(text) } }
    }
}

// ─────────────────────────────── Masjid jamaat times ───────────────────────────────
object Masjids {
    fun store(c: Context) = JsonStore(c, "masjids")
    val PRAYERS = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha", "Jummah")
    /** Merges a shared card: newer times win. */
    fun merge(c: Context, card: JSONObject): Int {
        val a = card.optJSONArray("masjids") ?: return 0
        var n = 0
        for (i in 0 until a.length()) { val m = a.getJSONObject(i)
            val mine = store(c).all().firstOrNull { it.optString("name").equals(m.optString("name"), true) }
            if (mine == null) { store(c).add(m.put("id", System.nanoTime().toString())); n++ }
            else if (m.optLong("updated") > mine.optLong("updated")) { store(c).update(mine.optString("id")) { it.put("times", m.optJSONObject("times")).put("updated", m.optLong("updated")).put("by", m.optString("by")) }; n++ }
        }
        return n
    }
}

@Composable
fun MasjidScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick) { Masjids.store(c).all() }
    var edit by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf("") }
    edit?.let { m -> var times by remember { mutableStateOf(Masjids.PRAYERS.associateWith { m.optJSONObject("times")?.optString(it).orEmpty() }) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(m.optString("name")) }, text = { Column {
            Masjids.PRAYERS.forEach { p -> OutlinedTextField(times[p].orEmpty(), { v -> times = times + (p to v) }, singleLine = true, label = { Text("$p jamaat (e.g. 1:30)") }) } } },
            confirmButton = { TextButton(onClick = { Masjids.store(c).update(m.optString("id")) { it.put("times", JSONObject(times as Map<*, *>)).put("updated", System.currentTimeMillis())
                .put("by", c.getSharedPreferences("atlascard", 0).getString("from", "me")) }; edit = null; tick++ }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } }) }
    AtlasScreen(title = "Masjid jamaat times", subtitle = "Shared person to person — no server", hub = Hub.Deen) {
        item("b") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { busy = true; scope.launch {
                runCatching {
                    val lat = container.settingsRepository.latitude.first(); val lon = container.settingsRepository.longitude.first(); val d = 0.03
                    val q = "[out:json][timeout:20];(nwr[amenity=place_of_worship][religion=muslim](${lat - d},${lon - d},${lat + d},${lon + d}););out center tags 40;"
                    val els = JSONObject(Http.getJson("https://overpass-api.de/api/interpreter?data=" + java.net.URLEncoder.encode(q, "UTF-8"))).optJSONArray("elements") ?: JSONArray()
                    var added = 0
                    for (i in 0 until els.length()) { val e = els.getJSONObject(i); val name = e.optJSONObject("tags")?.optString("name").orEmpty(); if (name.isBlank()) continue
                        val la = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
                        val lo = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
                        if (Masjids.store(c).all().none { it.optString("name") == name }) { Masjids.store(c).add(JSONObject().put("name", name).put("lat", la).put("lon", lo)
                            .put("km", Math.hypot((la - lat) * 111, (lo - lon) * 111 * cos(Math.toRadians(lat)))).put("times", JSONObject())); added++ } }
                    Feedback.show("Found $added masjids nearby")
                }.onFailure { Feedback.failed("Finding masjids", it) }
                busy = false; tick++ } }, enabled = !busy) { Text(if (busy) "Searching…" else "Find masjids near me") }
            OutlinedButton(onClick = { com.nizam.app.feature.nx.Share.text(c, "Jamaat times near us — open Atlas → Masjid jamaat times → paste:\n" +
                AtlasCard.encode(JSONObject().put("v", 1).put("kind", "Masjid").put("title", "Jamaat times").put("masjids", JSONArray(list.filter { (it.optJSONObject("times")?.length() ?: 0) > 0 })))) }) {
                Text("Share times") }
        } }
        item("p") { Row(Modifier.padding(Space.page), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(paste, { paste = it }, Modifier.weight(1f), singleLine = true, label = { Text("Paste times someone shared") })
            Button(onClick = { AtlasCard.decode(paste)?.let { Feedback.show("Updated ${Masjids.merge(c, it)} masjids") } ?: Feedback.show("That isn't an Atlas card"); paste = ""; tick++ },
                enabled = paste.contains("ATLAS1:"), modifier = Modifier.padding(start = 8.dp)) { Text("Add") }
        } }
        list.sortedBy { it.optDouble("km", 99.0) }.forEach { m -> item(m.optString("id")) {
            val times = m.optJSONObject("times") ?: JSONObject()
            val nowT = LocalTime.now()
            val next = Masjids.PRAYERS.filter { it != "Jummah" }.mapNotNull { p -> times.optString(p).takeIf { it.isNotBlank() }?.let { t -> runCatching {
                val (h, mi) = t.split(':').map { it.trim().filter { ch -> ch.isDigit() }.toInt() }
                val hh = if (p != "Fajr" && h < 11) h + 12 else h; p to LocalTime.of(hh % 24, mi) }.getOrNull() } }.firstOrNull { it.second.isAfter(nowT) }
            ListRow(m.optString("name"), supporting = listOfNotNull("%.1f km".format(m.optDouble("km", 0.0)),
                next?.let { "${it.first} jamaat ${it.second}" }, m.optString("by").takeIf { it.isNotBlank() }?.let { "updated by $it" }).joinToString(" · ").ifBlank { "No times yet — tap to add" },
                icon = Icons.Outlined.Mosque) { edit = m }
        } }
    }
}

// ─────────────────────────────── Recitation coach ───────────────────────────────
@Composable
fun TajweedScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var ref by remember { mutableStateOf("1:1") }
    var ayah by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val rec = remember { if (SpeechRecognizer.isRecognitionAvailable(c)) SpeechRecognizer.createSpeechRecognizer(c) else null }
    DisposableEffect(Unit) { onDispose { rec?.destroy() } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun norm(s: String) = s.replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]"), "").replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ة", "ه").replace("ى", "ي")
        .split(Regex("\\s+")).filter { it.isNotBlank() }
    AtlasScreen(title = "Recitation coach", subtitle = "Checks your words against the ayah", hub = Hub.Deen) {
        item("r") { Column(Modifier.padding(horizontal = Space.page)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ref, { ref = it }, Modifier.weight(1f), singleLine = true, label = { Text("Surah:Ayah (e.g. 2:255)") })
                Button(onClick = { scope.launch { ayah = runCatching { JSONObject(Http.getJson("https://api.alquran.cloud/v1/ayah/${ref.trim()}/quran-simple-clean")).getJSONObject("data").optString("text") }
                    .getOrElse { "Couldn't load that ayah" }; heard = "" } }, modifier = Modifier.padding(start = 8.dp)) { Text("Load") }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("1:1" to "Fatiha 1", "112:1" to "Ikhlas", "2:255" to "Ayat al-Kursi", "113:1" to "Falaq").forEach { (k, l) -> Capsule(l, ref == k) { ref = k } } }
        } }
        if (ayah.isNotBlank()) item("a") { Group(Modifier.padding(Space.page)) {
            Text(ayah, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { mic.launch(Manifest.permission.RECORD_AUDIO); return@Button }
                val r = rec ?: run { Feedback.show("No speech recogniser on this phone"); return@Button }
                listening = true
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) {} ; override fun onBeginningOfSpeech() {} ; override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {} ; override fun onEndOfSpeech() { listening = false }
                    override fun onError(e: Int) { listening = false; Feedback.show("Didn't catch that — try again") }
                    override fun onResults(b: Bundle?) { listening = false; heard = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
                    override fun onPartialResults(b: Bundle?) {} ; override fun onEvent(t: Int, b: Bundle?) {}
                })
                r.startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA").putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L))
            }, enabled = !listening, modifier = Modifier.padding(top = 10.dp)) { Text(if (listening) "Listening… recite now" else "🎙 Recite") }
        } }
        if (heard.isNotBlank()) item("res") { Group(Modifier.padding(horizontal = Space.page)) {
            val want = norm(ayah); val got = norm(heard)
            val ok = want.map { w -> got.any { it == w || (it.length > 3 && w.length > 3 && (it.contains(w) || w.contains(it))) } }
            val score = if (want.isEmpty()) 0 else ok.count { it } * 100 / want.size
            Text("$score% of words matched", style = MaterialTheme.typography.titleMedium, color = if (score >= 85) Color(0xFF16A34A) else Color(0xFFF59E0B))
            Text(want.mapIndexed { i, w -> if (ok[i]) w else "【$w】" }.joinToString(" "), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            Text("Words in brackets were missed or unclear. This checks the words, not makharij or madd — for tajweed rules, recite to a qari.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        } }
    }
}
