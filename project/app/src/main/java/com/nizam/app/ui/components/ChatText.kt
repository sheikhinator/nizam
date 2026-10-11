package com.nizam.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Model replies rendered the way Claude renders them: headings, bold, italics, inline code, bullet
 * and numbered lists, and fenced code blocks you can scroll and copy. Selectable throughout.
 */
@Composable
fun ChatText(text: String, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val blocks = remember(text) { split(text) }
    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { i, b ->
                if (i > 0) Spacer(Modifier.height(6.dp))
                when (b) {
                    is Md.Code -> CodeBlock(b.body, codeBg)
                    is Md.Heading -> Text(inline(b.text, codeBg), style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }, modifier = Modifier.padding(top = 4.dp))
                    is Md.Bullet -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
                        Text(b.marker, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(inline(b.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                    }
                    is Md.Para -> Text(inline(b.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(body: String, bg: Color) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg)) {
        Text("copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button) {
                clipboard.setText(AnnotatedString(body)); Feedback.show("Copied")
            }.padding(start = 12.dp, top = 8.dp, end = 12.dp))
        Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
    }
}

private sealed class Md {
    data class Para(val text: String) : Md()
    data class Heading(val level: Int, val text: String) : Md()
    data class Bullet(val marker: String, val text: String, val depth: Int) : Md()
    data class Code(val body: String) : Md()
}

private fun split(src: String): List<Md> {
    val out = mutableListOf<Md>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Md.Para(para.toString().trim()); para.clear() }
    val lines = src.replace("\r", "").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { body.appendLine(lines[i]); i++ }
                out += Md.Code(body.toString().trimEnd())
            }
            t.isEmpty() -> flush()
            Regex("^#{1,6}\\s").containsMatchIn(t) -> { flush(); out += Md.Heading(t.takeWhile { it == '#' }.length, t.trimStart('#').trim()) }
            Regex("^[-*•]\\s").containsMatchIn(t) -> {
                flush(); out += Md.Bullet("•", t.drop(2).trim(), (line.length - line.trimStart().length) / 2)
            }
            Regex("^\\d+[.)]\\s").containsMatchIn(t) -> {
                flush(); val n = t.takeWhile { it.isDigit() }
                out += Md.Bullet("$n.", t.substringAfter(' ').trim(), (line.length - line.trimStart().length) / 2)
            }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

/** **bold**, *italic* / _italic_, `code` */
private fun inline(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)")
    var last = 0
    pattern.findAll(s).forEach { m ->
        append(s.substring(last, m.range.first))
        val (bold, code, it1, it2) = m.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" $code ") }
            it1.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(it1) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(it2) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}
