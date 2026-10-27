package com.nizam.app.feature.omni

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.being.Being
import com.nizam.app.feature.being.BeingVoice
import com.nizam.app.feature.being.Body
import com.nizam.app.feature.being.Face
import com.nizam.app.feature.being.Look
import com.nizam.app.feature.being.Pose
import com.nizam.app.feature.being.Robot
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.random.Random

// ─────────────── The being's village ───────────────
object Village {
    fun people(c: Context) = JsonStore(c, "omni_village_people")
    fun days(c: Context) = JsonStore(c, "omni_village_days")

    suspend fun ensure(c: Context) {
        if (people(c).all().isNotEmpty()) return
        val o = O.aiJson(c, "Invent 5 little robot neighbours living in a tiny village inside a phone app, next to a robot called ${Being.name(c)}. Give them jobs (baker, postman, inventor…), distinct personalities and one quirk each. " +
            "Output only JSON: {\"people\":[{\"name\":\"\",\"job\":\"\",\"personality\":\"\",\"quirk\":\"\"}]}", 600)
        val list = O.arr(o?.optJSONArray("people")).ifEmpty { listOf("Bolt|baker", "Rivet|postman", "Nimbus|weather watcher", "Cog|inventor", "Pixie|librarian").map { JSONObject().put("name", it.substringBefore('|')).put("job", it.substringAfter('|')).put("personality", "friendly").put("quirk", "hums") } }
        val r = Random(System.nanoTime())
        list.take(5).forEach { p -> people(c).add(p.put("look", Look.random(r).json()).put("coins", 50 + r.nextInt(100)).put("mood", 0.6).put("friends", JSONArray())) }
    }

    suspend fun tick(c: Context) {
        if (!Being.born(c)) return
        val today = LocalDate.now().toString()
        if (days(c).all().any { it.optString("date") == today } || java.time.LocalTime.now().hour < 7) return
        ensure(c); simulate(c)
    }

    suspend fun simulate(c: Context): JSONObject? {
        val today = LocalDate.now().toString()
        val ppl = people(c).all()
        val yesterday = days(c).all().maxByOrNull { it.optLong("t") }
        val o = O.aiJson(c, "Simulate one day in a tiny robot village. Residents: ${ppl.joinToString("; ") { "${it.optString("name")} the ${it.optString("job")} (${it.optString("personality")}, quirk: ${it.optString("quirk")}, ${it.optInt("coins")} coins)" }}; " +
            "and ${Being.name(c)} (${Being.persona(c).optString("personality")}, currently: ${Being.activity(c)}). Yesterday: ${yesterday?.optString("headline") ?: "the village was founded"}. ${yesterday?.optString("summary").orEmpty()}\n" +
            "Make it a gentle soap opera: small events, a bit of gossip, trades, a friendship or a squabble, continuity with yesterday. " +
            "Output only JSON: {\"headline\":\"village newspaper headline\",\"summary\":\"2 sentences\",\"events\":[{\"who\":\"name\",\"what\":\"one line\"}],\"gossip\":\"one juicy but kind line\",\"trades\":[{\"from\":\"name\",\"to\":\"name\",\"coins\":5,\"for\":\"\"}],\"weather\":\"\"}", 1000) ?: return null
        O.arr(o.optJSONArray("trades")).forEach { t -> ppl.firstOrNull { it.optString("name") == t.optString("from") }?.let { p -> people(c).update(p.optString("id")) { it.put("coins", it.optInt("coins") - t.optInt("coins")) } }
            ppl.firstOrNull { it.optString("name") == t.optString("to") }?.let { p -> people(c).update(p.optString("id")) { it.put("coins", it.optInt("coins") + t.optInt("coins")) } } }
        days(c).add(o.put("date", today).put("t", System.currentTimeMillis()))
        Being.write(c, "village", "Village news: ${o.optString("headline")}")
        O.notify(c, 5890, "🏘 Village news", o.optString("headline"), "nx/village")
        return o
    }
}

