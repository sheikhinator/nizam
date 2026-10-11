package com.nizam.app.feature.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.phone.AtlasAccessibilityService
import com.nizam.app.net.Ai
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Skills: a process that worked once, saved so the next run doesn't start from zero. A skill has inputs
 * ({{name}} placeholders), steps (Atlas actions, taps and typing in other apps, or a goal handed to the agent),
 * rules it follows, and approval boundaries — steps that always wait for your OK. Skills come from four places:
 * a demonstration you record, a run the agent just did, a description you write, or a repair of a failed run.
 * When a recorded tap can't find its button (an app update moved it), the step heals itself by handing that
 * one step to the in-app agent, and the skill remembers how it recovered.
 */
object SkillsPro {
    fun store(c: Context) = JsonStore(c, "skills")
    var version by mutableIntStateOf(0)

    fun all(c: Context): List<JSONObject> = store(c).all().sortedByDescending { it.optLong("lastAt") }
    fun find(c: Context, name: String): JSONObject? = store(c).all().let { l ->
        l.firstOrNull { it.optString("name").equals(name.trim(), true) } ?: l.firstOrNull { it.optString("name").contains(name.trim(), true) }
            ?: l.firstOrNull { s -> s.optString("when").split(',').any { w -> w.isNotBlank() && name.contains(w.trim(), true) } }
    }
    fun save(c: Context, o: JSONObject): JSONObject {
        if (!o.has("id")) o.put("id", System.currentTimeMillis().toString())
        val s = store(c); val old = s.all().firstOrNull { it.optString("id") == o.optString("id") }
        if (old != null) {
            // keep the last five versions so a bad edit can be undone
            val hist = (old.optJSONArray("versions") ?: JSONArray()).let { h -> (0 until h.length()).map { h.getJSONObject(it) } }
            o.put("versions", JSONArray((listOf(JSONObject().put("at", System.currentTimeMillis()).put("steps", old.optJSONArray("steps") ?: JSONArray())
                .put("rules", old.optString("rules"))) + hist).take(5)))
            s.save(s.all().map { if (it.optString("id") == o.optString("id")) o else it })
        } else s.add(o)
        version++; return o
    }
    fun delete(c: Context, id: String) { store(c).remove(id); version++ }

    fun params(skill: JSONObject): List<JSONObject> = skill.optJSONArray("params")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    fun steps(skill: JSONObject): List<JSONObject> = skill.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    fun describe(s: JSONObject): String = when (s.optString("k", if (s.has("a")) "action" else "ui")) {
        "action" -> s.optString("a").replace('_', ' ') + (s.optJSONObject("args")?.let { a -> a.keys().asSequence().joinToString(", ", " · ") { "$it: ${a.optString(it).take(30)}" } } ?: "")
        "agent" -> "Ask the agent: ${s.optString("goal")}"
        "ask" -> "Ask you: ${s.optString("question")}"
        else -> when (s.optString("op")) {
            "open" -> "Open ${s.optString("app")}"
            "tap" -> "Tap “${s.optString("text").ifBlank { s.optString("desc").ifBlank { s.optString("id").substringAfterLast('/') } }}”"
            "type" -> "Type “${s.optString("value")}”"
            "scroll" -> "Scroll ${s.optString("dir", "down")}"
            "back" -> "Go back"; "home" -> "Go home"
            "wait" -> "Wait ${s.optLong("ms", 1000) / 1000.0}s"
            else -> s.optString("op")
        }
    } + if (s.optBoolean("approve")) "  ·  needs your OK" else ""

    fun promptBlock(c: Context): String {
        val l = all(c).take(25); if (l.isEmpty()) return ""
        return "\n\nSaved skills (run_skill {name, params:{…}}):\n" + l.joinToString("\n") { s ->
            "- ${s.optString("name")}: ${s.optString("desc").take(90)}" + params(s).takeIf { it.isNotEmpty() }?.let { p -> " (inputs: ${p.joinToString { it.optString("key") }})" }.orEmpty()
        }
    }

