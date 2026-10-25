package com.nizam.app.feature.being

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Everything it can do with its body. */
enum class Pose { IDLE, WALK, RUN, SIT, SLEEP, WAVE, JUMP, DANCE, READ, TYPE, THINK, SAD, CELEBRATE, DIZZY, HELD, CARRY, CLIMB, LOOK }

/** What its face screen shows. */
enum class Face { NORMAL, HAPPY, SLEEPY, SAD, SURPRISED, LOVE, THINKING, CONFUSED, DIZZY, WINK, FOCUSED, GRUMPY, ASLEEP }

/** Its look, grown from its own genome and changed as it lives. */
data class Look(val head: Int, val antenna: Int, val eyes: Int, val extra: Int, val hue: Float, val accent: Float, val glow: Float) {
    fun json(): JSONObject = JSONObject().put("head", head).put("antenna", antenna).put("eyes", eyes).put("extra", extra).put("hue", hue.toDouble()).put("accent", accent.toDouble()).put("glow", glow.toDouble())
    companion object {
        fun from(o: JSONObject) = Look(o.optInt("head"), o.optInt("antenna"), o.optInt("eyes"), o.optInt("extra"), o.optDouble("hue", 200.0).toFloat(), o.optDouble("accent", 30.0).toFloat(), o.optDouble("glow", 170.0).toFloat())
        fun random(r: Random) = Look(r.nextInt(3), r.nextInt(4), r.nextInt(3), r.nextInt(5), r.nextInt(360).toFloat(), r.nextInt(360).toFloat(), listOf(170f, 190f, 140f, 45f, 300f, 20f).random(r))
        val EXTRAS = listOf("nothing", "a scarf", "a bow tie", "a backpack", "headphones")
        val HEADS = listOf("boxy", "rounded", "dome")
        val ANTENNAS = listOf("a ball antenna", "two antennae", "a lightning antenna", "a glowing top light")
    }
}

object Body {
    fun look(c: Context): Look {
        val p = c.getSharedPreferences("being", 0)
        p.getString("look", null)?.let { s -> runCatching { return Look.from(JSONObject(s)) } }
        val l = Look.random(Random(p.getLong("seed", 7L)))
        p.edit().putString("look", l.json().toString()).apply()
        return l
    }
    fun setLook(c: Context, l: Look) { c.getSharedPreferences("being", 0).edit().putString("look", l.json().toString()).apply(); Being.version++ }

    /** Small change as it grows: a new accessory, colour drift, a different antenna. Returns what changed. */
    fun evolve(c: Context): String {
        val l = look(c); val r = Random(System.nanoTime())
        val (n, what) = when (r.nextInt(4)) {
            0 -> { val e = (l.extra + 1 + r.nextInt(4)) % 5; l.copy(extra = e) to "Now wearing ${Look.EXTRAS[e]}" }
            1 -> { val h = (l.hue + r.nextInt(-40, 41) + 360) % 360; l.copy(hue = h) to "My paint shifted colour" }
            2 -> { val a = (l.antenna + 1 + r.nextInt(3)) % 4; l.copy(antenna = a) to "Grew ${Look.ANTENNAS[a]}" }
            else -> { val g = listOf(170f, 190f, 140f, 45f, 300f, 20f).random(r); l.copy(glow = g, accent = (l.accent + 60) % 360) to "My lights changed colour" }
        }
        setLook(c, n); return what
    }
}