@Composable
fun VillageScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var v by remember { mutableIntStateOf(0) }; var busy by remember { mutableStateOf(false) }
    var talkTo by remember { mutableStateOf<JSONObject?>(null) }; var said by remember { mutableStateOf("") }; var reply by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { if (Being.born(c)) { busy = true; Village.ensure(c); busy = false; v++ } } }
    val ppl = remember(v) { Village.people(c).all() }
    val days = remember(v) { Village.days(c).all().sortedByDescending { it.optLong("t") } }
    Column {
        if (!Being.born(c)) { OCard { OText("Your being hasn't hatched yet. Open Your Atlas being first; then it gets neighbours.") }; return@Column }
        Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(Color(0xFF7EC8F2), Color(0xFFBFE6C4))))) {
            Box(Modifier.fillMaxWidth().height(40.dp).align(Alignment.BottomCenter).background(Color(0xFF6FA86A)))
            Row(Modifier.align(Alignment.BottomStart).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Robot(Body.look(c), Pose.WAVE, Face.HAPPY, Modifier.size(48.dp, 68.dp)); Text(Being.name(c), fontSize = 10.sp, color = Color.Black) }
                ppl.forEachIndexed { i, p -> Column(Modifier.clickable { talkTo = p; reply = "" }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🏠", fontSize = 26.sp)
                    Robot(Look.from(p.optJSONObject("look") ?: JSONObject()), listOf(Pose.IDLE, Pose.READ, Pose.LOOK, Pose.CARRY, Pose.DANCE)[i % 5], Face.NORMAL, Modifier.size(42.dp, 60.dp))
                    Text(p.optString("name"), fontSize = 10.sp, color = Color.Black) } }
            }
        }
        OBusy(busy, "The village is waking up…")
        talkTo?.let { p -> OCard { OTitle("${p.optString("name")} the ${p.optString("job")}"); OMuted("${p.optString("personality")} · ${p.optString("quirk")} · ${p.optInt("coins")} coins")
            OField(said, "Say something to ${p.optString("name")}") { said = it }
            OBtn("Say it", said.isNotBlank()) { val s = said; said = ""; scope.launch { reply = O.ai(c, "You are ${p.optString("name")}, a little robot ${p.optString("job")} in a village inside a phone (${p.optString("personality")}, quirk: ${p.optString("quirk")}). " +
                "Your neighbour ${Being.name(c)}'s human says: \"$s\". Reply in character, one or two casual sentences, maybe some village gossip.", "Plain text, in character.", 150) } }
            if (reply.isNotBlank()) OText("${p.optString("name")}: $reply") } }
        OBtn2(if (busy) "Simulating…" else "Run a new village day", !busy) { busy = true; scope.launch { Village.ensure(c); Village.simulate(c); busy = false; v++ } }
        days.take(14).forEach { d -> OCard {
            Text("📰 ${d.optString("headline")}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OMuted("${d.optString("date")} · ${d.optString("weather")}"); OText(d.optString("summary"))
            O.arr(d.optJSONArray("events")).forEach { e -> OText("• ${e.optString("who")}: ${e.optString("what")}") }
            if (d.optString("gossip").isNotBlank()) OMuted("🤫 ${d.optString("gossip")}")
        } }
    }
}

