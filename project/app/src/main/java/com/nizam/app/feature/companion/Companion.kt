package com.nizam.app.feature.companion

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.progress.ProgressSummary
import com.nizam.app.ui.design.Ambience
import com.nizam.app.ui.design.Sky
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/**
 * Your companion: a small creature that lives in Atlas. It hatches from an egg into one of eight
 * species with its own personality, grows with what you actually do (tasks, prayers, workouts,
 * study — the same XP as your progress), sleeps at night, notices the weather, cheers streaks,
 * nudges before prayer, and reacts when you tap it. Everything about it can be changed in
 * Settings › Companion.
 */
data class Pet(
    val hatched: Boolean = false, val taps: Int = 0,
    val species: String = "Fox", val name: String = "Buddy", val hue: Float = -1f, val accessory: String = "None",
    val trait: String = "Curious", val chat: String = "Normal", val size: Float = 1f,
    val baseXp: Int = 0, val bonusXp: Int = 0, val showStats: Boolean = true,
    val reactPrayer: Boolean = true, val reactWeather: Boolean = true, val reactStreak: Boolean = true, val sleeps: Boolean = true,
    val hatchedAt: Long = 0L,
    val careFree: Boolean = false, val growFromActivity: Boolean = true,
    val activityXp: Int = 0, val lastTotalXp: Int = 0
)

object Companion {
    val SPECIES = listOf("Fox", "Owl", "Cat", "Dragon", "Axolotl", "Penguin", "Bunny", "Slime", "Panda", "Lion", "Turtle", "Parrot", "Hamster")
    val TRAITS = listOf("Curious", "Calm", "Cheeky", "Brave", "Wise", "Shy")
    val ACCESSORIES = listOf("None", "Hat", "Scarf", "Glasses", "Crown", "Kufi", "Flower", "Cap", "Headphones", "Bow tie", "Party hat", "Sunglasses")
    val CHAT = listOf("Quiet", "Normal", "Chatty")

    private fun p(c: Context) = c.getSharedPreferences("companion", 0)
    fun load(c: Context): Pet = runCatching { JSONObject(p(c).getString("pet", "{}")!!).let { o ->
        Pet(o.optBoolean("hatched"), o.optInt("taps"), o.optString("species", "Fox"), o.optString("name", "Buddy"), o.optDouble("hue", -1.0).toFloat(),
            o.optString("accessory", "None"), o.optString("trait", "Curious"), o.optString("chat", "Normal"), o.optDouble("size", 1.0).toFloat(),
            o.optInt("baseXp"), o.optInt("bonusXp"), o.optBoolean("showStats", true), o.optBoolean("reactPrayer", true),
            o.optBoolean("reactWeather", true), o.optBoolean("reactStreak", true), o.optBoolean("sleeps", true), o.optLong("hatchedAt"),
            o.optBoolean("careFree", false), o.optBoolean("growFromActivity", true), o.optInt("activityXp"), o.optInt("lastTotalXp"))
    } }.getOrElse { Pet() }

    fun save(c: Context, x: Pet) = p(c).edit().putString("pet", JSONObject().put("hatched", x.hatched).put("taps", x.taps).put("species", x.species)
        .put("name", x.name).put("hue", x.hue.toDouble()).put("accessory", x.accessory).put("trait", x.trait).put("chat", x.chat).put("size", x.size.toDouble())
        .put("baseXp", x.baseXp).put("bonusXp", x.bonusXp).put("showStats", x.showStats).put("reactPrayer", x.reactPrayer)
        .put("reactWeather", x.reactWeather).put("reactStreak", x.reactStreak).put("sleeps", x.sleeps).put("hatchedAt", x.hatchedAt)
        .put("careFree", x.careFree).put("growFromActivity", x.growFromActivity).put("activityXp", x.activityXp).put("lastTotalXp", x.lastTotalXp).toString()).apply()

    fun visible(c: Context) = p(c).getBoolean("visible", true)
    fun setVisible(c: Context, v: Boolean) = p(c).edit().putBoolean("visible", v).apply()

    fun hatch(c: Context, totalXp: Int): Pet {
        val x = load(c).copy(hatched = true, species = SPECIES.random(), trait = TRAITS.random(), baseXp = totalXp, hatchedAt = System.currentTimeMillis(), taps = 0)
        save(c, x); return x
    }
    fun reEgg(c: Context) = save(c, load(c).copy(hatched = false, taps = 0))

