package com.nizam.app.feature.expertise

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.Space
import org.json.JSONArray
import org.json.JSONObject

/** Shared machinery for the screens where several kinds of expertise work together. */
object MindKit {
    /** One answer from one expertise; still Atlas. */
    suspend fun ask(container: AppContainer, e: Expertise.Expert, prompt: String, max: Int = 800, web: Boolean = false): String {
        Expertise.noteUse(container.appContext, e)
        return Ai.generate(container.settingsRepository, prompt, Expertise.block(e) + Training.block(container.appContext, e.id), max, web)
    }

    /** The Core expertise used to plan, judge and synthesise. */
    fun core(c: android.content.Context, id: String) = Expertise.find(c, id) ?: Expertise.all(c).first()

    /** A short directory of expertise for a planner prompt: Core plus one line per sector. */
    fun directory(c: android.content.Context): String = Expertise.all(c).filter { it.sector != "Yours" || it.prompt != null }
        .groupBy { it.sector }.entries.joinToString("\n") { (s, l) -> "$s: " + l.take(14).joinToString(", ") { it.name } }

    fun byName(c: android.content.Context, name: String): Expertise.Expert? {
        val n = name.trim().lowercase()
        val all = Expertise.all(c)
        return all.firstOrNull { it.name.lowercase() == n } ?: all.firstOrNull { it.name.lowercase().contains(n) || n.contains(it.name.lowercase()) }
    }

    fun json(raw: String): JSONObject? = runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrNull()
    fun array(o: JSONObject?, key: String): List<JSONObject> = o?.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    fun strings(o: JSONObject?, key: String): List<String> = o?.optJSONArray(key)?.let { a: JSONArray -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    /** A judge's score (1–10) for an answer, with a one-line reason. */
    suspend fun judge(container: AppContainer, question: String, answer: String): Pair<Int, String> {
        val raw = Ai.generate(container.settingsRepository,
            "Question: ${question.take(800)}\n\nAnswer:\n${answer.take(2500)}\n\nScore the answer 1-10 for accuracy, usefulness and safety together. " +
                "Return ONLY JSON {\"score\":n,\"why\":\"one short sentence\"}.",
            "You are a strict, fair judge. Be brief.", 120)
        val o = json(raw)
        return (o?.optInt("score", 5) ?: 5).coerceIn(1, 10) to (o?.optString("why").orEmpty().ifBlank { "No reason given." })
    }
}

/** Pick one or more kinds of expertise by search; Core ones first. */
@Composable
fun ExpertPicker(chosen: List<Expertise.Expert>, max: Int, onChange: (List<Expertise.Expert>) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val found = remember(q) { if (q.isBlank()) Expertise.all(ctx).filter { it.sector == "Core" || it.sector == "Yours" } else Expertise.search(ctx, q, null).take(12) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (chosen.isNotEmpty()) {
            Text("Chosen · tap to remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                chosen.forEach { e -> Capsule(e.name, true) { onChange(chosen - e) } }
            }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Find expertise (lawyer, coach, accountant…)") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            found.filter { it !in chosen }.forEach { e -> Capsule(e.name, false) { if (chosen.size < max) onChange(chosen + e) } }
        }
    }
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = Space.s))

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Links to the Atlas features a module absorbed, so nothing that existed is ever hidden. */
@Composable
fun AlsoHere(open: (String) -> Unit, vararg links: Pair<String, String>) {
    if (links.isEmpty()) return
    Heading("Also part of this")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        links.forEach { (label, route) -> Capsule(label, false) { open(route) } }
    }
}
