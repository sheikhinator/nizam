package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.brain2.Calc
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.net.Ai
import com.nizam.app.net.Router
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Forge: describe a tool in your own words and Atlas builds it inside itself — no update needed.
 * A forged tool is a tracker, a calculator or an AI helper, built on the same engines as every
 * other tool, so it gets voice, trends, export, schedules and "turn into actions" for free.
 * Tools can be shared as a small file or a code and installed on another phone (Forge Exchange).
 */
object Forge {
    private const val PREFIX = "f_"
    private fun store(c: Context) = JsonStore(c, "forge_tools")
    @Volatile private var cache: List<Spec>? = null
    var version by mutableIntStateOf(0)
        private set

    fun raw(c: Context): List<JSONObject> = store(c).all()
    fun specs(c: Context? = Router.appContext): List<Spec> {
        c ?: return emptyList()
        cache?.let { return it }
        return raw(c).mapNotNull { runCatching { toSpec(it) }.getOrNull() }.also { cache = it }
    }
    fun find(id: String): Spec? = if (!id.startsWith(PREFIX)) null else specs().firstOrNull { it.id == id }

    private fun changed() { cache = null; version++ }

    fun install(c: Context, def: JSONObject): String {
        val id = def.optString("id").takeIf { it.startsWith(PREFIX) } ?: (PREFIX + System.currentTimeMillis().toString(36))
        def.put("id", id)
        toSpec(def)   // throws if it can't be built
        val s = store(c)
        s.all().firstOrNull { it.optString("id") == id }?.let { s.remove(it.optString("id")) }
        s.add(def)
        changed()
        return id
    }
    fun remove(c: Context, id: String) { store(c).remove(id); changed() }

    private fun hub(s: String) = Hub.values().firstOrNull { it.name.equals(s, true) || it.label.equals(s, true) } ?: Hub.Tools

