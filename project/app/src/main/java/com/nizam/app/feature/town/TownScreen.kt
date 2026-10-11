package com.nizam.app.feature.town

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoFamily
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.hypot
import kotlin.random.Random

private const val TW = 32f   // tile width in world pixels
private const val TH = 16f   // tile height — 2:1 is the classic isometric ratio

private fun iso(x: Float, y: Float, z: Float = 0f) = Offset((x - y) * TW / 2f, (x + y) * TH / 2f - z * TH)

private fun shade(c: Color, f: Float) = Color(
    (c.red * f).coerceIn(0f, 1f), (c.green * f).coerceIn(0f, 1f), (c.blue * f).coerceIn(0f, 1f), c.alpha
)

private data class Look(val wall: Color, val roof: Color)
private val LOOKS = mapOf(
    Kind.HOME to Look(Color(0xFFD9C3A0), Color(0xFFB5462E)),
    Kind.MASJID to Look(Color(0xFFF2EEE6), Color(0xFF2F8A73)),
    Kind.LIBRARY to Look(Color(0xFFB08A6A), Color(0xFF5B6BA8)),
    Kind.GYM to Look(Color(0xFF9AA3AD), Color(0xFF3E5C76)),
    Kind.MARKET to Look(Color(0xFFE8D5A8), Color(0xFFD98E4A)),
    Kind.SCHOOL to Look(Color(0xFFCFA67A), Color(0xFF8A4F6B)),
    Kind.FOUNTAIN to Look(Color(0xFFB8BCC8), Color(0xFF58A6C9)),
    Kind.TOWER to Look(Color(0xFF8C8478), Color(0xFF5E3A26)),
    Kind.BAKERY to Look(Color(0xFFE6CFA0), Color(0xFFC9A227))
)

/** One extruded block: lit top, mid left face, shaded right face — that's what reads as 3D. */
private fun DrawScope.block(x: Float, y: Float, w: Float, h: Float, z0: Float, height: Float, wall: Color, top: Color) {
    val z1 = z0 + height
    val a = iso(x, y + h, z0); val b = iso(x + w, y + h, z0); val c = iso(x + w, y, z0)
    val a1 = iso(x, y + h, z1); val b1 = iso(x + w, y + h, z1); val c1 = iso(x + w, y, z1); val d1 = iso(x, y, z1)
    drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(b1.x, b1.y); lineTo(a1.x, a1.y); close() }, shade(wall, 0.92f))
    drawPath(Path().apply { moveTo(b.x, b.y); lineTo(c.x, c.y); lineTo(c1.x, c1.y); lineTo(b1.x, b1.y); close() }, shade(wall, 0.70f))
    drawPath(Path().apply { moveTo(d1.x, d1.y); lineTo(c1.x, c1.y); lineTo(b1.x, b1.y); lineTo(a1.x, a1.y); close() }, top)
}

private fun DrawScope.tile(x: Int, y: Int, colour: Color) {
    val p = iso(x.toFloat(), y.toFloat()); val q = iso(x + 1f, y.toFloat())
    val r = iso(x + 1f, y + 1f); val s = iso(x.toFloat(), y + 1f)
    drawPath(Path().apply { moveTo(p.x, p.y); lineTo(q.x, q.y); lineTo(r.x, r.y); lineTo(s.x, s.y); close() }, colour)
}

private val HOME_ROOFS = listOf(Color(0xFFB5462E), Color(0xFF3E5C76), Color(0xFF6E8E4E), Color(0xFF8A4F6B), Color(0xFFC9832B))
private val HOME_WALLS = listOf(Color(0xFFD9C3A0), Color(0xFFE8DCC4), Color(0xFFCFB08A), Color(0xFFE2D2B6))

