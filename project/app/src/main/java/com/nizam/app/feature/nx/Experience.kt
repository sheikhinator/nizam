package com.nizam.app.feature.nx

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Art
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Celebrate
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.Mesh3D
import com.nizam.app.ui.design.Object3D
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import com.nizam.app.ui.design.styledCard
import com.nizam.app.ui.design.Radius
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

object Experience {
    val all: List<Spec> = listOf(
        ScreenSpec("stories", "Your week in stories", "A swipeable recap of your week", Icons.Outlined.AutoStories, Hub.Memory, "recap week stories summary wrapped") { c, o -> StoriesScreen(c, o) },
        ScreenSpec("badges", "Badges", "Achievements you've earned across Atlas", Icons.Outlined.EmojiEvents, Hub.Memory, "badges achievements trophies streaks") { c, _ -> BadgesScreen(c) },
        ScreenSpec("moodwheel", "Mood wheel", "Check in with a tap; see your month", Icons.Outlined.Mood, Hub.Health, "mood feelings emotion check in") { _, _ -> MoodWheelScreen() },
        ScreenSpec("visionboard", "Vision board", "The life you're building, in pictures", Icons.Outlined.Dashboard, Hub.Memory, "vision board goals dreams pictures") { _, _ -> VisionBoardScreen() },
        ScreenSpec("wisdom", "Daily wisdom & intention", "A verse, a saying and your intention for today", Icons.Outlined.Lightbulb, Hub.Deen, "wisdom quote ayah hadith intention niyyah") { c, _ -> WisdomScreen(c) },
        ScreenSpec("adventures", "Pet adventures", "Send your pet travelling; it brings back postcards", Icons.Outlined.Luggage, Hub.Social, "pet adventure trip postcard travel") { c, _ -> AdventuresScreen(c) },
        ScreenSpec("briefing", "Audio briefing", "Your day read aloud: prayers, weather, tasks, money", Icons.Outlined.RecordVoiceOver, Hub.Work, "briefing morning audio listen day summary") { c, _ -> BriefingScreen(c) },
        ScreenSpec("qibla3d", "Qibla 3D", "A 3D Kaaba that turns to face Makkah", Icons.Outlined.Explore, Hub.Deen, "qibla kaaba direction compass 3d makkah") { c, _ -> Qibla3DScreen(c) },
        ScreenSpec("globe", "World clock globe", "Spin the Earth; see the time everywhere", Icons.Outlined.Public, Hub.Travel, "globe world clock time zones earth 3d") { _, _ -> GlobeScreen() },
    )
}

// ─────────────────────────────── Stories ───────────────────────────────
private data class Page(val title: String, val big: String, val line: String, val art: String, val colors: List<Color>)

