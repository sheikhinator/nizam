package com.nizam.app.feature.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * Grok-style chat modes: Think (reason it through before answering), DeepSearch (several web searches,
 * then one sourced report), Private (nothing remembered or learned), Imagine (make an image from words),
 * and personalities from plain to fun.
 */
object ChatModes {
    var think by mutableStateOf(false)
    var deep by mutableStateOf(false)
    var private by mutableStateOf(false)
    var imagine by mutableStateOf(false)
    var contemplate by mutableStateOf(false)
    /** Off by default: messages send straight away. On: show the context preview to check/redact first. */
    var review by mutableStateOf(false)
    var persona by mutableStateOf("Atlas")
    val PERSONAS = listOf("Atlas", "Fun", "Concise", "Coach", "Scholar", "Roast")

    fun personaPrompt(): String = when (persona) {
        "Fun" -> "\nPersonality: playful, witty and warm — jokes welcome, still accurate."
        "Concise" -> "\nPersonality: as brief as possible. Short sentences, no filler."
        "Coach" -> "\nPersonality: a motivating coach — direct, encouraging, always ends with a concrete next step."
        "Scholar" -> "\nPersonality: a careful scholar — nuanced, cites reasoning and sources, notes uncertainty."
        "Roast" -> "\nPersonality: cheeky and teasing (never cruel), roasts them lightly, then genuinely helps."
        else -> ""
    } + if (think) "\nThink it through carefully before answering: weigh the options, check your facts and arithmetic, " +
        "then give a short 'Reasoning' section followed by the answer." else ""

    /** DeepSearch: plans sub-questions, searches each on the web in parallel, and writes one report with sources. */
    suspend fun deepSearch(c: AppContainer, question: String, status: (String) -> Unit): String {
        status("Planning searches")
        val plan = Ai.generate(c.settingsRepository, question,
            "Break this into 3-5 focused web search questions that together answer it fully. One per line, no numbering.", 300)
            .lines().map { it.trim().trimStart('-', '•', '*').trim() }.filter { it.length > 5 }.take(5).ifEmpty { listOf(question) }
        status("Searching ${plan.size} angles")
        val findings = coroutineScope {
            plan.map { q -> async { runCatching { q to Ai.generate(c.settingsRepository, q,
                "Answer from current web results. Be factual and specific, include numbers and dates, and list source URLs at the end.",
                900, useWeb = true) }.getOrNull() } }.awaitAll().filterNotNull()
        }
        status("Writing the report")
        return Ai.generate(c.settingsRepository,
            "QUESTION: $question\n\nRESEARCH NOTES:\n" + findings.joinToString("\n\n") { (q, a) -> "## $q\n$a" },
            "Write a thorough, well-organised report answering the question from the research notes: a short summary first, " +
                "then sections with headings, then 'Sources' listing the URLs found. Say where sources disagree or are thin.", 2400)
    }

    /**
     * Contemplate (as in Meta's Muse Spark): several minds work the question in parallel — an analyst, a
     * sceptic, a creative and a practical one — then a final pass weighs them into one best answer.
     */
    suspend fun contemplate(c: AppContainer, question: String, status: (String) -> Unit): String {
        val minds = listOf(
            "the rigorous analyst: reason step by step, check every fact and number",
            "the sceptic: look for what could be wrong, missing or risky",
            "the creative: find unexpected angles and better options",
            "the practical one: what should they actually do, concretely, next")
        status("Contemplating with ${minds.size} minds")
        val views = coroutineScope {
            minds.map { m -> async { runCatching { m.substringBefore(':') to Ai.generate(c.settingsRepository, question,
                "You are $m. Answer the user's question from that stance in under 250 words.", 700) }.getOrNull() } }.awaitAll().filterNotNull()
        }
        status("Weighing their views")
        return Ai.generate(c.settingsRepository, "QUESTION: $question\n\n" + views.joinToString("\n\n") { (m, v) -> "[${m.removePrefix("the ")}]\n$v" },
            "Several independent thinkers answered. Weigh them: keep what's right, drop what's wrong, resolve disagreements, " +
                "and give the single best, well-reasoned answer. End with a one-line 'Confidence:' and why.", 2000)
    }

    /** Imagine: an image from a description (Pollinations, free, no key). Returns the saved file. */
    suspend fun imagine(c: android.content.Context, prompt: String): File = withContext(Dispatchers.IO) {
        val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?width=1024&height=1024&nologo=true&seed=" + (1..999999).random()
        val out = File(c.cacheDir, "imagine_${System.currentTimeMillis()}.jpg")
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 30_000; readTimeout = 90_000 }
            .inputStream.use { i -> out.outputStream().use { i.copyTo(it) } }
        if (out.length() < 2000) throw IllegalStateException("The image service didn't return a picture — try again")
        out
    }

    @Suppress("unused") private val keep = Http
}
