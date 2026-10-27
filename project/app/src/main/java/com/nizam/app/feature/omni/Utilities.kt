package com.nizam.app.feature.omni

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

// ─────────────── Pre-mortem ───────────────
@Composable
fun PreMortemScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var plan by remember { mutableStateOf("") }; var res by remember { mutableStateOf<JSONObject?>(null) }; var busy by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Int>() }
    Column {
        OCard { OTitle("Pre-mortem"); OMuted("Before you start: imagine it's six months later and it failed. Atlas writes the obituary, then turns every cause into a guard.")
            OField(plan, "The plan or goal", 3) { plan = it }
            OBtn(if (busy) "Imagining the failure…" else "Run the pre-mortem", plan.isNotBlank() && !busy) { busy = true; picked.clear(); scope.launch {
                res = O.aiJson(c, "Pre-mortem. Plan: \"$plan\". It is six months later and this plan FAILED. Using what you know about this person, explain why, specifically. " +
                    "Output only JSON: {\"obituary\":\"3 sentences, vivid and honest\",\"causes\":[{\"cause\":\"\",\"likelihood\":\"high|medium|low\",\"guard\":\"a small concrete task that prevents it\"}],\"early_warning\":\"the first sign it's going wrong\"}", 1200)
                busy = false } } }
        res?.let { r -> OCard { OTitle("🪦 The obituary"); OText(r.optString("obituary")); OMuted("Early warning sign: ${r.optString("early_warning")}") }
            O.arr(r.optJSONArray("causes")).forEachIndexed { i, cz -> OCard(onClick = { if (i in picked) picked.remove(i) else picked.add(i) }) {
                OText("${if (i in picked) "☑" else "☐"} ${cz.optString("cause")}  (${cz.optString("likelihood")})"); OMuted("Guard: ${cz.optString("guard")}") } }
            OBtn("Add ${picked.size} guards to my tasks", picked.isNotEmpty()) { scope.launch { picked.forEach { i -> O.arr(r.optJSONArray("causes")).getOrNull(i)?.let { cz ->
                com.nizam.app.feature.agent.Actions.run(O.container(c), "add_task", JSONObject().put("title", "Guard: " + cz.optString("guard").take(90)).put("priority", 1)) } }
                com.nizam.app.ui.components.Feedback.show("Guards added"); picked.clear() } } }
    }
}

// ─────────────── Pairwise ranker ───────────────
object Ranker {
    fun lists(c: Context) = JsonStore(c, "omni_rank")
    fun elo(a: Double, b: Double, aWon: Boolean): Pair<Double, Double> { val ea = 1 / (1 + Math.pow(10.0, (b - a) / 400)); val k = 32
        return (a + k * ((if (aWon) 1 else 0) - ea)) to (b + k * ((if (aWon) 0 else 1) - (1 - ea))) }
}

