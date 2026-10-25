package com.nizam.app.feature.nx

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Style generator: describe a look ("Ferrari cockpit", "old Lahore at dusk", "Japanese garden") or pick a
 * photo, and Atlas makes a whole theme from it: six colours, card style, corners, background, font and a
 * matching logo, applied in one tap and always reversible from Theme studio.
 */
object StyleGen {
    private val KEYS = listOf("surface", "card", "ink", "inkMuted", "primary", "accent")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    fun valid(o: JSONObject) = KEYS.all { HEX.matches(o.optString(it)) }

    suspend fun fromWords(container: AppContainer, words: String): JSONObject {
        val raw = Ai.generate(container.settingsRepository, """Design a phone app theme inspired by: $words
Return ONLY JSON: {"label":"2-3 words","surface":"#RRGGBB background","card":"#RRGGBB card","ink":"#RRGGBB main text","inkMuted":"#RRGGBB soft text",
"primary":"#RRGGBB","accent":"#RRGGBB","card_style":"${Style.CARDS.joinToString("|")}","corners":"${Style.CORNERS.joinToString("|")}",
"background":"${Style.BACKGROUNDS.joinToString("|")}","font":"${Style.FONTS.joinToString("|")}","logo_shape":"one word"}
Text must contrast strongly with surface and card (at least 7:1 for ink, 4.5:1 for inkMuted).""",
            system = "You are a sharp UI colour designer. Output only JSON.", maxOutputTokens = 500)
        val o = EngineX.json(raw) ?: throw IllegalStateException("Couldn't design that look. Try other words.")
        if (!valid(o)) throw IllegalStateException("The colours came back broken. Try again.")
        return o
    }

    /** Six colours from a photo, worked out on the phone: the photo never leaves it. */
    fun fromBitmap(b: Bitmap): JSONObject {
        val small = Bitmap.createScaledBitmap(b, 48, 48, true)
        val px = IntArray(48 * 48); small.getPixels(px, 0, 48, 0, 0, 48, 48)
        val hsvs = px.map { p -> FloatArray(3).also { android.graphics.Color.colorToHSV(p, it) } }
        // the most colourful bright pixels become primary/accent; average darkness sets the background
        val vivid = hsvs.filter { it[1] > 0.35f && it[2] > 0.35f }.sortedByDescending { it[1] * it[2] }
        val p1 = vivid.firstOrNull() ?: floatArrayOf(210f, 0.5f, 0.9f)
        val p2 = vivid.firstOrNull { kotlin.math.abs(((it[0] - p1[0] + 540) % 360) - 180) > 40 } ?: floatArrayOf((p1[0] + 150) % 360, 0.6f, 0.95f)
        val avgV = hsvs.map { it[2] }.average().toFloat()
        val dark = avgV < 0.6f
        val hue = p1[0]
        fun hex(h: Float, s: Float, v: Float) = "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))) and 0xFFFFFF)
        return JSONObject().put("label", "From my photo")
            .put("surface", if (dark) hex(hue, 0.45f, 0.10f) else hex(hue, 0.06f, 0.98f))
            .put("card", if (dark) hex(hue, 0.40f, 0.17f) else hex(hue, 0.04f, 1f))
            .put("ink", if (dark) hex(hue, 0.04f, 0.97f) else hex(hue, 0.30f, 0.12f))
            .put("inkMuted", if (dark) hex(hue, 0.12f, 0.72f) else hex(hue, 0.18f, 0.40f))
            .put("primary", hex(p1[0], maxOf(p1[1], 0.5f), if (dark) maxOf(p1[2], 0.85f) else minOf(p1[2], 0.65f)))
            .put("accent", hex(p2[0], maxOf(p2[1], 0.5f), if (dark) 0.95f else 0.6f))
            .put("card_style", if (dark) "Glass" else "Solid").put("corners", "Squircle").put("background", if (dark) "Mesh" else "Gradient").put("font", "System")
    }

    suspend fun apply(c: android.content.Context, container: AppContainer, o: JSONObject) {
        val pal = JSONObject().put("label", o.optString("label").ifBlank { "Generated" }.take(30))
        KEYS.forEach { pal.put(it, o.getString(it)) }
        container.settingsRepository.setCustomPalette(pal.toString())
        container.settingsRepository.setAccent("custom")
        Style.update(c) { s -> s.copy(
            card = o.optString("card_style").takeIf { it in Style.CARDS } ?: s.card,
            corners = o.optString("corners").takeIf { it in Style.CORNERS } ?: s.corners,
            bg = o.optString("background").takeIf { it in Style.BACKGROUNDS } ?: s.bg,
            font = o.optString("font").takeIf { it in Style.FONTS } ?: s.font) }
    }

    fun color(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
}

@Composable
fun StyleGenScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var words by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<JSONObject?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val b = c.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) }
                StyleGen.fromBitmap(b!!)
            } }.onSuccess { made = it }.onFailure { Feedback.show("Couldn't read that photo") }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Describe any look, or pick a photo. Atlas builds a whole theme from it.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Ferrari cockpit, old Lahore at dusk…") },
            trailingIcon = { MicButton({ heard -> words = heard }) })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val ideas = listOf("Japanese garden", "Ferrari cockpit", "Old Lahore at dusk", "Arctic morning", "Emerald mosque", "Retro arcade", "Desert night", "Paper notebook")
            items(ideas.size) { i -> Capsule(ideas[i], words == ideas[i]) { words = ideas[i] } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                busy = true
                scope.launch { runCatching { StyleGen.fromWords(container, words) }.onSuccess { made = it }.onFailure { Feedback.show(it.message ?: "Couldn't design that") }; busy = false }
            }, enabled = words.isNotBlank() && !busy) { Text(if (busy) "Designing…" else "✦ Make my theme") }
            TextButton(onClick = { runCatching { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }, enabled = !busy) { Text("From a photo") }
        }
        made?.let { o ->
            Group {
                Text(o.optString("label"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                // live mini preview of the generated theme
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(18.dp)).background(StyleGen.color(o.optString("surface"))).padding(14.dp)) {
                    Column {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(StyleGen.color(o.optString("card"))).padding(12.dp)) {
                            Column {
                                Text("Assalam o alaikum", color = StyleGen.color(o.optString("ink")), style = MaterialTheme.typography.titleMedium)
                                Text("This is how your Atlas will look.", color = StyleGen.color(o.optString("inkMuted")), style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.clip(RoundedCornerShape(50)).background(StyleGen.color(o.optString("primary"))).padding(horizontal = 14.dp, vertical = 6.dp)) {
                                        Text("Primary", color = StyleGen.color(o.optString("surface")), style = MaterialTheme.typography.labelLarge) }
                                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(StyleGen.color(o.optString("accent"))))
                                }
                            }
                        }
                    }
                }
                Text(listOf(o.optString("card_style"), o.optString("corners"), o.optString("background"), o.optString("font")).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { runCatching { StyleGen.apply(c, container, o) }.onSuccess { Feedback.show("Applied. Theme studio can switch back any time") }
                        .onFailure { Feedback.show(it.message ?: "Couldn't apply") } } }) { Text("Use this theme") }
                    TextButton(onClick = { open("nx/themestudio") }) { Text("Theme studio") }
                }
            }
        }
    }
}

val StyleGenSpecs: List<Spec> get() = listOf(
    ScreenSpec("stylegen", "Style generator", "Describe a look or pick a photo: Atlas makes a whole theme from it",
        Icons.Outlined.ColorLens, Hub.Tools, "style generator theme from words photo generate design look colours palette ai theme") { container, open -> StyleGenScreen(container, open) })
