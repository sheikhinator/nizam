package com.nizam.app.feature.linux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

/** Open terminal tabs; they keep running while Atlas is open, whichever screen you're on. */
object Terminals {
    data class Tab(val title: String, val session: TerminalSession)
    val tabs = mutableStateListOf<Tab>()
    var current by mutableIntStateOf(0)
    @Volatile var view: TerminalView? = null
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var fontSize by mutableIntStateOf(28)
    /** A folder to open the next new tab in (Atlas Code's project, for example). */
    @Volatile var nextCwd: String? = null

    val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { view?.let { if (it.currentSession == changedSession) it.onScreenUpdated() } }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            val i = tabs.indexOfFirst { it.session == finishedSession }
            if (i >= 0) { tabs.removeAt(i); if (current >= tabs.size) current = (tabs.size - 1).coerceAtLeast(0) }
            view?.let { v -> tabs.getOrNull(current)?.let { v.attachSession(it.session) } }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            view?.context?.let { c -> (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal", text)) }
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val c = view?.context ?: return
            val t = (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString() ?: return
            session?.emulator?.paste(t)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) { view?.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    /** A new tab: Linux if it is installed, otherwise Android's own shell. */
    fun open(c: Context, distro: LinuxEnv.Distro?, cwd: String? = null): Boolean {
        val dir = cwd ?: nextCwd ?: "/root"
        nextCwd = null
        val s = if (distro != null && LinuxEnv.installed(c, distro)) {
            val cmd = LinuxEnv.command(c, distro, listOf(distro.shell, "-l"), dir)
            TerminalSession(cmd[0], c.filesDir.path, cmd.toTypedArray(), LinuxEnv.envArray(c), 3000, client)
        } else {
            TerminalSession("/system/bin/sh", LinuxEnv.projects(c).path, arrayOf("sh"), arrayOf("HOME=${LinuxEnv.projects(c).path}", "TERM=xterm-256color",
                "PATH=/system/bin:/system/xbin", "TMPDIR=${c.cacheDir.path}"), 3000, client)
        }
        tabs += Tab(distro?.takeIf { LinuxEnv.installed(c, it) }?.title ?: "Android", s)
        current = tabs.lastIndex
        view?.attachSession(s)
        return true
    }

    fun send(text: String) { tabs.getOrNull(current)?.session?.write(text) }
}

@Composable
fun TerminalScreen() {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val progress by LinuxEnv.progress.collectAsState()
    var installing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val ready = remember(refresh, progress) { LinuxEnv.installedAny(c) }
    var showSetup by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0E14)).statusBarsPadding().imePadding()) {
        // ── tabs ──
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("❯_", color = Color(0xFF5EEAD4), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
            Terminals.tabs.forEachIndexed { i, t ->
                val on = i == Terminals.current
                Text("${i + 1} · ${t.title}", color = if (on) Color.Black else Color(0xFFCBD5E1), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF5EEAD4) else Color(0xFF1E2430))
                        .clickable { Terminals.current = i; Terminals.view?.attachSession(t.session) }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
            Text("＋", color = Color(0xFF5EEAD4), modifier = Modifier.clip(CircleShape).clickable { Terminals.open(c, LinuxEnv.preferred(c)) }.padding(8.dp))
            Text("Linux ⚙", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelMedium, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showSetup = !showSetup }.padding(8.dp))
        }

