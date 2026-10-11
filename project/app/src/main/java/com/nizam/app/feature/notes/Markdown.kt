package com.nizam.app.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Obsidian-flavoured Markdown for reading view: headings, **bold**, *italic*, ~~strike~~, ==highlight==,
 * `code`, fenced code, quotes, callouts (> [!note]), task lists you can tick, bullet and numbered lists,
 * tables, rules, [[wikilinks|aliases]], ![[embeds]], [links](url), #tags and YAML properties.
 */
object Md {
    val WIKI = Regex("!?\\[\\[([^\\]|#]+)(#[^\\]|]*)?(\\|([^\\]]*))?]]")
    val TAG = Regex("(?<=^|\\s)#([\\p{L}\\p{N}_/-]+)")

    /** Splits "---\nkey: value\n---" properties off the top of a note. */
    fun frontmatter(body: String): Pair<Map<String, String>, String> {
        val lines = body.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to body
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return emptyMap<String, String>() to body
        val props = lines.subList(1, end + 1).mapNotNull { l -> l.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        return props to lines.drop(end + 2).joinToString("\n")
    }

    fun tags(body: String): Set<String> {
        val (props, text) = frontmatter(body)
        val fm = props["tags"]?.trim('[', ']')?.split(',', ' ')?.map { it.trim().trim('#') }?.filter { it.isNotBlank() }.orEmpty()
        val clean = text.replace(Regex("```[\\s\\S]*?```"), "").replace(Regex("`[^`]*`"), "")
        return (fm + TAG.findAll(clean).map { it.groupValues[1] }).map { it.lowercase() }.toSet()
    }

    fun headings(body: String): List<Pair<Int, String>> = frontmatter(body).second.lines()
        .mapNotNull { l -> Regex("^(#{1,6})\\s+(.*)").find(l)?.let { it.groupValues[1].length to it.groupValues[2].trim() } }

    /** Inline formatting into a string with "link", "url" and "tag" annotations for taps. */
    fun inline(text: String, accent: Color, code: Color): AnnotatedString = buildAnnotatedString {
        val pattern = Regex("(\\*\\*[^*]+\\*\\*)|(__[^_]+__)|(\\*[^*\\s][^*]*\\*)|(_[^_\\s][^_]*_)|(~~[^~]+~~)|(==[^=]+==)|(`[^`]+`)|" +
            "(!?\\[\\[[^\\]]+]])|(\\[[^\\]]+]\\([^)]+\\))|((?<=^|\\s)#[\\p{L}\\p{N}_/-]+)|(https?://\\S+)")
        var i = 0
        pattern.findAll(text).forEach { m ->
            append(text.substring(i, m.range.first))
            val t = m.value
            when {
                t.startsWith("**") || t.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.drop(2).dropLast(2)) }
                t.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(t.drop(2).dropLast(2)) }
                t.startsWith("==") -> withStyle(SpanStyle(background = accent.copy(alpha = 0.25f))) { append(t.drop(2).dropLast(2)) }
                t.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = code)) { append(" " + t.drop(1).dropLast(1) + " ") }
                t.startsWith("[[") || t.startsWith("![[") -> {
                    val w = WIKI.find(t)
                    val target = w?.groupValues?.get(1)?.trim().orEmpty()
                    val shown = w?.groupValues?.get(4)?.takeIf { it.isNotBlank() } ?: (target + (w?.groupValues?.get(2) ?: ""))
                    pushStringAnnotation("link", target)
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(shown) }
                    pop()
                }
                t.startsWith("[") -> {
                    val label = t.substringAfter('[').substringBefore("](")
                    val url = t.substringAfter("](").dropLast(1)
                    pushStringAnnotation("url", url)
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(label) }
                    pop()
                }
                t.startsWith("#") -> {
                    pushStringAnnotation("tag", t.drop(1).lowercase())
                    withStyle(SpanStyle(color = accent, background = accent.copy(alpha = 0.12f))) { append(t) }
                    pop()
                }
                t.startsWith("http") -> { pushStringAnnotation("url", t); withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(t) }; pop() }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.drop(1).dropLast(1)) }
            }
            i = m.range.last + 1
        }
        append(text.substring(i))
    }
}

