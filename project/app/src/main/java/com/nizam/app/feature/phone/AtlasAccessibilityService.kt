package com.nizam.app.feature.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Tier 3: screen control. Atlas reads what is on screen and taps, types and scrolls inside other
 * apps — the Gemini / Folax level of control.
 *
 * Safeguards, because this is the most powerful thing in the app:
 *  - a master switch in Settings; when off, every screen action refuses
 *  - it can never act on password fields — typing into them is blocked outright
 *  - it refuses to operate inside banking and payment apps
 *  - every action it takes is logged and visible in Settings
 */
class AtlasAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AtlasAccessibilityService? = null
            private set
        @Volatile var enabledByUser: Boolean = false

        val log = java.util.concurrent.ConcurrentLinkedDeque<String>()

        private fun record(line: String) {
            log.addFirst("${java.time.LocalTime.now().withNano(0)}  $line")
            while (log.size > 80) log.removeLast()
        }

        // Apps Atlas will never touch, whatever it's asked.
        private val forbidden = listOf(
            "bank", "wallet", "pay", "jazzcash", "easypaisa", "sadapay", "nayapay",
            "meezan", "hbl", "ubl", "mcb", "alfalah", "allied", "askari", "faysal",
            "binance", "crypto", "authenticator", "password", "1password", "bitwarden", "lastpass"
        )

        fun ready(): String? = when {
            instance == null -> "Screen control isn't switched on. Enable Atlas in Settings > " +
                "Accessibility > Installed apps."
            !enabledByUser -> "Screen control is turned off in Atlas settings."
            else -> null
        }
    }

    private var lastPkg = ""
    private var enteredAt = 0L
    private var fade: android.view.View? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Grey veil over the screen. Touch passes straight through — it drains colour, it doesn't block. */
    private fun fadeTo(alpha: Float) {
        val wm = getSystemService(android.view.WindowManager::class.java)
        if (alpha <= 0f) { fade?.let { runCatching { wm.removeView(it) } }; fade = null; return }
        val v = fade ?: android.view.View(this).also { view ->
            view.setBackgroundColor(android.graphics.Color.rgb(128, 128, 128))
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT)
            runCatching { wm.addView(view, lp) }; fade = view
        }
        v.alpha = alpha
    }

    private val fadeTick = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("fade", 0)
            if (prefs.getBoolean("on", false) && lastPkg in (prefs.getStringSet("apps", emptySet()) ?: emptySet())) {
                val mins = (System.currentTimeMillis() - enteredAt) / 60_000.0
                // nothing for 20 minutes, then a slow slide to 55% grey over the next 20
                fadeTo(if (mins < 20) 0f else ((mins - 20) / 20 * 0.55).coerceIn(0.08, 0.55).toFloat())
            } else if (fade != null) fadeTo(0f)
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onServiceConnected() { instance = this; record("service connected"); handler.post(fadeTick); runCatching { com.nizam.app.feature.wild.DrawCommand.attach(this) } }
    override fun onDestroy() { runCatching { com.nizam.app.feature.wild.DrawCommand.detach(this) }; handler.removeCallbacks(fadeTick); fadeTo(0f); instance = null; super.onDestroy() }
    private var overtimeShownAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && com.nizam.app.feature.agent.DemoRecorder.recording) runCatching { com.nizam.app.feature.agent.DemoRecorder.onEvent(this, event) }
        if (event != null) runCatching { com.nizam.app.feature.wild.WatchLearn.onEvent(this, event) }
        if (event != null) runCatching { com.nizam.app.feature.omni.Sense.onEvent(this, event) }
        // Bodyguard: the 15 minutes you allowed in a paused app ran out while you're still scrolling, so Atlas steps in mid-session
        if (event != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) runCatching {
            val p = event.packageName?.toString()
            val now = System.currentTimeMillis()
            if (p != null && p == lastPkg && p != packageName && p in com.nizam.app.feature.wellbeing.Pause.apps(this)) {
                val until = com.nizam.app.feature.wellbeing.Pause.allowedUntil(this, p)
                if (until > 0 && now > until && now - overtimeShownAt > 60_000L && !com.nizam.app.feature.shield.Shield.isActive(this)) {
                    overtimeShownAt = now
                    val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrElse { p }
                    startActivity(android.content.Intent(this, com.nizam.app.feature.wellbeing.PauseActivity::class.java)
                        .putExtra("pkg", p).putExtra("label", label).putExtra("overtime", true)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_HISTORY or
                            android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                    record("time's up in $label")
                }
            }
        }
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val entering = pkg != lastPkg
        if (entering) runCatching { com.nizam.app.feature.nx.AfterCall.onWindow(this, lastPkg, pkg) }
        lastPkg = pkg
        if (entering) { enteredAt = System.currentTimeMillis(); fadeTo(0f) }
        if (entering && pkg != packageName && !pkg.startsWith("com.android.systemui") && !pkg.contains("inputmethod") &&
            !pkg.contains("launcher")) runCatching {
            val label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            com.nizam.app.feature.jarvis.Timeline.log(this, "app", "Opened $label")
            com.nizam.app.feature.jarvis.StandingOrders.onEvent(this, "app_open", label, pkg, label)
        }
        if (entering && pkg != packageName && pkg in com.nizam.app.feature.wellbeing.Pause.apps(this) &&
            System.currentTimeMillis() > com.nizam.app.feature.wellbeing.Pause.allowedUntil(this, pkg) &&
            !com.nizam.app.feature.shield.Shield.isActive(this)) {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrElse { pkg }
            startActivity(android.content.Intent(this, com.nizam.app.feature.wellbeing.PauseActivity::class.java)
                .putExtra("pkg", pkg).putExtra("label", label)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_HISTORY or
                    android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            record("paused $label")
            return
        }
        if (com.nizam.app.feature.shield.Shield.isActive(this) && pkg in com.nizam.app.feature.shield.Shield.blocked(this)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val left = (com.nizam.app.feature.shield.Shield.activeUntil(this) - System.currentTimeMillis()) / 60_000 + 1
            android.widget.Toast.makeText(this, "🛡 Focus shield — $left min left", android.widget.Toast.LENGTH_SHORT).show()
            record("shield blocked $pkg")
        }
    }
    override fun onInterrupt() {}

    private fun currentPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    private fun blockedReason(): String? {
        val pkg = currentPackage().lowercase()
        return if (forbidden.any { pkg.contains(it) })
            "I won't operate inside $pkg — banking, payment and password apps are off-limits."
        else null
    }

    /** What's on screen, as readable text — the agent's eyes. */
    fun readScreen(): String {
        blockedReason()?.let { return it }
        val root = rootInActiveWindow ?: return "Nothing readable on screen right now."
        val lines = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || lines.size > 120 || depth > 25) return
            if (node.isPassword) { lines.add("[password field — hidden]"); return }
            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (label.isNotBlank()) {
                val tag = when {
                    node.isEditable -> "[input] "
                    node.isClickable -> "[button] "
                    else -> ""
                }
                lines.add(tag + label.take(120))
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        record("read screen in ${currentPackage()}")
        return "App: ${currentPackage()}\n" + lines.distinct().joinToString("\n").take(3500)
    }

    /**
     * The app underneath Atlas's own windows — what the co-pilot looks at. Walks the interactive
     * windows (top first) and takes the first ordinary app window that isn't Atlas or the system UI.
     */
    fun readUnderlying(): Pair<String, String> {
        val win = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .firstOrNull { w ->
                val pkg = w.root?.packageName?.toString().orEmpty()
                pkg.isNotBlank() && pkg != packageName && !pkg.startsWith("com.android.systemui")
            }
        val root = win?.root ?: rootInActiveWindow ?: return "" to "Nothing readable on screen right now."
        val pkg = root.packageName?.toString().orEmpty()
        if (forbidden.any { pkg.lowercase().contains(it) })
            return pkg to "This is a banking, payment or password app — Atlas doesn't read or act inside those."
        val lines = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || lines.size > 160 || depth > 28) return
            if (node.isPassword) { lines.add("[password field — hidden]"); return }
            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (label.isNotBlank()) lines.add((if (node.isEditable) "[input] " else if (node.isClickable) "[button] " else "") + label.take(200))
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrElse { pkg }
        record("co-pilot read $label")
        return label to lines.distinct().joinToString("\n").take(5000)
    }

    private fun findNode(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.isVisibleToUser }
    }

    /** Taps the element whose text or description matches. Walks up to a clickable parent. */
    fun tap(text: String): String {
        blockedReason()?.let { return it }
        var node = findNode(text) ?: return "Couldn't find \"$text\" on screen."
        var hops = 0
        while (!node.isClickable && node.parent != null && hops < 6) { node = node.parent; hops++ }
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            record("tapped \"$text\""); return "Tapped \"$text\""
        }
        // Fall back to a real touch at the element's centre
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        gestureTap(bounds.exactCenterX(), bounds.exactCenterY())
        record("gesture-tapped \"$text\"")
        return "Tapped \"$text\""
    }

    /** Taps by the exact element a recording saw: its view id first, then its label. Null when it isn't on screen. */
    fun tapTarget(id: String?, text: String?, desc: String?): String? {
        blockedReason()?.let { return it }
        val root = rootInActiveWindow ?: return null
        val byId = id?.takeIf { it.isNotBlank() }?.let { root.findAccessibilityNodeInfosByViewId(it)?.firstOrNull { n -> n.isVisibleToUser &&
            (text.isNullOrBlank() || n.text?.toString() == text || root.findAccessibilityNodeInfosByViewId(it).size == 1) } }
        val node = byId ?: text?.takeIf { it.isNotBlank() }?.let { findNode(it) } ?: desc?.takeIf { it.isNotBlank() }?.let { findNode(it) } ?: return null
        var n = node; var hops = 0
        while (!n.isClickable && n.parent != null && hops < 6) { n = n.parent; hops++ }
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { record("replayed tap ${text ?: desc ?: id}"); return "Tapped ${text ?: desc ?: "button"}" }
        val b = Rect().also { n.getBoundsInScreen(it) }
        gestureTap(b.exactCenterX(), b.exactCenterY()); record("replayed gesture ${text ?: desc ?: id}")
        return "Tapped ${text ?: desc ?: "button"}"
    }

    /** Types into a field found by its view id, falling back to the focused one. */
    fun typeInto(id: String?, value: String): String {
        blockedReason()?.let { return it }
        val root = rootInActiveWindow ?: return "No window to type into."
        val target = id?.takeIf { it.isNotBlank() }?.let { root.findAccessibilityNodeInfosByViewId(it)?.firstOrNull { n -> n.isEditable } }
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return "No text field on screen."
        if (target.isPassword) return "That's a password field. I don't type into those — ever."
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
        return if (ok) "Typed \"$value\"" else "The field wouldn't accept text."
    }

    fun foregroundPackage(): String = currentPackage()

    /** Types into the focused field, or the first editable field. Never into passwords. */
    fun type(text: String): String {
        blockedReason()?.let { return it }
        val root = rootInActiveWindow ?: return "No window to type into."
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return "No text field on screen."
        if (target.isPassword) {
            record("REFUSED password field")
            return "That's a password field. I don't type into those — ever."
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        record(if (ok) "typed ${text.length} chars" else "typing failed")
        return if (ok) "Typed \"$text\"" else "The field wouldn't accept text."
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && !node.isPassword) return node
        for (i in 0 until node.childCount) findEditable(node.getChild(i))?.let { return it }
        return null
    }

    fun scroll(forward: Boolean): String {
        blockedReason()?.let { return it }
        val root = rootInActiveWindow ?: return "Nothing to scroll."
        fun scrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) scrollable(n.getChild(i))?.let { return it }
            return null
        }
        val target = scrollable(root) ?: return "Nothing scrollable here."
        val ok = target.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
        record("scrolled ${if (forward) "down" else "up"}")
        return if (ok) "Scrolled ${if (forward) "down" else "up"}" else "Couldn't scroll."
    }

    fun global(action: String): String {
        val code = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings", "quick settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return "Unknown action \"$action\"."
        }
        val ok = performGlobalAction(code)
        record("global: $action")
        return if (ok) "Done: $action" else "The system refused \"$action\"."
    }

    private fun gestureTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build(),
            null, null
        )
    }

    /** Lets UI settle between steps so a tap lands on the screen it was meant for. */
    suspend fun settle(ms: Long = 700) = delay(ms)
}
