package com.nizam.app.feature.pc

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A small VNC (RFB 3.8) client for Atlas's PC: raw and copy-rect frames into a bitmap, plus mouse and keyboard.
 * Both the screen you see and the screenshots Atlas looks at come from here.
 */
object Vnc {
    var connected by mutableStateOf(false)
    var frame by mutableIntStateOf(0)          // bumps when the picture changes
    var width by mutableIntStateOf(0); var height by mutableIntStateOf(0)
    var error by mutableStateOf("")
    @Volatile var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    @Volatile private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val lock = Any()
    private var buttons = 0
    @Volatile private var dirty = false

    @Volatile private var wanted = false
    @Volatile private var looping = false

    /** Connects, and keeps reconnecting by itself if the link drops, until [disconnect]. */
    fun connect(port: Int = PcEnv.PORT) {
        wanted = true
        if (looping) return
        looping = true
        Thread {
            var fails = 0
            try {
                while (wanted && fails < 60) {
                    try { session(port); fails = 0 } catch (e: Throwable) { if (wanted) error = e.message ?: "Can't reach the screen"; fails++ }
                    if (wanted) try { Thread.sleep(1200) } catch (_: InterruptedException) { }
                }
            } finally { looping = false }
        }.apply { isDaemon = true; start() }
    }

    fun disconnect() { wanted = false; runCatching { socket?.close() }; socket = null; connected = false }

    private fun session(port: Int) {
        val s = Socket(); s.connect(InetSocketAddress("127.0.0.1", port), 3000); s.tcpNoDelay = true
        socket = s
        val inp = DataInputStream(s.getInputStream().buffered(1 shl 16)); val o = DataOutputStream(s.getOutputStream().buffered(4096))
        out = o
        val ver = ByteArray(12); inp.readFully(ver)
        o.write("RFB 003.008\n".toByteArray()); o.flush()
        val n = inp.readUnsignedByte()
        if (n == 0) { val len = inp.readInt(); val b = ByteArray(len); inp.readFully(b); throw IllegalStateException(String(b)) }
        val types = ByteArray(n); inp.readFully(types)
        if (types.none { it.toInt() == 1 }) throw IllegalStateException("The screen server wants a password")
        o.writeByte(1); o.flush()
        if (inp.readInt() != 0) throw IllegalStateException("The screen server refused the connection")
        o.writeByte(1); o.flush() // shared
        val w = inp.readUnsignedShort(); val h = inp.readUnsignedShort()
        inp.skipBytes(16); val nl = inp.readInt(); inp.skipBytes(nl)
        resize(w, h)
        // 32-bit true colour, little endian, red at bit 16 → reads straight into ARGB
        synchronized(lock) {
            o.writeByte(0); o.write(ByteArray(3)); o.writeByte(32); o.writeByte(24); o.writeByte(0); o.writeByte(1)
            o.writeShort(255); o.writeShort(255); o.writeShort(255); o.writeByte(16); o.writeByte(8); o.writeByte(0); o.write(ByteArray(3))
            // encodings: CopyRect, Raw, DesktopSize, ExtendedDesktopSize (so the PC can change shape to fit the phone)
            o.writeByte(2); o.writeByte(0); o.writeShort(4); o.writeInt(1); o.writeInt(0); o.writeInt(-223); o.writeInt(-308)
            o.flush()
        }
        connected = true; error = ""
        request(false)
        // redraws at most ~30 times a second; never lets an interruption escape (that would crash Atlas)
        val ticker = Thread { try { while (connected) { if (dirty) { dirty = false; runCatching { publish() } }; Thread.sleep(33) } } catch (_: Throwable) { } }.apply { isDaemon = true; start() }
        try {
            while (true) {
                when (inp.readUnsignedByte()) {
                    0 -> {
                        inp.readByte(); val rects = inp.readUnsignedShort()
                        repeat(rects) {
                            val x = inp.readUnsignedShort(); val y = inp.readUnsignedShort(); val rw = inp.readUnsignedShort(); val rh = inp.readUnsignedShort(); val enc = inp.readInt()
                            when (enc) {
                                0 -> {
                                    val row = ByteArray(rw * 4)
                                    for (j in 0 until rh) {
                                        inp.readFully(row)
                                        val base = (y + j) * width + x
                                        if (y + j < height) for (i in 0 until rw) if (x + i < width) {
                                            val k = i * 4
                                            pixels[base + i] = (0xFF shl 24) or ((row[k + 2].toInt() and 0xff) shl 16) or ((row[k + 1].toInt() and 0xff) shl 8) or (row[k].toInt() and 0xff)
                                        }
                                    }
                                }
                                1 -> {
                                    val sx = inp.readUnsignedShort(); val sy = inp.readUnsignedShort()
                                    val tmp = IntArray(rw * rh)
                                    for (j in 0 until rh) System.arraycopy(pixels, (sy + j) * width + sx, tmp, j * rw, rw)
                                    for (j in 0 until rh) System.arraycopy(tmp, j * rw, pixels, (y + j) * width + x, rw)
                                }
                                -223 -> resize(rw, rh)
                                -308 -> { val n = inp.readUnsignedByte(); inp.skipBytes(3 + n * 16); extended = true; if (rw != width || rh != height) resize(rw, rh) }
                                else -> throw IllegalStateException("Unsupported screen encoding $enc")
                            }
                        }
                        dirty = true
                        request(true)
                    }
                    1 -> { inp.readByte(); inp.readUnsignedShort(); val k = inp.readUnsignedShort(); inp.skipBytes(k * 6) }
                    2 -> {}
                    3 -> { inp.skipBytes(3); val l = inp.readInt(); inp.skipBytes(l) }
                    150 -> {}                                   // end of continuous updates
                    248 -> { inp.skipBytes(3); inp.readInt(); val l = inp.readUnsignedByte(); inp.skipBytes(l) } // fence
                    else -> throw IllegalStateException("Unknown message from the screen server")
                }
            }
        } finally { connected = false; runCatching { s.close() } }
    }

