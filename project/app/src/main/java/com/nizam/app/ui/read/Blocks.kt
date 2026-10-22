package com.nizam.app.ui.read

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoFamily
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed content blocks. Lectures, insights and long-form module text all render through this,
 * so everything reads like a designed page rather than a wall of markdown.
 */
sealed class Block {
    data class Heading(val text: String) : Block()
    data class Paragraph(val text: String) : Block()
    data class Example(val title: String, val body: String) : Block()
    data class Takeaways(val items: List<String>) : Block()
    data class Numbered(val items: List<String>) : Block()
    data class Formula(val expression: String, val explain: String) : Block()
    data class Quote(val text: String, val attribution: String) : Block()
    data class Cite(val text: String) : Block()
    data class Practice(val question: String, val answer: String) : Block()
    /** A process drawn as connected steps — the most common diagram in any lesson. */
    data class Flow(val title: String, val steps: List<String>) : Block()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : Block()
    data class Chart(val title: String, val labels: List<String>, val values: List<Double>) : Block()
    data class Image(val url: String, val caption: String) : Block()
    data class Callout(val tone: String, val text: String) : Block()
}

object BlockParser {
    /** Parses the model's JSON. Falls back to paragraphs so a bad response still reads fine. */
    fun parse(raw: String): List<Block> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return plainText(trimmed)
        return try {
            val array = if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("content") ?: return plainText(trimmed)
            val out = mutableListOf<Block>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                when (o.optString("type")) {
                    "heading" -> out.add(Block.Heading(o.optString("text")))
                    "paragraph" -> out.add(Block.Paragraph(o.optString("text")))
                    "example" -> out.add(
                        Block.Example(o.optString("title", "Example"), o.optString("body"))
                    )
                    "takeaways" -> out.add(Block.Takeaways(stringList(o.optJSONArray("items"))))
                    "listNum" -> out.add(Block.Numbered(stringList(o.optJSONArray("items"))))
                    "formula" -> out.add(
                        Block.Formula(o.optString("expression"), o.optString("explain"))
                    )
                    "quote" -> out.add(Block.Quote(o.optString("text"), o.optString("attribution")))
                    "cite" -> out.add(Block.Cite(o.optString("text")))
                    "practice" -> out.add(
                        Block.Practice(o.optString("question"), o.optString("answer"))
                    )
                    "flow", "diagram" -> out.add(
                        Block.Flow(o.optString("title"), stringList(o.optJSONArray("steps")))
                    )
                    "table" -> {
                        val rows = o.optJSONArray("rows")
                        out.add(
                            Block.Table(
                                stringList(o.optJSONArray("headers")),
                                if (rows == null) emptyList()
                                else (0 until rows.length()).map { stringList(rows.optJSONArray(it)) }
                            )
                        )
                    }
                    "chart" -> {
                        val vals = o.optJSONArray("values")
                        out.add(
                            Block.Chart(
                                o.optString("title"),
                                stringList(o.optJSONArray("labels")),
                                if (vals == null) emptyList()
                                else (0 until vals.length()).map { vals.optDouble(it, 0.0) }
                            )
                        )
                    }
                    "image" -> out.add(Block.Image(o.optString("url"), o.optString("caption")))
                    "callout", "note", "warning" -> out.add(
                        Block.Callout(o.optString("tone", o.optString("type")), o.optString("text"))
                    )
                    else -> {
                        val text = o.optString("text")
                        if (text.isNotBlank()) out.add(Block.Paragraph(text))
                    }
                }
            }
            if (out.isEmpty()) plainText(trimmed) else out
        } catch (e: Exception) {
            plainText(trimmed)
        }
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }

    private fun plainText(text: String): List<Block> =
        text.split("\n\n").filter { it.isNotBlank() }.map { para ->
            val clean = para.trim()
            if (clean.startsWith("#")) Block.Heading(clean.trimStart('#', ' '))
            else Block.Paragraph(clean)
        }
}

@Composable
private fun PracticeCard(block: Block.Practice, accent: Color) {
    var shown by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clickable { shown = !shown }
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(block.question, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (shown) block.answer else "Tap to reveal answer",
                style = MaterialTheme.typography.bodyMedium,
                color = if (shown) MaterialTheme.colorScheme.onSurface else accent
            )
        }
    }
}

