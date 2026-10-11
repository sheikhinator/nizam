package com.nizam.app.feature.hq

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// A small 3D renderer for Atlas HQ, in plain Kotlin. Triangles are grouped into objects; objects are
// drawn far to near, and each object's own triangles far to near, which keeps furniture-sized scenes
// correct without a depth buffer. Triangles are clipped at the near plane, shaded per corner (soft
// light, contact shadow near floors, distance haze) and handed to the canvas in one call.

/** A 4×4 transform (row-major); points are columns: p' = M·p. */
class M4(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)) {
    fun mul(o: M4): M4 {
        val r = FloatArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += m[i * 4 + k] * o.m[k * 4 + j]
            r[i * 4 + j] = s
        }
        return M4(r)
    }
    companion object {
        val I = M4()
        fun t(x: Float, y: Float, z: Float) = M4(floatArrayOf(1f, 0f, 0f, x, 0f, 1f, 0f, y, 0f, 0f, 1f, z, 0f, 0f, 0f, 1f))
        fun s(x: Float, y: Float, z: Float) = M4(floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f))
        fun ry(a: Float): M4 { val c = cos(a); val s = sin(a); return M4(floatArrayOf(c, 0f, s, 0f, 0f, 1f, 0f, 0f, -s, 0f, c, 0f, 0f, 0f, 0f, 1f)) }
        fun rx(a: Float): M4 { val c = cos(a); val s = sin(a); return M4(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, -s, 0f, 0f, s, c, 0f, 0f, 0f, 0f, 1f)) }
        fun rz(a: Float): M4 { val c = cos(a); val s = sin(a); return M4(floatArrayOf(c, -s, 0f, 0f, s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)) }
    }
}

const val EMIT: Int = 1      // lit by itself (screens, lamps, windows)
const val TWO: Int = 2       // seen from both sides

/** Triangles in world space, grouped into objects (one per shape added). */
class Mesh(cap: Int = 256) {
    var n = 0
    var v = FloatArray(cap * 9)          // corners
    var vn = FloatArray(cap * 9)         // a normal per corner (smooth shading)
    var fn = FloatArray(cap * 3)         // the face normal (culling)
    var col = IntArray(cap)
    var fl = IntArray(cap)
    var obj = IntArray(cap)
    var on = 0
    var oc = FloatArray(64 * 3)          // object centres
    var ob = FloatArray(64)              // object bias: metres to pull it towards the camera
    var mat: M4 = M4.I
    /** Objects added while this is set are drawn as if this much closer (things resting on others). */
    var bias = 0f
    private var level = 0
    private var first = 0

    fun clear() { n = 0; on = 0; mat = M4.I; bias = 0f; level = 0 }
    private fun grow() {
        val c = col.size * 2
        v = v.copyOf(c * 9); vn = vn.copyOf(c * 9); fn = fn.copyOf(c * 3); col = col.copyOf(c); fl = fl.copyOf(c); obj = obj.copyOf(c)
    }
    fun begin() { if (level++ == 0) first = n }
    fun end() {
        if (--level != 0 || n <= first) return
        if (on == ob.size) { oc = oc.copyOf(on * 6); ob = ob.copyOf(on * 2) }
        var sx = 0f; var sy = 0f; var sz = 0f
        for (t in first until n) { val o = t * 9; sx += v[o] + v[o + 3] + v[o + 6]; sy += v[o + 1] + v[o + 4] + v[o + 7]; sz += v[o + 2] + v[o + 5] + v[o + 8]; obj[t] = on }
        val k = 3f * (n - first)
        oc[on * 3] = sx / k; oc[on * 3 + 1] = sy / k; oc[on * 3 + 2] = sz / k; ob[on] = bias
        on++
    }
    inline fun with(t: M4, block: () -> Unit) { val old = mat; mat = old.mul(t); block(); mat = old }