    private fun fill(text: String, values: Map<String, String>) = Regex("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}").replace(text) { m -> values[m.groupValues[1]] ?: m.value }
    private fun fillJson(o: JSONObject, values: Map<String, String>): JSONObject = JSONObject(fill(o.toString(), values.mapValues { JSONObject.quote(it.value).trim('"') }))

    data class Run(val ok: Boolean, val log: List<String>, val stoppedAt: Int = -1)

    /**
     * Runs a skill. [confirm] is asked before any step marked for approval (and before sends/pays found by the reviewer);
     * return false to stop there. [progress] reports each step as it happens.
     */
    suspend fun run(container: AppContainer, skill: JSONObject, values: Map<String, String>, from: Int = 0,
                    confirm: suspend (String) -> Boolean, progress: (Int, String) -> Unit = { _, _ -> },
                    backgroundApprovalPolicy: String? = null,
                    confirmAction: suspend (String, JSONObject) -> Boolean = { _, _ -> false }): Run {
        val c = container.appContext
        val all = steps(skill); val log = mutableListOf<String>()
        val rules = skill.optString("rules")
        for (i in from until all.size) {
            val step = fillJson(all[i], values)
            val label = describe(step)
            progress(i, label)
            val kind = step.optString("k", if (step.has("a")) "action" else "ui")
            val actionName = if (kind == "action") step.optString("a") else ""
            val actionArgs = step.optJSONObject("args") ?: JSONObject()
            val backgroundAction = backgroundApprovalPolicy != null && kind == "action" &&
                !Reviewer.isReadOnly(actionName)
            val backgroundAgent = backgroundApprovalPolicy != null && kind == "agent"
            if (step.optBoolean("approve") && !backgroundAction && !backgroundAgent && !confirm(label)) {
                log += "⏸ Stopped before: $label"
                return finish(c, skill, false, log, i)
            }
            if (backgroundAction && !confirmAction(actionName, actionArgs)) {
                log += "⏸ Stopped before: $label"
                return finish(c, skill, false, log, i)
            }
            val r: Pair<Boolean, String> = when (kind) {
                "action" -> Actions.run(container, actionName, actionArgs).let { it.ok to it.summary }
                "agent" -> {
                    val outcome = container.agentLoop.run(
                        step.optString("goal") + if (rules.isNotBlank()) "\nRules: $rules" else "",
                        useWeb = true,
                        autoApprove = false
                    )
                    val waiting = mutableListOf<String>()
                    val completed = mutableListOf<String>()
                    outcome.pendingApproval.forEach { (name, args) ->
                        val allowed = if (backgroundApprovalPolicy != null) {
                            confirmAction(name, args)
                        } else {
                            confirm("${name.replace('_', ' ')} · ${Secrets.redact(args.toString()).take(180)} " +
                                "(from: ${step.optString("goal").take(60)})")
                        }
                        if (!allowed) {
                            waiting += name.replace('_', ' ')
                        } else {
                            val result = Actions.run(container, name, args)
                            completed += result.summary
                            if (!result.ok) waiting += "$name failed"
                        }
                    }
                    if (waiting.isNotEmpty()) {
                        false to "Stopped before unapproved actions: ${waiting.distinct().joinToString()}"
                    } else {
                        true to (outcome.reply + if (completed.isEmpty()) "" else "\n" + completed.joinToString(" · ")).take(300)
                    }
                }
                "ask" -> true to "Asked: ${step.optString("question")}"
                else -> ui(container, step)
            }
            log += (if (r.first) "✓ " else "✕ ") + r.second
            if (!r.first) return finish(c, skill, false, log, i)
        }
        return finish(c, skill, true, log, -1)
    }

