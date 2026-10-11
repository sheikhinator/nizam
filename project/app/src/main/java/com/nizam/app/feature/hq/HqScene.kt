package com.nizam.app.feature.hq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Atlas HQ's world: a tower with a floor per department, a desk per person, your office on top and a
// street outside. People keep office hours, sit at their desks, walk around furniture, get coffee,
// chat, hand work over in person, gather for meetings, pray together and go home at night.
// Pure Kotlin (no Android), so it can be drawn and tested anywhere.

data class HqAgent(val id: String, val name: String, val emoji: String, val color: Int, val room: Int, val slot: Int,
                   val status: String, val pose: String, val card: String, val line: String, val say: String = "",
                   val level: Int = 1, val persona: String = "careful", val dept: String = "research", val visit: String = "", val urgent: Boolean = false)

data class HqRoomDef(val name: String, val emoji: String, val color: Int)

data class HqState(
    val agents: List<HqAgent> = emptyList(), val needs: List<String> = emptyList(),
    val sky: String = "DAY", val hour: Float = 12f, val weather: String = "clear", val event: String? = null,
    val salah: Boolean = false, val qibla: Float = 255f, val loadshed: Boolean = false, val low: Boolean = false,
    val meeting: List<String> = emptyList(), val speaking: String? = null, val jar: Float = 1f,
    val approvals: Int = 0, val changes: Int = 0, val pcOnline: Boolean = false, val celebrate: Int = -1,
    val summary: String = "", val prayer: String = "", val routine: String = "", val plugsOk: Int = 0, val plugsTotal: Int = 0,
    val plugsKnown: Boolean = false, val topic: String = "",
    /** The live team conversation: who said what, latest last. */
    val log: List<Pair<String, String>> = emptyList(), val thread: String? = null,
    val open: Boolean = true, val escalate: Boolean = true, val idleLife: Boolean = true,
    val friends: Map<String, List<String>> = emptyMap(), val leaving: List<String> = emptyList(),
    val meetingKind: String = "", val gathering: Boolean = false, val boardText: String = "",
)

/** Something on screen: an agent's card, a sign, a bubble. */
class HqLabel(val key: String, var kind: String, var id: String) {
    var x = 0f; var y = 0f; var shown = false; var text = ""; var sub = ""; var line = ""; var color = 0; var status = ""; var near = false
    var level = -1; var urgent = false
}

class HqScene(private val rooms: List<HqRoomDef>) {
    companion object {
        const val W = 14f; const val D = 10f; const val H = 3.2f
        private val SKY = mapOf(
            "DAWN" to intArrayOf(0x7C8FD6, 0xF6B98A, 0xFFD7A8), "DAY" to intArrayOf(0x4F8FEA, 0xDBEAFE, 0xFFFFFF),
            "GOLDEN" to intArrayOf(0x6D8FDC, 0xFFD18A, 0xFFC27A), "DUSK" to intArrayOf(0x3B2B6B, 0xF08A6C, 0xFF9D6B),
            "NIGHT" to intArrayOf(0x070B1C, 0x1B2447, 0x8AA0FF))
        val STATUS = mapOf("working" to 0x3B82F6, "needs" to 0xEF4444, "done" to 0x22C55E, "idle" to 0x9CA3AF, "asleep" to 0x6B7280)
        val LEVEL_COLORS = intArrayOf(0x22C55E, 0x0EA5E9, 0x8B5CF6, 0xF59E0B, 0xEF4444)
        val LEVEL_NAMES = listOf("Intern", "Associate", "Senior", "Lead", "Chief")
        /** Small talk, by personality (no AI: just office life). */
        val CHATTER = mapOf(
            "careful" to listOf("Did you double-check that?", "I'll write it up in the wiki.", "Small steps, big results.", "Let's verify the source."),
            "bold" to listOf("Let's ship it today!", "Bigger goals, yaar.", "I say we go for it.", "Done by lunch, watch me."),
            "funny" to listOf("Who took my chai? 😅", "My laptop needs a holiday.", "Coffee first, genius later.", "The dog is the real CEO."),
            "blunt" to listOf("That plan is weak.", "Numbers don't lie.", "Skip the fluff.", "Fix it, then talk."),
            "warm" to listOf("How's your family?", "Nice work today!", "Take a break, yaar.", "Proud of this team."),
            "quiet" to listOf("Hmm.", "Interesting.", "Thinking…", "Makes sense."))
        val REPLIES = listOf("Haha, true.", "Agreed!", "Hmm, maybe.", "InshaAllah.", "Fair point.", "Let's see.", "Totally.", "Right?")
        val GREETS = mapOf(
            "careful" to "Salam, boss. All in order.", "bold" to "Boss! Big things today 💪", "funny" to "Boss in the building! 🎉",
            "blunt" to "Boss. Need anything?", "warm" to "Assalam o alaikum! How are you?", "quiet" to "Salam, boss.")
        const val OWNER = 6
    }

    var state = HqState()
        private set

    // ───────────── building ─────────────
    class Slot(val x: Float, val z: Float, val bean: Boolean, val deskZ: Float)
    class Spot(val kind: String, val id: String, val label: String, val floor: Int, val x: Float, val z: Float, val ay: Float = 1.2f, val face: Float = 0f)
    inner class Floor(val i: Int, val room: HqRoomDef) {
        val y = i * H
        val base = Mesh(32); val under = Mesh(512); val decal = Mesh(128); val walls = Mesh(128); val decor = Mesh(256); val main = Mesh(3072)
        val dyn = Mesh(3072); val dynDecal = Mesh(256)
        val slots = ArrayList<Slot>(); val solids = ArrayList<FloatArray>()
        val doorX = -5.9f; val doorZ = -3.0f
        var doorOpen = 0f
        val coffee = floatArrayOf(-5.45f, 3.3f, -PI.toFloat() / 2)       // where you stand to make coffee
        val board = floatArrayOf(-4.3f, 2.0f, PI.toFloat())             // where you stand at the whiteboard
        val grid = BooleanArray(GX * GZ)
    }
    private val GX = 28; private val GZ = 20; private val CS = 0.5f
    val floors = ArrayList<Floor>()
    val spots = ArrayList<Spot>()
    private val ground = Mesh(1024); private val roads = Mesh(512); private val marks = Mesh(256)
    private val back = Mesh(2048); private val front = Mesh(1024); private val roof = Mesh(256)
    private val worldDyn = Mesh(512)
    val light = Light(); val inside = Light()
    var skyTop = 0x4F8FEA; var skyBottom = 0xDBEAFE; var night = false; var stars = false; var raining = false; var storm = false; var fog = false

    private fun solid(f: Floor, x: Float, z: Float, w: Float, d: Float) { f.solids += floatArrayOf(x - w / 2, x + w / 2, z - d / 2, z + d / 2) }

    private fun build() {
        ground.floor(160f, 160f, 0f, -0.03f, 0f, 0x7FB069, 10f)
        roads.floor(160f, 6f, 0f, -0.01f, 13f, 0x3D4250, 4f)
        roads.floor(160f, 3.2f, 0f, 0.0f, 8.4f, 0xC9C3B6, 4f)
        roads.floor(160f, 3.2f, 0f, 0.0f, 17.6f, 0xC9C3B6, 4f)
        roads.floor(2.4f, 3.6f, 0f, 0.004f, 6.6f, 0xB8B0A2, 1.2f)                   // the path to the front door
        var x = -78f; while (x < 80f) { marks.floor(2.4f, 0.18f, x, 0.005f, 13f, 0xF5F0E1); x += 6f }
        fun tree(m: Mesh, tx: Float, tz: Float, s: Float) {
            m.cyl(0.14f * s, 0.2f * s, 1.4f * s, tx, 0.7f * s, tz, 0x7A5537, 6)
            m.sphere(1.1f * s, tx, 2.1f * s, tz, 0x4F9D4F, 8, 5)
            m.sphere(0.8f * s, tx + 0.4f * s, 2.7f * s, tz + 0.2f * s, 0x5FB35F, 8, 4)
        }
        listOf(-10f to 7.5f, 10f to 7.5f, -16f to 7.5f, 16f to 7.5f, -22f to 7.4f, 22f to 7.6f).forEachIndexed { i, (a, b) -> tree(front, a, b, 0.9f + (i % 3) * 0.15f) }
        listOf(-9f to -9f, 11f to -10f, -14f to -6f).forEachIndexed { i, (a, b) -> tree(back, a, b, 1f + (i % 2) * 0.2f) }
        // street lamps and benches along the pavement
        for (lx in listOf(-13f, -5f, 5f, 13f)) { front.cyl(0.05f, 0.07f, 3.4f, lx, 1.7f, 7.0f, 0x374151, 6); streetLamps += front.n; front.sphere(0.2f, lx, 3.5f, 7.0f, 0xFFF1C1, 6, 3, 1f, EMIT) }
        front.box(1.6f, 0.08f, 0.45f, 7.5f, 0.45f, 7.2f, 0x8B5A2B); front.box(1.6f, 0.4f, 0.06f, 7.5f, 0.65f, 7.0f, 0x8B5A2B)
        fun building(m: Mesh, bx: Float, bz: Float, w: Float, h: Float, d: Float, color: Int) {
            m.box(w, h, d, bx, h / 2, bz, color)
            m.box(w + 0.3f, 0.4f, d + 0.3f, bx, h + 0.2f, bz, mix(color, 0x000000, 0.2f))
            var fy = 1.4f
            while (fy < h - 0.8f) {
                var fx = -w / 2 + 1f
                while (fx < w / 2 - 0.6f) { cityWindows += intArrayOf(m.n, (fx * 7 + fy * 13).toInt()); m.front(0.8f, 1.1f, bx + fx, fy, bz + d / 2 + 0.02f, 0x2A3350, EMIT); fx += 1.6f }
                fy += 2.2f
            }
        }
        building(back, -12f, -20f, 8f, 14f, 6f, 0xC7B7A3); building(back, 2f, -24f, 9f, 22f, 7f, 0x9FB3C8); building(back, 15f, -19f, 6f, 11f, 5f, 0xD9C8B4)
        building(back, 28f, -22f, 10f, 17f, 7f, 0xB8A894); building(back, -30f, -16f, 9f, 12f, 6f, 0xA9B8A0)
        back.with(M4.t(-24f, 0f, -8f).mul(M4.ry(-PI.toFloat() / 2))) {
            back.box(8f, 4f, 6f, 0f, 2f, 0f, 0xF1ECE2)
            back.sphere(2.3f, 0f, 4f, 0f, 0x2F9E8F, 12, 4, 1f, 0, 0f, 1.5707964f)
            back.cyl(0.45f, 0.55f, 10f, 4.6f, 5f, -2f, 0xF1ECE2, 10)
            back.cyl(0f, 0.6f, 1.4f, 4.6f, 10.7f, -2f, 0x2F9E8F, 10)
            for (k in -1..1) { masjidWindows += back.n; back.quad(k * 2.4f + 0.55f, 0.9f, -3.02f, k * 2.4f - 0.55f, 0.9f, -3.02f, k * 2.4f - 0.55f, 2.7f, -3.02f, k * 2.4f + 0.55f, 2.7f, -3.02f, 0f, 0f, -1f, 0x5A4A2A, EMIT) }
        }
        rooms.forEachIndexed { i, r -> buildFloor(Floor(i, r).also { floors += it }) }
        buildRoof()
        floors.forEach { buildGrid(it) }
    }
    private val cityWindows = ArrayList<IntArray>()
    private val masjidWindows = ArrayList<Int>()
    private val streetLamps = ArrayList<Int>()

