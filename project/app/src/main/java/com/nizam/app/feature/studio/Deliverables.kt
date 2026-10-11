package com.nizam.app.feature.studio

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.ChatModes
import com.nizam.app.net.Ai
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Deliverables: the company makes real files. Ask for anything (a picture, a thesis, a deck, a budget,
 * a working mini-app, a narrated explainer, code) and it is planned, made, checked and put on the shelf
 * as a file you can open, share or ask to revise.
 */
object Deliverables {

    enum class Kind(val emoji: String, val label: String, val hint: String) {
        AUTO("✨", "Anything", "Atlas decides the best format"),
        IMAGE("🖼️", "Image", "Pictures, posters, logos, art"),
        DOCUMENT("📄", "Document", "Essays, reports, letters, CVs, guides"),
        THESIS("🎓", "Thesis", "Long researched work in chapters with references"),
        SLIDES("📊", "Slides", "A presentation deck as PDF"),
        SHEET("🧮", "Spreadsheet", "Tables, budgets, plans as CSV"),
        APP("🧩", "Mini-app", "A working tool or game you can open"),
        AUDIO("🎧", "Audio", "A narrated explainer or podcast"),
        CODE("💻", "Code", "Scripts and source files"),
    }

    data class Item(
        val id: String, val kind: Kind, val title: String, val request: String,
        val files: List<String>, val created: Long, val summary: String, val team: List<String>,
    ) {
        fun dir(c: Context) = File(root(c), id)
        fun main(c: Context): File? = files.firstOrNull()?.let { File(dir(c), it) }
        fun json() = JSONObject().put("id", id).put("kind", kind.name).put("title", title).put("request", request)
            .put("files", JSONArray(files)).put("created", created).put("summary", summary).put("team", JSONArray(team))
        companion object {
            fun of(o: JSONObject) = Item(o.getString("id"), runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.DOCUMENT),
                o.optString("title"), o.optString("request"), o.optJSONArray("files").strings(), o.optLong("created"),
                o.optString("summary"), o.optJSONArray("team").strings())
        }
    }

    data class Job(val id: String, val request: String, val kind: Kind, val stage: String, val fraction: Float, val who: String, val error: String? = null)

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

    fun root(c: Context) = File(c.filesDir, "deliverables").apply { mkdirs() }

    // ------------------------------------------------------------------ shelf

    private val _shelf = MutableStateFlow<List<Item>>(emptyList())
    val shelf: StateFlow<List<Item>> = _shelf.asStateFlow()
    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs.asStateFlow()

    fun load(c: Context): List<Item> {
        val list = root(c).listFiles()?.mapNotNull { d ->
            File(d, "meta.json").takeIf { it.exists() }?.let { runCatching { Item.of(JSONObject(it.readText())) }.getOrNull() }
        }.orEmpty().sortedByDescending { it.created }
        _shelf.value = list
        return list
    }

    fun delete(c: Context, item: Item) { item.dir(c).deleteRecursively(); load(c) }

    private fun save(c: Context, item: Item) { File(item.dir(c), "meta.json").writeText(item.json().toString()); load(c) }

    fun shareIntent(c: Context, f: File): Intent {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
        return Intent(Intent.ACTION_SEND).setType(mime(f)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun openIntent(c: Context, f: File): Intent {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun mime(f: File) = when (f.extension.lowercase()) {
        "pdf" -> "application/pdf"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "csv" -> "text/csv"
        "html" -> "text/html"; "wav" -> "audio/wav"; "md" -> "text/markdown"; else -> "text/plain"
    }

    // ------------------------------------------------------------------ ordering

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** Start making something. Returns the job id; progress shows in [jobs], the result lands on [shelf]. */
    fun order(container: AppContainer, request: String, kind: Kind = Kind.AUTO, options: Options = Options()): String {
        val id = "d" + System.currentTimeMillis()
        val c = container.appContext
        update(Job(id, request, kind, "Reading your brief", 0.02f, "Lead"))
        running[id] = scope.launch {
            runCatching {
                val k = if (kind == Kind.AUTO) classify(container, request) else kind
                update(Job(id, request, k, "Planning", 0.05f, "Lead"))
                val maker = Maker(container, id, request, k, options)
                val item = maker.make()
                save(c, item)
                runCatching {
                    com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE,
                        5100 + (id.hashCode() % 50), "${k.emoji} Ready: ${item.title}", "Your company finished it. Open the shelf to see it.")
                }
                _jobs.value = _jobs.value - id
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) { _jobs.value = _jobs.value - id; return@onFailure }
                _jobs.value[id]?.let { update(it.copy(error = e.message ?: "Something went wrong")) }
            }
            running.remove(id)
        }
        return id
    }

    fun cancel(id: String) { running.remove(id)?.cancel(); _jobs.value = _jobs.value - id }
    fun dismiss(id: String) { _jobs.value = _jobs.value - id }

    /** Ask for changes: the same brief plus your notes, made again as a new version. */
    fun revise(container: AppContainer, item: Item, notes: String): String =
        order(container, item.request + "\n\nRevision notes from the user (apply all of them):\n" + notes, item.kind,
            Options(previous = item.main(container.appContext)?.takeIf { it.extension in setOf("md", "csv", "html", "txt", "kt", "py", "js") }?.readText()?.take(60_000)))

    data class Options(val language: String = "", val depth: Int = 2, val previous: String? = null)

    private fun update(j: Job) { _jobs.value = _jobs.value + (j.id to j) }

    private suspend fun classify(container: AppContainer, request: String): Kind {
        val r = request.lowercase()
        fun has(vararg w: String) = w.any { it in r }
        return when {
            has("thesis", "dissertation", "research paper", "literature review", "assignment of", "term paper", "مقالہ") -> Kind.THESIS
            has("slide", "presentation", "deck", "pitch") -> Kind.SLIDES
            has("spreadsheet", "excel", "csv", "table of", "budget", "timetable", "schedule sheet") -> Kind.SHEET
            has("picture", "image", "photo of", "draw", "logo", "poster", "wallpaper", "illustration", "art of", "تصویر") -> Kind.IMAGE
            has("app", "game", "calculator", "tool that", "website", "web page", "html") -> Kind.APP
            has("podcast", "narrat", "audio", "voice over", "read aloud") -> Kind.AUDIO
            has("code", "script", "function", "program", "python", "kotlin", "javascript") -> Kind.CODE
            else -> runCatching {
                val k = Ai.generate(container.settingsRepository, "Request: $request\n\nWhich ONE output format fits best? Answer with one word from: IMAGE, DOCUMENT, THESIS, SLIDES, SHEET, APP, AUDIO, CODE.", null, 10)
                Kind.entries.firstOrNull { it != Kind.AUTO && k.uppercase().contains(it.name) }
            }.getOrNull() ?: Kind.DOCUMENT
        }
    }

    // ------------------------------------------------------------------ the makers

    private class Maker(val container: AppContainer, val id: String, val request: String, val kind: Kind, val opt: Options) {
        val c: Context = container.appContext
        val settings = container.settingsRepository
        val dir = File(root(c), id).apply { mkdirs() }
        val team = mutableListOf<String>()
        val people: List<Pair<String, String>> = runCatching {
            com.nizam.app.feature.agent.Crew.agents(c).map { "${it.emoji} ${it.name}" to it.role.lowercase() }
        }.getOrDefault(emptyList())

        /** Credit the work to someone in your HQ whose role fits, so the office shows who made what. */
        fun who(role: String): String {
            val p = people.firstOrNull { (_, r) -> role.split('|').any { it in r } }?.first
                ?: people.getOrNull((role.hashCode() and 0x7fffffff) % maxOf(1, people.size))?.first ?: role.split('|').first().replaceFirstChar { it.uppercase() }
            if (p !in team) team += p
            return p
        }

        fun step(stage: String, f: Float, role: String) = update(Job(id, request, kind, stage, f, who(role)))

        val lang get() = opt.language.ifBlank { if (request.any { it in '؀'..'ۿ' }) "Urdu" else "the language of the request" }

        suspend fun ai(prompt: String, system: String, tokens: Int = 3000, web: Boolean = false) =
            Ai.generate(settings, prompt, system, tokens, web)

        suspend fun make(): Item = when (kind) {
            Kind.IMAGE -> image()
            Kind.THESIS -> thesis()
            Kind.SLIDES -> slides()
            Kind.SHEET -> sheet()
            Kind.APP -> app()
            Kind.AUDIO -> audio()
            Kind.CODE -> code()
            else -> document()
        }

        fun item(title: String, files: List<String>, summary: String) =
            Item(id, kind, title.take(90).ifBlank { request.take(60) }, request, files, System.currentTimeMillis(), summary, team.toList())

        // ---- image: an art director writes the prompt, then four takes are made and the best kept first
        suspend fun image(): Item {
            step("Art director is writing the brief", 0.1f, "design|creative|art")
            val brief = ai("Request: $request\n${opt.previous?.let { "Earlier prompt: $it" } ?: ""}\n\nWrite ONE detailed English image-generation prompt (subject, composition, style, lighting, colours, mood, camera). Return only the prompt.",
                "You are an expert art director.", 300).trim().trim('"')
            File(dir, "prompt.txt").writeText(brief)
            step("Designers are painting 4 takes", 0.3f, "design|creative")
            val files = coroutineScope {
                (1..4).map { n -> async { runCatching { ChatModes.imagine(c, brief).let { f -> File(dir, "take_$n.jpg").also { f.copyTo(it, true); f.delete() } } }.getOrNull() } }.awaitAll()
            }.filterNotNull()
            if (files.isEmpty()) throw IllegalStateException("The image service didn't answer. Try again in a minute.")
            step("Picking the best", 0.95f, "design")
            val title = ai("Give a 2-5 word title for this image: $brief", "Reply with the title only.", 20).trim().trim('"')
            return item(title, files.map { it.name } + "prompt.txt", brief)
        }

        // ---- document: outline, draft, edit, PDF
        suspend fun document(): Item {
            step("Planning the outline", 0.1f, "research|strategy|lead")
            val draft = ai(buildString {
                append("Write the complete deliverable for this request, ready to hand in or send.\nRequest: $request\n")
                opt.previous?.let { append("\nPrevious version to improve:\n$it\n") }
                append("\nUse Markdown: one # title, ## sections, lists and tables where useful. Write in $lang. Be thorough, specific and well organised. No preamble.")
            }, "You are a senior writer producing finished, professional documents.", 6000, web = true)
            step("Editor is checking and polishing", 0.65f, "edit|quality|review|writer")
            val polished = ai("Polish this document: fix errors, tighten weak sentences, keep structure and length, keep Markdown. Return only the document.\n\n$draft",
                "You are a meticulous editor.", 6000).ifBlank { draft }
            step("Typesetting the PDF", 0.9f, "design")
            val title = polished.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: request.take(60)
            File(dir, "document.md").writeText(polished)
            DocPdf.render(File(dir, "document.pdf"), title, polished, cover = false)
            return item(title, listOf("document.pdf", "document.md"), polished.lines().filter { it.isNotBlank() && !it.startsWith("#") }.take(2).joinToString(" ").take(240))
        }

        // ---- thesis: outline → chapters in parallel → abstract, intro, conclusion → references → PDF with contents
        suspend fun thesis(): Item {
            step("Research lead is designing the structure", 0.05f, "research|lead|strategy")
            val outlineRaw = ai(buildString {
                append("Design the structure of an academic thesis/research work for: $request\n")
                append("Return JSON only: {\"title\":\"...\",\"field\":\"...\",\"chapters\":[{\"title\":\"...\",\"sections\":[\"...\"],\"brief\":\"what this chapter argues\"}]}. ")
                append("Use ${if (opt.depth >= 3) "7 or 8" else "5 or 6"} chapters: Introduction, Literature Review, Methodology, findings/analysis chapters, Discussion, Conclusion.")
            }, "You are a thesis supervisor. Output strict JSON.", 1800)
            val outline = parseJson(outlineRaw)
            val title = outline.optString("title").ifBlank { request.take(80) }
            val chapters = outline.optJSONArray("chapters")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                .ifEmpty { listOf("Introduction", "Literature Review", "Methodology", "Analysis", "Discussion", "Conclusion").map { JSONObject().put("title", it) } }
            File(dir, "outline.json").writeText(outline.toString(2))

            val total = chapters.size
            var finished = 0
            val bodies = coroutineScope {
                chapters.mapIndexed { i, ch ->
                    async {
                        val writer = who(if (i % 2 == 0) "research|analyst" else "writer|content")
                        update(Job(id, request, kind, "Writing chapter ${i + 1}: ${ch.optString("title")}", 0.1f + 0.6f * finished / total, writer))
                        val text = ai(buildString {
                            append("Thesis title: $title\nChapter ${i + 1} of $total: ${ch.optString("title")}\n")
                            ch.optJSONArray("sections")?.let { append("Sections: ${(0 until it.length()).joinToString("; ") { k -> it.optString(k) }}\n") }
                            append("Purpose: ${ch.optString("brief")}\nWhole thesis outline: ${chapters.joinToString(" | ") { it.optString("title") }}\n\n")
                            append("Write this chapter in full academic prose in $lang (${if (opt.depth >= 3) "2000-2600" else "1200-1700"} words). Use ## for the chapter heading and ### for sections. ")
                            append("Cite sources in (Author, Year) form ONLY for real works you are confident exist; never invent a citation. ")
                            append("Where a claim needs a source you can't name, write [source needed]. At the very end add a line 'REFS:' then each real reference you cited, one per line, in APA style.")
                        }, "You are a rigorous academic writer. You never fabricate sources or data.", 5000, web = true)
                        finished++
                        update(Job(id, request, kind, "Chapter ${i + 1} done", 0.1f + 0.6f * finished / total, writer))
                        text
                    }
                }.awaitAll()
            }
            val refs = linkedSetOf<String>()
            val chaptersText = bodies.map { b ->
                val parts = b.split(Regex("\\n\\s*REFS:\\s*\\n?"), limit = 2)
                parts.getOrNull(1)?.lines()?.map { it.trim().trimStart('-', '*', ' ') }?.filter { it.length > 10 }?.forEach { refs += it }
                parts[0].trim()
            }
            step("Supervisor is writing the abstract", 0.78f, "lead|research")
            val abstract = ai("Thesis: $title\nChapter openings:\n" + chaptersText.joinToString("\n") { it.take(600) } +
                "\n\nWrite a 200-250 word abstract in $lang. Return only the abstract text.", "You are a thesis supervisor.", 700)
            step("Editor is checking consistency", 0.85f, "edit|quality|review")
            val md = buildString {
                append("# $title\n\n## Abstract\n\n$abstract\n\n")
                chaptersText.forEach { append(it).append("\n\n") }
                append("## References\n\n")
                if (refs.isEmpty()) append("_No verifiable references were produced. Add your sources here._\n")
                refs.sorted().forEach { append("- ").append(it).append('\n') }
                append("\n---\n_Drafted by your Atlas company. Check every citation and any [source needed] before submitting._\n")
            }
            File(dir, "thesis.md").writeText(md)
            step("Typesetting with cover and contents", 0.93f, "design")
            DocPdf.render(File(dir, "thesis.pdf"), title, md, cover = true, subtitle = outline.optString("field"), toc = true)
            return item(title, listOf("thesis.pdf", "thesis.md", "outline.json"), abstract.take(260))
        }

        // ---- slides: storyline → slides JSON → designed PDF deck (plus optional cover art)
        suspend fun slides(): Item {
            step("Strategist is building the storyline", 0.1f, "strategy|marketing|lead")
            val raw = ai(buildString {
                append("Create a presentation for: $request\n"); opt.previous?.let { append("Previous version:\n$it\n") }
                append("Return JSON only: {\"title\":\"...\",\"subtitle\":\"...\",\"theme\":\"#RRGGBB accent colour\",\"slides\":[{\"title\":\"...\",\"bullets\":[\"...\"],\"note\":\"speaker note\"}]}. ")
                append("10-14 slides, max 5 short bullets each, strong headlines, in $lang.")
            }, "You are a presentation designer. Output strict JSON.", 3500)
            val deck = parseJson(raw)
            step("Designer is making the cover art", 0.45f, "design|creative")
            val cover = runCatching { ChatModes.imagine(c, "Presentation cover background, abstract, elegant, no text: " + deck.optString("title", request)) }.getOrNull()
            step("Laying out the slides", 0.75f, "design")
            val pdf = File(dir, "slides.pdf")
            DeckPdf.render(pdf, deck, cover?.let { BitmapFactory.decodeFile(it.absolutePath) })
            cover?.delete()
            val notes = buildString {
                append("# ${deck.optString("title")}\n\n")
                deck.optJSONArray("slides")?.let { a -> for (i in 0 until a.length()) { val s = a.getJSONObject(i)
                    append("## ${i + 1}. ${s.optString("title")}\n"); s.optJSONArray("bullets").strings().forEach { append("- $it\n") }
                    if (s.optString("note").isNotBlank()) append("\n> ${s.optString("note")}\n"); append('\n') } }
            }
            File(dir, "slides.md").writeText(notes)
            return item(deck.optString("title", request), listOf("slides.pdf", "slides.md"), deck.optString("subtitle"))
        }

        // ---- spreadsheet: CSV, checked for consistent columns
        suspend fun sheet(): Item {
            step("Analyst is building the table", 0.2f, "finance|analyst|data|operations")
            val csv = ai("Create the spreadsheet for: $request\n${opt.previous?.let { "Previous version:\n$it\n" } ?: ""}\nReturn ONLY CSV (comma-separated, first row headers, quote fields containing commas). Include totals rows where they make sense. Realistic, complete data.",
                "You are a precise spreadsheet analyst.", 4000).trim().removePrefix("```csv").removePrefix("```").removeSuffix("```").trim()
            step("Checking every row", 0.8f, "quality|review|finance")
            val rows = Csv.parse(csv)
            if (rows.size < 2) throw IllegalStateException("The analyst returned an empty table. Try describing the columns you need.")
            File(dir, "sheet.csv").writeText(csv)
            val title = ai("Give a 2-6 word title for a spreadsheet made for: $request", "Reply with the title only.", 20).trim().trim('"')
            DocPdf.render(File(dir, "sheet.pdf"), title, Csv.toMarkdown(rows), cover = false)
            return item(title, listOf("sheet.csv", "sheet.pdf"), "${rows.size - 1} rows × ${rows.first().size} columns")
        }

        // ---- mini-app: one self-contained HTML file, reviewed for bugs
        suspend fun app(): Item {
            step("Engineer is building it", 0.2f, "engineer|developer|tech|product")
            val html = ai(buildString {
                append("Build a complete, working single-file web app for: $request\n"); opt.previous?.let { append("Previous version to improve:\n$it\n") }
                append("Rules: ONE html file with inline CSS and JS, no external requests or libraries, mobile-first touch UI, beautiful modern design, ")
                append("dark and light friendly, saves state in localStorage where useful. Return ONLY the HTML.")
            }, "You are a senior front-end engineer who ships polished, bug-free apps.", 8000)
            step("QA is testing for bugs", 0.7f, "quality|review|tester")
            val fixed = ai("Review this single-file app for bugs (JS errors, broken layout on a 400px phone, missing features from the request: $request). Return the corrected full HTML only.\n\n$html",
                "You are a QA engineer who fixes what they find.", 8000)
            val final = extractHtml(fixed).ifBlank { extractHtml(html) }
            if (!final.contains("<")) throw IllegalStateException("The engineer didn't return an app. Try again.")
            File(dir, "app.html").writeText(final)
            val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(final)?.groupValues?.get(1)?.trim() ?: request.take(40)
            return item(title, listOf("app.html"), "Opens inside Atlas. Works offline.")
        }

        // ---- audio: a script, then a narrated WAV
        suspend fun audio(): Item {
            step("Writer is scripting it", 0.15f, "writer|content|marketing")
            val script = ai("Write a spoken script for: $request\nIn $lang. Natural, engaging, for listening (no headings, no bullet symbols), about ${if (opt.depth >= 3) "1200" else "700"} words.",
                "You are a podcast scriptwriter.", 3000)
            File(dir, "script.md").writeText(script)
            step("Recording the narration", 0.6f, "voice|media|marketing")
            val wav = File(dir, "audio.wav")
            Narrator.record(c, script, wav, if (script.count { it in '؀'..'ۿ' } > script.length / 4) Locale("ur", "PK") else Locale.ENGLISH)
            val title = ai("Give a 2-6 word title for this audio: ${script.take(400)}", "Reply with the title only.", 20).trim().trim('"')
            return item(title, listOf("audio.wav", "script.md"), script.take(200))
        }

        // ---- code: file + explanation
        suspend fun code(): Item {
            step("Engineer is writing the code", 0.2f, "engineer|developer|tech")
            val out = ai("Write the code for: $request\n${opt.previous?.let { "Previous version:\n$it\n" } ?: ""}\nReturn: first line FILENAME: <name.ext>, then the complete code in one fenced block, then a short 'How to use' section.",
                "You are a senior software engineer. Code must be complete and runnable.", 7000)
            val name = Regex("FILENAME:\\s*([\\w.\\-]+)").find(out)?.groupValues?.get(1) ?: "main.txt"
            val body = Regex("```[\\w+-]*\\n([\\s\\S]*?)```").find(out)?.groupValues?.get(1) ?: out
            step("Reviewer is checking it", 0.8f, "quality|review")
            File(dir, name).writeText(body)
            File(dir, "README.md").writeText(out.substringAfterLast("```").trim().ifBlank { "Generated for: $request" })
            return item(name, listOf(name, "README.md"), out.substringAfterLast("```").trim().take(200))
        }
    }

    private fun parseJson(raw: String): JSONObject {
        val s = raw.substringAfter("```json", raw).substringAfter("```", raw.substringAfter("```json", raw))
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrElse { runCatching { JSONObject(s) }.getOrDefault(JSONObject()) }
    }

    private fun extractHtml(s: String): String {
        val fenced = Regex("```(?:html)?\\n([\\s\\S]*?)```").find(s)?.groupValues?.get(1)
        val t = (fenced ?: s).trim()
        val i = t.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 } ?: t.indexOf("<html", ignoreCase = true).takeIf { it >= 0 } ?: 0
        return t.substring(i)
    }

    /** A finished team goal also lands on the shelf as a document, so team work is never just a chat message. */
    fun fromTeamResult(c: Context, goal: String, text: String) {
        if (text.length < 200) return
        val id = "t" + System.currentTimeMillis()
        val dir = File(root(c), id).apply { mkdirs() }
        val title = goal.lineSequence().first().take(70)
        val md = if (text.trimStart().startsWith("#")) text else "# $title\n\n$text"
        File(dir, "result.md").writeText(md)
        runCatching { DocPdf.render(File(dir, "result.pdf"), title, md, cover = false) }
        save(c, Item(id, Kind.DOCUMENT, title, goal, listOfNotNull("result.pdf".takeIf { File(dir, it).exists() }, "result.md"), System.currentTimeMillis(), text.take(200), listOf("Your team")))
    }
}

