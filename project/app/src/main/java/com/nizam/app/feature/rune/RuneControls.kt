package com.nizam.app.feature.rune

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val ChipShape = RoundedCornerShape(50)

@Composable
fun RuneChip(label: String, on: Boolean, onClick: () -> Unit) {
    val m = MaterialTheme.colorScheme
    Box(Modifier.clip(ChipShape).background(if (on) m.primary else m.surfaceVariant).border(BorderStroke(1.dp, if (on) m.primary else m.outline.copy(alpha = 0.3f)), ChipShape)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = if (on) m.onPrimary else m.onSurface, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuneChoice(options: List<String>, current: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> RuneChip(o, o == current) { onPick(o) } }
    }
}

@Composable
fun RuneSection(title: String, open0: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(open0) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(if (open) "−" else "+", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (open) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
fun RuneNum(label: String, value: Float, min: Float, max: Float, step: Float, isSet: Boolean, onChange: (Float) -> Unit, onReset: () -> Unit) {
    val m = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = m.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (value == value.toInt().toFloat()) value.toInt().toString() else "%.2f".format(value), fontSize = 13.sp, color = if (isSet) m.primary else m.onSurfaceVariant)
                if (isSet) Text("  ✕", fontSize = 13.sp, color = m.onSurfaceVariant, modifier = Modifier.clickable(onClick = onReset).padding(4.dp))
            }
        }
        val steps = if (step > 0f) (((max - min) / step).toInt() - 1).coerceIn(0, 120) else 0
        Slider(value.coerceIn(min, max), { v -> onChange(if (step > 0f) (Math.round(v / step) * step) else v) }, valueRange = min..max, steps = if (steps in 1..120) steps else 0)
    }
}

@Composable
fun RuneText(label: String, value: String, long: Boolean = false, mono: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label, fontSize = 12.sp) }, singleLine = !long, minLines = if (long) 3 else 1,
        textStyle = TextStyle(fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else null))
}

@Composable
fun RuneBool(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(on, onChange)
    }
}