@Composable
fun RankerScreen() {
    val c = LocalContext.current
    var v by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }; var item by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val lists = remember(v) { Ranker.lists(c).all() }
    Column {
        OCard { OTitle("Pairwise ranker"); OMuted("Never give star ratings again. Pick between two at a time and Atlas builds your true ranking: restaurants, movies, books, even job offers.")
            OField(name, "New list (e.g. Biryani in Lahore)") { name = it }
            OBtn("Create", name.isNotBlank()) { open = Ranker.lists(c).add(JSONObject().put("name", name).put("items", JSONObject())).optString("id"); name = ""; v++ } }
        lists.forEach { l -> OCard(onClick = { open = l.optString("id") }) { OText("🏆 ${l.optString("name")}"); OMuted("${l.optJSONObject("items")?.length() ?: 0} items") } }
        val l = lists.firstOrNull { it.optString("id") == open }
        if (l != null) {
            val items = l.optJSONObject("items") ?: JSONObject()
            val names = items.keys().asSequence().toList()
            OCard { OTitle(l.optString("name"))
                OField(item, "Add an item") { item = it }
                OBtn2("Add", item.isNotBlank()) { Ranker.lists(c).update(l.optString("id")) { it.optJSONObject("items")?.put(item.trim(), JSONObject().put("r", 1000.0).put("n", 0)) }; item = ""; v++ } }
            if (names.size >= 2) {
                val pair = remember(v, names.size) { val least = names.sortedBy { items.optJSONObject(it)?.optInt("n") ?: 0 }.take(4).shuffled(); least[0] to (names - least[0]).random() }
                Text("Which is better?", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(pair.first, pair.second).forEach { pick -> Box(Modifier.weight(1f).height(110.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable {
                        Ranker.lists(c).update(l.optString("id")) { x -> val it2 = x.optJSONObject("items")!!
                            val a = it2.optJSONObject(pair.first)!!; val b = it2.optJSONObject(pair.second)!!
                            val (na, nb) = Ranker.elo(a.optDouble("r", 1000.0), b.optDouble("r", 1000.0), pick == pair.first)
                            a.put("r", na).put("n", a.optInt("n") + 1); b.put("r", nb).put("n", b.optInt("n") + 1) }; v++ }, contentAlignment = Alignment.Center) {
                        Text(pick, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp)) } }
                }
                OCard { OTitle("Your ranking")
                    names.sortedByDescending { items.optJSONObject(it)?.optDouble("r") ?: 0.0 }.forEachIndexed { i, n -> OText("${i + 1}. $n  ·  ${items.optJSONObject(n)?.optDouble("r")?.roundToInt()}") } }
            } else OMuted("Add at least two items.")
        }
    }
}

// ─────────────── Hold for me ───────────────
private val HELPLINES = listOf("LESCO" to "118", "PTCL" to "1218", "SNGPL" to "1199", "NADRA" to "1777", "HBL" to "021111111425", "Meezan Bank" to "021111331331",
    "Jazz" to "111", "Zong" to "310", "Telenor" to "345", "Ufone" to "333", "Daraz" to "021111132729", "Careem" to "080022736")

