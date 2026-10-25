package com.nizam.app.feature.being

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun Pill(t: String, on: Boolean, onClick: () -> Unit) =
    Text(t, Modifier.clip(RoundedCornerShape(50)).background(if (on) BP.glow else BP.panel).border(1.dp, BP.line, RoundedCornerShape(50)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp), color = if (on) BP.bg else BP.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BP.panel).border(1.dp, BP.line, RoundedCornerShape(16.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable private fun Small(t: String) = Text(t, color = BP.dim, fontSize = 11.sp, fontFamily = BP.mono)

private val KIND_LABEL = mapOf("tool" to "🛠 Tool", "learn" to "📚 Lesson", "art" to "🎨 Painting", "story" to "📖 Story", "poem" to "✒ Poem", "song" to "🎵 Song", "joke" to "😂 Jokes",
    "invention" to "💡 Invention", "comic" to "🗯 Comic", "project" to "🗂 Project", "pc" to "💻 On its PC", "news" to "📰 News")

@Composable
fun BeingScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val v = Being.version
    var tab by remember { mutableStateOf("Talk") }
    var busy by remember { mutableStateOf(false) }
    var mindOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { BeingVoice.ensure(c); Being.upgradeBody(c); Being.visited(c); Being.liveNow(c) } }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { delay(20_000); Being.liveIfDue(c); Being.version++ } } }

    LazyColumn(Modifier.fillMaxSize().background(BP.bg).statusBarsPadding(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 140.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("home") {
            if (!Being.born(c)) Card {
                Text("🥚", fontSize = 48.sp)
                Text("Something is about to hatch inside Atlas.", color = BP.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("A little robot that picks its own name, personality and voice, and lives its own life here: exploring, learning, making things, using its own computer. It never sees your data.", color = BP.dim)
                Text(if (busy) "Hatching…" else "Let it hatch", Modifier.clip(RoundedCornerShape(12.dp)).background(BP.glow).clickable(enabled = !busy) {
                    busy = true; scope.launch { runCatching { Being.birth(container, c) }; busy = false; Being.version++ }
                }.padding(horizontal = 18.dp, vertical = 10.dp), color = BP.bg, fontWeight = FontWeight.Bold)
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Being.name(c), color = BP.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("  ${Being.stage(c)} · ${Being.ageDays(c)} days", color = BP.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp).weight(1f))
                    Text("🧠", Modifier.clip(RoundedCornerShape(50)).clickable { mindOpen = true }.padding(6.dp), fontSize = 20.sp)
                }
                RoomScene(container, 300.dp, 80.dp, Mode.ROOM)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { mindOpen = true }) {
                    Text(remember(v) { Being.icon(c) } + "  ", fontSize = 16.sp)
                    Text(remember(v) { Being.activity(c) }, Modifier.weight(1f), color = BP.warm, fontFamily = BP.mono, fontSize = 13.sp)
                    Text("why? ›", color = BP.glow, fontSize = 12.sp)
                }
                Text("Tap it to poke, double-tap to make it jump, hold to pick it up, rub it to make it happy. Tap its thought cloud to see how it's thinking.", color = BP.dim, fontSize = 11.sp)
            }
        }
        if (Being.born(c)) {
            item("tabs") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Talk", "Life", "Mind", "Made", "Skills", "Finds", "Dreams", "Notes", "Home", "Changes", "Asks", "Settings").forEach { t -> Pill(t, tab == t) { tab = t } }
                }
            }
            item("body") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        "Talk" -> Card { BeingChat(container, maxHeight = 460.dp) }
                        "Life" -> {
                            Card {
                                Being.DRIVES.forEach { k -> MiniBar(Being.DRIVE_NAMES[k] ?: k, remember(v) { Being.drive(c, k) }, when (k) { "energy" -> Color(0xFF7CF29C); "curiosity" -> BP.glow; "hunger" -> BP.warm; "mood" -> Color(0xFFFF8AD8); else -> Color(0xFFB39DFF) }) }
                                Text(remember(v) { Being.persona(c).optString("personality") }, color = BP.dim, fontSize = 12.sp)
                            }
                            remember(v) { Being.journal(c).all().sortedByDescending { it.optLong("t") }.take(40) }.forEach { e ->
                                Card { Small(ago(e.optLong("t")) + " · " + e.optString("kind"))
                                    Text(e.optString("text"), color = BP.text, fontSize = 14.sp, modifier = Modifier.clickable { BeingVoice.speak(c, e.optString("text")) }) } }
                        }
                        "Mind" -> {
                            val pr = remember(v) { Being.persona(c) }
                            Card(onClick = { mindOpen = true }) { Text("What it's thinking now", color = BP.glow, fontWeight = FontWeight.SemiBold)
                                Text(remember(v) { Being.mind(c).let { "${it.optString("icon")} ${it.optString("line")}\n${it.optString("why")}" } }, color = BP.text, fontSize = 13.sp)
                                Text("See its whole reasoning ›", color = BP.glow, fontSize = 12.sp) }
                            Card { Text("Who it is", color = BP.glow, fontWeight = FontWeight.SemiBold); Text(pr.optString("personality"), color = BP.text, fontSize = 13.sp)
                                Text("Its dream: ${pr.optString("dream")}", color = BP.warm, fontSize = 12.sp)
                                Text("Into right now: " + Being.interests(c).joinToString("  ·  "), color = BP.text, fontSize = 13.sp) }
                            val preds = remember(v) { Being.predictions(c).all().sortedByDescending { it.optLong("t") }.take(9) }
                            if (preds.isNotEmpty()) Card { Text("Its bets about the world this week", color = BP.glow, fontWeight = FontWeight.SemiBold)
                                preds.forEach { p -> Text("${when (p.optString("result")) { "right" -> "✓"; "wrong" -> "✗"; "unclear" -> "?"; else -> "…" }}  ${p.optString("text")}", color = BP.text, fontSize = 13.sp) } }
                            remember(v) { Being.minds(c).all().sortedByDescending { it.optLong("t") }.take(25) }.forEach { m ->
                                Card { Row { Text(m.optString("icon"), fontSize = 18.sp, modifier = Modifier.width(30.dp)); Column(Modifier.weight(1f)) {
                                    Text(m.optString("line"), color = BP.text, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text(m.optString("why"), color = BP.dim, fontSize = 12.sp) }
                                    Small(ago(m.optLong("t"))) } } }
                            Small("Thoughts today: ${Being.thoughtsToday(c)} of ${Being.budget(c)}")
                        }
                        "Made" -> {
                            val all = remember(v) { World.works(c).all().sortedByDescending { it.optLong("t") } }
                            var kind by remember { mutableStateOf("all") }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (listOf("all") + all.map { it.optString("kind") }.distinct()).forEach { k -> Pill(if (k == "all") "Everything" else KIND_LABEL[k] ?: k, kind == k) { kind = k } }
                            }
                            if (all.isEmpty()) Text("It hasn't made anything yet. It uses Atlas's tools, paints, writes, learns and runs things on its own PC as it lives.", color = BP.dim)
                            all.filter { kind == "all" || it.optString("kind") == kind }.take(40).forEach { w -> WorkCard(w, onOpen) }
                        }
                        "Skills" -> {
                            val sk = remember(v) { World.skills(c).entries.sortedByDescending { it.value } }
                            Card { Text("Skills it's learning", color = BP.glow, fontWeight = FontWeight.SemiBold)
                                if (sk.isEmpty()) Text("Nothing yet.", color = BP.dim)
                                sk.forEach { (k, x) -> val lv = World.level(x); val next = lv * lv * 3; val prev = (lv - 1) * (lv - 1) * 3
                                    MiniBar("$k · Lv $lv", ((x - prev).toFloat() / (next - prev).coerceAtLeast(1)), BP.warm, label = "$x xp") } }
                            remember(v) { World.projects(c).all().sortedByDescending { it.optLong("t") } }.forEach { pj ->
                                val steps = pj.optJSONArray("steps"); val n = steps?.length() ?: 0; val d = pj.optInt("done")
                                Card { Text("🗂 ${pj.optString("title")}", color = BP.text, fontWeight = FontWeight.SemiBold)
                                    Text(pj.optString("goal"), color = BP.dim, fontSize = 12.sp)
                                    MiniBar(if (pj.optString("state") == "done") "Finished" else "Step $d of $n", if (n == 0) 0f else d.toFloat() / n, if (pj.optString("state") == "done") Color(0xFF7CF29C) else BP.glow, label = "${if (n == 0) 0 else d * 100 / n}%")
                                    for (i in 0 until n) Text("${if (i < d) "✓" else "○"}  ${steps!!.optString(i)}", color = if (i < d) BP.text else BP.dim, fontSize = 13.sp)
                                    val log = pj.optJSONArray("log")
                                    if (log != null && log.length() > 0) Text(log.optJSONObject(log.length() - 1)?.optString("did").orEmpty(), color = BP.warm, fontSize = 12.sp) }
                            }
                        }
                        "Finds" -> { val l = remember(v) { Being.finds(c).all().sortedByDescending { it.optLong("t") } }
                            if (l.isEmpty()) Text("It hasn't gone exploring yet.", color = BP.dim)
                            l.forEach { f -> Card { Text(f.optString("title"), color = BP.glow, fontWeight = FontWeight.SemiBold); Text(f.optString("find"), color = BP.text, fontSize = 13.sp)
                                Small("${f.optString("source")} · while curious about ${f.optString("topic")} · ${ago(f.optLong("t"))}")
                                if (f.optString("why").isNotBlank()) Text("Why it loved it: ${f.optString("why")}", color = BP.warm, fontSize = 12.sp) } } }
                        "Dreams" -> { val l = remember(v) { Being.dreams(c).all().sortedByDescending { it.optLong("t") } }
                            if (l.isEmpty()) Text("No dreams yet. It dreams between 3 and 5am.", color = BP.dim)
                            l.forEach { d -> Card { Small(ago(d.optLong("t"))); Text(d.optString("text"), color = BP.text, fontSize = 14.sp, fontFamily = FontFamily.Serif) } } }
                        "Notes" -> { val l = remember(v) { World.notes(c).all().sortedByDescending { it.optLong("t") } }
                            if (l.isEmpty()) Text("It leaves sticky notes on Atlas's screens as it wanders. None yet.", color = BP.dim)
                            l.forEach { n -> Card(onClick = { onOpen(n.optString("route")) }) { Text("📝 ${n.optString("title")}", color = BP.text, fontWeight = FontWeight.Medium)
                                Text(n.optString("text"), color = BP.dim, fontSize = 13.sp); Small(ago(n.optLong("t")) + if (n.optBoolean("seen")) " · read" else " · waiting there for you") } } }
                        "Home" -> remember(v) { Being.home(c).all().sortedByDescending { it.optLong("t") } }.forEach { i ->
                            Card { Row { Text(i.optString("emoji"), fontSize = 26.sp, modifier = Modifier.width(44.dp))
                                Column { Text(i.optString("name"), color = BP.text, fontWeight = FontWeight.Medium); Text(i.optString("why"), color = BP.dim, fontSize = 12.sp) } } } }
                        "Changes" -> remember(v) { Being.evolution(c).all().sortedByDescending { it.optLong("t") } }.forEach { e ->
                            Card { Text(e.optString("what"), color = BP.text, fontWeight = FontWeight.Medium); Text(e.optString("why"), color = BP.dim, fontSize = 12.sp); Small(ago(e.optLong("t"))) } }
                        "Asks" -> { val l = remember(v) { Being.asks(c).all().sortedByDescending { it.optLong("t") } }
                            if (l.isEmpty()) Text("It hasn't asked for anything yet. Whenever it wants to remove something, delete files on its PC or act outside the phone, it asks here and by notification.", color = BP.dim)
                            l.forEach { a -> Card {
                                Text(a.optString("what"), color = BP.text, fontWeight = FontWeight.SemiBold); Text("Why: ${a.optString("why")}", color = BP.dim, fontSize = 12.sp); Text("How: ${a.optString("how")}", color = BP.dim, fontSize = 12.sp)
                                if (a.optString("state") == "open") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Pill("Approve", true) { Being.answer(c, a.optString("id"), true) }; Pill("Deny", false) { Being.answer(c, a.optString("id"), false) } }
                                else Text(a.optString("state").replaceFirstChar { it.uppercase() }, color = BP.warm, fontSize = 12.sp) } } }
                        else -> Settings(onOpen)
                    }
                }
            }
        }
    }
    if (mindOpen) MindDialog { mindOpen = false }
}