    private fun xf(i: Int, arr: FloatArray, x: Float, y: Float, z: Float, point: Boolean) {
        val m = mat.m
        if (point) {
            arr[i] = m[0] * x + m[1] * y + m[2] * z + m[3]; arr[i + 1] = m[4] * x + m[5] * y + m[6] * z + m[7]; arr[i + 2] = m[8] * x + m[9] * y + m[10] * z + m[11]
        } else {
            var a = m[0] * x + m[1] * y + m[2] * z; var b = m[4] * x + m[5] * y + m[6] * z; var c = m[8] * x + m[9] * y + m[10] * z
            val l = sqrt(a * a + b * b + c * c).takeIf { it > 1e-6f } ?: 1f
            a /= l; b /= l; c /= l
            arr[i] = a; arr[i + 1] = b; arr[i + 2] = c
        }
    }

    /** One triangle; [n] holds a normal per corner (9 numbers), or null for a flat face. */
    fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float,
            nx: Float, ny: Float, nz: Float, color: Int, flags: Int = 0, n9: FloatArray? = null) {
        begin()
        if (n == col.size) grow()
        val o = n * 9
        xf(o, v, ax, ay, az, true); xf(o + 3, v, bx, by, bz, true); xf(o + 6, v, cx, cy, cz, true)
        xf(n * 3, fn, nx, ny, nz, false)
        if (n9 == null) { for (k in 0 until 3) { vn[o + k * 3] = fn[n * 3]; vn[o + k * 3 + 1] = fn[n * 3 + 1]; vn[o + k * 3 + 2] = fn[n * 3 + 2] } }
        else { xf(o, vn, n9[0], n9[1], n9[2], false); xf(o + 3, vn, n9[3], n9[4], n9[5], false); xf(o + 6, vn, n9[6], n9[7], n9[8], false) }
        col[n] = color; fl[n] = flags
        n++
        end()
    }
    private val q9 = FloatArray(9)
    fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
             nx: Float, ny: Float, nz: Float, color: Int, flags: Int = 0, na: FloatArray? = null) {
        begin()
        if (na == null) {
            tri(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz, color, flags)
            tri(ax, ay, az, cx, cy, cz, dx, dy, dz, nx, ny, nz, color, flags)
        } else {   // na: a normal per corner a, b, c, d (12 numbers)
            q9[0] = na[0]; q9[1] = na[1]; q9[2] = na[2]; q9[3] = na[3]; q9[4] = na[4]; q9[5] = na[5]; q9[6] = na[6]; q9[7] = na[7]; q9[8] = na[8]
            tri(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz, color, flags, q9)
            q9[3] = na[6]; q9[4] = na[7]; q9[5] = na[8]; q9[6] = na[9]; q9[7] = na[10]; q9[8] = na[11]
            tri(ax, ay, az, cx, cy, cz, dx, dy, dz, nx, ny, nz, color, flags, q9)
        }
        end()
    }

    /** A box centred on (x, y, z). Faces bigger than [cell] are cut up so they sort and clip well. */
    fun box(w: Float, h: Float, d: Float, x: Float, y: Float, z: Float, color: Int, flags: Int = 0, bottom: Boolean = false) {
        begin()
        val a = w / 2; val b = h / 2; val c = d / 2
        val x0 = x - a; val x1 = x + a; val y0 = y - b; val y1 = y + b; val z0 = z - c; val z1 = z + c
        quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0f, 1f, 0f, color, flags)              // top
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, color, flags)              // front
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f, color, flags)             // back
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f, color, flags)              // right
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f, color, flags)             // left
        if (bottom) quad(x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, 0f, -1f, 0f, color, flags)
        end()
    }

    /** A cylinder (a cone when [rt] is 0) centred on (x, y, z), smooth-shaded round its side. */
    fun cyl(rt: Float, rb: Float, h: Float, x: Float, y: Float, z: Float, color: Int, seg: Int = 8, flags: Int = 0) {
        begin()
        val y0 = y - h / 2; val y1 = y + h / 2
        val k = (rb - rt) / h * 0.5f
        val na = FloatArray(12)
        for (i in 0 until seg) {
            val a0 = i * 6.2831855f / seg; val a1 = (i + 1) * 6.2831855f / seg; val am = (a0 + a1) / 2
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            na[0] = c0; na[1] = k; na[2] = s0; na[3] = c1; na[4] = k; na[5] = s1; na[6] = c1; na[7] = k; na[8] = s1; na[9] = c0; na[10] = k; na[11] = s0
            quad(x + c0 * rb, y0, z + s0 * rb, x + c1 * rb, y0, z + s1 * rb, x + c1 * rt, y1, z + s1 * rt, x + c0 * rt, y1, z + s0 * rt,
                cos(am), k, sin(am), color, flags, na)
            if (rt > 0f) tri(x, y1, z, x + c1 * rt, y1, z + s1 * rt, x + c0 * rt, y1, z + s0 * rt, 0f, 1f, 0f, color, flags)
        }
        end()
    }

    /** A sphere (squashed by [sy]) with smooth shading. */
    fun sphere(r: Float, x: Float, y: Float, z: Float, color: Int, seg: Int = 10, rings: Int = 6, sy: Float = 1f, flags: Int = 0,
               lat0: Float = -1.5707964f, lat1: Float = 1.5707964f) {
        begin()
        val na = FloatArray(12)
        for (j in 0 until rings) {
            val p0 = lat0 + (lat1 - lat0) * j / rings; val p1 = lat0 + (lat1 - lat0) * (j + 1) / rings
            for (i in 0 until seg) {
                val a0 = i * 6.2831855f / seg; val a1 = (i + 1) * 6.2831855f / seg
                val pm = (p0 + p1) / 2; val am = (a0 + a1) / 2
                fun nrm(o: Int, p: Float, a: Float) { na[o] = cos(p) * cos(a); na[o + 1] = sin(p) / sy; na[o + 2] = cos(p) * sin(a) }
                nrm(0, p0, a0); nrm(3, p0, a1); nrm(6, p1, a1); nrm(9, p1, a0)
                quad(x + cos(p0) * cos(a0) * r, y + sin(p0) * r * sy, z + cos(p0) * sin(a0) * r,
                    x + cos(p0) * cos(a1) * r, y + sin(p0) * r * sy, z + cos(p0) * sin(a1) * r,
                    x + cos(p1) * cos(a1) * r, y + sin(p1) * r * sy, z + cos(p1) * sin(a1) * r,
                    x + cos(p1) * cos(a0) * r, y + sin(p1) * r * sy, z + cos(p1) * sin(a0) * r,
                    cos(pm) * cos(am), sin(pm) / sy, cos(pm) * sin(am), color, flags, na)
            }
        }
        end()
    }

    /** A flat rectangle facing up, cut into tiles so it clips well close to the camera. */
    fun floor(w: Float, d: Float, x: Float, y: Float, z: Float, color: Int, tile: Float = 1f, flags: Int = 0) {
        begin()
        val nx = max(1, (w / tile).toInt()); val nz = max(1, (d / tile).toInt())
        val tw = w / nx; val td = d / nz
        for (i in 0 until nx) for (k in 0 until nz) {
            val x0 = x - w / 2 + i * tw; val z0 = z - d / 2 + k * td
            quad(x0, y, z0, x0 + tw, y, z0, x0 + tw, y, z0 + td, x0, y, z0 + td, 0f, 1f, 0f, color, flags)
        }
        end()
    }
    /** A flat disc facing up. */
    fun disc(r: Float, x: Float, y: Float, z: Float, color: Int, seg: Int = 12, flags: Int = 0, sx: Float = 1f) {
        begin()
        for (i in 0 until seg) {
            val a0 = i * 6.2831855f / seg; val a1 = (i + 1) * 6.2831855f / seg
            tri(x, y, z, x + cos(a1) * r * sx, y, z + sin(a1) * r, x + cos(a0) * r * sx, y, z + sin(a0) * r, 0f, 1f, 0f, color, flags)
        }
        end()
    }
    /** A rectangle standing up, facing +z. */
    fun front(w: Float, h: Float, x: Float, y: Float, z: Float, color: Int, flags: Int = 0) =
        quad(x - w / 2, y - h / 2, z, x + w / 2, y - h / 2, z, x + w / 2, y + h / 2, z, x - w / 2, y + h / 2, z, 0f, 0f, 1f, color, flags)
    /** A rectangle standing up, facing +x. */
    fun side(d: Float, h: Float, x: Float, y: Float, z: Float, color: Int, flags: Int = 0) =
        quad(x, y - h / 2, z + d / 2, x, y - h / 2, z - d / 2, x, y + h / 2, z - d / 2, x, y + h / 2, z + d / 2, 1f, 0f, 0f, color, flags)
}