    private fun chair(m: Mesh, x: Float, z: Float, back: Float, color: Int = 0x374151) {
        // seat, back, gas lift and a five-star base
        m.box(0.52f, 0.08f, 0.5f, x, 0.48f, z, color); m.box(0.5f, 0.55f, 0.07f, x, 0.8f, z + back, color)
        m.cyl(0.03f, 0.03f, 0.36f, x, 0.26f, z, 0x6B7280, 5)
        m.box(0.5f, 0.04f, 0.06f, x, 0.07f, z, 0x4B5563); m.box(0.06f, 0.04f, 0.5f, x, 0.07f, z, 0x4B5563)
    }
    private fun desk(f: Floor, x: Float, z: Float, big: Boolean, idx: Int) {
        val m = f.main; val w = if (big) 2.2f else 1.6f
        m.box(w, 0.06f, 0.8f, x, 0.76f, z, if (big) 0x6B4A33 else 0xC79A6B)
        m.box(w - 0.1f, 0.5f, 0.03f, x, 0.48f, z + 0.36f, if (big) 0x5A3E2B else 0xB08556)                       // modesty panel
        for (dx in listOf(-w / 2 + 0.06f, w / 2 - 0.06f)) m.box(0.05f, 0.73f, 0.7f, x + dx, 0.37f, z, 0x4B5563)
        chair(m, x, z - 0.78f, -0.26f)
        m.bias = 0.8f
        if (idx % 2 == 0) {
            m.box(0.5f, 0.03f, 0.34f, x, 0.805f, z + 0.05f, 0x9CA3AF)
            m.with(M4.t(x, 0.82f, z + 0.22f).mul(M4.rx(-0.35f))) { m.box(0.5f, 0.34f, 0.02f, 0f, 0.17f, 0f, 0x9CA3AF) }
        } else {
            m.box(0.08f, 0.02f, 0.18f, x, 0.8f, z + 0.18f, 0x374151); m.box(0.03f, 0.28f, 0.03f, x, 0.93f, z + 0.2f, 0x374151)
            m.box(0.62f, 0.38f, 0.03f, x, 1.2f, z + 0.22f, 0x111827)                                              // monitor
            m.box(0.42f, 0.02f, 0.14f, x, 0.8f, z - 0.08f, 0xE5E7EB)                                              // keyboard
        }
        if (idx % 3 == 0) { m.cyl(0.04f, 0.035f, 0.1f, x + w / 2 - 0.2f, 0.84f, z - 0.15f, 0xFFFFFF, 8); m.cyl(0.035f, 0.035f, 0.005f, x + w / 2 - 0.2f, 0.885f, z - 0.15f, 0x6B3E1F, 8) }
        if (idx % 3 == 1) { m.cyl(0.06f, 0.05f, 0.1f, x - w / 2 + 0.18f, 0.84f, z + 0.2f, 0xB45309, 6); m.sphere(0.09f, x - w / 2 + 0.18f, 0.96f, z + 0.2f, 0x3F8F4A, 6, 4) }
        m.bias = 0f
        solid(f, x, z - 0.2f, w + 0.1f, 1.6f)
    }
    private fun beanbag(f: Floor, x: Float, z: Float, col: Int) {
        f.main.sphere(0.55f, x, 0.3f, z - 0.05f, col, 10, 5, 0.55f)
        f.main.box(0.7f, 0.05f, 0.4f, x, 0.42f, z + 0.55f, 0xC79A6B); f.main.bias = 0.5f; f.main.box(0.4f, 0.02f, 0.28f, x, 0.46f, z + 0.55f, 0x9CA3AF); f.main.bias = 0f
    }
    private fun plant(m: Mesh, x: Float, z: Float, s: Float = 1f) {
        m.cyl(0.22f * s, 0.17f * s, 0.4f * s, x, 0.2f * s, z, 0xB45309, 8)
        m.sphere(0.32f * s, x, 0.78f * s, z, 0x3F8F4A, 8, 5); m.sphere(0.22f * s, x + 0.1f * s, 1.05f * s, z, 0x5AAD5F, 8, 4); m.sphere(0.18f * s, x - 0.12f * s, 1.0f * s, z + 0.08f * s, 0x4B9E55, 6, 3)
    }
    private fun coffeeStation(f: Floor) {
        val m = f.main
        m.box(0.7f, 0.92f, 1.0f, -6.25f, 0.46f, 3.3f, 0x57534E); m.box(0.74f, 0.04f, 1.04f, -6.25f, 0.94f, 3.3f, 0xE7E5E4)
        m.bias = 0.6f
        m.box(0.34f, 0.42f, 0.3f, -6.3f, 1.17f, 3.1f, 0x1F2937); m.box(0.1f, 0.06f, 0.04f, -6.12f, 1.2f, 3.1f, 0x9CA3AF)
        m.cyl(0.035f, 0.03f, 0.09f, -6.1f, 1.0f, 3.55f, 0xFFFFFF, 8); m.cyl(0.035f, 0.03f, 0.09f, -6.28f, 1.0f, 3.6f, 0xEF4444, 8)
        m.cyl(0.12f, 0.1f, 0.5f, -6.3f, 1.2f, 3.65f, 0xBFDBFE, 8)                                                 // water bottle
        m.bias = 0f
        solid(f, -6.25f, 3.3f, 0.8f, 1.1f)
        spots += Spot("coffee", "${f.i}", "☕ Make chai", f.i, -5.5f, 3.3f, 1.1f)
    }
    private fun whiteboard(f: Floor) {
        val m = f.main
        m.box(0.05f, 1.7f, 0.05f, -4.95f, 0.85f, 1.2f, 0x6B7280); m.box(0.05f, 1.7f, 0.05f, -3.65f, 0.85f, 1.2f, 0x6B7280)
        m.box(0.6f, 0.04f, 0.4f, -4.95f, 0.04f, 1.2f, 0x6B7280); m.box(0.6f, 0.04f, 0.4f, -3.65f, 0.04f, 1.2f, 0x6B7280)
        m.box(1.4f, 0.9f, 0.04f, -4.3f, 1.35f, 1.2f, 0xFFFFFF)
        m.bias = 0.3f
        for (k in 0 until 3) { m.front(0.36f, 0.6f, -4.72f + k * 0.42f, 1.35f, 1.225f, intArrayOf(0xFDE68A, 0xBFDBFE, 0xBBF7D0)[k]) }
        for (k in 0 until 5) m.front(0.1f, 0.08f, -4.72f + (k % 3) * 0.42f, 1.5f - (k / 3) * 0.2f, 1.23f, intArrayOf(0xF472B6, 0xFBBF24, 0x60A5FA, 0x34D399, 0xF87171)[k])
        m.bias = 0f
        solid(f, -4.3f, 1.2f, 1.5f, 0.45f)
        spots += Spot("board", "${f.i}", "📋 Task board", f.i, -4.3f, 2.0f, 1.6f)
    }

    private fun slotsFor(i: Int): List<Triple<Float, Float, Int>> = when (i) {    // x, z, 0 desk / 1 bean / 2 big desk
        0 -> listOf(Triple(4.9f, -2.6f, 2), Triple(-2.6f, -2.6f, 0), Triple(4.9f, 3.4f, 0), Triple(-2.6f, 3.6f, 0), Triple(1.2f, 4.3f, 1), Triple(6.2f, 0.6f, 1), Triple(-0.8f, 4.3f, 1))
        OWNER -> emptyList()
        else -> listOf(Triple(-2.8f, -2.0f, 0), Triple(0.4f, -2.0f, 0), Triple(3.6f, -2.0f, 0), Triple(-0.8f, 3.6f, 1), Triple(2.4f, 3.6f, 1), Triple(5.4f, 3.6f, 1), Triple(5.9f, 0.6f, 1))
    }

    private fun buildFloor(f: Floor) {
        val i = f.i; val tint = f.room.color
        val owner = i == OWNER
        f.base.with(M4.t(0f, f.y, 0f)) { f.base.box(W, 0.2f, D, 0f, -0.1f, 0f, mix(0xE9DFCF, tint, 0.08f)) }
        // wooden floor: every plank a slightly different shade
        val wood = if (owner) 0x9A6B45 else mix(0xE3CFAE, tint, 0.06f)
        f.under.with(M4.t(0f, f.y, 0f)) { f.under.floor(W, D, 0f, 0.001f, 0f, wood, 1f) }
        for (t in 0 until f.under.n) { val tile = t / 2; val j = ((tile * 2654435761L) ushr 13 and 7L).toInt(); f.under.col[t] = mix(wood, if (j % 2 == 0) 0xFFFFFF else 0x000000, 0.012f * j) }
        f.decal.with(M4.t(0f, f.y, 0f)) { f.decal.floor(5.5f, 3.4f, 1.2f, 0.01f, if (i == 0) 0.2f else -0.5f, mix(0xFFFFFF, tint, 0.35f), 1.1f) }
        val wall = mix(0xF7F3EC, tint, 0.12f)
        f.walls.with(M4.t(0f, f.y, 0f)) { f.walls.box(W, H, 0.2f, 0f, H / 2, -D / 2, wall); f.walls.box(0.2f, H, D, -W / 2, H / 2, 0f, wall) }
        f.decor.with(M4.t(0f, f.y, 0f)) {
            for (wx in listOf(-4.5f, -1.5f, 1.5f, 4.5f)) {
                panes += intArrayOf(i, f.decor.n); f.decor.front(1.9f, 1.5f, wx + 1.2f, 1.75f, -D / 2 + 0.11f, 0x9CC3F5, EMIT)
                // frame and glazing bars
                val fr = 0xE5E7EB
                f.decor.front(2.0f, 0.07f, wx + 1.2f, 2.52f, -D / 2 + 0.125f, fr); f.decor.front(2.0f, 0.07f, wx + 1.2f, 0.98f, -D / 2 + 0.125f, fr)
                f.decor.front(0.07f, 1.6f, wx + 0.22f, 1.75f, -D / 2 + 0.125f, fr); f.decor.front(0.07f, 1.6f, wx + 2.18f, 1.75f, -D / 2 + 0.125f, fr)
                f.decor.front(0.05f, 1.5f, wx + 1.2f, 1.75f, -D / 2 + 0.128f, fr)
            }
            f.decor.front(W - 0.4f, 0.12f, 0f, 0.06f, -D / 2 + 0.115f, 0x8B7355); f.decor.side(D - 0.4f, 0.12f, -W / 2 + 0.115f, 0.06f, 0f, 0x8B7355)   // skirting
            f.decor.front(2.4f, 0.6f, -1.5f, 2.75f, -D / 2 + 0.12f, tint, EMIT)
        }
        val m = f.main
        m.with(M4.t(0f, f.y, 0f)) {
            m.box(0.3f, H, 0.3f, W / 2, H / 2, D / 2, 0xD6D3D1); m.box(0.3f, H, 0.3f, -W / 2, H / 2, D / 2, 0xD6D3D1); m.box(0.3f, H, 0.3f, W / 2, H / 2, -D / 2, 0xD6D3D1)
            // glass balustrade posts along the open edges
            var px = -W / 2 + 1.4f; while (px < W / 2 - 0.5f) { if (!(i == 0 && abs(px) < 1.1f)) m.box(0.05f, 1.0f, 0.05f, px, 0.5f, D / 2 - 0.05f, 0x9CA3AF); px += 1.4f }
            if (i == 0) { m.box(5.8f, 0.05f, 0.06f, -4.0f, 1.0f, D / 2 - 0.05f, 0x9CA3AF); m.box(5.8f, 0.05f, 0.06f, 4.0f, 1.0f, D / 2 - 0.05f, 0x9CA3AF) }
            else m.box(W, 0.05f, 0.06f, 0f, 1.0f, D / 2 - 0.05f, 0x9CA3AF)
            // elevator shaft (its doors move, so they're drawn each frame)
            m.box(1.6f, H - 0.1f, 1.4f, -5.9f, (H - 0.1f) / 2, -4.1f, 0x9AA3B2)
            m.box(1.5f, 0.08f, 0.06f, -5.9f, 2.3f, -3.38f, 0x6B7280)
            m.front(0.4f, 0.2f, -5.9f, 2.55f, -3.37f, 0xFBBF24, EMIT)
            m.front(0.08f, 0.14f, -4.95f, 1.2f, -3.37f, 0xE5E7EB, EMIT)                                          // call button
            plant(m, 6.4f, -4.4f); plant(m, -6.3f, 4.3f, 1.2f)
        }
        solid(f, -5.9f, -4.1f, 1.7f, 1.5f); solid(f, 6.4f, -4.4f, 0.6f, 0.6f); solid(f, -6.3f, 4.3f, 0.6f, 0.6f)
        spots += Spot("elevator", "$i", "🛗 Elevator", i, -5.9f, -3.2f, 1.6f)
        m.with(M4.t(0f, f.y, 0f)) {
            slotsFor(i).forEachIndexed { k, (sx, sz, kind) ->
                if (kind == 1) beanbag(f, sx, sz, tint) else desk(f, sx, sz, kind == 2, k + i)
                f.slots += Slot(sx, if (kind == 1) sz else sz - 0.72f, kind == 1, sz)
            }
            coffeeStation(f)
            if (!owner) whiteboard(f)
            decorate(f)
        }
    }

    private val panes = ArrayList<IntArray>()
    val tableSeats = ArrayList<FloatArray>()
    val benchSeats = ArrayList<FloatArray>()
    private var jarAt = floatArrayOf(-6.2f, 0.9f, 1.7f)
    private var gongSwing = 0f
    private var pcSignTri = -1
    private val blinkers = ArrayList<IntArray>()

