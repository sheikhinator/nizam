package com.nizam.app.feature.jarvis

import android.app.Activity
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.attach.Attachments
import com.nizam.app.feature.phone.PhoneActions
import com.nizam.app.feature.voice.speechIntent
import com.nizam.app.net.Ai
import com.nizam.app.net.Streaming
import com.nizam.app.ui.components.ChatText
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

// ── Call & negotiation coach ─────────────────────────────────────────────────────────────────────

/**
 * Before a call: the opening line, what to ask for, your walk-away point, and answers to the
 * objections they'll raise. After it: say what happened, and your commitments become tasks with
 * dates, theirs become follow-ups, and the thank-you / confirmation message is drafted.
 */
@Composable
fun CallCoachScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberWorkScope()
    var tab by remember { mutableStateOf("Before") }
    var who by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var prep by remember { mutableStateOf("") }
    var debrief by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.let { debrief = (debrief + " " + it).trim() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Call coach", "Walk in prepared, walk out with everything captured")
        ChipRow(listOf("Before", "After"), tab, { tab = it })
        OutlinedTextField(who, { who = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Who with") },
            placeholder = { Text("Landlord, a client, HR, the car dealer…") })
        if (tab == "Before") {
            OutlinedTextField(goal, { goal = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("What you want from it") },
                placeholder = { Text("Get rent kept at 60k for another year; I can agree to 62k max") })
            Button(enabled = who.isNotBlank() && goal.isNotBlank() && !busy, modifier = Modifier.padding(top = 8.dp), onClick = {
                busy = true; prep = ""
                scope.launch {
                    runCatching {
                        Streaming.reply(container.settingsRepository,
                            "${Snapshot.of(container)}\n\nThey have a call or meeting with: $who\nWhat they want: $goal\n\n" +
                                "Prepare them. Use markdown headings: **Opening line** (word for word), **What to ask for** (anchor high, " +
                                "specific numbers), **Walk-away point**, **Their likely objections → your answer** (3–4), **Silence and " +
                                "concessions** (what to trade, what never to give), **Close** (the exact commitment to get). " +
                                "Pakistani context and courtesy where it fits. Tight — they'll read it 2 minutes before the call.",
                            "You are an elite negotiation and communication coach.", 1400, context = c) { prep += it }
                    }.onFailure { prep = "Couldn't prepare: ${Feedback.friendly(it)}" }
                    busy = false
                }
            }) { Text("Prepare me") }
            if (busy && prep.isBlank()) Thinking(label = "Preparing your brief")
            if (prep.isNotBlank()) {
                ChatText(prep, Modifier.padding(vertical = 10.dp))
                OutlinedButton(onClick = {
                    scope.launch { container.noteRepository.createFull("Call prep — $who (${LocalDate.now()})", prep); Feedback.show("Saved to Notes") }
                }) { Text("Save to notes") }
            }
        } else {
            OutlinedTextField(debrief, { debrief = it }, modifier = Modifier.fillMaxWidth(), minLines = 4,
                label = { Text("What happened") }, placeholder = { Text("Say it or type it — who agreed to what, by when") })
            Row {
                OutlinedButton(onClick = { listen.launch(speechIntent()) }) { Text("🎙 Speak it") }
                Spacer(Modifier.width(10.dp))
                Button(enabled = who.isNotBlank() && debrief.isNotBlank() && !busy, onClick = {
                    busy = true; result = null
                    scope.launch {
                        runCatching {
                            val raw = Ai.generate(container.settingsRepository,
                                "Today is ${LocalDate.now()}. Call with $who. Their account of it:\n\"$debrief\"\n\nJSON only:\n" +
                                    "{\"summary\":\"2-3 sentences\",\"mine\":[{\"task\":\"what I committed to\",\"due\":\"YYYY-MM-DD or empty\"}]," +
                                    "\"theirs\":[{\"what\":\"what they committed to\",\"due\":\"YYYY-MM-DD or empty\"}]," +
                                    "\"followUp\":\"a short, warm confirmation message to send $who restating what was agreed\"}",
                                "You capture commitments precisely. Resolve relative dates like 'next Friday' to real dates.", maxOutputTokens = 900)
                            JSONObject(Ai.cleanJson(raw))
                        }.onSuccess { o ->
                            result = o
                            val zone = ZoneId.systemDefault()
                            fun due(s: String) = runCatching { LocalDate.parse(s).atTime(10, 0).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                            o.optJSONArray("mine")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let {
                                container.taskRepository.add(it.optString("task"), due(it.optString("due")), 2) } }
                            o.optJSONArray("theirs")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let {
                                container.taskRepository.add("Follow up: $who — ${it.optString("what")}", due(it.optString("due")), 1) } }
                            container.noteRepository.createFull("Call — $who (${LocalDate.now()})", o.optString("summary") + "\n\n" + debrief)
                            Timeline.log(c, "call", "Call with $who", o.optString("summary").take(200))
                            Feedback.show("Commitments added to Tasks")
                        }.onFailure { Feedback.failed("Capturing the call", it) }
                        busy = false
                    }
                }) { Text("Capture it") }
            }
            if (busy) Thinking(label = "Pulling out commitments")
            result?.let { o ->
                Text(o.optString("summary"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
                fun lines(k: String, f: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                    ?.map { it.optString(f) + it.optString("due").let { d -> if (d.isNotBlank()) " · by $d" else "" } } ?: emptyList()
                lines("mine", "task").forEach { Text("☐ You: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                lines("theirs", "what").forEach { Text("↻ $who: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                val msg = o.optString("followUp")
                if (msg.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 12.dp))
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    Text("Send on WhatsApp", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(role = Role.Button) {
                        Feedback.show(PhoneActions.whatsapp(c, who, msg).message)
                    }.padding(vertical = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ── Receipts ────────────────────────────────────────────────────────────────────────────────────

@Composable
fun ReceiptsScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberWorkScope()
    var receipt by remember { mutableStateOf<Receipt?>(null) }
    var image by remember { mutableStateOf<com.nizam.app.feature.attach.Attachment?>(null) }
    var split by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<List<String>>(emptyList()) }
    var history by remember { mutableStateOf(Receipts.all(c)) }

    fun read(a: com.nizam.app.feature.attach.Attachment?) {
        if (a == null) return
        image = a; receipt = null; done = emptyList(); busy = true
        scope.launch {
            runCatching { Receipts.read(container, a) }.onSuccess { receipt = it }.onFailure { Feedback.failed("Reading the receipt", it) }
            busy = false
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { read(withContext(Dispatchers.IO) { Attachments.fromUri(c, uri) }) }
    }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) scope.launch { read(withContext(Dispatchers.IO) { Attachments.fromBitmap(c, bmp, "receipt.jpg") }) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Receipts", "One photo: expense logged, split worked out, returns and warranty remembered")
        Row {
            Button(onClick = { shoot.launch(null) }) { Text("Take photo") }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("From gallery") }
        }
        if (busy) Thinking(label = "Reading the receipt")
        receipt?.let { r ->
            Text(r.merchant, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 14.dp))
            Text("${com.nizam.app.core.fmtAmount(r.total)} · ${r.category} · ${r.date}", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            r.items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            if (r.returnDays > 0) Text("Return window: ${r.returnDays} days", style = MaterialTheme.typography.labelMedium)
            if (r.warrantyMonths > 0) Text("Warranty: ${r.warrantyMonths} months", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(split, { split = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Split with (optional)") }, placeholder = { Text("Ali, Hamza") })
            if (done.isEmpty()) Button(modifier = Modifier.padding(top = 8.dp), onClick = {
                scope.launch {
                    val people = split.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    done = runCatching { Receipts.file(container, r, people, image?.file) }.getOrElse { listOf("Failed: ${Feedback.friendly(it)}") }
                    history = Receipts.all(c)
                }
            }) { Text("File it") }
            done.forEach { Text("✓ $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            val people = split.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (done.isNotEmpty() && people.isNotEmpty()) people.forEach { p ->
                Text("Send $p their share on WhatsApp", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(role = Role.Button) {
                    Feedback.show(PhoneActions.whatsapp(c, p, Receipts.splitMessage(r, r.total / (people.size + 1))).message)
                }.padding(vertical = 6.dp))
            }
        }
        if (history.isNotEmpty()) {
            Text("FILED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
            history.take(30).forEach { o ->
                Text("${o.optString("date")} · ${o.optString("merchant")} · ${com.nizam.app.core.fmtAmount(o.optDouble("total"))}" +
                    (if (o.optInt("warrantyMonths") > 0) " · warranty ${o.optInt("warrantyMonths")}m" else ""),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}
