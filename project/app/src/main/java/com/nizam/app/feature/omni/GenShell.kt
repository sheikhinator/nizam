package com.nizam.app.feature.omni

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Generative Shell: no apps, just ask. Atlas composes a real native screen for that moment (numbers, lists, charts,
 * checklists, forms and buttons wired to the Kernel) from your data and the web. Pin the ones you want to keep; they rebuild live.
 */
object Shell {
    fun pins(c: android.content.Context) = JsonStore(c, "omni_shell_pins")
    const val SPEC = """Output only JSON describing a phone screen that answers the request, using real numbers from the user's data and the web when relevant (never invent figures; say when data is missing):
{"title":"short","blocks":[ ... ]}
Block types:
{"type":"text","text":"1-3 sentences"}
{"type":"stats","items":[{"label":"","value":"","sub":""}]}   (2-4 big numbers)
{"type":"list","items":[{"title":"","sub":"","url":"optional"}]}
{"type":"chart","kind":"bar|line","labels":[""],"values":[0]}
{"type":"table","headers":[""],"rows":[[""]]}
{"type":"checklist","items":[""]}
{"type":"input","key":"name","label":"what to type"}
{"type":"buttons","items":[{"label":"","ask":"a follow-up request that builds the next screen (may use {inputKey})"}, {"label":"","fn":"an Atlas function e.g. add_task","args":{}}]}
Use 3-7 blocks. Make it feel like a purpose-built app screen."""
}

@Composable
fun GenShellScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf<JSONObject?>(null) }
    var asked by remember { mutableStateOf("") }
    val inputs = remember { mutableStateMapOf<String, String>() }
    var v by remember { mutableIntStateOf(0) }
    fun build(req: String) { asked = req; busy = true; scope.launch {
        screen = O.aiJson(c, "Request: $req\n\n${Shell.SPEC}", 2200, web = true) ?: JSONObject().put("title", "Hmm").put("blocks", JSONArray().put(JSONObject().put("type", "text").put("text", "I couldn't build that screen. Try again or rephrase.")))
        inputs.clear(); busy = false } }
    Column {
        OCard { OTitle("Ask for any screen"); OMuted("No apps: describe what you need and Atlas builds the screen, live from your data and the web.")
            OField(q, "e.g. my spending vs last month with what to cut", 2) { q = it }
            ORow { OBtn(if (busy) "Building…" else "Build", q.isNotBlank() && !busy) { build(q) } }
            val pins = remember(v) { Shell.pins(c).all() }
            if (pins.isNotEmpty()) OChips(pins.map { it.optString("title") }, "") { t -> pins.firstOrNull { it.optString("title") == t }?.let { q = it.optString("q"); build(it.optString("q")) } }
            if (screen == null && !busy) OChips(listOf("Where did my money go this month?", "Plan my Friday around prayers", "Cricket today: live scores", "A workout for 20 minutes at home", "Compare 3 phones under Rs 100k"), "") { q = it; build(it) }
        }
        OBusy(busy, "Composing your screen…")
        screen?.let { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.optString("title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("📌 Pin", Modifier.clickable { Shell.pins(c).add(JSONObject().put("title", s.optString("title")).put("q", asked)); v++; com.nizam.app.ui.components.Feedback.show("Pinned. It rebuilds live each time.") }.padding(8.dp), color = MaterialTheme.colorScheme.primary)
            }
            O.arr(s.optJSONArray("blocks")).forEach { b -> Block(b, inputs, onAsk = { req -> build(inputs.entries.fold(req) { a, (k, x) -> a.replace("{$k}", x) }) },
                onFn = { fn, args -> scope.launch { com.nizam.app.ui.components.Feedback.show(Kernel.call(c, fn, args, "Generative Shell").optString("result").take(160)) } }) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Block(b: JSONObject, inputs: MutableMap<String, String>, onAsk: (String) -> Unit, onFn: (String, JSONObject) -> Unit) {
    val c = LocalContext.current
    val col = MaterialTheme.colorScheme
    when (b.optString("type")) {
        "text" -> OCard { OMd(b.optString("text")) }
        "stats" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            O.arr(b.optJSONArray("items")).take(4).forEach { s -> Column(Modifier.weight(1f)) { OCard {
                Text(s.optString("value"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); OMuted(s.optString("label"))
                if (s.optString("sub").isNotBlank()) Text(s.optString("sub"), style = MaterialTheme.typography.labelSmall, color = col.primary) } } } }
        "list" -> OCard { O.arr(b.optJSONArray("items")).forEach { i -> Column(Modifier.fillMaxWidth().clickable(enabled = i.optString("url").startsWith("http")) {
                runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(i.optString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }.padding(vertical = 4.dp)) {
            OText(i.optString("title")); if (i.optString("sub").isNotBlank()) OMuted(i.optString("sub")) } } }
        "chart" -> OCard {
            val vals = (0 until (b.optJSONArray("values")?.length() ?: 0)).map { b.optJSONArray("values")!!.optDouble(it, 0.0).toFloat() }
            val labels = O.strs(b.optJSONArray("labels"))
            val max = (vals.maxOrNull() ?: 1f).coerceAtLeast(0.0001f)
            val bar = b.optString("kind") != "line"; val pc = col.primary
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                if (vals.isEmpty()) return@Canvas
                val w = size.width / vals.size
                if (bar) vals.forEachIndexed { i, x -> val h = size.height * (x / max); drawRoundRect(pc, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(8f)) }
                else { val path = androidx.compose.ui.graphics.Path(); vals.forEachIndexed { i, x -> val px = i * w + w / 2; val py = size.height * (1 - x / max); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                    drawPath(path, pc, style = Stroke(5f)) }
            }
            Row(Modifier.fillMaxWidth()) { labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1) } }
        }
        "table" -> OCard {
            Row { O.strs(b.optJSONArray("headers")).forEach { Text(it, Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) } }
            val rows = b.optJSONArray("rows") ?: JSONArray()
            for (r in 0 until rows.length()) Row { O.strs(rows.optJSONArray(r)).forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } }
        }
        "checklist" -> OCard { O.strs(b.optJSONArray("items")).forEach { t -> var on by remember(t) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(on, { on = it }); OText(t) } } }
        "input" -> { val k = b.optString("key"); OField(inputs[k].orEmpty(), b.optString("label")) { inputs[k] = it } }
        "buttons" -> androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            O.arr(b.optJSONArray("items")).forEach { i ->
                if (i.optString("fn").isNotBlank()) OBtn2(i.optString("label")) { onFn(i.optString("fn"), i.optJSONObject("args") ?: JSONObject()) }
                else OBtn(i.optString("label")) { onAsk(i.optString("ask").ifBlank { i.optString("label") }) } } }
        else -> if (b.optString("text").isNotBlank()) OCard { OText(b.optString("text")) }
    }
}
