package com.nizam.app.feature.mind

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.LineChart
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.Point
import com.nizam.app.feature.insight.Correlations
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.insight.METRICS
import com.nizam.app.net.Ai
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.YearMonth
import kotlin.math.cos
import kotlin.math.sin

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun MindScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("Ask a chart") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Mind", "Charts on demand, your life wheel, your values, your ideas")
        ChipRow(listOf("Ask a chart", "Life wheel", "Values", "Ideas"), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        when (tab) {
            "Ask a chart" -> AskChart(container)
            "Life wheel" -> LifeWheel()
            "Values" -> ValuesTab()
            else -> IdeasTab(container)
        }
        Spacer(Modifier.height(60.dp))
    }
}

/** Any question about your data becomes a chart. The AI only picks metrics and a window — the numbers are real. */
@Composable
private fun AskChart(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var series by remember { mutableStateOf<List<Pair<String, List<Point>>>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(q, { q = it }, label = { Text("e.g. my sleep against my mood this month") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = q.isNotBlank() && !busy, onClick = {
        scope.launch {
            busy = true; note = null
            runCatching {
                val raw = Ai.generate(container.settingsRepository,
                    "Turn this request into a chart spec. Available metrics: ${METRICS.keys.joinToString()} " +
                        "(${METRICS.values.joinToString()}). Return ONLY JSON {\"metrics\":[\"...\"],\"days\":30,\"title\":\"...\"}. " +
                        "Use 1-3 metrics, days between 7 and 365.\n\nRequest: $q", maxOutputTokens = 300)
                val o = JSONObject(Ai.cleanJson(raw))
                val keys = o.optJSONArray("metrics")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.filter { it in METRICS } ?: emptyList()
                val days = Days.build(container, c, o.optInt("days", 30).coerceIn(7, 365))
                title = o.optString("title", q)
                series = keys.map { k -> METRICS.getValue(k) to days.mapNotNull { d -> d.values[k]?.let { Point(d.date.toString().takeLast(5), it) } } }
                if (keys.size >= 2) {
                    val pairs = days.mapNotNull { d -> val x = d.values[keys[0]]; val y = d.values[keys[1]]; if (x != null && y != null) x to y else null }
                    Correlations.pearson(pairs.map { it.first }, pairs.map { it.second })?.let { r ->
                        note = "${METRICS[keys[0]]} and ${METRICS[keys[1]]}: r = ${"%.2f".format(r)} over ${pairs.size} shared days" +
                            if (kotlin.math.abs(r) < 0.3) " — no real relationship." else if (r > 0) " — they rise together." else " — one falls as the other rises."
                    }
                }
                if (keys.isEmpty()) note = "I couldn't match that to anything Atlas tracks."
            }.onFailure { note = "Couldn't build it: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            busy = false
        }
    }) { Text(if (busy) "Drawing…" else "Chart it") }
    if (series.isNotEmpty()) Card {
        Text(title, style = MaterialTheme.typography.titleMedium)
        series.forEach { (name, pts) ->
            Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            if (pts.size < 2) Text("Not enough data yet.", style = MaterialTheme.typography.bodyMedium)
            else LineChart(points = pts, accent = MaterialTheme.colorScheme.primary)
        }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun LifeWheel() {
    val c = LocalContext.current
    val month = YearMonth.now().toString()
    val history = remember { mutableStateOf(Wheel.all(c)) }
    val scores = remember { mutableStateMapOf<String, Int>().apply { putAll(history.value[month] ?: WHEEL_AREAS.associateWith { 5 }) } }
    val previous = history.value.keys.filter { it < month }.maxOrNull()?.let { history.value[it] }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card {
        Text("How is each part of your life, this month?", style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp)) {
            val cx = size.width / 2; val cy = size.height / 2; val r = size.minDimension / 2
            fun pt(i: Int, v: Float): Offset {
                val a = -Math.PI / 2 + i * 2 * Math.PI / WHEEL_AREAS.size
                return Offset(cx + (r * v / 10f * cos(a)).toFloat(), cy + (r * v / 10f * sin(a)).toFloat())
            }
            listOf(2.5f, 5f, 7.5f, 10f).forEach { ring ->
                val p = Path(); WHEEL_AREAS.indices.forEach { i -> val o = pt(i, ring); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                p.close(); drawPath(p, muted.copy(alpha = 0.25f), style = Stroke(1.5f))
            }
            fun shape(values: Map<String, Int>) = Path().apply {
                WHEEL_AREAS.forEachIndexed { i, a -> val o = pt(i, values[a]?.toFloat() ?: 0f); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                close()
            }
            previous?.let { drawPath(shape(it), muted.copy(alpha = 0.6f), style = Stroke(3f)) }
            drawPath(shape(scores), primary.copy(alpha = 0.3f)); drawPath(shape(scores), primary, style = Stroke(4f))
        }
        if (previous != null) Text("Grey outline = last month", style = MaterialTheme.typography.labelMedium, color = muted)
        WHEEL_AREAS.forEach { a ->
            Row { Text(a, Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium); Text("${scores[a]}", style = MaterialTheme.typography.bodyMedium) }
            Slider((scores[a] ?: 5).toFloat(), { scores[a] = it.toInt() }, valueRange = 0f..10f, steps = 9)
        }
        Button(onClick = { Wheel.save(c, month, scores.toMap()); history.value = Wheel.all(c) }) { Text("Save this month") }
        val low = scores.minByOrNull { it.value }
        if (low != null) Text("Lowest right now: ${low.key}. That's usually the one worth a mission.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ValuesTab() {
    val c = LocalContext.current
    var list by remember { mutableStateOf(Values.all(c)) }
    var name by remember { mutableStateOf("") }
    var why by remember { mutableStateOf("") }
    Card {
        Text("Your values", style = MaterialTheme.typography.titleMedium)
        Text("Name what matters most. The Curator, Council and planner will check advice against these and tell you when " +
            "something conflicts.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it }, label = { Text("Value, e.g. Family first") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(why, { why = it }, label = { Text("What it means to you (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = name.isNotBlank(), onClick = { list = list + (name.trim() to why.trim()); Values.save(c, list); name = ""; why = "" }) { Text("Add") }
    }
    list.forEachIndexed { i, (n, w) ->
        Card {
            Text("${i + 1}. $n", style = MaterialTheme.typography.titleMedium)
            if (w.isNotBlank()) Text(w, style = MaterialTheme.typography.bodyMedium)
            Text("remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { list = list.filterIndexed { j, _ -> j != i }; Values.save(c, list) })
        }
    }
}

@Composable
private fun IdeasTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var list by remember { mutableStateOf(Ideas.all(c)) }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<Long?>(null) }
    Card {
        Text("Idea incubator", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(title, { title = it }, label = { Text("An idea, in a sentence") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = title.isNotBlank(), onClick = {
            list = listOf(Idea(System.currentTimeMillis(), title.trim(), "", "Seed", "")) + list; Ideas.save(c, list); title = ""
        }) { Text("Plant it") }
    }
    list.forEach { idea ->
        Card {
            Text(idea.title, style = MaterialTheme.typography.titleMedium)
            ChipRow(IDEA_STAGES, idea.stage, { st -> list = list.map { if (it.id == idea.id) it.copy(stage = st) else it }; Ideas.save(c, list) })
            if (idea.research.isNotBlank()) Text(idea.research, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            Row {
                OutlinedButton(enabled = busy == null, onClick = {
                    scope.launch {
                        busy = idea.id
                        val r = runCatching {
                            Ai.generate(container.settingsRepository,
                                "Develop this idea for the user (use what you know about them):\n\"${idea.title}\"\n" +
                                    (if (idea.research.isNotBlank()) "Earlier notes: ${idea.research}\n" else "") +
                                    "Give, concisely: what it really is, who it's for, what already exists like it, the riskiest " +
                                    "assumption, the cheapest test of that assumption, and one first step for this week.",
                                maxOutputTokens = 1200)
                        }.getOrElse { "Couldn't develop it: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                        list = list.map { if (it.id == idea.id) it.copy(research = r, stage = if (it.stage == "Seed") "Researched" else it.stage) else it }
                        Ideas.save(c, list); busy = null
                    }
                }) { Text(if (busy == idea.id) "Thinking…" else if (idea.research.isBlank()) "Develop" else "Develop further") }
                Spacer(Modifier.width(8.dp))
                Text("remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(role = Role.Button) { list = list - idea; Ideas.save(c, list) }.padding(10.dp))
            }
        }
    }
}