// ─────────────── Being classes ───────────────
@Composable
fun BeingClassesScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var lesson by remember { mutableStateOf<JSONObject?>(null) }; var busy by remember { mutableStateOf(false) }
    val picked = remember { mutableStateOf(mapOf<Int, Int>()) }
    val score = c.getSharedPreferences("omni_classes", 0)
    Column {
        if (!Being.born(c)) { OCard { OText("Your being hasn't hatched yet.") }; return@Column }
        Row(verticalAlignment = Alignment.CenterVertically) { Robot(Body.look(c), if (busy) Pose.THINK else Pose.READ, if (busy) Face.THINKING else Face.HAPPY, Modifier.size(70.dp, 98.dp))
            Column(Modifier.padding(start = 10.dp)) { OTitle("${Being.name(c)}'s class"); OMuted("Two minutes on something it's been learning. Lessons passed: ${score.getInt("passed", 0)}") } }
        OBtn(if (busy) "Preparing…" else "Today's lesson", !busy) { busy = true; picked.value = emptyMap(); scope.launch {
            val learned = com.nizam.app.feature.being.World.works(c).all().filter { it.optString("kind") == "learn" }.maxByOrNull { it.optLong("t") }
            val topic = learned?.let { "${it.optString("title")}: ${it.optString("body").take(600)}" } ?: com.nizam.app.feature.being.World.skills(c).keys.randomOrNull() ?: "something it finds fascinating"
            lesson = O.aiJson(c, "You are ${Being.name(c)}, a little robot (${Being.persona(c).optString("personality")}) teaching your human what you've been learning: $topic. " +
                "Make a 2-minute lesson in your own casual voice, then a 3-question quiz. Output only JSON: {\"title\":\"\",\"lesson\":\"150-220 words\",\"quiz\":[{\"q\":\"\",\"options\":[\"\",\"\",\"\",\"\"],\"answer\":0}]}", 1200)
            busy = false; lesson?.optString("title")?.let { Being.say(c, "Okay class. Today: $it", "excited") } } }
        lesson?.let { l -> OCard { OTitle(l.optString("title")); OText(l.optString("lesson")); OBtn2("🔊 Read it to me") { BeingVoice.speak(c, l.optString("lesson"), "happy") } }
            val quiz = O.arr(l.optJSONArray("quiz"))
            quiz.forEachIndexed { qi, q -> OCard { OText("${qi + 1}. ${q.optString("q")}")
                O.strs(q.optJSONArray("options")).forEachIndexed { oi, o -> val sel = picked.value[qi]
                    Text((if (sel == oi) (if (oi == q.optInt("answer")) "✅ " else "❌ ") else "○ ") + o, Modifier.fillMaxWidth().clickable(enabled = sel == null) {
                        picked.value = picked.value + (qi to oi)
                        if (picked.value.size == quiz.size) { val right = quiz.indices.count { picked.value[it] == quiz[it].optInt("answer") }
                            if (right == quiz.size) { score.edit().putInt("passed", score.getInt("passed", 0) + 1).apply(); Being.say(c, "All correct! I'm so proud of you.", "excited") }
                            else Being.say(c, "$right out of ${quiz.size}. Not bad! We'll try again tomorrow.", "happy") }
                    }.padding(vertical = 6.dp)) } } }
        }
    }
}