    private fun fields(a: JSONArray?): List<F> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a!!.optJSONObject(i) ?: return@mapNotNull null
        val key = o.optString("key").lowercase().replace(Regex("[^a-z0-9_]"), "").ifBlank { "f$i" }.let { if (it[0].isDigit()) "f$it" else it }
        val kind = when (o.optString("kind").lowercase()) { "number" -> K.NUMBER; "date" -> K.DATE; "choice" -> K.CHOICE; "long", "paragraph" -> K.LONG; else -> K.TEXT }
        val opts = o.optJSONArray("options")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        F(key, o.optString("label").ifBlank { key }.take(60), if (kind == K.CHOICE && opts.isEmpty()) K.TEXT else kind, o.optString("hint").take(80), opts.take(12),
            default = if (kind == K.CHOICE) opts.firstOrNull().orEmpty() else "", optional = o.optBoolean("optional", false))
    }.distinctBy { it.key }.take(10)

    /** Turns a stored definition into a working tool. */
    fun toSpec(d: JSONObject): Spec {
        val id = d.getString("id"); val title = d.optString("title").ifBlank { "My tool" }.take(50)
        val blurb = d.optString("blurb").take(120); val h = hub(d.optString("hub"))
        val words = "forge forged my tool " + d.optString("words") + " " + title.lowercase()
        val fs = fields(d.optJSONArray("fields"))
        require(fs.isNotEmpty()) { "A tool needs at least one field" }
        return when (d.optString("kind")) {
            "calc" -> {
                val outs = d.optJSONArray("outputs") ?: JSONArray()
                val vars = fs.associate { it.key to 2.0 }
                val list = (0 until outs.length()).mapNotNull { i -> outs.optJSONObject(i)?.let { o -> o.optString("label") to o.optString("expr") } }
                    .filter { (l, e) -> l.isNotBlank() && e.isNotBlank() && runCatching { Calc.eval(e, vars) }.isSuccess }.take(8)
                require(list.isNotEmpty()) { "The calculator's formulas didn't work" }
                CalcSpec(id, title, blurb, Icons.Outlined.Construction, h, words, fs, list, d.optString("note").take(200))
            }
            "ask" -> {
                val tpl = d.optString("prompt").ifBlank { fs.joinToString("\n") { "${it.label}: {${it.key}}" } }
                AiSpec(id, title, blurb, Icons.Outlined.AutoAwesome, h, words, fs, d.optString("system").ifBlank { "You are a helpful, practical assistant." }.take(800),
                    web = d.optBoolean("web", false)) { v -> fs.fold(tpl) { acc, f -> acc.replace("{${f.key}}", v[f.key].orEmpty()) } }
            }
            else -> {
                val nums = fs.filter { it.kind == K.NUMBER }
                LogSpec(id, title, blurb, Icons.Outlined.Construction, h, words, fs,
                    line = { o -> o.optString(fs[0].key) to fs.drop(1).mapNotNull { f -> o.optString(f.key).takeIf { it.isNotBlank() }?.let { "${f.label}: $it" } }.take(3).joinToString(" · ") },
                    summary = { list -> if (list.isEmpty()) emptyList() else listOf("${list.size} entr${if (list.size == 1) "y" else "ies"}") +
                        nums.map { f -> val v = list.map { num(it.optString(f.key)) }; "${f.label}: total ${short(v.sum())} · average ${short(v.average())}" } })
            }
        }
    }

    suspend fun design(container: AppContainer, ask: String): JSONObject {
        val raw = Ai.generate(container.settingsRepository, """Design a small phone tool for this request: $ask

Choose ONE kind:
- "log": a tracker the user adds entries to (habits, expenses of one kind, readings, collections, journals).
- "calc": a calculator; outputs are formulas over the input field keys.
- "ask": an AI helper; the user fills fields and gets a written answer.

Return ONLY JSON:
{"kind":"log|calc|ask","title":"2-4 words","blurb":"one line","hub":"Money|Deen|Health|Learn|Home|Media|Memory|Work|Social|Tools|Create|Travel",
 "words":"search keywords","fields":[{"key":"snake_case","label":"Label","kind":"text|long|number|date|choice","options":["only for choice"],"hint":"","optional":false}],
 "outputs":[{"label":"Result name","expr":"formula using field keys, + - * / ( ) and functions min max round if gt lt sqrt pow"}],
 "system":"for ask: who the assistant is","prompt":"for ask: the request, with {field_key} placeholders","note":"for calc: one-line caveat or empty"}
Rules: 2 to 8 fields. For a log put the most identifying field first and a date field if timing matters. For calc only use number or choice fields
(a choice is its option index from 0). Currency is PKR. Keep labels short.""",
            system = "You design compact, genuinely useful tools. Output only one JSON object.", maxOutputTokens = 1400)
        val o = EngineX.json(raw) ?: throw IllegalStateException("Atlas couldn't design that. Try describing what you want to track or work out.")
        if (o.optString("kind") !in setOf("log", "calc", "ask")) o.put("kind", "log")
        toSpec(JSONObject(o.toString()).put("id", "f_preview"))   // validate now, not after install
        return o
    }

    fun shareCode(def: JSONObject): String = "ATLAS-TOOL:" + android.util.Base64.encodeToString(JSONObject(def.toString()).apply { remove("id") }.toString().toByteArray(), android.util.Base64.NO_WRAP)
    fun fromCode(code: String): JSONObject? = runCatching {
        val t = code.trim().substringAfter("ATLAS-TOOL:")
        JSONObject(String(android.util.Base64.decode(t, android.util.Base64.DEFAULT)))
    }.getOrNull() ?: EngineX.json(code)
}

