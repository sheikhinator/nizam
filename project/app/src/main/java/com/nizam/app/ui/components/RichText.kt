package com.nizam.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Atlas's answer view, used by every chat and every AI result in the app. Beyond text (headings, bold, lists,
 * tasks, links, quotes) it draws what the answer needs: tables, callouts, code, images, charts (bar, line, pie,
 * donut), flow diagrams, key-figure cards, timelines and progress bars, written by the model as small fenced blocks.
 * Unfinished blocks (while an answer streams in) show as plain text until they close.
 */
object RichFormat {
    /** What every AI in Atlas is told about the answer view, so answers use it well. */
    const val GUIDE = "Format answers for a rich chat view (Markdown). Use ## headings for sections, **bold** key terms, " +
        "bullet or numbered lists, and Markdown tables (| a | b | with a |---| line) for comparisons, costs and specs. " +
        "Use > [!TIP], > [!NOTE] or > [!WARNING] lines for callouts. When numbers compare or change over time, add a chart: " +
        "```chart\n{\"type\":\"bar|line|pie|donut\",\"title\":\"...\",\"unit\":\"Rs\",\"labels\":[\"A\",\"B\"],\"values\":[1,2]}\n``` " +
        "(several series: \"series\":[{\"name\":\"...\",\"values\":[...]}]). For a process or decision path: ```mermaid\nflowchart TD\nA[Start] --> B[Next]\n``` " +
        "For key figures: ```stats\n[{\"label\":\"...\",\"value\":\"...\",\"note\":\"...\"}]\n``` For plans and steps over time: " +
        "```timeline\n[{\"when\":\"...\",\"title\":\"...\",\"detail\":\"...\"}]\n``` For progress toward targets: ```progress\n[{\"label\":\"...\",\"value\":40,\"max\":100}]\n``` " +
        "Only include an image as ![alt](https://…) when you have a real image URL. Use these only when they genuinely help; keep short answers short."
}

private sealed class Block {
    data class Para(val text: String) : Block()
    data class Heading(val level: Int, val text: String) : Block()
    data class Item(val marker: String, val text: String, val depth: Int, val task: Boolean? = null) : Block()
    data class Quote(val kind: String, val text: String) : Block()
    data class Table(val head: List<String>, val rows: List<List<String>>, val align: List<Char>) : Block()
    data class Fence(val lang: String, val body: String, val closed: Boolean) : Block()
    data class Image(val alt: String, val url: String) : Block()
    object Rule : Block()
}

private fun cells(line: String): List<String> = line.trim().trim('|').split('|').map { it.trim() }

private fun parse(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
    val lines = src.replace("\r", "").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]; val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim().lowercase()
                val body = StringBuilder(); i++
                var closed = false
                while (i < lines.size) { if (lines[i].trim().startsWith("```")) { closed = true; break }; body.appendLine(lines[i]); i++ }
                out += Block.Fence(lang, body.toString().trimEnd(), closed)
            }
            t.isEmpty() -> flush()
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out += Block.Rule }
            Regex("^#{1,6}\\s").containsMatchIn(t) -> { flush(); out += Block.Heading(t.takeWhile { it == '#' }.length, t.trimStart('#').trim().trimEnd('#').trim()) }
            Regex("^!\\[[^\\]]*]\\((https?://[^)\\s]+)\\)$").matches(t) -> {
                flush(); val m = Regex("^!\\[([^\\]]*)]\\((https?://[^)\\s]+)\\)$").find(t)!!
                out += Block.Image(m.groupValues[1], m.groupValues[2])
            }
            t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$").matches(lines[i + 1].trim()) -> {
                flush()
                val head = cells(t)
                val align = cells(lines[i + 1]).map { c -> when { c.startsWith(":") && c.endsWith(":") -> 'c'; c.endsWith(":") -> 'r'; else -> 'l' } }
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                out += Block.Table(head, rows, align)
                continue
            }
            t.startsWith(">") -> {
                flush()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) { q.appendLine(lines[i].trim().removePrefix(">").trim()); i++ }
                var body = q.toString().trim()
                val kind = Regex("^\\[!(\\w+)]").find(body)?.groupValues?.get(1)?.uppercase()
                    ?: Regex("^\\*\\*(Note|Tip|Warning|Important|Caution)[:.]?\\*\\*", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.uppercase() ?: "QUOTE"
                body = body.replace(Regex("^\\[!\\w+]\\s*"), "")
                out += Block.Quote(kind, body)
                continue
            }
            Regex("^[-*+•]\\s+\\[( |x|X)]\\s").containsMatchIn(t) -> {
                flush(); val done = t.contains("[x]") || t.contains("[X]")
                out += Block.Item("", t.substringAfter("]").trim(), (line.length - line.trimStart().length) / 2, done)
            }
            Regex("^[-*+•]\\s").containsMatchIn(t) -> { flush(); out += Block.Item("•", t.drop(2).trim(), (line.length - line.trimStart().length) / 2) }
            Regex("^\\d+[.)]\\s").containsMatchIn(t) -> {
                flush(); out += Block.Item("${t.takeWhile { it.isDigit() }}.", t.substringAfter(' ').trim(), (line.length - line.trimStart().length) / 2)
            }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line.trimEnd()) }
        }
        i++
    }
    flush()
    return out
}