// ─────────────── Life arcade ───────────────
@Composable
fun LifeArcadeScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var game by remember { mutableStateOf("Month quiz") }
    Column {
        OCard { OTitle("Life arcade"); OMuted("Little games made from your own life.")
            OChips(listOf("Month quiz", "Better week", "Typing race"), game) { game = it } }
        when (game) {
            "Month quiz" -> {
                var quiz by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var ans by remember { mutableStateOf(mapOf<Int, Int>()) }; var busy by remember { mutableStateOf(false) }
                OBtn(if (busy) "Building…" else "New quiz about my month", !busy) { busy = true; ans = emptyMap(); scope.launch {
                    val h = O.days(O.container(c), 30).filter { O.num(it, "tasks_done") + O.num(it, "spent") + O.num(it, "prayers") > 0 }
                    quiz = O.arr(O.aiJson(c, "Make a fun 5-question multiple-choice quiz about this person's last month, using ONLY these daily numbers: ${h.joinToString("\n")}\n" +
                        "Questions like: which day did you spend most, how many gym sets in total, best prayer streak. Output only JSON {\"quiz\":[{\"q\":\"\",\"options\":[\"\",\"\",\"\"],\"answer\":0,\"why\":\"\"}]}", 1100)?.optJSONArray("quiz")); busy = false } }
                quiz.forEachIndexed { i, q -> OCard { OText("${i + 1}. ${q.optString("q")}")
                    O.strs(q.optJSONArray("options")).forEachIndexed { oi, o -> val s = ans[i]
                        Text((if (s == null) "○ " else if (oi == q.optInt("answer")) "✅ " else if (oi == s) "❌ " else "○ ") + o, Modifier.fillMaxWidth().clickable(enabled = s == null) { ans = ans + (i to oi) }.padding(vertical = 5.dp)) }
                    if (ans[i] != null) OMuted(q.optString("why")) } }
                if (quiz.isNotEmpty() && ans.size == quiz.size) OTitle("Score: ${quiz.indices.count { ans[it] == quiz[it].optInt("answer") }} / ${quiz.size}")
            }
            "Better week" -> {
                var weeks by remember { mutableStateOf<Pair<List<JSONObject>, List<JSONObject>>?>(null) }; var pick by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { val h = LifeGit.history(c, 56); val ws = h.chunked(7).filter { it.size == 7 }.shuffled()
                    if (ws.size >= 2) weeks = ws[0] to ws[1] } }
                weeks?.let { (a, b) ->
                    OMuted("Two of your real weeks. Which one was better? (Then we reveal which ones they were.)")
                    listOf(a, b).forEachIndexed { i, w -> OCard(onClick = { pick = i }) { OTitle("Week ${"AB"[i]}")
                        OText("Tasks ${O.fmt(w.sumOf { O.num(it, "tasks_done") })} · prayers ${O.fmt(w.sumOf { O.num(it, "prayers") })} · gym ${O.fmt(w.sumOf { O.num(it, "gym_sets") })} · spent Rs ${O.fmt(w.sumOf { O.num(it, "spent") })}")
                        if (pick != null) OMuted("${w.first().optString("date")} → ${w.last().optString("date")}") } }
                    pick?.let { p -> val score = { w: List<JSONObject> -> w.sumOf { O.num(it, "tasks_done") * 2 + O.num(it, "prayers") + O.num(it, "gym_sets") * 0.5 - O.num(it, "spent") / 3000 } }
                        val better = if (score(a) >= score(b)) 0 else 1
                        OTitle(if (p == better) "✅ Right! Week ${"AB"[better]} scored higher." else "❌ By the numbers, week ${"AB"[better]} was better.")
                        OBtn2("Play again") { pick = null; weeks = null; scope.launch { val h = LifeGit.history(c, 56); val ws = h.chunked(7).filter { it.size == 7 }.shuffled(); if (ws.size >= 2) weeks = ws[0] to ws[1] } } }
                } ?: OMuted("Needs at least two weeks of history.")
            }
            else -> {
                var text by remember { mutableStateOf("") }; var typed by remember { mutableStateOf("") }; var t0 by remember { mutableLongStateOf(0L) }; var result by remember { mutableStateOf("") }
                OBtn("New race from my notes") { scope.launch { val n = O.container(c).noteRepository.everything().filter { it.body.length > 60 }.randomOrNull()
                    text = (n?.body ?: "Atlas is a living app that runs your whole life from one place.").replace(Regex("\\s+"), " ").take(140); typed = ""; t0 = 0; result = "" } }
                if (text.isNotBlank()) { OCard { OText(text) }
                    OField(typed, "Type it as fast as you can") { if (t0 == 0L) t0 = System.currentTimeMillis(); typed = it
                        if (it.length >= text.length) { val min = (System.currentTimeMillis() - t0) / 60000.0; val acc = text.zip(it).count { (a, b) -> a == b } * 100 / text.length
                            val best = c.getSharedPreferences("omni_arcade", 0).getInt("wpm", 0); val wpm = ((text.length / 5) / min.coerceAtLeast(0.01)).toInt()
                            if (wpm > best) c.getSharedPreferences("omni_arcade", 0).edit().putInt("wpm", wpm).apply()
                            result = "$wpm words a minute · $acc% accurate · best ${maxOf(best, wpm)}" } }
                    if (result.isNotBlank()) OTitle(result) }
            }
        }
    }
}

