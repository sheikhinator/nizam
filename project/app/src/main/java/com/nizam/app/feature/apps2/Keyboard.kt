package com.nizam.app.feature.apps2

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.feature.jarvis.Background
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.net.Ai
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object KeyboardApp {
    val all: List<Spec> = listOf(ScreenSpec("keyboard", "Atlas keyboard", "English, Urdu and numbers, with AI fix, formal and translate in any app", Icons.Outlined.Keyboard, Hub.Tools,
        "keyboard typing urdu ai rewrite translate ime") { _, _ -> KeyboardSetupScreen() })
}

@Composable
fun KeyboardSetupScreen() {
    val ctx = LocalContext.current
    var test by remember { mutableStateOf("") }
    AtlasScreen(title = "Atlas keyboard", subtitle = "Type anywhere with Atlas's AI on the top row", hub = Hub.Tools) {
        item("s") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("1 · Turn it on", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.padding(vertical = Space.s)) { Text("Open keyboard settings") }
            Text("2 · Switch to it", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }, modifier = Modifier.padding(vertical = Space.s)) { Text("Choose keyboard") }
            Text("Android warns that any keyboard can see what you type. Atlas's keyboard only sends text to your AI when you tap an AI key, and never in password fields.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item("t") { OutlinedTextField(test, { test = it }, Modifier.fillMaxWidth().padding(Space.page), label = { Text("Try it here") }, minLines = 3) }
    }
}

/** The keyboard itself: plain Android views so it opens instantly in any app. */
class AtlasKeyboard : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var layout = "en"
    private var shift = false
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var password = false

    private val EN = listOf("qwertyuiop", "asdfghjkl", "⇧zxcvbnm⌫")
    private val UR = listOf("طصھدٹپتبجح", "مورنلہاکیق", "فگسشچخعزذ⌫")
    private val NUM = listOf("1234567890", "@#₨&-+()/:", "*\"';!?,.⌫")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val dark get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = (info?.inputType ?: 0) and EditorInfo.TYPE_MASK_VARIATION
        password = cls == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD || cls == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD || cls == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        if ((info?.inputType ?: 0) and EditorInfo.TYPE_MASK_CLASS == EditorInfo.TYPE_CLASS_NUMBER || (info?.inputType ?: 0) and EditorInfo.TYPE_MASK_CLASS == EditorInfo.TYPE_CLASS_PHONE) layout = "num"
        rebuild()
    }

    override fun onCreateInputView(): View = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root = this; rebuild() }

    private fun key(label: String, weight: Float = 1f, accent: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = if (label.length > 2) 13f else 20f
        setTextColor(if (dark) Color.WHITE else Color.BLACK)
        background = GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(when { accent -> 0xFF4FC3A1.toInt(); dark -> 0xFF3A3D45.toInt(); else -> Color.WHITE }) }
        layoutParams = LinearLayout.LayoutParams(0, dp(46), weight).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
        if (label == "⌫") setOnLongClickListener { currentInputConnection?.deleteSurroundingText(40, 0); true }
    }

    private fun rebuild() {
        val r = root ?: return
        r.removeAllViews()
        r.setBackgroundColor(if (dark) 0xFF1E2026.toInt() else 0xFFD8DCE2.toInt())
        r.setPadding(dp(3), dp(3), dp(3), dp(6))
        // AI row
        val ai = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = if (password) listOf("🔒 AI off in password fields" to "") else
            listOf("✨ Fix" to "Fix spelling and grammar. Keep the language, tone and meaning.", "👔 Formal" to "Rewrite politely and formally.",
                "😊 Friendly" to "Rewrite warmly and casually.", "→ اردو" to "Translate into Urdu script.", "→ English" to "Translate into natural English.",
                "→ Roman" to "Rewrite in Roman Urdu.", "✂ Shorter" to "Make it shorter, keep the meaning.", "💬 Reply" to "REPLY")
        chips.forEach { (label, instr) ->
            ai.addView(TextView(this).apply {
                text = label; textSize = 13f; setPadding(dp(12), dp(6), dp(12), dp(6)); setTextColor(if (dark) Color.WHITE else Color.BLACK)
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (dark) 0xFF2C3E38.toInt() else 0xFFE3F5EE.toInt()) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
                if (instr.isNotBlank()) setOnClickListener { rewrite(instr) }
            })
        }
        r.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ai) })
        status = TextView(this).apply { textSize = 11f; setTextColor(0xFF4FC3A1.toInt()); setPadding(dp(8), 0, 0, 0); visibility = View.GONE }
        r.addView(status)
        val rows = when (layout) { "ur" -> UR; "num" -> NUM; else -> EN }
        rows.forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; if (row.length < 10) setPadding(dp(14), 0, dp(14), 0) }
            row.forEach { ch ->
                val s = ch.toString()
                line.addView(when (s) {
                    "⇧" -> key(if (shift) "⬆" else "⇧", 1.4f, shift) { shift = !shift; rebuild() }
                    "⌫" -> key("⌫", 1.4f) { val sel = currentInputConnection?.getSelectedText(0); if (!sel.isNullOrEmpty()) currentInputConnection?.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
                    else -> key(if (shift && layout == "en") s.uppercase() else s) { currentInputConnection?.commitText(if (shift && layout == "en") s.uppercase() else s, 1); if (shift) { shift = false; rebuild() } }
                })
            }
            r.addView(line)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(key(if (layout == "num") "ABC" else "?123", 1.5f) { layout = if (layout == "num") "en" else "num"; rebuild() })
        bottom.addView(key(if (layout == "ur") "EN" else "اردو", 1.3f) { layout = if (layout == "ur") "en" else "ur"; rebuild() })
        bottom.addView(key(if (layout == "ur") "،" else ",", 1f) { currentInputConnection?.commitText(if (layout == "ur") "،" else ",", 1) })
        bottom.addView(key("space", 4f) { currentInputConnection?.commitText(" ", 1) })
        bottom.addView(key(if (layout == "ur") "۔" else ".", 1f) { currentInputConnection?.commitText(if (layout == "ur") "۔" else ".", 1) })
        bottom.addView(key("⏎", 1.5f, true) {
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
            if (action != 0 && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) currentInputConnection?.performEditorAction(action)
            else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        })
        r.addView(bottom)
    }

    private fun rewrite(instr: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(4000, 0)?.toString().orEmpty()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val text = selected.ifBlank { before + after }
        if (text.isBlank() && instr != "REPLY") { status?.apply { this.text = "Type something first"; visibility = View.VISIBLE }; return }
        status?.apply { this.text = "Atlas is writing…"; visibility = View.VISIBLE }
        scope.launch {
            val out = runCatching {
                val s = Background.container(this@AtlasKeyboard).settingsRepository
                if (instr == "REPLY") Ai.generate(s, "Suggest a short, natural reply I could send. The conversation or draft so far:\n$text\nReply with the message only.", maxOutputTokens = 200)
                else Ai.generate(s, "$instr Reply with the rewritten text only.\n\n$text", maxOutputTokens = 700)
            }.getOrNull()?.trim()?.trim('"')
            val ic2 = currentInputConnection
            if (out.isNullOrBlank() || ic2 == null) { status?.text = "Couldn't reach AI"; return@launch }
            ic2.beginBatchEdit()
            if (selected.isNotBlank()) ic2.commitText(out, 1)
            else if (instr == "REPLY" && text.isBlank()) ic2.commitText(out, 1)
            else { ic2.deleteSurroundingText(before.length, after.length); ic2.commitText(out, 1) }
            ic2.endBatchEdit()
            status?.visibility = View.GONE
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