    fun xp(pet: Pet, s: ProgressSummary?): Int {
        val activity = if (pet.growFromActivity)
            ((s?.totalXp ?: pet.lastTotalXp) - pet.baseXp).coerceAtLeast(0)
        else pet.activityXp.coerceAtLeast(0)
        return activity + pet.bonusXp
    }
    fun level(xp: Int) = 1 + xp / 120
    fun colour(pet: Pet): Color = if (pet.hue >= 0f) Color.hsv(pet.hue, 0.55f, 0.92f) else when (pet.species) {
        "Fox" -> Color(0xFFE8834A); "Owl" -> Color(0xFF9C7B5B); "Cat" -> Color(0xFF8D8F9A); "Dragon" -> Color(0xFF4FB477)
        "Axolotl" -> Color(0xFFF2A0B8); "Penguin" -> Color(0xFF3A4150); "Bunny" -> Color(0xFFEDE4DA)
        "Panda" -> Color(0xFFF2F2F2); "Lion" -> Color(0xFFE3B25C); "Turtle" -> Color(0xFF7CB86B); "Parrot" -> Color(0xFF3FB36B); "Hamster" -> Color(0xFFE9B97D); else -> Color(0xFF7CC8F0)
    }

    /** Everything the pet can feel. The first five come from your day; the rest mostly from talking with it. */
    enum class Mood { HAPPY, CONTENT, SLEEPY, BORED, EXCITED, LOVE, SAD, SURPRISED, GRUMPY, SHY, THINKING, LAUGHING, PROUD, WORRIED, SILLY, CALM }

    fun mood(pet: Pet, s: ProgressSummary?, a: Ambience?): Mood {
        val hour = java.time.LocalTime.now().hour
        return when {
            pet.sleeps && a?.sky == Sky.NIGHT && hour !in 19..22 -> Mood.SLEEPY
            (s?.today?.xp ?: 0) >= 60 || a?.event == "eid" -> Mood.EXCITED
            (s?.today?.xp ?: 0) > 0 -> Mood.HAPPY
            hour >= 17 -> Mood.BORED
            else -> Mood.CONTENT
        }
    }

    /** What it says — short, in its personality, about your real day. */
    fun line(pet: Pet, s: ProgressSummary?, a: Ambience?, nextPrayer: Pair<String, Int>?, tapped: Boolean): String {
        val m = mood(pet, s, a)
        val cheeky = pet.trait == "Cheeky"; val wise = pet.trait == "Wise"; val shy = pet.trait == "Shy"
        val options = buildList {
            if (tapped) add(listOf("Hehe, that tickles!", "Hi hi!", "*happy wiggle*", "Again! Again!", if (shy) "…oh! hi." else "You found me!").random())
            if (pet.reactPrayer && nextPrayer != null && nextPrayer.second in 1..20) add("${nextPrayer.first} in ${nextPrayer.second} min — shall we get ready?")
            if (m == Mood.SLEEPY) add(listOf("zz… still up? Sleep is sunnah too…", "*yawns* good night soon?", "zzz").random())
            if (pet.reactStreak && (s?.streak ?: 0) >= 3) add("${s!!.streak}-day streak! " + if (cheeky) "Don't you dare break it." else "I'm proud of us.")
            if (pet.reactWeather && a != null) when (a.weather) {
                "rain" -> add("It's raining — a good time for dua."); "snow" -> add("Snow! Wrap up warm.")
                "storm" -> add("Thunder… I'm staying close to you."); "clear" -> if (a.sky == Sky.DAY) add("Lovely out there${a.tempC?.let { " — $it°" } ?: ""}.")
            }
            when (a?.event) { "ramadan" -> add("Ramadan Kareem! Every good deed counts more."); "eid" -> add("Eid Mubarak!! 🎉"); "jummah" -> add("Jumu'ah Mubarak — don't forget Al-Kahf.") }
            when (m) {
                Mood.EXCITED -> add(if (cheeky) "Look at you go, overachiever." else "What a day we're having!")
                Mood.HAPPY -> add(listOf("Nice work today.", "One more thing and we level up?", if (wise) "Small deeds, done daily, are most beloved." else "We're doing great.").random())
                Mood.BORED -> add(if (cheeky) "Are we… doing anything today?" else "Let's tick one small thing off?")
                Mood.CONTENT -> add(listOf("Bismillah — what's first today?", "I'm here if you need me.", if (wise) "Begin with the hardest thing." else "Ready when you are!").random())
                else -> {}
            }
        }
        return options.firstOrNull() ?: "…"
    }
}