    private fun decorate(f: Floor) {
        val m = f.main; val i = f.i
        val oldDecor = f.decor.mat; f.decor.mat = M4.t(0f, f.y, 0f)
        decorate2(f, m, i)
        f.decor.mat = oldDecor
    }
    private fun decorate2(f: Floor, m: Mesh, i: Int) {
        when {
            i == 0 -> {
                // the boardroom table seats ten
                m.box(5.6f, 0.08f, 1.5f, 1.1f, 0.77f, 0.2f, 0x5B3B26)
                for (lx in listOf(-1.4f, 3.6f)) m.box(0.12f, 0.73f, 1.2f, lx, 0.37f, 0.2f, 0x3B2A1C)
                for ((k, sx) in listOf(-1.0f, 0.2f, 1.4f, 2.6f, 3.8f).withIndex()) for (side in listOf(-1f, 1f)) {
                    val sz = 0.2f + side * 1.02f
                    chair(m, sx, sz, side * 0.26f, 0x1F2937)
                    tableSeats += floatArrayOf(sx, 0.2f + side * 1.02f, if (side < 0) 0f else PI.toFloat())
                    if (k == 2 && side < 0) { m.bias = 0.6f; m.cyl(0.05f, 0.04f, 0.2f, sx, 0.9f, 0.2f, 0xBFDBFE, 8); m.bias = 0f }
                }
                spots += Spot("table", "", "🤝 Meeting table", 0, 1.1f, 0.2f, 1.0f)
                solid(f, 1.1f, 0.2f, 5.8f, 1.7f)
                m.box(4.35f, 1.78f, 0.06f, 2.6f, 1.95f, -D / 2 + 0.1f, 0x111827)
                f.decor.front(4.2f, 1.64f, 2.6f, 1.95f, -D / 2 + 0.14f, 0x1E1B4B, EMIT)
                spots += Spot("screen", "", "📺 HQ screen", 0, 2.6f, -4.6f, 2.2f)
                m.box(1.0f, 0.9f, 3.6f, -6.2f, 0.45f, 0.6f, 0xD6C7B0); solid(f, -6.2f, 0.6f, 1.1f, 3.7f)
                m.bias = 0.8f
                m.box(0.6f, 0.06f, 0.45f, -6.2f, 0.93f, -0.6f, 0xEF4444)
                spots += Spot("inbox", "", "📥 Inbox", 0, -6.2f, -0.6f, 1.1f)
                m.box(0.6f, 0.06f, 0.45f, -6.2f, 0.93f, 0.5f, 0x0EA5E9)
                spots += Spot("changes", "", "🧾 What agents changed", 0, -6.2f, 0.5f, 1.1f)
                m.cyl(0.3f, 0.3f, 0.06f, -6.2f, 1.63f, 1.7f, 0xB45309, 8)
                m.bias = 0f
                spots += Spot("jar", "", "🫙 Agent budget", 0, -6.2f, 1.7f, 1.4f)
                f.decor.side(1.8f, 1.2f, -W / 2 + 0.12f, 2.2f, 1.9f, 0x1F2937, EMIT)
                spots += Spot("plugs", "", "🔌 Connectors", 0, -6.8f, 1.9f, 2.2f)
                f.decor.front(1.8f, 0.9f, -3.6f, 1.9f, -D / 2 + 0.12f, 0xFEF3C7, EMIT)
                spots += Spot("routines", "", "⏰ Routines", 0, -3.6f, -4.7f, 1.9f)
                m.box(0.08f, 1.8f, 0.08f, 5.6f, 0.9f, 2.0f, 0x3B2A1C); m.box(0.08f, 1.8f, 0.08f, 6.8f, 0.9f, 2.0f, 0x3B2A1C); m.box(1.3f, 0.08f, 0.08f, 6.2f, 1.8f, 2.0f, 0x3B2A1C)
                spots += Spot("gong", "", "🔔 Ring the gong", 0, 6.2f, 2.0f, 1.2f); solid(f, 6.2f, 2.0f, 1.4f, 0.4f)
                m.cyl(0.45f, 0.45f, 0.14f, 6.3f, 0.07f, 4.3f, 0xF97316, 12)
                // reception desk by the front door
                m.box(2.2f, 1.05f, 0.6f, 3.2f, 0.52f, 4.3f, 0xF5F5F4); m.box(2.3f, 0.05f, 0.7f, 3.2f, 1.07f, 4.3f, 0x57534E); solid(f, 3.2f, 4.3f, 2.3f, 0.7f)
            }
            i == OWNER -> {
                // your office: a big desk facing the room, a sofa, a bookcase and a bench outside for visitors
                m.box(2.6f, 0.08f, 1.0f, 1.2f, 0.78f, -2.4f, 0x4A2F1F)
                m.box(2.5f, 0.72f, 0.9f, 1.2f, 0.38f, -2.4f, 0x3B2416)
                chair(m, 1.2f, -3.3f, -0.28f, 0x111827)
                m.bias = 0.8f
                m.box(0.7f, 0.42f, 0.03f, 1.2f, 1.22f, -2.25f, 0x0B1220); m.box(0.08f, 0.3f, 0.03f, 1.2f, 0.95f, -2.25f, 0x374151)
                m.cyl(0.05f, 0.04f, 0.12f, 2.1f, 0.87f, -2.3f, 0xFBBF24, 10)
                m.bias = 0f
                solid(f, 1.2f, -2.6f, 2.7f, 1.8f)
                spots += Spot("company", "", "🏢 Company dashboard", OWNER, 1.2f, -2.4f, 1.3f)
                spots += Spot("seat", "desk", "🪑 Sit at your desk", OWNER, 1.2f, -3.3f, 1.0f, 0f)
                m.box(2.2f, 0.45f, 0.9f, 4.4f, 0.3f, 2.9f, 0x7C2D12); m.box(2.2f, 0.7f, 0.25f, 4.4f, 0.6f, 3.3f, 0x6B2410)
                m.box(0.25f, 0.55f, 0.9f, 3.3f, 0.45f, 2.9f, 0x6B2410); m.box(0.25f, 0.55f, 0.9f, 5.5f, 0.45f, 2.9f, 0x6B2410)
                solid(f, 4.4f, 2.9f, 2.4f, 1.0f)
                spots += Spot("seat", "sofa", "🛋️ Sit on the sofa", OWNER, 4.4f, 2.7f, 0.8f, PI.toFloat())
                for (k in 0 until 4) { m.box(1.6f, 0.04f, 0.35f, 4.8f, 0.4f + k * 0.5f, -4.6f, 0x5B3B26)
                    m.bias = 0.3f; for (b in 0 until 7) m.box(0.16f, 0.36f, 0.26f, 4.2f + b * 0.2f, 0.6f + k * 0.5f, -4.6f, intArrayOf(0x7F1D1D, 0x1E3A8A, 0x166534, 0x854D0E)[(b + k) % 4]); m.bias = 0f }
                m.box(1.7f, 2.1f, 0.05f, 4.8f, 1.05f, -4.8f, 0x3B2416); solid(f, 4.8f, -4.6f, 1.8f, 0.5f)
                m.box(2.2f, 0.08f, 0.45f, -3.4f, 0.46f, -3.9f, 0x8B5A2B); m.box(2.2f, 0.5f, 0.06f, -3.4f, 0.72f, -4.12f, 0x8B5A2B)
                m.box(0.06f, 0.44f, 0.4f, -4.45f, 0.22f, -3.9f, 0x4B5563); m.box(0.06f, 0.44f, 0.4f, -2.35f, 0.22f, -3.9f, 0x4B5563)
                solid(f, -3.4f, -3.95f, 2.3f, 0.6f)
                for (k in 0 until 3) benchSeats += floatArrayOf(-4.1f + k * 0.7f, -3.72f, 0f)
                spots += Spot("seat", "bench", "🪑 Sit on the bench", OWNER, -2.7f, -3.72f, 0.8f, 0f)
                m.cyl(0.02f, 0.02f, 1.8f, -1.2f, 0.9f, 3.9f, 0x1F2937, 4); m.cyl(0.25f, 0.3f, 0.3f, -1.2f, 1.9f, 3.9f, 0xFEF3C7, 10, EMIT)   // floor lamp
                f.decor.front(1.2f, 0.9f, -1.0f, 1.9f, -D / 2 + 0.13f, 0xD4AF37)                                       // a framed certificate
            }
            f.room.name.contains("Home") -> {
                m.box(2.8f, 0.9f, 0.6f, 3.6f, 0.45f, -4.5f, 0xE7E5E4); m.box(2.8f, 0.06f, 0.64f, 3.6f, 0.92f, -4.5f, 0x78716C)
                m.box(0.8f, 1.9f, 0.7f, 5.6f, 0.95f, -4.45f, 0xF1F5F9); solid(f, 4.1f, -4.5f, 3.8f, 0.8f)
                m.box(0.9f, 0.45f, 2.1f, -5.6f, 0.3f, 0.2f, 0x6366F1); m.box(0.25f, 0.7f, 2.1f, -5.95f, 0.6f, 0.2f, 0x4F46E5)
                m.box(0.9f, 0.55f, 0.22f, -5.6f, 0.45f, -0.85f, 0x4F46E5); m.box(0.9f, 0.55f, 0.22f, -5.6f, 0.45f, 1.25f, 0x4F46E5); solid(f, -5.6f, 0.2f, 1.0f, 2.2f)
                spots += Spot("seat", "sofa", "🛋️ Sit on the sofa", f.i, -5.35f, 0.2f, 0.8f, PI.toFloat() / 2)
                f.decor.side(0.9f, 0.7f, -W / 2 + 0.12f, 1.9f, -2.2f, 0xFEF3C7, EMIT)
            }
            f.room.name.contains("Money") -> {
                m.box(1.1f, 1.2f, 0.9f, 5.8f, 0.6f, -4.2f, 0x374151); solid(f, 5.8f, -4.2f, 1.2f, 1.0f)
                m.cyl(0.14f, 0.14f, 0.05f, 6.0f, 0.7f, -3.74f, 0xD4AF37, 8)
                for (k in 0 until 5) for (hh in 0 until 2 + k) m.cyl(0.12f, 0.12f, 0.05f, 3.3f + k * 0.3f, 0.03f + hh * 0.055f, -4.4f, 0xEAB308, 8)
                f.decor.side(2f, 1f, -W / 2 + 0.12f, 2.0f, -1.2f, 0x0F172A, EMIT)
            }
            f.room.name.contains("Deen") -> {
                for (k in 0 until 3) {
                    m.box(1.8f, 0.05f, 0.4f, -5.6f, 0.6f + k * 0.55f, -1f, 0x8B5A2B)
                    m.bias = 0.3f
                    for (b in 0 until 8) m.box(0.14f, 0.4f, 0.3f, -6.3f + b * 0.19f, 0.83f + k * 0.55f, -1f, intArrayOf(0x166534, 0x7C2D12, 0x1E3A8A, 0x854D0E)[b % 4])
                    m.bias = 0f
                }
                solid(f, -5.6f, -1f, 1.9f, 0.5f)
                spots += Spot("shelf", "${f.i}", "📖 Read", f.i, -5.6f, -0.35f, 1.2f, PI.toFloat())
                m.sphere(0.25f, -4.2f, 2.6f, -0.2f, 0xFFC36B, 6, 3, 1.3f, EMIT)
            }
            f.room.name.contains("Work") -> {
                f.decor.side(2.2f, 1.1f, -W / 2 + 0.12f, 1.8f, -1.6f, 0xFFFFFF, EMIT)
                for (k in 0 until 3) m.box(0.6f, 1.3f, 0.6f, 4.6f + k * 0.65f, 0.65f, -4.4f, 0x94A3B8)
                solid(f, 5.25f, -4.4f, 2.0f, 0.7f)
            }
            else -> {
                for (k in 0 until 3) {
                    m.box(0.8f, 2.2f, 0.8f, 4.2f + k * 0.9f, 1.1f, -4.3f, 0x1F2937)
                    for (r in 0 until 6) { blinkers += intArrayOf(i, m.n, r + k * 7); m.front(0.08f, 0.08f, 4.0f + k * 0.9f + (r % 2) * 0.14f, 0.4f + r * 0.3f, -3.89f, 0x22C55E, EMIT) }
                }
                solid(f, 5.1f, -4.3f, 2.8f, 0.9f)
                pcSignTri = f.decor.n; f.decor.side(1.6f, 0.4f, -W / 2 + 0.12f, 2.3f, -1.5f, 0x7F1D1D, EMIT)
                spots += Spot("pc", "", "🖥️ PC link", i, -6.8f, -1.5f, 2.3f)
            }
        }
    }

    private fun buildRoof() {
        val y = floors.size * H
        roof.with(M4.t(0f, y, 0f)) {
            roof.box(W, 0.2f, D, 0f, -0.1f, 0f, 0xD6D3D1)
            roof.box(W, 0.6f, 0.2f, 0f, 0.3f, D / 2, 0xE7E5E4); roof.box(W, 0.6f, 0.2f, 0f, 0.3f, -D / 2, 0xE7E5E4)
            roof.box(0.2f, 0.6f, D, W / 2, 0.3f, 0f, 0xE7E5E4); roof.box(0.2f, 0.6f, D, -W / 2, 0.3f, 0f, 0xE7E5E4)
            roof.front(6f, 1.3f, 0f, 1.4f, D / 2 - 0.3f, 0x6366F1, EMIT)
            roof.cyl(0.04f, 0.06f, 3f, 5f, 1.5f, -3f, 0x9CA3AF, 4)
            roof.box(1.6f, 1.0f, 1.2f, -4.5f, 0.5f, -3f, 0xA8A29E); roof.box(1.2f, 0.6f, 0.9f, -2.5f, 0.3f, -3.2f, 0xA8A29E)   // plant rooms
        }
    }