/** **bold**, *italic*, ~~strike~~, `code`, [links](https://…) and bare https links. */
private fun inline(s: String, codeBg: Color, accent: Color): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|~~(.+?)~~|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|(https?://[^\\s)]+)|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)")
    var last = 0
    pattern.findAll(s).forEach { m ->
        append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1].ifEmpty { g[2] }) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" ${g[3]} ") }
            g[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[4]) }
            g[5].isNotEmpty() -> { pushStringAnnotation("url", g[6]); withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(g[5]) }; pop() }
            g[7].isNotEmpty() -> { pushStringAnnotation("url", g[7]); withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(g[7].removePrefix("https://").take(48)) }; pop() }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[8].ifEmpty { g[9] }) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

/** The palette charts use: the theme's primary first, then distinct companions that read in light and dark. */
@Composable
private fun palette(): List<Color> {
    val p = MaterialTheme.colorScheme.primary
    return listOf(p, Color(0xFFE38B4F), Color(0xFF3FA37C), Color(0xFF6C7FE0), Color(0xFFE07A8C), Color(0xFFD4A24C), Color(0xFF4DB0D6), Color(0xFF8E97B8))
}

@Composable
fun RichText(text: String, modifier: Modifier = Modifier, onUrl: ((String) -> Unit)? = null) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val codeBg = cs.onSurface.copy(alpha = 0.07f)
    val blocks = remember(text) { parse(text) }
    val open: (String) -> Unit = onUrl ?: { u -> runCatching { com.nizam.app.feature.nx.Share.open(ctx, u) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Heading -> {
                    val style = when (b.level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; 3 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall }
                    Column(Modifier.padding(top = if (b.level <= 2) 8.dp else 4.dp)) {
                        Text(inline(b.text, codeBg, cs.primary), style = style.copy(fontWeight = FontWeight.Bold))
                        if (b.level <= 2) Box(Modifier.padding(top = 4.dp).width(36.dp).height(3.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.7f)))
                    }
                }
                is Block.Para -> Linked(inline(b.text, codeBg, cs.primary), MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp), open)
                is Block.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    when (b.task) {
                        null -> Text(b.marker, style = MaterialTheme.typography.bodyLarge, color = cs.primary, fontWeight = FontWeight.Bold,
                            modifier = Modifier.widthIn(min = 18.dp))
                        else -> Text(if (b.task) "☑" else "☐", style = MaterialTheme.typography.bodyLarge, color = cs.primary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Linked(inline(b.text, codeBg, cs.primary), MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp,
                        textDecoration = if (b.task == true) TextDecoration.LineThrough else null), open)
                }
                is Block.Quote -> Callout(b.kind, inline(b.text, codeBg, cs.primary))
                is Block.Table -> TableView(b, codeBg)
                is Block.Image -> coil.compose.AsyncImage(b.url, b.alt, Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(16.dp)).clickable { open(b.url) },
                    contentScale = ContentScale.Fit)
                Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(cs.outlineVariant))
                is Block.Fence -> Fence(b, codeBg)
            }
        }
    }
}