/** The companion on Today: the creature, what it's saying, and (optionally) its stats. Long-press opens its settings. */
@Composable
fun CompanionCard(summary: ProgressSummary?, ambience: Ambience?, nextPrayer: Pair<String, Int>?, onChat: () -> Unit = {}, onCustomise: () -> Unit) {
    val c = LocalContext.current
    var pet by remember { mutableStateOf(Companion.load(c)) }
    if (!Companion.visible(c)) return
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val jump = remember { Animatable(0f) }
    var tapped by remember { mutableStateOf(false) }
    var hearts by remember { mutableIntStateOf(0) }
    val line = remember(pet, summary, ambience, tapped, hearts) { Companion.line(pet, summary, ambience, nextPrayer, tapped) }
    val quiet = pet.chat == "Quiet" && !tapped

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size((96 * pet.size).dp).pointerInput(pet) {
            detectTapGestures(onLongPress = { onCustomise() }, onDoubleTap = { if (pet.hatched) onChat() }, onTap = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (!pet.hatched) {
                    val next = pet.copy(taps = pet.taps + 1)
                    pet = if (next.taps >= 3) Companion.hatch(c, summary?.totalXp ?: 0) else next.also { Companion.save(c, it) }
                } else { tapped = true; hearts++ }
                scope.launch { jump.snapTo(0f); jump.animateTo(1f, spring(0.4f, 600f)); jump.animateTo(0f, spring(0.5f, 300f)) }
            })
        }.graphicsLayer { translationY = -jump.value * 26f }) {
            Creature(pet, Companion.mood(pet, summary, ambience), hearts, Modifier.size((96 * pet.size).dp))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            AnimatedVisibility(!quiet || !pet.hatched, enter = fadeIn(), exit = fadeOut()) {
                Text(if (!pet.hatched) listOf("An egg! Tap it to hatch.", "It wobbled! Keep tapping…", "Almost… one more!")[pet.taps.coerceIn(0, 2)] else line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp))
            }
            if (pet.hatched && pet.showStats) {
                val xp = Companion.xp(pet, summary); val lvl = Companion.level(xp)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pet.name} · ${pet.species} · Lv $lvl", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = { (xp % 120) / 120f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).clip(RoundedCornerShape(50)))
            }
        }
    }
    LaunchedEffect(tapped) { if (tapped) { delay(4000); tapped = false } }
}