// ─────────────── Oracle theatre ───────────────
@Composable
fun OracleTheatreScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var play by remember { mutableStateOf<JSONObject?>(null) }; var scene by remember { mutableIntStateOf(0) }
    LaunchedEffect(play, scene) { com.nizam.app.ui.design.guard { val p = play ?: return@guard; val a = O.arr(p.optJSONArray("left")); val b = O.arr(p.optJSONArray("right"))
        if (scene < maxOf(a.size, b.size)) { BeingVoice.speak(c, listOfNotNull(a.getOrNull(scene)?.optString("caption"), b.getOrNull(scene)?.optString("caption")).joinToString(" Meanwhile, in the other future: "), "thinking"); delay(9000); scene++ }
        else if (scene == maxOf(a.size, b.size)) BeingVoice.speak(c, p.optString("verdict"), "calm") } }
    Column {
        OCard { OTitle("Oracle theatre"); OMuted("Ask a big question. ${if (Being.born(c)) Being.name(c) else "Your being"} plays out two futures side by side, scene by scene, and narrates them.")
            OField(q, "e.g. should I take the new job?", 2) { q = it }
            OBtn(if (busy) "Writing the play…" else "Raise the curtain", q.isNotBlank() && !busy) { busy = true; scope.launch {
                play = O.aiJson(c, "Question: $q\nWrite two short possible futures (A: one choice, B: the other) as 4 scenes each, realistic, grounded in what you know of the person. " +
                    "Output only JSON: {\"a\":\"label of choice A\",\"b\":\"label of choice B\",\"left\":[{\"when\":\"3 months later\",\"caption\":\"1-2 sentences\",\"emoji\":\"\",\"mood\":\"happy|calm|sad|thinking|excited\"}],\"right\":[same],\"verdict\":\"one honest line on what matters most in choosing\"}", 1500)
                scene = 0; busy = false } } }
        play?.let { p -> val a = O.arr(p.optJSONArray("left")); val b = O.arr(p.optJSONArray("right"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(p.optString("a") to a, p.optString("b") to b).forEach { (label, scenes) -> Column(Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    scenes.take(scene + 1).forEach { s -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF151A2C)).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(s.optString("emoji"), fontSize = 26.sp)
                            Robot(Body.look(c), when (s.optString("mood")) { "happy", "excited" -> Pose.CELEBRATE; "sad" -> Pose.SAD; "thinking" -> Pose.THINK; else -> Pose.IDLE },
                                when (s.optString("mood")) { "happy", "excited" -> Face.HAPPY; "sad" -> Face.SAD; "thinking" -> Face.THINKING; else -> Face.NORMAL }, Modifier.size(34.dp, 48.dp)) }
                        Text(s.optString("when"), color = Color(0xFF8BE9FD), fontSize = 11.sp); Text(s.optString("caption"), color = Color.White, fontSize = 13.sp) } }
                } }
            }
            if (scene >= maxOf(a.size, b.size)) OCard { OTitle("The oracle says"); OText(p.optString("verdict")); OBtn2("Replay") { scene = 0 } }
        }
    }
}

// ─────────────── Focus tunnel ───────────────
@Composable
fun FocusTunnelScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tasks by remember { mutableStateOf<List<com.nizam.app.data.db.Task>>(emptyList()) }
    var task by remember { mutableStateOf<com.nizam.app.data.db.Task?>(null) }; var mins by remember { mutableStateOf("25") }
    var endAt by remember { mutableLongStateOf(0L) }; var now by remember { mutableLongStateOf(System.currentTimeMillis()) }; var finished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { tasks = O.container(c).taskRepository.all().first().filter { it.completedAt == null && it.deletedAt == null }.take(12) } }
    LaunchedEffect(endAt) { com.nizam.app.ui.design.guard { while (endAt > 0 && !finished) { now = System.currentTimeMillis(); if (now >= endAt) { finished = true
        Being.say(c, "We did it! Look at us.", "excited"); Being.write(c, "focus", "Worked next to my human for ${mins} minutes. Good team."); Being.nudge(c, "mood", 0.06f) }; delay(500) } } }
    DisposableEffect(Unit) { onDispose { if (endAt > 0 && !finished && System.currentTimeMillis() < endAt) { Being.write(c, "focus", "My human left the focus tunnel early. I kept working though."); Being.nudge(c, "mood", -0.04f) } } }
    val running = endAt > 0 && !finished
    if (!running && !finished) Column {
        OCard { OTitle("Focus tunnel"); OMuted("Everything else disappears: one task, a timer, and ${if (Being.born(c)) Being.name(c) else "your being"} working beside you on its own thing. Leave early and it notices.") }
        OChips(tasks.map { it.title.take(40) }, task?.title?.take(40) ?: "") { t -> task = tasks.firstOrNull { it.title.take(40) == t } }
        OChips(listOf("15", "25", "45", "60", "90"), mins) { mins = it }
        OBtn("Enter the tunnel", task != null) { endAt = System.currentTimeMillis() + (mins.toIntOrNull() ?: 25) * 60_000L; Being.say(c, "Okay, heads down. I'll work too.", "calm") }
    } else Box(Modifier.fillMaxWidth().height(560.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF05060B))) {
        val t = rememberInfiniteTransition(label = "tunnel").animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "z")
        Canvas(Modifier.fillMaxSize()) { for (i in 0 until 9) { val k = ((i + t.value) / 9f); val s = k * k
            drawRoundRect(Color(0xFF8BE9FD).copy(alpha = (1 - k) * 0.35f), Offset(size.width / 2 * (1 - s), size.height / 2 * (1 - s)), Size(size.width * s, size.height * s), CornerRadius(40f * s), style = Stroke(2f)) } }
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(task?.title.orEmpty(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            val left = ((endAt - now) / 1000).coerceAtLeast(0)
            Text(if (finished) "Done ✦" else "${left / 60}:${"%02d".format(left % 60)}", color = Color(0xFF8BE9FD), fontSize = 64.sp, fontWeight = FontWeight.Light)
            if (finished) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OBtn("Mark task done") { task?.let { tk -> scope.launch { O.container(c).taskRepository.toggleComplete(tk) } }; endAt = 0; finished = false }
                OBtn2("Back") { endAt = 0; finished = false } }
            else OBtn2("Leave early") { endAt = 0; Being.say(c, "Oh. Okay. Leaving already?", "sad") }
        }
        if (Being.born(c)) Robot(Body.look(c), if (finished) Pose.CELEBRATE else Pose.TYPE, if (finished) Face.HAPPY else Face.FOCUSED, Modifier.align(Alignment.BottomEnd).padding(16.dp).size(64.dp, 90.dp))
    }
}

