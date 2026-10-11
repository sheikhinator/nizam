package com.nizam.app.ui.design

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Display choices for the instrument look: how dense (style), whether it makes sound, and how much it moves.
 * Kept separate from the colour theme so each can be changed on its own.
 */
object Instruments {
    enum class StyleLevel(val label: String) { Friendly("Friendly"), Lab("Lab-grade"), Minimal("Minimal") }
    enum class SoundLevel(val label: String, val gain: Float) { Full("Full", 0.5f), Soft("Soft", 0.22f), Off("Off", 0f) }
    enum class MotionLevel(val label: String) { Full("Full"), Reduced("Reduced"), Calm("Calm") }

    private fun p(c: Context) = c.getSharedPreferences("instruments", 0)
    fun style(c: Context) = runCatching { StyleLevel.valueOf(p(c).getString("style", "Friendly")!!) }.getOrDefault(StyleLevel.Friendly)
    fun sound(c: Context) = runCatching { SoundLevel.valueOf(p(c).getString("sound", "Soft")!!) }.getOrDefault(SoundLevel.Soft)
    fun motion(c: Context) = runCatching { MotionLevel.valueOf(p(c).getString("motion", "Full")!!) }.getOrDefault(MotionLevel.Full)
    fun set(c: Context, style: StyleLevel = style(c), sound: SoundLevel = sound(c), motion: MotionLevel = motion(c)) =
        p(c).edit().putString("style", style.name).putString("sound", sound.name).putString("motion", motion.name).apply()
    fun animated(c: Context) = motion(c) == MotionLevel.Full && !Motion.still
    val mono = FontFamily.Monospace
}

/** Short synthesised tones: nothing is downloaded or bundled. Respects silent mode and the user's sound level. */
object SoundFx {
    private const val RATE = 22050
    @Volatile private var lastAt = 0L

    private fun allowed(c: Context): Float {
        val g = Instruments.sound(c).gain; if (g <= 0f) return 0f
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return 0f
        // no sounds during prayer / worship mode
        if (com.nizam.app.feature.modes.AutoModes.isQuiet(c)) return 0f
        return g
    }

    private fun play(c: Context, freqs: List<Pair<Float, Int>>, gainScale: Float = 1f) {
        val g = allowed(c) * gainScale; if (g <= 0f) return
        val now = System.currentTimeMillis(); if (now - lastAt < 60) return; lastAt = now
        val app = c.applicationContext
        @Suppress("OPT_IN_USAGE") GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val total = freqs.sumOf { it.second * RATE / 1000 }
                val buf = ShortArray(total); var i = 0
                freqs.forEach { (f, ms) ->
                    val n = ms * RATE / 1000
                    for (k in 0 until n) {
                        val env = min(1f, min(k / (RATE * 0.004f), (n - k) / (RATE * 0.02f)))   // click-free attack and release
                        buf[i++] = (sin(2.0 * PI * f * k / RATE) * env * g * Short.MAX_VALUE * 0.6).toInt().toShort()
                    }
                }
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                t.write(buf, 0, buf.size); t.setNotificationMarkerPosition(buf.size)
                t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(a: AudioTrack) { a.release() }
                    override fun onPeriodicNotification(a: AudioTrack) {}
                })
                t.play()
            }
        }
    }
    fun tick(c: Context) = play(c, listOf(1800f to 12), 0.5f)
    @Volatile private var lastBoot = 0L
    @Volatile private var lastWarn = 0L
    /** The "instrument powering on" sound plays at most once every 30 minutes, not on every visit to Today. */
    fun boot(c: Context) { val n = System.currentTimeMillis(); if (n - lastBoot < 30 * 60_000L) return; lastBoot = n; play(c, listOf(520f to 70, 780f to 70, 1040f to 110), 0.8f) }
    fun chime(c: Context) = play(c, listOf(880f to 90, 1320f to 160))
    fun warn(c: Context) { val n = System.currentTimeMillis(); if (n - lastWarn < 10 * 60_000L) return; lastWarn = n; play(c, listOf(300f to 140, 240f to 180), 0.9f) }
    fun done(c: Context) = play(c, listOf(660f to 70, 990f to 120), 0.8f)
}

/** The colours instruments use: neutral by default, signal colours only where they mean something. */
object Signal {
    val good = Color(0xFF3DDC97); val warn = Color(0xFFFFB454); val bad = Color(0xFFFF6B6B)
}