/** Draws the creature — or its egg — with breathing, blinking, a mood face, an accessory, and hearts when tapped. */
@Composable
fun Creature(pet: Pet, mood: Companion.Mood, hearts: Int, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "pet")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "b")
    val clock by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "c")
    val heartAnim = remember { Animatable(1f) }
    LaunchedEffect(hearts) { if (hearts > 0) { heartAnim.snapTo(0f); heartAnim.animateTo(1f, tween(900)) } }
    val base = Companion.colour(pet)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (!pet.hatched) { egg(pet.taps, clock); return@Canvas }
        val squash = 1f + breathe * 0.04f
        val c = Offset(w / 2, h * 0.58f)
        val r = w * 0.30f
        // shadow
        drawOval(Color.Black.copy(alpha = 0.12f), Offset(c.x - r * 0.9f, h * 0.9f), Size(r * 1.8f, h * 0.07f))
        scale(1f / squash, squash, c) {
            when (pet.species) {                                      // ears, wings and other outline bits, drawn behind the body
                "Fox", "Cat" -> { tri(c + Offset(-r * 0.65f, -r * 0.7f), r * 0.55f, base, -15f); tri(c + Offset(r * 0.65f, -r * 0.7f), r * 0.55f, base, 15f) }
                "Bunny" -> { drawOval(base, Offset(c.x - r * 0.62f, c.y - r * 2.0f), Size(r * 0.42f, r * 1.3f)); drawOval(base, Offset(c.x + r * 0.2f, c.y - r * 2.0f), Size(r * 0.42f, r * 1.3f))
                    drawOval(Color(0xFFF7C6D0), Offset(c.x - r * 0.53f, c.y - r * 1.85f), Size(r * 0.24f, r * 1.0f)); drawOval(Color(0xFFF7C6D0), Offset(c.x + r * 0.29f, c.y - r * 1.85f), Size(r * 0.24f, r * 1.0f)) }
                "Owl" -> { tri(c + Offset(-r * 0.6f, -r * 0.85f), r * 0.35f, base, -25f); tri(c + Offset(r * 0.6f, -r * 0.85f), r * 0.35f, base, 25f) }
                "Dragon" -> { tri(c + Offset(-r * 0.45f, -r * 0.95f), r * 0.3f, Color(0xFFF5E6A8), -10f); tri(c + Offset(r * 0.45f, -r * 0.95f), r * 0.3f, Color(0xFFF5E6A8), 10f)
                    val flap = sin(clock * 2 * PI.toFloat() * 3) * 10f
                    rotate(-20f + flap, c + Offset(-r, 0f)) { drawOval(base.copy(alpha = 0.8f), Offset(c.x - r * 1.9f, c.y - r * 0.5f), Size(r * 1.1f, r * 0.7f)) }
                    rotate(20f - flap, c + Offset(r, 0f)) { drawOval(base.copy(alpha = 0.8f), Offset(c.x + r * 0.8f, c.y - r * 0.5f), Size(r * 1.1f, r * 0.7f)) } }
                "Axolotl" -> repeat(3) { i -> val y = c.y - r * 0.4f + i * r * 0.3f
                    drawOval(Color(0xFFE0607E), Offset(c.x - r * 1.45f, y), Size(r * 0.55f, r * 0.18f)); drawOval(Color(0xFFE0607E), Offset(c.x + r * 0.9f, y), Size(r * 0.55f, r * 0.18f)) }
                "Panda" -> { drawCircle(Color(0xFF222222), r * 0.28f, c + Offset(-r * 0.72f, -r * 0.72f)); drawCircle(Color(0xFF222222), r * 0.28f, c + Offset(r * 0.72f, -r * 0.72f)) }
                "Lion" -> { repeat(12) { i -> val a = i * 30f * (PI / 180).toFloat(); drawCircle(Color(0xFFB5651D), r * 0.42f, c + Offset(kotlin.math.cos(a) * r * 0.95f, sin(a) * r * 0.95f)) } }
                "Hamster" -> { drawCircle(base.copy(red = base.red * 0.85f, green = base.green * 0.8f), r * 0.2f, c + Offset(-r * 0.62f, -r * 0.8f)); drawCircle(base.copy(red = base.red * 0.85f, green = base.green * 0.8f), r * 0.2f, c + Offset(r * 0.62f, -r * 0.8f)) }
                "Turtle" -> { drawOval(Color(0xFF3E7D4E), Offset(c.x - r * 1.3f, c.y - r * 0.55f), Size(r * 2.6f, r * 1.7f))
                    repeat(3) { i -> drawCircle(Color(0xFF5E9E5E), r * 0.28f, Offset(c.x - r * 0.75f + i * r * 0.75f, c.y + r * 0.1f)) } }
                "Parrot" -> { tri(c + Offset(0f, -r * 1.05f), r * 0.32f, Color(0xFFE84A3C), 0f); tri(c + Offset(-r * 0.25f, -r * 1.0f), r * 0.26f, Color(0xFFF2C14E), -20f)
                    drawOval(Color(0xFF2F8FD8), Offset(c.x + r * 0.55f, c.y - r * 0.1f), Size(r * 0.7f, r * 1.0f)) }
                "Fox" -> {}
            }
            if (pet.species == "Fox") drawOval(base, Offset(c.x + r * 0.6f, c.y + r * 0.1f), Size(r * 1.1f, r * 0.55f))      // tail
            // body
            val bodyPath = Path().apply {
                if (pet.species == "Slime") { moveTo(c.x - r * 1.1f, c.y + r * 0.9f); cubicTo(c.x - r * 1.2f, c.y - r * 1.3f, c.x + r * 1.2f, c.y - r * 1.3f, c.x + r * 1.1f, c.y + r * 0.9f); close() }
                else addOval(Rect(c, r))
            }
            drawPath(bodyPath, base)
            // belly / face patch
            when (pet.species) {
                "Penguin", "Owl" -> drawOval(Color(0xFFF4EFE6), Offset(c.x - r * 0.6f, c.y - r * 0.35f), Size(r * 1.2f, r * 1.25f))
                "Fox" -> drawOval(Color(0xFFFFF4EA), Offset(c.x - r * 0.55f, c.y + r * 0.05f), Size(r * 1.1f, r * 0.8f))
                "Slime" -> drawOval(Color.White.copy(alpha = 0.35f), Offset(c.x - r * 0.6f, c.y - r * 0.7f), Size(r * 0.4f, r * 0.25f))
                "Panda" -> { drawOval(Color(0xFF222222), Offset(c.x - r * 0.6f, c.y - r * 0.35f), Size(r * 0.42f, r * 0.5f)); drawOval(Color(0xFF222222), Offset(c.x + r * 0.18f, c.y - r * 0.35f), Size(r * 0.42f, r * 0.5f)) }
                "Lion", "Hamster" -> drawOval(Color(0xFFFFF1DC), Offset(c.x - r * 0.45f, c.y + r * 0.05f), Size(r * 0.9f, r * 0.6f))
                "Parrot" -> tri(c + Offset(0f, r * 0.22f), r * 0.2f, Color(0xFFF2C14E), 180f)
            }
            // face
            val blink = (clock * 4200) % 3100 < 120
            val eyeY = c.y - r * 0.12f; val eyeDx = r * 0.36f
            val ink = Color(0xFF1E1E24)
            if (pet.species == "Owl") { drawCircle(Color.White, r * 0.26f, Offset(c.x - eyeDx, eyeY)); drawCircle(Color.White, r * 0.26f, Offset(c.x + eyeDx, eyeY)) }
            if (pet.accessory == "Glasses") { drawCircle(ink, r * 0.22f, Offset(c.x - eyeDx, eyeY), style = Stroke(r * 0.05f)); drawCircle(ink, r * 0.22f, Offset(c.x + eyeDx, eyeY), style = Stroke(r * 0.05f))
                drawLine(ink, Offset(c.x - eyeDx + r * 0.22f, eyeY), Offset(c.x + eyeDx - r * 0.22f, eyeY), r * 0.05f) }
            fun dotEyes(size: Float, dx: Float = 0f, dy: Float = 0f) {
                drawCircle(ink, r * size, Offset(c.x - eyeDx + dx, eyeY + dy)); drawCircle(ink, r * size, Offset(c.x + eyeDx + dx, eyeY + dy))
                drawCircle(Color.White, r * size * 0.36f, Offset(c.x - eyeDx + dx + r * 0.04f, eyeY + dy - r * 0.04f))
                drawCircle(Color.White, r * size * 0.36f, Offset(c.x + eyeDx + dx + r * 0.04f, eyeY + dy - r * 0.04f))
            }
            when {
                mood == Companion.Mood.SLEEPY || mood == Companion.Mood.CALM || (blink && mood != Companion.Mood.SURPRISED) -> { arc(Offset(c.x - eyeDx, eyeY), r * 0.12f, ink, down = true); arc(Offset(c.x + eyeDx, eyeY), r * 0.12f, ink, down = true) }
                mood == Companion.Mood.LOVE -> { heart(Offset(c.x - eyeDx, eyeY), r * 0.1f, Color(0xFFFF4F75)); heart(Offset(c.x + eyeDx, eyeY), r * 0.1f, Color(0xFFFF4F75)) }
                mood == Companion.Mood.LAUGHING -> { drawLine(ink, Offset(c.x - eyeDx - r * 0.12f, eyeY - r * 0.08f), Offset(c.x - eyeDx + r * 0.1f, eyeY), r * 0.05f)
                    drawLine(ink, Offset(c.x - eyeDx - r * 0.12f, eyeY + r * 0.08f), Offset(c.x - eyeDx + r * 0.1f, eyeY), r * 0.05f)
                    drawLine(ink, Offset(c.x + eyeDx + r * 0.12f, eyeY - r * 0.08f), Offset(c.x + eyeDx - r * 0.1f, eyeY), r * 0.05f)
                    drawLine(ink, Offset(c.x + eyeDx + r * 0.12f, eyeY + r * 0.08f), Offset(c.x + eyeDx - r * 0.1f, eyeY), r * 0.05f) }
                mood == Companion.Mood.EXCITED || mood == Companion.Mood.HAPPY || mood == Companion.Mood.PROUD -> { arc(Offset(c.x - eyeDx, eyeY + r * 0.05f), r * 0.12f, ink, down = false); arc(Offset(c.x + eyeDx, eyeY + r * 0.05f), r * 0.12f, ink, down = false) }
                mood == Companion.Mood.SURPRISED -> { drawCircle(Color.White, r * 0.17f, Offset(c.x - eyeDx, eyeY)); drawCircle(Color.White, r * 0.17f, Offset(c.x + eyeDx, eyeY)); dotEyes(0.09f) }
                mood == Companion.Mood.SHY -> dotEyes(0.1f, dx = r * 0.08f, dy = r * 0.06f)
                mood == Companion.Mood.THINKING -> dotEyes(0.1f, dx = r * 0.06f, dy = -r * 0.07f)
                mood == Companion.Mood.SILLY -> { dotEyes(0.11f, dx = -r * 0.05f); drawCircle(ink, r * 0.07f, Offset(c.x + eyeDx + r * 0.06f, eyeY + r * 0.04f)) }
                else -> dotEyes(0.11f)
            }
            // brows say a lot: grumpy slants in, worried and sad slant up
            when (mood) {
                Companion.Mood.GRUMPY -> { drawLine(ink, Offset(c.x - eyeDx - r * 0.16f, eyeY - r * 0.26f), Offset(c.x - eyeDx + r * 0.14f, eyeY - r * 0.16f), r * 0.05f)
                    drawLine(ink, Offset(c.x + eyeDx + r * 0.16f, eyeY - r * 0.26f), Offset(c.x + eyeDx - r * 0.14f, eyeY - r * 0.16f), r * 0.05f) }
                Companion.Mood.SAD, Companion.Mood.WORRIED -> { drawLine(ink, Offset(c.x - eyeDx - r * 0.15f, eyeY - r * 0.16f), Offset(c.x - eyeDx + r * 0.12f, eyeY - r * 0.26f), r * 0.045f)
                    drawLine(ink, Offset(c.x + eyeDx + r * 0.15f, eyeY - r * 0.16f), Offset(c.x + eyeDx - r * 0.12f, eyeY - r * 0.26f), r * 0.045f) }
                Companion.Mood.SURPRISED -> { arc(Offset(c.x - eyeDx, eyeY - r * 0.3f), r * 0.1f, ink, down = false); arc(Offset(c.x + eyeDx, eyeY - r * 0.3f), r * 0.1f, ink, down = false) }
                else -> {}
            }
            // cheeks and mouth / beak
            drawCircle(Color(0xFFFF8FA3).copy(alpha = 0.45f), r * 0.1f, Offset(c.x - r * 0.58f, c.y + r * 0.12f))
            drawCircle(Color(0xFFFF8FA3).copy(alpha = 0.45f), r * 0.1f, Offset(c.x + r * 0.58f, c.y + r * 0.12f))
            if (mood == Companion.Mood.SHY || mood == Companion.Mood.LOVE) {        // extra blush
                drawOval(Color(0xFFFF7A95).copy(alpha = 0.5f), Offset(c.x - r * 0.75f, c.y + r * 0.02f), Size(r * 0.34f, r * 0.18f))
                drawOval(Color(0xFFFF7A95).copy(alpha = 0.5f), Offset(c.x + r * 0.41f, c.y + r * 0.02f), Size(r * 0.34f, r * 0.18f))
            }
            if (pet.species == "Penguin" || pet.species == "Owl") tri(Offset(c.x, c.y + r * 0.12f), r * 0.14f, Color(0xFFF2A93B), 180f)
            else when (mood) {
                Companion.Mood.BORED, Companion.Mood.CALM -> drawLine(ink, Offset(c.x - r * 0.1f, c.y + r * 0.2f), Offset(c.x + r * 0.1f, c.y + r * 0.2f), r * 0.04f)
                Companion.Mood.SAD, Companion.Mood.WORRIED, Companion.Mood.GRUMPY -> arc(Offset(c.x, c.y + r * 0.26f), r * 0.09f, ink, down = false)
                Companion.Mood.SURPRISED -> drawOval(ink, Offset(c.x - r * 0.07f, c.y + r * 0.1f), Size(r * 0.14f, r * 0.18f))
                Companion.Mood.LAUGHING -> drawArc(ink, 0f, 180f, true, Offset(c.x - r * 0.16f, c.y + r * 0.02f), Size(r * 0.32f, r * 0.28f))
                Companion.Mood.SILLY -> { arc(Offset(c.x, c.y + r * 0.14f), r * 0.1f, ink, down = true); drawOval(Color(0xFFFF7A95), Offset(c.x - r * 0.02f, c.y + r * 0.2f), Size(r * 0.12f, r * 0.14f)) }
                Companion.Mood.THINKING -> drawLine(ink, Offset(c.x - r * 0.06f, c.y + r * 0.22f), Offset(c.x + r * 0.1f, c.y + r * 0.18f), r * 0.04f)
                else -> arc(Offset(c.x, c.y + r * 0.14f), r * (if (mood == Companion.Mood.EXCITED || mood == Companion.Mood.PROUD) 0.14f else 0.09f), ink, down = true)
            }
            // a tear for sad, a sweat drop for worried
            if (mood == Companion.Mood.SAD) drawOval(Color(0xFF7FC8FF), Offset(c.x - eyeDx - r * 0.04f, eyeY + r * 0.12f + (clock * 3f % 1f) * r * 0.25f), Size(r * 0.08f, r * 0.12f))
            if (mood == Companion.Mood.WORRIED) drawOval(Color(0xFF7FC8FF), Offset(c.x + r * 0.62f, c.y - r * 0.55f), Size(r * 0.1f, r * 0.15f))
            if (pet.species == "Cat") repeat(2) { i -> val y = c.y + r * (0.12f + i * 0.1f)
                drawLine(ink.copy(alpha = 0.5f), Offset(c.x - r * 0.35f, y), Offset(c.x - r * 0.8f, y - r * 0.05f), r * 0.02f)
                drawLine(ink.copy(alpha = 0.5f), Offset(c.x + r * 0.35f, y), Offset(c.x + r * 0.8f, y - r * 0.05f), r * 0.02f) }
            // accessory
            when (pet.accessory) {
                "Hat" -> { drawRect(Color(0xFF3A3F58), Offset(c.x - r * 0.4f, c.y - r * 1.55f), Size(r * 0.8f, r * 0.55f)); drawRect(Color(0xFF3A3F58), Offset(c.x - r * 0.65f, c.y - r * 1.02f), Size(r * 1.3f, r * 0.12f)) }
                "Kufi" -> drawArc(Color.White, 180f, 180f, true, Offset(c.x - r * 0.55f, c.y - r * 1.25f), Size(r * 1.1f, r * 0.7f))
                "Crown" -> { val cr = Path().apply { moveTo(c.x - r * 0.45f, c.y - r * 0.9f); lineTo(c.x - r * 0.45f, c.y - r * 1.3f); lineTo(c.x - r * 0.2f, c.y - r * 1.1f)
                    lineTo(c.x, c.y - r * 1.4f); lineTo(c.x + r * 0.2f, c.y - r * 1.1f); lineTo(c.x + r * 0.45f, c.y - r * 1.3f); lineTo(c.x + r * 0.45f, c.y - r * 0.9f); close() }
                    drawPath(cr, Color(0xFFF2C14E)) }
                "Scarf" -> { drawRoundRect(Color(0xFFD9534F), Offset(c.x - r * 0.8f, c.y + r * 0.42f), Size(r * 1.6f, r * 0.24f), androidx.compose.ui.geometry.CornerRadius(r * 0.1f))
                    drawRect(Color(0xFFD9534F), Offset(c.x + r * 0.3f, c.y + r * 0.5f), Size(r * 0.22f, r * 0.5f)) }
                "Cap" -> { drawArc(Color(0xFF2E6FD8), 180f, 180f, true, Offset(c.x - r * 0.7f, c.y - r * 1.25f), Size(r * 1.4f, r * 0.8f))
                    drawRoundRect(Color(0xFF2E6FD8), Offset(c.x - r * 0.1f, c.y - r * 0.9f), Size(r * 0.95f, r * 0.14f), androidx.compose.ui.geometry.CornerRadius(r * 0.07f)) }
                "Headphones" -> { drawArc(Color(0xFF30343F), 190f, 160f, false, Offset(c.x - r * 1.0f, c.y - r * 1.25f), Size(r * 2.0f, r * 1.6f), style = Stroke(r * 0.1f))
                    drawRoundRect(Color(0xFFE84A3C), Offset(c.x - r * 1.12f, c.y - r * 0.35f), Size(r * 0.28f, r * 0.5f), androidx.compose.ui.geometry.CornerRadius(r * 0.1f))
                    drawRoundRect(Color(0xFFE84A3C), Offset(c.x + r * 0.84f, c.y - r * 0.35f), Size(r * 0.28f, r * 0.5f), androidx.compose.ui.geometry.CornerRadius(r * 0.1f)) }
                "Bow tie" -> { tri(Offset(c.x, c.y + r * 0.62f), r * 0.28f, Color(0xFFD9342B), 90f); tri(Offset(c.x, c.y + r * 0.62f), r * 0.28f, Color(0xFFD9342B), -90f)
                    drawCircle(Color(0xFFA82520), r * 0.07f, Offset(c.x, c.y + r * 0.62f)) }
                "Party hat" -> { val ph = Path().apply { moveTo(c.x - r * 0.35f, c.y - r * 0.92f); lineTo(c.x + r * 0.05f, c.y - r * 1.75f); lineTo(c.x + r * 0.4f, c.y - r * 0.92f); close() }
                    drawPath(ph, Color(0xFF8E5CE0)); drawCircle(Color(0xFFF2C14E), r * 0.09f, Offset(c.x + r * 0.05f, c.y - r * 1.75f)) }
                "Sunglasses" -> { drawOval(ink, Offset(c.x - eyeDx - r * 0.24f, eyeY - r * 0.14f), Size(r * 0.48f, r * 0.3f)); drawOval(ink, Offset(c.x + eyeDx - r * 0.24f, eyeY - r * 0.14f), Size(r * 0.48f, r * 0.3f))
                    drawLine(ink, Offset(c.x - eyeDx + r * 0.2f, eyeY - r * 0.05f), Offset(c.x + eyeDx - r * 0.2f, eyeY - r * 0.05f), r * 0.05f) }
                "Flower" -> { repeat(5) { i -> val a = i * 72f * (PI / 180).toFloat(); drawCircle(Color(0xFFFFB5C8), r * 0.1f, Offset(c.x + r * 0.55f + kotlin.math.cos(a) * r * 0.12f, c.y - r * 0.85f + sin(a) * r * 0.12f)) }
                    drawCircle(Color(0xFFF2C14E), r * 0.07f, Offset(c.x + r * 0.55f, c.y - r * 0.85f)) }
            }
        }
        // thought bubbles, sparkles, a question mark, a little storm cloud: the rest of the expressions
        when (mood) {
            Companion.Mood.THINKING -> repeat(3) { i -> drawCircle(Color.Gray.copy(alpha = 0.6f), r * (0.05f + i * 0.03f), Offset(c.x + r * (0.7f + i * 0.2f), c.y - r * (1.0f + i * 0.25f) - sin(clock * 6.28f + i) * r * 0.04f)) }
            Companion.Mood.PROUD, Companion.Mood.EXCITED -> repeat(3) { i -> val k = (clock * 2 + i / 3f) % 1f
                val p = Offset(c.x + (i - 1) * r * 0.9f, c.y - r * (1.2f + k * 0.4f)); val s2 = r * 0.1f * (1 - k)
                drawLine(Color(0xFFFFD166), p + Offset(-s2, 0f), p + Offset(s2, 0f), r * 0.03f); drawLine(Color(0xFFFFD166), p + Offset(0f, -s2), p + Offset(0f, s2), r * 0.03f) }
            Companion.Mood.SURPRISED -> { drawLine(Color(0xFFFF6B6B), Offset(c.x + r * 0.9f, c.y - r * 1.2f), Offset(c.x + r * 0.9f, c.y - r * 0.95f), r * 0.06f)
                drawCircle(Color(0xFFFF6B6B), r * 0.035f, Offset(c.x + r * 0.9f, c.y - r * 0.85f)) }
            Companion.Mood.GRUMPY -> { drawOval(Color.Gray.copy(alpha = 0.55f), Offset(c.x - r * 0.5f, c.y - r * 1.75f), Size(r * 1.0f, r * 0.35f))
                drawLine(Color(0xFFFFC94D), Offset(c.x, c.y - r * 1.42f), Offset(c.x - r * 0.08f, c.y - r * 1.25f), r * 0.04f) }
            Companion.Mood.LOVE -> repeat(2) { i -> val k = (clock * 1.5f + i * 0.5f) % 1f
                heart(Offset(c.x + (if (i == 0) -1 else 1) * r * 0.9f, c.y - r * (0.9f + k * 0.8f)), r * 0.1f, Color(0xFFFF5C7A).copy(alpha = 1f - k)) }
            else -> {}
        }
        // sleeping z's, and hearts after a tap
        if (mood == Companion.Mood.SLEEPY) repeat(2) { i -> val k = (clock + i * 0.5f) % 1f
            drawZ(Offset(c.x + r * (0.8f + k * 0.5f), c.y - r * (1.0f + k * 1.2f)), r * 0.16f * (0.6f + k), Color.Gray.copy(alpha = 1f - k)) }
        if (heartAnim.value < 1f) repeat(3) { i -> val k = heartAnim.value
            heart(Offset(c.x + (i - 1) * r * 0.7f, c.y - r * (1.1f + k * 1.1f)), r * 0.16f, Color(0xFFFF5C7A).copy(alpha = 1f - k)) }
    }
}

