package com.nizam.app.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A tiny 3D renderer: meshes of coloured quads, rotated, flat-shaded by one light, sorted back to
 * front and drawn on a Canvas. Enough for a Kaaba, a globe and a coin without a 10 MB engine.
 */
object Mesh3D {
    data class V(val x: Float, val y: Float, val z: Float) {
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        fun norm(): V { val l = sqrt(dot(this)).coerceAtLeast(1e-6f); return V(x / l, y / l, z / l) }
    }
    data class Face(val pts: List<V>, val color: Color)

    fun rotate(v: V, yaw: Float, pitch: Float): V {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val x1 = v.x * cy + v.z * sy; val z1 = -v.x * sy + v.z * cy
        val y2 = v.y * cp - z1 * sp; val z2 = v.y * sp + z1 * cp
        return V(x1, y2, z2)
    }

    private val LIGHT = V(-0.4f, -0.7f, -0.6f).norm()

    fun DrawScope.render(faces: List<Face>, yaw: Float, pitch: Float, scale: Float, ambient: Float = 0.35f) {
        val cx = size.width / 2; val cy = size.height / 2; val s = minOf(size.width, size.height) * 0.5f * scale
        val rotated = faces.map { f -> Face(f.pts.map { rotate(it, yaw, pitch) }, f.color) }
        rotated.sortedByDescending { f -> f.pts.sumOf { it.z.toDouble() } / f.pts.size }.forEach { f ->
            if (f.pts.size < 3) return@forEach
            val n = (f.pts[1] - f.pts[0]).cross(f.pts[2] - f.pts[0]).norm()
            if (n.z > 0.02f) return@forEach // facing away
            val lum = (ambient + (1 - ambient) * (-n.dot(LIGHT)).coerceIn(0f, 1f))
            val c = f.color
            val shaded = Color(c.red * lum, c.green * lum, c.blue * lum, c.alpha)
            val p = Path()
            f.pts.forEachIndexed { i, v -> val persp = 3.2f / (3.2f + v.z)
                val x = cx + v.x * s * persp; val y = cy + v.y * s * persp
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            p.close(); drawPath(p, shaded)
        }
    }

    /** A box split into horizontal bands so a gold band can wrap it — the Kaaba. */
    fun kaaba(): List<Face> {
        val out = mutableListOf<Face>()
        val black = Color(0xFF15161A); val gold = Color(0xFFD9B45A); val w = 0.62f; val d = 0.62f
        val bands = listOf(-0.7f to -0.25f to black, -0.25f to -0.13f to gold, -0.13f to 0.7f to black)
        fun quad(a: V, b: V, c: V, e: V, col: Color) = out.add(Face(listOf(a, b, c, e), col))
        bands.forEach { (yy, col) -> val (y0, y1) = yy
            quad(V(-w, y0, -d), V(w, y0, -d), V(w, y1, -d), V(-w, y1, -d), col)
            quad(V(w, y0, d), V(-w, y0, d), V(-w, y1, d), V(w, y1, d), col)
            quad(V(-w, y0, d), V(-w, y0, -d), V(-w, y1, -d), V(-w, y1, d), col)
            quad(V(w, y0, -d), V(w, y0, d), V(w, y1, d), V(w, y1, -d), col)
        }
        quad(V(-w, -0.7f, d), V(w, -0.7f, d), V(w, -0.7f, -d), V(-w, -0.7f, -d), Color(0xFF26272C))
        // the door, a gold panel on the front face
        quad(V(0.12f, 0.05f, -d - 0.005f), V(0.42f, 0.05f, -d - 0.005f), V(0.42f, 0.55f, -d - 0.005f), V(0.12f, 0.55f, -d - 0.005f), gold)
        return out
    }

    /** A globe with rough continents: blue sea, green land, from a coarse latitude/longitude grid. */
    fun globe(landAt: (lat: Double, lon: Double) -> Boolean): List<Face> {
        val out = mutableListOf<Face>(); val nLat = 18; val nLon = 32
        for (i in 0 until nLat) for (j in 0 until nLon) {
            val la0 = -PI / 2 + PI * i / nLat; val la1 = -PI / 2 + PI * (i + 1) / nLat
            val lo0 = 2 * PI * j / nLon; val lo1 = 2 * PI * (j + 1) / nLon
            fun p(la: Double, lo: Double) = V((cos(la) * cos(lo)).toFloat() * 0.8f, (-sin(la)).toFloat() * 0.8f, (cos(la) * sin(lo)).toFloat() * 0.8f)
            val land = landAt(Math.toDegrees((la0 + la1) / 2), Math.toDegrees((lo0 + lo1) / 2) - 180)
            out += Face(listOf(p(la0, lo0), p(la0, lo1), p(la1, lo1), p(la1, lo0)), if (land) Color(0xFF4FB477) else Color(0xFF2E7BD8))
        }
        return out
    }

    /** A coin: two faces and a ribbed edge. */
    fun coin(): List<Face> {
        val out = mutableListOf<Face>(); val n = 40; val r = 0.75f; val t = 0.1f
        val gold = Color(0xFFE8B84A); val edge = Color(0xFFC9962E)
        val front = (0 until n).map { i -> val a = 2 * PI * i / n; V((r * cos(a)).toFloat(), (r * sin(a)).toFloat(), -t) }
        val back = front.map { V(it.x, it.y, t) }
        out += Face(front, gold); out += Face(back.reversed(), gold)
        for (i in 0 until n) { val j = (i + 1) % n; out += Face(listOf(front[j], front[i], back[i], back[j]), if (i % 2 == 0) edge else gold) }
        return out
    }

    /** Very coarse continents so the globe reads as Earth. */
    fun roughLand(lat: Double, lon: Double): Boolean = when {
        lat > 72 || lat < -65 -> true
        lon in -170.0..-50.0 && lat in 10.0..70.0 -> !(lon > -100 && lat < 25 && lon < -80)
        lon in -82.0..-35.0 && lat in -55.0..12.0 -> true
        lon in -18.0..50.0 && lat in -35.0..35.0 -> true
        lon in -10.0..60.0 && lat in 35.0..70.0 -> true
        lon in 60.0..145.0 && lat in 5.0..75.0 -> !(lon in 88.0..100.0 && lat < 20)
        lon in 113.0..154.0 && lat in -40.0..-11.0 -> true
        else -> false
    }
}

/** A spinnable 3D object: drag to turn it; it drifts slowly on its own. */
@Composable
fun Object3D(faces: List<Mesh3D.Face>, modifier: Modifier, pitch: Float = -0.35f, spin: Boolean = true, fixedYaw: Float? = null, scale: Float = 0.9f) {
    var dragYaw by remember { mutableFloatStateOf(0f) }
    var dragPitch by remember { mutableFloatStateOf(0f) }
    val auto = if (spin && fixedYaw == null && !Motion.still) rememberInfiniteTransition(label = "spin").animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "y").value else 0f
    val (tx, ty) = rememberTilt()
    Canvas(modifier.pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); dragYaw += d.x / 180f; dragPitch = (dragPitch + d.y / 220f).coerceIn(-1.2f, 1.2f) } }) {
        with(Mesh3D) { render(faces, (fixedYaw ?: auto) + dragYaw + tx * 0.25f, pitch + dragPitch + ty * 0.15f, scale) }
    }
}