/** Sun, sky, room light, contact shadow and haze. */
class Light(var sx: Float = 0.5f, var sy: Float = 0.8f, var sz: Float = 0.3f,
            var sun: Int = 0xFFFFFF, var sunK: Float = 0.75f, var amb: Int = 0xDDE6F5, var ambK: Float = 0.55f) {
    var indoor = false
    var storey = 3.2f
    var fog = 0xDBEAFE; var fogStart = 45f; var fogEnd = 170f; var fogMax = 0.55f
    fun norm() { val l = sqrt(sx * sx + sy * sy + sz * sz); sx /= l; sy /= l; sz /= l }
}

class Cam {
    var ex = 0f; var ey = 0f; var ez = 0f
    var fx = 0f; var fy = 0f; var fz = -1f
    var rx = 1f; var ry = 0f; var rz = 0f
    var ux = 0f; var uy = 1f; var uz = 0f
    var w = 1f; var h = 1f; var fov = 30f; var near = 0.2f
    val focal get() = (h / 2) / tan(Math.toRadians(fov / 2.0).toFloat())

    fun look(x: Float, y: Float, z: Float, tx: Float, ty: Float, tz: Float) {
        ex = x; ey = y; ez = z
        fx = tx - x; fy = ty - y; fz = tz - z
        var l = sqrt(fx * fx + fy * fy + fz * fz); fx /= l; fy /= l; fz /= l
        rx = -fz; ry = 0f; rz = fx
        l = sqrt(rx * rx + rz * rz).takeIf { it > 1e-5f } ?: 1f; rx /= l; rz /= l
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx
    }
    /** Screen x, y and depth of a point; depth ≤ 0 means behind the camera. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray) {
        val dx = x - ex; val dy = y - ey; val dz = z - ez
        val d = dx * fx + dy * fy + dz * fz
        out[2] = d
        if (d <= 0.01f) { out[0] = -1e5f; out[1] = -1e5f; return }
        val f = focal / d
        out[0] = w / 2 + (dx * rx + dy * ry + dz * rz) * f
        out[1] = h / 2 - (dx * ux + dy * uy + dz * uz) * f
    }
}

/** Everything ready for one canvas call: screen corners and a colour per corner. */
class Frame {
    var verts = FloatArray(6 * 4096)
    var colors = IntArray(3 * 4096)
    var tris = 0
    fun clear() { tris = 0 }
    fun ensure(extra: Int) {
        val need = tris + extra
        if (need * 6 > verts.size) { val c = max(need, tris * 2); verts = verts.copyOf(c * 6); colors = colors.copyOf(c * 3) }
    }
}

class Renderer {
    val frame = Frame()
    private var sx = FloatArray(6 * 2048)
    private var cc = IntArray(3 * 2048)
    private var keys = LongArray(2048)
    private var cnt = 0
    private var od = FloatArray(256)
    // one triangle in camera space: x, y, z and colour per corner, and the clipped polygon (up to 4 corners)
    private val px = FloatArray(3); private val py = FloatArray(3); private val pz = FloatArray(3); private val pc = IntArray(3)
    private val qx = FloatArray(4); private val qy = FloatArray(4); private val qz = FloatArray(4); private val qc = IntArray(4)