// ─────────────── Story run ───────────────
@Composable
fun StoryRunScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val prefs = c.getSharedPreferences("omni_storyrun", 0)
    var story by remember { mutableStateOf("Zombies") }; var running by remember { mutableStateOf(false) }
    var steps by remember { mutableIntStateOf(0) }; var cadence by remember { mutableIntStateOf(0) }; var chase by remember { mutableIntStateOf(60) }
    var log by remember { mutableStateOf(listOf<String>()) }
    val stepTimes = remember { ArrayDeque<Long>() }
    DisposableEffect(running) {
        val sm = c.getSystemService(SensorManager::class.java)
        val l = object : SensorEventListener { override fun onSensorChanged(e: SensorEvent) { steps++; stepTimes.addLast(System.currentTimeMillis()); while (stepTimes.size > 0 && System.currentTimeMillis() - stepTimes.first() > 60_000) stepTimes.removeFirst() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {} }
        if (running) sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_FASTEST) }
        onDispose { runCatching { sm?.unregisterListener(l) } }
    }
    LaunchedEffect(running) { com.nizam.app.ui.design.guard { if (!running) return@guard
        var seg = 0
        while (running) {
            cadence = stepTimes.size
            chase = (chase + when { cadence > 120 -> -12; cadence > 95 -> -5; cadence > 60 -> 6; else -> 14 }).coerceIn(0, 100)
            val line = O.ai(c, "You narrate a $story audio adventure for someone walking or running in real life (episode ${prefs.getInt("episode", 1)}, segment ${seg + 1}). " +
                "Their pace right now: $cadence steps/min. Danger/chase level 0-100: $chase (high = it's catching up). Previous: ${log.takeLast(2).joinToString(" ")}\n" +
                "Write the next 2-3 sentences, present tense, second person, reacting to their pace (urge them faster if danger is high, reward them if they're fast). No markdown.", "Vivid, short, spoken aloud.", 160)
            log = log + line; BeingVoice.speak(c, line, if (chase > 70) "excited" else "calm"); seg++
            if (chase <= 0) { BeingVoice.speak(c, "You escaped. Episode complete.", "excited"); prefs.edit().putInt("episode", prefs.getInt("episode", 1) + 1).apply(); running = false }
            delay(60_000)
        } } }
    val perm = rememberPermission(if (android.os.Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else Manifest.permission.VIBRATE) { steps = 0; chase = 60; log = emptyList(); running = true }
    Column {
        OCard { OTitle("Story run · episode ${prefs.getInt("episode", 1)}"); OMuted("An audio adventure while you walk or run. Your real pace changes the story: speed up to escape, slow down and it gets closer. Put your earphones in.")
            OChips(listOf("Zombies", "Space station escape", "Mughal palace heist", "Haunted Lahore"), story) { story = it }
            if (!running) OBtn("▶ Start") { perm() } else OBtn2("■ Stop") { running = false; com.nizam.app.feature.being.BeingVoice.stop() } }
        if (running || log.isNotEmpty()) OCard {
            OText("Steps $steps · pace $cadence/min"); OMuted("Danger"); OBar(chase / 100f, if (chase > 70) Color(0xFFFF6B6B) else Color(0xFFFFC37A))
            log.reversed().forEach { OText(it) } }
    }
}