/** A colour you can type, tap from swatches and tokens, or dial in OKLCH. Emits "#RRGGBB", "#AARRGGBB" or "@token". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuneColorField(label: String, value: String, theme: RuneTheme, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = FillSpec.color(value.ifEmpty { "@ink" }, theme)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(shown).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape).clickable { open = !open })
            OutlinedTextField(value, onChange, Modifier.weight(1f), label = { Text(label, fontSize = 12.sp) }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace))
        }
        if (open) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("@primary", "@accent", "@ink", "@muted", "@card", "@surface").forEach { tk -> RuneChip(tk, value == tk) { onChange(tk) } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RunePresets.swatches.forEach { hx ->
                        Box(Modifier.size(30.dp).clip(CircleShape).background(RuneColor.parse(hx, Color.Gray)).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape).clickable { onChange(hx) })
                    }
                }
                val lch = RuneColor.lchOf(shown.copy(alpha = 1f))
                RuneNum("Lightness", lch[0], 0f, 1f, 0f, false, { l -> onChange(RuneColor.hex(RuneColor.lch(l, lch[1], lch[2], shown.alpha))) }, {})
                RuneNum("Colourfulness", lch[1], 0f, 0.37f, 0f, false, { c -> onChange(RuneColor.hex(RuneColor.lch(lch[0], c, lch[2], shown.alpha))) }, {})
                RuneNum("Hue", lch[2], 0f, 360f, 0f, false, { h -> onChange(RuneColor.hex(RuneColor.lch(lch[0], lch[1], h, shown.alpha))) }, {})
                RuneNum("Opacity", shown.alpha, 0f, 1f, 0f, false, { a -> onChange(RuneColor.hex(shown.copy(alpha = a))) }, {})
            }
        }
    }
}

/** Edits a fill string: solid, linear, radial, sweep, mesh, aurora or liquid, with any number of colours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuneFillEditor(label: String, spec: String, theme: RuneTheme, onChange: (String) -> Unit) {
    val raw = remember(spec) { parseRaw(spec) }
    val kinds = listOf("none", "solid", "lin", "rad", "sweep", "mesh", "aurora", "liquid")
    fun emit(kind: String, angle: Float, cols: List<String>) = onChange(FillSpec.toSpec(kind, angle, cols))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            kinds.forEach { k ->
                RuneChip(when (k) { "lin" -> "gradient"; "rad" -> "radial"; else -> k }, raw.kind == k) {
                    var cols = raw.cols
                    val need = when (k) { "lin", "rad", "sweep" -> 2; "mesh" -> 3; "aurora" -> 3; "liquid" -> 3; else -> 1 }
                    val extra = listOf("@accent", "@primary", "#7A6CFF", "#FF7AB6")
                    var i = 0
                    if (cols.isEmpty()) cols = listOf("@primary")
                    while (cols.size < need) { cols = cols + extra[i % extra.size]; i++ }
                    emit(k, raw.angle, cols)
                }
            }
        }
        if (raw.kind != "none") {
            if (raw.kind == "lin") RuneNum("Angle", raw.angle, 0f, 360f, 5f, false, { a -> emit(raw.kind, a, raw.cols) }, {})
            raw.cols.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f)) { RuneColorField("Colour ${i + 1}", c, theme) { v -> emit(raw.kind, raw.angle, raw.cols.toMutableList().also { it[i] = v }) } }
                    if (raw.cols.size > 1 && raw.kind != "solid") TextButton({ emit(raw.kind, raw.angle, raw.cols.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
                }
            }
            if (raw.kind != "solid") TextButton({ emit(raw.kind, raw.angle, raw.cols + (RunePresets.swatches.random())) }) { Text("+ add colour") }
            Text("Looks", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GRADIENTS.forEach { g ->
                    val gs = FillSpec.parse(g, theme)
                    Box(Modifier.size(width = 56.dp, height = 36.dp).clip(RoundedCornerShape(10.dp)).runeFill(g, RoundedCornerShape(10.dp), theme, 1f, true).clickable { onChange(g) })
                    if (gs == null) Unit
                }
            }
        }
    }
}

private val GRADIENTS = listOf(
    "lin:135:#6C8CFF,#C77DFF", "lin:135:#FF9A3D,#FF5A5F", "lin:135:#3DF2B0,#31D0D0", "lin:160:#FF7AB6,#FFC86B", "lin:180:#0B0E17,#1B2A4A",
    "lin:135:#F6F3EC,#E8DCC4", "rad:#7A6CFF,#0B0E17", "sweep:#FF7AB6,#6C8CFF,#3DF2B0,#FF7AB6",
    "mesh:#6C8CFF,#FF7AB6,#FFC86B,#3DF2B0", "mesh:#7A6CFF,#31D0D0,#0B0E17", "aurora:#3DF2B0,#7A6CFF,#2B8CFF", "liquid:#1B1F3A,#9DB4FF,#FFFFFF"
)

private class Raw(val kind: String, val angle: Float, val cols: List<String>)

private fun parseRaw(spec: String): Raw {
    val s = spec.trim()
    if (s.isEmpty() || s == "none") return Raw("none", 135f, emptyList())
    val kind = s.substringBefore(':', "")
    if (kind in setOf("lin", "rad", "sweep", "mesh", "aurora", "liquid")) {
        var rest = s.substringAfter(':'); var angle = 135f
        if (kind == "lin") { val a = rest.substringBefore(':', "").toFloatOrNull(); if (a != null && rest.contains(':')) { angle = a; rest = rest.substringAfter(':') } }
        return Raw(kind, angle, rest.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }
    return Raw("solid", 135f, listOf(s))
}

/** Renders a single prop from the table. */
@Composable
fun RunePropEditor(def: PropDef, node: JSONObject, theme: RuneTheme, set: (String, Any?) -> Unit) {
    val k = def.key
    when (def.kind) {
        "string" -> RuneText(def.label, node.optString(k), long = k == "text") { v -> set(k, v.ifEmpty { null }) }
        "num" -> {
            val has = node.has(k)
            val cur = if (has) node.optDouble(k, 0.0).toFloat() else (def.def.toFloatOrNull() ?: def.min)
            RuneNum(def.label, cur, def.min, def.max, def.step, has, { v -> set(k, if (v == v.toInt().toFloat()) v.toInt() else v.toDouble()) }, { set(k, null) })
        }
        "bool" -> RuneBool(def.label, node.optBoolean(k, false)) { v -> set(k, if (v) true else null) }
        "choice" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(def.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RuneChoice(def.options, node.optString(k, def.options.firstOrNull().orEmpty()).ifEmpty { def.options.first() }) { v -> set(k, if (v == def.options.first() && (k == "enter" || k == "loop" || k == "press" || k == "fx")) null else v) }
        }
        "color" -> RuneColorField(def.label, node.optString(k), theme) { v -> set(k, v.ifEmpty { null }) }
        "fill" -> RuneFillEditor(def.label, node.optString(k), theme) { v -> set(k, v.ifEmpty { null }) }
    }
}