    // ───────────── walking around furniture ─────────────
    private fun buildGrid(f: Floor) {
        for (i in 0 until GX) for (k in 0 until GZ) {
            val x = -W / 2 + (i + 0.5f) * CS; val z = -D / 2 + (k + 0.5f) * CS
            var b = x < -W / 2 + 0.4f || x > W / 2 - 0.4f || z < -D / 2 + 0.4f || (z > D / 2 - 0.45f && !(f.i == 0 && abs(x) < 1.0f))
            if (!b) for (s in f.solids) if (x > s[0] - 0.28f && x < s[1] + 0.28f && z > s[2] - 0.28f && z < s[3] + 0.28f) { b = true; break }
            f.grid[i * GZ + k] = b
        }
    }
    private fun ci(x: Float) = ((x + W / 2) / CS).toInt().coerceIn(0, GX - 1)
    private fun ck(z: Float) = ((z + D / 2) / CS).toInt().coerceIn(0, GZ - 1)
    private fun cxOf(i: Int) = -W / 2 + (i + 0.5f) * CS
    private fun czOf(k: Int) = -D / 2 + (k + 0.5f) * CS
    private fun nearestFree(f: Floor, i0: Int, k0: Int): Int {
        if (!f.grid[i0 * GZ + k0]) return i0 * GZ + k0
        for (r in 1..6) for (di in -r..r) for (dk in -r..r) {
            if (abs(di) != r && abs(dk) != r) continue
            val i = i0 + di; val k = k0 + dk
            if (i in 0 until GX && k in 0 until GZ && !f.grid[i * GZ + k]) return i * GZ + k
        }
        return i0 * GZ + k0
    }
    private fun clear(f: Floor, ax: Float, az: Float, bx: Float, bz: Float): Boolean {
        val d = hypot(bx - ax, bz - az); val n = max(1, (d / 0.2f).toInt())
        for (s in 0..n) { val t = s.toFloat() / n; if (f.grid[ci(ax + (bx - ax) * t) * GZ + ck(az + (bz - az) * t)]) return false }
        return true
    }
    private val openSet = java.util.PriorityQueue<LongArray>(64) { a, b -> a[0].compareTo(b[0]) }
    /** A walkable route on one floor, as points to walk through (the last is exactly where you asked). */
    var pathCalls = 0
    private fun pathOn(f: Floor, x0: Float, z0: Float, x1: Float, z1: Float): List<FloatArray> {
        pathCalls++
        if (clear(f, x0, z0, x1, z1)) return listOf(floatArrayOf(x1, z1))
        val s = nearestFree(f, ci(x0), ck(z0)); val g = nearestFree(f, ci(x1), ck(z1))
        val n = GX * GZ
        val cost = FloatArray(n) { Float.MAX_VALUE }; val from = IntArray(n) { -1 }
        openSet.clear(); cost[s] = 0f; openSet.add(longArrayOf(0, s.toLong()))
        while (openSet.isNotEmpty()) {
            val cur = openSet.poll()[1].toInt()
            if (cur == g) break
            val ci0 = cur / GZ; val ck0 = cur % GZ
            for (di in -1..1) for (dk in -1..1) {
                if (di == 0 && dk == 0) continue
                val i = ci0 + di; val k = ck0 + dk
                if (i !in 0 until GX || k !in 0 until GZ) continue
                val nb = i * GZ + k
                if (f.grid[nb]) continue
                if (di != 0 && dk != 0 && (f.grid[(ci0 + di) * GZ + ck0] || f.grid[ci0 * GZ + ck0 + dk])) continue
                val c = cost[cur] + if (di != 0 && dk != 0) 1.414f else 1f
                if (c < cost[nb]) {
                    cost[nb] = c; from[nb] = cur
                    val h = hypot((i - g / GZ).toFloat(), (k - g % GZ).toFloat())
                    openSet.add(longArrayOf(((c + h) * 1000).toLong(), nb.toLong()))
                }
            }
        }
        if (from[g] == -1 && g != s) return listOf(floatArrayOf(x1, z1))
        val cells = ArrayList<Int>(); var c = g; while (c != -1 && c != s) { cells += c; c = from[c] }
        cells.reverse()
        val pts = ArrayList<FloatArray>()
        var ax = x0; var az = z0
        var idx = 0
        // skip corners we can see past
        while (idx < cells.size) {
            var far = idx
            for (j in cells.size - 1 downTo idx) if (clear(f, ax, az, cxOf(cells[j] / GZ), czOf(cells[j] % GZ))) { far = j; break }
            ax = cxOf(cells[far] / GZ); az = czOf(cells[far] % GZ)
            pts += floatArrayOf(ax, az)
            idx = far + 1
        }
        pts += floatArrayOf(x1, z1)
        return pts
    }

    // ───────────── people ─────────────
    class Way(val x: Float, val z: Float, val floor: Int, val jump: Boolean = false, val hide: Boolean = false)
    class Goal(val key: String, val floor: Int, val x: Float, val z: Float, val face: Float, val mode: String, val slot: Slot? = null)
    inner class Rt(var a: HqAgent) {
        var x = 0f; var z = 0f; var floor = 0; var face = 0f
        var goal: Goal? = null; val path = ArrayList<Way>(); var hop = 0f
        val phase = ((a.id.hashCode() and 0x7fffffff) % 1000) / 160f
        var bodyY = 0f
        var away = false; var fired = false
        var idleAct = ""; var idleUntil = 0f; var idleX = 0f; var idleZ = 0f; var idleFace = 0f; var partner: String? = null
        var prop = ""; var propUntil = 0f
        var say = ""; var sayUntil = 0f; var nextLine = 0f
        var greetAt = -99f; var lookAt: FloatArray? = null; var lookUntil = 0f
        var moving = false; var running = false
        val rnd = java.util.Random(a.id.hashCode().toLong())
    }
    val agents = LinkedHashMap<String, Rt>()

    private fun qiblaYaw(): Float { val b = Math.toRadians(state.qibla.toDouble()); return atan2(sin(b), -cos(b)).toFloat() }
    private fun slotOf(a: HqAgent): Pair<Int, Slot> {
        var fi = a.room.coerceIn(0, floors.size - 1)
        if (floors[fi].slots.isEmpty()) fi = 0
        val f = floors[fi]
        return fi to f.slots[a.slot % f.slots.size]
    }

    /** Where this person should be right now, and doing what. */
    private fun desired(r: Rt, t: Float): Goal? {
        val a = r.a
        val (fi, slot) = slotOf(a)
        val busy = a.status == "working" || a.status == "needs"
        val mi = state.meeting.indexOf(a.id)
        if (r.fired) return null
        if (!state.open && !busy && mi < 0 && !state.salah) return null                                         // gone home
        if (state.salah && a.status != "asleep") return Goal("mat", fi, slot.x, if (slot.bean) slot.z + 0.9f else slot.z - 0.95f, qiblaYaw(), "pray", slot)
        if (mi >= 0 && tableSeats.isNotEmpty()) { val s = tableSeats[mi % tableSeats.size]; return Goal("table$mi", 0, s[0], s[1], s[2], "seat") }
        if (a.status == "needs" && state.escalate && benchSeats.isNotEmpty() && floors.size > OWNER) {
            val k = state.needs.indexOf(a.id).coerceAtLeast(0) % benchSeats.size; val b = benchSeats[k]
            return Goal("bench$k", OWNER, b[0], b[1], b[2], "seat")
        }
        agents[a.visit]?.takeIf { a.visit.isNotBlank() && !it.away }?.let { o ->
            val side = if (o.x > 5f) -0.85f else 0.85f
            return Goal("visit:${o.a.id}", o.floor, o.x + side, o.z + 0.1f, if (side > 0) -PI.toFloat() / 2 else PI.toFloat() / 2, "stand")
        }
        if (busy || !state.idleLife) return Goal("desk", fi, slot.x, slot.z, 0f, if (slot.bean) "bean" else "desk", slot)
        // idle: a little office life
        if (t >= r.idleUntil || r.idleAct.isEmpty()) pickIdle(r, t, fi, slot)
        return when (r.idleAct) {
            "desk", "phone" -> Goal("desk", fi, slot.x, slot.z, 0f, if (slot.bean) "bean" else "desk", slot)
            else -> Goal("idle:${r.idleAct}:${r.partner}", fi, r.idleX, r.idleZ, r.idleFace, if (r.idleAct == "sofa" || r.idleAct == "sit") "seat" else "stand")
        }
    }

    private fun pickIdle(r: Rt, t: Float, fi: Int, slot: Slot) {
        val f = floors[fi]
        val roll = r.rnd.nextFloat()
        r.partner = null
        val ids = agents.values.filter { it !== r && it.floor == fi && !it.away && it.a.status == "idle" && it.idleAct != "chat" }
        val friend = state.friends[r.a.id]?.mapNotNull { agents[it] }?.firstOrNull { it in ids } ?: ids.randomOrNull(r.rnd)
        val (act, dur) = when {
            roll < 0.38f -> "desk" to 25f
            roll < 0.48f -> "phone" to 15f
            roll < 0.62f -> "coffee" to 14f
            roll < 0.74f && friend != null -> "chat" to 18f
            roll < 0.82f -> "board" to 12f
            roll < 0.9f -> "window" to 12f
            f.room.name.contains("Deen") && roll < 0.95f -> "read" to 16f
            f.room.name.contains("Home") && roll < 0.95f -> "sofa" to 18f
            else -> "stretch" to 6f
        }
        r.idleAct = act; r.idleUntil = t + dur * (0.7f + r.rnd.nextFloat() * 0.6f)
        when (act) {
            "coffee" -> { r.idleX = f.coffee[0]; r.idleZ = f.coffee[1] + (r.rnd.nextFloat() - 0.5f) * 0.6f; r.idleFace = f.coffee[2] }
            "board" -> { r.idleX = f.board[0] + (r.rnd.nextFloat() - 0.5f) * 0.8f; r.idleZ = f.board[1]; r.idleFace = f.board[2] }
            "window" -> { r.idleX = -3f + r.rnd.nextInt(3) * 3f + 1.2f; r.idleZ = -4.25f; r.idleFace = PI.toFloat() }
            "read" -> { r.idleX = -5.6f + (r.rnd.nextFloat() - 0.5f) * 0.8f; r.idleZ = -0.35f; r.idleFace = PI.toFloat() }
            "sofa" -> { r.idleX = -5.35f; r.idleZ = -0.3f + r.rnd.nextFloat() * 1.0f; r.idleFace = PI.toFloat() / 2 }
            "stretch" -> { r.idleX = slot.x + 0.9f; r.idleZ = slot.z; r.idleFace = 0f }
            "chat" -> {
                val o = friend!!
                // meet halfway on a free spot, facing each other
                val mx = ((r.x + o.x) / 2).coerceIn(-5f, 5.5f); val mz = ((r.z + o.z) / 2).coerceIn(-1.4f, 1.8f).let { if (abs(it) < 0.2f) 0.9f else it }
                r.partner = o.a.id; r.idleX = mx - 0.45f; r.idleZ = mz; r.idleFace = PI.toFloat() / 2
                o.partner = r.a.id; o.idleAct = "chat"; o.idleUntil = r.idleUntil; o.idleX = mx + 0.45f; o.idleZ = mz; o.idleFace = -PI.toFloat() / 2
                r.nextLine = t + 2f; o.nextLine = t + 4f
            }
        }
    }

    private var loadedOnce = false
    var celebrations = 0
        private set
    private var lastCelebrate = -1
    var celebrateText = ""
    var hint: String? = null
    private var wasHuddle = false

    fun update(s: HqState) {
        val wasOpen = state.open
        val firstLoad = !loadedOnce
        loadedOnce = true
        state = s
        val seen = HashSet<String>()
        for (a in s.agents) {
            seen += a.id
            val r = agents.getOrPut(a.id) {
                Rt(a).also { n ->
                    val (fi, slot) = slotOf(a)
                    n.floor = fi; n.x = slot.x; n.z = slot.z
                    // someone new walks in from the street
                    if (!firstLoad) { n.floor = 0; n.x = 0f; n.z = 12f; n.away = true }
                    else if (!s.open && a.status == "idle") n.away = true
                }
            }
            r.a = a
        }
        // people who were let go pick up a box and walk out
        for ((id, r) in agents) if (id !in seen) { if (!r.fired) { r.fired = true; r.prop = "box"; r.propUntil = Float.MAX_VALUE; r.goal = null; goHome(r) } }
        agents.entries.removeAll { (id, r) -> id !in seen && r.away }
        val huddle = s.meeting.size >= 2
        if (huddle && !wasHuddle) {
            hint = when (s.meetingKind) {
                "standup" -> "☕ Daily stand-up at the table"
                "allhands" -> "🎉 All-hands in the boardroom"
                else -> "🤝 " + (if (s.gathering) "Gathering for: " else "Team huddle: ") + s.topic.take(40)
            }
            if (mode == "diorama" && view.focus == null) view.floor = 0
        }
        if (!firstLoad && wasOpen != s.open) hint = if (s.open) "🌅 The office is open" else "🌙 Office closed — the team is heading home"
        wasHuddle = huddle
        if (lastCelebrate >= 0 && s.celebrate > lastCelebrate) celebrate()
        lastCelebrate = s.celebrate
        applySky()
    }