/**
 * Reading view. [onLink] opens a note by title, [onTag] shows a tag, [onUrl] opens the web,
 * [onToggleTask] ticks "- [ ]" line number N in the source, [embed] returns an embedded note's body.
 */
@Composable
fun MarkdownView(
    body: String,
    onLink: (String) -> Unit,
    onTag: (String) -> Unit,
    onUrl: (String) -> Unit,
    onToggleTask: (Int) -> Unit,
    embed: (String) -> String?,
    depth: Int = 0
) {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    val codeBg = cs.surfaceVariant
    val (props, text) = Md.frontmatter(body)
    val offset = if (props.isEmpty()) 0 else body.lines().drop(1).indexOfFirst { it.trim() == "---" } + 2
    val lines = text.lines()

    @Composable
    fun rich(s: String, style: TextStyle, modifier: Modifier = Modifier) {
        val a = Md.inline(s, accent, codeBg)
        ClickableText(a, style = style.copy(color = style.color.takeIf { it != Color.Unspecified } ?: cs.onSurface), modifier = modifier) { pos ->
            a.getStringAnnotations(pos, pos).firstOrNull()?.let { ann ->
                when (ann.tag) { "link" -> onLink(ann.item); "tag" -> onTag(ann.item); "url" -> onUrl(ann.item) }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (props.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant.copy(alpha = 0.6f)).padding(10.dp)) {
            Text("Properties", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            props.forEach { (k, v) -> Row(Modifier.padding(top = 4.dp)) {
                Text(k, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.width(96.dp))
                rich(v, MaterialTheme.typography.bodySmall)
            } }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]; val t = line.trim(); val src = i + offset
            when {
                t.startsWith("```") -> {
                    val lang = t.removePrefix("```").trim()
                    val block = mutableListOf<String>(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { block += lines[i]; i++ }
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(codeBg).padding(12.dp)) {
                        if (lang.isNotBlank()) Text(lang, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text(block.joinToString("\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.horizontalScroll(rememberScrollState()))
                    }
                }
                Regex("^#{1,6}\\s").containsMatchIn(t) -> {
                    val level = t.takeWhile { it == '#' }.length
                    val style = when (level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall }
                    rich(t.drop(level).trim(), style.copy(fontWeight = FontWeight.SemiBold), Modifier.padding(top = if (level <= 2) 14.dp else 10.dp, bottom = 4.dp))
                }
                t == "---" || t == "***" || t == "___" -> HorizontalDivider(Modifier.padding(vertical = 10.dp), color = cs.outlineVariant)
                t.startsWith(">") -> {
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { quote += lines[i].trim().removePrefix(">").trimStart(); i++ }
                    i--
                    val callout = Regex("^\\[!(\\w+)]([+-]?)\\s*(.*)").find(quote.first())
                    val kind = callout?.groupValues?.get(1)?.lowercase()
                    val tint = when (kind) { null -> cs.outline; "warning", "caution", "attention" -> Color(0xFFE0A030)
                        "danger", "error", "bug", "failure" -> cs.error; "tip", "success", "check", "done" -> Color(0xFF3DAA6D)
                        "question", "help", "faq" -> Color(0xFFD08A30); else -> accent }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 6.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (kind != null) tint.copy(alpha = 0.10f) else Color.Transparent)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(tint))
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (callout != null) Text(callout.groupValues[3].ifBlank { kind!!.replaceFirstChar { it.uppercase() } },
                                style = MaterialTheme.typography.titleSmall, color = tint)
                            (if (callout != null) quote.drop(1) else quote).forEach { q -> rich(q, MaterialTheme.typography.bodyMedium.copy(color = cs.onSurfaceVariant)) }
                        }
                    }
                }
                Regex("^[-*+]\\s\\[([ xX])]\\s?").containsMatchIn(t) -> {
                    val done = t[3] != ' '
                    val indent = line.takeWhile { it == ' ' || it == '\t' }.length / 2
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = (indent * 16).dp)) {
                        Checkbox(done, { onToggleTask(src) })
                        rich(t.substring(5).trimStart(), MaterialTheme.typography.bodyLarge.copy(
                            textDecoration = if (done) TextDecoration.LineThrough else null, color = if (done) cs.onSurfaceVariant else Color.Unspecified))
                    }
                }
                Regex("^([-*+]|\\d+[.)])\\s").containsMatchIn(t) -> {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }.length / 2
                    val marker = Regex("^([-*+]|\\d+[.)])").find(t)!!.value
                    Row(Modifier.padding(start = (indent * 16).dp, top = 2.dp, bottom = 2.dp)) {
                        Text(if (marker.first().isDigit()) marker else "•", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
                            modifier = Modifier.width(22.dp))
                        rich(t.drop(marker.length).trim(), MaterialTheme.typography.bodyLarge)
                    }
                }
                t.startsWith("|") -> {
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows += cells
                        i++
                    }
                    i--
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState())) {
                        rows.forEachIndexed { r, cells -> Row(Modifier.background(if (r == 0) codeBg else Color.Transparent)) {
                            cells.forEach { cell -> Box(Modifier.width(120.dp).padding(6.dp)) {
                                rich(cell, MaterialTheme.typography.bodySmall.copy(fontWeight = if (r == 0) FontWeight.SemiBold else null)) } }
                        } }
                    }
                }
                t.startsWith("![[") && t.endsWith("]]") -> {
                    val target = Md.WIKI.find(t)?.groupValues?.get(1)?.trim().orEmpty()
                    val inner = if (depth < 2) embed(target) else null
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { onLink(target) }.padding(12.dp)) {
                        Text(target, style = MaterialTheme.typography.labelMedium, color = accent)
                        if (inner != null) MarkdownView(inner, onLink, onTag, onUrl, {}, embed, depth + 1)
                        else Text("Not created yet — tap to create", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                t.isBlank() -> Box(Modifier.padding(vertical = 4.dp))
                else -> rich(line, MaterialTheme.typography.bodyLarge, Modifier.padding(vertical = 2.dp))
            }
            i++
        }
    }
}