    private fun finish(c: Context, skill: JSONObject, ok: Boolean, log: List<String>, at: Int): Run {
        store(c).update(skill.optString("id")) {
            it.put("runs", it.optInt("runs") + 1).put(if (ok) "okRuns" else "failRuns", it.optInt(if (ok) "okRuns" else "failRuns") + 1)
                .put("last", LocalDate.now().toString()).put("lastAt", System.currentTimeMillis()).put("lastLog", JSONArray(log.takeLast(20)))
        }
        version++
        return Run(ok, log, at)
    }

    /** One step in another app. If the recorded target has moved, hand just this step to the in-app agent. */
    private suspend fun ui(container: AppContainer, s: JSONObject): Pair<Boolean, String> {
        val svc = AtlasAccessibilityService.instance ?: return false to (AtlasAccessibilityService.ready() ?: "Screen control is off")
        val c = container.appContext
        val out: Pair<Boolean, String> = when (s.optString("op")) {
            "open" -> com.nizam.app.feature.phone.PhoneActions.openApp(c, s.optString("app").ifBlank { s.optString("pkg") }).let { o -> delay(1600); o.ok to o.message }
            "tap" -> {
                var res: String? = null
                repeat(4) { if (res == null) { res = svc.tapTarget(s.optString("id"), s.optString("text"), s.optString("desc")); if (res == null) delay(700) } }
                if (res != null) { delay(900); true to res!! } else {
                    // self-heal: the button moved or was renamed
                    val goal = "In the app that's open, ${describe(s).lowercase()} (it may be labelled differently now)."
                    val h = Actions.run(container, "do_in_app", JSONObject().put("goal", goal))
                    h.ok to (if (h.ok) "Healed: ${h.summary}" else "Couldn't find ${describe(s)}")
                }
            }
            "type" -> svc.typeInto(s.optString("id"), s.optString("value")).let { delay(400); (!it.startsWith("No ") && !it.contains("wouldn't") && !it.contains("password")) to it }
            "scroll" -> svc.scroll(s.optString("dir", "down") != "up").let { delay(600); true to it }
            "back" -> svc.global("back").let { delay(600); true to it }
            "home" -> svc.global("home").let { delay(600); true to it }
            "wait" -> { delay(s.optLong("ms", 1000).coerceIn(100, 15_000)); true to "Waited" }
            else -> false to "Unknown step ${s.optString("op")}"
        }
        return out
    }

    /** Runs without a person watching: steps marked for approval park the run in the approval inbox, to resume from there. */
    suspend fun runBackground(container: AppContainer, skill: JSONObject, values: Map<String, String>, from: Int, source: String, approvalPolicy: String): Run {
        var resumed = from > 0
        var actionApprovalHandled = false
        val r = run(
            container,
            skill,
            values,
            from,
            confirm = { _ -> if (resumed) { resumed = false; true } else false },
            backgroundApprovalPolicy = approvalPolicy,
            confirmAction = { action, args ->
                val verdict = Reviewer.review(
                    container,
                    action,
                    args,
                    "$source · ${skill.optString("name")}: ${action.replace('_', ' ')}"
                )
                if (Reviewer.mayRunAlone(approvalPolicy, verdict)) {
                    true
                } else {
                    actionApprovalHandled = true
                    if (verdict.decision != "block") {
                        ApprovalInbox.add(
                            container.appContext,
                            source,
                            action,
                            args,
                            verdict,
                            "Skill ${skill.optString("name")}: ${action.replace('_', ' ')}"
                        )
                    }
                    false
                }
            }
        )
        if (!actionApprovalHandled && !r.ok && r.stoppedAt >= 0 && r.log.lastOrNull()?.startsWith("⏸") == true) {
            val step = steps(skill).getOrNull(r.stoppedAt)?.let { describe(it) } ?: "next step"
            ApprovalInbox.add(container.appContext, source, "run_skill_from",
                JSONObject().put("name", skill.optString("name")).put("from", r.stoppedAt).put("params", JSONObject(values as Map<*, *>)),
                Reviewer.Verdict("medium", "ask", "“${skill.optString("name")}” wants to: $step"), "Continue skill ${skill.optString("name")}")
        }
        return r
    }

