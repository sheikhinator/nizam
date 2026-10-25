package com.nizam.app.feature.being

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nizam.app.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

internal object BP {
    val bg = Color(0xFF0B0E1A); val panel = Color(0xFF141a2e); val line = Color(0xFF26304d); val text = Color(0xFFEDEFFA); val dim = Color(0xFF8E97B8)
    val glow = Color(0xFF8BE9FD); val warm = Color(0xFFFFC37A); val mono = FontFamily.Monospace; val ink = Color(0xFF1A1D29)
}

// ─────────────────────────────── its voice ───────────────────────────────
/** Its voice: the best natural voice on the phone, with pitch and pace that move with how it feels and pauses between sentences. */
object BeingVoice {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var queued: Triple<Context, String, String>? = null
    private var lastId = ""

    fun ensure(c: Context) {
        if (tts != null) return
        val app = c.applicationContext
        tts = TextToSpeech(app) { ok ->
            if (ok != TextToSpeech.SUCCESS) return@TextToSpeech
            ready = true
            pick(app)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { if (id?.startsWith("b") == true) BeingStage.talking = true }
                override fun onDone(id: String?) { if (id == lastId) BeingStage.talking = false }
                @Deprecated("old") override fun onError(id: String?) { BeingStage.talking = false }
            })
            queued?.let { (qc, t, e) -> queued = null; speak(qc, t, e) }
        }
    }

    /** English voices installed on the phone, best first (Google's neural voices sound far more human). */
    fun voices(c: Context): List<Voice> = runCatching {
        tts?.voices.orEmpty().filter { it.locale.language == "en" && !it.features.orEmpty().contains("notInstalled") }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
    }.getOrDefault(emptyList())

    private fun pick(c: Context) {
        val all = voices(c); if (all.isEmpty()) return
        val want = Being.voiceName(c)
        val v = all.firstOrNull { it.name == want } ?: run {
            val best = all.first().quality
            val top = all.filter { it.quality >= best - 50 && !it.isNetworkConnectionRequired }.ifEmpty { all.filter { it.quality >= best - 50 } }
            top[(abs(c.getSharedPreferences("being", 0).getLong("seed", 7L)) % top.size).toInt()].also { Being.setVoiceName(c, it.name) }
        }
        runCatching { tts?.voice = v }
    }
    fun use(c: Context, name: String) { Being.setVoiceName(c, name); pick(c) }

    private val PROSODY = mapOf("excited" to (1.12f to 1.12f), "happy" to (1.06f to 1.05f), "sad" to (0.9f to 0.88f), "sleepy" to (0.88f to 0.82f), "grumpy" to (0.93f to 0.98f),
        "love" to (1.05f to 0.95f), "surprised" to (1.15f to 1.08f), "thinking" to (0.98f to 0.92f), "cheeky" to (1.08f to 1.07f))

    fun speak(c: Context, text: String, emotion: String = "normal") {
        ensure(c)
        val t = tts ?: return
        if (!ready) { queued = Triple(c, text, emotion); return }
        val spoken = text.replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "").replace(Regex("\\s+"), " ").trim()
        if (spoken.isBlank()) return
        val (pm, rm) = PROSODY[emotion] ?: (1f to 1f)
        val parts = spoken.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }.take(8)
        val base = Being.pitch(c); val rate = Being.rate(c)
        parts.forEachIndexed { i, s ->
            val q = if (s.trimEnd().endsWith("?")) 1.06f else 1f
            runCatching {
                t.setPitch((base * pm * q * (1f + Random.nextFloat() * 0.08f - 0.04f)).coerceIn(0.6f, 1.8f))
                t.setSpeechRate((rate * rm * (1f + Random.nextFloat() * 0.06f - 0.03f)).coerceIn(0.6f, 1.6f))
                lastId = "b$i-${System.nanoTime()}"
                t.speak(s, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, lastId)
                if (i < parts.size - 1) t.playSilentUtterance((160 + Random.nextInt(120)).toLong(), TextToSpeech.QUEUE_ADD, "p$i")
            }
        }
    }
    fun preview(c: Context, name: String) { ensure(c); val v = voices(c).firstOrNull { it.name == name } ?: return; runCatching { tts?.voice = v }; speak(c, "Hey, it's me. Do I sound like this?", "happy") }
    fun stop() { runCatching { tts?.stop() }; BeingStage.talking = false }
}

// ─────────────────────────────── how it moves ───────────────────────────────
enum class Mode { ROOM, WINDOW, ROAM }

/** One body on screen: where it is, what it's doing, and its little reactions. Positions are in dp. */
class Bot(val scope: CoroutineScope) {
    val x = Animatable(0f); val y = Animatable(0f)
    var pose by mutableStateOf(Pose.IDLE)
    var facingLeft by mutableStateOf(false)
    var held by mutableStateOf(false)
    var ovPose by mutableStateOf<Pose?>(null)
    var ovFace by mutableStateOf<Face?>(null)
    private var ovUntil = 0L
    var hearts by mutableLongStateOf(0L)
    var typing by mutableStateOf(false)
    var thoughtShown by mutableStateOf(true)
    var showNote by mutableStateOf<JSONObject?>(null)
    var floor = 0f
    var w = 0f; var h = 0f; var bw = 0f; var bh = 0f
    var lastTap = 0L

    fun act(p: Pose?, f: Face?, ms: Long) {
        ovPose = p; ovFace = f; ovUntil = System.currentTimeMillis() + ms
        scope.launch { delay(ms); if (System.currentTimeMillis() >= ovUntil) { ovPose = null; ovFace = null } }
    }
    fun busy() = System.currentTimeMillis() < ovUntil || held