@Composable
fun ForgeScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var ask by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<JSONObject?>(null) }
    var code by remember { mutableStateOf("") }
    val v = Forge.version
    val mine = remember(v) { Forge.raw(c) }
    AtlasScreen(title = "Tool Forge", subtitle = "Describe a tool. Atlas builds it into itself, no update needed.", hub = Hub.Tools) {
        item("ask") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(ask, { ask = it }, Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    placeholder = { Text("\"Track my car's fuel and service, with cost per km\"\n\"A Zakat-style split for our family committee\"") },
                    trailingIcon = { MicButton({ heard -> ask = heard }) })
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = {
                        busy = true; draft = null
                        scope.launch {
                            runCatching { Forge.design(container, ask) }.onSuccess { draft = it }.onFailure { Feedback.show(it.message ?: "Couldn't design that") }
                            busy = false
                        }
                    }, enabled = ask.isNotBlank() && !busy) { Text(if (busy) "Forging…" else "✦ Forge it") }
                }
                LazyRow(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    val ideas = listOf("Bike fuel & mileage log", "Committee (BC) calculator", "Plant watering tracker", "Cricket scores with friends",
                        "Freelance invoice helper", "Book quotes collection", "Electricity units to bill", "Rishta meeting notes")
                    items(ideas.size) { i -> Capsule(ideas[i], false) { ask = ideas[i] } }
                }
            }
        }
        draft?.let { d ->
            item("draft") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                    Text(d.optString("title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(when (d.optString("kind")) { "calc" -> "Calculator"; "ask" -> "AI helper"; else -> "Tracker" } + " · " + d.optString("blurb"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val fs = d.optJSONArray("fields") ?: JSONArray()
                    Text("Fields", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = Space.s))
                    for (i in 0 until fs.length()) fs.optJSONObject(i)?.let { f ->
                        Text("• ${f.optString("label")}  (${f.optString("kind")}${f.optJSONArray("options")?.let { a -> ": " + (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: ""})",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    d.optJSONArray("outputs")?.takeIf { d.optString("kind") == "calc" }?.let { outs ->
                        Text("Works out", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = Space.s))
                        for (i in 0 until outs.length()) outs.optJSONObject(i)?.let { Text("• ${it.optString("label")} = ${it.optString("expr")}", style = MaterialTheme.typography.bodySmall) }
                    }
                    Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Button(onClick = {
                            runCatching { Forge.install(c, JSONObject(d.toString()).apply { remove("id") }) }
                                .onSuccess { id -> draft = null; ask = ""; Feedback.show("Installed. It's in search too"); open("nx/$id") }
                                .onFailure { Feedback.show(it.message ?: "Couldn't install") }
                        }) { Text("Install") }
                        TextButton(onClick = { draft = null }) { Text("Discard") }
                    }
                }
            }
        }
        if (mine.isNotEmpty()) {
            item("mh") { SectionHeader("Your forged tools") }
            mine.forEach { d ->
                item("m" + d.optString("id")) {
                    ListRow(d.optString("title"), supporting = d.optString("blurb"), icon = Icons.Outlined.Construction, trailing = {
                        Row {
                            TextButton(onClick = { Share.text(c, "Install my Atlas tool \"${d.optString("title")}\": open Atlas → Tool Forge → Install from code, and paste:\n\n" + Forge.shareCode(d), "Atlas tool") }) { Text("Share") }
                            TextButton(onClick = { Forge.remove(c, d.optString("id")); Feedback.show("Removed") }) { Text("✕") }
                        }
                    }) { open("nx/" + d.optString("id")) }
                }
            }
        }
        item("import") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Install from code", fontWeight = FontWeight.SemiBold)
                Text("Someone shared a tool with you? Paste its code here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 4, placeholder = { Text("ATLAS-TOOL:…") })
                TextButton(onClick = {
                    val d = Forge.fromCode(code)
                    if (d == null) Feedback.show("That code isn't a tool")
                    else runCatching { Forge.install(c, d) }.onSuccess { id -> code = ""; Feedback.show("Installed"); open("nx/$id") }.onFailure { Feedback.show(it.message ?: "Couldn't install") }
                }, enabled = code.isNotBlank()) { Text("Install") }
            }
        }
    }
}

val ForgeSpecs: List<Spec> get() = listOf(
    ScreenSpec("toolforge", "Tool Forge", "Describe any tool and Atlas builds it into itself: trackers, calculators, AI helpers. Share them with a code.",
        Icons.Outlined.AutoAwesome, Hub.Tools, "forge build make create my own tool app tracker calculator custom maker exchange share install") { container, open -> ForgeScreen(container, open) })