@Composable
private fun Linked(a: AnnotatedString, style: TextStyle, open: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (a.getStringAnnotations("url", 0, a.length).isEmpty()) Text(a, style = style.copy(color = cs.onSurface))
    else ClickableText(a, style = style.copy(color = cs.onSurface)) { pos -> a.getStringAnnotations("url", pos, pos).firstOrNull()?.let { open(it.item) } }
}

@Composable
private fun Callout(kind: String, text: AnnotatedString) {
    val (icon, color, title) = when (kind) {
        "WARNING", "CAUTION" -> Triple("⚠️", Color(0xFFE0903A), "Watch out")
        "TIP" -> Triple("💡", Color(0xFF3FA37C), "Tip")
        "IMPORTANT" -> Triple("❗", Color(0xFFE07A8C), "Important")
        "NOTE" -> Triple("ℹ️", Color(0xFF4F95D8), "Note")
        else -> Triple("", MaterialTheme.colorScheme.outline, "")
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f))) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(12.dp)) {
            if (title.isNotEmpty()) Text("$icon  $title", style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp, fontStyle = if (title.isEmpty()) FontStyle.Italic else FontStyle.Normal),
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun TableView(t: Block.Table, codeBg: Color) {
    val cs = MaterialTheme.colorScheme
    val cols = maxOf(t.head.size, t.rows.maxOfOrNull { it.size } ?: 0)
    // column widths from content, so short numbers stay narrow and descriptions get room
    val widths = (0 until cols).map { c ->
        val longest = (listOf(t.head.getOrNull(c).orEmpty()) + t.rows.map { it.getOrNull(c).orEmpty() }).maxOf { it.length }
        (longest * 7.5f + 28f).coerceIn(64f, 220f).dp
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(cs.primary.copy(alpha = 0.12f))) {
                (0 until cols).forEach { c ->
                    Text(inline(t.head.getOrNull(c).orEmpty(), codeBg, cs.primary), Modifier.width(widths[c]).padding(10.dp),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = align(t.align.getOrNull(c)))
                }
            }
            t.rows.forEachIndexed { r, row ->
                Row(Modifier.background(if (r % 2 == 1) cs.onSurface.copy(alpha = 0.035f) else Color.Transparent)) {
                    (0 until cols).forEach { c ->
                        val v = row.getOrNull(c).orEmpty()
                        val numeric = Regex("^[~≈]?\\s*(rs\\.?|pkr|\\$|€|£)?\\s*[-+]?[\\d,.]+\\s*(%|k|m|kg|g|km|min|h)?$", RegexOption.IGNORE_CASE).matches(v)
                        Text(inline(v, codeBg, cs.primary), Modifier.width(widths[c]).padding(10.dp), style = MaterialTheme.typography.bodyMedium,
                            textAlign = if (t.align.getOrNull(c) == 'l' && numeric) TextAlign.End else align(t.align.getOrNull(c)),
                            fontWeight = if (r == t.rows.lastIndex && row.firstOrNull()?.contains("total", true) == true) FontWeight.Bold else null)
                    }
                }
            }
        }
    }
}

private fun align(a: Char?) = when (a) { 'r' -> TextAlign.End; 'c' -> TextAlign.Center; else -> TextAlign.Start }

private class ChartData(val type: String, val title: String, val unit: String, val labels: List<String>, val series: List<Pair<String, List<Double>>>)
private class FlowData(val names: Map<String, String>, val shapes: Map<String, Char>, val edges: List<Triple<String, String, String>>, val order: List<String>)

/** Turns a closed fenced block into something drawable, or null (then it shows as code). Never throws. */
private fun visual(f: Block.Fence): Any? = if (!f.closed) null else runCatching {
    when (f.lang) {
        "chart", "graph" -> chartData(JSONObject(f.body))
        "mermaid", "flow", "flowchart", "diagram" -> flowData(f.body)
        "stats", "kpi", "infographic" -> objs(JSONArray(f.body)).takeIf { it.isNotEmpty() }?.let { "stats" to it }
        "timeline", "steps" -> objs(JSONArray(f.body)).takeIf { it.isNotEmpty() }?.let { "timeline" to it }
        "progress" -> objs(JSONArray(f.body)).takeIf { it.isNotEmpty() }?.let { "progress" to it }
        else -> null
    }
}.getOrNull()