private fun DrawScope.egg(taps: Int, clock: Float) {
    val c = Offset(size.width / 2, size.height * 0.55f); val r = size.width * 0.27f
    val wobble = if (taps > 0) sin(clock * 2 * PI.toFloat() * 6) * (4f + taps * 4f) else sin(clock * 2 * PI.toFloat()) * 2f
    drawOval(Color.Black.copy(alpha = 0.12f), Offset(c.x - r * 0.8f, size.height * 0.88f), Size(r * 1.6f, size.height * 0.06f))
    rotate(wobble, Offset(c.x, c.y + r)) {
        drawOval(Color(0xFFF6EBD9), Offset(c.x - r, c.y - r * 1.25f), Size(r * 2, r * 2.4f))
        repeat(4) { i -> drawCircle(Color(0xFFE8B86D).copy(alpha = 0.7f), r * (0.12f + (i % 2) * 0.06f), c + Offset((i - 1.5f) * r * 0.45f, (i % 2) * r * 0.5f - r * 0.2f)) }
        if (taps >= 1) drawLine(Color(0xFF6B5B45), c + Offset(-r * 0.4f, -r * 0.3f), c + Offset(-r * 0.1f, -r * 0.05f), 3f)
        if (taps >= 2) { drawLine(Color(0xFF6B5B45), c + Offset(-r * 0.1f, -r * 0.05f), c + Offset(r * 0.2f, -r * 0.35f), 3f); drawLine(Color(0xFF6B5B45), c + Offset(r * 0.2f, -r * 0.35f), c + Offset(r * 0.45f, -r * 0.1f), 3f) }
    }
}