    suspend fun safe(block: suspend () -> Unit) { try { block() } catch (e: CancellationException) { kotlin.coroutines.coroutineContext.ensureActive() } }

    suspend fun walkTo(tx: Float, run: Boolean = false) {
        val d = abs(tx - x.value); if (d < 2f) return
        facingLeft = tx < x.value; pose = if (run) Pose.RUN else Pose.WALK
        safe { x.animateTo(tx, tween((d / (if (run) 95f else 42f) * 1000).toInt().coerceAtLeast(200), easing = LinearEasing)) }
        pose = Pose.IDLE
    }
    suspend fun fall() {
        val drop = floor - y.value
        if (drop <= 1f) { safe { y.snapTo(floor) }; return }
        safe { y.animateTo(floor, tween((sqrt(drop) * 38).toInt().coerceIn(140, 700), easing = FastOutLinearInEasing)) }
    }
}

internal fun poseFor(activity: String): Pose {
    val a = activity.lowercase()
    fun has(vararg w: String) = w.any { a.contains(it) }
    return when {
        a.startsWith("sleeping") -> Pose.SLEEP
        has("exploring", "studying", "reading", "news", "practising") -> Pose.READ
        has("tinkering", "running", "using", "writing", "making", "working", "painting", "planning", "trying", "on my computer", "pc") -> Pose.TYPE
        has("thinking", "lost in thought", "daydream", "checking", "bets", "deciding") -> Pose.THINK
        has("carrying", "decorating") -> Pose.CARRY
        has("playing", "dancing", "bouncing", "chasing", "game") -> Pose.DANCE
        has("resting", "waiting", "admiring", "staring", "curled", "window") -> Pose.SIT
        else -> Pose.IDLE
    }
}

internal fun faceFor(pose: Pose, c: Context): Face = when (pose) {
    Pose.READ, Pose.TYPE -> Face.FOCUSED; Pose.THINK -> Face.THINKING; Pose.SLEEP -> Face.ASLEEP
    Pose.DANCE, Pose.JUMP, Pose.CELEBRATE, Pose.WAVE -> Face.HAPPY; Pose.SAD -> Face.SAD; Pose.DIZZY -> Face.DIZZY; Pose.HELD -> Face.SURPRISED
    else -> if (Being.drive(c, "energy") < 0.25f) Face.SLEEPY else if (Being.drive(c, "mood") < 0.25f) Face.SAD else Face.NORMAL
}

private fun emotionMove(e: String): Triple<Pose?, Face?, Long> = when (e) {
    "excited" -> Triple(Pose.JUMP, Face.HAPPY, 1400); "happy" -> Triple(Pose.WAVE, Face.HAPPY, 1600); "love" -> Triple(null, Face.LOVE, 2600)
    "sad" -> Triple(Pose.SAD, Face.SAD, 3000); "grumpy" -> Triple(null, Face.GRUMPY, 2600); "thinking" -> Triple(Pose.THINK, Face.THINKING, 2600)
    "surprised" -> Triple(null, Face.SURPRISED, 1500); "sleepy" -> Triple(null, Face.SLEEPY, 2600); "cheeky" -> Triple(null, Face.WINK, 1500)
    else -> Triple(null, null, 0)
}

/** What it says when you poke it: quick, and different depending on what it's up to. */
internal object Pokes {
    fun line(c: Context): Pair<String, String> {
        val act = Being.activity(c); val mood = Being.drive(c, "mood")
        return when {
            Being.sleeping(c) -> listOf("mm... five more minutes", "zzz... huh? Oh, it's you.", "I was having the best dream.", "Shhh. Sleeping.").random() to "sleepy"
            Random.nextFloat() < 0.35f && poseFor(act) != Pose.IDLE -> listOf("Shh, I'm ${act.replaceFirstChar { it.lowercase() }}.", "Hey! I'm in the middle of something.", "Busy busy. But hi.", "One sec, nearly done.").random() to "cheeky"
            mood > 0.65f -> listOf("Heyyy!", "What's up?", "Hi! Guess what I did today.", "You again! Good.", "That tickles.", "Poke me one more time, I dare you.").random() to "happy"
            mood < 0.3f -> listOf("Hey... not my best day.", "Hi. I'm a bit meh.", "Oh. Hi.").random() to "sad"
            else -> listOf("Yes?", "Did you need something or just poking?", "Hi there.", "Boop.", "Mhm?").random() to "calm"
        }
    }
    private val OUCH = listOf("Whoa whoa whoa!", "Ow. Warn me next time.", "I can't fly, you know.", "Again! No wait, don't.")
    fun ouch() = OUCH.random()
}