private fun objs(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

private fun chartData(o: JSONObject): ChartData? {
    val labels = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val series: List<Pair<String, List<Double>>> = o.optJSONArray("series")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { s -> s.optString("name") to (s.optJSONArray("values")?.let { v -> (0 until v.length()).map { v.optDouble(it, 0.0) } }.orEmpty()) }
    } ?: listOf("" to (o.optJSONArray("values")?.let { v -> (0 until v.length()).map { v.optDouble(it, 0.0) } }.orEmpty()))
    val clean = series.map { (n, v) -> n to v.map { if (it.isNaN() || it.isInfinite()) 0.0 else it } }.filter { it.second.isNotEmpty() }
    if (clean.isEmpty()) return null
    return ChartData(o.optString("type", "bar").lowercase(), o.optString("title"), o.optString("unit"), labels, clean)
}

@Composable
private fun Fence(f: Block.Fence, codeBg: Color) {
    @Suppress("UNCHECKED_CAST")
    when (val v = remember(f) { visual(f) }) {
        is ChartData -> ChartBlock(v)
        is FlowData -> FlowBlock(v)
        is Pair<*, *> -> when (v.first) {
            "stats" -> StatsBlock(v.second as List<JSONObject>)
            "timeline" -> TimelineBlock(v.second as List<JSONObject>)
            else -> ProgressBlock(v.second as List<JSONObject>)
        }
        else -> CodeBox(f.lang, f.body, codeBg)
    }
}

@Composable
private fun CodeBox(lang: String, body: String, bg: Color) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp)) {
            Text(lang.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button) { clipboard.setText(AnnotatedString(body)); Feedback.show("Copied") })
        }
        Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
    }
}