/** A number that rolls up to its value when it appears. */
@Composable
fun CountUp(value: Double, modifier: Modifier = Modifier, decimals: Int = 0, prefix: String = "", suffix: String = "",
            style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineMedium, color: Color = MaterialTheme.colorScheme.onSurface, sound: Boolean = false) {
    val c = LocalContext.current
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) {
        if (!Instruments.animated(c)) { anim.snapTo(value.toFloat()); return@LaunchedEffect }
        if (sound) SoundFx.tick(c)
        anim.animateTo(value.toFloat(), tween(700, easing = FastOutSlowInEasing))
    }
    val shown = anim.value.toDouble()
    val hide = (prefix.contains("Rs") || prefix.contains("$")) && com.nizam.app.feature.platform2.AmountPrivacy.hidden()
    val text = if (hide) "••••" else if (decimals == 0) "%,d".format(shown.roundToInt()) else "%,.${decimals}f".format(shown)
    Text("$prefix$text$suffix", modifier, style = style.copy(fontFamily = Instruments.mono, fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
}

/** Small-caps label above a figure, the way instruments are labelled. */
@Composable
fun InstrumentLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

/** One headline figure: label, big number, change since last period, and a faint trend behind it. */
@Composable
fun KpiTile(label: String, value: Double, modifier: Modifier = Modifier, decimals: Int = 0, prefix: String = "", suffix: String = "",
            delta: Double? = null, higherIsBetter: Boolean = true, trend: List<Double> = emptyList(), accent: Color = MaterialTheme.colorScheme.primary, onClick: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val lab = Instruments.style(LocalContext.current) == Instruments.StyleLevel.Lab
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.clip(shape).background(c.surface).then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)) {
        if (trend.size > 1 && (trend.maxOrNull() ?: 0.0) != (trend.minOrNull() ?: 0.0)) Sparkline(trend, Modifier.align(Alignment.TopEnd).padding(12.dp).size(width = 56.dp, height = 22.dp), accent.copy(alpha = 0.55f))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            InstrumentLabel(label)
            CountUp(value, decimals = decimals, prefix = prefix, suffix = suffix, color = c.onSurface)
            if (delta != null) {
                val good = (delta >= 0) == higherIsBetter
                val col = if (abs(delta) < 0.5) c.onSurfaceVariant else if (good) Signal.good else Signal.bad
                Text("${if (delta >= 0) "▲" else "▼"} ${"%.1f".format(abs(delta))}%" + if (lab) " vs prev" else "", style = MaterialTheme.typography.labelMedium.copy(fontFamily = Instruments.mono), color = col)
            }
        }
    }
}

/** A half-dial with ticks and a needle that sweeps (with a small overshoot) to its value. */
@Composable
fun DialGauge(fraction: Float, label: String, valueText: String, modifier: Modifier = Modifier, diameter: Dp = 150.dp, warnBelow: Float = 0.25f, higherIsBetter: Boolean = true) {
    val c = MaterialTheme.colorScheme; val ctx = LocalContext.current
    val a = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        val target = fraction.coerceIn(0f, 1f)
        if (!Instruments.animated(ctx)) a.snapTo(target) else {
            a.animateTo(min(1f, target + 0.05f), tween(520, easing = FastOutSlowInEasing)); a.animateTo(target, tween(260))
            if ((if (higherIsBetter) target < warnBelow else target > 1f - warnBelow)) SoundFx.warn(ctx)
        }
    }
    val f = a.value
    val col = if (higherIsBetter) { if (f < warnBelow) Signal.bad else if (f < 0.5f) Signal.warn else Signal.good } else { if (f > 1f - warnBelow) Signal.bad else if (f > 0.5f) Signal.warn else Signal.good }
    Column(modifier.width(diameter), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(diameter).height(diameter / 2 + 12.dp)) {
            val stroke = 10.dp.toPx(); val tl = Offset(stroke, stroke); val s = Size(size.width - stroke * 2, size.width - stroke * 2)
            drawArc(c.surfaceVariant, 180f, 180f, false, tl, s, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(col, 180f, 180f * f, false, tl, s, style = Stroke(stroke, cap = StrokeCap.Round))
            val ctr = Offset(size.width / 2, size.width / 2); val r = size.width / 2 - stroke
            for (i in 0..10) {                                    // tick marks
                val ang = Math.toRadians(180.0 + 18.0 * i); val len = if (i % 5 == 0) 9.dp.toPx() else 5.dp.toPx()
                drawLine(c.onSurfaceVariant.copy(alpha = 0.5f), Offset(ctr.x + (cos(ang) * (r - stroke - 2)).toFloat(), ctr.y + (sin(ang) * (r - stroke - 2)).toFloat()),
                    Offset(ctr.x + (cos(ang) * (r - stroke - 2 - len)).toFloat(), ctr.y + (sin(ang) * (r - stroke - 2 - len)).toFloat()), 1.2.dp.toPx())
            }
            val ang = Math.toRadians(180.0 + 180.0 * f)
            drawLine(c.onSurface, ctr, Offset(ctr.x + (cos(ang) * (r - stroke * 2.6f)).toFloat(), ctr.y + (sin(ang) * (r - stroke * 2.6f)).toFloat()), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(c.onSurface, 4.dp.toPx(), ctr)
        }
        Text(valueText, style = MaterialTheme.typography.titleMedium.copy(fontFamily = Instruments.mono, fontWeight = FontWeight.SemiBold))
        InstrumentLabel(label)
    }
}

