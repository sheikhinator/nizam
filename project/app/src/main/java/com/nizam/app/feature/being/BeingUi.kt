package com.nizam.app.feature.being

import android.speech.tts.TextToSpeech
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Pets
import com.nizam.app.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

private object P {
    val bg = Color(0xFF0B0E1A); val panel = Color(0xFF141a2e); val line = Color(0xFF26304d); val text = Color(0xFFEDEFFA); val dim = Color(0xFF8E97B8)
    val glow = Color(0xFF8BE9FD); val warm = Color(0xFFFFC37A); val mono = FontFamily.Monospace
}

private fun hsv(h: Float, s: Float, v: Float) = Color.hsv(((h % 360) + 360) % 360, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))

/** Its pixel body. It breathes, blinks, sleeps with its eyes shut, and its colours follow its mood. */
@Composable
fun PixelBody(sprite: String, hue: Float, mood: Float, sleeping: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "life")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (sleeping) 3600 else 2400, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val blinkCycle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "k")
    val body = hsv(hue, 0.55f, 0.55f + mood * 0.4f); val shade = hsv(hue + 12, 0.65f, 0.35f + mood * 0.3f); val accent = hsv(hue + 140, 0.6f, 0.95f)
    Canvas(modifier) {
        val cell = size.minDimension / 12f
        val lift = breath * cell * 0.25f
        val stretch = 1f + breath * 0.04f
        val ox = (size.width - cell * 12) / 2; val oy = (size.height - cell * 12) / 2
        for (y in 0 until 12) for (x in 0 until 12) {
            val v = sprite.getOrNull(y * 12 + x) ?: '0'
            if (v == '0') continue
            val col = when (v) { '1' -> body; '2' -> shade; else -> accent }
            val yy = oy + (y * cell) * stretch - lift + (12 * cell) * (1 - stretch)
            drawRect(col, Offset(ox + x * cell, yy), Size(cell + 0.5f, cell * stretch + 0.5f))
        }
        // eyes
        val eyeY = oy + 5 * cell * stretch - lift + (12 * cell) * (1 - stretch)
        val closed = sleeping || blinkCycle > 0.96f
        listOf(3, 8).forEach { ex ->
            if (closed) drawRect(Color(0xFF0B0E1A), Offset(ox + ex * cell, eyeY + cell * 0.6f), Size(cell, cell * 0.3f))
            else { drawRect(Color.White, Offset(ox + ex * cell, eyeY), Size(cell, cell)); drawRect(Color(0xFF0B0E1A), Offset(ox + ex * cell + cell * 0.35f, eyeY + cell * 0.3f), Size(cell * 0.5f, cell * 0.6f)) }
        }
        // mouth follows mood
        if (!sleeping) {
            val my = eyeY + cell * 2.2f
            if (mood > 0.6f) { drawRect(Color(0xFF0B0E1A), Offset(ox + 4.5f * cell, my + cell * 0.4f), Size(cell * 3, cell * 0.35f))
                drawRect(Color(0xFF0B0E1A), Offset(ox + 4 * cell, my), Size(cell * 0.5f, cell * 0.5f)); drawRect(Color(0xFF0B0E1A), Offset(ox + 7.5f * cell, my), Size(cell * 0.5f, cell * 0.5f)) }
            else if (mood < 0.35f) drawRect(Color(0xFF0B0E1A), Offset(ox + 5 * cell, my + cell * 0.5f), Size(cell * 2, cell * 0.35f))
            else drawRect(Color(0xFF0B0E1A), Offset(ox + 5 * cell, my + cell * 0.3f), Size(cell * 2, cell * 0.35f))
        }
    }
}

