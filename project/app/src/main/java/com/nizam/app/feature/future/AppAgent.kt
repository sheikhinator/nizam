package com.nizam.app.feature.future

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nizam.app.AppContainer
import com.nizam.app.feature.phone.AtlasAccessibilityService
import com.nizam.app.net.Ai
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app agent: say what you want and Atlas operates your other apps to do it — WhatsApp, YouTube, Chrome,
 * Careem, Foodpanda, Daraz… It reads the screen through accessibility (screen control), decides one step at a
 * time with the AI, and always stops to ask before anything that can't be undone (sending, ordering, paying,
 * booking, deleting). Tasks that work are saved as recipes and replayed in seconds next time.
 */
object AppAgent {
    data class El(val id: Int, val node: AccessibilityNodeInfo, val label: String, val kind: String)
    data class Step(val action: String, val label: String, val text: String, val app: String)
    data class Recipe(val name: String, val steps: List<Step>, val uses: Int, val schedule: String)

    val status = MutableStateFlow("")
    val log = MutableStateFlow<List<String>>(emptyList())
    @Volatile var running = false; private set
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val FORBIDDEN = listOf("bank", "wallet", "jazzcash", "easypaisa", "sadapay", "nayapay", "meezan", "hbl", "ubl", "mcb",
        "alfalah", "allied", "askari", "faysal", "binance", "crypto", "authenticator", "password", "bitwarden", "lastpass")
    private val RISKY = Regex("\\b(send|pay|place order|order now|confirm|buy|book|checkout|check out|submit|delete|remove|post|publish|transfer|purchase|call|subscribe)\\b", RegexOption.IGNORE_CASE)

    // ── the screen, as numbered elements ────────────────────────────────────────────────────────
    private fun svc() = AtlasAccessibilityService.instance

