package com.nizam.app.feature.jarvis

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.nizam.app.MainActivity
import com.nizam.app.feature.phone.AtlasAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Folax — the AI assistant built into Infinix, Tecno and itel phones. It has no public API, so Atlas
 * works it the way you would: opens Folax, types the question, waits for the reply to finish, reads
 * it off the screen (through Atlas's accessibility service), and comes back with the answer. Free,
 * and it runs on Infinix's own AI. Takes ~10–30 s and briefly shows Folax on screen.
 */
object Folax {
    private val KNOWN = listOf("com.transsion.folax", "com.transsion.aivoiceassistant", "com.transsion.ai.assistant",
        "com.infinix.folax", "com.transsion.aiassistant")

    /** Folax's package on this phone, found by known names or by an app called "Folax". */
    fun pkg(c: Context): String? {
        val pm = c.packageManager
        KNOWN.firstOrNull { p -> runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false) }?.let { return it }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(launcher, 0)
        return apps.firstOrNull { it.loadLabel(pm).toString().contains("folax", true) }?.activityInfo?.packageName
            ?: runCatching { pm.getInstalledApplications(0).firstOrNull { pm.getApplicationLabel(it).toString().contains("folax", true) }?.packageName }.getOrNull()
    }

    fun available(c: Context) = pkg(c) != null

    /** Just opens Folax (with the text handed over when it accepts shared text). */
    fun open(c: Context, text: String? = null): Boolean {
        val p = pkg(c) ?: return false
        val share = text?.let { Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it).setPackage(p) }
            ?.takeIf { it.resolveActivity(c.packageManager) != null }
        val intent = share ?: c.packageManager.getLaunchIntentForPackage(p) ?: return false
        return runCatching { c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Asks Folax and returns its answer as text. */
    suspend fun ask(c: Context, question: String): String {
        val p = pkg(c) ?: throw IllegalStateException("Folax isn't on this phone (it comes with Infinix, Tecno and itel phones).")
        val svc = AtlasAccessibilityService.instance
            ?: throw IllegalStateException("Turn on Atlas in Settings › Accessibility so it can read Folax's answer.")
        c.packageManager.getLaunchIntentForPackage(p)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ?: throw IllegalStateException("Folax can't be opened from another app on this phone.")

        // wait for Folax's window
        var root: AccessibilityNodeInfo? = null
        repeat(30) { if (root == null) { root = svc.rootInActiveWindow?.takeIf { it.packageName == p }; if (root == null) delay(300) } }
        root ?: throw IllegalStateException("Folax didn't open.")
        delay(600)

        // the text box; some Folax screens start in voice mode with a keyboard button
        var field = editable(svc.rootInActiveWindow)
        if (field == null) {
            clickable(svc.rootInActiveWindow, listOf("keyboard", "type", "text", "键盘", "input"))?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(700); field = editable(svc.rootInActiveWindow)
        }
        field ?: throw IllegalStateException("Couldn't find Folax's text box — open Folax once and switch it to typing.")
        val before = lines(svc.rootInActiveWindow).toSet()
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, question) })
        delay(400)
        val sent = clickable(svc.rootInActiveWindow, listOf("send", "发送", "submit", "enviar"))?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true ||
            (Build.VERSION.SDK_INT >= 30 && field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
        if (!sent) throw IllegalStateException("Couldn't press Folax's send button.")

        // read new text until it stops changing (Folax writes its answer progressively)
        var answer = ""; var stableFor = 0; var waited = 0
        while (waited < 60_000) {
            delay(800); waited += 800
            val now = lines(svc.rootInActiveWindow).filter { it !in before && !it.equals(question, true) && it.length > 1 }
            val text = now.filterNot { it.length < 3 && it.all { ch -> !ch.isLetter() } }.joinToString("\n")
            if (text.isNotBlank() && text == answer) { stableFor += 800; if (stableFor >= 2400) break } else { stableFor = 0; answer = text }
        }
        // back to Atlas
        withContext(Dispatchers.Main) {
            runCatching { c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        }
        return answer.ifBlank { throw IllegalStateException("Folax didn't answer in time.") }
    }

    private fun editable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && !n.isPassword) return n
        for (i in 0 until n.childCount) editable(n.getChild(i))?.let { return it }
        return null
    }

    private fun clickable(n: AccessibilityNodeInfo?, words: List<String>): AccessibilityNodeInfo? {
        if (n == null) return null
        val label = listOfNotNull(n.text, n.contentDescription, n.viewIdResourceName).joinToString(" ").lowercase()
        if (words.any { label.contains(it) }) {
            var x: AccessibilityNodeInfo? = n; var hops = 0
            while (x != null && !x.isClickable && hops < 4) { x = x.parent; hops++ }
            if (x?.isClickable == true) return x
        }
        for (i in 0 until n.childCount) clickable(n.getChild(i), words)?.let { return it }
        return null
    }

    private fun lines(n: AccessibilityNodeInfo?): List<String> {
        val out = mutableListOf<String>()
        fun walk(x: AccessibilityNodeInfo?, d: Int) {
            if (x == null || d > 30 || out.size > 300) return
            if (!x.isEditable) x.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until x.childCount) walk(x.getChild(i), d + 1)
        }
        walk(n, 0)
        return out
    }
}