@Composable
fun HoldForMeScreen() {
    val c = LocalContext.current
    var number by remember { mutableStateOf("") }; var menu by remember { mutableStateOf("") }
    var holding by remember { mutableStateOf(0L) }; var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(holding) { com.nizam.app.ui.design.guard { while (holding > 0) { now = System.currentTimeMillis(); delay(1000) } } }
    val saved = remember { JsonStore(c, "omni_hold") }
    var v by remember { mutableIntStateOf(0) }
    val call = rememberPermission(Manifest.permission.CALL_PHONE) {
        // commas are 2-second pauses: the phone itself presses the menu digits for you
        val digits = menu.split(Regex("[ ,>-]+")).filter { it.isNotBlank() }.joinToString(",,") { it }
        val uri = "tel:" + Uri.encode(number + if (digits.isBlank()) "" else ",,,$digits")
        runCatching { c.startActivity(Intent(Intent.ACTION_CALL, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        holding = System.currentTimeMillis()
        if (saved.all().none { it.optString("number") == number && it.optString("menu") == menu }) { saved.add(JSONObject().put("number", number).put("menu", menu)); v++ } }
    Column {
        OCard { OTitle("Hold for me"); OMuted("Atlas dials the helpline and presses the menu digits for you (a 2-second pause between each), so you just wait. Android doesn't let apps hear calls, so it can't tell when a person answers; it keeps the timer.")
            OChips(HELPLINES.map { it.first }, HELPLINES.firstOrNull { it.second == number }?.first ?: "") { n -> number = HELPLINES.first { it.first == n }.second }
            OField(number, "Number") { number = it }
            OField(menu, "Menu presses, e.g. 1 3 0 (Urdu=1, complaint=3, agent=0)") { menu = it }
            OBtn("📞 Dial and press for me", number.isNotBlank()) { call() } }
        if (holding > 0) OCard { val s = (now - holding) / 1000; OTitle("On hold ${s / 60}:${"%02d".format(s % 60)}"); OBtn2("Got through") { holding = 0 } }
        remember(v) { saved.all() }.forEach { s -> OCard(onClick = { number = s.optString("number"); menu = s.optString("menu") }) { OText("${HELPLINES.firstOrNull { it.second == s.optString("number") }?.first ?: s.optString("number")} · ${s.optString("menu")}") } }
    }
}

// ─────────────── Subscription graveyard ───────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SubGraveyardScreen() {
    val c = LocalContext.current
    var v by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }; var since by remember { mutableStateOf(LocalDate.now().minusMonths(3).toString()) }
    val graves = remember(v) {
        val manual = JsonStore(c, "omni_graves").all()
        val subs = com.nizam.app.feature.next.Subs.store(c).all().filter { it.optBoolean("cancelled") }
        subs.forEach { s -> if (manual.none { it.optString("sub") == s.optString("id") }) JsonStore(c, "omni_graves").add(JSONObject().put("sub", s.optString("id")).put("name", s.optString("name"))
            .put("monthly", s.optDouble("amount") * 30.0 / s.optInt("days", 30).coerceAtLeast(1)).put("since", LocalDate.now().toString())) }
        JsonStore(c, "omni_graves").all() }
    val total = graves.sumOf { g -> val m = java.time.temporal.ChronoUnit.DAYS.between(runCatching { LocalDate.parse(g.optString("since")) }.getOrDefault(LocalDate.now()), LocalDate.now()) / 30.0; g.optDouble("monthly") * m }
    Column {
        OCard { OTitle("🪦 Subscription graveyard"); OText("Saved so far: Rs ${O.fmt(total)}"); OMuted("Every subscription you've cancelled, and what it would have cost you by now. Cancelled ones from Subscriptions are buried here automatically.") }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            graves.forEach { g -> val m = java.time.temporal.ChronoUnit.DAYS.between(runCatching { LocalDate.parse(g.optString("since")) }.getOrDefault(LocalDate.now()), LocalDate.now()) / 30.0
                Column(Modifier.width(150.dp).clip(RoundedCornerShape(topStart = 70.dp, topEnd = 70.dp, bottomStart = 6.dp, bottomEnd = 6.dp)).background(Color(0xFF8A8F98)).padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("R.I.P.", color = Color(0xFF2B2E33), fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(g.optString("name"), color = Color(0xFF1B1D21), fontWeight = FontWeight.SemiBold)
                    Text("since ${g.optString("since")}", color = Color(0xFF2B2E33), fontSize = 11.sp); Text("saved Rs ${O.fmt(g.optDouble("monthly") * m)}", color = Color(0xFF14361F), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("🥀", fontSize = 18.sp) } }
        }
        OCard { OTitle("Bury one"); OField(name, "Name (e.g. Netflix)") { name = it }; OField(amt, "Monthly cost (Rs)") { amt = it.filter { ch -> ch.isDigit() || ch == '.' } }
            OField(since, "Cancelled on (YYYY-MM-DD)") { since = it }
            OBtn2("⚰ Bury", name.isNotBlank() && amt.isNotBlank()) { JsonStore(c, "omni_graves").add(JSONObject().put("name", name).put("monthly", amt.toDoubleOrNull() ?: 0.0).put("since", since)); name = ""; amt = ""; v++ } }
    }
}

// ─────────────── Life bug tracker ───────────────
object LifeBugs {
    fun store(c: Context) = JsonStore(c, "omni_bugs")
    fun tick(c: Context) { if (LocalTime.now().hour != 19) return
        val p = c.getSharedPreferences("omni_bugs", 0); if (p.getString("day", "") == LocalDate.now().toString()) return; p.edit().putString("day", LocalDate.now().toString()).apply()
        val open = store(c).all().filter { it.optString("state") == "open" }
        open.filter { it.optString("priority") == "P1" || System.currentTimeMillis() - it.optLong("t") > 3 * 86_400_000L }.maxByOrNull { if (it.optString("priority") == "P1") 2 else 1 }?.let { b ->
            O.notify(c, 5900, "🐞 ${b.optString("priority")} still open: ${b.optString("title")}", "Next step: ${b.optString("next")}", "nx/lifebugs") } }
}

@Composable
fun LifeBugsScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var text by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var v by remember { mutableIntStateOf(0) }
    val bugs = remember(v) { LifeBugs.store(c).all().sortedWith(compareBy<JSONObject> { if (it.optString("state") == "open") 0 else 1 }.thenBy { it.optString("priority") }) }
    Column {
        OCard { OTitle("🐞 Life bug report"); OMuted("Something broken or annoying? Say it once. Atlas files it with a priority and a fix plan, and chases it until it's closed.")
            OField(text, "What's wrong (e.g. kitchen tap leaking, bank charged me twice)", 2) { text = it }
            OBtn(if (busy) "Filing…" else "File it", text.isNotBlank() && !busy) { busy = true; scope.launch {
                val o = O.aiJson(c, "File this life issue like a bug ticket: \"$text\". Output only JSON {\"title\":\"short\",\"priority\":\"P1|P2|P3\",\"plan\":[\"3-5 concrete fix steps, Pakistan context\"],\"next\":\"the very next step\",\"who\":\"who can fix it (plumber, bank helpline…)\"}", 500)
                    ?: JSONObject().put("title", text.take(60)).put("priority", "P2").put("next", "Decide who can fix it")
                LifeBugs.store(c).add(o.put("t", System.currentTimeMillis()).put("state", "open").put("raw", text)); text = ""; busy = false; v++ } } }
        bugs.forEach { b -> OCard {
            OText("${if (b.optString("state") == "open") "🐞" else "✅"} [${b.optString("priority")}] ${b.optString("title")}")
            OMuted("Who: ${b.optString("who")} · next: ${b.optString("next")}")
            O.strs(b.optJSONArray("plan")).forEachIndexed { i, s -> OMuted("${i + 1}. $s") }
            if (b.optString("state") == "open") ORow { OBtn2("Closed ✓") { LifeBugs.store(c).update(b.optString("id")) { it.put("state", "closed").put("closed", System.currentTimeMillis()) }; v++ }
                OBtn2("Remind me tomorrow") { scope.launch { com.nizam.app.feature.agent.Actions.run(O.container(c), "add_task", JSONObject().put("title", "Fix: ${b.optString("title")} · ${b.optString("next")}").put("priority", if (b.optString("priority") == "P1") 2 else 1)) }
                    com.nizam.app.ui.components.Feedback.show("Added to tasks") } }
        } }
    }
}

// ─────────────── Crowd of yous ───────────────
@Composable
fun CrowdOfYouScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }; var res by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var verdict by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Column {
        OCard { OTitle("Ask the crowd of yous"); OMuted("Five versions of you, built from your own notes and history, answer the same question.")
            OField(q, "Your question", 2) { q = it }
            OBtn(if (busy) "Gathering…" else "Ask them", q.isNotBlank() && !busy) { busy = true; scope.launch {
                val o = O.aiJson(c, "Question from the user: \"$q\". Using everything you know about this person (their notes, habits, values, history), answer as five different versions of THEM, in first person, each 2-3 sentences: " +
                    "the careful one, the ambitious one, the religious one, the fun one, and themselves one year ago. Then a one-line verdict on where most of them agree. " +
                    "Output only JSON {\"yous\":[{\"who\":\"\",\"emoji\":\"\",\"says\":\"\"}],\"verdict\":\"\"}", 1200)
                res = O.arr(o?.optJSONArray("yous")); verdict = o?.optString("verdict").orEmpty(); busy = false } } }
        res.forEach { y -> OCard { OText("${y.optString("emoji")} ${y.optString("who")}"); OText(y.optString("says")) } }
        if (verdict.isNotBlank()) OCard { OTitle("Where they agree"); OText(verdict) }
    }
}