    fun begin() { frame.clear() }

    /** Projects, shades and sorts one layer of meshes, then appends it to the frame (far to near). */
    fun layer(cam: Cam, light: Light, vararg meshes: Mesh) {
        cnt = 0
        val focal = cam.focal; val hw = cam.w / 2; val hh = cam.h / 2; val near = cam.near
        val margin = 60f
        for (mi in meshes.indices) {
            val m = meshes[mi]
            if (od.size < m.on) od = FloatArray(m.on * 2)
            for (o in 0 until m.on) od[o] = (m.oc[o * 3] - cam.ex) * cam.fx + (m.oc[o * 3 + 1] - cam.ey) * cam.fy + (m.oc[o * 3 + 2] - cam.ez) * cam.fz - m.ob[o]
            val v = m.v
            for (t in 0 until m.n) {
                val o = t * 9
                val fl = m.fl[t]
                val nx = m.fn[t * 3]; val ny = m.fn[t * 3 + 1]; val nz = m.fn[t * 3 + 2]
                val facing = nx * (cam.ex - v[o]) + ny * (cam.ey - v[o + 1]) + nz * (cam.ez - v[o + 2])
                if (fl and TWO == 0 && facing <= 0f) continue
                val flip = facing < 0f
                var inside = 0
                for (k in 0 until 3) {
                    val dx = v[o + k * 3] - cam.ex; val dy = v[o + k * 3 + 1] - cam.ey; val dz = v[o + k * 3 + 2] - cam.ez
                    px[k] = dx * cam.rx + dy * cam.ry + dz * cam.rz
                    py[k] = dx * cam.ux + dy * cam.uy + dz * cam.uz
                    pz[k] = dx * cam.fx + dy * cam.fy + dz * cam.fz
                    if (pz[k] >= near) inside++
                }
                if (inside == 0) continue
                for (k in 0 until 3) pc[k] = shade(m.col[t], fl, m.vn[o + k * 3], m.vn[o + k * 3 + 1], m.vn[o + k * 3 + 2], flip, v[o + k * 3 + 1], pz[k], light)
                // clip against the near plane
                var qn = 0
                if (inside == 3) { for (k in 0 until 3) { qx[k] = px[k]; qy[k] = py[k]; qz[k] = pz[k]; qc[k] = pc[k] }; qn = 3 }
                else for (k in 0 until 3) {
                    val j = (k + 1) % 3
                    val ki = pz[k] >= near; val ji = pz[j] >= near
                    if (ki) { qx[qn] = px[k]; qy[qn] = py[k]; qz[qn] = pz[k]; qc[qn] = pc[k]; qn++ }
                    if (ki != ji) {
                        val s = (near - pz[k]) / (pz[j] - pz[k])
                        qx[qn] = px[k] + (px[j] - px[k]) * s; qy[qn] = py[k] + (py[j] - py[k]) * s; qz[qn] = near
                        qc[qn] = lerpColor(pc[k], pc[j], s); qn++
                    }
                }
                val depthO = od[m.obj[t]]
                var i = 1
                while (i + 1 < qn) {
                    emit(0, i, i + 1, focal, hw, hh, margin, cam, depthO, mi)
                    i++
                }
            }
        }
        java.util.Arrays.sort(keys, 0, cnt)
        frame.ensure(cnt)
        val out = frame.verts; val oc = frame.colors
        var t = frame.tris
        for (i in cnt - 1 downTo 0) {
            val idx = (keys[i] and 0x1FFFFFL).toInt()
            System.arraycopy(sx, idx * 6, out, t * 6, 6)
            System.arraycopy(cc, idx * 3, oc, t * 3, 3)
            t++
        }
        frame.tris = t
    }