    // ───────────── making skills ─────────────
    private val SHAPE = "Return ONLY JSON {\"name\":\"short name\",\"desc\":\"one line\",\"when\":\"comma-separated phrases that should trigger it\"," +
        "\"params\":[{\"key\":\"snake_case\",\"label\":\"question to ask\",\"example\":\"\"}],\"rules\":\"decision rules and output requirements, short\"," +
        "\"steps\":[ {\"k\":\"action\",\"a\":\"action_name\",\"args\":{}} | {\"k\":\"ui\",\"op\":\"open|tap|type|scroll|back|home|wait\",\"app\":\"\",\"text\":\"\",\"desc\":\"\",\"id\":\"\",\"value\":\"\",\"dir\":\"down\",\"ms\":1000} " +
        "| {\"k\":\"agent\",\"goal\":\"\"} ], each step may add \"approve\":true}. Use {{key}} for anything that changes between runs (a name, a message, an amount). " +
        "Mark approve:true on any step that sends, posts, pays, orders, deletes or books."

    /** Turns a raw recording into a clean, reusable skill with inputs and approval boundaries. */
    suspend fun fromDemo(container: AppContainer, raw: List<JSONObject>, hint: String): JSONObject {
        val text = Ai.generate(container.settingsRepository, "What the user did, step by step:\n" + JSONArray(raw).toString().take(9000) +
            "\n\nWhat it was for: ${hint.ifBlank { "(not said)" }}",
            "You turn a recorded phone demonstration into a reusable automation. Drop accidental or duplicate steps, keep the ids and labels " +
                "from the recording on taps, turn typed text that will change into {{inputs}}. $SHAPE", 2500)
        return JSONObject(Ai.cleanJson(text)).put("source", "demo").put("created", System.currentTimeMillis())
    }

    /** Writes a skill from a plain description, using Atlas's actions where possible. */
    suspend fun write(container: AppContainer, description: String): JSONObject {
        val text = Ai.generate(container.settingsRepository, description,
            "You write automations for the Atlas assistant. Prefer Atlas actions (below) over tapping in apps; use {\"k\":\"agent\"} for fuzzy parts.\n" +
                "Atlas actions:\n${Actions.CATALOGUE.take(7000)}\n\n$SHAPE", 2500)
        return JSONObject(Ai.cleanJson(text)).put("source", "written").put("created", System.currentTimeMillis())
    }

    /** Saves what the agent just did (its recent actions since [since]) as a skill. */
    suspend fun fromRecent(container: AppContainer, since: Long, what: String): JSONObject? {
        val calls = com.nizam.app.feature.brain2.Skills.recent(container.appContext).filter { it.optLong("t") >= since }.reversed()
        if (calls.isEmpty()) return null
        val raw = calls.map { JSONObject().put("k", "action").put("a", it.optString("a")).put("args", it.optJSONObject("args") ?: JSONObject()) }
        val text = Ai.generate(container.settingsRepository, "Request: $what\nActions that worked:\n${JSONArray(raw)}",
            "Generalise this successful run into a reusable skill. Keep the same actions; turn specifics into {{inputs}}. $SHAPE", 2000)
        return JSONObject(Ai.cleanJson(text)).put("source", "agent").put("created", System.currentTimeMillis())
    }

    /** Fixes a skill from its last failure. */
    suspend fun repair(container: AppContainer, skill: JSONObject): JSONObject {
        val text = Ai.generate(container.settingsRepository, "Skill: ${skill}\n\nLast run log: ${skill.optJSONArray("lastLog")}",
            "This automation failed. Fix the steps (reorder, add waits, replace brittle taps with agent steps, correct actions) and keep the same name. $SHAPE", 2500)
        return JSONObject(Ai.cleanJson(text)).put("id", skill.optString("id")).put("source", skill.optString("source")).put("created", skill.optLong("created"))
    }
}