// ---------------------------------------------------------------------- CSV

object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var q = false; var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                q && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> q = !q
                !q && ch == ',' -> { row += cell.toString().trim(); cell.clear() }
                !q && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += cell.toString().trim(); cell.clear(); if (row.any { it.isNotEmpty() }) rows += row; row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        row += cell.toString().trim(); if (row.any { it.isNotEmpty() }) rows += row
        val w = rows.maxOfOrNull { it.size } ?: 0
        return rows.map { r -> r + List(w - r.size) { "" } }
    }

    fun toMarkdown(rows: List<List<String>>): String = buildString {
        rows.forEachIndexed { i, r ->
            append("| ").append(r.joinToString(" | ") { it.replace("|", "/") }).append(" |\n")
            if (i == 0) append("|").append(r.joinToString("|") { "---" }).append("|\n")
        }
    }
}

// ---------------------------------------------------------------------- narration

object Narrator {
    suspend fun record(c: Context, text: String, out: File, locale: Locale) = withContext(Dispatchers.Main) {
        val tts = suspendCancellableCoroutine<TextToSpeech> { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(c.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) cont.resume(engine!!) else cont.resumeWith(Result.failure(IllegalStateException("Text-to-speech isn't available on this phone")))
            }
        }
        try {
            tts.language = locale
            val clean = text.replace(Regex("[#*_`>]"), "").take(TextToSpeech.getMaxSpeechInputLength() - 10)
            suspendCancellableCoroutine<Unit> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (cont.isActive) cont.resume(Unit) }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("Recording failed"))) }
                })
                tts.synthesizeToFile(clean, Bundle(), out, "rec")
            }
        } finally { tts.shutdown() }
        if (!out.exists() || out.length() < 1000) throw IllegalStateException("The narration couldn't be recorded")
    }
}