@Composable
fun StoriesScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var pages by remember { mutableStateOf<List<Page>?>(null) }
    var i by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val tasks = runCatching { container.taskRepository.all().first() }.getOrDefault(emptyList())
        val done = tasks.count { (it.completedAt ?: 0L) > weekAgo }
        val spent = runCatching { container.financeRepository.since(LocalDate.now().minusDays(7).toString()).first().filter { it.kind == "EXPENSE" }.sumOf { it.amount } }.getOrDefault(0.0)
        val pet = com.nizam.app.feature.companion.Companion.load(ctx)
        val moods = (0..6).mapNotNull { ctx.getSharedPreferences("mood_wheel", 0).getString("today_${LocalDate.now().minusDays(it.toLong())}", null) }
        val reads = JsonStore(ctx, "read_later").all().count { it.optBoolean("read") }
        pages = listOf(
            Page("Your week", "7 days", "Here's how it went.", "sparkles", listOf(Color(0xFF3B2A8F), Color(0xFF7B4FD6))),
            Page("Things done", "$done", if (done >= 10) "A productive week. MashaAllah." else "Every step counts.", "check_mark_button", listOf(Color(0xFF0E6B52), Color(0xFF2FBF71))),
            Page("Money out", "Rs " + "%,d".format(spent.toLong()), "Across the last seven days.", "money_bag", listOf(Color(0xFF7A4B0E), Color(0xFFE0A030))),
            Page("How you felt", moods.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "—", if (moods.isEmpty()) "Try the mood wheel this week." else "Most common mood of ${moods.size} check-ins.",
                Art.MOODS.firstOrNull { it.second == moods.firstOrNull() }?.first ?: "smiling_face_with_smiling_eyes", listOf(Color(0xFF7A1E4B), Color(0xFFE0608E))),
            Page("Reading", "$reads", "articles finished from Read later.", "books", listOf(Color(0xFF14406B), Color(0xFF3F8AE0))),
            Page(pet.name, "Level ${com.nizam.app.feature.companion.Companion.level(com.nizam.app.feature.companion.Companion.xp(pet, null))}", "grew with you this week.", "paw_prints", listOf(Color(0xFF5A2A0E), Color(0xFFE3834F))),
            Page("Next week", "Bismillah", "One intention at a time.", "rocket", listOf(Color(0xFF101830), Color(0xFF3B4FB0))),
        )
    } }
    val ps = pages
    if (ps == null) { AtlasScreen(title = "Your week", loading = true) {}; return }
    LaunchedEffect(i) { com.nizam.app.ui.design.guard { progress = 0f; while (progress < 1f) { delay(50); progress += 0.01f }; if (i < ps.lastIndex) i++ } }
    val p = ps[i]
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(p.colors)).pointerInput(ps.size) {
        detectTapGestures { o -> if (o.x < size.width / 3) { if (i > 0) i-- } else if (i < ps.lastIndex) i++ else open("today") } }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ps.indices.forEach { k -> LinearProgressIndicator(progress = { if (k < i) 1f else if (k == i) progress else 0f }, modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Color.White, trackColor = Color.White.copy(alpha = 0.3f)) }
        }
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Art3D(p.art, 180.dp)
            Text(p.title.uppercase(), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
            Text(p.big, color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text(p.line, color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        Text("Tap right for next, left for back", Modifier.align(Alignment.BottomCenter).padding(24.dp), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

// ─────────────────────────────── Badges ───────────────────────────────
private data class Badge(val name: String, val how: String, val art: String, val earned: Boolean)

@Composable
fun BadgesScreen(container: AppContainer) {
    val ctx = LocalContext.current
    var badges by remember { mutableStateOf<List<Badge>?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        val tasks = runCatching { container.taskRepository.all().first() }.getOrDefault(emptyList())
        val done = tasks.count { it.completedAt != null }
        val pet = com.nizam.app.feature.companion.Companion.load(ctx); val lvl = com.nizam.app.feature.companion.Companion.level(com.nizam.app.feature.companion.Companion.xp(pet, null))
        fun n(store: String) = JsonStore(ctx, store).all().size
        val moods = ctx.getSharedPreferences("mood_wheel", 0).all.size
        val qaza = ctx.getSharedPreferences("qaza", 0).getInt("made", 0)
        badges = listOf(
            Badge("First step", "Complete a task", "check_mark_button", done >= 1), Badge("Getting things done", "Complete 50 tasks", "direct_hit", done >= 50),
            Badge("Centurion", "Complete 100 tasks", "trophy", done >= 100), Badge("Hatched", "Hatch your pet", "paw_prints", pet.hatched),
            Badge("Growing up", "Pet reaches level 10", "seedling", lvl >= 10), Badge("Elder", "Pet reaches level 35", "glowing_star", lvl >= 35),
            Badge("Bookworm", "Save 10 read-later articles", "books", n("read_later") >= 10), Badge("Researcher", "Run 5 deep research reports", "crystal_ball", n("research_reports") >= 5),
            Badge("Truth seeker", "Fact-check 5 claims", "shield", n("fact_checks") >= 5), Badge("Know thyself", "Log your mood 7 times", "sparkling_heart", moods >= 7),
            Badge("Qaza hero", "Make up 30 missed prayers", "mosque", qaza >= 30), Badge("Memory palace", "Create 20 flashcards", "brain", n("memory_cards") >= 20),
            Badge("Organised", "Note 10 places in Where is it?", "key", n("object_places") >= 10), Badge("Dreamer", "Pin 6 pictures to your vision board", "glowing_star", n("vision_board") >= 6),
            Badge("Explorer", "Send your pet on 3 adventures", "world_map", n("pet_adventures") >= 3), Badge("Stylist", "Try a different design kit", "artist_palette", com.nizam.app.ui.design.Style.s.kit != "Atlas Glass"),
        )
    } }
    val b = badges
    AtlasScreen(title = "Badges", subtitle = b?.let { "${it.count { x -> x.earned }} of ${it.size} earned" }, hub = Hub.Memory, loading = b == null) {
        b.orEmpty().chunked(3).forEachIndexed { r, row -> item("r$r") { Row(Modifier.padding(horizontal = Space.page, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { x -> Column(Modifier.weight(1f).styledCard(Radius.tile).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    if (x.earned) Art3D(x.art, 76.dp, float = false) else Text("🔒", fontSize = 34.sp)
                }
                Text(x.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                Text(x.how, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
            } }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        } } }
    }
}

// ─────────────────────────────── Mood wheel ───────────────────────────────
@Composable
fun MoodWheelScreen() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("mood_wheel", 0) }
    var today by remember { mutableStateOf(p.getString("today_${LocalDate.now()}", null)) }
    AtlasScreen(title = "Mood wheel", subtitle = today?.let { "Today: $it" } ?: "How are you, really?", hub = Hub.Health) {
        item("wheel") {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(Space.page), contentAlignment = Alignment.Center) {
                val n = Art.MOODS.size
                Art.MOODS.forEachIndexed { k, (art, label) ->
                    val a = -Math.PI / 2 + 2 * Math.PI * k / n
                    Column(Modifier.offset { IntOffset((cos(a) * 125.dp.toPx()).toInt(), (sin(a) * 125.dp.toPx()).toInt()) }.pressable {
                        today = label; p.edit().putString("today_${LocalDate.now()}", label).apply()
                        if (k < 5) Celebrate.now()
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Art3D(art, if (today == label) 70.dp else 52.dp, float = today == label, shadow = false)
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Art3D(Art.MOODS.firstOrNull { it.second == today }?.first ?: "sparkles", 96.dp)
            }
        }
        item("h") { SectionHeader("Last 30 days") }
        item("grid") { Column(Modifier.padding(horizontal = Space.page)) {
            (0 until 30).reversed().chunked(6).forEach { row -> Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { d -> val date = LocalDate.now().minusDays(d.toLong()); val m = p.getString("today_$date", null)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (m != null) Art3D(Art.MOODS.firstOrNull { it.second == m }?.first ?: "neutral_face", 30.dp, float = false, shadow = false) else Box(Modifier.size(30.dp))
                        Text("${date.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
                    } }
            } }
        } }
    }
}

// ─────────────────────────────── Vision board ───────────────────────────────
@Composable
fun VisionBoardScreen() {
    val ctx = LocalContext.current
    val store = remember { JsonStore(ctx, "vision_board") }
    var items by remember { mutableStateOf(store.all()) }
    var caption by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            store.add(JSONObject().put("uri", u.toString()).put("cap", caption.trim())); caption = ""; items = store.all() }
    }
    AtlasScreen(title = "Vision board", subtitle = "Pin what you're working towards", hub = Hub.Memory) {
        item("add") { Row(Modifier.padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedTextField(caption, { caption = it }, Modifier.weight(1f), placeholder = { Text("Caption: Umrah 2027, first car, fit at 30…") }, singleLine = true)
            Button(onClick = { pick.launch(arrayOf("image/*")) }) { Text("Add photo") }
        } }
        if (items.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Dashboard, "Your board is empty", "Add pictures of the goals and places that pull you forward.")) }
        items.chunked(2).forEachIndexed { r, row -> item("v$r") { Row(Modifier.padding(horizontal = Space.page, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEachIndexed { k, v -> Box(Modifier.weight(1f).aspectRatio(if ((r + k) % 2 == 0) 0.8f else 1.1f).clip(RoundedCornerShape(20.dp))) {
                coil.compose.AsyncImage(Uri.parse(v.optString("uri")), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (v.optString("cap").isNotBlank()) Text(v.optString("cap"), Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))).padding(10.dp), color = Color.White, fontWeight = FontWeight.Bold)
                Text("✕", Modifier.align(Alignment.TopEnd).padding(6.dp).pressable { store.remove(v.optString("id")); items = store.all() }, color = Color.White)
            } }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        } } }
    }
}

