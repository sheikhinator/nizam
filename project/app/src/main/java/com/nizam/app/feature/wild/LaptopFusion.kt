package com.nizam.app.feature.wild

import kotlinx.coroutines.runBlocking

import android.content.Context
import com.nizam.app.NizamApplication
import com.nizam.app.net.Health
import com.nizam.app.net.Router
import com.nizam.app.net.Streaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** Stateless multi-model synthesis for the PIN-protected local gateway. */
internal object LaptopFusion {
    data class Result(val text: String, val metadata: JSONObject?)

    private data class PanelAnswer(val endpoint: Router.Endpoint, val text: String?)

    suspend fun run(
        context: Context,
        prompt: String,
        system: String,
        maxTokens: Int,
        options: JSONObject?,
        onDelta: ((String) -> Unit)? = null
    ): Result {
        val config = options ?: JSONObject()
        val requestedModels = readModels(config)
        val k = if (config.has("k")) {
            val raw = config.opt("k")
            require(raw is Number && raw.toDouble() % 1.0 == 0.0) { "fusion.k must be an integer between 1 and 8." }
            raw.toInt()
        } else requestedModels.size.takeIf { it > 0 } ?: 3
        require(k in 1..8) { "fusion.k must be an integer between 1 and 8." }

        val strategy = config.optString("strategy", "synthesize").ifBlank { "synthesize" }.lowercase()
        require(strategy == "synthesize" || strategy == "best_of") {
            "fusion.strategy must be synthesize or best_of."
        }

        val requestedJudge = config.optString("judge").trim().takeIf { it.isNotEmpty() && !it.equals("auto", true) }
        val exposePanel = config.optBoolean("expose_panel", false)
        val app = context.applicationContext as? NizamApplication
            ?: throw IllegalStateException("Atlas is not ready.")

        val ordered = Health.order(runBlocking {
            Router.endpoints(app.container.settingsRepository, context.applicationContext, Router.Lane.CHAT)
        }.filter {
            (it.kind.equals("OPENAI", true) || it.kind.equals("GEMINI", true)) &&
                it.url.isNotBlank() && it.model.isNotBlank()
        }.distinctBy { endpointKey(it) })
        if (ordered.isEmpty()) throw IllegalStateException("Fusion needs at least one configured remote chat model.")

        val selected = choosePanel(ordered, requestedModels, k)
        if (selected.isEmpty()) throw IllegalArgumentException("No requested fusion panel models are available in Atlas.")

        val memberSystem = listOf(
            system,
            "Answer as one independent model in a multi-model panel. Address the request directly. Do not mention the panel or other models."
        ).filter { it.isNotBlank() }.joinToString("\n\n")

        val panel = runBlocking {
            coroutineScope {
                selected.map { endpoint ->
                    async(Dispatchers.IO) {
                        val text = try {
                            Streaming.reply(
                                app.container.settingsRepository,
                                prompt,
                                memberSystem,
                                maxTokens,
                                context = context.applicationContext,
                                lane = Router.Lane.CHAT,
                                model = endpoint.model,
                                allowFolax = false,
                                endpointUrl = endpoint.url
                            ) { }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }?.takeIf { it.isNotBlank() }
                        PanelAnswer(endpoint, text)
                    }
                }.awaitAll()
            }
        }

        val successful = panel.filter { it.text != null }
        if (successful.isEmpty()) throw IllegalStateException("All fusion panel models failed.")

        val judge = if (strategy == "synthesize" && successful.size > 1) {
            val judgeEndpoint = chooseJudge(ordered, selected, requestedJudge)
            val judgePrompt = buildString {
                append("User request:\n").append(prompt)
                append("\n\nIndependent candidate responses:\n")
                successful.forEachIndexed { index, answer ->
                    append("\nCandidate ").append(index + 1)
                        .append(" (").append(answer.endpoint.model).append("):\n")
                    append(answer.text.orEmpty().take(MAX_PANEL_TEXT_CHARS)).append('\n')
                }
            }
            val judgeSystem = listOf(
                system,
                "Synthesize the candidate responses into one accurate, useful answer to the original request. Reconcile agreement, preserve important disagreements or uncertainty, and do not invent facts."
            ).filter { it.isNotBlank() }.joinToString("\n\n")
            var streamed = false
            val judgeText = try {
                Streaming.reply(
                    app.container.settingsRepository,
                    judgePrompt,
                    judgeSystem,
                    maxTokens,
                    context = context.applicationContext,
                    lane = Router.Lane.CHAT,
                    model = judgeEndpoint.model,
                    allowFolax = false,
                    endpointUrl = judgeEndpoint.url
                ) { delta ->
                    if (onDelta != null) {
                        streamed = true
                        onDelta(delta)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (streamed) throw e
                null
            }
            judgeText?.takeIf { it.isNotBlank() }?.let { judgeEndpoint to it }
        } else null

        val best = successful.maxByOrNull { it.text.orEmpty().length }!!
        val finalText = judge?.second ?: best.text.orEmpty().also { fallback ->
            if (onDelta != null) onDelta(fallback)
        }
        val metadata = if (exposePanel) {
            JSONObject()
                .put("strategy", strategy)
                .put("panel", JSONArray().apply {
                    panel.forEach { answer ->
                        put(JSONObject()
                            .put("model", answer.endpoint.model)
                            .put("status", if (answer.text == null) "failed" else "ok")
                            .apply { answer.text?.let { put("content", it) } })
                    }
                })
                .put("judge", judge?.first?.model ?: JSONObject.NULL)
                .put("judge_fallback", strategy == "synthesize" && successful.size > 1 && judge == null)
        } else null
        return Result(finalText, metadata)
    }

    private fun readModels(config: JSONObject): List<String> {
        if (!config.has("models") || config.isNull("models")) return emptyList()
        val raw = config.opt("models")
        require(raw is JSONArray) { "fusion.models must be an array of model names." }
        require(raw.length() <= 8) { "fusion.models may contain at most 8 entries." }
        return (0 until raw.length()).map { index ->
            val value = raw.opt(index)
            require(value is String && value.isNotBlank()) { "fusion.models entries must be non-empty strings." }
            value.trim()
        }.distinct()
    }

    private fun choosePanel(
        ordered: List<Router.Endpoint>,
        requestedModels: List<String>,
        k: Int
    ): List<Router.Endpoint> {
        if (requestedModels.isNotEmpty()) {
            val missing = requestedModels.filter { model -> ordered.none { it.model.equals(model, true) || it.name.equals(model, true) } }
            require(missing.isEmpty()) { "Fusion model(s) are not available in Atlas: ${missing.joinToString(", ")}." }
            val requested = requestedModels.flatMap { model ->
                ordered.filter { it.model.equals(model, true) || it.name.equals(model, true) }
            }
            return diversify(requested).take(k)
        }
        val diversified = diversify(ordered)
        return (diversified.take(k) + ordered.filter { candidate ->
            diversified.take(k).none { endpointKey(it) == endpointKey(candidate) }
        }.take((k - diversified.take(k).size).coerceAtLeast(0))).distinctBy(::endpointKey)
    }

    private fun diversify(endpoints: List<Router.Endpoint>): List<Router.Endpoint> {
        val seenHosts = mutableSetOf<String>()
        val distinctHosts = mutableListOf<Router.Endpoint>()
        val repeatedHosts = mutableListOf<Router.Endpoint>()
        endpoints.forEach { endpoint ->
            val host = endpoint.url.substringAfter("://").substringBefore('/').lowercase()
            if (seenHosts.add(host)) distinctHosts += endpoint else repeatedHosts += endpoint
        }
        return distinctHosts + repeatedHosts
    }

    private fun chooseJudge(
        ordered: List<Router.Endpoint>,
        panel: List<Router.Endpoint>,
        requestedJudge: String?
    ): Router.Endpoint {
        if (requestedJudge != null) {
            return ordered.firstOrNull {
                it.model.equals(requestedJudge, true) || it.name.equals(requestedJudge, true)
            } ?: throw IllegalArgumentException("Fusion judge model “$requestedJudge” is not available in Atlas.")
        }
        val panelKeys = panel.map(::endpointKey).toSet()
        return ordered.firstOrNull { endpointKey(it) !in panelKeys } ?: ordered.first()
    }

    private fun endpointKey(endpoint: Router.Endpoint): String =
        "${endpoint.url.lowercase()}\u0000${endpoint.model.lowercase()}"

    private const val MAX_PANEL_TEXT_CHARS = 12_000
}