    private fun routeTo(r: Rt, floor: Int, x: Float, z: Float) {
        r.path.clear()
        if (r.away) {                                          // coming in from the street
            r.away = false; r.floor = 0; r.x = 0f; r.z = 12f
            r.path += Way(0f, 6f, 0); r.path += Way(0f, 4.2f, 0)
            if (floor == 0) pathOn(floors[0], 0f, 4.2f, x, z).forEach { r.path += Way(it[0], it[1], 0) }
            else { pathOn(floors[0], 0f, 4.2f, floors[0].doorX, floors[0].doorZ).forEach { r.path += Way(it[0], it[1], 0) }
                r.path += Way(floors[floor].doorX, floors[floor].doorZ, floor, true)
                pathOn(floors[floor], floors[floor].doorX, floors[floor].doorZ, x, z).forEach { r.path += Way(it[0], it[1], floor) } }
            return
        }
        if (r.floor != floor) {
            val f0 = floors[r.floor]; val f1 = floors[floor]
            pathOn(f0, r.x, r.z, f0.doorX, f0.doorZ).forEach { r.path += Way(it[0], it[1], r.floor) }
            r.path += Way(f1.doorX, f1.doorZ, floor, true)
            pathOn(f1, f1.doorX, f1.doorZ, x, z).forEach { r.path += Way(it[0], it[1], floor) }
        } else pathOn(floors[floor], r.x, r.z, x, z).forEach { r.path += Way(it[0], it[1], floor) }
    }
    private fun goHome(r: Rt) {
        r.path.clear()
        if (r.away) return
        if (r.floor != 0) {
            val f0 = floors[r.floor]
            pathOn(f0, r.x, r.z, f0.doorX, f0.doorZ).forEach { r.path += Way(it[0], it[1], r.floor) }
            r.path += Way(floors[0].doorX, floors[0].doorZ, 0, true)
            pathOn(floors[0], floors[0].doorX, floors[0].doorZ, 0f, 4.2f).forEach { r.path += Way(it[0], it[1], 0) }
        } else pathOn(floors[0], r.x, r.z, 0f, 4.2f).forEach { r.path += Way(it[0], it[1], 0) }
        r.path += Way(0f, 6.5f, 0); r.path += Way(if (r.rnd.nextBoolean()) -14f else 14f, 8.4f, 0); r.path += Way(if (r.rnd.nextBoolean()) -30f else 30f, 8.4f, 0, hide = true)
    }

    fun celebrate() { celebrations++; gongSwing = 1f; for (r in agents.values) if (r.a.status != "asleep" && r.path.isEmpty() && !r.away) r.hop = 1.2f }

    // ───────────── the dog ─────────────
    private var dogX = 6.3f; private var dogZ = 4.3f; private var dogFloor = 0; private var dogFace = 0f
    private val dogPath = ArrayList<Way>(); private var dogNext = 0f; private var dogMode = "nap"; private var dogMoving = false
    var dogBark = false; private set
    var dogSleep = false; private set
    private var rnd = java.util.Random(7)

    private fun dogThink(t: Float) {
        if (t < dogNext || dogPath.isNotEmpty()) return
        dogNext = t + 3f
        val needy = state.needs.firstOrNull()?.let { agents[it] }?.takeIf { !it.away }
        val f: Int; val x: Float; val z: Float
        if (needy != null) { f = needy.floor; x = needy.x + 0.6f; z = needy.z + 0.8f; dogMode = "bark" }
        else {
            f = if (mode == "explore") player.floor else view.floor.coerceAtMost(floors.size - 1)
            val busy = agents.values.filter { it.floor == f && it.a.status == "working" && !it.away }
            if (mode == "explore" && rnd.nextFloat() < 0.4f) { x = player.x + 0.8f; z = player.z + 0.6f; dogMode = "wander" }
            else if (busy.isNotEmpty() && rnd.nextFloat() < 0.5f) { val a = busy[rnd.nextInt(busy.size)]; x = a.x + 0.3f; z = (a.goal?.slot?.deskZ ?: a.z) + 0.1f; dogMode = "nap" }
            else if (rnd.nextFloat() < 0.3f && f == 0) { x = 6.3f; z = 4.3f; dogMode = "nap" }
            else { x = -3f + rnd.nextFloat() * 9f; z = -1.5f + rnd.nextFloat() * 3f; dogMode = "wander" }
        }
        dogPath.clear()
        if (dogFloor != f) {
            pathOn(floors[dogFloor], dogX, dogZ, floors[dogFloor].doorX, floors[dogFloor].doorZ).forEach { dogPath += Way(it[0], it[1], dogFloor) }
            dogPath += Way(floors[f].doorX, floors[f].doorZ, f, true)
            pathOn(floors[f], floors[f].doorX, floors[f].doorZ, x, z).forEach { dogPath += Way(it[0], it[1], f) }
        } else pathOn(floors[f], dogX, dogZ, x, z).forEach { dogPath += Way(it[0], it[1], f) }
    }
    fun petDog(t: Float) { dogMode = "wander"; dogNext = clock + 3f }

    // ───────────── cars and weather ─────────────
    private val cars = listOf(floatArrayOf(-60f, 11.5f, 1f, 5f), floatArrayOf(-27f, 14.5f, -1f, 6f), floatArrayOf(6f, 11.5f, 1f, 7f), floatArrayOf(39f, 14.5f, -1f, 8f))
    private val carCols = intArrayOf(0xEF4444, 0xF8FAFC, 0x0EA5E9, 0xF59E0B)

    private fun applySky() {
        val s = SKY[state.sky] ?: SKY.getValue("DAY")
        val grey = state.weather in setOf("rain", "storm", "cloudy", "fog")
        skyTop = if (grey) mix(s[0], 0x6B7280, 0.5f) else s[0]; skyBottom = if (grey) mix(s[1], 0x9AA3B2, 0.5f) else s[1]
        night = state.sky == "NIGHT" || state.sky == "DUSK"
        stars = state.sky == "NIGHT" && !grey
        raining = state.weather == "rain" || state.weather == "storm"; storm = state.weather == "storm"; fog = state.weather == "fog"
        val ang = ((state.hour - 6f) / 12.5f * PI).toFloat()
        light.sx = cos(ang) * 0.8f; light.sy = max(0.35f, sin(ang)); light.sz = 0.55f; light.norm()
        light.sun = s[2]; light.sunK = (if (night) 0.35f else 0.75f) * (if (grey) 0.6f else 1f)
        light.amb = mix(skyTop, 0xFFFFFF, 0.55f); light.ambK = if (night) 0.45f else 0.62f
        inside.sx = 0.35f; inside.sy = 0.9f; inside.sz = 0.55f; inside.norm()
        inside.sun = if (night) 0xFFD7A0 else 0xFFF4E0
        inside.sunK = when { state.loadshed -> 0.3f; night -> 0.66f; else -> 0.72f }
        inside.amb = if (night) 0xFFE2B8 else mix(skyBottom, 0xFFFFFF, 0.6f)
        inside.ambK = when { state.loadshed -> 0.36f; night -> 0.62f; else -> 0.7f }
        inside.indoor = true; inside.storey = H
        light.fog = skyBottom; inside.fog = skyBottom
        light.fogStart = if (fog) 18f else 55f; light.fogEnd = if (fog) 70f else 190f; light.fogMax = if (fog) 0.8f else 0.5f
        inside.fogStart = light.fogStart + 20f; inside.fogEnd = light.fogEnd; inside.fogMax = light.fogMax * 0.6f
        val pane = mix(mix(skyBottom, skyTop, 0.4f), if (night) 0x000000 else 0xFFFFFF, if (night) 0.3f else 0.05f)
        for ((fi, t) in panes) { floors[fi].decor.col[t] = pane; floors[fi].decor.col[t + 1] = pane }
        for ((t, seed) in cityWindows) { val lit = night && (seed and 3) != 0; back.col[t] = if (lit) 0xFFD98A else 0x2A3350; back.col[t + 1] = back.col[t] }
        for (t in masjidWindows) { val c = if (state.salah || night) 0xFFD27A else 0x5A4A2A; back.col[t] = c; back.col[t + 1] = c }
        for (t in streetLamps) for (k in 0 until 36) if (t + k < front.n) front.col[t + k] = if (night) 0xFFE9A8 else 0xD1D5DB
        if (pcSignTri >= 0) floors.firstOrNull { it.room.name.contains("Lab") }?.decor?.let { it.col[pcSignTri] = if (state.pcOnline) 0x22C55E else 0x7F1D1D; it.col[pcSignTri + 1] = it.col[pcSignTri] }
    }

    // ───────────── camera, you and explore mode ─────────────
    class View { var floor = 0; var yaw = 0.65f; var pitch = 0.5f; var zoom = 1f; var tx = 0.6f; var ty = 0.9f; var tz = 0.2f; var focus: FloatArray? = null
        var cx = 30f; var cy = 30f; var cz = 40f }
    class Player { var x = 0.8f; var y = 0f; var z = 2.4f; var vy = 0f; var floor = 0; var yaw = PI.toFloat(); var look = 0.45f; var face = PI.toFloat()
        var ground = true; var moving = false; var running = false; var sitting = false; var stepPhase = 0f }
    val view = View(); val player = Player()
    var mode = "diorama"
        private set
    val cam = Cam()
    var joyX = 0f; var joyY = 0f
    var nearSpot: Spot? = null; var nearAgent: String? = null; var nearDog = false
    /** Someone you're heading to: a marker floats over them and the minimap points the way. */
    var waypoint: String? = null
    val overview get() = mode == "diorama" && view.zoom > 1.7f

    fun switchMode(m: String) {
        mode = m
        if (m == "explore") { player.floor = view.floor; player.x = 0.8f; player.z = if (view.floor == 0) 2.4f else if (view.floor == OWNER) 0.5f else 0.1f; player.y = floors[view.floor].y
            player.yaw = PI.toFloat(); player.look = 0.45f; player.sitting = false; view.focus = null }
    }
    fun goFloor(i: Int) {
        val k = i.coerceIn(0, floors.size - 1)
        view.floor = k; view.focus = null
        if (mode == "explore") { player.floor = k; player.x = floors[k].doorX + 0.4f; player.z = floors[k].doorZ + 0.9f; player.y = floors[k].y; player.vy = 0f; player.sitting = false }
    }
    fun focusAgent(id: String) {
        val r = agents[id] ?: return
        if (mode == "explore") { goFloor(r.floor); player.x = r.x + 0.2f; player.z = r.z + 1.6f; player.yaw = PI.toFloat() }
        else { view.floor = r.floor; view.zoom = min(view.zoom, 1f); view.focus = floatArrayOf(r.x, floors[r.floor].y, r.z) }
    }
    fun drag(dx: Float, dy: Float) {
        if (mode == "diorama") { view.yaw = (view.yaw - dx * 0.006f).coerceIn(0.08f, 1.45f); view.pitch = (view.pitch + dy * 0.004f).coerceIn(0.18f, 1.2f) }
        else { player.yaw -= dx * 0.006f; player.look = (player.look + dy * 0.004f).coerceIn(-0.2f, 1.1f) }
    }
    fun pinch(scale: Float) { if (mode == "diorama" && scale > 0f) { view.zoom = (view.zoom / scale).coerceIn(0.35f, 2.6f); if (view.zoom > 0.8f) view.focus = null } }
    fun jump() { if (player.sitting) { player.sitting = false; return }; if (player.ground) { player.vy = 6.5f; player.ground = false } }
    /** Sit on whatever seat you're standing next to. */
    fun sit(s: Spot) { player.sitting = true; player.x = s.x; player.z = s.z; player.face = s.face; player.floor = s.floor; player.y = floors[s.floor].y }

    private fun fitDist(): Float { val a = min(cam.w / cam.h, 1.2f); return 17.5f / (2f * tan(Math.toRadians(15.0).toFloat()) * a) }

    private fun updateCamera(dt: Float) {
        if (mode == "diorama") {
            cam.fov = 30f; cam.near = 0.5f
            val f = floors[view.floor]
            val fo = view.focus
            val gx = fo?.get(0) ?: 0.6f; val gy = (fo?.get(1) ?: f.y) + 0.9f; val gz = fo?.get(2) ?: 0.2f
            val k = min(1f, dt * 4f)
            view.tx += (gx - view.tx) * k; view.ty += (gy - view.ty) * k; view.tz += (gz - view.tz) * k
            val dist = fitDist() * view.zoom * (if (fo != null) 0.42f else 1f)
            val cp = cos(view.pitch)
            val wx = view.tx + sin(view.yaw) * cp * dist; val wy = view.ty + sin(view.pitch) * dist; val wz = view.tz + cos(view.yaw) * cp * dist
            val q = min(1f, dt * 5f)
            view.cx += (wx - view.cx) * q; view.cy += (wy - view.cy) * q; view.cz += (wz - view.cz) * q
            cam.look(view.cx, view.cy, view.cz, view.tx, view.ty, view.tz)
        } else {
            cam.fov = if (player.running) 66f else 60f; cam.near = 0.55f
            val back = if (player.sitting) 2.6f else 3.4f; val up = if (player.sitting) 1.7f else 2.2f
            var wx = player.x - sin(player.yaw) * back * cos(player.look)
            var wy = player.y + up + sin(player.look) * back * 0.6f
            var wz = player.z - cos(player.yaw) * back * cos(player.look)
            if (player.z < D / 2 + 0.2f) { wx = wx.coerceIn(-W / 2 + 0.3f, W / 2 + 4f); wz = max(-D / 2 + 0.3f, wz); wy = min(wy, player.y + H - 0.35f) }
            val q = min(1f, dt * 8f)
            view.cx += (wx - view.cx) * q; view.cy += (wy - view.cy) * q; view.cz += (wz - view.cz) * q
            cam.look(view.cx, view.cy, view.cz, player.x, player.y + 1.1f, player.z)
        }
    }