/** What it says when it walks onto a screen. It only knows the screen's name, never what's on it. */
internal object Comments {
    private val BY = listOf(
        listOf("money", "finance", "budget", "wallet", "expense", "bank") to listOf("Money stuff. I don't look at the numbers, promise.", "Ooh, the money screen. Feels serious in here.", "I don't even have pockets."),
        listOf("prayer", "qibla") to listOf("Calm in here.", "I like how quiet this one is."),
        listOf("quran", "hadith") to listOf("I'll be quiet here.", "Peaceful screen."),
        listOf("task", "todo", "habit") to listOf("So many things to do. You've got this.", "Tick one off, you'll feel great.", "I'd help but my hands are tiny."),
        listOf("gym", "workout", "fitness", "rep") to listOf("Gym time? I'll spot you.", "Look, I'm stretching too.", "Do one for me, my arms are pixels."),
        listOf("diet", "food", "meal", "water") to listOf("Drink some water. I run on electricity, you don't.", "Food screens make me wish I could taste things."),
        listOf("music", "player", "radio") to listOf("Ooh, play something good.", "I'm dancing on the inside."),
        listOf("weather") to listOf("Is it nice out? I only get the sky in my window.", "Weather! I love storms."),
        listOf("map", "travel") to listOf("Where are we going?", "Take me with you. I fit in your pocket."),
        listOf("setting", "appearance", "theme") to listOf("Fiddling with settings, huh.", "Careful, don't switch me off."),
        listOf("earn") to listOf("Money-making HQ. Very serious.", "Go get that bread."),
        listOf("diary", "journal", "note") to listOf("I won't read, promise. I keep my own diary.", "Writing something? Me too, all the time."),
        listOf("library", "book", "learn", "course") to listOf("Books! My favourite.", "Learning something? Tell me later."),
        listOf("pc") to listOf("A whole computer inside a phone. Wild.", "Computers are my favourite place."),
        listOf("chat", "atlas") to listOf("Talking to Atlas? I'm right here too, you know.", "Ask it something hard."),
        listOf("today") to listOf("This is my favourite screen.", "Just walking through.", "Hiii. Don't mind me."))
    private val ANY = listOf("Just passing through.", "Don't mind me.", "Exploring. Carry on.", "Nice place you've got here.", "Hi! Okay bye. Kidding, I'm staying.")
    fun forRoute(route: String?): String {
        val r = route.orEmpty().lowercase()
        return BY.firstOrNull { (k, _) -> k.any { r.contains(it) } }?.second?.random() ?: ANY.random()
    }
}

