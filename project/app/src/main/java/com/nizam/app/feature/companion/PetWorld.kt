package com.nizam.app.feature.companion

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nizam.app.AppContainer
import com.nizam.app.MainActivity
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Share
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/** The pet's life outside chat: needs, growing up, skills, a room, games, photos, and a floating mode. */
object PetWorld {
    val all: List<Spec> = listOf(
        ScreenSpec("pethome", "Pet home", "Feed, play, dress up, see your pet grow", Icons.Outlined.Pets, Hub.Social, "pet feed play room wardrobe evolve care buddy") { c, open -> PetHomeScreen(c, open) },
        ScreenSpec("petgames", "Pet games", "Catch the treats, memory match, rock-paper-scissors", Icons.Outlined.SportsEsports, Hub.Social, "pet game play mini games") { _, _ -> PetGamesScreen() },
        ScreenSpec("petphoto", "Pet photos & stickers", "Put your pet in any photo; make stickers", Icons.Outlined.AddAPhoto, Hub.Social, "pet ar photo sticker selfie") { _, _ -> PetPhotoScreen() },
        ScreenSpec("petskills", "Pet skills & jobs", "Unlock skills as it levels up; give it helper jobs", Icons.Outlined.AccountTree, Hub.Social, "pet skill tree helper role reminder water bedtime") { _, _ -> PetSkillsScreen() },
        ScreenSpec("petdreams", "Pet dreams", "Each morning your pet tells last night's dream, woven from your day", Icons.Outlined.Bedtime, Hub.Social, "pet dream journal story morning buddy") { c, _ -> PetDreamsScreen(c) },
        ScreenSpec("petmemories", "Pet memory capsules", "Pin, edit and remove moments your pet may refer back to", Icons.Outlined.Pets, Hub.Social, "pet memory capsule pinned moments keepsake remember") { _, _ -> CompanionMemoriesScreen() },
    )

    // ── needs ──
    data class Needs(val food: Int, val fun_: Int, val energy: Int)
    private fun p(c: Context) = c.getSharedPreferences("petcare", 0)
    fun needs(c: Context): Needs {
        val p = p(c); val now = System.currentTimeMillis()
        val last = p.getLong("t", now); val hrs = ((now - last) / 3_600_000.0)
        if (Companion.load(c).careFree) {
            p.edit().putLong("t", now).apply()
            return Needs(p.getInt("food", 80), p.getInt("fun", 80), p.getInt("energy", 80))
        }
        val sleeping = LocalTime.now().hour !in 7..22
        val n = Needs((p.getInt("food", 80) - hrs * 4).roundToInt().coerceIn(0, 100), (p.getInt("fun", 80) - hrs * 3).roundToInt().coerceIn(0, 100),
            (p.getInt("energy", 80) + hrs * (if (sleeping) 8 else -2)).roundToInt().coerceIn(0, 100))
        return n
    }
    fun set(c: Context, n: Needs) = p(c).edit().putInt("food", n.food.coerceIn(0, 100)).putInt("fun", n.fun_.coerceIn(0, 100)).putInt("energy", n.energy.coerceIn(0, 100)).putLong("t", System.currentTimeMillis()).apply()
    fun reward(c: Context, xp: Int) { val pet = Companion.load(c); Companion.save(c, pet.copy(bonusXp = pet.bonusXp + xp)) }

    val FOODS = listOf(Triple("🍛 Biryani", 35, 5), Triple("🍎 Apple", 15, 0), Triple("🥟 Samosa", 20, 8), Triple("🥛 Milk", 12, 0), Triple("🍫 Chocolate", 10, 15), Triple("💧 Water", 6, 0))

    // ── growing up ──
    fun stage(level: Int) = when { level < 5 -> "Baby"; level < 10 -> "Kid"; level < 20 -> "Teen"; level < 35 -> "Adult"; else -> "Elder" }
    fun stageScale(stage: String) = when (stage) { "Baby" -> 0.72f; "Kid" -> 0.82f; "Teen" -> 0.92f; else -> 1f }