    @Volatile var extended = false

    /** Asks the PC to change its screen to w×h (it fits the phone's shape). */
    fun setSize(w: Int, h: Int) = send { o ->
        o.writeByte(251); o.writeByte(0); o.writeShort(w); o.writeShort(h); o.writeByte(1); o.writeByte(0)
        o.writeInt(0); o.writeShort(0); o.writeShort(0); o.writeShort(w); o.writeShort(h); o.writeInt(0)
    }

    private fun resize(w: Int, h: Int) {
        synchronized(lock) {
            pixels = IntArray(w * h)
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            width = w; height = h
        }
        if (connected) request(false)
    }

    private fun publish() {
        val b = bitmap ?: return
        synchronized(lock) { if (b.width == width && pixels.size == width * height) runCatching { b.setPixels(pixels, 0, width, 0, 0, width, height) } }
        frame++
    }

    private fun request(incremental: Boolean) = send { o -> o.writeByte(3); o.writeByte(if (incremental) 1 else 0); o.writeShort(0); o.writeShort(0); o.writeShort(width); o.writeShort(height) }

    private fun send(block: (DataOutputStream) -> Unit) { val o = out ?: return; runCatching { synchronized(lock) { block(o); o.flush() } } }

    /** A copy of the screen right now (for Atlas to look at). */
    fun snapshot(): Bitmap? { val b = bitmap ?: return null; return synchronized(lock) { runCatching { b.setPixels(pixels, 0, width, 0, 0, width, height) }; b.copy(Bitmap.Config.ARGB_8888, false) } }

    // ── mouse ───────────────────────────────────────────────────────────────
    fun pointer(x: Int, y: Int, mask: Int = buttons) { buttons = mask; send { o -> o.writeByte(5); o.writeByte(mask); o.writeShort(x.coerceIn(0, maxOf(0, width - 1))); o.writeShort(y.coerceIn(0, maxOf(0, height - 1))) } }
    fun click(x: Int, y: Int, button: Int = 1, times: Int = 1) {
        val bit = when (button) { 2 -> 2; 3 -> 4; else -> 1 }
        pointer(x, y, 0); repeat(times) { pointer(x, y, bit); Thread.sleep(40); pointer(x, y, 0); Thread.sleep(60) }
    }
    fun scroll(x: Int, y: Int, clicks: Int) { val bit = if (clicks < 0) 8 else 16; repeat(kotlin.math.abs(clicks)) { pointer(x, y, bit); pointer(x, y, 0) } }
    fun drag(x1: Int, y1: Int, x2: Int, y2: Int) {
        pointer(x1, y1, 0); pointer(x1, y1, 1)
        for (k in 1..12) { pointer(x1 + (x2 - x1) * k / 12, y1 + (y2 - y1) * k / 12, 1); Thread.sleep(16) }
        pointer(x2, y2, 0)
    }

    // ── keyboard ────────────────────────────────────────────────────────────
    val KEYS = mapOf("enter" to 0xff0d, "return" to 0xff0d, "tab" to 0xff09, "esc" to 0xff1b, "escape" to 0xff1b, "backspace" to 0xff08, "delete" to 0xffff,
        "up" to 0xff52, "down" to 0xff54, "left" to 0xff51, "right" to 0xff53, "home" to 0xff50, "end" to 0xff57, "pageup" to 0xff55, "pagedown" to 0xff56,
        "ctrl" to 0xffe3, "control" to 0xffe3, "alt" to 0xffe9, "shift" to 0xffe1, "super" to 0xffeb, "win" to 0xffeb, "meta" to 0xffeb, "space" to 0x20,
        "f1" to 0xffbe, "f2" to 0xffbf, "f3" to 0xffc0, "f4" to 0xffc1, "f5" to 0xffc2, "f6" to 0xffc3, "f7" to 0xffc4, "f8" to 0xffc5, "f9" to 0xffc6, "f10" to 0xffc7, "f11" to 0xffc8, "f12" to 0xffc9)

    fun key(sym: Int, down: Boolean) = send { o -> o.writeByte(4); o.writeByte(if (down) 1 else 0); o.writeShort(0); o.writeInt(sym) }
    fun tap(sym: Int) { key(sym, true); key(sym, false) }
    private fun symFor(ch: Char): Int = when { ch == '\n' -> 0xff0d; ch == '\t' -> 0xff09; ch.code in 0x20..0xff -> ch.code; else -> 0x01000000 or ch.code }
    fun type(text: String) { text.forEach { tap(symFor(it)); Thread.sleep(8) } }

    /** "ctrl+l", "alt+f4", "enter", "ctrl+shift+t" … */
    fun combo(spec: String) {
        val parts = spec.lowercase().split('+').map { it.trim() }.filter { it.isNotEmpty() }
        val syms = parts.map { KEYS[it] ?: (if (it.length == 1) symFor(it[0]) else 0) }.filter { it != 0 }
        syms.forEach { key(it, true) }; syms.reversed().forEach { key(it, false) }
    }
}