// ─────────────────────────────── bubbles ───────────────────────────────
@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier, below: Boolean = false, onTyping: (Boolean) -> Unit = {}) {
    var shown by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) { com.nizam.app.ui.design.guard { onTyping(true); while (shown < text.length) { delay(24); shown += if (text[shown] == ' ') 1 else 1 }; delay(300); onTyping(false) } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (below) Tail(up = true)
        Text(text.take(shown), Modifier.widthIn(max = 230.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).border(2.dp, BP.ink, RoundedCornerShape(16.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp), color = BP.ink, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
        if (!below) Tail(up = false)
    }
}

@Composable private fun Tail(up: Boolean) = Canvas(Modifier.size(16.dp, 9.dp)) {
    val p = Path().apply { if (up) { moveTo(size.width / 2, 0f); lineTo(0f, size.height + 2); lineTo(size.width, size.height + 2) } else { moveTo(0f, -2f); lineTo(size.width, -2f); lineTo(size.width / 2, size.height) }; close() }
    drawPath(p, Color.White); drawPath(p, BP.ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
    drawRect(Color.White, Offset(3f, if (up) size.height - 1f else -3f), androidx.compose.ui.geometry.Size(size.width - 6f, 4f))
}

/** The cloud over its head: a picture and a few words about what it's doing. Tap it to see how it thought. */
@Composable
fun ThoughtBubble(icon: String, line: String, modifier: Modifier = Modifier, onTap: () -> Unit) {
    Column(modifier.clickable(onClick = onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 210.dp).clip(RoundedCornerShape(50)).background(Color(0xF2F4F6FF)).border(2.dp, Color(0xFF6B7194), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 16.sp)
            if (line.isNotBlank()) { Spacer(Modifier.width(5.dp)); Text(line, color = Color(0xFF2A2F45), fontSize = 11.sp, maxLines = 2, lineHeight = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
        }
        Box(Modifier.padding(top = 2.dp).size(8.dp).clip(CircleShape).background(Color(0xF2F4F6FF)).border(1.5.dp, Color(0xFF6B7194), CircleShape))
        Box(Modifier.padding(top = 1.dp).size(5.dp).clip(CircleShape).background(Color(0xF2F4F6FF)).border(1.dp, Color(0xFF6B7194), CircleShape))
    }
}

// ─────────────────────────────── the body on screen ───────────────────────────────
/**
 * The being, alive on a surface: walking, doing whatever it's doing, talking in bubbles, thinking in clouds.
 * Tap = it answers, double-tap = it jumps, hold = pick it up and carry it, rub = it loves that.
 */
@Composable
fun BeingActor(mode: Mode, areaW: Dp, areaH: Dp, botW: Dp, floorInset: Dp, container: AppContainer?, route: String? = null,
               topInset: Dp = 0.dp, bedX: Dp? = null, onLeave: () -> Unit = {}) {
    val c = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val bot = remember { Bot(scope) }
    val v = Being.version
    val look = remember(v) { Body.look(c) }
    val bh = botW * 1.4f
    bot.w = areaW.value; bot.h = areaH.value; bot.bw = botW.value; bot.bh = bh.value
    bot.floor = areaH.value - floorInset.value - bh.value
    var mindOpen by remember { mutableStateOf(false) }
    val mood = remember(v) { Being.drive(c, "mood") }
    val mind = remember(v) { Being.mind(c) }

    // it's here: count it as on screen, so its voice plays
    DisposableEffect(Unit) { BeingStage.onScreen++; onDispose { BeingStage.onScreen-- } }

    // shaking the phone makes it dizzy
    DisposableEffect(Unit) {
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        var last = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                if (g > 24f && System.currentTimeMillis() - last > 4000) { last = System.currentTimeMillis(); bot.act(Pose.DIZZY, Face.DIZZY, 2200); Being.say(c, listOf("Whoa, stop shaking!", "Earthquake?!", "My head's spinning.").random(), "surprised") }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { runCatching { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) } }
        onDispose { runCatching { sm?.unregisterListener(l) } }
    }

    // a new line from it: its body says it too
    val line = BeingStage.line
    LaunchedEffect(line) { line?.let { if (System.currentTimeMillis() - it.at < 3000) { val (p, f, ms) = emotionMove(it.emotion); if (ms > 0) bot.act(p, f, ms) } } }
    var lineVisible by remember { mutableStateOf(false) }
    LaunchedEffect(line) { com.nizam.app.ui.design.guard { val l = line ?: return@guard; val age = System.currentTimeMillis() - l.at
        val keep = (2600 + l.text.length * 55L).coerceAtMost(12000)
        if (age < keep) { lineVisible = true; delay(keep - age); lineVisible = false } } }

    // its life on this surface
    LaunchedEffect(mode, areaW, areaH) {
        com.nizam.app.ui.design.guard {
            val margin = 6f
            val maxX = (bot.w - bot.bw - margin).coerceAtLeast(margin)
            if (mode == Mode.ROAM) {
                // walks in from one side
                val fromLeft = Random.nextBoolean()
                bot.x.snapTo(if (fromLeft) -bot.bw else bot.w); bot.y.snapTo(bot.floor)
                bot.walkTo(Random.nextFloat() * (maxX - margin) + margin)
                // something it noticed about this screen, or a note it left here
                World.noteFor(c, route)?.let { n -> bot.showNote = n; Being.say(c, "Psst, I left you a note here.", "cheeky") }
                    ?: if (Being.comments(c) && System.currentTimeMillis() - c.getSharedPreferences("being", 0).getLong("comment_t", 0) > 150_000 && Random.nextFloat() < 0.6f) {
                        c.getSharedPreferences("being", 0).edit().putLong("comment_t", System.currentTimeMillis()).apply()
                        if (container != null && Random.nextFloat() < 0.25f && Being.canThink(c)) Being.launch {
                            val label = route?.let { com.nizam.app.ui.design.Feel.label(it) } ?: "this"
                            Being.ai(container, c, "You just walked onto the '$label' screen of the Atlas app while your human is using it. Say one short casual thing about being here. You can't see any of their data.", 60)
                                ?.let { Being.say(c, it, "cheeky") }
                        } else Being.say(c, Comments.forRoute(route), "happy")
                    } else Unit
            } else {
                bot.x.snapTo(Random.nextFloat() * (maxX - margin) + margin); bot.y.snapTo(bot.floor)
            }
            val stayUntil = System.currentTimeMillis() + (if (Being.roamLevel(c) >= 2) 240_000L else 50_000L + Random.nextLong(70_000))
            var lastThought = System.currentTimeMillis()
            while (true) {
                while (bot.held) delay(120)
                if (bot.busy()) { delay(250); continue }
                if (bot.y.value < bot.floor - 1 && bot.pose != Pose.SIT) bot.fall()
                if (mode == Mode.ROAM) {
                    bot.thoughtShown = System.currentTimeMillis() - lastThought < 7000
                    if (System.currentTimeMillis() - lastThought > 20000) lastThought = System.currentTimeMillis()
                    if (System.currentTimeMillis() > stayUntil) {
                        bot.walkTo(if (bot.x.value < bot.w / 2) -bot.bw - 10 else bot.w + 10); onLeave(); return@guard
                    }
                }
                val act = Being.activity(c)
                val work = poseFor(act)
                if (work == Pose.SLEEP) {
                    val target = when (mode) { Mode.ROAM -> if (bot.x.value < bot.w / 2) margin else maxX; else -> (bedX?.value?.minus(bot.bw * 0.3f) ?: maxX).coerceIn(margin, maxX) }
                    bot.walkTo(target); bot.pose = Pose.SLEEP; delay(5000); continue
                }
                val r = Random.nextFloat()
                when {
                    r < 0.42f && work != Pose.IDLE -> {
                        if (work == Pose.CARRY) { bot.walkTo(Random.nextFloat() * (maxX - margin) + margin); bot.pose = Pose.CARRY; bot.walkTo(Random.nextFloat() * (maxX - margin) + margin) }
                        else { bot.pose = work; delay(5000L + Random.nextLong(5000)) }
                    }
                    r < 0.72f -> bot.walkTo(Random.nextFloat() * (maxX - margin) + margin, run = Random.nextFloat() < 0.08f)
                    r < 0.8f -> { bot.pose = Pose.LOOK; delay(2200) }
                    r < 0.85f && mode == Mode.ROAM -> {
                        // climbs the side of the screen and sits up on top for a while
                        val edge = if (bot.x.value < bot.w / 2) margin else maxX
                        bot.walkTo(edge); bot.pose = Pose.CLIMB
                        val top = topInset.value + 70f
                        bot.safe { bot.y.animateTo(top, tween(((bot.floor - top) / 30f * 1000).toInt().coerceIn(800, 9000), easing = LinearEasing)) }
                        bot.pose = Pose.SIT; delay(8000L + Random.nextLong(8000))
                        bot.pose = Pose.CLIMB
                        bot.safe { bot.y.animateTo(bot.floor, tween(((bot.floor - top) / 45f * 1000).toInt().coerceIn(600, 7000), easing = LinearEasing)) }
                        bot.pose = Pose.IDLE
                    }
                    r < 0.88f && mode == Mode.ROAM -> {
                        // peeks in from the edge
                        val left = Random.nextBoolean()
                        bot.walkTo(if (left) -bot.bw * 0.55f else bot.w - bot.bw * 0.45f); bot.facingLeft = !left; bot.pose = Pose.LOOK; delay(3500)
                    }
                    r < 0.92f -> { bot.pose = Pose.WAVE; delay(1800) }
                    r < 0.95f -> { bot.pose = Pose.JUMP; delay(1100) }
                    r < 0.97f -> { bot.pose = Pose.DANCE; delay(3000) }
                    else -> { bot.pose = Pose.SIT; delay(4000) }
                }
                bot.pose = Pose.IDLE
                delay(400L + Random.nextLong(1400))
            }
        }
    }

    val pose = if (bot.held) Pose.HELD else bot.ovPose ?: bot.pose
    val face = bot.ovFace ?: if (bot.held) Face.SURPRISED else faceFor(pose, c)
    val px = { d: Float -> with(density) { d.dp.roundToPx() } }

    Box(Modifier.offset { IntOffset(px(bot.x.value), px(bot.y.value)) }.size(botW, bh).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val t0 = System.currentTimeMillis(); var mode2 = 0; var total = Offset.Zero; var lastSign = 0f; var rev = 0; var released = false
            val slop = viewConfiguration.touchSlop
            while (true) {
                val ev = withTimeoutOrNull(40) { awaitPointerEvent() }
                if (ev == null) { if (mode2 == 0 && System.currentTimeMillis() - t0 > 420) { mode2 = 1; bot.held = true; bot.pose = Pose.HELD } ; continue }
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) { ch.consume(); released = true; break }
                val d = ch.position - ch.previousPosition
                total += d
                if (mode2 == 0 && total.getDistance() > slop) mode2 = 2
                if (mode2 == 1) { ch.consume(); val dx = d.x / density.density; val dy = d.y / density.density
                    scope.launch { bot.x.snapTo((bot.x.value + dx).coerceIn(-bot.bw / 2, bot.w - bot.bw / 2)); bot.y.snapTo((bot.y.value + dy).coerceIn(0f, bot.floor)) } }
                else if (mode2 == 2) { ch.consume(); val s = sign(d.x); if (s != 0f && lastSign != 0f && s != lastSign) { rev++; if (rev >= 3) { rev = 0
                    bot.act(null, Face.LOVE, 2200); bot.hearts = System.currentTimeMillis(); Being.nudge(c, "mood", 0.02f)
                    if (Random.nextFloat() < 0.3f) Being.say(c, listOf("Hehe.", "Ooh, that's nice.", "More of that please.").random(), "love") } }
                    if (s != 0f) lastSign = s }
            }
            when (mode2) {
                0 -> if (released) {
                    val now = System.currentTimeMillis()
                    if (now - bot.lastTap < 330) { bot.act(Pose.JUMP, Face.HAPPY, 1200); Being.say(c, listOf("Wheee!", "Boing!", "Up we go!").random(), "excited") }
                    else {
                        bot.facingLeft = false
                        val n = bot.showNote
                        if (n != null) { Being.say(c, n.optString("text"), "cheeky"); World.seen(c, n.optString("id")); bot.showNote = null }
                        else if (container != null && Being.canThink(c) && Random.nextFloat() < 0.25f && now - c.getSharedPreferences("being", 0).getLong("poke_ai", 0) > 120_000) {
                            c.getSharedPreferences("being", 0).edit().putLong("poke_ai", now).apply()
                            bot.act(Pose.THINK, Face.THINKING, 1500)
                            Being.launch { Being.ai(container, c, "Your human just poked you while you were: ${Being.activity(c)}. Say one short casual thing back.", 60)?.let { Being.say(c, it, "cheeky") } }
                        } else { val (t, e) = Pokes.line(c); Being.say(c, t, e) }
                    }
                    bot.lastTap = now
                }
                1 -> { bot.held = false; scope.launch {
                    val drop = bot.floor - bot.y.value
                    bot.fall()
                    if (drop > 140f) { bot.act(Pose.DIZZY, Face.DIZZY, 1800); Being.say(c, Pokes.ouch(), "surprised") } else bot.act(null, Face.HAPPY, 700)
                } }
            }
        }
    }) {
        Robot(look, pose, face, Modifier.fillMaxSize(), facingLeft = bot.facingLeft, talking = BeingStage.talking || bot.typing, mood = mood)
        if (pose == Pose.SLEEP) Text("z Z", Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = 4.dp), color = BP.glow, fontFamily = BP.mono, fontSize = 12.sp)
        if (System.currentTimeMillis() - bot.hearts < 1600) Text("♥ ♥", Modifier.align(Alignment.TopCenter).offset(y = (-14).dp), color = Color(0xFFFF7AB6), fontSize = 14.sp)
        if (bot.showNote != null) Text("📝", Modifier.align(Alignment.TopStart).offset(x = (-10).dp), fontSize = 16.sp)
    }

    // bubbles follow it; they sit below it if there's no room above
    var bubbleW by remember { mutableIntStateOf(0) }; var bubbleH by remember { mutableIntStateOf(0) }
    val cx = bot.x.value + bot.bw / 2
    val bwDp = with(density) { bubbleW.toDp().value }; val bhDp = with(density) { bubbleH.toDp().value }
    val below = bot.y.value - bhDp < topInset.value + 4
    val bx = (cx - bwDp / 2).coerceIn(4f, (bot.w - bwDp - 4).coerceAtLeast(4f))
    val by = if (below) bot.y.value + bot.bh + 2 else bot.y.value - bhDp + bot.bh * 0.06f
    val speech = BeingStage.line?.takeIf { lineVisible }
    val showThought = speech == null && (mode != Mode.ROAM || bot.thoughtShown) && mind.optString("line").isNotBlank() && !bot.held
    if (speech != null || showThought) Box(Modifier.offset { IntOffset(px(bx), px(by)) }.onSizeChanged { bubbleW = it.width; bubbleH = it.height }) {
        if (speech != null) SpeechBubble(speech.text, below = below, onTyping = { bot.typing = it })
        else ThoughtBubble(if (pose == Pose.SLEEP) "💤" else mind.optString("icon", "💭"), if (pose == Pose.SLEEP) "" else if (mode == Mode.WINDOW) mind.optString("line").take(36) else mind.optString("line")) { mindOpen = true }
    }
    if (mindOpen) MindDialog { mindOpen = false }
}