/** The architecture style chosen in the town planner recolours walls and roofs. */
private fun styled(l: Look, b: Building): Look = when (TownLayout.style) {
    "Mughal" -> Look(Color(0xFFE3B98F), if (b.kind == Kind.MASJID) Color(0xFFF2EEE6) else Color(0xFFB0412E))
    "Modern" -> Look(Color(0xFFE9ECEF), listOf(Color(0xFF5B7C99), Color(0xFF3D4A57), Color(0xFF2F8A73))[(b.x + b.y) % 3])
    "Coastal" -> Look(Color(0xFFF6F4EF), listOf(Color(0xFF2E7DBA), Color(0xFF58A6C9), Color(0xFF1F5F8B))[(b.x + b.y) % 3])
    "Desert" -> Look(Color(0xFFD8B27A), Color(0xFFA0643A))
    else -> l
}

private fun DrawScope.building(b: Building, night: Float) {
    val base = LOOKS[b.kind] ?: LOOKS.getValue(Kind.HOME)
    val look = styled(if (b.kind == Kind.HOME)
        Look(HOME_WALLS[(b.x * 3 + b.y) % HOME_WALLS.size], HOME_ROOFS[(b.x + b.y * 5) % HOME_ROOFS.size])
    else base, b)
    val x = b.x.toFloat(); val y = b.y.toFloat(); val w = b.w.toFloat(); val h = b.h.toFloat()
    when (b.kind) {
        Kind.FOUNTAIN -> {
            block(x + 0.1f, y + 0.1f, 0.8f, 0.8f, 0f, 0.35f, look.wall, shade(look.wall, 1.1f))
            val c = iso(x + 0.5f, y + 0.5f, 0.36f)
            drawOval(look.roof, Offset(c.x - 9f, c.y - 4f), Size(18f, 8f))
            drawRect(Color(0xFFBDE3F2), Offset(c.x - 1f, c.y - 12f), Size(2f, 10f))
        }
        Kind.MASJID -> {
            block(x, y, w, h, 0f, b.height.toFloat(), look.wall, shade(look.wall, 1.05f))
            val top = iso(x + w / 2, y + h / 2, b.height.toFloat())
            drawRect(shade(look.wall, 0.95f), Offset(top.x - 10f, top.y - 8f), Size(20f, 8f))        // drum
            drawArc(look.roof, 180f, 180f, true, Offset(top.x - 11f, top.y - 19f), Size(22f, 22f))  // dome
            drawRect(Color(0xFFE0B14A), Offset(top.x - 0.75f, top.y - 25f), Size(1.5f, 6f))          // finial
            drawCircle(Color(0xFFE0B14A), 2f, Offset(top.x, top.y - 26f))
            block(x + w - 0.45f, y + 0.05f, 0.35f, 0.35f, 0f, b.height + 3.5f, look.wall, look.roof) // minaret
        }
        Kind.TOWER -> {
            block(x + 0.15f, y + 0.15f, 0.7f, 0.7f, 0f, b.height.toFloat(), look.wall, shade(look.wall, 1.1f))
            block(x + 0.05f, y + 0.05f, 0.9f, 0.9f, b.height.toFloat(), 0.4f, look.roof, shade(look.roof, 1.2f))
        }
        else -> {
            block(x, y, w, h, 0f, b.height.toFloat(), look.wall, shade(look.wall, 1.05f))
            // a stepped roof reads as pitched from this angle
            block(x + 0.1f, y + 0.1f, w - 0.2f, h - 0.2f, b.height.toFloat(), 0.3f, look.roof, shade(look.roof, 1.15f))
            block(x + 0.3f, y + 0.3f, w - 0.6f, h - 0.6f, b.height + 0.3f, 0.25f, shade(look.roof, 0.9f), shade(look.roof, 1.25f))
        }
    }
    if (night > 0.35f && b.kind != Kind.FOUNTAIN) {   // lit windows after dark
        val p = iso(x + w * 0.5f, y + h, b.height * 0.5f)
        drawRect(Color(0xFFFFD27A).copy(alpha = night), Offset(p.x - 5f, p.y - 3f), Size(4f, 4f))
        drawRect(Color(0xFFFFD27A).copy(alpha = night), Offset(p.x + 2f, p.y - 3f), Size(4f, 4f))
    }
}