    private fun movePlayer(dt: Float) {
        val push = hypot(joyX, joyY)
        if (player.sitting && push > 0.3f) player.sitting = false
        player.running = push > 0.88f
        val sp = if (player.running) 5.6f else 3.0f
        val fx = sin(player.yaw); val fz = cos(player.yaw); val rx = -fz; val rz = fx
        val mx = (fx * -joyY + rx * joyX) * sp * dt; val mz = (fz * -joyY + rz * joyX) * sp * dt
        player.moving = push > 0.08f && !player.sitting
        if (player.moving) { player.face = atan2(mx, mz); player.stepPhase += dt * (if (player.running) 14f else 9f) }
        val f = floors[player.floor]
        if (!player.sitting) {
            val outside = player.floor == 0 && player.z > D / 2 - 0.1f
            var nx = player.x + mx; var nz = player.z + mz
            if (!outside) {
                val gap = player.floor == 0 && abs(nx) < 1.0f
                nx = nx.coerceIn(-W / 2 + 0.35f, W / 2 - 0.35f)
                nz = if (gap) max(-D / 2 + 0.35f, nz) else nz.coerceIn(-D / 2 + 0.35f, D / 2 - 0.35f)
                for (s in f.solids) if (nx > s[0] - 0.3f && nx < s[1] + 0.3f && nz > s[2] - 0.3f && nz < s[3] + 0.3f) {
                    val px = player.x; val pz = player.z
                    if (px > s[0] - 0.3f && px < s[1] + 0.3f) nz = pz else if (pz > s[2] - 0.3f && pz < s[3] + 0.3f) nx = px else { nx = px; nz = pz }
                }
            } else {
                nx = nx.coerceIn(-40f, 40f); nz = nz.coerceIn(-30f, 30f)
                if (nz < D / 2 && abs(nx) > 1.0f && nx > -W / 2 - 0.3f && nx < W / 2 + 0.3f) nz = D / 2 + 0.05f
            }
            // people are solid too: step round them
            for (r in agents.values) if (r.floor == player.floor && !r.away) {
                val d = hypot(nx - r.x, nz - r.z)
                if (d < 0.5f && d > 0.001f) { nx = r.x + (nx - r.x) / d * 0.5f; nz = r.z + (nz - r.z) / d * 0.5f }
            }
            player.x = nx; player.z = nz
        }
        player.vy -= 18f * dt; player.y += player.vy * dt
        if (player.y <= f.y) { player.y = f.y; player.vy = 0f; player.ground = true }
        var best: Spot? = null; var bd = 1.6f
        for (s in spots) if (s.floor == player.floor) { val d = hypot(s.x - player.x, s.z - player.z); if (d < bd) { bd = d; best = s } }
        nearAgent = null
        for (r in agents.values) if (r.floor == player.floor && !r.away) { val d = hypot(r.x - player.x, r.z - player.z); if (d < bd + 0.3f) { bd = d; nearAgent = r.a.id; best = null } }
        nearDog = dogFloor == player.floor && hypot(dogX - player.x, dogZ - player.z) < 1.2f
        nearSpot = if (player.sitting) null else best
    }

    // ───────────── each frame ─────────────
    private var clock = 0f
    fun step(dt: Float) {
        clock += dt
        val t = clock
        for (r in agents.values) think(r, t)
        for (r in agents.values) moveAgent(r, dt, t)
        agents.entries.removeAll { it.value.fired && it.value.away }
        dogThink(t); moveDog(dt)
        if (mode == "explore") movePlayer(dt)
        gongSwing = max(0f, gongSwing - dt * 0.4f)
        for (c in cars) { c[0] += c[2] * c[3] * dt; if (c[0] > 70f) c[0] = -70f; if (c[0] < -70f) c[0] = 70f }
        // elevator doors open when someone's close
        for (f in floors) {
            var want = 0f
            if (mode == "explore" && player.floor == f.i && hypot(player.x - f.doorX, player.z - f.doorZ) < 1.8f) want = 1f
            for (r in agents.values) if (r.floor == f.i && !r.away && hypot(r.x - f.doorX, r.z - f.doorZ) < 1.6f) want = 1f
            if (dogFloor == f.i && hypot(dogX - f.doorX, dogZ - f.doorZ) < 1.4f) want = 1f
            f.doorOpen += (want - f.doorOpen) * min(1f, dt * 4f)
        }
        updateCamera(dt)
    }

    private fun think(r: Rt, t: Float) {
        if (r.path.isNotEmpty()) return
        val g = desired(r, t)
        if (g == null) {
            if (!r.away && r.goal?.key != "home") { r.goal = Goal("home", 0, 0f, 12f, 0f, "stand"); goHome(r) }
            return
        }
        if (g.key != r.goal?.key || (g.floor != r.floor && !r.away)) { r.goal = g; routeTo(r, g.floor, g.x, g.z) }
        else r.goal = g
        // small talk while chatting, and a reply from the partner
        if (r.idleAct == "chat" && r.path.isEmpty() && t >= r.nextLine && r.partner != null) {
            val p = agents[r.partner!!]
            if (p != null && p.path.isEmpty() && hypot(p.x - r.x, p.z - r.z) < 1.4f) {
                r.say = (CHATTER[r.a.persona] ?: CHATTER.getValue("careful")).randomOrNull(r.rnd).orEmpty(); r.sayUntil = t + 3.2f
                p.say = REPLIES.randomOrNull(r.rnd).orEmpty(); p.sayUntil = t + 5.8f
                r.nextLine = t + 7f + r.rnd.nextFloat() * 4f; p.nextLine = t + 9f
                chatter += "${r.a.emoji} ${r.a.name} → ${p.a.name}: ${r.say}"
                if (chatter.size > 30) chatter.removeAt(0)
            }
        }
        if (r.idleAct == "coffee" && r.path.isEmpty() && r.prop != "mug") { r.prop = "mug"; r.propUntil = t + 40f }
        if (r.a.visit.isNotBlank() && r.prop.isEmpty()) { r.prop = "papers"; r.propUntil = t + 20f }
    }
    /** Office small talk as it happens, for the screen's chat feed. */
    val chatter = ArrayList<String>()