// ─────────────── Life quests & random adventure ───────────────
object Quests { fun store(c: Context) = JsonStore(c, "omni_quests"); fun finds(c: Context) = JsonStore(c, "omni_adventures") }

private fun dist(a: Double, b: Double, c2: Double, d: Double): Double { val r = 6371000.0; val dl = Math.toRadians(c2 - a); val dn = Math.toRadians(d - b)
    val h = Math.sin(dl / 2).let { it * it } + Math.cos(Math.toRadians(a)) * Math.cos(Math.toRadians(c2)) * Math.sin(dn / 2).let { it * it }; return 2 * r * Math.asin(Math.sqrt(h)) }

@Composable
fun LifeQuestsScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var city by remember { mutableStateOf(c.getSharedPreferences("omni_quests", 0).getString("city", "Lahore")!!) }
    var theme by remember { mutableStateOf("History") }; var busy by remember { mutableStateOf(false) }; var v by remember { mutableIntStateOf(0) }
    val quests = remember(v) { Quests.store(c).all().sortedByDescending { it.optLong("t") } }
    var checking by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val locate = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION) {
        val (qid, i) = checking ?: return@rememberPermission
        val q = Quests.store(c).all().firstOrNull { it.optString("id") == qid } ?: return@rememberPermission
        val cp = O.arr(q.optJSONArray("checkpoints")).getOrNull(i) ?: return@rememberPermission
        val loc = O.location(c)
        val near = loc != null && cp.has("lat") && dist(loc.latitude, loc.longitude, cp.optDouble("lat"), cp.optDouble("lng")) < 500
        Quests.store(c).update(qid) { x -> x.optJSONArray("checkpoints")?.optJSONObject(i)?.put("done", true)?.put("verified", near) }
        com.nizam.app.ui.components.Feedback.show(if (near) "📍 Verified! Checkpoint done." else "Checkpoint done (couldn't confirm the location, trusting you).")
        if (Being.born(c)) Being.write(c, "quest", "We reached ${cp.optString("name")} on our quest!")
        checking = null; v++ }
    Column {
        OCard { OTitle("Life quests"); OMuted("Real-world quests in your city with checkpoints. Your location is only read when you check in.")
            OField(city, "City") { city = it; c.getSharedPreferences("omni_quests", 0).edit().putString("city", it).apply() }
            OChips(listOf("History", "Mosques", "Food", "Nature", "Books & art", "Hidden gems"), theme) { theme = it }
            OBtn(if (busy) "Designing…" else "New quest", !busy) { busy = true; scope.launch {
                val o = O.aiJson(c, "Design a real-world quest in $city, theme: $theme. 3-5 real, existing places that can be visited in a day or a month. " +
                    "Output only JSON: {\"title\":\"\",\"story\":\"one line\",\"badge\":\"emoji\",\"checkpoints\":[{\"name\":\"real place\",\"task\":\"what to do there\",\"lat\":0.0,\"lng\":0.0}]}", 900, web = true)
                if (o != null) Quests.store(c).add(o.put("t", System.currentTimeMillis()).put("theme", theme)); busy = false; v++ } } }
        quests.forEach { q -> val cps = O.arr(q.optJSONArray("checkpoints")); val done = cps.count { it.optBoolean("done") }
            OCard { OTitle("${q.optString("badge")} ${q.optString("title")}"); OMuted(q.optString("story")); OBar(done.toFloat() / cps.size.coerceAtLeast(1))
                if (done == cps.size && cps.isNotEmpty()) OText("🏅 Quest complete! Badge earned.")
                cps.forEachIndexed { i, cp -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { OText("${if (cp.optBoolean("done")) "✅" else "○"} ${cp.optString("name")}"); OMuted(cp.optString("task")) }
                    if (!cp.optBoolean("done")) Column { OBtn2("Map") { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${cp.optDouble("lat")},${cp.optDouble("lng")}?q=${Uri.encode(cp.optString("name") + " " + city)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        OBtn2("I'm here") { checking = q.optString("id") to i; locate() } } } } } }
    }
}