    // ── skills ──
    data class Skill(val id: String, val name: String, val level: Int, val what: String)
    val SKILLS = listOf(
        Skill("jokes", "Joke teller", 1, "tells clean jokes and puns"), Skill("prayer", "Prayer buddy", 3, "gently reminds about salah and shares short duas"),
        Skill("water", "Hydration coach", 5, "reminds to drink water through the day"), Skill("stories", "Storyteller", 8, "tells short bedtime and adventure stories"),
        Skill("quiz", "Quiz master", 12, "runs quizzes on any topic and keeps score"), Skill("study", "Study buddy", 15, "runs 25-minute focus sessions and checks in"),
        Skill("poet", "Poet", 20, "writes little poems and shayari on request"), Skill("guard", "Guardian", 25, "watches for late-night doomscrolling and nudges bedtime"),
    )
    fun enabled(c: Context): Set<String> = p(c).getStringSet("skills", setOf("jokes", "prayer")).orEmpty()
    fun setEnabled(c: Context, s: Set<String>) = p(c).edit().putStringSet("skills", s).apply()
    fun level(c: Context) = Companion.level(Companion.xp(Companion.load(c), null))
    fun skillsLine(c: Context): String = SKILLS.filter { it.id in enabled(c) && it.level <= level(c) }.joinToString("; ") { "${it.name}: ${it.what}" }.ifBlank { "none yet" }

    val ROOMS = listOf("Meadow", "Bedroom", "Space", "Beach", "Courtyard", "Night sky")
    fun room(c: Context) = p(c).getString("room", "Meadow")!!
    fun setRoom(c: Context, r: String) = p(c).edit().putString("room", r).apply()

    /** Helper jobs, called by the background worker. */
    fun tick(c: Context, now: Double) {
        val pet = Companion.load(c); if (!pet.hatched) return
        val on = enabled(c); val lvl = level(c)
        val today = LocalDate.now().toString()
        fun once(k: String, block: () -> Unit) { if (p(c).getString("done_$k", "") != today) { p(c).edit().putString("done_$k", today).apply(); block() } }
        fun slot(k: String, block: () -> Unit) { val key = "$k-${now.toInt()}"; if (p(c).getString("slot_$k", "") != "$today$key") { p(c).edit().putString("slot_$k", "$today$key").apply(); block() } }
        if ("water" in on && lvl >= 5 && now in 10.0..21.0 && now.toInt() % 2 == 0) slot("water") {
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 910, "${pet.name} 💧", listOf("Paani time! One glass, for me?", "Sip sip — have some water.", "Hydration check! 🥤").random())
        }
        if ("guard" in on && lvl >= 25 && now in 23.5..24.0) once("guard") {
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 911, "${pet.name} is yawning", "It's late. Phone down, let's both sleep? 🌙")
        }
        val n = needs(c)
        if (!pet.careFree && n.food < 20 && now in 9.0..21.0) once("hungry") { Notifications.post(c, Notifications.CHANNEL_NUDGE, 912, "${pet.name} is hungry 🍛", "Open Pet home and feed me?") }
    }
}

