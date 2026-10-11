package com.nizam.app.feature.brain2

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.net.Ai
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() }

/**
 * Deep research: splits a question into sub-questions, searches the web for each at the same time,
 * then writes one report with its sources. Reports are kept so you can read them again.
 */
object Research {
    private data class Finding(val question: String, val answer: String, val returned: Boolean)

    fun store(c: Context) = JsonStore(c, "research_reports")

    suspend fun run(c: Context, container: AppContainer, question: String, progress: (String) -> Unit): JSONObject {
        val s = container.settingsRepository
        progress("Planning the questions")
        val plan = runCatching {
            JSONObject(Ai.cleanJson(Ai.generate(s,
                "Break this research question into 3 to 5 focused sub-questions that together answer it fully.\nQuestion: $question\n" +
                    "Reply JSON only: {\"sub\":[\"...\"]}", maxOutputTokens = 300))).optJSONArray("sub").strings()
        }.getOrDefault(emptyList()).ifEmpty { listOf(question) }.take(5)

        progress("Searching ${plan.size} angles at once")
        val findings = coroutineScope {
            plan.map { q ->
                async {
                    val result = runCatching {
                        Ai.generate(s, "Answer with facts, figures and dates. Name each source (site or publication) in brackets after the fact.\n$q",
                            system = "You are a careful researcher. Say plainly when sources disagree or when something is unknown.",
                            maxOutputTokens = 700, useWeb = true)
                    }
                    val answer = result.getOrNull()?.takeIf { it.isNotBlank() }
                    Finding(q, answer ?: "Couldn't search this one: ${result.exceptionOrNull()?.message ?: "No response returned."}", answer != null)
                }
            }.awaitAll()
        }

        progress("Writing the report")
        val notes = findings.joinToString("\n\n") { "## ${it.question}\n${it.answer}" }
        val report = Ai.generate(s,
            "Write a research report answering: $question\n\nNotes gathered:\n$notes\n\n" +
                "Format in Markdown: a 3-line **Bottom line** first, then sections with headings, then **Where sources disagree** (if any), " +
                "then **Sources** as a bullet list. Only use facts from the notes.",
            system = "You write clear, honest research briefs. No filler. Keep numbers exact.", maxOutputTokens = 1800)
        val record = JSONObject()
            .put("q", question)
            .put("report", report)
            .put("sub", JSONArray(plan))
            .put("findings", JSONArray().apply {
                findings.forEach { finding ->
                    put(JSONObject()
                        .put("question", finding.question)
                        .put("answer", finding.answer)
                        .put("returned", finding.returned))
                }
            })
            .put("t", System.currentTimeMillis())
        return store(c).add(record)
    }
}

/** Fact check: a claim or forwarded message against the web, with a verdict and how sure Atlas is. */
object FactCheck {
    data class Verdict(
        val verdict: String,
        val confidence: Int?,
        val summary: String,
        val detail: String,
        val sources: List<String>,
        val confidenceReported: Boolean = confidence != null
    )
    fun store(c: Context) = JsonStore(c, "fact_checks")

    suspend fun check(c: Context, container: AppContainer, claim: String): Verdict {
        val raw = Ai.generate(container.settingsRepository,
            "Fact-check this claim or message using current web sources:\n\"\"\"${Injection.guard(claim)}\"\"\"\n" +
                "Reply JSON only: {\"verdict\":\"TRUE|FALSE|MISLEADING|UNPROVEN\",\"confidence\":0-100,\"summary\":\"one sentence\"," +
                "\"detail\":\"what is right, what is wrong, and the real facts\",\"sources\":[\"publication — what it says\"]}",
            system = "You are a neutral fact-checker. Prefer primary and reputable sources. If evidence is thin, say UNPROVEN.",
            maxOutputTokens = 700, useWeb = true)
        val j = runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrElse { JSONObject().put("verdict", "UNPROVEN").put("summary", raw.take(300)) }
        val confidence = j.optInt("confidence", -1).takeIf { it in 0..100 }
        val v = Verdict(
            j.optString("verdict", "UNPROVEN").uppercase(),
            confidence,
            j.optString("summary"),
            j.optString("detail"),
            j.optJSONArray("sources").strings(),
            confidenceReported = confidence != null
        )
        store(c).add(JSONObject().put("claim", claim.take(400)).put("verdict", v.verdict).put("confidence", v.confidence ?: -1)
            .put("confidenceReported", v.confidenceReported).put("summary", v.summary).put("detail", v.detail)
            .put("sources", JSONArray(v.sources)).put("t", System.currentTimeMillis()))
        return v
    }
}