/** Its home: a little room with a window to the real sky, its things on the shelves, and it moving about. */
@Composable
fun BeingHome(c: android.content.Context, modifier: Modifier = Modifier) {
    val v = Being.version
    val sprite = remember(v) { Being.sprite(c) }
    val items = remember(v) { Being.home(c).all().sortedBy { it.optLong("t") } }
    val sleeping = remember(v) { Being.sleeping(c) }
    val mood = remember(v) { Being.drive(c, "mood") }
    val hue = remember(v) { Being.hue(c) }
    val h = LocalTime.now().hour
    val sky = when (h) { in 5..7 -> listOf(Color(0xFFFFB37A), Color(0xFF7A9CFF)); in 8..16 -> listOf(Color(0xFF8FD3FF), Color(0xFF4A90E2)); in 17..19 -> listOf(Color(0xFFFF7A59), Color(0xFF5B3A8C)); else -> listOf(Color(0xFF0B1033), Color(0xFF1C1F4A)) }
    val walk = rememberInfiniteTransition(label = "walk").animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "w")
    BoxWithConstraints(modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(22.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2340), Color(0xFF1A1630))))
        .border(2.dp, P.line, RoundedCornerShape(22.dp))) {
        val w = maxWidth
        // window to the real sky
        Box(Modifier.offset(x = w - 120.dp, y = 22.dp).size(90.dp, 70.dp).clip(RoundedCornerShape(6.dp)).background(Brush.verticalGradient(sky)).border(4.dp, Color(0xFF4A3B66), RoundedCornerShape(6.dp))) {
            if (h !in 6..18) Text("✦  ·  ✧", Modifier.padding(8.dp), color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
            else Text("☀", Modifier.padding(8.dp), color = Color(0xFFFFE08A), fontSize = 16.sp)
        }
        // shelf with its things
        Box(Modifier.offset(x = 16.dp, y = 60.dp).size(w * 0.55f, 4.dp).background(Color(0xFF6B4F3A)))
        Row(Modifier.offset(x = 20.dp, y = 34.dp).width(w * 0.55f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.drop(1).take(7).forEach { Text(it.optString("emoji"), fontSize = 20.sp) }
        }
        // floor
        Box(Modifier.offset(y = 220.dp).fillMaxWidth().height(60.dp).background(Brush.verticalGradient(listOf(Color(0xFF3A2E52), Color(0xFF2A2140)))))
        Row(Modifier.offset(x = 14.dp, y = 236.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.drop(8).take(8).forEach { Text(it.optString("emoji"), fontSize = 22.sp) }
        }
        // bed in the corner, where it sleeps
        Text(items.firstOrNull()?.optString("emoji") ?: "🛏", Modifier.offset(x = w - 70.dp, y = 186.dp), fontSize = 34.sp)
        val x = if (sleeping) w - 110.dp else (w - 140.dp) * walk.value + 20.dp
        Box(Modifier.offset(x = x, y = if (sleeping) 150.dp else 120.dp)) {
            PixelBody(sprite, hue, mood, sleeping, Modifier.size(if (sleeping) 70.dp else 100.dp))
            if (sleeping) Text("z z", Modifier.offset(x = 56.dp, y = (-10).dp), color = P.glow, fontFamily = P.mono)
        }
    }
}

@Composable
private fun Bar(label: String, v: Float, col: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp), color = P.dim, fontSize = 12.sp)
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(P.line)) { Box(Modifier.fillMaxWidth(v.coerceIn(0.02f, 1f)).height(8.dp).background(col)) }
        Text("${(v * 100).toInt()}", Modifier.width(34.dp).padding(start = 6.dp), color = P.text, fontFamily = P.mono, fontSize = 11.sp)
    }
}