@Composable
fun BlockList(blocks: List<Block>, tone: Color, accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> {
                    Spacer(Modifier.height(24.dp))
                    Row {
                        Box(
                            Modifier.width(3.dp).height(22.dp)
                                .background(accent, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            block.text,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = DisplayFamily, fontWeight = FontWeight.Medium
                            )
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                is Block.Paragraph -> {
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                }

                is Block.Example -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tone.copy(alpha = 0.10f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                block.title.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = tone
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                block.body,
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)
                            )
                        }
                    }
                }

                is Block.Takeaways -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accent.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "TAKEAWAYS",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent
                            )
                            Spacer(Modifier.height(8.dp))
                            block.items.forEach {
                                Row(Modifier.padding(bottom = 6.dp)) {
                                    Text("— ", style = MaterialTheme.typography.bodyLarge, color = accent)
                                    Text(it, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }

                is Block.Numbered -> {
                    Column(Modifier.padding(bottom = 16.dp)) {
                        block.items.forEachIndexed { index, item ->
                            Row(Modifier.padding(bottom = 8.dp)) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = MonoFamily
                                    ),
                                    color = accent,
                                    modifier = Modifier.width(24.dp)
                                )
                                Text(item, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                is Block.Formula -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            MathView(
                                latex = block.expression,
                                textColor = MaterialTheme.colorScheme.onSurface
                            )
                            if (block.explain.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    block.explain,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                is Block.Quote -> {
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                        Box(Modifier.width(3.dp).height(60.dp).background(tone))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                block.text,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = DisplayFamily,
                                    fontStyle = FontStyle.Italic,
                                    lineHeight = 30.sp
                                )
                            )
                            if (block.attribution.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    block.attribution,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                is Block.Cite -> {
                    Text(
                        block.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                }

                is Block.Practice -> {
                    PracticeCard(block = block, accent = accent)
                }

                is Block.Flow -> FlowDiagram(block, tone, accent)
                is Block.Table -> TableBlock(block, accent)
                is Block.Chart -> ChartBlock(block, accent)
                is Block.Image -> ImageBlock(block)
                is Block.Callout -> CalloutBlock(block, tone, accent)

            }
        }
    }
}


/** Steps as boxes joined by arrows — drawn natively, so it inherits the theme and works offline. */
@Composable
private fun FlowDiagram(block: Block.Flow, tone: Color, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        if (block.title.isNotBlank()) {
            Text(block.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = tone)
            Spacer(Modifier.height(8.dp))
        }
        block.steps.forEachIndexed { index, step ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = tone.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp)) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFamily),
                        color = accent,
                        modifier = Modifier.width(26.dp)
                    )
                    Text(step, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (index < block.steps.lastIndex) {
                Text(
                    "↓",
                    style = MaterialTheme.typography.titleLarge,
                    color = accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TableBlock(block: Block.Table, accent: Color) {
    if (block.headers.isEmpty() && block.rows.isEmpty()) return
    val columns = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            if (block.headers.isNotEmpty()) {
                Row {
                    (0 until columns).forEach { c ->
                        Text(
                            block.headers.getOrNull(c).orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            modifier = Modifier.width(120.dp).padding(end = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            block.rows.forEach { row ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    (0 until columns).forEach { c ->
                        Text(
                            row.getOrNull(c).orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(120.dp).padding(end = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartBlock(block: Block.Chart, accent: Color) {
    if (block.values.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            if (block.title.isNotBlank()) {
                Text(block.title, style = MaterialTheme.typography.titleMedium)
            }
            val points = block.values.mapIndexed { i, v ->
                com.nizam.app.core.Point(block.labels.getOrNull(i) ?: "${i + 1}", v)
            }
            com.nizam.app.core.BarChart(points = points, accent = accent, height = 140)
            Row(Modifier.fillMaxWidth()) {
                points.forEach {
                    Text(
                        it.label.take(6),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Loads an image from a URL. Lessons work offline; images need a connection. */
@Composable
private fun ImageBlock(block: Block.Image) {
    var bitmap by remember(block.url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(block.url) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(block.url) { com.nizam.app.ui.design.guard {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                java.net.URL(block.url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        failed = bitmap == null
    } }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        when {
            bitmap != null -> androidx.compose.foundation.Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = block.caption,
                modifier = Modifier.fillMaxWidth()
            )
            failed -> Text(
                "Image unavailable offline",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Text("Loading image…", style = MaterialTheme.typography.labelMedium)
        }
        if (block.caption.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                block.caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CalloutBlock(block: Block.Callout, tone: Color, accent: Color) {
    val (icon, colour) = when (block.tone.lowercase()) {
        "warning", "mistake" -> "⚠" to MaterialTheme.colorScheme.error
        "tip" -> "✦" to accent
        else -> "ℹ" to tone
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colour.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    ) {
        Row(Modifier.padding(14.dp)) {
            Text(icon, style = MaterialTheme.typography.titleMedium, color = colour)
            Spacer(Modifier.width(10.dp))
            Text(block.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