/** The graph's shape of the vault: note titles and who links to whom (links to missing notes included). */
data class VaultGraph(val nodes: List<String>, val edges: List<Pair<Int, Int>>, val missing: Set<Int>)

fun buildGraph(notes: List<Note>, focus: String? = null): VaultGraph {
    val titles = notes.map { it.title }.toMutableList()
    val idx = titles.withIndex().associate { it.value.lowercase() to it.index }.toMutableMap()
    val missing = mutableSetOf<Int>()
    val edges = mutableSetOf<Pair<Int, Int>>()
    notes.forEachIndexed { i, n ->
        Md.WIKI.findAll(n.body).map { it.groupValues[1].trim() }.forEach { target ->
            val j = idx[target.lowercase()] ?: run { titles += target; missing += titles.lastIndex; idx[target.lowercase()] = titles.lastIndex; titles.lastIndex }
            if (i != j) edges += (minOf(i, j) to maxOf(i, j))
        }
    }
    if (focus == null) return VaultGraph(titles, edges.toList(), missing)
    // local graph: the note, its neighbours, and theirs
    val f = idx[focus.lowercase()] ?: return VaultGraph(titles, edges.toList(), missing)
    val near = mutableSetOf(f)
    repeat(2) { val add = edges.filter { it.first in near || it.second in near }.flatMap { listOf(it.first, it.second) }; near += add }
    val keep = near.toList(); val map = keep.withIndex().associate { it.value to it.index }
    return VaultGraph(keep.map { titles[it] }, edges.filter { it.first in near && it.second in near }.map { map[it.first]!! to map[it.second]!! },
        missing.filter { it in near }.map { map[it]!! }.toSet())
}

@Composable
fun SmallTag(text: String, onClick: () -> Unit) {
    Text("#$text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
fun TagRow(tags: Collection<String>, onTag: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { SmallTag(it) { onTag(it) } } }
}