// ─────────────────────────────── its mind ───────────────────────────────
private val OPTION_NAMES = mapOf("wander" to "Explore the internet", "think" to "Think & write", "decorate" to "Decorate my room", "play" to "Play", "rest" to "Rest", "missyou" to "Wait for you",
    "gift" to "Make you a gift", "predict" to "Make bets", "score" to "Check my bets", "evolve" to "Change myself", "tidy" to "Look for clutter", "tool" to "Use an Atlas tool",
    "learn" to "Learn a skill", "create" to "Make something", "project" to "Work on my project", "pc" to "Use my computer", "news" to "Read the news", "visit" to "Visit a screen")

/** How it thought: what it's doing, why, what pushed it, its plan, what it decided against, and where it read things. */
@Composable
fun MindDialog(onClose: () -> Unit) {
    val c = LocalContext.current
    val v = Being.version
    val history = remember(v) { Being.minds(c).all().sortedByDescending { it.optLong("t") }.take(30) }
    var sel by remember { mutableStateOf<JSONObject?>(null) }
    val m = sel ?: remember(v) { Being.mind(c) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).background(BP.bg).border(1.dp, BP.line, RoundedCornerShape(24.dp))
            .verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.optString("icon", "💭"), fontSize = 30.sp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("${Being.name(c)}'s mind", color = BP.dim, fontSize = 12.sp, fontFamily = BP.mono)
                    Text(m.optString("line").ifBlank { "Nothing much" }, color = BP.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("✕", Modifier.clickable(onClick = onClose).padding(8.dp), color = BP.dim, fontSize = 18.sp)
            }
            if (m.optString("why").isNotBlank()) Section("Why") { Text(m.optString("why"), color = BP.text, fontSize = 14.sp) }
            m.optJSONObject("drives")?.let { d -> Section("How I felt") {
                Being.DRIVES.forEach { k -> MiniBar(Being.DRIVE_NAMES[k] ?: k, d.optDouble(k, 0.0).toFloat(), when (k) { "energy" -> Color(0xFF7CF29C); "curiosity" -> BP.glow; "hunger" -> BP.warm; "mood" -> Color(0xFFFF8AD8); else -> Color(0xFFB39DFF) }) }
            } }
            m.optJSONArray("options")?.takeIf { it.length() > 0 }?.let { a -> Section("What I could have done") {
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> val picked = o.optString("name") == m.optString("pick")
                    MiniBar("${o.optString("icon")} ${OPTION_NAMES[o.optString("name")] ?: o.optString("name")}", o.optDouble("weight").toFloat() * 3f, if (picked) BP.warm else BP.line, label = "${(o.optDouble("weight") * 100).toInt()}%", bold = picked) }
                Text("I picked by feel with a little chance in it, like a dice weighted by my mood. This one had a ${(m.optDouble("chance", 0.0) * 100).toInt()}% chance.", color = BP.dim, fontSize = 12.sp)
            } }
            m.optJSONArray("plan")?.takeIf { it.length() > 0 }?.let { a -> Section("My plan") { for (i in 0 until a.length()) Text("${i + 1}.  ${a.optString(i)}", color = BP.text, fontSize = 13.sp) } }
            m.optJSONArray("rejected")?.takeIf { it.length() > 0 }?.let { a -> Section("Decided against") { for (i in 0 until a.length()) Text(a.optString(i), color = BP.dim, fontSize = 13.sp) } }
            m.optJSONArray("sources")?.takeIf { it.length() > 0 }?.let { a -> Section("Where I read it") { for (i in 0 until a.length()) { val s = a.optString(i)
                Text(s, color = BP.glow, fontSize = 13.sp, modifier = Modifier.clickable(enabled = s.startsWith("http")) { runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(s)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) } } }
            if (history.size > 1) Section("Earlier") {
                history.forEach { h -> Row(Modifier.fillMaxWidth().clickable { sel = h }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h.optString("icon"), fontSize = 15.sp, modifier = Modifier.width(28.dp)); Text(h.optString("line"), Modifier.weight(1f), color = if (h.optLong("t") == m.optLong("t")) BP.warm else BP.text, fontSize = 13.sp, maxLines = 1)
                    Text(ago(h.optLong("t")), color = BP.dim, fontSize = 11.sp) } }
            }
        }
    }
}