    fun elements(): List<El> {
        val root = svc()?.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<El>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || out.size >= 90 || depth > 30) return
            if (n.isVisibleToUser) {
                val text = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
                val kind = when {
                    n.isPassword -> "password"
                    n.isEditable -> "input"
                    n.isCheckable -> if (n.isChecked) "checked" else "checkbox"
                    n.isClickable || n.isLongClickable -> "button"
                    n.isScrollable -> "list"
                    else -> "text"
                }
                val label = text.ifBlank { hint }.take(90)
                if (label.isNotBlank() || kind == "input") out += El(out.size + 1, n, label.ifBlank { "(empty field)" }, kind)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    fun currentApp(): String = svc()?.rootInActiveWindow?.packageName?.toString().orEmpty()

    private fun describe(els: List<El>) = els.joinToString("\n") { "[${it.id}] ${it.kind}: ${it.label}" }

    // ── doing things ────────────────────────────────────────────────────────────────────────────
    private fun tap(el: El): Boolean {
        var n: AccessibilityNodeInfo? = el.node; var hops = 0
        while (n != null && !n.isClickable && hops < 6) { n = n.parent; hops++ }
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect().also { el.node.getBoundsInScreen(it) }
        return gesture(r.exactCenterX(), r.exactCenterY())
    }
    private fun gesture(x: Float, y: Float, ms: Long = 60): Boolean {
        val s = svc() ?: return false
        return s.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, ms)).build(), null, null)
    }
    private fun type(el: El, text: String): Boolean {
        if (el.node.isPassword) return false
        el.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return el.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
    }
    private fun enter(): Boolean {
        val root = svc()?.rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
    }
    private fun scroll(forward: Boolean): Boolean {
        val root = svc()?.rootInActiveWindow ?: return false
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? { if (n == null) return null; if (n.isScrollable) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }; return null }
        val t = find(root)
        if (t != null) return t.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        val dm = svc()!!.resources.displayMetrics
        val s = svc()!!
        val path = Path().apply { moveTo(dm.widthPixels / 2f, dm.heightPixels * if (forward) 0.75f else 0.3f); lineTo(dm.widthPixels / 2f, dm.heightPixels * if (forward) 0.3f else 0.75f) }
        return s.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build(), null, null)
    }

    /** Opens an installed app by (fuzzy) name. */
    fun openApp(c: Context, name: String): Boolean {
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val want = name.lowercase().trim()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().equals(want, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(want) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(want.replace(" ", "")) }
            ?: return false
        c.startActivity(pm.getLaunchIntentForPackage(hit.activityInfo.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return true
    }

    // ── the floating panel (an accessibility overlay: no extra permission, stays above every app) ─
    private var panel: View? = null
    private var panelText: TextView? = null
    private var confirmRow: LinearLayout? = null
    private var decision: CompletableDeferred<Boolean>? = null

    private fun showPanel(text: String) {
        val s = svc() ?: return
        status.value = text
        if (panel == null) {
            val d = s.resources.displayMetrics.density
            val box = LinearLayout(s).apply {
                orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 22 * d; setColor(0xEE101418.toInt()) }
            }
            val t = TextView(s).apply { setTextColor(android.graphics.Color.WHITE); textSize = 13f }
            val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
            val yes = Button(s).apply { setText("Yes, do it"); setOnClickListener { decision?.complete(true) } }
            val no = Button(s).apply { setText("No"); setOnClickListener { decision?.complete(false) } }
            row.addView(yes); row.addView(no)
            val stop = TextView(s).apply { setText("■ Stop"); setTextColor(0xFFFF8A80.toInt()); textSize = 13f; setPadding(0, (6 * d).toInt(), 0, 0)
                setOnClickListener { stop("Stopped") } }
            box.addView(t); box.addView(row); box.addView(stop)
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
                .apply { gravity = Gravity.BOTTOM; y = (90 * d).toInt(); horizontalMargin = 0.04f }
            runCatching { s.getSystemService(WindowManager::class.java).addView(box, lp); panel = box; panelText = t; confirmRow = row }
        }
        panelText?.text = "✦ Atlas · $text"
    }
    private fun hidePanel() {
        panel?.let { v -> runCatching { svc()?.getSystemService(WindowManager::class.java)?.removeView(v) } }
        panel = null; panelText = null; confirmRow = null
    }
    private suspend fun confirm(message: String): Boolean {
        showPanel("$message — go ahead?")
        confirmRow?.visibility = View.VISIBLE
        val d = CompletableDeferred<Boolean>(); decision = d
        val ok = withTimeoutOrNull(90_000) { d.await() } ?: false
        confirmRow?.visibility = View.GONE; decision = null
        return ok
    }

    fun stop(why: String) {
        job?.cancel(); running = false
        decision?.complete(false)
        status.value = why
        scope.launch { delay(1500); hidePanel() }
    }

    private fun note(line: String) { log.value = (log.value + line).takeLast(60) }

    // ── recipes ─────────────────────────────────────────────────────────────────────────────────
    private fun p(c: Context) = c.getSharedPreferences("app_agent", 0)
    fun recipes(c: Context): List<Recipe> = runCatching { JSONArray(p(c).getString("recipes", "[]")) }.getOrElse { JSONArray() }.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Recipe(o.optString("name"), o.optJSONArray("steps")?.let { s ->
            (0 until s.length()).map { s.getJSONObject(it) }.map { Step(it.optString("a"), it.optString("l"), it.optString("t"), it.optString("p")) } }.orEmpty(),
            o.optInt("uses"), o.optString("schedule")) } }
    fun saveRecipes(c: Context, list: List<Recipe>) = p(c).edit().putString("recipes", JSONArray().apply {
        list.forEach { r -> put(JSONObject().put("name", r.name).put("uses", r.uses).put("schedule", r.schedule).put("steps", JSONArray().apply {
            r.steps.forEach { put(JSONObject().put("a", it.action).put("l", it.label).put("t", it.text).put("p", it.app)) } })) }
    }.toString()).apply()
    private fun key(goal: String) = goal.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    // ── the loop ────────────────────────────────────────────────────────────────────────────────
    private const val SYSTEM = """You operate an Android phone for the user through its accessibility tree, one step at a time.
Each turn you get: the goal, the steps already taken, the current app and the screen as numbered elements.
Reply with ONE JSON object only:
{"thought":"very short","action":"open_app|tap|type|enter|scroll_down|scroll_up|back|home|wait|confirm|done|fail","id":0,"text":"","app":"","message":""}
- open_app: app = the app's name as on the home screen (WhatsApp, YouTube, Chrome, Careem, foodpanda, Daraz…)
- tap: id of the element. type: id of an input and the text to put in it. enter: presses the keyboard's search/send key.
- confirm: BEFORE anything that can't be undone — sending a message, placing an order, paying, booking a ride, deleting, posting.
  message = exactly what will happen (who, what, how much). Then, if approved, do the final tap in the next step.
- done: the goal is achieved; message = a short result for the user (what you did or found).
- fail: impossible here; message = why.
Never type passwords, PINs, OTP codes or card numbers. Prefer the most direct path. If a popup blocks the way, close it."""

    fun start(c: Context, container: AppContainer, goal: String) {
        if (running) { stop("Replaced by a new task") }
        if (svc() == null) { com.nizam.app.ui.components.Feedback.show("Turn on screen control first (Settings › Accessibility › Atlas)"); return }
        running = true; log.value = emptyList()
        val app = c.applicationContext
        job = scope.launch {
            val result = runCatching { run(app, container, goal) }.getOrElse { "Stopped: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            running = false
            showPanel(result); note("= $result")
            com.nizam.app.feature.notify.Notifications.post(app, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 950, "Atlas finished", result)
            delay(4000); hidePanel()
        }
    }

    private suspend fun run(c: Context, container: AppContainer, goal: String): String {
        // a saved recipe for this exact task runs first; if the app changed and it gets stuck, the AI takes over
        recipes(c).firstOrNull { key(it.name) == key(goal) }?.let { r ->
            showPanel("Using your saved steps for “${r.name}”")
            if (replay(c, r)) { saveRecipes(c, recipes(c).map { if (it.name == r.name) it.copy(uses = it.uses + 1) else it }); return "Done: ${r.name}" }
            note("Saved steps didn't fit the screen — thinking it through")
        }
        val steps = mutableListOf<String>(); val recorded = mutableListOf<Step>()
        var lastScreen = ""; var same = 0
        for (i in 1..30) {
            val app = currentApp()
            if (FORBIDDEN.any { app.lowercase().contains(it) }) return "I stopped at ${app} — banking, payment and password apps are yours to operate. Everything up to here is ready."
            val els = elements()
            val screen = describe(els)
            same = if (screen == lastScreen) same + 1 else 0; lastScreen = screen
            if (same >= 3) return "The screen stopped changing — I couldn't finish “$goal”."
            showPanel("Step $i · thinking…")
            val raw = Ai.generate(container.settingsRepository,
                "Goal: $goal\n\nSteps so far:\n${steps.joinToString("\n").ifBlank { "(none)" }}\n\nCurrent app: $app\nScreen:\n${screen.ifBlank { "(nothing readable — maybe loading)" }}",
                SYSTEM, 400)
            val o = runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrNull()
            if (o == null) { steps += "$i. (unclear reply, retrying)"; delay(600); continue }
            val action = o.optString("action"); val el = els.firstOrNull { it.id == o.optInt("id", -1) }
            val text = o.optString("text"); val msg = o.optString("message")
            showPanel("Step $i · ${o.optString("thought").ifBlank { action }}")
            val ok: Boolean = when (action) {
                "open_app" -> openApp(c, o.optString("app")).also { if (it) recorded += Step("open_app", o.optString("app"), "", "") }
                "tap" -> el?.let { e ->
                    // a tap that would send, pay or order needs your yes, even if the AI didn't ask
                    if (RISKY.containsMatchIn(e.label) && steps.none { it.contains("approved") } && !confirm("Tap “${e.label}”")) return "Cancelled — nothing was sent or paid."
                    tap(e).also { if (it) recorded += Step("tap", e.label, "", app) } } ?: false
                "type" -> el?.let { e -> type(e, text).also { if (it) recorded += Step("type", e.label, text, app) } } ?: false
                "enter" -> enter().also { if (it) recorded += Step("enter", "", "", app) }
                "scroll_down" -> scroll(true).also { recorded += Step("scroll_down", "", "", app) }
                "scroll_up" -> scroll(false).also { recorded += Step("scroll_up", "", "", app) }
                "back" -> (svc()?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) == true).also { recorded += Step("back", "", "", app) }
                "home" -> svc()?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) == true
                "wait" -> { delay(1500); true }
                "confirm" -> if (confirm(msg.ifBlank { "Continue" })) { steps += "$i. user approved: $msg"; true } else return "Cancelled — nothing was sent or paid."
                "done" -> {
                    if (recorded.size >= 2) saveRecipes(c, recipes(c).filter { key(it.name) != key(goal) } + Recipe(goal, recorded, 1, ""))
                    return msg.ifBlank { "Done." }
                }
                "fail" -> return msg.ifBlank { "I couldn't do that here." }
                else -> false
            }
            val line = "$i. $action ${el?.let { "[${it.id}] ${it.label}" } ?: o.optString("app")} ${if (text.isNotBlank()) "“$text”" else ""} → ${if (ok) "ok" else "didn't work"}"
            steps += line; note(line)
            delay(if (action == "open_app") 2200 else 1100)
        }
        return "That took too many steps — I stopped. Try a more specific instruction."
    }

    /** Replays saved steps by what the buttons say, not where they were — so it survives small layout changes. */
    private suspend fun replay(c: Context, r: Recipe): Boolean {
        for ((i, s) in r.steps.withIndex()) {
            showPanel("${r.name} · step ${i + 1} of ${r.steps.size}")
            val ok = when (s.action) {
                "open_app" -> openApp(c, s.label)
                "tap", "type" -> {
                    var el: El? = null
                    repeat(4) { if (el == null) { el = elements().firstOrNull { it.label.equals(s.label, true) } ?: elements().firstOrNull { it.label.contains(s.label, true) }; if (el == null) delay(700) } }
                    val e = el ?: return false
                    if (s.action == "tap" && RISKY.containsMatchIn(e.label) && !confirm("Tap “${e.label}”")) return false
                    if (s.action == "tap") tap(e) else type(e, s.text)
                }
                "enter" -> enter()
                "scroll_down" -> scroll(true)
                "scroll_up" -> scroll(false)
                "back" -> svc()?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) == true
                else -> true
            }
            if (!ok) return false
            delay(if (s.action == "open_app") 2200 else 900)
        }
        return true
    }
}
