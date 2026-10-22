package com.nizam.app.feature.diagram

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.net.Ai
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A node in a flow diagram, and the shape Atlas asks the AI for. */
private data class DNode(val id: String, val label: String, val step: Int)
private data class DEdge(val from: String, val to: String, val label: String)
private class Diagram(val nodes: List<DNode>, val edges: List<DEdge>)

/**
 * Turns a plain-language description into a flowchart drawn on the phone — the AI only picks the
 * steps and how they connect; the phone lays them out and draws the boxes and arrows, so nothing
 * depends on an image model or an outside drawing library.
 */
private object DiagramEngine {
    suspend fun make(container: AppContainer, text: String): Diagram {
        val raw = Ai.generate(container.settingsRepository,
            "Turn this into a simple flow diagram of 4 to 10 steps. Reply with JSON only: " +
                "{\"nodes\":[{\"id\":\"a\",\"label\":\"short step name\"}],\"edges\":[{\"from\":\"a\",\"to\":\"b\",\"label\":\"\"}]}\n\n$text",
            system = "You design clear, short flowcharts. Every edge's from/to must be a node id. JSON only.", maxOutputTokens = 700)
        val j = JSONObject(Ai.cleanJson(raw))
        val ns = j.optJSONArray("nodes")
        val ids = mutableListOf<String>()
        val nodes = (0 until (ns?.length() ?: 0)).mapNotNull { i ->
            val o = ns!!.getJSONObject(i)
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            ids += id
            DNode(id, o.optString("label", id).take(40), ids.size - 1)
        }
        val es = j.optJSONArray("edges")
        val edges = (0 until (es?.length() ?: 0)).mapNotNull { i ->
            val o = es!!.getJSONObject(i)
            val from = o.optString("from"); val to = o.optString("to")
            if (from !in ids || to !in ids) null else DEdge(from, to, o.optString("label"))
        }
        return Diagram(nodes, edges)
    }
}

@Composable
internal fun DiagramScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var text by remember { mutableStateOf("") }
    var diagram by remember { mutableStateOf<Diagram?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // No verticalScroll: this screen is reached through the generic tool-screen shell, which already
    // scrolls its content in a LazyColumn — nesting another scrollable Column crashes Compose.
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Describe it, see it as a diagram", style = MaterialTheme.typography.titleMedium)
        Text("Explain a process in your own words — how you handle expenses, a morning routine, a recipe's steps — and Atlas draws it as a flowchart on your phone.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(text, { text = it }, label = { Text("What should the diagram show?") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(enabled = text.isNotBlank() && !busy, onClick = {
            busy = true; error = ""
            scope.launch {
                diagram = runCatching { DiagramEngine.make(container, text) }.getOrElse { error = "Couldn't make that diagram: ${it.message ?: "try rephrasing it"}"; null }
                busy = false
            }
        }) { Text(if (busy) "Drawing…" else "Draw it") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        diagram?.let { d ->
            if (d.nodes.isEmpty()) Text("That came back empty — try describing it with clearer steps.", style = MaterialTheme.typography.bodySmall)
            else DiagramCanvas(d)
        }
    }
}

@Composable
private fun DiagramCanvas(d: Diagram) {
    val boxH = 64.dp; val gap = 28.dp
    val tint = MaterialTheme.colorScheme.primary
    val onTint = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height((d.nodes.size * 92).dp)) {
        val w = size.width
        val boxWpx = w * 0.7f
        val x = (w - boxWpx) / 2
        val boxHpx = boxH.toPx(); val gapPx = gap.toPx()
        fun yOf(step: Int) = step * (boxHpx + gapPx)
        // edges first, so boxes sit on top of the lines
        d.edges.forEach { e ->
            val from = d.nodes.firstOrNull { it.id == e.from } ?: return@forEach
            val to = d.nodes.firstOrNull { it.id == e.to } ?: return@forEach
            val start = Offset(w / 2, yOf(from.step) + boxHpx)
            val end = Offset(w / 2, yOf(to.step))
            drawLine(tint.copy(alpha = 0.6f), start, end, strokeWidth = 4f)
            // a simple arrowhead
            val dir = if (end.y >= start.y) 1f else -1f
            drawLine(tint.copy(alpha = 0.6f), Offset(end.x, end.y), Offset(end.x - 10f, end.y - 14f * dir), strokeWidth = 4f)
            drawLine(tint.copy(alpha = 0.6f), Offset(end.x, end.y), Offset(end.x + 10f, end.y - 14f * dir), strokeWidth = 4f)
        }
        d.nodes.forEach { n ->
            val top = yOf(n.step)
            drawRoundRect(surface, topLeft = Offset(x, top), size = androidx.compose.ui.geometry.Size(boxWpx, boxHpx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f))
            drawRoundRect(tint.copy(alpha = 0.5f), topLeft = Offset(x, top), size = androidx.compose.ui.geometry.Size(boxWpx, boxHpx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f), style = Stroke(3f))
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = onTint.toArgb(); textAlign = android.graphics.Paint.Align.CENTER; textSize = 30f; isAntiAlias = true
                }
                val lines = wrap(n.label, paint, boxWpx - 24f)
                val lineH = 34f
                val startY = top + boxHpx / 2 - (lines.size - 1) * lineH / 2 + 10f
                lines.forEachIndexed { i, l -> drawText(l, w / 2, startY + i * lineH, paint) }
            }
        }
    }
}

private fun wrap(text: String, paint: android.graphics.Paint, maxWidth: Float): List<String> {
    val words = text.split(" "); val out = mutableListOf<String>(); var cur = ""
    for (w in words) {
        val next = if (cur.isEmpty()) w else "$cur $w"
        if (paint.measureText(next) > maxWidth && cur.isNotEmpty()) { out += cur; cur = w } else cur = next
    }
    if (cur.isNotEmpty()) out += cur
    return out.take(3)
}

object DiagramSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("diagram", "Diagram maker", "Describe a process, see it as a flowchart on your phone", Icons.Outlined.AccountTree, Hub.Create,
            "diagram flowchart flow chart visualise process steps draw") { c, _ -> DiagramScreen(c) },
    )
}