        if (showSetup || (ready.isEmpty() && Terminals.tabs.isEmpty())) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Atlas Terminal", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Text("A real Linux on your phone, no root. Install one (or both); your projects folder is shared with Atlas Code at /root/projects. The vault and Atlas's data are never visible here.",
                    color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodyMedium)
                if (!LinuxEnv.available(c)) Text("This build doesn't include the Linux runtime.", color = Color(0xFFF87171))
                LinuxEnv.Distro.values().forEach { d ->
                    val inst = LinuxEnv.installed(c, d)
                    val p = progress?.takeIf { it.distro == d.id }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF151A23)).border(1.dp, Color(0xFF263041), RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Text((if (d == LinuxEnv.Distro.ALPINE) "🏔  " else "🟠  ") + d.title + if (inst) "  ✓" else "", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(d.blurb, color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (p != null && p.stage != "Ready") {
                            Text(p.stage, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                            if (p.pct in 0..99) LinearProgressIndicator(progress = { p.pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            if (p.error.isNotBlank()) Text(p.error, color = Color(0xFFF87171), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!inst) Button(enabled = !installing && LinuxEnv.available(c), onClick = {
                                installing = true
                                scope.launch { val ok = LinuxEnv.install(c, d); installing = false; refresh++
                                    if (ok) { showSetup = false; Terminals.open(c, d)
                                        // so git and friends are there from the start
                                        Terminals.send(if (d == LinuxEnv.Distro.ALPINE) "apk update && apk add git bash curl nano python3 && clear && echo 'Atlas Terminal ready ✓'\n"
                                            else "apt update && DEBIAN_FRONTEND=noninteractive apt install -y git curl nano python3 && clear && echo 'Atlas Terminal ready ✓'\n") } }
                            }) { Text("Install") }
                            else {
                                Button(onClick = { showSetup = false; LinuxEnv.setPreferred(c, d); Terminals.open(c, d) }) { Text("Open") }
                                OutlinedButton(onClick = { LinuxEnv.setPreferred(c, d); refresh++ }) { Text(if (LinuxEnv.preferred(c) == d) "Default ✓" else "Make default") }
                                TextButton(onClick = { LinuxEnv.remove(c, d); refresh++ }) { Text("Remove", color = Color(0xFFF87171)) }
                            }
                        }
                    }
                }
                TextButton(onClick = { showSetup = false; Terminals.open(c, null) }) { Text("Open Android's own shell instead") }
            }
        } else {
            androidx.compose.runtime.LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { if (Terminals.tabs.isEmpty()) Terminals.open(c, LinuxEnv.preferred(c)) } }
            AndroidView(factory = { ctx -> makeView(ctx) }, update = { v ->
                Terminals.view = v
                Terminals.tabs.getOrNull(Terminals.current)?.let { if (v.currentSession != it.session) v.attachSession(it.session) }
            }, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp))
            ExtraKeys()
        }
    }
}

private fun makeView(ctx: Context): TerminalView {
    val v = TerminalView(ctx, null)
    v.setTextSize(Terminals.fontSize)
    v.setTypeface(Typeface.MONOSPACE)
    v.isFocusable = true; v.isFocusableInTouchMode = true
    v.setBackgroundColor(android.graphics.Color.parseColor("#0B0E14"))
    v.setTerminalViewClient(object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) { Terminals.fontSize = (Terminals.fontSize * scale).toInt().coerceIn(14, 64); v.setTextSize(Terminals.fontSize); return 1f }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            v.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey(): Boolean { val on = Terminals.ctrl; if (on) Terminals.ctrl = false; return on }
        override fun readAltKey(): Boolean { val on = Terminals.alt; if (on) Terminals.alt = false; return on }
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    })
    Terminals.view = v
    Terminals.tabs.getOrNull(Terminals.current)?.let { v.attachSession(it.session) }
    v.requestFocus()
    return v
}

@Composable
private fun ExtraKeys() {
    val keys = listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL" to "", "ALT" to "", "←" to "\u001b[D", "↑" to "\u001b[A", "↓" to "\u001b[B", "→" to "\u001b[C",
        "-" to "-", "/" to "/", "|" to "|", "~" to "~", "HOME" to "\u001b[H", "END" to "\u001b[F", "PASTE" to "")
    Row(Modifier.fillMaxWidth().background(Color(0xFF151A23)).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.forEach { (label, seq) ->
            val on = (label == "CTRL" && Terminals.ctrl) || (label == "ALT" && Terminals.alt)
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF5EEAD4) else Color(0xFF1E2430)).clickable {
                when (label) {
                    "CTRL" -> Terminals.ctrl = !Terminals.ctrl
                    "ALT" -> Terminals.alt = !Terminals.alt
                    "PASTE" -> Terminals.tabs.getOrNull(Terminals.current)?.session?.let { Terminals.client.onPasteTextFromClipboard(it) }
                    else -> Terminals.send(seq)
                }
            }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(label, color = if (on) Color.Black else Color(0xFFE2E8F0), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