// ─────────────────────────────── Daily wisdom & intention ───────────────────────────────
private val WISDOM = listOf(
    "“Indeed, with hardship comes ease.” — Qur'an 94:6", "“Allah does not burden a soul beyond that it can bear.” — Qur'an 2:286",
    "“The best of people are those most beneficial to people.” — Hadith (al-Mu'jam al-Awsat)", "“Take advantage of five before five: youth before old age, health before sickness…” — Hadith",
    "“Remember Me; I will remember you.” — Qur'an 2:152", "“The strong man is the one who controls himself when angry.” — Hadith (Bukhari)",
    "“Speak good or remain silent.” — Hadith (Bukhari, Muslim)", "“So remember Me, be grateful to Me.” — Qur'an 2:152",
    "Khudi ko kar buland itna… — Allama Iqbal", "Small deeds done consistently are most beloved to Allah. — Hadith (Bukhari)",
    "“Verily, in the remembrance of Allah do hearts find rest.” — Qur'an 13:28", "The best time to plant a tree was 20 years ago. The second best is now. — Proverb",
)

@Composable
fun WisdomScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val p = remember { ctx.getSharedPreferences("wisdom", 0) }
    val today = LocalDate.now().toString()
    var idx by remember { mutableIntStateOf(LocalDate.now().dayOfYear % WISDOM.size) }
    var intent by remember { mutableStateOf(p.getString("intent_$today", "").orEmpty()) }
    var reflect by remember { mutableStateOf<String?>(null) }
    AtlasScreen(title = "Daily wisdom", subtitle = LocalDate.now().dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }, hub = Hub.Deen) {
        item("w") { Box(Modifier.fillMaxWidth().padding(horizontal = Space.page).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)))).padding(24.dp)) {
            Column {
                Art3D("light_bulb", 72.dp)
                Text(WISDOM[idx], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Capsule("Another", false) { idx = (idx + 1) % WISDOM.size }
                    Capsule("Reflect on it", false) { scope.launch { reflect = runCatching { Ai.generate(container.settingsRepository, "Give a short, warm reflection (80 words) on how to live this today, practically: ${WISDOM[idx]}", maxOutputTokens = 250) }.getOrElse { it.message } } }
                    Capsule("Share", false) { Share.text(ctx, WISDOM[idx]) }
                }
            }
        } }
        reflect?.let { r -> item("r") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(r) } } }
        item("ih") { SectionHeader("Today's intention (niyyah)") }
        item("i") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(intent, { intent = it; p.edit().putString("intent_$today", it).apply() }, Modifier.fillMaxWidth(), placeholder = { Text("Today I intend to…") }, minLines = 2)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("be patient", "pray on time", "call my parents", "help someone", "finish what I start").forEach { s -> Capsule(s, false) { intent = "Today I intend to $s."; p.edit().putString("intent_$today", intent).apply() } }
            }
        } }
        item("past") { SectionHeader("Past intentions") }
        (1..7).forEach { d -> val date = LocalDate.now().minusDays(d.toLong()); val t = p.getString("intent_$date", null)
            if (!t.isNullOrBlank()) item("p$d") { ListRow(t, supporting = date.toString(), icon = Icons.Outlined.Flag) } }
    }
}