// ─────────────────────────────── Scene ───────────────────────────────
@Composable
fun PetRoom(room: String, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        when (room) {
            "Bedroom" -> { drawRect(Color(0xFFF3E3D3)); drawRect(Color(0xFFC89F7A), Offset(0f, h * 0.72f), Size(w, h * 0.28f))
                drawRoundRect(Color(0xFF9CC5E8), Offset(w * 0.08f, h * 0.12f), Size(w * 0.25f, h * 0.25f), androidx.compose.ui.geometry.CornerRadius(12f))
                drawRoundRect(Color(0xFFE07A8C), Offset(w * 0.62f, h * 0.58f), Size(w * 0.32f, h * 0.18f), androidx.compose.ui.geometry.CornerRadius(20f)) }
            "Space" -> { drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0F2A), Color(0xFF28195C))))
                val rnd = java.util.Random(7); repeat(40) { drawCircle(Color.White.copy(alpha = 0.4f + rnd.nextFloat() * 0.6f), 1.5f + rnd.nextFloat() * 2, Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)) }
                drawCircle(Color(0xFFE38B4F), w * 0.1f, Offset(w * 0.8f, h * 0.22f)); drawCircle(Color(0xFF6C7FE0), h * 0.5f, Offset(w * 0.5f, h * 1.25f)) }
            "Beach" -> { drawRect(Brush.verticalGradient(listOf(Color(0xFF7FD0F5), Color(0xFFBFEAFB)))); drawRect(Color(0xFF3AA0D8), Offset(0f, h * 0.55f), Size(w, h * 0.15f))
                drawRect(Color(0xFFF2D59B), Offset(0f, h * 0.7f), Size(w, h * 0.3f)); drawCircle(Color(0xFFFFD166), w * 0.08f, Offset(w * 0.15f, h * 0.18f)) }
            "Courtyard" -> { drawRect(Color(0xFFF5EBDD)); drawRect(Color(0xFFE2D2BD), Offset(0f, h * 0.72f), Size(w, h * 0.28f))
                repeat(3) { i -> val x = w * (0.18f + i * 0.32f)
                    drawRect(Color(0xFFD9C3A5), Offset(x - w * 0.06f, h * 0.3f), Size(w * 0.12f, h * 0.42f)); drawArc(Color(0xFFD9C3A5), 180f, 180f, true, Offset(x - w * 0.06f, h * 0.24f), Size(w * 0.12f, h * 0.12f)) }
                drawArc(Color(0xFF3FA37C), 180f, 180f, true, Offset(w * 0.4f, h * 0.02f), Size(w * 0.2f, h * 0.2f)) }
            "Night sky" -> { drawRect(Brush.verticalGradient(listOf(Color(0xFF0E1B3D), Color(0xFF30446E)))); drawCircle(Color(0xFFF6F1D5), w * 0.08f, Offset(w * 0.78f, h * 0.2f))
                drawCircle(Color(0xFF0E1B3D), w * 0.07f, Offset(w * 0.81f, h * 0.18f)); drawRect(Color(0xFF1F2B46), Offset(0f, h * 0.75f), Size(w, h * 0.25f)) }
            else -> { drawRect(Brush.verticalGradient(listOf(Color(0xFFBFE6FF), Color(0xFFE9F7FF)))); drawOval(Color(0xFF8BCB6B), Offset(-w * 0.2f, h * 0.62f), Size(w * 1.4f, h * 0.6f))
                repeat(6) { i -> drawCircle(listOf(Color(0xFFFFB5C8), Color(0xFFFFD166), Color.White)[i % 3], 6f, Offset(w * (0.1f + i * 0.16f), h * (0.8f + (i % 2) * 0.06f))) } }
        }
    }
}

@Composable
private fun Bar(label: String, v: Int, c: Color) = Column(Modifier.padding(vertical = 4.dp)) {
    Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text("$v%", style = MaterialTheme.typography.labelLarge) }
    LinearProgressIndicator(progress = { v / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = if (v < 25) MaterialTheme.colorScheme.error else c)
}