@Composable
private fun Card(title: String?, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.onSurface.copy(alpha = 0.045f))
        .border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(14.dp)) {
        if (!title.isNullOrBlank()) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

private fun fmt(v: Double, unit: String): String {
    val n = if (kotlin.math.abs(v) >= 1000) "%,.0f".format(v) else if (v % 1.0 == 0.0) "%.0f".format(v) else "%.1f".format(v)
    return when { unit.isBlank() -> n; unit.length <= 3 && !unit.endsWith("%") && unit.first().isLetter() && unit.lowercase() in setOf("rs", "pkr", "$", "usd") -> "$unit $n"; else -> "$n$unit" }
}

/** ```chart {"type":"bar|line|pie|donut","title","unit","labels","values"|"series"} */
@Composable
private fun ChartBlock(d: ChartData) {
    val type = d.type; val unit = d.unit; val labels = d.labels; val series = d.series
    val colors = palette()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    Card(d.title.ifBlank { null }) {
        when (type) {
            "pie", "donut", "doughnut" -> {
                val values = series.first().second
                val total = values.sum().coerceAtLeast(1e-9)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(140.dp)) {
                        var start = -90f
                        values.forEachIndexed { i, v ->
                            val sweep = (v / total * 360).toFloat()
                            if (type == "pie") drawArc(colors[i % colors.size], start, sweep, true)
                            else drawArc(colors[i % colors.size], start, sweep - 1.5f, false, topLeft = Offset(14f, 14f),
                                size = Size(size.width - 28f, size.height - 28f), style = Stroke(26f))
                            start += sweep
                        }
                    }
                    Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        values.forEachIndexed { i, v ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size]))
                                Text(" ${labels.getOrNull(i) ?: "#${i + 1}"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                                Text("  ${(v / total * 100).let { "%.0f".format(it) }}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            "line", "area" -> {
                val all = series.flatMap { it.second }
                val max = (all.maxOrNull() ?: 1.0).coerceAtLeast(1e-9); val min = minOf(0.0, all.minOrNull() ?: 0.0)
                Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                    for (g in 0..3) { val y = size.height * g / 3f; drawLine(grid.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1f) }
                    series.forEachIndexed { si, (_, v) ->
                        if (v.size < 2) return@forEachIndexed
                        fun x(i: Int) = size.width * i / (v.size - 1).toFloat()
                        fun y(d: Double) = (size.height - (d - min) / (max - min) * size.height * 0.92).toFloat()
                        val p = Path().apply { v.forEachIndexed { i, d -> if (i == 0) moveTo(x(i), y(d)) else lineTo(x(i), y(d)) } }
                        if (type == "area" || series.size == 1) {
                            val fill = Path().apply { addPath(p); lineTo(x(v.lastIndex), size.height); lineTo(0f, size.height); close() }
                            drawPath(fill, colors[si % colors.size].copy(alpha = 0.14f))
                        }
                        drawPath(p, colors[si % colors.size], style = Stroke(5f))
                        drawCircle(colors[si % colors.size], 7f, Offset(x(v.lastIndex), y(v.last())))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    labels.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ink) }
                    Spacer(Modifier.weight(1f))
                    labels.lastOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ink) }
                }
                Text("Latest: " + series.joinToString(" · ") { (n, v) -> (if (n.isNotBlank()) "$n " else "") + fmt(v.last(), unit) },
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
            }
            else -> {
                // horizontal bars: labels stay readable however long they are
                val max = series.flatMap { it.second }.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
                val n = series.first().second.size
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0 until n).forEach { i ->
                        Column {
                            Text(labels.getOrNull(i) ?: "#${i + 1}", style = MaterialTheme.typography.labelMedium, color = ink)
                            series.forEachIndexed { si, (_, v) ->
                                val value = v.getOrNull(i) ?: 0.0
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                    Box(Modifier.weight(1f).height(14.dp)) {
                                        Box(Modifier.fillMaxWidth((value / max).toFloat().coerceIn(0.02f, 1f)).height(14.dp).clip(RoundedCornerShape(7.dp))
                                            .background(colors[si % colors.size]))
                                    }
                                    Text(fmt(value, unit), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.widthIn(min = 64.dp).padding(start = 8.dp), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (series.size > 1) Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            series.forEachIndexed { i, (name, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size])); Text(" $name", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** ```mermaid flowchart (A[Text] --> B{Question} -->|label| C(...)) or ```flow lines "A -> B". Drawn top to bottom. */
private fun flowData(src: String): FlowData? {
    class Edge(val from: String, val to: String, val label: String)
    val names = LinkedHashMap<String, String>()
    val shapes = HashMap<String, Char>()
    val edges = mutableListOf<Edge>()
    val node = Regex("([A-Za-z0-9_]+)\\s*(\\[\\[?([^\\]]*)]]?|\\{([^}]*)}|\\(\\(?([^)]*)\\)?\\))?")
    fun reg(token: String): String? {
        val m = node.find(token.trim()) ?: return null
        val id = m.groupValues[1]
        val label = m.groupValues[3].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5] } }.trim().trim('"')
        if (label.isNotEmpty() || id !in names) names[id] = label.ifEmpty { names[id] ?: id }
        if (m.groupValues[4].isNotEmpty()) shapes[id] = 'd' else if (m.groupValues[5].isNotEmpty()) shapes[id] = 'r'
        return id
    }
    src.lines().map { it.trim().removeSuffix(";") }.filter { it.isNotBlank() && !Regex("^(flowchart|graph)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) && !it.startsWith("%%") && !it.startsWith("style") && !it.startsWith("classDef") }.forEach { l ->
        val parts = l.split(Regex("\\s*(-->|---|->|==>|-\\.->)\\s*"))
        if (parts.size < 2) { reg(l); return@forEach }
        var prev: String? = null
        parts.forEach { raw ->
            var p = raw; var label = ""
            Regex("^\\|([^|]*)\\|\\s*").find(p)?.let { label = it.groupValues[1]; p = p.removeRange(it.range) }
            val id = reg(p) ?: return@forEach
            prev?.let { pv -> edges += Edge(pv, id, label) }
            prev = id
        }
    }
    if (names.size < 2) return null
    // order: a simple walk from the nodes nothing points at
    val order = mutableListOf<String>()
    val incoming = edges.map { it.to }.toSet()
    val queue = ArrayDeque(names.keys.filter { it !in incoming }.ifEmpty { listOf(names.keys.first()) })
    while (queue.isNotEmpty()) { val n = queue.removeFirst(); if (n in order) continue; order += n; edges.filter { it.from == n }.forEach { queue.addLast(it.to) } }
    names.keys.filter { it !in order }.forEach { order += it }
    return FlowData(names, shapes, edges.map { Triple(it.from, it.to, it.label) }, order)
}

@Composable
private fun FlowBlock(d: FlowData) {
    val names = d.names; val shapes = d.shapes; val order = d.order
    val edges = d.edges.map { Triple(it.first, it.second, it.third) }
    val cs = MaterialTheme.colorScheme
    Card(null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            order.forEachIndexed { i, id ->
                val shape = shapes[id]
                val bg = when (shape) { 'd' -> Color(0xFFE38B4F).copy(alpha = 0.16f); 'r' -> cs.tertiary.copy(alpha = 0.16f); else -> cs.primary.copy(alpha = 0.13f) }
                val border = when (shape) { 'd' -> Color(0xFFE38B4F); 'r' -> cs.tertiary; else -> cs.primary }
                Text((if (shape == 'd') "◆ " else "") + names[id].orEmpty(), Modifier.widthIn(min = 140.dp, max = 300.dp)
                    .clip(RoundedCornerShape(if (shape == 'r') 50 else 12)).background(bg).border(1.5.dp, border.copy(alpha = 0.7f), RoundedCornerShape(if (shape == 'r') 50 else 12))
                    .padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                val outs = edges.filter { it.first == id }
                if (i < order.lastIndex || outs.isNotEmpty()) {
                    val next = order.getOrNull(i + 1)
                    val direct = outs.firstOrNull { it.second == next }
                    val others = outs.filter { it.second != next }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (direct != null || (outs.isEmpty() && next != null && edges.any { it.second == next })) {
                            Text("↓" + (direct?.third?.takeIf { it.isNotBlank() }?.let { "  $it" } ?: ""), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(vertical = 2.dp))
                        } else Spacer(Modifier.height(10.dp))
                        others.forEach { e ->
                            Text("↳ ${e.third.ifBlank { "then" }}: ${names[e.second].orEmpty()}", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** ```stats [{"label","value","note"}] as key-figure tiles. */
@Composable
private fun StatsBlock(items: List<JSONObject>) {
    val colors = palette()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { c, o ->
                    val col = colors[(r * 2 + c) % colors.size]
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(col.copy(alpha = 0.11f)).border(1.dp, col.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Text(o.optString("value"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = col)
                        Text(o.optString("label"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        o.optString("note").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** ```timeline [{"when","title","detail"}] */
@Composable
private fun TimelineBlock(items: List<JSONObject>) {
    val cs = MaterialTheme.colorScheme
    Card(null) {
        items.forEachIndexed { i, o ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(22.dp)) {
                    Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape).background(cs.primary))
                    if (i < items.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(cs.primary.copy(alpha = 0.3f)))
                }
                Column(Modifier.padding(start = 10.dp, bottom = if (i < items.lastIndex) 14.dp else 0.dp)) {
                    o.optString("when").takeIf { it.isNotBlank() }?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.primary, fontWeight = FontWeight.Bold) }
                    Text(o.optString("title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    o.optString("detail").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                }
            }
        }
    }
}

/** ```progress [{"label","value","max"}] */
@Composable
private fun ProgressBlock(items: List<JSONObject>) {
    val colors = palette()
    Card(null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEachIndexed { i, o ->
                val v = o.optDouble("value", 0.0); val max = o.optDouble("max", 100.0).coerceAtLeast(1e-9)
                val f = (v / max).toFloat().coerceIn(0f, 1f)
                Column {
                    Row { Text(o.optString("label"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors[i % colors.size]) }
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(10.dp).clip(CircleShape).background(colors[i % colors.size].copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxWidth(f).height(10.dp).clip(CircleShape).background(colors[i % colors.size]))
                    }
                }
            }
        }
    }
}