/**
 * Teach by showing: while recording, every tap, text entry, scroll and app switch you make is noted — with the
 * button's id and label so replay finds it again. Passwords are never recorded, and banking and payment apps
 * are skipped entirely. A small red pill floats on screen; tap it to stop.
 */
object DemoRecorder {
    var recording by mutableStateOf(false)
    var count by mutableIntStateOf(0)
    private val steps = mutableListOf<JSONObject>()
    private var pill: TextView? = null
    private var lastPkg = ""
    private var oldTypes = 0
    private val skip = listOf("bank", "wallet", "pay", "jazzcash", "easypaisa", "sadapay", "nayapay", "meezan", "hbl", "ubl", "mcb", "alfalah",
        "binance", "authenticator", "password", "bitwarden", "1password", "lastpass")

    fun start(c: Context): String? {
        val svc = AtlasAccessibilityService.instance ?: return AtlasAccessibilityService.ready() ?: "Turn on Atlas screen control first."
        steps.clear(); count = 0; lastPkg = ""
        runCatching {
            val info = svc.serviceInfo
            oldTypes = info.eventTypes
            info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED
            svc.serviceInfo = info
        }
        showPill(svc)
        recording = true
        return null
    }

    fun stop(): List<JSONObject> {
        recording = false
        AtlasAccessibilityService.instance?.let { svc ->
            runCatching { val info = svc.serviceInfo; info.eventTypes = oldTypes.takeIf { it != 0 } ?: info.eventTypes; svc.serviceInfo = info }
            pill?.let { p -> runCatching { svc.getSystemService(WindowManager::class.java).removeView(p) } }
        }
        pill = null
        return steps.toList()
    }

    private fun showPill(svc: AtlasAccessibilityService) {
        val wm = svc.getSystemService(WindowManager::class.java)
        val v = TextView(svc).apply {
            text = "●  Recording — tap to stop"; setTextColor(android.graphics.Color.WHITE); textSize = 13f
            setPadding(36, 18, 36, 18)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 60f; setColor(android.graphics.Color.rgb(220, 38, 38)) }
            setOnClickListener {
                stop()
                svc.startActivity(android.content.Intent(svc, com.nizam.app.MainActivity::class.java).putExtra("route", "skills")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 90 }
        runCatching { wm.addView(v, lp); pill = v }
    }

    fun onEvent(svc: AtlasAccessibilityService, e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == svc.packageName || skip.any { pkg.lowercase().contains(it) }) return
        if (pkg.contains("inputmethod") || pkg.startsWith("com.android.systemui")) return
        val src = e.source
        val text = (e.text?.joinToString(" ")?.trim()).orEmpty().ifBlank { src?.text?.toString().orEmpty() }.take(60)
        val desc = (e.contentDescription?.toString() ?: src?.contentDescription?.toString()).orEmpty().take(60)
        val id = src?.viewIdResourceName.orEmpty()
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (pkg != lastPkg && !pkg.contains("launcher")) {
                lastPkg = pkg
                val label = runCatching { svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrElse { pkg }
                add(JSONObject().put("k", "ui").put("op", "open").put("app", label).put("pkg", pkg))
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> add(JSONObject().put("k", "ui").put("op", "tap").put("text", text).put("desc", desc).put("id", id).put("pkg", pkg))
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (e.isPassword || src?.isPassword == true) return          // never recorded
                val last = steps.lastOrNull()
                if (last != null && last.optString("op") == "type" && last.optString("id") == id) last.put("value", text)
                else add(JSONObject().put("k", "ui").put("op", "type").put("value", text).put("id", id).put("pkg", pkg))
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (steps.lastOrNull()?.optString("op") != "scroll") add(JSONObject().put("k", "ui").put("op", "scroll").put("dir", "down").put("pkg", pkg))
        }
    }

    private fun add(o: JSONObject) { if (steps.size < 150) { steps += o; count = steps.size; pill?.text = "●  Recording · ${steps.size} steps — tap to stop" } }
}