// ─────────────────────────────── Pet home ───────────────────────────────
@Composable
fun PetHomeScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var pet by remember { mutableStateOf(Companion.load(ctx)) }
    var needs by remember { mutableStateOf(PetWorld.needs(ctx)) }
    var progress by remember { mutableStateOf<com.nizam.app.feature.progress.ProgressSummary?>(null) }
    var room by remember { mutableStateOf(PetWorld.room(ctx)) }
    var hearts by remember { mutableIntStateOf(0) }
    var mood by remember { mutableStateOf(Companion.mood(pet, null, null)) }
    var floating by remember { mutableStateOf(PetOverlayService.running) }
    LaunchedEffect(container) { com.nizam.app.ui.design.guard {
        progress = runCatching { com.nizam.app.feature.progress.Xp.summarise(container) }.getOrNull()
        progress?.let { summary ->
            val latest = Companion.load(ctx).copy(lastTotalXp = summary.totalXp)
            Companion.save(ctx, latest)
            pet = latest
        }
    } }
    val lvl = Companion.level(Companion.xp(pet, progress)); val stage = PetWorld.stage(lvl)
    fun act(n: PetWorld.Needs, m: Companion.Mood, xp: Int = 1) { needs = n; PetWorld.set(ctx, n); mood = m; hearts++; PetWorld.reward(ctx, xp); pet = Companion.load(ctx) }
    AtlasScreen(title = pet.name, subtitle = "$stage ${pet.species.lowercase()} · level $lvl · ${pet.trait.lowercase()}", hub = Hub.Social) {
        if (!pet.hatched) { item("egg") { Text("Your egg hasn't hatched yet — tap it on the Today screen.", Modifier.padding(Space.page)) }; return@AtlasScreen }
        item("scene") {
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.page).aspectRatio(1.2f).clip(RoundedCornerShape(24.dp))) {
                PetRoom(room, Modifier.fillMaxSize())
                if (stage == "Adult" || stage == "Elder") Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(Color(0x55FFD166), Color.Transparent), center = Offset(size.width / 2, size.height * 0.58f), radius = size.width * 0.4f), size.width * 0.4f, Offset(size.width / 2, size.height * 0.58f))
                }
                val sc = PetWorld.stageScale(stage)
                Creature(pet, mood, hearts, Modifier.fillMaxWidth(0.62f * sc).aspectRatio(1f).align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = { hearts++; mood = Companion.Mood.HAPPY }, onDoubleTap = { open(com.nizam.app.ui.nav.Routes.PETCHAT) }) })
            }
        }
        item("needs") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            if (pet.careFree) Text("No-penalty mode is on — needs stay steady while you're away.", style = MaterialTheme.typography.labelMedium, color = Hub.Social.tint())
            Bar("Food", needs.food, Color(0xFFE38B4F)); Bar("Fun", needs.fun_, Color(0xFFB36AD8)); Bar("Energy", needs.energy, Color(0xFF3DB2B0))
        } }
        item("feed") { LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(PetWorld.FOODS.size) { i -> val (f, food, fn) = PetWorld.FOODS[i]
                Capsule(f, false) { act(needs.copy(food = needs.food + food, fun_ = needs.fun_ + fn), if (f.contains("Biryani") || f.contains("Chocolate")) Companion.Mood.LOVE else Companion.Mood.HAPPY) } }
        } }
        item("acts") { Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { act(needs.copy(fun_ = needs.fun_ + 25, energy = needs.energy - 10), Companion.Mood.LAUGHING, 2) }, modifier = Modifier.weight(1f)) { Text("Play") }
            Button(onClick = { act(needs.copy(energy = needs.energy + 30), Companion.Mood.SLEEPY) }, modifier = Modifier.weight(1f)) { Text("Nap") }
            Button(onClick = { open(com.nizam.app.ui.nav.Routes.PETCHAT) }, modifier = Modifier.weight(1f)) { Text("Talk") }
        } }
        item("rh") { SectionHeader("Room") }
        item("rooms") { LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(PetWorld.ROOMS.size) { i -> val r = PetWorld.ROOMS[i]; Capsule(r, room == r) { room = r; PetWorld.setRoom(ctx, r) } } } }
        item("wh") { SectionHeader("Wardrobe") }
        item("ward") { LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(Companion.ACCESSORIES.size) { i -> val a = Companion.ACCESSORIES[i]; Capsule(a, pet.accessory == a) { pet = pet.copy(accessory = a); Companion.save(ctx, pet); mood = Companion.Mood.PROUD } } } }
        item("float") { Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Let ${pet.name} float over other apps", style = MaterialTheme.typography.titleMedium)
                Text("Tap it to chat; drag to move; drag to the bottom to send it home", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(floating, { v ->
                if (v && !android.provider.Settings.canDrawOverlays(ctx)) {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else { if (v) PetOverlayService.start(ctx) else PetOverlayService.stop(ctx); floating = v }
            })
        } }
        item("more") { Column(Modifier.padding(horizontal = Space.page)) {
            ListRow("Games", supporting = "Win treats and XP", icon = Icons.Outlined.SportsEsports, tint = Hub.Social.tint()) { open("nx/petgames") }
            ListRow("Photos & stickers", supporting = "Put ${pet.name} into your pictures", icon = Icons.Outlined.AddAPhoto, tint = Hub.Social.tint()) { open("nx/petphoto") }
            ListRow("Skills & jobs", supporting = "${PetWorld.SKILLS.count { it.level <= lvl }} of ${PetWorld.SKILLS.size} unlocked", icon = Icons.Outlined.AccountTree, tint = Hub.Social.tint()) { open("nx/petskills") }
            ListRow("Memory capsules", supporting = "Choose what ${pet.name} remembers", icon = Icons.Outlined.Pets, tint = Hub.Social.tint()) { open("nx/petmemories") }
        } }
        item("evo") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("Growing up", style = MaterialTheme.typography.titleSmall)
            val next = listOf(5 to "Kid", 10 to "Teen", 20 to "Adult", 35 to "Elder").firstOrNull { it.first > lvl }
            Text(if (next == null) "Fully grown — a wise elder. ✨" else "Becomes a ${next.second} at level ${next.first}. Tasks, prayers, workouts, games and care all give XP.",
                style = MaterialTheme.typography.bodyMedium)
        } }
    }
}

