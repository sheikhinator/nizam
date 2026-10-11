package com.nizam.app.feature.flows

import android.content.Context
import com.nizam.app.NizamApplication
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agent.Reviewer
import com.nizam.app.feature.connect.Connectors
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.nx.Noticer
import com.nizam.app.feature.nx.Roads
import com.nizam.app.net.Ai
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * Flows: Atlas's own automation engine. A flow is a trigger plus a list of steps. It runs on the phone,
 * talks to apps directly, and anything that acts on the outside world waits for the user's OK unless the
 * flow was explicitly allowed to act alone.
 */
object FlowEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun defs(c: Context) = JsonStore(c, "flows_def")
    private fun runs(c: Context) = JsonStore(c, "flows_runs")
    private fun pending(c: Context) = JsonStore(c, "flows_pending")
    private fun prefs(c: Context) = c.getSharedPreferences("flows", 0)

    fun paused(c: Context) = prefs(c).getBoolean("paused", false)
    fun setPaused(c: Context, v: Boolean) = prefs(c).edit().putBoolean("paused", v).apply()

    fun all(c: Context): List<JSONObject> = defs(c).all()
    fun get(c: Context, id: String) = all(c).firstOrNull { it.optString("id") == id }
    fun save(c: Context, flow: JSONObject) {
        val s = defs(c)
        if (flow.optString("id").isNotBlank() && get(c, flow.optString("id")) != null) s.update(flow.optString("id")) { e -> flow.keys().forEach { k -> e.put(k, flow.get(k)) } }
        else s.add(flow)
    }
    fun delete(c: Context, id: String) = defs(c).remove(id)
    fun history(c: Context, flowId: String?) = runs(c).all().filter { flowId == null || it.optString("flow") == flowId }.sortedByDescending { it.optLong("t") }

    fun newFlow(name: String = "New flow"): JSONObject = JSONObject().put("name", name).put("enabled", false).put("auto", false)
        .put("trigger", JSONObject().put("type", "manual")).put("steps", JSONArray())

    // ---------- templates ----------
    private val TEMPLATE = Regex("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*\\}\\}")
    fun fill(t: String, vars: Map<String, String>) = TEMPLATE.replace(t) { m -> vars[m.groupValues[1]].orEmpty() }

    // ---------- running ----------
    private fun needsOk(flow: JSONObject, s: JSONObject): Boolean {
        val type = s.optString("type")
        if (type == "ask") return true
        if (flow.optBoolean("auto")) return false
        return when (type) {
            "http", "connector" -> s.optString("method").ifBlank { "GET" }.uppercase() != "GET"
            "atlas", "relay_send" -> true
            else -> false
        }
    }

    private fun saveRun(c: Context, runId: String, flow: JSONObject, status: String, log: List<String>) {
        val s = runs(c)
        val o = JSONObject().put("id", runId).put("flow", flow.optString("id")).put("name", flow.optString("name")).put("t", System.currentTimeMillis())
            .put("status", status).put("log", JSONArray(log))
        if (s.all().any { it.optString("id") == runId }) s.update(runId) { e -> o.keys().forEach { k -> e.put(k, o.get(k)) } } else s.add(o)
        s.all().let { l -> if (l.size > 150) l.sortedBy { it.optLong("t") }.take(l.size - 150).forEach { s.remove(it.optString("id")) } }
    }

    /** Runs [flow]. Returns "ok", "waiting" (needs your OK) or "failed". */
    suspend fun run(app: Context, flow: JSONObject, trigger: Map<String, String> = emptyMap(), startIndex: Int = 0,
                    vars0: Map<String, String>? = null, runId0: String? = null, log0: List<String> = emptyList()): String {
        val container = (app.applicationContext as? NizamApplication)?.container
        val runId = runId0 ?: System.nanoTime().toString()
        val vars = HashMap<String, String>()
        trigger.forEach { (k, v) -> vars["trigger.$k"] = v }
        vars0?.let { vars.putAll(it) }
        val log = ArrayList(log0)
        val steps = flow.optJSONArray("steps") ?: JSONArray()
        var i = startIndex
        val resumed = runId0 != null && startIndex > 0
        while (i < steps.length()) {
            val s = steps.getJSONObject(i)
            val type = s.optString("type")
            if (resumed && i == startIndex && type == "ask") { log += "You approved: ${fill(s.optString("title"), vars)}"; i++; continue }
            if (needsOk(flow, s) && !(resumed && i == startIndex)) {
                val rid = runId
                pending(app).add(JSONObject().put("id", rid).put("flow", flow.optString("id")).put("index", i).put("vars", JSONObject(vars as Map<*, *>)).put("log", JSONArray(log)))
                val title = if (type == "ask") fill(s.optString("title"), vars) else FlowKinds.sentence(FlowKinds.step(type), s)
                ApprovalInbox.add(app, "Flow: ${flow.optString("name")}", "flow_continue", JSONObject().put("run", rid),
                    Reviewer.Verdict("medium", "ask", title.take(120)), fill(s.optString("body"), vars).ifBlank { title })
                log += "Waiting for your OK: $title"
                saveRun(app, runId, flow, "waiting", log)
                return "waiting"
            }
            try {
                if (type == "if") {
                    if (!check(s, vars)) { val skip = s.optString("skip").toIntOrNull() ?: 1; log += "Check failed, skipping $skip step(s)"; i += 1 + skip; continue }
                    log += "Check passed"
                } else {
                    val out = exec(app, container, flow, s, vars)
                    vars["last"] = out
                    val name = s.optString(if (type == "set") "var" else "out")
                    if (name.isNotBlank()) vars[name] = out
                    log += "${FlowKinds.step(type)?.label ?: type}: ${out.replace("\n", " ").take(100)}"
                }
            } catch (t: Throwable) {
                log += "Step ${i + 1} failed: ${t.message}"
                saveRun(app, runId, flow, "failed", log)
                runCatching { Notifications.post(app, Notifications.CHANNEL_NUDGE, 6200 + (flow.optString("name").hashCode() and 0xff), "Flow failed: ${flow.optString("name")}", t.message.orEmpty().take(120)) }
                return "failed"
            }
            i++
        }
        saveRun(app, runId, flow, "ok", log)
        return "ok"
    }

    /** Called when you approve a waiting step in the approvals inbox. */
    suspend fun continueRun(app: Context, runId: String): String {
        val p = pending(app).all().firstOrNull { it.optString("id") == runId } ?: return "That step was already handled."
        pending(app).remove(p.optString("id"))
        val flow = get(app, p.optString("flow")) ?: return "The flow no longer exists."
        val vo = p.optJSONObject("vars") ?: JSONObject()
        val vars = HashMap<String, String>().apply { vo.keys().forEach { k -> put(k, vo.optString(k)) } }
        val la = p.optJSONArray("log") ?: JSONArray()
        val log = (0 until la.length()).map { la.optString(it) }
        return run(app, flow, emptyMap(), p.optInt("index"), vars, runId, log)
    }

    private fun check(s: JSONObject, vars: Map<String, String>): Boolean {
        val left = fill(s.optString("left"), vars); val right = fill(s.optString("right"), vars)
        return when (s.optString("op").trim().lowercase()) {
            "equals", "=" -> left.equals(right, true)
            "has", "notempty" -> left.isNotBlank()
            "more", ">" -> (left.replace(",", "").toDoubleOrNull() ?: 0.0) > (right.replace(",", "").toDoubleOrNull() ?: 0.0)
            "less", "<" -> (left.replace(",", "").toDoubleOrNull() ?: 0.0) < (right.replace(",", "").toDoubleOrNull() ?: 0.0)
            else -> left.contains(right, true)
        }
    }

    private suspend fun exec(app: Context, container: com.nizam.app.AppContainer?, flow: JSONObject, s: JSONObject, vars: MutableMap<String, String>): String {
        fun p(k: String) = fill(s.optString(k), vars)
        return when (s.optString("type")) {
            "ai" -> {
                container ?: error("Atlas is still starting.")
                var prompt = p("prompt")
                if (s.optString("data").equals("yes", true)) { Roads.warm(container); prompt += "\n\nMy own recorded data:\n" + Roads.relevant(app, prompt, 3000) }
                Ai.generate(container.settingsRepository, prompt, system = "You run inside the user's automation. Answer with exactly what is asked, no preamble.", maxOutputTokens = 800)
            }
            "notify" -> { Notifications.post(app, Notifications.CHANNEL_NUDGE, 6100 + (p("title").hashCode() and 0xff), p("title").ifBlank { "Flow" }, p("body")); "sent" }
            "ask" -> "approved"
            "http" -> {
                val (code, body) = FlowNet.request(p("method"), p("url"), FlowNet.parseLines(p("headers"), ':'), p("body").ifBlank { null })
                if (code !in 200..299) error("The web address answered $code")
                body
            }
            "connector" -> {
                val want = p("service")
                val svc = Connectors.services(app).firstOrNull { it.id.equals(want, true) || it.name.equals(want, true) } ?: error("No connected app called \"$want\".")
                val (code, body) = Connectors.call(app, svc, p("method").ifBlank { "GET" }, p("path"), FlowNet.parseLines(p("query"), '='), p("body").ifBlank { null })
                if (code !in 200..299) error("${svc.name} answered $code")
                body.take(20_000)
            }
            "atlas" -> { com.nizam.app.feature.wild.CommandRunner.run(app, "Flow: ${flow.optString("name")}", p("text"), notify = true); "started" }
            "roads" -> { Feeds.add(app, p("source").ifBlank { "Flows" }, p("text")); "added" }
            "set" -> p("value")
            "log" -> p("text")
            "relay_send" -> Relay.send(app, p("topic"), p("title"), p("message"))
            else -> error("Unknown step type \"${s.optString("type")}\".")
        }
    }

    // ---------- triggers ----------
    private fun markRan(c: Context, id: String) { runCatching { defs(c).update(id) { it.put("last", System.currentTimeMillis()) } } }

    fun runNow(c: Context, id: String) {
        val app = c.applicationContext
        val f = get(app, id) ?: return
        scope.launch { run(app, f, mapOf("text" to "run by hand")); markRan(app, id) }
    }

    /** Phone events: notifications and texts. Filters in the flow decide whether it fires. */
    fun onEvent(c: Context, kind: String, data: Map<String, String>) {
        if (paused(c)) return
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        all(app).filter { it.optBoolean("enabled") && it.optJSONObject("trigger")?.optString("type") == kind }.forEach { f ->
            val t = f.optJSONObject("trigger") ?: return@forEach
            val ok = when (kind) {
                "notification" -> data["app"].orEmpty().contains(t.optString("app"), true) && "${data["title"]} ${data["text"]}".contains(t.optString("contains"), true)
                "sms" -> data["from"].orEmpty().contains(t.optString("from"), true) && data["text"].orEmpty().contains(t.optString("contains"), true)
                else -> false
            }
            if (!ok || now - f.optLong("last", 0) < 10_000) return@forEach
            markRan(app, f.optString("id"))
            scope.launch { run(app, f, data) }
        }
    }

    /** Called by the background worker (about every 15 minutes) and when the Flows screen opens. */
    fun tick(c: Context) { val app = c.applicationContext; scope.launch { runCatching { tickNow(app) } } }

    suspend fun tickNow(app: Context) {
        if (paused(app)) return
        val now = System.currentTimeMillis()
        val flows = all(app).filter { it.optBoolean("enabled") }
        for (f in flows) {
            val t = f.optJSONObject("trigger") ?: continue
            val last = f.optLong("last", 0)
            val due = when (t.optString("type")) {
                "schedule" -> if (t.optString("mode") == "daily") {
                    val at = runCatching { LocalTime.parse(t.optString("time").ifBlank { "08:00" }) }.getOrDefault(LocalTime.of(8, 0))
                    LocalTime.now() >= at && java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault()).toLocalDate() != LocalDate.now()
                } else now - last >= (t.optString("minutes").toLongOrNull() ?: 60L).coerceAtLeast(15L) * 60_000L
                "notice" -> now - last > 20 * 3_600_000L && runCatching { Noticer.scan(app).any { it.title.contains(t.optString("contains"), true) } }.getOrDefault(false)
                else -> false
            }
            if (due) { markRan(app, f.optString("id")); run(app, f, mapOf("text" to "scheduled")) }
        }
        // relay channels
        val topics = flows.mapNotNull { f -> f.optJSONObject("trigger")?.takeIf { it.optString("type") == "relay" }?.optString("topic")?.takeIf { it.isNotBlank() } }.distinct()
        for (topic in topics) {
            val msgs = runCatching { Relay.poll(app, topic) }.getOrDefault(emptyList())
            for (m in msgs) flows.filter { it.optJSONObject("trigger")?.optString("type") == "relay" && it.optJSONObject("trigger")?.optString("topic") == topic }.forEach { f ->
                markRan(app, f.optString("id"))
                run(app, f, mapOf("title" to m.title, "text" to m.text, "topic" to topic))
            }
        }
    }
}
