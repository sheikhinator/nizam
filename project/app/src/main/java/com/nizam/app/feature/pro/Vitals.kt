package com.nizam.app.feature.pro

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Pdf
import com.nizam.app.feature.nx.Share
import com.nizam.app.feature.rune.RuneChip
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Hub
import org.json.JSONObject
import java.time.LocalDate

/**
 * Vitals: blood pressure and blood sugar readings with the standard ranges, averages, a trend line and a page for your doctor.
 * Replaces the Blood Pressure Log and Blood Sugar Log (their entries are imported once).
 * A record: {id, kind: bp|sugar, date, sys, dia, pulse, val, when}
 */
object Vitals {
    fun store(c: Context) = JsonStore(c.applicationContext, "pro_vitals")

    fun all(c: Context): List<JSONObject> {
        val p = c.getSharedPreferences("pro", 0)
        if (!p.getBoolean("vitals_migrated", false)) {
            runCatching {
                JsonStore(c.applicationContext, "nxlog_bplog").all().forEach { o -> store(c).add(JSONObject().put("kind", "bp").put("date", o.optString("date")).put("sys", num(o.optString("sys"))).put("dia", num(o.optString("dia"))).put("pulse", num(o.optString("pulse")))) }
                JsonStore(c.applicationContext, "nxlog_sugarlog").all().forEach { o -> store(c).add(JSONObject().put("kind", "sugar").put("date", o.optString("date")).put("val", num(o.optString("val"))).put("when", o.optString("when", "Fasting"))) }
            }
            p.edit().putBoolean("vitals_migrated", true).apply()
        }
        return store(c).all().sortedBy { it.optString("date") }
    }

    /** Label, and level: 0 in range, 1 watch, 2 high, 3 get help now. Standard adult ranges; not a diagnosis. */
    fun bp(sys: Double, dia: Double): Pair<String, Int> = when {
        sys > 180 || dia > 120 -> "Very high: get medical help now" to 3
        sys >= 140 || dia >= 90 -> "High (stage 2 range)" to 2
        sys >= 130 || dia >= 80 -> "High (stage 1 range)" to 2
        sys >= 120 -> "Elevated" to 1
        sys < 90 || dia < 60 -> "Low" to 1
        else -> "Normal" to 0
    }

    fun sugar(v: Double, whenTaken: String): Pair<String, Int> = when {
        v < 54 -> "Very low: eat fast sugar and get help now" to 3
        v < 70 -> "Low: eat something sweet now" to 2
        v >= 300 -> "Very high: contact your doctor today" to 3
        whenTaken == "Fasting" -> when { v < 100 -> "Normal" to 0; v < 126 -> "Above normal (pre-diabetes range)" to 1; else -> "High (diabetes range)" to 2 }
        else -> when { v < 140 -> "Normal" to 0; v < 200 -> "Above normal" to 1; else -> "High" to 2 }
    }

    fun report(list: List<JSONObject>): String = buildString {
        val bp = list.filter { it.optString("kind") == "bp" }; val su = list.filter { it.optString("kind") == "sugar" }
        if (bp.isNotEmpty()) {
            appendLine("BLOOD PRESSURE (${bp.size} readings)")
            appendLine("Average: ${bp.map { it.optDouble("sys") }.average().toInt()}/${bp.map { it.optDouble("dia") }.average().toInt()}   Highest: ${bp.maxOf { it.optDouble("sys") }.toInt()}/${bp.maxOf { it.optDouble("dia") }.toInt()}   Lowest: ${bp.minOf { it.optDouble("sys") }.toInt()}/${bp.minOf { it.optDouble("dia") }.toInt()}")
            bp.takeLast(40).forEach { appendLine("${it.optString("date")}   ${it.optDouble("sys").toInt()}/${it.optDouble("dia").toInt()}" + (if (it.optDouble("pulse", 0.0) > 0) "   pulse ${it.optDouble("pulse").toInt()}" else "") + "   ${bp(it.optDouble("sys"), it.optDouble("dia")).first}") }
            appendLine()
        }
        if (su.isNotEmpty()) {
            appendLine("BLOOD SUGAR, mg/dL (${su.size} readings)")
            su.groupBy { it.optString("when") }.forEach { (w, l) -> appendLine("$w average: ${l.map { it.optDouble("val") }.average().toInt()} (${l.size} readings)") }
            su.takeLast(40).forEach { appendLine("${it.optString("date")}   ${it.optDouble("val").toInt()}   ${it.optString("when")}   ${sugar(it.optDouble("val"), it.optString("when")).first}") }
        }
        appendLine(); append("Self-recorded in Atlas. Ranges are the standard adult ones and are not a diagnosis.")
    }
}