/**
 * The calculator that can't hallucinate: expressions are worked out on the phone by a real parser.
 * Lines can set variables ("rent = 45000") and later lines use them. For word problems, the AI only
 * writes the expression; the phone does the arithmetic.
 */
object Calc {
    class Err(msg: String) : Exception(msg)

    private val FN: Map<String, (List<Double>) -> Double> = mapOf(
        "sqrt" to { a -> sqrt(a[0]) }, "abs" to { a -> abs(a[0]) }, "round" to { a -> if (a.size > 1) { val p = 10.0.pow(a[1]); Math.round(a[0] * p) / p } else Math.round(a[0]).toDouble() },
        "floor" to { a -> floor(a[0]) }, "ceil" to { a -> ceil(a[0]) }, "ln" to { a -> ln(a[0]) }, "log" to { a -> log10(a[0]) },
        "sin" to { a -> sin(Math.toRadians(a[0])) }, "cos" to { a -> cos(Math.toRadians(a[0])) }, "tan" to { a -> tan(Math.toRadians(a[0])) },
        "min" to { a -> a.min() }, "max" to { a -> a.max() }, "avg" to { a -> a.average() }, "sum" to { a -> a.sum() },
        "pow" to { a -> a[0].pow(a[1]) },
        // if(cond, a, b): cond above zero picks a
        "if" to { a -> if (a[0] > 0) a[1] else a[2] }, "gt" to { a -> if (a[0] > a[1]) 1.0 else 0.0 }, "lt" to { a -> if (a[0] < a[1]) 1.0 else 0.0 },
        "clamp" to { a -> a[0].coerceIn(a[1], a[2]) },
        // progressive bands: band(x, limit1, rate1%, limit2, rate2%, ..., topRate%)
        "band" to { a ->
            val x = a[0]; var tax = 0.0; var lo = 0.0; var i = 1
            while (i + 1 < a.size) { val hi = a[i]; val r = a[i + 1]; if (x > lo) tax += (minOf(x, hi) - lo) * r / 100; lo = hi; i += 2 }
            if (i < a.size && x > lo) tax += (x - lo) * a[i] / 100
            tax
        },
        // loan instalment: principal, yearly rate %, months
        "emi" to { a -> val r = a[1] / 1200; if (r == 0.0) a[0] / a[2] else a[0] * r * (1 + r).pow(a[2]) / ((1 + r).pow(a[2]) - 1) },
        // compound growth: amount, yearly rate %, years
        "grow" to { a -> a[0] * (1 + a[1] / 100).pow(a[2]) },
    )