// ---------------------------------------------------------------------- typesetting

/** A4 documents from Markdown: optional cover and contents, real headings, lists, tables, page numbers, Urdu-safe. */
object DocPdf {
    private const val W = 595; private const val H = 842; private const val M = 56f

    fun render(out: File, title: String, md: String, cover: Boolean, subtitle: String = "", toc: Boolean = false) {
        val doc = PdfDocument()
        var n = 0
        var page: PdfDocument.Page? = null
        var y = M
        val headings = mutableListOf<Pair<String, Int>>()
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(30, 30, 34) }
        val h1 = TextPaint(body).apply { textSize = 20f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); color = Color.rgb(20, 40, 90) }
        val h2 = TextPaint(body).apply { textSize = 15f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.rgb(20, 40, 90) }
        val h3 = TextPaint(body).apply { textSize = 12.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val small = TextPaint(body).apply { textSize = 8.5f; color = Color.GRAY }
        val quote = TextPaint(body).apply { color = Color.rgb(70, 70, 80); typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC) }

        fun finish() { page?.let { p -> if (n > (if (cover) 1 else 0)) p.canvas.drawText("$n", W - M, H - 28f, small); doc.finishPage(p) }; page = null }
        fun newPage() { finish(); n++; page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()); y = M }

        fun block(text: String, paint: TextPaint, indent: Float = 0f, before: Float = 0f, after: Float = 6f) {
            if (page == null) newPage()
            y += before
            val rtl = text.count { it in '؀'..'ۿ' } > text.length / 4
            val lay = StaticLayout.Builder.obtain(text, 0, text.length, paint, (W - 2 * M - indent).toInt())
                .setAlignment(if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.12f).build()
            for (i in 0 until lay.lineCount) {
                val top = lay.getLineTop(i); val bottom = lay.getLineBottom(i)
                if (y + (bottom - top) > H - M - 10) newPage()
                val cv = page!!.canvas
                cv.save(); cv.translate(M + indent, y - top); cv.clipRect(0f, top.toFloat(), W.toFloat(), bottom.toFloat()); lay.draw(cv); cv.restore()
                y += bottom - top
            }
            y += after
        }

        fun inline(s: String) = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)\\*"), "$1").replace(Regex("`(.+?)`"), "$1")
            .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")

        if (cover) {
            newPage()
            val cv = page!!.canvas
            val bg = Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), Color.rgb(16, 32, 74), Color.rgb(58, 28, 98), Shader.TileMode.CLAMP) }
            cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
            val tp = TextPaint(h1).apply { color = Color.WHITE; textSize = 28f }
            val lay = StaticLayout.Builder.obtain(title, 0, title.length, tp, (W - 2 * M).toInt()).setLineSpacing(4f, 1.1f).build()
            cv.save(); cv.translate(M, H * 0.36f); lay.draw(cv); cv.restore()
            val sp = TextPaint(body).apply { color = Color.argb(210, 255, 255, 255); textSize = 13f }
            if (subtitle.isNotBlank()) cv.drawText(subtitle, M, H * 0.36f + lay.height + 30f, sp)
            cv.drawText(java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(java.util.Date()), M, H - 90f, sp)
            finish()
            if (toc) {
                // contents come from the headings, numbered by order; page numbers are approximate before layout, so list them plainly
                newPage()
                block("Contents", h1, after = 14f)
                md.lines().filter { it.startsWith("## ") }.forEachIndexed { i, h -> block("${i + 1}.  ${inline(h.removePrefix("## "))}", body, after = 4f) }
                finish()
            }
        }

        var inCode = false
        val table = mutableListOf<List<String>>()
        fun flushTable() {
            if (table.isEmpty()) return
            if (page == null) newPage()
            val cols = table.maxOf { it.size }
            val cw = (W - 2 * M) / cols
            val tp = TextPaint(body).apply { textSize = if (cols > 6) 8f else 9.5f }
            val hp = TextPaint(tp).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            table.forEachIndexed { r, row ->
                val lays = (0 until cols).map { k -> val t = inline(row.getOrElse(k) { "" }); StaticLayout.Builder.obtain(t, 0, t.length, if (r == 0) hp else tp, (cw - 8).toInt()).build() }
                val rh = (lays.maxOf { it.height } + 8).toFloat()
                if (y + rh > H - M) newPage()
                val cv = page!!.canvas
                if (r == 0) cv.drawRect(M, y, W - M, y + rh, Paint().apply { color = Color.rgb(232, 236, 248) })
                else if (r % 2 == 0) cv.drawRect(M, y, W - M, y + rh, Paint().apply { color = Color.rgb(247, 248, 252) })
                lays.forEachIndexed { k, l -> cv.save(); cv.translate(M + k * cw + 4, y + 4); l.draw(cv); cv.restore() }
                cv.drawLine(M, y + rh, W - M, y + rh, Paint().apply { color = Color.rgb(215, 218, 228) })
                y += rh
            }
            y += 10f
            table.clear()
        }

        for (raw in md.lines()) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) { inCode = !inCode; continue }
            if (inCode) { block(line.ifEmpty { " " }, TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = 9f }, indent = 10f, after = 0f); continue }
            if (line.trimStart().startsWith("|")) {
                val cells = line.trim().trim('|').split('|').map { it.trim() }
                if (cells.all { it.matches(Regex(":?-{2,}:?")) }) continue
                table += cells; continue
            } else flushTable()
            when {
                line.startsWith("# ") -> { if (cover) continue; block(inline(line.removePrefix("# ")), h1, after = 12f) }
                line.startsWith("## ") -> {
                    val h = inline(line.removePrefix("## "))
                    if (cover && y > M + 200) newPage()
                    headings += h to n; block(h, h2, before = 10f, after = 6f)
                }
                line.startsWith("### ") -> block(inline(line.removePrefix("### ")), h3, before = 6f, after = 4f)
                line.matches(Regex("^\\s*[-*•] .*")) -> block("•  " + inline(line.trimStart().drop(2)), body, indent = 12f, after = 3f)
                line.matches(Regex("^\\s*\\d+[.)] .*")) -> block(inline(line.trim()), body, indent = 12f, after = 3f)
                line.startsWith(">") -> block(inline(line.trimStart('>', ' ')), quote, indent = 16f)
                line.trim() == "---" -> { if (page == null) newPage(); page!!.canvas.drawLine(M, y + 4, W - M, y + 4, Paint().apply { color = Color.LTGRAY }); y += 12f }
                line.isBlank() -> y += 4f
                else -> block(inline(line), body)
            }
        }
        flushTable()
        finish()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}