// ─────────────────────────────── Pet adventures ───────────────────────────────
private val PLACES = listOf("Hunza Valley" to "mountain", "Karachi beach" to "beach", "Badshahi Mosque" to "mosque", "Deosai plains" to "flowers", "Istanbul" to "city", "Madinah" to "palms", "Space station" to "space", "Swat river" to "river")

@Composable
fun AdventuresScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val store = remember { JsonStore(ctx, "pet_adventures") }
    var list by remember { mutableStateOf(store.all().reversed()) }
    val pet = remember { com.nizam.app.feature.companion.Companion.load(ctx) }
    val away = list.firstOrNull { it.optLong("back") > System.currentTimeMillis() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { delay(30_000); now = System.currentTimeMillis() } } }
    AtlasScreen(title = "${pet.name}'s adventures", subtitle = if (away != null) "Away in ${away.optString("place")} — back in ${((away.optLong("back") - now) / 60000).coerceAtLeast(0)} min" else "Where should ${pet.name} go?", hub = Hub.Social) {
        if (away == null) {
            item("places") { Column(Modifier.padding(horizontal = Space.page)) {
                PLACES.chunked(2).forEach { row -> Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (place, _) -> Button(onClick = {
                        scope.launch {
                            val story = runCatching { Ai.generate(container.settingsRepository,
                                "Write a short, cute postcard (60 words) from ${pet.name}, a ${pet.species.lowercase()} pet with a ${pet.trait.lowercase()} personality, visiting $place. First person, one funny detail, one real fact about the place, sign off with love.", maxOutputTokens = 200) }
                                .getOrElse { "Dear friend, $place was amazing! I saw so much and thought of you the whole time. — ${pet.name}" }
                            store.add(JSONObject().put("place", place).put("story", story).put("back", System.currentTimeMillis() + 90 * 60_000L)
                                .put("souvenir", listOf("gem_stone", "wrapped_gift", "star", "seedling", "coin", "crystal_ball", "sparkles").random()))
                            list = store.all().reversed(); Feedback.show("${pet.name} is off to $place! Back in 90 minutes.")
                        }
                    }, modifier = Modifier.weight(1f)) { Text(place, maxLines = 1) } }
                } }
            } }
        }
        if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Luggage, "No trips yet", "Pick a place above. Postcards arrive when ${pet.name} gets home.")) }
        list.forEach { a -> item(a.optString("id")) {
            val home = a.optLong("back") <= now
            Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("📮 ${a.optString("place")}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (home) a.optString("story") else "The postcard is on its way…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (home) Art3D(a.optString("souvenir"), 64.dp)
                }
            }
        } }
    }
}