// ─────────────────────────────── Games ───────────────────────────────
@Composable
fun PetGamesScreen() {
    val ctx = LocalContext.current
    var game by remember { mutableStateOf("Catch") }
    AtlasScreen(title = "Pet games", subtitle = "Every win feeds your pet and gives XP", hub = Hub.Social) {
        item("g") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf("Catch", "Memory", "Rock paper scissors").forEach { g -> Capsule(g, game == g) { game = g } } } }
        item("body") { Box(Modifier.padding(Space.page)) {
            when (game) { "Catch" -> CatchGame { s -> PetWorld.reward(ctx, s / 5); PetWorld.set(ctx, PetWorld.needs(ctx).let { it.copy(fun_ = it.fun_ + 20, food = it.food + s / 4) }) }
                "Memory" -> MemoryGame { moves -> PetWorld.reward(ctx, (30 - moves).coerceAtLeast(3)); PetWorld.set(ctx, PetWorld.needs(ctx).let { it.copy(fun_ = it.fun_ + 20) }) }
                else -> RpsGame { won -> if (won) PetWorld.reward(ctx, 2) } }
        } }
    }
}

@Composable
private fun CatchGame(done: (Int) -> Unit) {
    val items = remember { mutableStateListOf<Triple<Float, Float, String>>() }
    var score by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(0) }
    var box by remember { mutableStateOf(Size.Zero) }
    val treats = listOf("🍎", "🍛", "🥟", "🍫", "🍪", "💣")
    LaunchedEffect(left) { com.nizam.app.ui.design.guard {
        if (left <= 0) return@LaunchedEffect
        repeat(20) { delay(50); for (i in items.indices.reversed()) { val (x, y, e) = items[i]; if (y > 1.05f) items.removeAt(i) else items[i] = Triple(x, y + 0.012f + score * 0.0002f, e) }
            if ((0..9).random() < 2) items += Triple((0..90).random() / 100f, -0.05f, treats.random()) }
        left--; if (left == 0) { items.clear(); done(score) }
    } }
    Column {
        Text(if (left > 0) "Score $score · ${left}s" else if (score > 0) "Final score $score! Tap Start to play again" else "Tap the treats, avoid the bombs",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFE9F7FF))
            .pointerInput(Unit) { detectTapGestures { o -> val w = size.width.toFloat(); val h = size.height.toFloat()
                val hit = items.indexOfFirst { (x, y, _) -> kotlin.math.abs(o.x - x * w - 24) < 60 && kotlin.math.abs(o.y - y * h - 24) < 60 }
                if (hit >= 0) { score += if (items[hit].third == "💣") -10 else 5; items.removeAt(hit) } } }) {
            items.forEach { (x, y, e) -> Text(e, fontSize = 34.sp, modifier = Modifier.offset { IntOffset((x * (box.width.takeIf { it > 0 } ?: 900f)).toInt(), (y * (box.height.takeIf { it > 0 } ?: 1000f)).toInt()) }) }
            Canvas(Modifier.fillMaxSize()) { box = size }
        }
        Button(onClick = { score = 0; items.clear(); left = 30 }, enabled = left == 0, modifier = Modifier.padding(top = Space.s)) { Text("Start (30 s)") }
    }
}