// ─────────────── Fajr mission alarm ───────────────
object Mission {
    private fun p(c: Context) = c.getSharedPreferences("omni_mission", 0)
    fun time(c: Context) = p(c).getString("time", "05:00")!!
    fun kind(c: Context) = p(c).getString("kind", "Walk 30 steps")!!
    fun code(c: Context) = p(c).getString("code", "")!!
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun save(c: Context, time: String, kind: String, on: Boolean) { p(c).edit().putString("time", time).putString("kind", kind).putBoolean("on", on).apply(); arm(c) }
    fun setCode(c: Context, v: String) = p(c).edit().putString("code", v).apply()
    private fun pi(c: Context) = PendingIntent.getActivity(c, 5910, Intent(c, MissionAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun arm(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(c)); if (!on(c)) return
        val t = runCatching { LocalTime.parse(time(c)) }.getOrDefault(LocalTime.of(5, 0))
        var at = LocalDateTime.of(LocalDate.now(), t); if (at.isBefore(LocalDateTime.now())) at = at.plusDays(1)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(ms, pi(c)), pi(c)) }
    }
}

class MissionAlarmActivity : ComponentActivity() {
    private var ring: android.media.Ringtone? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        ring = runCatching { android.media.RingtoneManager.getRingtone(this, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)).apply {
            if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true; play() } }.getOrNull()
        Mission.arm(this) // tomorrow's
        setContent { com.nizam.app.ui.theme.NizamTheme(darkTheme = true) { MissionUi(Mission.kind(this)) { ring?.stop(); finish() } } }
    }
    override fun onDestroy() { ring?.stop(); super.onDestroy() }
    @Deprecated("back is disabled until the mission is done") override fun onBackPressed() {}
}