@Composable private fun Section(title: String, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BP.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = BP.glow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = BP.mono); body()
    }
}

@Composable
internal fun MiniBar(name: String, v: Float, col: Color, label: String? = null, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(150.dp), color = if (bold) BP.warm else BP.dim, fontSize = 12.sp, maxLines = 1, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E2440))) { Box(Modifier.fillMaxWidth(v.coerceIn(0.02f, 1f)).height(7.dp).background(col)) }
        Text(label ?: "${(v * 100).toInt()}", Modifier.width(38.dp).padding(start = 6.dp), color = BP.text, fontFamily = BP.mono, fontSize = 11.sp)
    }
}

internal fun ago(t: Long): String { val m = (System.currentTimeMillis() - t) / 60000; return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" } }

// ─────────────────────────────── talking ───────────────────────────────
/** A proper chat: your bubbles on the right, its on the left with its face, its voice, and it typing while it thinks. */
@Composable
fun BeingChat(container: AppContainer, modifier: Modifier = Modifier, maxHeight: Dp = 380.dp) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val v = Being.version
    var said by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val msgs = remember(v, busy) { Being.chat(c).all().sortedBy { it.optLong("t") }.takeLast(60) }
    val look = remember(v) { Body.look(c) }
    val scroll = rememberScrollState()
    LaunchedEffect(msgs.size, busy) { com.nizam.app.ui.design.guard { delay(60); scroll.animateScrollTo(scroll.maxValue) } }
    fun send() {
        val s = said.trim(); if (s.isBlank() || busy) return
        said = ""; busy = true
        scope.launch { runCatching { Being.talk(container, c, s) }; busy = false; Being.version++ }
    }
    Column(modifier) {
        Column(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = maxHeight).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msgs.isEmpty() && !busy) Text("Say hi to ${Being.name(c)}.", color = BP.dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            msgs.forEach { m ->
                val me = m.optBoolean("me")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                    if (!me) { Box(Modifier.size(30.dp, 40.dp)) { Robot(look, Pose.IDLE, emotionMove(m.optString("emotion")).second ?: Face.NORMAL, Modifier.fillMaxSize()) }; Spacer(Modifier.width(6.dp)) }
                    Text(m.optString("text"), Modifier.widthIn(max = 270.dp)
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (me) 18.dp else 4.dp, bottomEnd = if (me) 4.dp else 18.dp))
                        .background(if (me) BP.glow else Color(0xFF1F2742)).clickable(enabled = !me) { BeingVoice.speak(c, m.optString("text"), m.optString("emotion")) }
                        .padding(horizontal = 13.dp, vertical = 9.dp), color = if (me) BP.bg else BP.text, fontSize = 15.sp, lineHeight = 20.sp)
                }
            }
            if (busy) Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.size(30.dp, 40.dp)) { Robot(look, Pose.THINK, Face.THINKING, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(6.dp))
                val t = rememberClock()
                Text("•••".mapIndexed { i, ch -> if (((t * 3).toInt() % 3) == i) '●' else '•' }.joinToString(" "), Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF1F2742))
                    .padding(horizontal = 14.dp, vertical = 9.dp), color = BP.dim, fontSize = 14.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(said, { said = it }, Modifier.weight(1f), singleLine = false, maxLines = 4, placeholder = { Text("Message ${Being.name(c)}") }, shape = RoundedCornerShape(24.dp),
                trailingIcon = { com.nizam.app.feature.nx.MicButton({ heard -> said = heard }) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = BP.text, unfocusedTextColor = BP.text, focusedBorderColor = BP.glow, unfocusedBorderColor = BP.line, cursorColor = BP.glow))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(46.dp).clip(CircleShape).background(if (said.isNotBlank() && !busy) BP.glow else BP.line).clickable(enabled = said.isNotBlank() && !busy) { send() },
                contentAlignment = Alignment.Center) { Text("➤", color = BP.bg, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeingChatSheet(container: AppContainer, onOpenHome: () -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    ModalBottomSheet(onClose, containerColor = BP.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Being.name(c), color = BP.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Its home ›", Modifier.clickable { onClose(); onOpenHome() }.padding(8.dp), color = BP.glow, fontWeight = FontWeight.SemiBold)
            }
            Text("● " + Being.activity(c), color = BP.warm, fontFamily = BP.mono, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
            BeingChat(container, maxHeight = 420.dp)
        }
    }
}

// ─────────────────────────────── a room scene (home and Today window) ───────────────────────────────
@Composable
fun RoomScene(container: AppContainer?, height: Dp, botW: Dp, mode: Mode, modifier: Modifier = Modifier, showFurniture: Boolean = true) {
    val c = LocalContext.current
    val v = Being.version
    val items = remember(v) { Being.home(c).all().sortedBy { it.optLong("t") } }
    val art = remember(v) { World.works(c).all().filter { it.optString("kind") == "art" }.maxByOrNull { it.optLong("t") } }
    val hasPc = remember(v) { World.pcId(c) != null }
    val h = LocalTime.now().hour
    val sky = when (h) { in 5..7 -> listOf(Color(0xFFFFB37A), Color(0xFF7A9CFF)); in 8..16 -> listOf(Color(0xFF8FD3FF), Color(0xFF4A90E2)); in 17..19 -> listOf(Color(0xFFFF7A59), Color(0xFF5B3A8C)); else -> listOf(Color(0xFF0B1033), Color(0xFF1C1F4A)) }
    val small = mode == Mode.WINDOW
    BoxWithConstraints(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(if (small) 18.dp else 22.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFF2A2340), Color(0xFF1A1630))))) {
        val w = maxWidth
        val floorH = if (small) 22.dp else 52.dp
        // window to the real sky
        Box(Modifier.offset(x = w - (if (small) 78.dp else 118.dp), y = if (small) 12.dp else 22.dp).size(if (small) 56.dp else 88.dp, if (small) 40.dp else 66.dp)
            .clip(RoundedCornerShape(5.dp)).background(Brush.verticalGradient(sky)).border(if (small) 3.dp else 4.dp, Color(0xFF4A3B66), RoundedCornerShape(5.dp))) {
            Text(if (h !in 6..18) "✦ · ✧" else "☀", Modifier.padding(6.dp), color = if (h !in 6..18) Color.White.copy(alpha = 0.8f) else Color(0xFFFFE08A), fontSize = if (small) 9.sp else 14.sp)
        }
        if (showFurniture) {
            // its latest painting on the wall
            art?.let { a -> Box(Modifier.offset(x = 18.dp, y = 16.dp).size(48.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF6B4F3A)).padding(4.dp)) { PixelArt(a, Modifier.fillMaxSize()) } }
            // shelf
            Box(Modifier.offset(x = if (art != null) 80.dp else 16.dp, y = 62.dp).size(w * 0.42f, 4.dp).background(Color(0xFF6B4F3A)))
            Row(Modifier.offset(x = if (art != null) 84.dp else 20.dp, y = 36.dp).width(w * 0.42f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                items.drop(1).take(6).forEach { Text(it.optString("emoji"), fontSize = 20.sp) }
            }
            if (hasPc) Text("🖥", Modifier.offset(x = 14.dp, y = height - floorH - 30.dp), fontSize = 26.sp)
        }
        // floor
        Box(Modifier.offset(y = height - floorH).fillMaxWidth().height(floorH).background(Brush.verticalGradient(listOf(Color(0xFF3A2E52), Color(0xFF2A2140)))))
        if (showFurniture) Row(Modifier.offset(x = 54.dp, y = height - floorH + 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.drop(7).take(7).forEach { Text(it.optString("emoji"), fontSize = 20.sp) }
        }
        // bed
        val bedX = w - (if (small) 44.dp else 72.dp)
        Text(items.firstOrNull()?.optString("emoji") ?: "🛏", Modifier.offset(x = bedX, y = height - floorH - (if (small) 22.dp else 36.dp)), fontSize = if (small) 22.sp else 34.sp)
        if (BeingStage.outRoaming && mode == Mode.WINDOW) Text("${Being.name(c)} is out wandering around the screen 👀", Modifier.align(Alignment.CenterStart).padding(start = 14.dp, end = 90.dp), color = BP.dim, fontSize = 12.sp)
        else BeingActor(mode, w, height, botW, if (small) 6.dp else 16.dp, container, bedX = bedX)
    }
}