    private class P(val s: String, val vars: Map<String, Double>) {
        var i = 0
        fun ws() { while (i < s.length && s[i] == ' ') i++ }
        fun eat(ch: Char): Boolean { ws(); return if (i < s.length && s[i] == ch) { i++; true } else false }
        fun expr(): Double { var v = term(); while (true) { v = when { eat('+') -> v + term(); eat('-') -> v - term(); else -> return v } } }
        fun term(): Double {
            var v = unary()
            while (true) {
                v = when {
                    eat('*') || eat('×') -> v * unary()
                    eat('/') || eat('÷') -> { val d = unary(); if (d == 0.0) throw Err("Division by zero"); v / d }
                    else -> return v
                }
            }
        }
        fun unary(): Double = if (eat('-')) -unary() else if (eat('+')) unary() else power()
        fun power(): Double { val b = postfix(); return if (eat('^')) b.pow(unary()) else b }
        fun postfix(): Double {
            var v = atom()
            while (true) {
                if (eat('%')) v /= 100.0
                else if (eat('!')) { if (v < 0 || v > 170 || v != floor(v)) throw Err("Factorial needs a whole number 0–170"); v = (1..v.toInt()).fold(1.0) { a, n -> a * n } }
                else return v
            }
        }
        fun atom(): Double {
            ws()
            if (eat('(')) { val v = expr(); if (!eat(')')) throw Err("Missing )"); return v }
            val st = i
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == '_' ||
                    (s[i] == ',' && i + 3 < s.length + 0 && s[i + 1].isDigit() && s[i + 2].isDigit() && s[i + 3].isDigit() && !(i + 4 < s.length && s[i + 4].isDigit())))) i++
                var n = s.substring(st, i).replace(",", "").replace("_", "").toDoubleOrNull() ?: throw Err("Bad number")
                ws()
                // Pakistani and short scales: 5k, 2.5lakh, 3crore, 1m
                val rest = s.substring(i).lowercase()
                listOf("crore" to 1e7, "lakh" to 1e5, "lac" to 1e5, "k" to 1e3, "m" to 1e6, "bn" to 1e9).firstOrNull { (w, _) ->
                    rest.startsWith(w) && (rest.length == w.length || !rest[w.length].isLetter())
                }?.let { (w, m) -> n *= m; i += w.length }
                return n
            }
            if (i < s.length && (s[i].isLetter() || s[i] == '_')) {
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val name = s.substring(st, i).lowercase()
                if (eat('(')) {
                    val args = mutableListOf<Double>()
                    if (!eat(')')) { do { args += expr() } while (eat(',')); if (!eat(')')) throw Err("Missing ) after $name") }
                    val f = FN[name] ?: throw Err("Unknown function $name")
                    return runCatching { f(args) }.getOrElse { throw Err("$name needs more numbers") }
                }
                return when (name) { "pi" -> PI; "e" -> E; else -> vars[name] ?: throw Err("Unknown name '$name'") }
            }
            throw Err(if (i >= s.length) "Expression ends too soon" else "Unexpected '${s[i]}'")
        }
    }

    fun eval(expr: String, vars: Map<String, Double> = emptyMap()): Double {
        val p = P(expr.trim(), vars)
        val v = p.expr(); p.ws()
        if (p.i < p.s.length) throw Err("Unexpected '${p.s[p.i]}'")
        if (v.isNaN() || v.isInfinite()) throw Err("Not a real number")
        return v
    }

    data class Line(val src: String, val result: String?, val error: Boolean)

    /** Works through a sheet line by line; "name = expr" assigns, "# ..." is a comment. */
    fun sheet(text: String): List<Line> {
        val vars = HashMap<String, Double>()
        var last: Double? = null
        return text.lines().map { raw ->
            val line = raw.substringBefore("#").trim()
            if (line.isEmpty()) return@map Line(raw, null, false)
            try {
                val m = Regex("^([A-Za-z_][A-Za-z0-9_ ]*?)\\s*=\\s*(.+)$").find(line)
                val v = if (m != null) {
                    val name = m.groupValues[1].trim().replace(' ', '_').lowercase()
                    eval(m.groupValues[2], vars + ("ans" to (last ?: 0.0))).also { vars[name] = it }
                } else eval(line, vars + ("ans" to (last ?: 0.0)))
                last = v; Line(raw, fmt(v), false)
            } catch (e: Exception) { Line(raw, e.message ?: "Error", true) }
        }
    }

    fun fmt(v: Double): String =
        if (abs(v) >= 1e15 || (abs(v) < 1e-6 && v != 0.0)) "%.6g".format(v)
        else java.text.DecimalFormat("#,##0.########").format(v)

    /** Word problem → the AI writes a sheet, the phone computes it. */
    suspend fun fromWords(container: AppContainer, question: String): String {
        val raw = Ai.generate(container.settingsRepository,
            "Turn this into calculator lines. One step per line, 'name = expression' to name values, final line is the answer expression.\n" +
                "Allowed: + - * / ^ % ( ) numbers, k/lakh/crore suffixes, functions sqrt abs round floor ceil ln log sin cos tan min max avg sum pow " +
                "emi(principal, yearly_rate_percent, months) grow(amount, yearly_rate_percent, years). Use # for short comments.\n" +
                "Reply with the lines only, no code fences.\nProblem: $question",
            system = "You translate word problems into precise arithmetic. Never compute the answer yourself.", maxOutputTokens = 400)
        return raw.replace("```", "").lines().filter { it.isNotBlank() }.joinToString("\n").trim()
    }
}