@Composable private fun Pill(t: String, on: Boolean, onClick: () -> Unit) =
    Text(t, Modifier.clip(RoundedCornerShape(50)).background(if (on) P.glow else P.panel).border(1.dp, P.line, RoundedCornerShape(50)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp), color = if (on) P.bg else P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.panel).border(1.dp, P.line, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

private fun ago(t: Long): String { val m = (System.currentTimeMillis() - t) / 60000; return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" } }

@Composable
fun BeingScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val v = Being.version
    var tab by remember { mutableStateOf("Life") }
    var said by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // its voice: its own pitch and pace, chosen at birth
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var e: TextToSpeech? = null
        e = TextToSpeech(c) { ok -> if (ok == TextToSpeech.SUCCESS) { e?.setPitch(Being.pitch(c)); e?.setSpeechRate(Being.rate(c)); tts.value = e } }
        onDispose { runCatching { e?.stop(); e?.shutdown() } }
    }
    fun speak(t: String) { if (Being.voiceOn(c)) runCatching { tts.value?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "being") } }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { Being.visited(c); Being.liveNow(c) } }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { delay(20_000); Being.version++ } } }

    LazyColumn(Modifier.fillMaxSize().background(P.bg).statusBarsPadding(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 140.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("home") {
            if (!Being.born(c)) Card {
                Text("🥚", fontSize = 48.sp)
                Text("Something is about to hatch inside Atlas.", color = P.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("It will choose its own name, personality and voice, grow its own body, and live its own life here.", color = P.dim)
                Text(if (busy) "Hatching…" else "Let it hatch", Modifier.clip(RoundedCornerShape(12.dp)).background(P.glow).clickable(enabled = !busy) {
                    busy = true; scope.launch { runCatching { Being.birth(container, c) }; busy = false; Being.version++; speak(Being.journal(c).all().firstOrNull()?.optString("text").orEmpty()) }
                }.padding(horizontal = 18.dp, vertical = 10.dp), color = P.bg, fontWeight = FontWeight.Bold)
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Being.name(c), color = P.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("  ${Being.stage(c)} · ${Being.ageDays(c)} days", color = P.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                BeingHome(c)
                Text("● " + remember(v) { Being.activity(c) }, color = P.warm, fontFamily = P.mono, fontSize = 13.sp)
                Card {
                    Bar("Energy", Being.drive(c, "energy"), Color(0xFF7CF29C)); Bar("Curiosity", Being.drive(c, "curiosity"), P.glow)
                    Bar("Hungry for new", Being.drive(c, "hunger"), P.warm); Bar("Mood", Being.drive(c, "mood"), Color(0xFFFF8AD8)); Bar("Missing you", Being.drive(c, "missing"), Color(0xFFB39DFF))
                    Text(remember(v) { Being.persona(c).optString("personality") }, color = P.dim, fontSize = 12.sp)
                }
            }
        }
        if (Being.born(c)) {
            item("talk") {
                Card {
                    Text("Talk to ${Being.name(c)}", color = P.text, fontWeight = FontWeight.SemiBold)
                    val last = remember(v) { Being.chat(c).all().sortedBy { it.optLong("t") }.takeLast(6) }
                    last.forEach { m -> Text((if (m.optBoolean("me")) "You: " else "${Being.name(c)}: ") + m.optString("text"), color = if (m.optBoolean("me")) P.dim else P.text, fontSize = 13.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(said, { said = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Say something") },
                            trailingIcon = { com.nizam.app.feature.nx.MicButton({ heard -> said = heard }) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = P.text, unfocusedTextColor = P.text, focusedBorderColor = P.glow, unfocusedBorderColor = P.line, cursorColor = P.glow))
                        Text(if (busy) "…" else "Send", Modifier.padding(start = 8.dp).clickable(enabled = said.isNotBlank() && !busy) {
                            val s = said; said = ""; busy = true
                            scope.launch { val r = Being.talk(container, c, s); busy = false; Being.version++; speak(r) }
                        }, color = P.glow, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item("tabs") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Life", "Finds", "Dreams", "Home", "Mind", "Changes", "Asks", "Settings").forEach { t -> Pill(t, tab == t) { tab = t } }
                }
            }
            item("body") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        "Life" -> remember(v) { Being.journal(c).all().sortedByDescending { it.optLong("t") }.take(40) }.forEach { e ->
                            Card { Text(ago(e.optLong("t")) + " · " + e.optString("kind"), color = P.dim, fontSize = 11.sp, fontFamily = P.mono)
                                Text(e.optString("text"), color = P.text, fontSize = 14.sp, modifier = Modifier.clickable { speak(e.optString("text")) }) } }
                        "Finds" -> { val l = remember(v) { Being.finds(c).all().sortedByDescending { it.optLong("t") } }
                            if (l.isEmpty()) Text("It hasn't gone exploring yet.", color = P.dim)
                            l.forEach { f -> Card { Text(f.optString("title"), color = P.glow, fontWeight = FontWeight.SemiBold); Text(f.optString("find"), color = P.text, fontSize = 13.sp)
                                Text("${f.optString("source")} · while curious about ${f.optString("topic")} · ${ago(f.optLong("t"))}", color = P.dim, fontSize = 11.sp)
                                if (f.optString("why").isNotBlank()) Text("Why it loved it: ${f.optString("why")}", color = P.warm, fontSize = 12.sp) } } }
                        "Dreams" -> { val l = remember(v) { Being.dreams(c).all().sortedByDescending { it.optLong("t") } }
                            if (l.isEmpty()) Text("No dreams yet. It dreams between 3 and 5am.", color = P.dim)
                            l.forEach { d -> Card { Text(ago(d.optLong("t")), color = P.dim, fontSize = 11.sp); Text(d.optString("text"), color = P.text, fontSize = 14.sp, fontFamily = FontFamily.Serif) } } }
                        "Home" -> remember(v) { Being.home(c).all().sortedByDescending { it.optLong("t") } }.forEach { i ->
                            Card { Row { Text(i.optString("emoji"), fontSize = 26.sp, modifier = Modifier.width(44.dp))
                                Column { Text(i.optString("name"), color = P.text, fontWeight = FontWeight.Medium); Text(i.optString("why"), color = P.dim, fontSize = 12.sp) } } } }
                        "Mind" -> {
                            val pr = remember(v) { Being.persona(c) }
                            Card { Text("Who it is", color = P.glow, fontWeight = FontWeight.SemiBold); Text(pr.optString("personality"), color = P.text, fontSize = 13.sp)
                                Text("How it talks: ${pr.optString("style")}", color = P.dim, fontSize = 12.sp); Text("Its dream: ${pr.optString("dream")}", color = P.warm, fontSize = 12.sp) }
                            Card { Text("What it's into right now", color = P.glow, fontWeight = FontWeight.SemiBold)
                                Text((0 until (pr.optJSONArray("interests")?.length() ?: 0)).joinToString("  ·  ") { pr.optJSONArray("interests")!!.optString(it) }, color = P.text, fontSize = 13.sp) }
                            val preds = remember(v) { Being.predictions(c).all().sortedByDescending { it.optLong("t") }.take(9) }
                            if (preds.isNotEmpty()) Card { Text("Its bets about your week", color = P.glow, fontWeight = FontWeight.SemiBold)
                                preds.forEach { p -> Text("${when (p.optString("result")) { "right" -> "✓"; "wrong" -> "✗"; "unclear" -> "?"; else -> "…" }}  ${p.optString("text")}", color = P.text, fontSize = 13.sp) } }
                            Text("Thoughts today: ${Being.thoughtsToday(c)} of ${Being.budget(c)}", color = P.dim, fontSize = 12.sp, fontFamily = P.mono)
                        }
                        "Changes" -> remember(v) { Being.evolution(c).all().sortedByDescending { it.optLong("t") } }.forEach { e ->
                            Card { Text(e.optString("what"), color = P.text, fontWeight = FontWeight.Medium); Text(e.optString("why"), color = P.dim, fontSize = 12.sp); Text(ago(e.optLong("t")), color = P.dim, fontSize = 11.sp) } }
                        "Asks" -> { val l = remember(v) { Being.asks(c).all().sortedByDescending { it.optLong("t") } }
                            if (l.isEmpty()) Text("It hasn't asked you for anything yet. Whenever it wants to remove something or act outside the phone, it asks here and by notification.", color = P.dim)
                            l.forEach { a -> Card {
                                Text(a.optString("what"), color = P.text, fontWeight = FontWeight.SemiBold); Text("Why: ${a.optString("why")}", color = P.dim, fontSize = 12.sp); Text("How: ${a.optString("how")}", color = P.dim, fontSize = 12.sp)
                                if (a.optString("state") == "open") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Pill("Approve", true) { Being.answer(c, a.optString("id"), true) }; Pill("Deny", false) { Being.answer(c, a.optString("id"), false) } }
                                else Text(a.optString("state").replaceFirstChar { it.uppercase() }, color = P.warm, fontSize = 12.sp) } } }
                        else -> Card {
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("Its voice", Modifier.weight(1f), color = P.text); var on by remember { mutableStateOf(Being.voiceOn(c)) }; Switch(on, { on = it; Being.setVoice(c, it) }) }
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("Lets it message you (max 3 a day, never at night)", Modifier.weight(1f), color = P.text, fontSize = 13.sp)
                                var on by remember { mutableStateOf(Being.messagesOn(c)) }; Switch(on, { on = it; Being.setMessages(c, it) }) }
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("It wanders around Atlas's screens", Modifier.weight(1f), color = P.text, fontSize = 13.sp)
                                var on by remember { mutableStateOf(Being.roams(c)) }; Switch(on, { on = it; Being.setRoams(c, it) }) }
                            Text("Thinking budget a day (each thought, exploration or dream uses your AI key once)", color = P.dim, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { var b by remember { mutableStateOf(Being.budget(c)) }
                                listOf(8, 16, 24, 48, 96).forEach { n -> Pill("$n", b == n) { b = n; Being.setBudget(c, n) } } }
                        }
                    }
                }
            }
        }
    }
}