@Composable
private fun WorkCard(w: JSONObject, onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Small((KIND_LABEL[w.optString("kind")] ?: w.optString("kind")) + " · " + ago(w.optLong("t")) + (w.optString("tool_title").takeIf { it.isNotBlank() }?.let { " · with $it" } ?: ""))
                Text(w.optString("title"), color = BP.text, fontWeight = FontWeight.SemiBold) }
            if (w.optString("kind") == "art") Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp))) { PixelArt(w, Modifier.fillMaxSize()) }
        }
        if (w.optString("kind") == "art" && open) Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { PixelArt(w, Modifier.size(256.dp)) }
        val body = w.optString("body")
        if (body.isNotBlank()) Text(if (open) body else body.take(160) + if (body.length > 160) "…" else "", color = BP.text.copy(alpha = 0.9f), fontSize = 13.sp,
            fontFamily = if (w.optString("kind") == "pc") BP.mono else null)
        if (open) {
            if (w.optString("why").isNotBlank()) Text("Why: ${w.optString("why")}", color = BP.warm, fontSize = 12.sp)
            if (w.optString("url").isNotBlank()) Text(w.optString("url"), color = BP.glow, fontSize = 12.sp)
            if (w.optString("route").isNotBlank()) Text("Open ${w.optString("tool_title")} ›", Modifier.clickable { onOpen(w.optString("route")) }, color = BP.glow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Settings(onOpen: (String) -> Unit) {
    val c = LocalContext.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Its voice", Modifier.weight(1f), color = BP.text); var on by remember { mutableStateOf(Being.voiceOn(c)) }; Switch(on, { on = it; Being.setVoice(c, it); if (!it) BeingVoice.stop() }) }
        Text("Which voice (the best ones on your phone first; tap to hear it)", color = BP.dim, fontSize = 12.sp)
        var voices by remember { mutableStateOf(BeingVoice.voices(c)) }
        LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { BeingVoice.ensure(c); repeat(10) { if (voices.isEmpty()) { delay(500); voices = BeingVoice.voices(c) } } } }
        var chosen by remember { mutableStateOf(Being.voiceName(c)) }
        if (voices.isEmpty()) Text("Loading voices… For a much more natural voice, install Speech Services by Google and its high-quality English voices.", color = BP.dim, fontSize = 12.sp)
        voices.take(12).forEach { vo -> Row(Modifier.fillMaxWidth().clickable { chosen = vo.name; BeingVoice.use(c, vo.name); BeingVoice.preview(c, vo.name) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (chosen == vo.name) "●" else "○", color = BP.glow, modifier = Modifier.width(22.dp))
            Text("${vo.locale.displayCountry.ifBlank { vo.locale.displayLanguage }} · ${vo.name.substringAfterLast('-').take(14)}", Modifier.weight(1f), color = BP.text, fontSize = 13.sp)
            Small(when { vo.quality >= 500 -> "natural"; vo.quality >= 400 -> "good"; else -> "basic" } + if (vo.isNetworkConnectionRequired) " · online" else "")
        } }
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("It walks around Atlas's screens", Modifier.weight(1f), color = BP.text, fontSize = 13.sp)
            var on by remember { mutableStateOf(Being.roams(c)) }; Switch(on, { on = it; Being.setRoams(c, it) }) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { var lv by remember { mutableStateOf(Being.roamLevel(c)) }
            listOf("Sometimes", "Often", "Always around").forEachIndexed { i, n -> Pill(n, lv == i) { lv = i; Being.setRoamLevel(c, i) } } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("It comments on the screen you're on (it only knows the screen's name)", Modifier.weight(1f), color = BP.text, fontSize = 13.sp)
            var on by remember { mutableStateOf(Being.comments(c)) }; Switch(on, { on = it; Being.setComments(c, it) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("It can message you (max 3 a day, never at night)", Modifier.weight(1f), color = BP.text, fontSize = 13.sp)
            var on by remember { mutableStateOf(Being.messagesOn(c)) }; Switch(on, { on = it; Being.setMessages(c, it) }) }
    }
    Card {
        Text("Its own computer", color = BP.glow, fontWeight = FontWeight.SemiBold)
        Text("Give it one of Atlas's PCs and it lives on it: writes programs, runs experiments, downloads things, in its own folder. Anything that deletes files waits for your OK. Make a separate PC just for it so it never touches yours.", color = BP.dim, fontSize = 12.sp)
        val pcs = remember { runCatching { com.nizam.app.feature.pc.PcEnv.all(c).filter { it.optBoolean("ready") } }.getOrDefault(emptyList()) }
        var pc by remember { mutableStateOf(World.pcId(c)) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("None", pc == null) { pc = null; World.setPc(c, null) }
            pcs.forEach { o -> Pill(o.optString("name"), pc == o.optString("id")) { pc = o.optString("id"); World.setPc(c, o.optString("id")); Being.write(c, "home", "I've got my own computer now. ${o.optString("name")}. Mine.") } }
        }
        Text("Set up a new PC ›", Modifier.clickable { onOpen("nx/pc") }, color = BP.glow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Card {
        Text("Thinking budget a day (each thought, exploration, tool use or dream uses your AI key once)", color = BP.dim, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { var b by remember { mutableStateOf(Being.budget(c)) }
            listOf(8, 16, 24, 48, 96, 200).forEach { n -> Pill("$n", b == n) { b = n; Being.setBudget(c, n) } } }
        Text("What it can use: the whole internet, every AI tool in Atlas (for its own projects), Tool Forge to build gifts, its own PC. What it can't: your data, files, messages, contacts, photos, or anything on your phone.", color = BP.dim, fontSize = 12.sp)
    }
}

val BeingSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    com.nizam.app.feature.nx.ScreenSpec("being", "Your Atlas being", "A little robot living inside Atlas with its own name, voice, room, skills, projects, computer and free will",
        androidx.compose.material.icons.Icons.Outlined.Pets, com.nizam.app.ui.design.Hub.Memory,
        "being creature alive living pet robot bot organism life free will autonomous companion home dreams diary") { container, open -> BeingScreen(container, open) })
