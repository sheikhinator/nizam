package com.nizam.app.feature.constellation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.mind.Neurgraph
import com.nizam.app.feature.mind.Neuron
import com.nizam.app.feature.mind.NeuronType
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.luminex
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private data class Star(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val radius: Float,
    val brightness: Float,
    val color: Color,
    val detail: String = "",
    val ts: Long = 0L
)

/**
 * Mnemonic Constellation.
 *
 * Your memories as a slow sky. Amber is an idea, rose a person, emerald a place, cyan an
 * event, violet a feeling. Drag to turn the sky, tap a star to bring the memory forward.
 * Nothing is randomised per frame; the whole thing is one rotation value and a canvas.
 */
@Composable
fun MnemonicConstellationScreen() {
    val context = LocalContext.current
    val neurons by produceState(emptyList<Neuron>()) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Neurgraph.all(context) }.getOrElse { emptyList() } } }
    val stars = remember(neurons) { buildSky(neurons) }
    val links = remember(stars) { buildLinks(stars) }

    var selected by remember { mutableStateOf<Star?>(null) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    val liveX = rememberUpdatedState(dragX)
    val liveY = rememberUpdatedState(dragY)

    val spin = rememberInfiniteTransition(label = "constellation")
    val rotation by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(140000, easing = LinearEasing)),
        label = "skyRotation"
    )
    val liveRotation = rememberUpdatedState(rotation)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF04050B), Color(0xFF07070F), Color(0xFF04040A))))
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        dragX += delta.x / 900f
                        dragY += delta.y / 900f
                    }
                }
                .pointerInput(stars) {
                    detectTapGestures { tap ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val rad = Math.toRadians(liveRotation.value.toDouble())
                        val c = cos(rad).toFloat()
                        val s = sin(rad).toFloat()
                        var best: Star? = null
                        var bestDistance = 46f
                        stars.forEach { star ->
                            val rx = star.x * c - star.z * s
                            val rz = star.x * s + star.z * c
                            val scale = 1f / (1f + rz / 1400f)
                            if (scale > 0.15f) {
                                val px = cx + (rx + liveX.value * 120f) * scale
                                val py = cy + (star.y + liveY.value * 120f) * scale
                                val d = hypot(tap.x - px, tap.y - py)
                                if (d < bestDistance) {
                                    bestDistance = d
                                    best = star
                                }
                            }
                        }
                        selected = best
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val rad = Math.toRadians(rotation.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()

            fun project(star: Star): Triple<Offset, Float, Float> {
                val rx = star.x * c - star.z * s
                val rz = star.x * s + star.z * c
                val scale = 1f / (1f + rz / 1400f)
                return Triple(Offset(cx + (rx + dragX * 120f) * scale, cy + (star.y + dragY * 120f) * scale), scale, rz)
            }

            // faint nebula haze, three soft pools of light
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Ascend.reactorViolet.copy(alpha = 0.05f), Color.Transparent),
                    center = Offset(cx * 0.7f, cy * 0.75f),
                    radius = size.minDimension * 0.7f
                ),
                radius = size.minDimension * 0.7f,
                center = Offset(cx * 0.7f, cy * 0.75f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Ascend.reactorCyan.copy(alpha = 0.045f), Color.Transparent),
                    center = Offset(cx * 1.3f, cy * 1.2f),
                    radius = size.minDimension * 0.6f
                ),
                radius = size.minDimension * 0.6f,
                center = Offset(cx * 1.3f, cy * 1.2f)
            )

            links.forEach { link ->
                val a = project(stars[link.first])
                val b = project(stars[link.second])
                if (a.second > 0.2f && b.second > 0.2f) {
                    val alpha = (0.10f * ((a.second + b.second) / 2f)).coerceIn(0f, 0.16f)
                    drawLine(
                        brush = Brush.linearGradient(
                            listOf(Ascend.reactorCyan.copy(alpha = alpha), Ascend.reactorViolet.copy(alpha = alpha * 0.6f)),
                            start = a.first,
                            end = b.first
                        ),
                        start = a.first,
                        end = b.first,
                        strokeWidth = 1f
                    )
                }
            }

            stars.forEach { star ->
                val p = project(star)
                if (p.second > 0.12f) {
                    val alpha = (star.brightness * p.second).coerceIn(0f, 1f)
                    drawCircle(
                        color = star.color.copy(alpha = alpha * 0.18f),
                        radius = star.radius * p.second * 4.5f,
                        center = p.first
                    )
                    drawCircle(
                        color = star.color.copy(alpha = alpha),
                        radius = star.radius * p.second,
                        center = p.first
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 28.dp, end = 24.dp)
        ) {
            Text(
                "Mnemonic Constellation",
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (neurons.isEmpty()) "Your sky is still dark. Live a little and it fills." else neurons.size.toString() + " memories, still turning",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp
            )
        }

        AnimatedVisibility(
            visible = selected != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp)
        ) {
            val star = selected
            if (star != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .luminex(RoundedCornerShape(22.dp), star.color, 0.5f)
                        .background(Color(0xCC0A0B14))
                        .padding(18.dp)
                ) {
                    Text(typeLabel(star.id), color = star.color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(star.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (star.detail.isNotBlank()) Text(star.detail, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 4,
                        modifier = Modifier.padding(top = 4.dp))
                    if (star.ts > 0) Text(java.time.Instant.ofEpochMilli(star.ts).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
                        color = star.color.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Tap a different star to move through the sky.",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

private fun typeLabel(id: String): String = when {
    id.startsWith("idea") -> "IDEA"
    id.startsWith("person") -> "PERSON"
    id.startsWith("place") -> "PLACE"
    id.startsWith("event") -> "EVENT"
    id.startsWith("feeling") -> "FEELING"
    id.startsWith("decision") -> "DECISION"
    id.startsWith("goal") -> "GOAL"
    id.startsWith("moment") -> "MOMENT"
    id.startsWith("talk") -> "CONVERSATION"
    id.startsWith("ambient") -> "LIGHT"
    else -> "MEMORY"
}

private fun colorFor(id: String, type: NeuronType?): Color = when (type) {
    NeuronType.PERSON -> Ascend.reactorRose
    NeuronType.PLACE -> Ascend.reactorEmerald
    NeuronType.EVENT -> Ascend.reactorCyan
    NeuronType.IDEA -> Ascend.reactorAmber
    NeuronType.EMOTION -> Ascend.reactorViolet
    NeuronType.DECISION -> Ascend.reactorIce
    NeuronType.GOAL -> Color(0xFFFFD98A)
    NeuronType.MOMENT -> Color.White
    else -> if (id.startsWith("ambient")) Color.White.copy(alpha = 0.7f) else Ascend.reactorCyan
}

private fun buildSky(neurons: List<Neuron>): List<Star> {
    val out = ArrayList<Star>()
    val rnd = Random(7)
    neurons.take(220).forEach { n ->
        val angle = rnd.nextFloat() * 6.2831855f
        val distance = 120f + rnd.nextFloat() * 820f
        out.add(
            Star(
                id = n.type.name.lowercase() + "_" + n.id,
                label = n.label,
                x = cos(angle) * distance,
                y = sin(angle) * distance * 0.62f,
                z = (rnd.nextFloat() - 0.5f) * 700f,
                radius = (1.1f + n.weight.coerceIn(0.2f, 3f) * 0.7f),
                brightness = (0.45f + rnd.nextFloat() * 0.5f).coerceAtMost(1f),
                color = colorFor(n.id, n.type),
                detail = n.content.takeIf { it != n.label }.orEmpty().take(220),
                ts = n.timestamp
            )
        )
    }
    val ambient = if (neurons.size >= 60) 40 else 130
    repeat(ambient) {
        val angle = rnd.nextFloat() * 6.2831855f
        val distance = 200f + rnd.nextFloat() * 900f
        out.add(
            Star(
                id = "ambient_" + it,
                label = "A quiet light",
                x = cos(angle) * distance,
                y = sin(angle) * distance * 0.62f,
                z = (rnd.nextFloat() - 0.5f) * 800f,
                radius = 0.7f + rnd.nextFloat() * 1.1f,
                brightness = 0.25f + rnd.nextFloat() * 0.45f,
                color = Color.White
            )
        )
    }
    return out
}

private fun buildLinks(stars: List<Star>): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    for (i in stars.indices) {
        for (j in i + 1 until minOf(i + 7, stars.size)) {
            val a = stars[i]
            val b = stars[j]
            val dx = a.x - b.x
            val dy = a.y - b.y
            if (dx * dx + dy * dy < 24000f) out.add(i to j)
            if (out.size > 900) return out
        }
    }
    return out
}