@Composable
fun VitalsScreen() {
    val c = LocalContext.current
    val m = MaterialTheme.colorScheme
    var list by remember { mutableStateOf(Vitals.all(c)) }
    fun reload() { list = Vitals.all(c) }
    var kind by remember { mutableStateOf("bp") }
    var sys by remember { mutableStateOf("") }; var dia by remember { mutableStateOf("") }; var pulse by remember { mutableStateOf("") }
    var v by remember { mutableStateOf("") }; var whenT by remember { mutableStateOf("Fasting") }
    val numKb = KeyboardOptions(keyboardType = KeyboardType.Number)
    val shown = list.filter { it.optString("kind") == kind }
    fun level(o: JSONObject) = if (kind == "bp") Vitals.bp(o.optDouble("sys"), o.optDouble("dia")) else Vitals.sugar(o.optDouble("val"), o.optString("when"))
    fun colour(l: Int): Color = when (l) { 0 -> m.primary; 1 -> m.tertiary; else -> m.error }
    val last = shown.lastOrNull(); val recent = shown.takeLast(7)

    AtlasScreen(title = "Vitals", subtitle = "Blood pressure and sugar, with the standard ranges and a page for your doctor.", hub = Hub.Health) {
        item("kind") { Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { RuneChip("Blood pressure", kind == "bp") { kind = "bp" }; RuneChip("Blood sugar", kind == "sugar") { kind = "sugar" } } }
        item("stats") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Latest", last?.let { if (kind == "bp") "${it.optDouble("sys").toInt()}/${it.optDouble("dia").toInt()}" else it.optDouble("val").toInt().toString() } ?: "–", last?.let { colour(level(it).second) } ?: m.onSurfaceVariant, Modifier.weight(1f))
                Stat("Last 7 average", if (recent.isEmpty()) "–" else if (kind == "bp") "${recent.map { it.optDouble("sys") }.average().toInt()}/${recent.map { it.optDouble("dia") }.average().toInt()}" else recent.map { it.optDouble("val") }.average().toInt().toString(), m.primary, Modifier.weight(1.2f))
                Stat("In range", if (shown.isEmpty()) "–" else "${shown.count { level(it).second == 0 } * 100 / shown.size}%", m.secondary, Modifier.weight(1f))
            }
        }
        last?.let { o -> val (label, l) = level(o); item("verdict") { Text("Latest reading: $label", Modifier.padding(horizontal = 16.dp), color = colour(l), fontWeight = if (l >= 2) FontWeight.Bold else FontWeight.Medium) } }
        if (shown.size >= 2) item("chart") {
            val pts = shown.takeLast(30); val a = m.primary; val b = m.tertiary
            Canvas(Modifier.fillMaxWidth().height(120.dp).padding(16.dp)) {
                fun series(key: String, col: Color) {
                    val vals = pts.map { it.optDouble(key, 0.0) }; val lo = if (kind == "bp") 50.0 else 40.0; val hi = maxOf(if (kind == "bp") 180.0 else 250.0, vals.max())
                    val path = Path(); vals.forEachIndexed { i, x -> val px = size.width * i / (vals.size - 1).coerceAtLeast(1); val py = size.height - ((x - lo) / (hi - lo) * size.height).toFloat()
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py); drawCircle(col, 5f, Offset(px, py)) }
                    drawPath(path, col, style = Stroke(4f))
                }
                if (kind == "bp") { series("sys", a); series("dia", b) } else series("val", a)
            }
        }
        item("add") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New reading (today)", fontWeight = FontWeight.SemiBold)
                if (kind == "bp") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(sys, { sys = it }, Modifier.weight(1f), label = { Text("Top") }, singleLine = true, keyboardOptions = numKb)
                        OutlinedTextField(dia, { dia = it }, Modifier.weight(1f), label = { Text("Bottom") }, singleLine = true, keyboardOptions = numKb)
                        OutlinedTextField(pulse, { pulse = it }, Modifier.weight(1f), label = { Text("Pulse") }, singleLine = true, keyboardOptions = numKb)
                    }
                    if (num(sys) > 0 && num(dia) > 0) Vitals.bp(num(sys), num(dia)).let { (t, l) -> Text(t, color = colour(l), fontSize = 13.sp) }
                    Button({ Vitals.store(c).add(JSONObject().put("kind", "bp").put("date", LocalDate.now().toString()).put("sys", num(sys)).put("dia", num(dia)).put("pulse", num(pulse))); sys = ""; dia = ""; pulse = ""; reload() },
                        enabled = num(sys) in 50.0..300.0 && num(dia) in 30.0..200.0) { Text("Save") }
                } else {
                    OutlinedTextField(v, { v = it }, Modifier.fillMaxWidth(), label = { Text("Reading (mg/dL)") }, singleLine = true, keyboardOptions = numKb)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Fasting", "After meal", "Bedtime", "Random").forEach { w -> RuneChip(w, whenT == w) { whenT = w } } }
                    if (num(v) > 0) Vitals.sugar(num(v), whenT).let { (t, l) -> Text(t, color = colour(l), fontSize = 13.sp) }
                    Button({ Vitals.store(c).add(JSONObject().put("kind", "sugar").put("date", LocalDate.now().toString()).put("val", num(v)).put("when", whenT)); v = ""; reload() }, enabled = num(v) in 20.0..700.0) { Text("Save") }
                }
                if (list.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ runCatching { Share.file(c, Pdf.fromText(c, "Vitals report", Vitals.report(list)), "application/pdf") } }) { Text("PDF for my doctor") }
                    OutlinedButton({ Share.text(c, Vitals.report(list), "Vitals report") }) { Text("Share as text") }
                }
                Text("Ranges are the standard adult ones. They are a guide, not a diagnosis; follow your own doctor's targets.", fontSize = 12.sp, color = m.onSurfaceVariant)
            }
        }
        shown.reversed().take(40).forEach { o ->
            item("r_" + o.optString("id")) { val (label, l) = level(o); Card2 { Row {
                Column(Modifier.weight(1f)) {
                    Text(if (kind == "bp") "${o.optDouble("sys").toInt()}/${o.optDouble("dia").toInt()}" + (if (o.optDouble("pulse", 0.0) > 0) " · pulse ${o.optDouble("pulse").toInt()}" else "") else "${o.optDouble("val").toInt()} mg/dL · ${o.optString("when")}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(o.optString("date") + " · " + label, fontSize = 12.sp, color = colour(l)) }
                TextButton({ Vitals.store(c).remove(o.optString("id")); reload() }) { Text("✕") } } } }
        }
        item("end") { Spacer(Modifier.height(90.dp)) }
    }
}