    private fun emit(a: Int, b: Int, c: Int, focal: Float, hw: Float, hh: Float, margin: Float, cam: Cam, depthO: Float, mi: Int) {
        if (cnt * 3 + 3 > cc.size) { sx = sx.copyOf(sx.size * 2); cc = cc.copyOf(cc.size * 2); keys = keys.copyOf(keys.size * 2) }
        val so = cnt * 6
        var minX = 1e9f; var maxX = -1e9f; var minY = 1e9f; var maxY = -1e9f
        for (k in 0 until 3) {
            val i = if (k == 0) a else if (k == 1) b else c
            val f = focal / qz[i]
            val x = hw + qx[i] * f; val y = hh - qy[i] * f
            sx[so + k * 2] = x; sx[so + k * 2 + 1] = y; cc[cnt * 3 + k] = qc[i]
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < -margin || minX > cam.w + margin || maxY < -margin || minY > cam.h + margin) return
        if (cnt >= 0x1FFFFF) return
        val triD = (qz[a] + qz[b] + qz[c]) / 3f
        val ok = (depthO * 32f).toLong().coerceIn(0L, 0x3FFFFFL)
        val tk = (triD * 32f).toLong().coerceIn(0L, 0xFFFFFL)
        keys[cnt] = (ok shl 41) or (tk shl 21) or cnt.toLong()
        cnt++
    }