/** 16:9 slide decks: a cover with art, then clean title-and-bullets slides in the deck's accent colour. */
object DeckPdf {
    private const val W = 960; private const val H = 540

    fun render(out: File, deck: JSONObject, cover: Bitmap?) {
        val doc = PdfDocument()
        val accent = runCatching { Color.parseColor(deck.optString("theme").trim().take(7)) }.getOrDefault(Color.rgb(64, 96, 220))
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.WHITE }
        var n = 0
        fun page(draw: (Canvas) -> Unit) { n++; val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()); draw(p.canvas); doc.finishPage(p) }
        fun text(cv: Canvas, s: String, paint: TextPaint, x: Float, y: Float, w: Int): Float {
            val l = StaticLayout.Builder.obtain(s, 0, s.length, paint, w).setLineSpacing(4f, 1.1f).build()
            cv.save(); cv.translate(x, y); l.draw(cv); cv.restore(); return l.height.toFloat()
        }

        page { cv ->
            if (cover != null) {
                val scale = maxOf(W / cover.width.toFloat(), H / cover.height.toFloat())
                val dw = cover.width * scale; val dh = cover.height * scale
                cv.drawBitmap(cover, null, RectF((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), 0f, Color.argb(235, 10, 12, 24), Color.argb(80, 10, 12, 24), Shader.TileMode.CLAMP) })
            } else cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), Color.rgb(14, 18, 36), accent, Shader.TileMode.CLAMP) })
            cv.drawRect(60f, 200f, 66f, 330f, Paint().apply { color = accent })
            val h = text(cv, deck.optString("title"), TextPaint(title).apply { textSize = 46f }, 86f, 196f, 600)
            text(cv, deck.optString("subtitle"), TextPaint(title).apply { textSize = 20f; typeface = Typeface.DEFAULT; color = Color.argb(220, 255, 255, 255) }, 88f, 210f + h, 600)
        }

        val slides = deck.optJSONArray("slides") ?: JSONArray()
        for (i in 0 until slides.length()) {
            val s = slides.getJSONObject(i)
            page { cv ->
                cv.drawColor(Color.rgb(250, 251, 254))
                cv.drawRect(0f, 0f, W.toFloat(), 8f, Paint().apply { color = accent })
                val head = TextPaint(title).apply { color = Color.rgb(18, 22, 40); textSize = 32f }
                val h = text(cv, s.optString("title"), head, 60f, 50f, W - 120)
                cv.drawRect(60f, 64f + h, 140f, 68f + h, Paint().apply { color = accent })
                val bp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 21f; color = Color.rgb(40, 44, 60) }
                var y = 96f + h
                val bullets = s.optJSONArray("bullets") ?: JSONArray()
                for (b in 0 until bullets.length()) {
                    cv.drawCircle(70f, y + 13f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
                    y += text(cv, bullets.optString(b), bp, 90f, y, W - 170) + 16f
                }
                val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.GRAY }
                cv.drawText("${i + 2}", W - 50f, H - 24f, small)
            }
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