    private fun moveAgent(r: Rt, dt: Float, t: Float) {
        r.moving = false
        if (r.prop.isNotEmpty() && t > r.propUntil) r.prop = ""
        if (r.path.isNotEmpty()) {
            val w = r.path[0]
            if (w.jump) { r.x = w.x; r.z = w.z; r.floor = w.floor; r.path.removeAt(0); return }
            val urgent = r.a.urgent || r.a.status == "needs"
            val speed = when { r.fired -> 1.6f; urgent -> 4.2f; r.a.persona == "bold" -> 2.8f; r.a.persona == "quiet" -> 2.0f; else -> 2.4f }
            r.running = speed > 3.5f
            val dx = w.x - r.x; val dz = w.z - r.z; val dist = hypot(dx, dz); val step = speed * dt
            r.face = lerpAngle(r.face, atan2(dx, dz), min(1f, dt * 10f))
            if (dist <= step) { r.x = w.x; r.z = w.z; r.floor = w.floor; r.path.removeAt(0); if (w.hide) r.away = true }
            else { r.x += dx / dist * step; r.z += dz / dist * step; r.moving = true }
            // keep a little distance from others on the way
            if (r.moving) for (o in agents.values) if (o !== r && o.floor == r.floor && !o.away) {
                val ox = r.x - o.x; val oz = r.z - o.z; val d = hypot(ox, oz)
                if (d < 0.45f && d > 0.001f) { r.x += ox / d * (0.45f - d) * 0.5f; r.z += oz / d * (0.45f - d) * 0.5f }
            }
        } else {
            val look = r.lookAt
            val want = if (look != null && t < r.lookUntil) atan2(look[0] - r.x, look[1] - r.z) else r.goal?.face ?: r.face
            r.face = lerpAngle(r.face, want, min(1f, dt * 6f))
        }
        if (r.hop > 0f) r.hop -= dt
        // greet you when you walk up in explore mode
        if (mode == "explore" && r.floor == player.floor && !r.away && t - r.greetAt > 45f && hypot(r.x - player.x, r.z - player.z) < 2.2f) {
            r.greetAt = t; r.say = GREETS[r.a.persona] ?: "Salam, boss."; r.sayUntil = t + 3.5f
            r.lookAt = floatArrayOf(player.x, player.z); r.lookUntil = t + 4f
        }
    }
    private fun moveDog(dt: Float) {
        dogMoving = false
        if (dogPath.isNotEmpty()) {
            val w = dogPath[0]
            if (w.jump) { dogX = w.x; dogZ = w.z; dogFloor = w.floor; dogPath.removeAt(0) }
            else {
                val dx = w.x - dogX; val dz = w.z - dogZ; val dist = hypot(dx, dz); val step = (if (dogMode == "bark") 4f else 2f) * dt
                dogFace = atan2(dx, dz)
                if (dist <= step) { dogX = w.x; dogZ = w.z; dogFloor = w.floor; dogPath.removeAt(0) } else { dogX += dx / dist * step; dogZ += dz / dist * step; dogMoving = true }
            }
        }
        dogBark = !dogMoving && dogMode == "bark" && (clock % 2.2f) < 0.7f
        dogSleep = !dogMoving && dogMode == "nap"
    }
    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var d = ((b - a + PI.toFloat()) % (2 * PI.toFloat())) - PI.toFloat(); if (d < -PI.toFloat()) d += 2 * PI.toFloat(); return a + d * t
    }

    // ───────────── drawing people ─────────────
    /** A little person: feet, body, arms, a face that blinks, a cap, a level badge, and whatever they carry. */
    private fun character(m: Mesh, sh: Mesh, x: Float, y: Float, z: Float, face: Float, color: Int, status: String, pose: String, t: Float,
                          seated: Float = 0f, prop: String = "", level: Int = -1): Float {
        var by = 0f; var bodyRx = 0f; var headRx = 0f; var headRz = 0f; var headRy = 0f; var aL = 0f; var aR = 0f; var aLz = 0f; var aRz = 0f
        var legL = 0f; var legR = 0f
        var bulb = STATUS[status] ?: 0x9CA3AF
        when (pose) {
            "walk" -> { by = abs(sin(t * 9)) * 0.06f; aL = sin(t * 9) * 0.7f; aR = -aL; legL = sin(t * 9) * 0.6f; legR = -legL }
            "run" -> { by = abs(sin(t * 14)) * 0.12f; aL = sin(t * 14) * 1.2f; aR = -aL; legL = sin(t * 14) * 1.0f; legR = -legL; bodyRx = 0.2f }
            "type", "web", "money" -> { by = sin(t * 18) * 0.01f; aL = -1.1f + sin(t * 22) * 0.2f; aR = -1.1f + cos(t * 20) * 0.2f; headRx = 0.18f + sin(t * 1.3f) * 0.04f }
            "read" -> { aL = -1.2f; aR = -1.2f; headRx = 0.3f + sin(t * 0.8f) * 0.05f }
            "call" -> { aR = -2.6f; aRz = 0.35f; aL = -0.2f; headRz = 0.12f + sin(t * 2f) * 0.05f }
            "phone" -> { aR = -1.4f; aL = -1.3f; headRx = 0.45f; headRy = sin(t * 0.7f) * 0.1f }
            "talk" -> { aL = -0.7f + sin(t * 3) * 0.5f; aR = -0.4f + cos(t * 2.3f) * 0.45f; by = abs(sin(t * 2.5f)) * 0.03f; headRz = sin(t * 1.5f) * 0.1f; headRx = sin(t * 2.1f) * 0.08f }
            "listen" -> { headRz = sin(t * 0.9f) * 0.06f; headRx = 0.06f + sin(t * 1.7f) * 0.05f; aL = -0.15f; aR = -0.15f }
            "sip" -> { val c = t % 6f; aR = if (c < 1.2f) -2.3f else -0.9f; aL = -0.1f; headRx = if (c < 1.2f) -0.15f else 0.05f }
            "stretch" -> { val c = t % 4f; aL = -3.0f + sin(t * 2) * 0.2f; aR = -3.0f - sin(t * 2) * 0.2f; aLz = -0.3f; aRz = 0.3f; by = if (c < 2f) 0.05f else 0f }
            "box" -> { by = abs(sin(t * 7)) * 0.04f; aL = -1.3f; aR = -1.3f; legL = sin(t * 7) * 0.5f; legR = -legL; headRx = 0.2f }
            "needs" -> { by = abs(sin(t * 6)) * 0.35f; aR = -2.9f + sin(t * 10) * 0.25f; aRz = 0.2f; if (floor(t * 4).toInt() % 2 == 0) bulb = 0xFECACA }
            "done" -> { val hh = t % 3.5f; if (hh < 0.5f) by = sin(hh / 0.5f * PI.toFloat()) * 0.25f; if (hh < 0.6f) { aL = -2.8f; aR = -2.8f } }
            "asleep" -> { headRx = 0.5f; by = sin(t * 1.2f) * 0.015f }
            "pray" -> { val cy = t % 9f
                when { cy < 3.5f -> { aL = -0.9f; aR = -0.9f; aLz = -0.5f; aRz = 0.5f }; cy < 5.5f -> { bodyRx = 1.0f; aL = -0.6f; aR = -0.6f }
                    cy < 8f -> { bodyRx = 1.45f; by = -0.45f; aL = -2.6f; aR = -2.6f }; else -> by = -0.35f } }
            else -> { by = sin(t * 1.6f) * 0.012f; headRz = sin(t * 0.5f) * 0.05f; headRy = sin(t * 0.31f) * 0.25f }
        }
        if (seated > 0f) { by += seated; legL = -1.45f; legR = -1.45f }
        val blink = (t % 4.3f) < 0.12f
        val skin = mix(color, 0xFFE9D6, 0.62f)
        val dark = mix(color, 0x000000, 0.35f)
        if (seated <= 0f) sh.with(M4.t(x, y, z)) { sh.disc(0.34f, 0f, 0.013f, 0f, 0x000000, 12, EMIT) }
        m.with(M4.t(x, y, z).mul(M4.ry(face))) {
            for ((side, a) in listOf(-1f to legL, 1f to legR)) m.with(M4.t(side * 0.12f, 0.22f + seated, 0f).mul(M4.rx(a))) {
                if (seated > 0f) m.sphere(0.08f, 0f, 0f, 0.14f, dark, 8, 4, 1.4f)
                m.sphere(0.085f, 0f, -0.14f, 0.04f, dark, 8, 4, 0.7f)
            }
            m.with(M4.t(0f, by, 0f).mul(M4.rx(bodyRx))) {
                m.sphere(0.28f, 0f, 0.6f, 0f, color, 12, 7, 1.4f)
                if (level >= 0) { m.bias = 0.1f; m.sphere(0.045f, 0.12f, 0.82f, 0.24f, LEVEL_COLORS[level.coerceIn(0, 4)], 6, 3, 1f, EMIT); m.bias = 0f }
                m.with(M4.t(0f, 1.2f, 0f).mul(M4.ry(headRy)).mul(M4.rx(headRx)).mul(M4.rz(headRz))) {
                    m.begin()
                    m.sphere(0.27f, 0f, 0f, 0f, skin, 12, 5, 1f, 0, -1.5707964f, 0.28f)
                    m.sphere(0.285f, 0f, 0f, 0f, color, 12, 3, 1f, 0, 0.28f, 1.5707964f)
                    m.with(M4.t(0f, 0.075f, 0.2f).mul(M4.rx(0.18f))) { m.sphere(0.16f, 0f, 0f, 0f, color, 10, 2, 0.22f, 0, 0f, 1.5707964f) }
                    m.end()
                    m.bias = 0.15f
                    val ey = if (blink) 0.15f else 1f
                    m.sphere(0.042f, -0.09f, 0.02f, 0.25f, 0x111827, 6, 3, ey); m.sphere(0.042f, 0.09f, 0.02f, 0.25f, 0x111827, 6, 3, ey)
                    m.front(0.1f, 0.022f, 0f, -0.09f, 0.262f, 0x7C2D12)
                    m.bias = 0f
                    m.cyl(0.012f, 0.012f, 0.22f, 0f, 0.38f, 0f, 0x6B7280, 4)
                    m.sphere(0.065f, 0f, 0.52f, 0f, bulb, 8, 4, 1f, EMIT)
                }
                for ((side, ax, az) in listOf(Triple(-1f, aL, aLz), Triple(1f, aR, aRz))) {
                    m.with(M4.t(side * 0.3f, 0.84f, 0f).mul(M4.rx(ax)).mul(M4.rz(az))) {
                        m.sphere(0.075f, 0f, -0.2f, 0f, color, 8, 4, 2.6f)
                        m.sphere(0.06f, 0f, -0.4f, 0f, skin, 6, 3)
                        if (side > 0 && pose == "call") m.box(0.06f, 0.16f, 0.03f, 0f, -0.42f, 0.07f, 0x111827)
                        if (side > 0 && prop == "mug") { m.cyl(0.05f, 0.045f, 0.1f, 0f, -0.46f, 0.07f, 0xFFFFFF, 8); m.cyl(0.045f, 0.045f, 0.005f, 0f, -0.41f, 0.07f, 0x6B3E1F, 8) }
                        if (side > 0 && pose == "phone") m.box(0.07f, 0.13f, 0.015f, 0f, -0.44f, 0.08f, 0x111827)
                    }
                }
                if (pose == "read" || prop == "papers") m.with(M4.t(0f, 0.8f, 0.36f).mul(M4.rx(-0.5f))) { m.front(0.26f, 0.34f, 0f, 0f, 0f, 0xFFFFFF, EMIT or TWO) }
                if (prop == "box") { m.bias = 0.2f; m.box(0.46f, 0.3f, 0.34f, 0f, 0.72f, 0.4f, 0xC8A574); m.sphere(0.09f, -0.1f, 0.94f, 0.4f, 0x3F8F4A, 6, 3); m.front(0.1f, 0.14f, 0.1f, 0.95f, 0.42f, 0x1E3A8A); m.bias = 0f }
            }
            if (pose == "web") m.with(M4.t(0.42f, 1.6f + by, 0.2f).mul(M4.ry(t * 1.5f))) { m.sphere(0.16f, 0f, 0f, 0f, 0x38BDF8, 8, 5, 1f, EMIT) }
            if (pose == "money") { val k = 2 + ((sin(t * 3) + 1) * 1.5f).toInt(); for (c in 0 until k) m.cyl(0.07f, 0.07f, 0.03f, -0.35f, 0.83f + c * 0.035f, 0.45f, 0xEAB308, 8) }
        }
        return by
    }

    private fun drawDog(m: Mesh, sh: Mesh, y: Float, t: Float) {
        val tan = 0xD9A066; val dark = 0x7C4A1E
        val sleepy = !dogMoving && dogMode == "nap"
        sh.with(M4.t(dogX, y, dogZ)) { sh.disc(0.25f, 0f, 0.013f, 0f, 0x000000, 10, EMIT) }
        m.with(M4.t(dogX, y, dogZ).mul(M4.ry(dogFace - PI.toFloat() / 2)).mul(M4.s(1f, if (sleepy) 0.7f else 1f, 1f))) {
            m.with(M4.s(1.5f, 1f, 1f)) { m.sphere(0.17f, 0f, 0.32f, 0f, tan, 10, 5) }
            val hz = if (sleepy) -0.4f else if (dogBark) sin(t * 10) * 0.15f + 0.2f else 0f
            m.with(M4.t(0.33f, 0.5f, 0f).mul(M4.rz(hz))) {
                m.sphere(0.14f, 0f, 0f, 0f, tan, 10, 5)
                m.sphere(0.07f, 0.12f, -0.04f, 0f, 0xF5DEB3, 8, 4)
                m.sphere(0.06f, -0.03f, 0.1f, 0.11f, dark, 6, 3, 1.4f); m.sphere(0.06f, -0.03f, 0.1f, -0.11f, dark, 6, 3, 1.4f)
                m.bias = 0.1f
                m.sphere(0.025f, 0.19f, -0.02f, 0f, 0x111827, 6, 3); m.sphere(0.02f, 0.1f, 0.04f, 0.07f, 0x111827, 5, 3); m.sphere(0.02f, 0.1f, 0.04f, -0.07f, 0x111827, 5, 3)
                m.bias = 0f
            }
            for ((i, lx, lz) in listOf(Triple(0, 0.16f, 0.09f), Triple(1, 0.16f, -0.09f), Triple(2, -0.16f, 0.09f), Triple(3, -0.16f, -0.09f))) {
                val a = if (dogMoving) sin(t * 16 + i * PI.toFloat()) * 0.5f else 0f
                m.with(M4.t(lx, 0.2f, lz).mul(M4.rz(a))) { m.cyl(0.035f, 0.035f, 0.2f, 0f, -0.1f, 0f, tan, 6) }
            }
            m.with(M4.t(-0.28f, 0.4f, 0f).mul(M4.rz(0.8f)).mul(M4.rx(sin(t * (if (dogMode == "bark") 20 else 8)) * 0.6f))) { m.cyl(0.02f, 0.035f, 0.22f, 0f, 0.1f, 0f, dark, 6) }
        }
    }

    // ───────────── render ─────────────
    val labels = LinkedHashMap<String, HqLabel>()
    private val pp = FloatArray(3)

    fun render(r: Renderer, w: Float, h: Float) {
        cam.w = w; cam.h = h
        val t = clock
        worldDyn.clear()
        for ((i, c) in cars.withIndex()) worldDyn.with(M4.t(c[0], 0f, c[1]).mul(M4.ry(if (c[2] > 0) 0f else PI.toFloat()))) {
            worldDyn.box(2.2f, 0.55f, 1.1f, 0f, 0.45f, 0f, carCols[i]); worldDyn.bias = 0.3f; worldDyn.box(1.2f, 0.45f, 1.0f, -0.1f, 0.95f, 0f, 0xDFE8F5); worldDyn.bias = 0f
            for ((wx, wz) in listOf(-0.7f to 0.56f, 0.7f to 0.56f, -0.7f to -0.56f, 0.7f to -0.56f)) worldDyn.with(M4.t(wx, 0.22f, wz).mul(M4.rx(PI.toFloat() / 2))) { worldDyn.cyl(0.22f, 0.22f, 0.12f, 0f, 0f, 0f, 0x111827, 10) }
            worldDyn.box(0.05f, 0.14f, 0.7f, 1.11f, 0.5f, 0f, if (night) 0xFFF6C0 else 0xE5E7EB, EMIT)
            worldDyn.box(0.05f, 0.12f, 0.6f, -1.11f, 0.5f, 0f, 0xDC2626, EMIT)
        }
        for (f in floors) { f.dyn.clear(); f.dynDecal.clear() }
        for (a in agents.values) {
            if (a.away) { a.bodyY = 0f; continue }
            val f = if (a.floor in floors.indices) floors[a.floor] else floors[0]
            val onStreet = a.floor == 0 && a.z > D / 2
            val dm = if (onStreet) worldDyn else f.dyn
            val walking = a.moving
            val g = a.goal
            val atGoal = !walking && a.path.isEmpty()
            val talking = a.a.say.isNotBlank() || (a.say.isNotBlank() && t < a.sayUntil)
            val seated = when { !atGoal -> 0f; g?.mode == "desk" -> 0.3f; g?.mode == "bean" -> 0.12f; g?.mode == "seat" -> 0.3f; else -> 0f }
            val pose = when {
                a.fired -> "box"
                walking -> if (a.running) "run" else "walk"
                g?.mode == "pray" -> "pray"
                a.a.status == "needs" && g?.key?.startsWith("bench") != true -> "needs"
                a.a.status == "asleep" -> "asleep"
                talking -> "talk"
                g?.key?.startsWith("table") == true -> if (state.speaking == a.a.id) "talk" else "listen"
                g?.key?.startsWith("visit") == true -> "talk"
                a.a.status == "done" -> "done"
                a.a.status == "working" -> a.a.pose.ifBlank { "type" }
                a.idleAct == "phone" -> "phone"
                a.idleAct == "chat" -> "listen"
                a.idleAct == "coffee" || a.prop == "mug" -> "sip"
                a.idleAct == "stretch" -> "stretch"
                a.idleAct == "read" -> "read"
                a.idleAct == "board" -> "listen"
                else -> "idle"
            }
            val hop = if (a.hop > 0f) abs(sin(a.hop * 8f)) * 0.3f else 0f
            a.bodyY = character(dm, if (onStreet) worldDyn else f.dynDecal, a.x, f.y + hop, a.z, a.face, a.a.color, a.a.status, pose, t + a.phase, seated, a.prop, a.a.level) + hop
            if (a.a.urgent && !walking) f.dynDecal.with(M4.t(a.x, f.y + 0.015f, a.z)) { f.dynDecal.disc(0.5f, 0f, 0f, 0f, 0xEF4444, 14, EMIT) }
            g?.slot?.takeIf { atGoal && g.mode == "desk" }?.let { s ->
                f.dyn.bias = 0.9f
                f.dyn.with(M4.t(s.x, f.y + 0.82f, s.deskZ + 0.21f).mul(M4.rx(-0.35f))) {
                    f.dyn.front(0.44f, 0.28f, 0f, 0.17f, 0.012f, if (a.a.status == "asleep") 0x334155 else if (a.a.status == "working") mix(a.a.color, 0xFFFFFF, 0.25f) else mix(a.a.color, 0x000000, 0.35f), EMIT)
                }
                f.dyn.bias = 0f
            }
        }
        drawDog(floors[dogFloor].dyn, floors[dogFloor].dynDecal, floors[dogFloor].y, t)
        floors.firstOrNull()?.let { f ->
            val v = state.jar.coerceIn(0.04f, 1f)
            f.dyn.bias = 0.9f
            f.dyn.cyl(0.25f, 0.25f, 0.66f * v, jarAt[0], jarAt[1] + 0.33f * v, jarAt[2], if (v > 0.5f) 0x22C55E else if (v > 0.2f) 0xF59E0B else 0xEF4444, 10)
            val n = min(12, state.approvals); if (n > 0) f.dyn.box(0.5f, 0.02f * n, 0.36f, -6.2f, 0.96f + 0.01f * n, -0.6f, 0xFFFFFF)
            val c = min(12, state.changes); if (c > 0) f.dyn.box(0.5f, 0.02f * c, 0.36f, -6.2f, 0.96f + 0.01f * c, 0.5f, 0xE0F2FE)
            f.dyn.bias = 0f
            f.dyn.with(M4.t(6.2f, 1.75f, 2.0f).mul(M4.rx(sin(t * 9) * 0.35f * gongSwing))) { f.dyn.with(M4.rx(PI.toFloat() / 2)) { f.dyn.cyl(0.45f, 0.45f, 0.05f, 0f, 0f, 0.55f, 0xD4A017, 16) } }
        }
        // elevator doors slide open for whoever comes near
        for (f in floors) {
            val o = f.doorOpen * 0.58f
            f.dyn.with(M4.t(0f, f.y, 0f)) {
                f.dyn.front(0.62f, 2.2f, -6.22f - o, 1.1f, -3.39f, 0xCBD5E1); f.dyn.front(0.62f, 2.2f, -5.58f + o, 1.1f, -3.39f, 0xCBD5E1)
                if (f.doorOpen > 0.05f) f.dyn.front(1.2f * f.doorOpen, 2.2f, -5.9f, 1.1f, -3.42f, 0x3F3F46, EMIT)
            }
        }
        if (state.salah) for (a in agents.values) if (a.goal?.mode == "pray" && a.path.isEmpty() && !a.away) {
            val f = floors[a.floor]
            f.dynDecal.with(M4.t(a.x, f.y + 0.012f, a.z).mul(M4.ry(qiblaYaw()))) { f.dynDecal.floor(0.75f, 1.3f, 0f, 0f, 0.25f, 0x166534, 2f) }
        }
        if (mode == "explore") {
            val f = floors[player.floor]
            val onStreet = player.floor == 0 && player.z > D / 2
            val pose = if (player.sitting) "idle" else if (player.moving && player.ground) (if (player.running) "run" else "walk") else "idle"
            character(if (onStreet) worldDyn else f.dyn, if (onStreet) worldDyn else f.dynDecal, player.x, player.y, player.z, player.face, 0xF97316, "idle", pose,
                if (player.moving) player.stepPhase / 9f else t, if (player.sitting) 0.3f else 0f, "", 4)
        }
        // a floating marker over whoever you're heading to
        waypoint?.let { agents[it] }?.takeIf { !it.away }?.let { a ->
            val f = floors[a.floor]
            f.dyn.bias = 2f
            f.dyn.with(M4.t(a.x, f.y + 2.55f + a.bodyY + sin(t * 3) * 0.08f, a.z).mul(M4.ry(t * 2f)).mul(M4.s(1f, 1.5f, 1f))) {
                f.dyn.cyl(0f, 0.16f, 0.2f, 0f, -0.1f, 0f, 0xFBBF24, 4, EMIT); f.dyn.cyl(0.16f, 0f, 0.2f, 0f, 0.1f, 0f, 0xFDE68A, 4, EMIT)
            }
            f.dyn.bias = 0f
        }
        for (f in floors) for (i in 0 until f.dynDecal.n) if (f.dynDecal.col[i] == 0x000000) f.dynDecal.col[i] = mix(mix(0xE3CFAE, f.room.color, 0.06f), 0x3B3326, 0.3f)
        for (i in 0 until worldDyn.n) if (worldDyn.col[i] == 0x000000) worldDyn.col[i] = 0x6B8F5A

        updateCamera(0f)
        r.begin()
        r.layer(cam, light, ground); r.layer(cam, light, roads); r.layer(cam, light, marks)
        r.layer(cam, light, back)
        val top = when { mode == "explore" -> player.floor; overview -> floors.size - 1; else -> view.floor }
        val bottom = if (mode == "explore") player.floor else 0
        for (k in bottom..top) {
            val f = floors[k]
            r.layer(cam, inside, f.base); r.layer(cam, inside, f.under); r.layer(cam, inside, f.decal, f.dynDecal)
            r.layer(cam, inside, f.walls); r.layer(cam, inside, f.decor); r.layer(cam, inside, f.main, f.dyn)
        }
        if (overview) r.layer(cam, light, roof)
        r.layer(cam, light, front, worldDyn)
        placeLabels(w, h, t)
    }

    private fun label(key: String, kind: String, id: String) = labels.getOrPut(key) { HqLabel(key, kind, id) }
    var cardW = 170f; var cardH = 40f; var sayH = 58f
    var topInset = 0f; var bottomInset = 0f
    private fun spread() {
        val list = labels.values.filter { it.shown && (it.kind == "agent" || it.kind == "say") }.sortedByDescending { it.y }
        val placed = ArrayList<FloatArray>()
        for (l in list) {
            val hh = if (l.kind == "say") sayH else cardH
            var y = l.y
            var moved = true; var guard = 0
            while (moved && guard++ < 12) {
                moved = false
                for (r in placed) if (abs(l.x - r[0]) < cardW * 0.95f && y > r[1] - r[2] - 2f && y - hh < r[1] + 2f) { y = r[1] - r[2] - 4f; moved = true }
            }
            if (l.y - y > hh * 3f) { l.shown = false; continue }
            l.y = y
            if (y - hh < topInset) l.shown = false
            placed += floatArrayOf(l.x, y, hh)
        }
    }
    private fun project(x: Float, y: Float, z: Float, l: HqLabel, w: Float, h: Float, show: Boolean) {
        if (!show) { l.shown = false; return }
        cam.project(x, y, z, pp)
        l.shown = pp[2] > 0.3f && pp[0] > -60 && pp[0] < w + 60 && pp[1] > topInset && pp[1] < h - bottomInset
        l.x = pp[0]; l.y = pp[1]
    }
    private fun placeLabels(w: Float, h: Float, t: Float) {
        val shownFloor = if (mode == "explore") player.floor else view.floor
        val close = mode == "explore" || view.focus != null || view.zoom < 1.25f
        val seen = HashSet<String>()
        for (f in floors) {
            val l = label("sign:${f.i}", "sign", "${f.i}"); seen += l.key
            l.text = f.room.emoji + " " + f.room.name; l.color = f.room.color
            project(-1.5f, f.y + 2.75f, -D / 2 + 0.2f, l, w, h, f.i == shownFloor && !overview && mode != "explore")
        }
        if (floors.isNotEmpty()) {
            fun board(key: String, text: String, sub: String, x: Float, y: Float, z: Float, floor: Int = 0) {
                val l = label(key, "board", key); seen += l.key; l.text = text; l.sub = sub
                val nearYou = mode != "explore" || hypot(player.x - x, player.z - z) < 5f
                project(x, floors[floor].y + y, z, l, w, h, shownFloor == floor && (mode == "explore" || view.zoom < 0.8f) && view.focus == null && nearYou)
            }
            board("screen", "ATLAS HQ · " + state.summary, listOfNotNull(state.prayer.ifBlank { null }, if (state.loadshed) "⚡ On backup power" else null).joinToString(" · "), 2.6f, 2.5f, -D / 2 + 0.2f)
            board("routine", "⏰ " + state.routine.ifBlank { "No routines" }, "", -3.6f, 2.2f, -D / 2 + 0.2f)
            board("plugs", if (state.plugsKnown) "🔌 ${state.plugsOk}/${state.plugsTotal} connectors ok" else "🔌 ${state.plugsTotal} connected", "", -W / 2 + 0.3f, 2.9f, 1.9f)
            if (state.boardText.isNotBlank()) board("kanban", "📋 " + state.boardText, "", -4.3f, 2.0f, 1.25f, shownFloor.coerceIn(0, floors.size - 1).takeIf { it != OWNER } ?: 0)
        }
        for (a in agents.values) {
            val l = label("a:" + a.a.id, "agent", a.a.id); seen += l.key
            l.text = a.a.emoji + " " + a.a.name; l.sub = a.a.card; l.line = a.a.line; l.color = a.a.color; l.status = a.a.status; l.near = close
            l.level = a.a.level; l.urgent = a.a.urgent
            val f = if (a.floor in floors.indices) floors[a.floor] else floors[0]
            val on = a.floor == shownFloor && !overview && !a.away
            val localSay = if (a.say.isNotBlank() && t < a.sayUntil) a.say else ""
            val sayText = a.a.say.ifBlank { localSay }
            val talking = sayText.isNotBlank()
            val atTable = a.goal?.key?.startsWith("table") == true && a.path.isEmpty()
            project(a.x, f.y + a.bodyY + 1.95f, a.z, l, w, h, on && !talking && (if (atTable) a.a.status == "needs" else close || a.a.status in setOf("needs", "working", "done")))
            val sb = label("s:" + a.a.id, "say", a.a.id); seen += sb.key
            sb.text = a.a.emoji + " " + a.a.name; sb.line = sayText; sb.color = a.a.color
            project(a.x, f.y + a.bodyY + 2.0f, a.z, sb, w, h, on && talking && (!atTable || state.speaking.isNullOrBlank() || state.speaking == a.a.id || a.a.say.isNotBlank()))
            val b = label("b:" + a.a.id, "bubble", a.a.id); seen += b.key
            b.text = if (a.a.status == "asleep" && a.path.isEmpty()) "z z z" else ""
            project(a.x + 0.35f, f.y + 2.45f + sin(t * 3) * 0.05f, a.z, b, w, h, on && b.text.isNotEmpty())
        }
        spread()
        val d = label("dog", "bubble", "dog"); seen += d.key
        d.text = if (dogBark) "Woof!" else if (dogSleep) "zz" else ""
        project(dogX, floors[dogFloor].y + 0.95f, dogZ, d, w, h, dogFloor == shownFloor && d.text.isNotEmpty() && !overview)
        labels.keys.retainAll(seen)
    }

    /** What's under a tap at (x, y), if anything. */
    fun pick(x: Float, y: Float, radius: Float): Pair<String, String>? {
        val shownFloor = if (mode == "explore") player.floor else view.floor
        var best: Pair<String, String>? = null; var bd = radius
        for (a in agents.values) if ((a.floor == shownFloor || overview) && !a.away) {
            cam.project(a.x, floors[a.floor].y + 1.0f, a.z, pp); if (pp[2] <= 0) continue
            val d = hypot(pp[0] - x, pp[1] - y) * 0.8f
            if (d < bd) { bd = d; best = "agent" to a.a.id }
            val l = labels["a:" + a.a.id]
            if (l != null && l.shown && abs(l.x - x) < 90f && y < l.y && y > l.y - 70f) { bd = 0f; best = "agent" to a.a.id }
        }
        for (s in spots) if (s.floor == shownFloor) {
            cam.project(s.x, floors[s.floor].y + s.ay, s.z, pp); if (pp[2] <= 0) continue
            val d = hypot(pp[0] - x, pp[1] - y)
            if (d < bd) { bd = d; best = s.kind to s.id }
        }
        cam.project(dogX, floors[dogFloor].y + 0.4f, dogZ, pp)
        if (dogFloor == shownFloor && pp[2] > 0 && hypot(pp[0] - x, pp[1] - y) < bd) best = "pet" to ""
        if (best?.first == "agent" && mode == "diorama") agents[best.second]?.let { view.focus = floatArrayOf(it.x, floors[it.floor].y, it.z) }
        if (best?.first == "gong") celebrate()
        if (best?.first == "pet") petDog(clock)
        return best
    }

    // ───────────── the minimap ─────────────
    class Dot(val x: Float, val z: Float, val color: Int, val status: String, val id: String)
    class MiniMap(val floor: Int, val solids: List<FloatArray>, val dots: List<Dot>, val px: Float, val pz: Float, val yaw: Float,
                  val target: FloatArray?, val targetFloor: Int, val dogX: Float, val dogZ: Float, val dogHere: Boolean)
    fun minimap(): MiniMap {
        val fl = if (mode == "explore") player.floor else view.floor
        val f = floors[fl]
        val dots = agents.values.filter { it.floor == fl && !it.away }.map { Dot(it.x, it.z, it.a.color, it.a.status, it.a.id) }
        val tgt = waypoint?.let { agents[it] }?.takeIf { !it.away }
        return MiniMap(fl, f.solids, dots, player.x, player.z, player.yaw, tgt?.let { floatArrayOf(it.x, it.z) }, tgt?.floor ?: -1, dogX, dogZ, dogFloor == fl)
    }
    /** How far you are from someone: metres, and floors apart. */
    fun distanceTo(id: String): Pair<Float, Int>? {
        val a = agents[id]?.takeIf { !it.away } ?: return null
        return hypot(a.x - player.x, a.z - player.z) to (a.floor - player.floor)
    }

    init { build() }
}

private fun <T> List<T>.randomOrNull(r: java.util.Random): T? = if (isEmpty()) null else this[r.nextInt(size)]
@Suppress("unused") private fun sq(x: Float) = sqrt(x)