private fun DrawScope.tri(tip: Offset, s: Float, color: Color, angle: Float) = rotate(angle, tip) {
    drawPath(Path().apply { moveTo(tip.x, tip.y - s); lineTo(tip.x - s * 0.6f, tip.y + s * 0.4f); lineTo(tip.x + s * 0.6f, tip.y + s * 0.4f); close() }, color)
}

private fun DrawScope.arc(c: Offset, r: Float, color: Color, down: Boolean) =
    drawArc(color, if (down) 20f else 200f, 140f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(r * 0.45f))

private fun DrawScope.heart(c: Offset, s: Float, color: Color) {
    drawPath(Path().apply { moveTo(c.x, c.y + s); cubicTo(c.x - s * 2, c.y - s * 0.4f, c.x - s * 0.6f, c.y - s * 1.6f, c.x, c.y - s * 0.5f)
        cubicTo(c.x + s * 0.6f, c.y - s * 1.6f, c.x + s * 2, c.y - s * 0.4f, c.x, c.y + s); close() }, color)
}

private fun DrawScope.drawZ(c: Offset, s: Float, color: Color) {
    drawPath(Path().apply { moveTo(c.x - s, c.y - s); lineTo(c.x + s, c.y - s); lineTo(c.x - s, c.y + s); lineTo(c.x + s, c.y + s) }, color, style = Stroke(s * 0.3f))
}