data class Slice(val label: String, val value: Double, val color: Color)

/** A ring split into slices that sweeps open; the total sits in the middle. Tap a legend row to isolate that slice. */
@Composable
fun DonutChart(slices: List<Slice>, centerLabel: String, centerValue: String, modifier: Modifier = Modifier, diameter: Dp = 150.dp) {
    val ctx = LocalContext.current; val c = MaterialTheme.colorScheme
    val sweep = remember { Animatable(0f) }
    var picked by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(slices) { if (Instruments.animated(ctx)) { sweep.snapTo(0f); sweep.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) } else sweep.snapTo(1f) }
    val total = max(1e-9, slices.sumOf { it.value })
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(diameter)) {
                val stroke = 20.dp.toPx(); var start = -90f
                slices.forEachIndexed { i, s ->
                    val full = (s.value / total * 360f).toFloat(); val sw = full * sweep.value
                    val dim = picked != null && picked != i
                    drawArc(s.color.copy(alpha = if (dim) 0.25f else 1f), start, max(0f, sw - 1.5f), false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                    start += full
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val sel = picked?.let { slices.getOrNull(it) }
                Text(sel?.let { "%,d".format(it.value.roundToInt()) } ?: centerValue, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Instruments.mono, fontWeight = FontWeight.SemiBold))
                InstrumentLabel(sel?.label ?: centerLabel)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slices.take(6).forEachIndexed { i, s ->
                Row(Modifier.pressable { picked = if (picked == i) null else i }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(s.color)); Spacer(Modifier.width(7.dp))
                    Text("${s.label} ${(s.value / total * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = c.onSurface, maxLines = 1)
                }
            }
        }
    }
}

/** A trend line that draws itself in, with last period as a faint ghost line and a draggable read-out. */
@Composable
fun TrendLine(values: List<Double>, modifier: Modifier = Modifier, ghost: List<Double> = emptyList(), labels: List<String> = emptyList(), height: Dp = 120.dp,
              accent: Color = MaterialTheme.colorScheme.primary, format: (Double) -> String = { "%,.0f".format(it) }) {
    if (values.size < 2) return
    if (values.all { it == 0.0 }) { NoData(modifier); return }
    val ctx = LocalContext.current; val c = MaterialTheme.colorScheme
    val draw = remember { Animatable(0f) }
    var sel by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(values) { if (Instruments.animated(ctx)) { draw.snapTo(0f); draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) } else draw.snapTo(1f) }
    val all = values + ghost; val lo = all.min(); val hi = all.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        val idx = if (sel >= 0f) (sel * (values.size - 1)).roundToInt().coerceIn(0, values.size - 1) else values.size - 1
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(format(values[idx]), style = MaterialTheme.typography.titleMedium.copy(fontFamily = Instruments.mono, fontWeight = FontWeight.SemiBold), color = accent)
            Spacer(Modifier.width(8.dp)); Text(labels.getOrNull(idx).orEmpty(), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(height).pointerInputDrag { x, w -> sel = (x / w).coerceIn(0f, 1f) }.padding(top = 6.dp)) {
            for (g in 0..3) drawLine(c.outline, Offset(0f, size.height * g / 3f), Offset(size.width, size.height * g / 3f), 1f)   // graph-paper lines
            fun pt(i: Int, n: Int, v: Double) = Offset(i * size.width / (n - 1), size.height - ((v - lo) / span * size.height * 0.92f).toFloat() - size.height * 0.04f)
            fun line(list: List<Double>, col: Color, w: Float, fill: Boolean, prog: Float) {
                if (list.size < 2) return
                val path = Path(); val upto = ((list.size - 1) * prog)
                list.forEachIndexed { i, v -> if (i <= upto) { val p = pt(i, list.size, v); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) } }
                if (fill) { val fp = Path(); fp.addPath(path); val last = pt(min(list.size - 1, upto.toInt()), list.size, list[min(list.size - 1, upto.toInt())])
                    fp.lineTo(last.x, size.height); fp.lineTo(0f, size.height); fp.close(); drawPath(fp, Brush.verticalGradient(listOf(col.copy(alpha = 0.28f), Color.Transparent))) }
                drawPath(path, col, style = Stroke(w, cap = StrokeCap.Round))
            }
            if (ghost.size > 1) line(ghost, c.onSurfaceVariant.copy(alpha = 0.35f), 1.5.dp.toPx(), false, 1f)
            line(values, accent, 2.5.dp.toPx(), true, draw.value)
            val p = pt(idx, values.size, values[idx]); drawLine(accent.copy(alpha = 0.4f), Offset(p.x, 0f), Offset(p.x, size.height), 1f); drawCircle(accent, 5.dp.toPx(), p); drawCircle(c.background, 2.dp.toPx(), p)
        }
    }
}