private val DUA = "Alhamdulillahil ladhi ahyana ba'da ma amatana wa ilayhin nushur"

@Composable
private fun MissionUi(kind: String, done: () -> Unit) {
    val c = LocalContext.current
    var steps by remember { mutableIntStateOf(0) }; var msg by remember { mutableStateOf("") }
    if (kind.startsWith("Walk")) DisposableEffect(Unit) {
        val sm = c.getSystemService(SensorManager::class.java)
        val l = object : SensorEventListener { override fun onSensorChanged(e: SensorEvent) { steps++; if (steps >= 30) done() }; override fun onAccuracyChanged(s: Sensor?, a: Int) {} }
        val s = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var last = 0L
        val acc = object : SensorEventListener { override fun onSensorChanged(e: SensorEvent) { val g = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
            if (g > 12.5 && System.currentTimeMillis() - last > 350) { last = System.currentTimeMillis(); steps++; if (steps >= 30) done() } }; override fun onAccuracyChanged(s: Sensor?, a: Int) {} }
        if (s?.type == Sensor.TYPE_STEP_DETECTOR) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_FASTEST) else if (s != null) sm.registerListener(acc, s, SensorManager.SENSOR_DELAY_GAME)
        onDispose { runCatching { sm?.unregisterListener(l); sm?.unregisterListener(acc) } }
    }
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> bmp ?: return@rememberLauncherForActivityResult
        com.google.mlkit.vision.barcode.BarcodeScanning.getClient().process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { codes ->
            val v = codes.firstOrNull()?.rawValue
            if (v != null && (Mission.code(c).isBlank() || v == Mission.code(c))) done() else msg = if (v == null) "No barcode seen. Get closer." else "That's not your registered barcode." } }
    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty().lowercase()
        val want = DUA.lowercase().split(" ").filter { it.length > 3 }
        val hit = want.count { w -> heard.contains(w.take(5)) }
        if (hit >= want.size / 2) done() else msg = "Say the whole dua clearly, once more." }
    Column(Modifier.fillMaxSize().background(Color(0xFF060814)).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🕌", fontSize = 64.sp); Text("Time for Fajr", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("As-salatu khayrun min an-nawm", color = Color(0xFF8BE9FD), fontSize = 16.sp, modifier = Modifier.padding(8.dp))
        when {
            kind.startsWith("Walk") -> { Text("Walk to stop the alarm: $steps / 30", color = Color.White, fontSize = 20.sp); OBar(steps / 30f) }
            kind.startsWith("Scan") -> OBtn("📷 Scan your barcode") { scan.launch(null) }
            else -> { Text(DUA, color = Color.White, fontSize = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                OBtn("🎙 Read it aloud") { runCatching { speak.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) } } }
        }
        if (msg.isNotBlank()) Text(msg, color = Color(0xFFFFC37A), modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
fun MissionAlarmScreen() {
    val c = LocalContext.current
    var time by remember { mutableStateOf(Mission.time(c)) }; var kind by remember { mutableStateOf(Mission.kind(c)) }; var on by remember { mutableStateOf(Mission.on(c)) }
    var code by remember { mutableStateOf(Mission.code(c)) }
    val reg = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> bmp ?: return@rememberLauncherForActivityResult
        com.google.mlkit.vision.barcode.BarcodeScanning.getClient().process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { codes ->
            codes.firstOrNull()?.rawValue?.let { code = it; Mission.setCode(c, it); com.nizam.app.ui.components.Feedback.show("Barcode registered") } ?: com.nizam.app.ui.components.Feedback.show("No barcode found, try again") } }
    val perm = rememberPermission(if (android.os.Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else Manifest.permission.VIBRATE) { Mission.save(c, time, kind, on) }
    Column {
        OCard { OTitle("Fajr mission alarm"); OMuted("The alarm doesn't stop until you complete a mission. No snoozing through Fajr.")
            Row(verticalAlignment = Alignment.CenterVertically) { OText("Alarm on  "); Switch(on, { on = it; Mission.save(c, time, kind, it) }) }
            OField(time, "Time (HH:mm, set it to your Fajr time)") { time = it }
            OChips(listOf("Walk 30 steps", "Scan a barcode", "Read the waking dua"), kind) { kind = it }
            if (kind.startsWith("Scan")) { OMuted(if (code.isBlank()) "Register a barcode far from your bed, like the soap by the wudu tap." else "Registered: $code"); OBtn2("Register barcode") { reg.launch(null) } }
            OBtn("Save", runCatching { LocalTime.parse(time) }.isSuccess) { perm(); com.nizam.app.ui.components.Feedback.show(if (on) "Armed for $time" else "Saved (off)") }
            OBtn2("Test it now") { c.startActivity(Intent(c, MissionAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

// ─────────────── Notification jar ───────────────
private data class Drop(val key: String, val title: String, val app: String, val weight: Float, var x: Float, var y: Float, var vy: Float)

private fun weigh(pkg: String, title: String, text: String, cat: String?): Float {
    val p = pkg.lowercase(); val t = (title + " " + text).lowercase()
    var w = 0.5f
    if (cat == android.app.Notification.CATEGORY_CALL || cat == android.app.Notification.CATEGORY_MESSAGE) w += 0.35f
    if (p.contains("whatsapp") || p.contains("messag") || p.contains("dialer") || p.contains("gmail")) w += 0.25f
    if (Regex("otp|code|bank|debit|credit|urgent|missed call|deliver|reply").containsMatchIn(t)) w += 0.25f
    if (Regex("sale|off|deal|offer|discount|promo|win|reward|%").containsMatchIn(t) || cat == android.app.Notification.CATEGORY_PROMO) w -= 0.4f
    if (p.contains("game") || p.contains("shop") || p.contains("daraz") || p.contains("foodpanda") || p.contains("tiktok") || p.contains("likee")) w -= 0.2f
    return w.coerceIn(0.05f, 1f)
}

@Composable
fun NotificationJarScreen() {
    val c = LocalContext.current
    val drops = remember { mutableStateListOf<Drop>() }
    var tick by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Drop?>(null) }
    val ver by com.nizam.app.feature.phone.AtlasNotificationListener.version.collectAsState()
    LaunchedEffect(ver) { com.nizam.app.ui.design.guard {
        val live = com.nizam.app.feature.phone.AtlasNotificationListener.active()
        drops.removeAll { d -> live.none { it.key == d.key } }
        live.forEach { s -> if (drops.none { it.key == s.key }) { val ex = s.notification.extras
            val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty(); val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            val app = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(s.packageName, 0)).toString() }.getOrElse { s.packageName }
            drops += Drop(s.key, title, app, weigh(s.packageName, title, text, s.notification.category), Random.nextFloat(), -0.1f - Random.nextFloat() * 0.3f, 0f) } } } }
    // physics: heavy things sink, light things float
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { delay(16)
        drops.forEach { d -> val floor = 1f - d.weight * 0.82f; d.vy += (floor - d.y) * 0.02f; d.vy *= 0.9f; d.y += d.vy }; tick++ } } }
    // shake to clear the light stuff
    DisposableEffect(Unit) { val sm = c.getSystemService(SensorManager::class.java); var last = 0L
        val l = object : SensorEventListener { override fun onSensorChanged(e: SensorEvent) { val g = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
            if (g > 24 && System.currentTimeMillis() - last > 1500) { last = System.currentTimeMillis()
                drops.filter { it.weight < 0.35f }.forEach { com.nizam.app.feature.phone.AtlasNotificationListener.dismiss(it.key) }; drops.removeAll { it.weight < 0.35f }
                com.nizam.app.ui.components.Feedback.show("Shook out the light stuff") } }; override fun onAccuracyChanged(s: Sensor?, a: Int) {} }
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }; onDispose { runCatching { sm?.unregisterListener(l) } } }
    Column {
        if (!com.nizam.app.feature.phone.AtlasNotificationListener.connected) OCard { OText("Turn on notification access for Atlas to fill the jar.")
            OBtn("Open settings") { runCatching { c.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
        OMuted("Heavy (people, calls, money, deliveries) sinks. Junk floats and fades. Shake to clear the light stuff. Tap a drop to open it.")
        BoxWithConstraints(Modifier.fillMaxWidth().height(460.dp).clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp, bottomStart = 60.dp, bottomEnd = 60.dp))
            .background(Color(0x221A9FFF)).pointerInput(Unit) { detectTapGestures { pos ->
                val w = size.width; val h = size.height
                picked = drops.minByOrNull { d -> val dx = d.x * (w - 140) + 70 - pos.x; val dy = d.y.coerceIn(0f, 1f) * (h - 60) + 30 - pos.y; dx * dx + dy * dy } } }) {
            val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat(); val dens = LocalDensity.current
            Canvas(Modifier.fillMaxSize()) { drawRoundRect(Color(0x553FA9F5), Offset(4f, 4f), Size(size.width - 8, size.height - 8), CornerRadius(80f), style = Stroke(6f)) }
            tick.let { }
            drops.forEach { d -> val alpha = if (d.weight < 0.3f) 0.55f else 1f
                Box(Modifier.offset { IntOffset((d.x * (w - with(dens) { 140.dp.toPx() })).toInt(), (d.y.coerceIn(-0.2f, 1f) * (h - with(dens) { 60.dp.toPx() })).toInt()) }
                    .width(140.dp).clip(RoundedCornerShape(30.dp)).background(Color.hsv(200f - d.weight * 180f, 0.55f, 0.95f, alpha)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("${d.app}: ${d.title}", color = Color(0xFF0B0E1A), fontSize = 11.sp, maxLines = 2) } }
        }
        picked?.let { d -> OCard { OText("${d.app}: ${d.title}"); ORow { OBtn("Open") { com.nizam.app.feature.phone.AtlasNotificationListener.open(c, d.key); picked = null }
            OBtn2("Dismiss") { com.nizam.app.feature.phone.AtlasNotificationListener.dismiss(d.key); drops.remove(d); picked = null } } } }
        OMuted("${drops.count { it.weight >= 0.6f }} heavy · ${drops.count { it.weight < 0.35f }} light · ${drops.size} in the jar")
    }
}