// ─────────────────────────────── Audio briefing ───────────────────────────────
@Composable
fun BriefingScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val speaker = remember { com.nizam.app.feature.voice.Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun make() {
        busy = true
        scope.launch {
            val tasks = runCatching { container.taskRepository.all().first().filter { it.completedAt == null }.take(5).map { it.title } }.getOrDefault(emptyList())
            val w = runCatching { JSONObject(ctx.getSharedPreferences("weather_pro", 0).getString("last", null)!!).getJSONObject("current") }.getOrNull()
            val spent = runCatching { container.financeRepository.since(LocalDate.now().minusDays(1).toString()).first().filter { it.kind == "EXPENSE" }.sumOf { it.amount } }.getOrDefault(0.0)
            val prayer = com.nizam.app.ui.design.Island.nextPrayer
            val facts = "Date: ${LocalDate.now()}. Next prayer: ${prayer?.let { "${it.first} in ${it.second} minutes" } ?: "none left today"}. " +
                "Weather: ${w?.let { "${it.optDouble("temperature_2m").toInt()} degrees, ${com.nizam.app.feature.next.Weather.describe(it.optInt("weather_code"))}" } ?: "unknown"}. " +
                "Open tasks: ${tasks.joinToString("; ").ifBlank { "none" }}. Spent since yesterday: Rs ${spent.toLong()}."
            text = runCatching { Ai.generate(container.settingsRepository, "Write a warm 60-second spoken briefing for my day from these facts. Start with salaam, end with a short dua or encouragement. Plain sentences, no lists.\n$facts", maxOutputTokens = 400) }
                .getOrElse { facts }
            speaker.speak(text!!)
            busy = false
        }
    }
    AtlasScreen(title = "Audio briefing", subtitle = "Your day, read aloud", hub = Hub.Work) {
        item("art") { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Art3D("headphone", 150.dp) } }
        item("b") { Row(Modifier.padding(Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { make() }, enabled = !busy) { Text(if (busy) "Preparing…" else "▶ Play my briefing") }
            OutlinedButton(onClick = { speaker.stop() }) { Text("Stop") }
        } }
        text?.let { t -> item("t") { Group(Modifier.padding(horizontal = Space.page)) { Text(t, style = MaterialTheme.typography.bodyLarge) } } }
    }
}