private fun Modifier.pointerInputDrag(onX: (Float, Float) -> Unit): Modifier = this.pointerInput(Unit) {
    detectDragGestures(onDragStart = { onX(it.x, size.width.toFloat()) }) { ch, _ -> ch.consume(); onX(ch.position.x, size.width.toFloat()) }
}

/** A year (or any run of days) as a grid of squares, darker = more. */
@Composable
fun CalendarHeat(values: List<Double>, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary, rows: Int = 7) {
    if (values.isEmpty()) return
    if (values.all { it == 0.0 }) { NoData(modifier); return }
    val hi = max(1e-9, values.max()); val c = MaterialTheme.colorScheme
    val cols = (values.size + rows - 1) / rows
    Canvas(modifier.fillMaxWidth().height((rows * 14).dp)) {
        val cell = min(size.width / cols, size.height / rows); val gap = cell * 0.18f
        values.forEachIndexed { i, v ->
            val col = i / rows; val row = i % rows
            drawRoundRect(if (v <= 0) c.surfaceVariant else accent.copy(alpha = 0.2f + 0.8f * (v / hi).toFloat()), Offset(col * cell, row * cell), Size(cell - gap, cell - gap),
                androidx.compose.ui.geometry.CornerRadius(cell * 0.2f))
        }
    }
}

data class Block(val startMin: Int, val endMin: Int, val label: String, val color: Color)

/** The day as a horizontal bar with a moving "now" marker. */
@Composable
fun DayTimeline(blocks: List<Block>, nowMin: Int, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            drawRoundRect(c.surfaceVariant, Offset.Zero, Size(size.width, size.height), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
            blocks.forEach { b -> val x1 = size.width * b.startMin / 1440f; val x2 = size.width * b.endMin / 1440f
                drawRoundRect(b.color.copy(alpha = 0.85f), Offset(x1, 3.dp.toPx()), Size(max(3.dp.toPx(), x2 - x1), size.height - 6.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())) }
            val nx = size.width * nowMin / 1440f; drawLine(c.onSurface, Offset(nx, 0f), Offset(nx, size.height), 2.dp.toPx(), StrokeCap.Round)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Instruments.mono), color = c.onSurfaceVariant) }
        }
    }
}

/** A thin progress ribbon with a marker for where you "should" be by now. */
@Composable
fun GoalRibbon(fraction: Float, expected: Float? = null, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    val c = MaterialTheme.colorScheme; val ctx = LocalContext.current
    val a = remember { Animatable(0f) }
    LaunchedEffect(fraction) { if (Instruments.animated(ctx)) a.animateTo(fraction.coerceIn(0f, 1f), tween(600)) else a.snapTo(fraction.coerceIn(0f, 1f)) }
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        drawRoundRect(c.surfaceVariant, Offset.Zero, size, androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        drawRoundRect(accent, Offset.Zero, Size(size.width * a.value, size.height), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        expected?.let { drawLine(c.onSurface, Offset(size.width * it.coerceIn(0f, 1f), 0f), Offset(size.width * it.coerceIn(0f, 1f), size.height), 2.dp.toPx()) }
    }
}

/** One sentence of plain-language insight under a dashboard. */
@Composable
fun InsightLine(text: String, modifier: Modifier = Modifier) =
    Text("◆ $text", modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A titled dashboard panel with the instrument styling. */
@Composable
fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InstrumentLabel(title); content()
    }
}

@Composable
fun NoData(modifier: Modifier = Modifier, text: String = "No data yet. Use the tool for a day or two and it fills in here.") =
    Text(text, modifier.fillMaxWidth().padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