@Composable
private fun MemoryGame(done: (Int) -> Unit) {
    val faces = listOf("🦊", "🐼", "🦁", "🐢", "🦜", "🐹", "🐧", "🐰")
    var cards by remember { mutableStateOf((faces + faces).shuffled()) }
    var open by remember { mutableStateOf(listOf<Int>()) }
    var matched by remember { mutableStateOf(setOf<Int>()) }
    var moves by remember { mutableIntStateOf(0) }
    LaunchedEffect(open) { com.nizam.app.ui.design.guard { if (open.size == 2) { delay(700); if (cards[open[0]] == cards[open[1]]) { matched = matched + open; if (matched.size == cards.size) done(moves) }; open = emptyList() } } }
    Column {
        Text(if (matched.size == cards.size) "All matched in $moves moves! 🎉" else "Moves: $moves", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        (0 until 4).forEach { r -> Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            (0 until 4).forEach { col -> val i = r * 4 + col; val shown = i in open || i in matched
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(if (shown) Color(0xFFFFF4E0) else Hub.Social.tint())
                    .pressable { if (!shown && open.size < 2) { open = open + i; if (open.size == 2) moves++ } }, contentAlignment = Alignment.Center) {
                    if (shown) Text(cards[i], fontSize = 30.sp) else Text("?", color = Color.White, fontSize = 22.sp)
                }
            }
        } }
        OutlinedButton(onClick = { cards = (faces + faces).shuffled(); open = emptyList(); matched = emptySet(); moves = 0 }, modifier = Modifier.padding(top = Space.s)) { Text("New game") }
    }
}

@Composable
private fun RpsGame(done: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val pet = remember { Companion.load(ctx) }
    var result by remember { mutableStateOf("Pick one!") }
    var score by remember { mutableStateOf(0 to 0) }
    val opts = listOf("✊" to "Rock", "✋" to "Paper", "✌️" to "Scissors")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("You ${score.first} – ${score.second} ${pet.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(result, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = Space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            opts.forEachIndexed { i, (e, _) -> Text(e, fontSize = 48.sp, modifier = Modifier.pressable {
                val p = (0..2).random(); val win = (i - p + 3) % 3 == 1
                result = "${pet.name} chose ${opts[p].first}. " + when { i == p -> "Draw!"; win -> "You win! 🎉"; else -> "${pet.name} wins! 😜" }
                if (i != p) score = if (win) score.copy(first = score.first + 1) else score.copy(second = score.second + 1)
                done(win && i != p)
            }) }
        }
    }
}

// ─────────────────────────────── Photos & stickers ───────────────────────────────
@Composable
fun PetPhotoScreen() {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val pet = remember { Companion.load(ctx) }
    var photo by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pos by remember { mutableStateOf(Offset(200f, 400f)) }
    var scale by remember { mutableStateOf(1f) }
    var mood by remember { mutableStateOf(Companion.Mood.HAPPY) }
    val layer = rememberGraphicsLayer()
    val stickerLayer = rememberGraphicsLayer()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u?.let { runCatching { ctx.contentResolver.openInputStream(it)?.use { s -> android.graphics.BitmapFactory.decodeStream(s, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) } }.getOrNull() }?.let { photo = it }
    }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> if (b != null) photo = b }
    fun save(l: androidx.compose.ui.graphics.layer.GraphicsLayer, name: String) = scope.launch {
        val bmp = l.toImageBitmap().asAndroidBitmap()
        val f = Share.out(ctx, name); f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        Share.file(ctx, f, "image/png")
    }
    AtlasScreen(title = "Pet photos & stickers", subtitle = "Drag ${pet.name} where you want it", hub = Hub.Social) {
        item("p") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { cam.launch(null) }) { Text("Camera") }; OutlinedButton(onClick = { pick.launch("image/*") }) { Text("Gallery") } } }
        item("moods") { LazyRow(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val ms = Companion.Mood.entries; items(ms.size) { i -> Capsule(ms[i].name.lowercase().replaceFirstChar { it.uppercase() }, mood == ms[i]) { mood = ms[i] } } } }
        photo?.let { ph -> item("canvas") {
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.page).aspectRatio(ph.width.toFloat() / ph.height)
                .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) {
                Image(ph.asImageBitmap(), null, Modifier.fillMaxSize())
                Creature(pet, mood, 0, Modifier.offset { IntOffset(pos.x.toInt(), pos.y.toInt()) }.size((140 * scale).dp)
                    .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); pos += d } })
            }
            Row(Modifier.padding(Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Capsule("Smaller", false) { scale = (scale - 0.15f).coerceAtLeast(0.4f) }; Capsule("Bigger", false) { scale = (scale + 0.15f).coerceAtMost(2.5f) }
                Button(onClick = { save(layer, "pet_photo.png") }) { Text("Share photo") }
            }
        } }
        item("sh") { SectionHeader("Sticker") }
        item("st") { Column(Modifier.padding(horizontal = Space.page)) {
            Box(Modifier.size(200.dp).drawWithContent { stickerLayer.record { this@drawWithContent.drawContent() }; drawLayer(stickerLayer) }) {
                Creature(pet, mood, 0, Modifier.fillMaxSize()) }
            Button(onClick = { save(stickerLayer, "pet_sticker.png") }, modifier = Modifier.padding(top = Space.s)) { Text("Share ${mood.name.lowercase()} sticker") }
        } }
    }
}