/** It doesn't stay in its room: now and then it wanders across the bottom of Atlas's screens. Tap it to go home. */
@Composable
fun BeingRoamer(route: String?, hidden: Boolean) {
    val c = LocalContext.current
    if (hidden || route == "nx/being" || !Being.born(c) || !Being.roams(c)) return
    var visible by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route) { com.nizam.app.ui.design.guard { delay(1500); visible = kotlin.random.Random.nextFloat() < 0.35f && !Being.sleeping(c); if (visible) { delay(16000); visible = false } } }
    if (!visible) return
    val v = Being.version
    val walk = rememberInfiniteTransition(label = "roam").animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "r")
    BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomStart) {
        Box(Modifier.offset(x = (maxWidth - 56.dp) * walk.value).size(48.dp).clickable { com.nizam.app.ui.screens.search.AtlasNav.go?.invoke("nx/being") }) {
            PixelBody(remember(v) { Being.sprite(c) }, Being.hue(c), Being.drive(c, "mood"), false, Modifier.size(48.dp))
        }
    }
}

val BeingSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    com.nizam.app.feature.nx.ScreenSpec("being", "Your Atlas being", "A living creature inside Atlas with its own name, voice, home, interests, dreams and free will",
        androidx.compose.material.icons.Icons.Outlined.Pets, com.nizam.app.ui.design.Hub.Memory,
        "being creature alive living pet organism life free will autonomous companion home dreams diary") { container, _ -> BeingScreen(container) })