    private fun shade(base: Int, fl: Int, nx0: Float, ny0: Float, nz0: Float, flip: Boolean, y: Float, depth: Float, l: Light): Int {
        var r: Int; var g: Int; var b: Int
        if (fl and EMIT != 0) { r = (base shr 16) and 0xFF; g = (base shr 8) and 0xFF; b = base and 0xFF }
        else {
            val s = if (flip) -1f else 1f
            val nx = nx0 * s; val ny = ny0 * s; val nz = nz0 * s
            val d = nx * l.sx + ny * l.sy + nz * l.sz
            val hl = d * 0.5f + 0.5f
            val diff = hl * hl
            val sky = 0.78f + 0.22f * ny
            // contact shadow: surfaces darken a little near the floor they stand on
            val yl = if (l.indoor) { val q = y % l.storey; if (q < 0) q + l.storey else q } else y
            val ao = if (yl < 0.5f && yl > -0.2f) 0.74f + 0.26f * (max(0f, yl) / 0.5f) else 1f
            val ka = l.ambK * sky / 255f; val ks = l.sunK * diff * 1.25f / 255f
            r = (((base shr 16) and 0xFF) * (((l.amb shr 16) and 0xFF) * ka + ((l.sun shr 16) and 0xFF) * ks) * ao).toInt()
            g = (((base shr 8) and 0xFF) * (((l.amb shr 8) and 0xFF) * ka + ((l.sun shr 8) and 0xFF) * ks) * ao).toInt()
            b = ((base and 0xFF) * ((l.amb and 0xFF) * ka + (l.sun and 0xFF) * ks) * ao).toInt()
            if (r > 255) r = 255; if (g > 255) g = 255; if (b > 255) b = 255
        }
        // haze: far things fade into the sky
        if (depth > l.fogStart) {
            val t = min(l.fogMax, (depth - l.fogStart) / (l.fogEnd - l.fogStart) * l.fogMax)
            r = (r + (((l.fog shr 16) and 0xFF) - r) * t).toInt(); g = (g + (((l.fog shr 8) and 0xFF) - g) * t).toInt(); b = (b + ((l.fog and 0xFF) - b) * t).toInt()
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r = (((a shr 16) and 0xFF) + ((((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * t)).toInt()
        val g = (((a shr 8) and 0xFF) + ((((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * t)).toInt()
        val bl = ((a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * t)).toInt()
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
    }
}

fun mix(a: Int, b: Int, t: Float): Int {
    fun c(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
    return (c(16) shl 16) or (c(8) shl 8) or c(0)
}
fun hexColor(s: String, fallback: Int = 0x6366F1): Int = runCatching { s.removePrefix("#").take(6).toInt(16) }.getOrDefault(fallback)
fun absf(x: Float) = abs(x)