private fun hsv(h: Float, s: Float, v: Float, a: Float = 1f) = Color.hsv(((h % 360) + 360) % 360, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

/** Seconds since this appeared, ticking every frame (only the drawing re-runs). */
@Composable
fun rememberClock(): Float {
    val t by produceState(0f) { val start = withFrameNanos { it }; while (true) withFrameNanos { value = (it - start) / 1e9f } }
    return t
}

/**
 * Its body: a little pixel robot with a screen for a face, an antenna that glows with its mood, arms and legs.
 * Drawn on a 20×28 grid; [facingLeft] mirrors it, [talking] moves its mouth.
 */
@Composable
fun Robot(look: Look, pose: Pose, face: Face, modifier: Modifier = Modifier, facingLeft: Boolean = false, talking: Boolean = false, mood: Float = 0.6f) {
    val clock = produceState(0f) { val start = withFrameNanos { it }; while (true) withFrameNanos { value = (it - start) / 1e9f } }
    Canvas(modifier) { drawRobot(look, pose, face, clock.value, facingLeft, talking, mood) }
}

fun DrawScope.drawRobot(look: Look, pose: Pose, face: Face, t: Float, facingLeft: Boolean, talking: Boolean, mood: Float) {
    val u = minOf(size.width / 20f, size.height / 28f)
    val ox = (size.width - u * 20) / 2; val oy = size.height - u * 28
    val body = hsv(look.hue, 0.45f, 0.82f); val outline = hsv(look.hue, 0.6f, 0.32f); val shade = hsv(look.hue, 0.5f, 0.62f)
    val accent = hsv(look.accent, 0.7f, 0.95f); val screen = Color(0xFF0E1420)
    val glow = hsv(look.glow, 0.55f, 1f); val dark = Color(0xFF151822)

    // pose → where things go
    var bob = 0f; var tilt = 0f
    var lh = Offset(4f, 21f); var rh = Offset(16f, 21f); var lf = Offset(8f, 27f); var rf = Offset(12f, 27f)
    var lookX = 0f; var lookY = 0f
    val sway = sin(t * 1.6f) * 0.3f
    when (pose) {
        Pose.IDLE, Pose.LOOK -> { bob = sin(t * 2.2f) * 0.25f; lh = Offset(4f, 21f + sway); rh = Offset(16f, 21f - sway)
            if (pose == Pose.LOOK) lookX = sin(t * 0.9f) * 1.2f }
        Pose.WALK, Pose.RUN, Pose.CARRY -> {
            val sp = if (pose == Pose.RUN) 13f else 8f
            val ph = t * sp
            bob = -abs(sin(ph)) * (if (pose == Pose.RUN) 0.9f else 0.5f)
            lf = Offset(8f + sin(ph) * 1.6f, 27f - (cos(ph)).coerceAtLeast(0f) * 1.2f)
            rf = Offset(12f - sin(ph) * 1.6f, 27f - (-cos(ph)).coerceAtLeast(0f) * 1.2f)
            if (pose == Pose.CARRY) { lh = Offset(7f, 19f); rh = Offset(13f, 19f) }
            else { lh = Offset(4f - sin(ph) * 1.4f, 21f); rh = Offset(16f + sin(ph) * 1.4f, 21f) }
            lookX = 1f
            if (pose == Pose.RUN) tilt = 6f
        }
        Pose.SIT -> { lf = Offset(7.5f + sin(t * 3f) * 0.8f, 26.5f); rf = Offset(12.5f - sin(t * 3f + 1) * 0.8f, 26.5f); lh = Offset(5f, 22f); rh = Offset(15f, 22f); bob = sin(t * 2f) * 0.15f }
        Pose.SLEEP -> { bob = sin(t * 1.2f) * 0.3f }
        Pose.WAVE -> { bob = sin(t * 2f) * 0.2f; rh = Offset(18f + sin(t * 10f) * 1.2f, 11f) }
        Pose.JUMP, Pose.CELEBRATE -> {
            val j = abs(sin(t * 5.5f)); bob = -j * 4f
            lh = Offset(2.5f, 12f + (1 - j) * 2); rh = Offset(17.5f, 12f + (1 - j) * 2)
            lf = Offset(8.5f, 27f - j * 0.8f); rf = Offset(11.5f, 27f - j * 0.8f)
        }
        Pose.DANCE -> {
            val ph = t * 6f; bob = -abs(sin(ph)) * 0.9f; tilt = sin(ph * 0.5f) * 9f
            lh = if (sin(ph * 0.5f) > 0) Offset(2.5f, 12f) else Offset(4f, 21f); rh = if (sin(ph * 0.5f) <= 0) Offset(17.5f, 12f) else Offset(16f, 21f)
            lf = Offset(7.5f + sin(ph) * 0.8f, 27f); rf = Offset(12.5f + sin(ph) * 0.8f, 27f)
        }
        Pose.READ -> { lh = Offset(7.5f, 20f); rh = Offset(12.5f, 20f); lookY = 1f; bob = sin(t * 1.5f) * 0.15f }
        Pose.TYPE -> { lh = Offset(7f, 22f - abs(sin(t * 14f)) * 0.6f); rh = Offset(13f, 22f - abs(sin(t * 14f + 1.3f)) * 0.6f); lookY = 1f }
        Pose.THINK -> { rh = Offset(12f, 14.5f); lh = Offset(4.5f, 21f); tilt = -5f + sin(t) * 2f; lookX = 1f; lookY = -1f }
        Pose.SAD -> { bob = 1f; lh = Offset(5f, 23f); rh = Offset(15f, 23f); lookY = 1f; tilt = sin(t * 0.8f) * 2f }
        Pose.DIZZY -> { tilt = sin(t * 7f) * 12f; lh = Offset(3f, 19f + sin(t * 9f)); rh = Offset(17f, 19f - sin(t * 9f)) }
        Pose.HELD -> { lh = Offset(4f, 13f); rh = Offset(16f, 13f); lf = Offset(8f + sin(t * 8f) * 1.5f, 27f); rf = Offset(12f - sin(t * 8f) * 1.5f, 27f); tilt = sin(t * 3f) * 6f }
        Pose.CLIMB -> { val ph = t * 7f; lh = Offset(4f, 10f + sin(ph) * 2f); rh = Offset(16f, 10f - sin(ph) * 2f); lf = Offset(8f, 27f - (sin(ph)).coerceAtLeast(0f) * 2); rf = Offset(12f, 27f - (-sin(ph)).coerceAtLeast(0f) * 2) }
    }

    fun px(x: Float, y: Float, w: Float, h: Float, c: Color) = drawRect(c, Offset(ox + x * u, oy + (y + bob) * u), Size(w * u + 0.6f, h * u + 0.6f))
    fun limb(a: Offset, b: Offset, c: Color) = drawLine(c, Offset(ox + a.x * u, oy + (a.y + bob) * u), Offset(ox + b.x * u, oy + (b.y + bob) * u), strokeWidth = u * 1.8f, cap = StrokeCap.Square)

    val pivot = Offset(ox + 10 * u, oy + 27 * u)
    val lying = pose == Pose.SLEEP
    scale((if (facingLeft) -1f else 1f) * (if (lying) 0.72f else 1f), if (lying) 0.72f else 1f, pivot) {
        rotate(if (lying) -90f else tilt, if (lying) Offset(ox + 10 * u, oy + 14 * u) else pivot) {
            translate(0f, 0f) {
                // backpack behind
                if (look.extra == 3) px(13.5f, 16f, 3f, 5f, accent.copy(alpha = 0.9f))
                // legs
                limb(Offset(8f, 22.5f), lf.copy(y = lf.y - 1f), shade); limb(Offset(12f, 22.5f), rf.copy(y = rf.y - 1f), shade)
                px(lf.x - 1.5f, lf.y - 1f, 3f, 1f, outline); px(rf.x - 1.5f, rf.y - 1f, 3f, 1f, outline)
                // arm behind the body when it's reading/typing goes first
                // torso
                px(5f, 15f, 10f, 8f, outline); px(6f, 16f, 8f, 6f, body); px(6f, 21f, 8f, 1f, shade)
                px(8f, 17f, 4f, 2f, accent); px(9.4f, 17.6f, 1.2f, 0.8f, glow.copy(alpha = 0.5f + 0.5f * abs(sin(t * 2.5f))))
                // neck
                px(9f, 14f, 2f, 1f, outline)
                // head
                when (look.head) {
                    0 -> { px(3f, 4f, 14f, 10f, outline); px(4f, 5f, 12f, 8f, body) }
                    1 -> { px(4f, 4f, 12f, 10f, outline); px(3f, 5f, 14f, 8f, outline); px(4f, 5f, 12f, 8f, body) }
                    else -> { px(6f, 3f, 8f, 1f, outline); px(4f, 4f, 12f, 10f, outline); px(3f, 6f, 14f, 7f, outline); px(5f, 4f, 10f, 1f, body); px(4f, 5f, 12f, 8f, body) }
                }
                px(4f, 12f, 12f, 1f, shade)
                // ears / bolts
                px(2f, 7f, 1f, 3f, accent); px(17f, 7f, 1f, 3f, accent)
                if (look.extra == 4) { px(1.5f, 5f, 1.5f, 6f, dark); px(17f, 5f, 1.5f, 6f, dark); px(2f, 2.6f, 16f, 0.9f, dark) }
                // antenna, its light follows its mood
                val light = hsv(look.glow, 0.6f, 0.6f + 0.4f * mood, 0.55f + 0.45f * abs(sin(t * (1.2f + mood * 2.5f))))
                val top = if (look.head == 2) 3f else 4f
                when (look.antenna) {
                    0 -> { px(9.5f, top - 2.5f, 1f, 2.5f, outline); px(9f, top - 4f, 2f, 2f, light) }
                    1 -> { px(6.5f, top - 2f, 1f, 2f, outline); px(12.5f, top - 2f, 1f, 2f, outline); px(6f, top - 3f, 1.6f, 1.4f, light); px(12.4f, top - 3f, 1.6f, 1.4f, light) }
                    2 -> { px(9.5f, top - 1f, 1f, 1f, outline); px(10.2f, top - 2f, 1f, 1f, outline); px(9.3f, top - 3f, 1f, 1f, outline); px(10f, top - 4f, 1.2f, 1.2f, light) }
                    else -> px(7f, top - 1f, 6f, 1f, light)
                }
                // face screen
                px(5f, 6f, 10f, 6f, screen)
                drawFace(face, t, talking, look, glow, screen, lookX, lookY, ::px, mood)
                // scarf / bow tie
                if (look.extra == 1) { px(5.5f, 13.6f, 9f, 1.6f, accent); px(12f, 15f, 1.6f, 3f, accent) }
                if (look.extra == 2) { px(8f, 14.2f, 1.6f, 1.4f, accent); px(10.4f, 14.2f, 1.6f, 1.4f, accent); px(9.4f, 14.5f, 1.2f, 0.9f, outline) }
                // props
                if (pose == Pose.READ) { px(6.5f, 18f, 7f, 4f, hsv(look.accent + 180, 0.6f, 0.7f)); px(9.8f, 18f, 0.4f, 4f, dark); px(7f, 18.5f, 2.5f, 0.4f, Color.White.copy(alpha = 0.6f)); px(10.5f, 18.5f, 2.5f, 0.4f, Color.White.copy(alpha = 0.6f)) }
                if (pose == Pose.TYPE) { px(5f, 18.5f, 10f, 4f, Color(0xFF3A4152)); px(5.6f, 19f, 8.8f, 3f, glow.copy(alpha = 0.35f)); px(4f, 22.5f, 12f, 1f, Color(0xFF555E73)) }
                if (pose == Pose.CARRY) { px(6f, 16.5f, 8f, 4.5f, Color(0xFFB07A45)); px(6f, 18.2f, 8f, 0.6f, Color(0xFF7A5230)) }
                // arms over the body
                limb(Offset(5f, 16f), lh, shade); limb(Offset(15f, 16f), rh, shade)
                px(lh.x - 1f, lh.y - 1f, 2f, 2f, body); px(rh.x - 1f, rh.y - 1f, 2f, 2f, body)
            }
        }
    }
}

private fun DrawScope.drawFace(face: Face, t: Float, talking: Boolean, look: Look, g: Color, screen: Color, lx: Float, ly: Float,
                               px: (Float, Float, Float, Float, Color) -> Unit, mood: Float) {
    val dx = lx.coerceIn(-1.2f, 1.2f); val dy = ly.coerceIn(-1f, 1f)
    val blink = (t % 4.3f) < 0.13f
    val ex1 = 6.8f + dx; val ex2 = 11.2f + dx; val ey = 7f + dy * 0.6f
    fun eyes(w: Float = 2f, h: Float = 2f, y: Float = ey) {
        if (look.eyes == 2) { px(6f + dx, y + 0.4f, 8f, 1.2f, g); return }
        if (look.eyes == 1) { px(ex1 + 0.5f, y, 1f, h, g); px(ex1, y + 0.5f, w, 1f, g); px(ex2 + 0.5f, y, 1f, h, g); px(ex2, y + 0.5f, w, 1f, g); return }
        px(ex1, y, w, h, g); px(ex2, y, w, h, g)
    }
    fun mouth() {
        if (talking) { val o = 0.4f + abs(sin(t * 16f)) * 1.1f; px(8.6f + dx * 0.5f, 10.2f, 2.8f, o, g); return }
        when {
            mood > 0.65f -> { px(8f, 10f, 1f, 0.8f, g); px(9f, 10.6f, 2f, 0.8f, g); px(11f, 10f, 1f, 0.8f, g) }
            mood < 0.35f -> { px(8f, 10.8f, 1f, 0.8f, g); px(9f, 10.2f, 2f, 0.8f, g); px(11f, 10.8f, 1f, 0.8f, g) }
            else -> px(8.8f, 10.4f, 2.4f, 0.7f, g)
        }
    }
    when (face) {
        Face.NORMAL -> { if (blink) eyes(2f, 0.5f, ey + 0.8f) else eyes(); mouth() }
        Face.HAPPY -> { listOf(ex1, ex2).forEach { x -> px(x - 0.3f, ey + 1f, 0.9f, 0.9f, g); px(x + 0.6f, ey + 0.2f, 0.9f, 0.9f, g); px(x + 1.5f, ey + 1f, 0.9f, 0.9f, g) }
            if (talking) mouth() else { px(8f, 9.8f, 1f, 0.8f, g); px(9f, 10.5f, 2f, 0.9f, g); px(11f, 9.8f, 1f, 0.8f, g) } }
        Face.SLEEPY, Face.ASLEEP -> { px(ex1, ey + 1f, 2f, 0.5f, g.copy(alpha = 0.7f)); px(ex2, ey + 1f, 2f, 0.5f, g.copy(alpha = 0.7f))
            if (face == Face.SLEEPY) px(9.3f, 10.4f, 1.4f, 0.6f + abs(sin(t * 1.4f)) * 0.6f, g.copy(alpha = 0.7f)) }
        Face.SAD -> { px(ex1, ey + 0.8f, 2f, 1.4f, g); px(ex2, ey + 0.8f, 2f, 1.4f, g); px(ex1 - 0.3f, ey - 0.2f, 1f, 0.5f, g); px(ex2 + 1.3f, ey - 0.2f, 1f, 0.5f, g)
            if (talking) mouth() else { px(8f, 10.9f, 1f, 0.7f, g); px(9f, 10.3f, 2f, 0.7f, g); px(11f, 10.9f, 1f, 0.7f, g) }
            if ((t % 3f) < 1.5f) px(ex1 + 0.4f, ey + 2.4f + (t % 1.5f), 0.6f, 0.7f, Color(0xFF7FC8FF)) }
        Face.SURPRISED -> { px(ex1 - 0.2f, ey - 0.3f, 2.4f, 2.4f, g); px(ex2 - 0.2f, ey - 0.3f, 2.4f, 2.4f, g); px(9.2f, 10f, 1.6f, 1.6f, g); px(9.6f, 10.4f, 0.8f, 0.8f, screen) }
        Face.LOVE -> { val pink = Color(0xFFFF7AB6); val b = 1f + abs(sin(t * 5f)) * 0.15f
            listOf(ex1 - 0.4f, ex2 - 0.4f).forEach { x -> px(x, ey, 1f * b, 1f, pink); px(x + 1.8f, ey, 1f * b, 1f, pink); px(x, ey + 0.8f, 2.8f * b, 1f, pink); px(x + 0.9f, ey + 1.7f, 1f, 0.9f, pink) }
            mouth() }
        Face.THINKING -> { eyes(2f, 1.4f, ey - 0.4f); (0..2).forEach { i -> val on = ((t * 3).toInt() % 3) == i; px(7.8f + i * 1.6f, if (on) 9.9f else 10.4f, 0.9f, 0.9f, g.copy(alpha = if (on) 1f else 0.5f)) } }
        Face.CONFUSED -> { px(ex1, ey + 0.4f, 2f, 1.6f, g); px(8.6f, 6.7f, 2.4f, 0.8f, g); px(10.8f, 7.4f, 0.8f, 1.2f, g); px(9.6f, 8.4f, 1f, 0.9f, g); px(9.6f, 10f, 1f, 0.9f, g) }
        Face.DIZZY -> { listOf(ex1, ex2).forEach { x -> px(x - 0.4f, ey - 0.4f, 2.8f, 2.8f, g); px(x + 0.3f, ey + 0.3f, 1.4f, 1.4f, screen); val a = t * 9f; px(x + 0.7f + cos(a) * 0.5f, ey + 0.7f + sin(a) * 0.5f, 0.6f, 0.6f, g) }
            px(8.4f, 10.4f, 3.2f, 0.6f, g) }
        Face.WINK -> { px(ex1, ey, 2f, 2f, g); px(ex2, ey + 0.9f, 2f, 0.5f, g); mouth() }
        Face.FOCUSED -> { px(ex1, ey + 0.6f, 2f, 1f, g); px(ex2, ey + 0.6f, 2f, 1f, g); if (talking) mouth() else px(9f, 10.4f, 2f, 0.5f, g) }
        Face.GRUMPY -> { px(ex1, ey + 0.8f, 2f, 1.2f, g); px(ex2, ey + 0.8f, 2f, 1.2f, g); px(ex1 + 1f, ey - 0.2f, 1.2f, 0.5f, g); px(ex2 - 0.2f, ey - 0.2f, 1.2f, 0.5f, g)
            if (talking) mouth() else px(8.6f, 10.6f, 2.8f, 0.5f, g) }
    }
}
