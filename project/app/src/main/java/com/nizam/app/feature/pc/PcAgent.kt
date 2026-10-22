package com.nizam.app.feature.pc

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nizam.app.AppContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Atlas using its PC: look at the screen, decide, click / type / scroll / run a command, look again —
 * until the task is done. Free hand, but anything risky (paying, sending, signing in, deleting your files)
 * waits for your OK on the screen.
 */
object PcAgent {
    data class Step(val text: String, val ok: Boolean = true)
    val steps = mutableStateListOf<Step>()
    var task by mutableStateOf("")
    var running by mutableStateOf(false)
    var status by mutableStateOf("")
    var cursor by mutableStateOf<Pair<Int, Int>?>(null)
    var confirm by mutableStateOf<String?>(null)
    private var confirmReply: CompletableDeferred<Boolean>? = null
    var question by mutableStateOf<String?>(null)
    private var questionReply: CompletableDeferred<String>? = null
    @Volatile var paused = false
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.nizam.app.feature.safe.CoroutineSafetyNet.inScope)
    /** A task handed over from a chat ("Use PC"), started when the PC screen opens. */
    @Volatile var queued: String? = null

    fun answerConfirm(yes: Boolean) { confirm = null; confirmReply?.complete(yes) }
    fun answerQuestion(text: String) { question = null; questionReply?.complete(text) }

    fun stop() { job?.cancel(); running = false; status = "Stopped"; confirmReply?.complete(false); questionReply?.complete("Stop.") ; confirm = null; question = null }

    private fun log(t: String, ok: Boolean = true) { steps += Step(t, ok); if (steps.size > 200) steps.removeAt(0) }

    fun start(container: AppContainer, pcId: String, request: String) {
        if (running) return
        task = request; steps.clear(); running = true; paused = false; status = "Starting"
        job = scope.launch {
            val c = container.appContext
            val result = runCatching { loop(container, c, pcId, request) }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) "Stopped." else "I hit a problem: ${e.message}" }
            running = false; status = "Done"; cursor = null
            log("✓ $result")
            // the chat that asked gets the outcome
            runCatching { container.agentMemory.addTurn(false, "🖥 **On Atlas's PC** — $request\n\n$result") }
        }
    }

    private fun system(w: Int, h: Int) = "You are Atlas, operating your own Linux desktop computer (screen $w×$h pixels) to complete the user's task. " +
        "Each turn you get a screenshot. Reply with ONE JSON object only:\n" +
        "{\"think\":\"what you see and why\",\"action\":\"click|double_click|right_click|move|drag|scroll|type|key|shell|open|wait|ask|done\",…}\n" +
        "click/double_click/right_click/move: \"x\",\"y\" (pixels in THIS screenshot). drag: \"x\",\"y\",\"x2\",\"y2\". scroll: \"x\",\"y\",\"amount\" (+down/−up, in notches). " +
        "type: \"text\" (click into the field first). key: \"keys\" like \"enter\", \"ctrl+l\", \"alt+f4\", \"ctrl+shift+t\". " +
        "shell: \"command\" (runs as root in the PC's terminal, output comes back; the GUI is DISPLAY=:1; start GUI apps with '&'). " +
        "open: \"app\" or \"url\" (launches a program or opens a web page in the browser). wait: \"seconds\". ask: \"question\" for the user. " +
        "done: \"summary\" in Markdown of what you did and found.\n" +
        "Add \"risky\":true (with a short \"reason\") when the step pays money, sends a message or email, posts publicly, signs in or enters a password, " +
        "or deletes the user's files — the user will be asked first. Prefer shell for files, installs and downloads; use the screen for websites and apps. " +
        "Be efficient, verify results on screen, and never invent results."

    private suspend fun loop(container: AppContainer, c: Context, pcId: String, request: String): String {
        if (!PcEnv.alive() || PcEnv.running != pcId) { status = "Starting the PC"; withContext(Dispatchers.Main) { PcEnv.start(c, pcId) } }
        Vnc.connect()
        var waited = 0
        while (!Vnc.connected && waited < 60) { delay(1000); waited++ }
        if (!Vnc.connected) return "The PC's screen didn't come up. Open Atlas's PC and try starting it again."
        delay(1500)
        val memory = StringBuilder()
        for (step in 1..80) {
            while (paused) delay(500)
            status = "Looking at the screen (step $step)"
            val shot: Bitmap = Vnc.snapshot() ?: return "No screen to look at."
            val scale = 1024f / maxOf(shot.width, shot.height)
            val sw = (shot.width * minOf(1f, scale)).toInt(); val sh = (shot.height * minOf(1f, scale)).toInt()
            val prompt = system(sw, sh) + "\n\nTASK: $request\n\nWHAT YOU'VE DONE SO FAR:\n" + memory.toString().takeLast(5000).ifBlank { "(nothing yet)" } + "\n\nYour next action (JSON):"
            val raw = com.nizam.app.net.Vision.ask(container.settingsRepository, prompt, shot)
            val a = parse(raw) ?: run { memory.appendLine("($step) [reply wasn't JSON, try again]"); null } ?: continue
            val action = a.optString("action")
            val think = a.optString("think").take(200)
            fun px(k: String) = (a.optDouble(k, 0.0) / minOf(1f, scale)).toInt()
            if (a.optBoolean("risky")) {
                status = "Waiting for your OK"
                val d = CompletableDeferred<Boolean>(); confirmReply = d
                confirm = (a.optString("reason").ifBlank { think }).ifBlank { "This step needs your OK" } + "\n→ " + describe(a)
                if (!d.await()) { memory.appendLine("($step) The user said NO to: ${describe(a)}. Find another way or stop."); log("✕ You declined: ${describe(a)}", false); continue }
            }
            status = think.ifBlank { describe(a) }
            val result = withContext(Dispatchers.IO) { when (action) {
                "click" -> { cursor = px("x") to px("y"); Vnc.click(px("x"), px("y")); "clicked" }
                "double_click" -> { cursor = px("x") to px("y"); Vnc.click(px("x"), px("y"), times = 2); "double-clicked" }
                "right_click" -> { cursor = px("x") to px("y"); Vnc.click(px("x"), px("y"), button = 3); "right-clicked" }
                "move" -> { cursor = px("x") to px("y"); Vnc.pointer(px("x"), px("y"), 0); "moved" }
                "drag" -> { cursor = px("x2") to px("y2"); Vnc.drag(px("x"), px("y"), px("x2"), px("y2")); "dragged" }
                "scroll" -> { Vnc.scroll(px("x"), px("y"), a.optInt("amount", 3)); "scrolled" }
                "type" -> { Vnc.type(a.optString("text")); "typed" }
                "key" -> { Vnc.combo(a.optString("keys")); "pressed ${a.optString("keys")}" }
                "shell" -> { val r = PcEnv.shell(c, pcId, a.optString("command"), 300); "exit ${r.first}: " + r.second.takeLast(2500) }
                "open" -> {
                    val target = a.optString("url").ifBlank { a.optString("app") }
                    val cmd = if (target.startsWith("http")) "(xdg-open '$target' || firefox '$target' || firefox-esr '$target' || falkon '$target') >/dev/null 2>&1 &" else "($target) >/dev/null 2>&1 &"
                    PcEnv.shell(c, pcId, cmd, 20); "opened $target"
                }
                "wait" -> { delay((a.optDouble("seconds", 2.0) * 1000).toLong().coerceIn(500, 20_000)); "waited" }
                "ask" -> {
                    val d = CompletableDeferred<String>(); questionReply = d; question = a.optString("question"); status = "Waiting for your answer"
                    "user answered: " + d.await()
                }
                "done" -> return@withContext null
                else -> "unknown action $action"
            } } ?: return a.optString("summary").ifBlank { think.ifBlank { "Done." } }
            log((if (think.isNotBlank()) "$think → " else "") + describe(a))
            memory.appendLine("($step) ${describe(a)} → ${result.take(600)}")
            delay(if (action in setOf("click", "double_click", "key", "open", "type")) 1200 else 600)
        }
        return "I stopped after many steps; tell me to continue if it isn't finished."
    }

    private fun describe(a: JSONObject): String = when (a.optString("action")) {
        "click", "double_click", "right_click", "move" -> "${a.optString("action").replace('_', ' ')} at ${a.optInt("x")},${a.optInt("y")}"
        "type" -> "type \"${a.optString("text").take(60)}\""
        "key" -> "press ${a.optString("keys")}"
        "shell" -> "$ ${a.optString("command").take(120)}"
        "open" -> "open ${a.optString("url").ifBlank { a.optString("app") }}"
        "scroll" -> "scroll ${a.optInt("amount", 3)}"
        else -> a.optString("action")
    }

    private fun parse(raw: String): JSONObject? {
        val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val s = t.indexOf('{'); val e = t.lastIndexOf('}')
        return if (s < 0 || e <= s) null else runCatching { JSONObject(t.substring(s, e + 1)) }.getOrNull()
    }
}