private fun DrawScope.tree(x: Int, y: Int, seed: Int) {
    val base = iso(x + 0.5f, y + 0.5f)
    drawRect(Color(0xFF5E3A26), Offset(base.x - 1.5f, base.y - 10f), Size(3f, 10f))
    val g = if (seed % 3 == 0) Color(0xFF3F7D4E) else Color(0xFF4E9A5C)
    drawCircle(g, 8f, Offset(base.x, base.y - 14f))
    drawCircle(shade(g, 1.2f), 5f, Offset(base.x - 2f, base.y - 17f))
}

private fun DrawScope.person(s: Sim) {
    val p = iso(s.x, s.y)
    drawOval(Color.Black.copy(alpha = 0.18f), Offset(p.x - 4f, p.y - 2f), Size(8f, 3f))
    drawRect(Color(0xFF2A2A33), Offset(p.x - 3f, p.y - 6f), Size(6f, 5f))           // legs
    drawRect(Color(s.shirt), Offset(p.x - 3.5f, p.y - 12f), Size(7f, 7f))           // body
    drawRect(Color(s.skin), Offset(p.x - 2.5f, p.y - 17f), Size(5f, 5f))            // head
}

@Composable
fun TownScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TownStore(context) }
    val measurer = rememberTextMeasurer()

    var town by remember { mutableStateOf<Town?>(null) }
    var frame by remember { mutableStateOf(0L) }
    var scale by remember { mutableStateOf(1.3f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<Sim?>(null) }
    var dialogues by remember { mutableStateOf(store.dialogues().second) }
    var listening by remember { mutableStateOf(false) }
    var talkError by remember { mutableStateOf<String?>(null) }
    var showTalk by remember { mutableStateOf(false) }
    var prayerHours by remember { mutableStateOf(emptyList<Double>()) }
    var adventures by remember { mutableStateOf(emptyList<Adventure>()) }
    var newAdventure by remember { mutableStateOf<Adventure?>(null) }
    var showAdventures by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val facts = TownData.facts(container)
        TownLayout.load(context)
        town = Town(store.seed(), facts).also { it.gentle = com.nizam.app.feature.wellbeing.Gentle.on(context) }
        Chatter.gentle = com.nizam.app.feature.wellbeing.Gentle.on(context)
        adventures = Adventures.all(context)
        // every flip-to-focus session you finish plants a tree on the town's edge
        town?.let { t ->
            val grove = com.nizam.app.feature.body.Grove.count(context)
            var planted = 0; var i = 0
            while (planted < grove && i < t.size * 4) {
                val x = if (i % 2 == 0) i / 2 % t.size else (if (i % 4 == 1) 0 else t.size - 1)
                val y = if (i % 2 == 0) (if (i % 4 == 0) 0 else t.size - 1) else i / 2 % t.size
                if ((x to y) !in t.trees) { t.trees.add(x to y); planted++ }
                i++
            }
        }
        town?.let { t -> Adventures.maybeSend(context, t)?.let { newAdventure = it; adventures = Adventures.all(context) } }
        val s = container.settingsRepository
        val t = PrayerTimes.calculate(
            LocalDate.now(), s.latitude.first(), s.longitude.first(),
            runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI },
            s.hanafiAsr.first()
        )
        prayerHours = listOf(t.fajr, t.dhuhr, t.asr, t.maghrib, t.isha)
    }

    // ~20fps and only while this screen exists — the town costs nothing when you're elsewhere
    LaunchedEffect(town) {
        val t = town ?: return@LaunchedEffect
        val rnd = Random(System.nanoTime())
        var last = 0L; var schedAt = 0L; var thoughtAt = 0L; var dialogueIdx = 0
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
                if (now - last >= 50_000_000L) {
                    last = now
                    t.step(dt * 2.5f)
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - schedAt > 3000) {
                        t.coworking = com.nizam.app.feature.growth.FocusFlag.active(context)
                        val lt = LocalTime.now()
                        t.schedule(lt.hour + lt.minute / 60.0, prayerHours, rnd); schedAt = nowMs
                    }
                    if (nowMs - thoughtAt > 2600) {
                        val talking = t.sims.filter { it.thought != null && nowMs < it.thoughtUntil }
                        // Never start a bubble within 4 tiles of one already showing — they'd overlap
                        val live = t.sims.filter { c ->
                            c.visible && (c.thought == null || nowMs > c.thoughtUntil) &&
                                talking.none { o -> hypot(o.x - c.x, o.y - c.y) < 4f }
                        }
                        if (live.isNotEmpty() && t.sims.count { it.thought != null && nowMs < it.thoughtUntil } < 3) {
                            val s = live[rnd.nextInt(live.size)]
                            // AI lines when we have them, local chatter otherwise
                            val fromAi = dialogues.flatMap { it.lines }.filter { it.first == s.name }
                            s.thought = if (fromAi.isNotEmpty() && rnd.nextFloat() < 0.6f)
                                fromAi[(dialogueIdx++) % fromAi.size].second
                            else Chatter.line(s, t.facts, rnd)
                            s.thoughtUntil = nowMs + 6000
                        }
                        thoughtAt = nowMs
                    }
                    frame++
                }
            }
        }
    }

    val hour = LocalTime.now().let { it.hour + it.minute / 60f }
    // night falls at your real Maghrib and lifts at your real Fajr
    val dusk = prayerHours.getOrNull(3)?.toFloat()?.takeIf { !it.isNaN() } ?: 18.5f
    val dawn = prayerHours.getOrNull(0)?.toFloat()?.takeIf { !it.isNaN() } ?: 5f
    val night = when {
        hour < dawn || hour > dusk + 1.5f -> 0.75f
        hour < dawn + 1f -> (dawn + 1f - hour) * 0.75f
        hour > dusk -> (hour - dusk) / 1.5f * 0.75f
        else -> 0f
    }
    val aqi = remember { com.nizam.app.feature.daily.AirCache.get(context)?.aqi ?: 0 }
    val sky = when {
        night > 0.5f -> Color(0xFF0E1428)
        night > 0.1f -> Color(0xFF3A2E4F)
        else -> Color(0xFF8EC5E8)
    }

    Box(Modifier.fillMaxSize().background(sky)) {
        val t = town
        if (t == null) {
            Text("Building your town…", Modifier.align(Alignment.Center), color = Color.White)
            return@Box
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, p, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.8f, 4.5f); pan += p
                    }
                }
                .pointerInput(t) {
                    detectTapGestures { tap ->
                        val centre = iso(t.size / 2f, t.size / 2f)
                        val cx = size.width / 2f - centre.x * scale + pan.x
                        val cy = size.height / 2f - centre.y * scale + pan.y
                        selected = t.sims.filter { it.visible }.minByOrNull {
                            val p = iso(it.x, it.y); hypot(cx + p.x * scale - tap.x, cy + (p.y - 10f) * scale - tap.y)
                        }?.takeIf {
                            val p = iso(it.x, it.y); hypot(cx + p.x * scale - tap.x, cy + (p.y - 10f) * scale - tap.y) < 40f
                        }
                    }
                }
        ) {
            @Suppress("UNUSED_EXPRESSION") frame   // read so each tick redraws
            val centre = iso(t.size / 2f, t.size / 2f)
            withTransform({
                translate(size.width / 2f - centre.x * scale + pan.x, size.height / 2f - centre.y * scale + pan.y)
                scale(scale, scale, Offset.Zero)
            }) {
                // ground
                for (x in 0 until t.size) for (y in 0 until t.size) {
                    val onRoad = x == t.road || y == t.road
                    val grass = if ((x * 7 + y * 13) % 5 == 0) Color(0xFF5FA85A) else Color(0xFF6BB463)
                    tile(x, y, if (onRoad) Color(0xFFB9A57E) else grass)
                    if (t.neglected && !onRoad && (x * 31 + y * 17) % 11 == 0) {   // weeds when you slip
                        val p = iso(x + 0.5f, y + 0.5f)
                        drawRect(Color(0xFF8A7A3A), Offset(p.x - 1f, p.y - 5f), Size(2f, 5f))
                    }
                }
                // everything upright, painted back to front
                data class Thing(val depth: Float, val draw: DrawScope.() -> Unit)
                val things = mutableListOf<Thing>()
                t.buildings.forEach { b -> things += Thing(b.x + b.y + (b.w + b.h) / 2f) { building(b, night) } }
                t.trees.forEach { (x, y) -> things += Thing(x + y + 0.9f) { tree(x, y, x * 13 + y) } }
                t.sims.filter { it.visible }.forEach { s -> things += Thing(s.x + s.y) { person(s) } }
                // monuments for milestones, standing on the square's far side
                listOfNotNull(if (t.facts.best >= 7) "🏅" else null, if (t.facts.best >= 30) "🗽" else null,
                    if (t.facts.level >= 5) "⛲" else null, if (t.facts.level >= 10) "🏛️" else null,
                    if (t.facts.prayers30 >= 140) "🕌" else null).forEachIndexed { i, e ->
                    val mx = t.road - 1.5f - i * 1.1f; val my = t.road + 1.5f
                    things += Thing(mx + my) {
                        val p = iso(mx, my); val lay = measurer.measure(e, TextStyle(fontSize = 13.sp))
                        drawText(lay, topLeft = Offset(p.x - lay.size.width / 2f, p.y - lay.size.height))
                    }
                }
                adventures.takeLast(24).forEachIndexed { i, a ->
                    val fx = t.road + 1.5f + (i % 4) * 0.9f; val fy = t.road - 1.5f - (i / 4) * 0.9f
                    things += Thing(fx + fy) {
                        val p = iso(fx, fy)
                        val lay = measurer.measure(a.emoji, TextStyle(fontSize = 9.sp))
                        drawText(lay, topLeft = Offset(p.x - lay.size.width / 2f, p.y - lay.size.height))
                    }
                }
                things.sortedBy { it.depth }.forEach { it.draw(this) }

                // thought bubbles last, so they're never hidden behind a roof
                val now = System.currentTimeMillis()
                t.sims.filter { it.visible && it.thought != null && now < it.thoughtUntil }.forEach { s ->
                    val p = iso(s.x, s.y)
                    val layout = measurer.measure(
                        s.thought!!, TextStyle(fontSize = 5.sp, color = Color(0xFF1A1A22)),
                        overflow = TextOverflow.Ellipsis, maxLines = 3,
                        constraints = Constraints(maxWidth = 150)
                    )
                    val bw = layout.size.width + 8f; val bh = layout.size.height + 6f
                    val bx = p.x - bw / 2f; val by = p.y - 22f - bh
                    drawRoundRect(Color.White.copy(alpha = 0.95f), Offset(bx, by), Size(bw, bh), CornerRadius(5f))
                    drawCircle(Color.White.copy(alpha = 0.95f), 2f, Offset(p.x - 1f, by + bh + 2f))
                    drawCircle(Color.White.copy(alpha = 0.95f), 1.2f, Offset(p.x, by + bh + 5f))
                    drawText(layout, topLeft = Offset(bx + 4f, by + 3f))
                }
            }
            if (night > 0f) drawRect(Color(0xFF0A1030).copy(alpha = night * 0.45f))
            if (aqi > 150) drawRect(Color(0xFFB8A888).copy(alpha = ((aqi - 150) / 250f).coerceIn(0.12f, 0.45f)))   // Lahore smog, mirrored
        }

        // ── Overlay: status, what's next, the talk ──────────────────────────
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(14.dp)) {
            Column(
                Modifier.background(Color(0xCC0B0F1A), RoundedCornerShape(16.dp)).padding(12.dp)
            ) {
                Text("${t.facts.name.ifBlank { "Your" }}'s Town", color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily))
                Text(
                    "Level ${t.facts.level} · ${t.sims.size} residents · ${t.buildings.size} buildings",
                    color = Color(0xFFB8BCC8), style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily)
                )
                val next = UNLOCKS.firstOrNull { it.kind !in t.unlocked }
                if (next != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Next: ${next.kind.label} — ${next.need}", color = Color(0xFFE0B14A),
                        style = MaterialTheme.typography.labelMedium)
                }
                // a friendly rival whose week tracks yours, a little ahead or behind
                val rivalXp = remember(t) { val w = java.time.LocalDate.now().get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                    (t.facts.xpToday.coerceAtLeast(30) * 7 * (0.85 + (w * 37 % 30) / 100.0)).toInt() }
                Text("Rival Kashif: $rivalXp XP this week · you: ${t.facts.xpToday * 7} pace",
                    color = Color(0xFFE08A6A), style = MaterialTheme.typography.labelMedium)
                if (t.neglected) {
                    Text("Weeds are creeping in. Log something and they'll clear.", color = Color(0xFFE08A6A),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp)) {
            selected?.let { s ->
                Column(
                    Modifier.fillMaxWidth().background(Color(0xEE0B0F1A), RoundedCornerShape(16.dp)).padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(14.dp).height(14.dp).background(Color(s.shirt), RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text("${s.name}, ${s.age}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text("close", color = Color(0xFFB8BCC8), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable(role = Role.Button) { selected = null })
                    }
                    Text("${s.job} · ${s.personality.label} · currently ${s.activity}",
                        color = Color(0xFFB8BCC8), style = MaterialTheme.typography.labelMedium)
                    t.sims.getOrNull(s.friend)?.let {
                        Text("Best friend: ${it.name}", color = Color(0xFFB8BCC8), style = MaterialTheme.typography.labelMedium)
                    }
                    s.thought?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("“$it”", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            newAdventure?.let { a ->
                Column(Modifier.fillMaxWidth().background(Color(0xEE0B0F1A), RoundedCornerShape(16.dp)).padding(14.dp)) {
                    Text("${a.emoji}  An adventure!", color = Color(0xFFE0B14A), style = MaterialTheme.typography.titleMedium)
                    Text(a.story, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text("nice", color = Color(0xFFB8BCC8), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(role = Role.Button) { newAdventure = null }.padding(top = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
            if (showAdventures) {
                Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).background(Color(0xEE0B0F1A), RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState()).padding(14.dp)) {
                    Text("ADVENTURES · ${adventures.size}", color = Color(0xFFE0B14A), style = MaterialTheme.typography.labelMedium)
                    if (adventures.isEmpty()) Text("A day with 40+ XP sends someone exploring.", color = Color.White)
                    adventures.reversed().forEach { a ->
                        Text("${a.emoji} ${a.date} — ${a.story}", color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (showTalk && dialogues.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .background(Color(0xEE0B0F1A), RoundedCornerShape(16.dp))
                        .verticalScroll(rememberScrollState()).padding(14.dp)
                ) {
                    Text("TOWN TALK · TODAY", color = Color(0xFFE0B14A),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily))
                    dialogues.forEach { d ->
                        Spacer(Modifier.height(10.dp))
                        Text("${d.a} & ${d.b}", color = Color(0xFFB8BCC8), style = MaterialTheme.typography.labelMedium)
                        d.lines.forEach { (who, text) ->
                            Text("$who: $text", color = Color.White, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            talkError?.let {
                Text(it, color = Color(0xFFE08A6A), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
            }

            Row {
                Text("🎒", modifier = Modifier.background(Color(0xCC0B0F1A), RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button) { showAdventures = !showAdventures }.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                val fresh = store.dialogues().first == LocalDate.now().toString()
                Text(
                    when {
                        listening -> "Listening in…"
                        dialogues.isNotEmpty() && fresh -> if (showTalk) "Hide the town talk" else "💬 Read today's town talk"
                        else -> "💬 Hear what they're saying"
                    },
                    color = Color(0xFF0B0F1A),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .background(Color(0xFFE0B14A), RoundedCornerShape(24.dp))
                        .clickable(enabled = !listening, role = Role.Button) {
                            if (dialogues.isNotEmpty() && fresh) { showTalk = !showTalk; return@clickable }
                            scope.launch {
                                listening = true; talkError = null
                                runCatching { TownData.writeDialogues(container, t) }
                                    .onSuccess { dialogues = it; store.saveDialogues(it); showTalk = true }
                                    .onFailure { talkError = "The town is quiet: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                                listening = false
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }
}