// ─────────────────────────────── Skills ───────────────────────────────
@Composable
fun PetSkillsScreen() {
    val ctx = LocalContext.current
    val lvl = remember { PetWorld.level(ctx) }
    var on by remember { mutableStateOf(PetWorld.enabled(ctx)) }
    AtlasScreen(title = "Skills & jobs", subtitle = "Level $lvl — new skills unlock as your pet grows", hub = Hub.Social) {
        PetWorld.SKILLS.forEach { s -> item(s.id) {
            val unlocked = s.level <= lvl
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (unlocked) "" else "🔒 ") + s.name, style = MaterialTheme.typography.titleMedium, color = if (unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (unlocked) s.what.replaceFirstChar { it.uppercase() } else "Unlocks at level ${s.level}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(s.id in on && unlocked, { v -> on = if (v) on + s.id else on - s.id; PetWorld.setEnabled(ctx, on) }, enabled = unlocked)
            }
        } }
    }
}

// ─────────────────────────────── Floating pet ───────────────────────────────
class PetOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private var view: ComposeView? = null

    override fun onCreate() { super.onCreate(); saved.performRestore(null); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("petfloat", "Floating pet", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 13, Intent(this, PetOverlayService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "petfloat").setSmallIcon(android.R.drawable.star_on).setContentTitle("${com.nizam.app.feature.companion.Companion.load(this).name} is out and about")
            .addAction(Notification.Action.Builder(null, "Send home", stop).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(14, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(14, n)
        if (view == null && android.provider.Settings.canDrawOverlays(this)) show()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        running = true
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val d = resources.displayMetrics.density
        val params = WindowManager.LayoutParams((96 * d).toInt(), (96 * d).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 600 }
        val v = ComposeView(this)
        v.setViewTreeLifecycleOwner(this); v.setViewTreeSavedStateRegistryOwner(this)
        v.setContent {
            val pet = remember { com.nizam.app.feature.companion.Companion.load(this) }
            var mood by remember { mutableStateOf(com.nizam.app.feature.companion.Companion.mood(pet, null, null)) }
            var hearts by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { delay(20_000); mood = listOf(mood, com.nizam.app.feature.companion.Companion.Mood.CONTENT, com.nizam.app.feature.companion.Companion.Mood.SILLY, com.nizam.app.feature.companion.Companion.Mood.THINKING, com.nizam.app.feature.companion.Companion.Mood.HAPPY).random() } } }
            Creature(pet, mood, hearts, Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(onDragEnd = {
                    if (params.y > resources.displayMetrics.heightPixels * 0.85f) stopSelf()
                }) { ch, drag -> ch.consume(); params.x += drag.x.toInt(); params.y += drag.y.toInt(); runCatching { wm.updateViewLayout(v, params) } }
            }.pointerInput(Unit) {
                detectTapGestures(onTap = { hearts++; mood = com.nizam.app.feature.companion.Companion.Mood.LOVE }, onDoubleTap = {
                    startActivity(Intent(this@PetOverlayService, MainActivity::class.java).putExtra("route", com.nizam.app.ui.nav.Routes.PETCHAT)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                })
            })
        }
        runCatching { wm.addView(v, params) }.onFailure { Feedback.show("Couldn't show the pet over other apps") }
        view = v
    }

    override fun onDestroy() {
        running = false
        view?.let { runCatching { wm.removeView(it) } }; view = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        fun start(c: Context) = androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, PetOverlayService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, PetOverlayService::class.java).setAction("stop"))
    }
}