// ─────────────────────────────── Qibla 3D ───────────────────────────────
@Composable
fun Qibla3DScreen(container: AppContainer) {
    val ctx = LocalContext.current
    var qibla by remember { mutableStateOf(0.0) }
    var heading by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        val la = runCatching { container.settingsRepository.latitude.first() }.getOrDefault(31.55); val lo = runCatching { container.settingsRepository.longitude.first() }.getOrDefault(74.34)
        val k1 = Math.toRadians(21.4225); val k2 = Math.toRadians(39.8262); val p1 = Math.toRadians(la); val l1 = Math.toRadians(lo)
        qibla = (Math.toDegrees(kotlin.math.atan2(sin(k2 - l1), cos(p1) * kotlin.math.tan(k1) - sin(p1) * cos(k2 - l1))) + 360) % 360
    } }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) { val m = FloatArray(9); android.hardware.SensorManager.getRotationMatrixFromVector(m, e.values)
                val o = FloatArray(3); android.hardware.SensorManager.getOrientation(m, o); heading = (Math.toDegrees(o[0].toDouble()) + 360) % 360 }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(l, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm?.unregisterListener(l) }
    }
    val off = ((qibla - heading + 540) % 360) - 180
    val faces = remember { Mesh3D.kaaba() }
    AtlasScreen(title = "Qibla", subtitle = "${qibla.toInt()}° from north · " + if (kotlin.math.abs(off) < 5) "you're facing the Kaaba ✓" else "turn ${if (off > 0) "right" else "left"} ${kotlin.math.abs(off).toInt()}°", hub = Hub.Deen) {
        item("k") { Object3D(faces, Modifier.fillMaxWidth().aspectRatio(1f).padding(Space.page), pitch = -0.45f, spin = false, fixedYaw = Math.toRadians(-off).toFloat(), scale = 0.8f) }
        item("n") { Text("Hold the phone flat. When the Kaaba's door faces you, you're facing the qibla. Keep away from metal and magnets.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

// ─────────────────────────────── Globe ───────────────────────────────
private val ZONES = listOf("Lahore" to "Asia/Karachi", "Makkah" to "Asia/Riyadh", "Dubai" to "Asia/Dubai", "London" to "Europe/London", "New York" to "America/New_York",
    "Toronto" to "America/Toronto", "Istanbul" to "Europe/Istanbul", "Kuala Lumpur" to "Asia/Kuala_Lumpur", "Sydney" to "Australia/Sydney", "Tokyo" to "Asia/Tokyo")

@Composable
fun GlobeScreen() {
    val faces = remember { Mesh3D.globe(Mesh3D::roughLand) }
    var now by remember { mutableStateOf(java.time.ZonedDateTime.now()) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { delay(20_000); now = java.time.ZonedDateTime.now() } } }
    AtlasScreen(title = "World clock", subtitle = "Drag the globe to spin it", hub = Hub.Travel) {
        item("g") { Object3D(faces, Modifier.fillMaxWidth().aspectRatio(1f).padding(Space.page), pitch = -0.3f, scale = 0.95f) }
        ZONES.forEach { (city, zone) -> item(city) {
            val t = now.withZoneSameInstant(java.time.ZoneId.of(zone))
            val diff = (t.offset.totalSeconds - now.offset.totalSeconds) / 3600.0
            ListRow(city, supporting = (if (diff == 0.0) "Same time" else "${if (diff > 0) "+" else ""}${if (diff % 1 == 0.0) diff.toInt().toString() else diff.toString()} h") +
                " · ${t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}", icon = Icons.Outlined.Schedule, tint = Hub.Travel.tint(), trailing = {
                Text("%02d:%02d".format(t.hour, t.minute), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) })
        } }
    }
}