@Composable
fun RandomAdventureScreen() {
    val c = LocalContext.current
    var radius by remember { mutableStateOf("1 km") }; var spot by remember { mutableStateOf<JSONObject?>(null) }
    var note by remember { mutableStateOf("") }; var v by remember { mutableIntStateOf(0) }
    val themes = listOf("Find something blue", "Find the oldest thing around", "Say salaam to someone new", "Find something that makes you smile", "Find a sound you've never noticed",
        "Find something handmade", "Find the best chai nearby", "Find a tree older than you", "Find a word in a language you don't speak", "Find something that's been forgotten")
    val go = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION) {
        val loc = O.location(c) ?: run { com.nizam.app.ui.components.Feedback.show("Couldn't get your location. Open Maps once, then try again."); return@rememberPermission }
        val maxM = when (radius) { "500 m" -> 500.0; "3 km" -> 3000.0; else -> 1000.0 }
        val d = Math.sqrt(Random.nextDouble()) * (maxM - 150) + 150; val b = Random.nextDouble() * 2 * Math.PI
        val lat = loc.latitude + d * Math.cos(b) / 111_320.0; val lng = loc.longitude + d * Math.sin(b) / (111_320.0 * Math.cos(Math.toRadians(loc.latitude)))
        spot = JSONObject().put("lat", lat).put("lng", lng).put("theme", themes.random()).put("m", d.toInt()).put("t", System.currentTimeMillis()) }
    Column {
        OCard { OTitle("Random adventure"); OMuted("Atlas picks a random point near you and a mission. Go, look, log what you found. Location is read only when you tap.")
            OChips(listOf("500 m", "1 km", "3 km"), radius) { radius = it }; OBtn("🎲 Roll an adventure") { go() } }
        spot?.let { s -> OCard { OTitle("🎯 ${s.optString("theme")}"); OText("${s.optInt("m")} m away")
            OBtn("Open in maps") { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.optDouble("lat")},${s.optDouble("lng")}?q=${s.optDouble("lat")},${s.optDouble("lng")}(Adventure)")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            OField(note, "What did you find?", 2) { note = it }
            OBtn2("Log it", note.isNotBlank()) { Quests.finds(c).add(JSONObject(s.toString()).put("found", note)); note = ""; spot = null; v++ } } }
        remember(v) { Quests.finds(c).all().sortedByDescending { it.optLong("t") } }.forEach { f -> OCard { OText("🎯 ${f.optString("theme")}"); OMuted(f.optString("found")) } }
    }
}

// ─────────────── Step-out timer ───────────────
@Composable
fun StepOutScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var match by remember { mutableStateOf("Pakistan cricket") }; var out by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf(false) }; var leftAt by remember { mutableLongStateOf(0L) }
    fun check() { busy = true; scope.launch { out = O.ai(c, "Live match: $match. Check the current live score and situation right now. Then say: is the next few minutes a SAFE moment to step away for chai or the toilet (e.g. drinks break, between innings, a slow phase, a tail-ender batting), or STAY (death overs, a key batter on 90s, a close finish)? " +
        "Format: first line 'SAFE: about N minutes' or 'STAY: reason'. Then the score in one line.", "Be current. Use the web.", 250, web = true); busy = false } }
    LaunchedEffect(watching) { com.nizam.app.ui.design.guard { while (watching) { check(); delay(180_000) } } }
    Column {
        OCard { OTitle("Step-out timer"); OMuted("Watching a match? Atlas watches the live state and tells you the safe moment for a chai or toilet break, then what you missed.")
            OField(match, "Match (e.g. Pakistan vs India T20)") { match = it }
            ORow { OBtn(if (busy) "Checking…" else "Can I step out?", !busy) { check() }; OBtn2(if (watching) "Stop watching" else "Watch every 3 min") { watching = !watching } } }
        if (out.isNotBlank()) OCard { OMd(out)
            if (out.startsWith("SAFE")) OBtn2(if (leftAt == 0L) "I'm stepping out" else "I'm back: what did I miss?") {
                if (leftAt == 0L) leftAt = System.currentTimeMillis()
                else { val m = (System.currentTimeMillis() - leftAt) / 60000; leftAt = 0; busy = true; scope.launch {
                    out = O.ai(c, "Live match: $match. The viewer stepped away for $m minutes. In 10 seconds of reading: what did they miss? Score now, key moments.", "Current, brief.", 200, web = true); busy = false } } } }
    }
}