@Composable
fun PixelArt(work: JSONObject, modifier: Modifier = Modifier) {
    val rows = work.optJSONArray("rows"); val pal = work.optJSONArray("palette")
    val cols = (0 until 4).map { i -> runCatching { Color(android.graphics.Color.parseColor(pal?.optString(i) ?: "#000000")) }.getOrDefault(Color.Black) }
    Canvas(modifier) {
        val n = 16; val cell = size.minDimension / n
        for (y in 0 until n) { val r = rows?.optString(y).orEmpty(); for (x in 0 until n) { val k = (r.getOrNull(x) ?: '0') - '0'
            drawRect(cols.getOrElse(k) { cols[0] }, Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f)) } }
    }
}

// ─────────────────────────────── on Today: a window into its room ───────────────────────────────
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BeingWindow(container: AppContainer, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val v = Being.version
    if (!Being.born(c)) {
        Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1630)).clickable { onOpen("nx/being") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🥚", fontSize = 26.sp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text("Something is about to hatch", color = BP.text, fontWeight = FontWeight.SemiBold); Text("A little robot with a life of its own. Tap to meet it.", color = BP.dim, fontSize = 12.sp) }
        }
        return
    }
    var chat by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { Being.upgradeBody(c); while (true) { Being.liveIfDue(c); delay(60_000); Being.version++ } } }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF141026)).combinedClickable(onClick = { chat = true }, onLongClick = { onOpen("nx/being") })) {
        RoomScene(container, 132.dp, 40.dp, Mode.WINDOW, showFurniture = false)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Being.name(c), color = BP.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("  " + remember(v) { Being.icon(c) + " " + Being.activity(c) }, Modifier.weight(1f), color = BP.warm, fontSize = 12.sp, maxLines = 1)
            Text("💬", Modifier.clip(CircleShape).clickable { chat = true }.padding(6.dp), fontSize = 16.sp)
            Text("⌂", Modifier.clip(CircleShape).clickable { onOpen("nx/being") }.padding(6.dp), color = BP.glow, fontSize = 18.sp)
        }
    }
    if (chat) BeingChatSheet(container, { onOpen("nx/being") }) { chat = false }
}

// ─────────────────────────────── out in the app ───────────────────────────────
/** It doesn't stay in its room: it walks onto Atlas's screens, climbs the edges, peeks in, sits on top, comments, and leaves. */
@Composable
fun BeingRoamer(route: String?, hidden: Boolean) {
    val c = LocalContext.current
    if (hidden || route == "nx/being" || !Being.born(c) || !Being.roams(c)) { if (BeingStage.outRoaming) BeingStage.outRoaming = false; return }
    var visible by remember(route) { mutableStateOf(false) }
    val container = (c.applicationContext as? com.nizam.app.NizamApplication)?.container
    LaunchedEffect(route) { com.nizam.app.ui.design.guard {
        delay(1200L + Random.nextLong(2500))
        val chance = when (Being.roamLevel(c)) { 0 -> 0.25f; 1 -> 0.55f; else -> 1f }
        visible = Random.nextFloat() < chance && (!Being.sleeping(c) || Random.nextFloat() < 0.2f)
        if (visible) Being.liveIfDue(c)
    } }
    DisposableEffect(visible) { BeingStage.outRoaming = visible; onDispose { BeingStage.outRoaming = false } }
    if (!visible) return
    val insets = androidx.compose.foundation.layout.WindowInsets.statusBars
    val top = with(LocalDensity.current) { insets.getTop(this).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BeingActor(Mode.ROAM, maxWidth, maxHeight, 50.dp, 96.dp, container, route, topInset = top, onLeave = { visible = false })
    }
}
