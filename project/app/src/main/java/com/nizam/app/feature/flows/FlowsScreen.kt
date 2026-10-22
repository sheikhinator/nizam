package com.nizam.app.feature.flows

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.connect.Connectors
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun trigSentence(f: JSONObject): String = FlowKinds.sentence(FlowKinds.trigger(f.optJSONObject("trigger")?.optString("type").orEmpty()), f.optJSONObject("trigger") ?: JSONObject())

@Composable
private fun Box2(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
fun FlowsScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var version by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf("list") }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var historyFlow by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(FlowEngine.paused(c)) }
    var words by remember { mutableStateOf("") }
    var building by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<JSONObject?>(null) }
    var appName by remember { mutableStateOf("") }
    var connectorDraft by remember { mutableStateOf<JSONObject?>(null) }
    var token by remember { mutableStateOf("") }
    var openStep by remember { mutableIntStateOf(-1) }
    val flows = remember(version) { FlowEngine.all(c) }
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM, h:mm a") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Flows", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Your own automations. Each flow starts with a trigger and runs steps, all on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(!paused, { paused = !it; FlowEngine.setPaused(c, paused) })
                Text(if (paused) "Paused" else "On", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (view != "list") TextButton(onClick = { view = "list"; editing = null; version++ }) { Text("← Back to flows") }

        when (view) {
            "list" -> {
                Box2 {
                    Text("Describe what you want", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(words, { words = it }, placeholder = { Text("Every morning, summarise my unread bank messages and tell me") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Button(enabled = words.isNotBlank() && !building, onClick = {
                        building = true; note = "Designing it…"; draft = null
                        scope.launch {
                            runCatching { FlowAi.build(container, words.trim()) }.onSuccess { draft = it; note = "Here is my draft. Check it, then save." }.onFailure { note = it.message.orEmpty() }
                            building = false
                        }
                    }) { Text(if (building) "Working…" else "Build it for me") }
                    if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                draft?.let { d ->
                    Box2 {
                        Text(d.optString("name"), fontWeight = FontWeight.SemiBold)
                        Text("When: " + trigSentence(d), style = MaterialTheme.typography.bodySmall)
                        val st = d.optJSONArray("steps") ?: JSONArray()
                        for (i in 0 until st.length()) Text("${i + 1}. " + FlowKinds.sentence(FlowKinds.step(st.getJSONObject(i).optString("type")), st.getJSONObject(i)), style = MaterialTheme.typography.bodySmall)
                        Text("It starts switched off and asks before it acts.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { FlowEngine.save(c, d); draft = null; words = ""; note = "Saved."; version++ }) { Text("Save") }
                            TextButton(onClick = { editing = d; draft = null; view = "edit" }) { Text("Edit first") }
                            TextButton(onClick = { draft = null }) { Text("Discard") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your flows", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editing = FlowEngine.newFlow(); view = "edit" }) { Text("New by hand") }
                    TextButton(onClick = { FlowEngine.tick(c); note = "Checked what is due." }) { Text("Run due now") }
                }
                if (flows.isEmpty()) Text("No flows yet. Describe one above.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                flows.forEach { f ->
                    Box2 {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.optString("name"), fontWeight = FontWeight.SemiBold)
                                Text("When: " + trigSentence(f) + "  ·  ${f.optJSONArray("steps")?.length() ?: 0} steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(f.optBoolean("enabled"), { v -> FlowEngine.save(c, JSONObject(f.toString()).put("enabled", v)); version++ })
                        }
                        Row {
                            TextButton(onClick = { FlowEngine.runNow(c, f.optString("id")); note = "Started ${f.optString("name")}." }) { Text("Run") }
                            TextButton(onClick = { editing = JSONObject(f.toString()); view = "edit" }) { Text("Edit") }
                            TextButton(onClick = { historyFlow = f.optString("id"); view = "history" }) { Text("History") }
                            TextButton(onClick = { FlowEngine.delete(c, f.optString("id")); version++ }) { Text("Delete") }
                        }
                    }
                }
                Box2 {
                    Text("Connect any app", fontWeight = FontWeight.SemiBold)
                    Text("If an app has a web API with a personal key, Atlas can talk to it directly. Say its name and I will work out how.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { view = "connect" }) { Text("Connect an app") }
                }
                Box2 {
                    Text("Relay channel", fontWeight = FontWeight.SemiBold)
                    val prefs = remember { c.getSharedPreferences("flows", 0) }
                    var topic by remember { mutableStateOf(prefs.getString("my_topic", null) ?: Relay.suggestTopic().also { prefs.edit().putString("my_topic", it).apply() }) }
                    Text("A private channel other apps can post to, such as Zapier, Pipedream or a bank alert forwarder. A flow with the trigger \"A message on a relay channel\" runs when something arrives.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Relay.base(c)}/$topic", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Relay", "${Relay.base(c)}/$topic")); note = "Copied. In Zapier use Webhooks, POST, with this address." }) { Text("Copy address") }
                        TextButton(onClick = { topic = Relay.suggestTopic(); prefs.edit().putString("my_topic", topic).apply() }) { Text("New channel") }
                    }
                    Text("Anyone who knows the address can post to it, so keep it private.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            "edit" -> editing?.let { f ->
                val bump = version
                OutlinedTextField(f.optString("name"), { f.put("name", it); version++ }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow this flow to act without asking", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(f.optBoolean("auto"), { f.put("auto", it); version++ })
                }
                Text("When", fontWeight = FontWeight.SemiBold)
                val trig = f.optJSONObject("trigger") ?: JSONObject().put("type", "manual").also { f.put("trigger", it) }
                FlowKinds.triggers.forEach { k ->
                    TextButton(onClick = { f.put("trigger", JSONObject().put("type", k.type)); version++ }) {
                        Text((if (trig.optString("type") == k.type) "● " else "○ ") + k.label, fontWeight = if (trig.optString("type") == k.type) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                FlowKinds.trigger(trig.optString("type"))?.params?.forEach { p ->
                    OutlinedTextField(trig.optString(p.key), { trig.put(p.key, it); version++ }, label = { Text(p.label) }, placeholder = { if (p.hint.isNotBlank()) Text(p.hint) }, modifier = Modifier.fillMaxWidth())
                }
                Text("Then", fontWeight = FontWeight.SemiBold)
                val steps = f.optJSONArray("steps") ?: JSONArray().also { f.put("steps", it) }
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    val kind = FlowKinds.step(s.optString("type"))
                    Box2 {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. " + FlowKinds.sentence(kind, s), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { openStep = if (openStep == i) -1 else i }) { Text(if (openStep == i) "Close" else "Edit") }
                        }
                        if (openStep == i) {
                            kind?.params?.forEach { p ->
                                OutlinedTextField(s.optString(p.key), { s.put(p.key, it); version++ }, label = { Text(p.label) }, placeholder = { if (p.hint.isNotBlank()) Text(p.hint) },
                                    minLines = if (p.long) 2 else 1, modifier = Modifier.fillMaxWidth())
                            }
                            Row {
                                TextButton(enabled = i > 0, onClick = { val a = steps.getJSONObject(i - 1); steps.put(i - 1, s); steps.put(i, a); openStep = i - 1; version++ }) { Text("Up") }
                                TextButton(enabled = i < steps.length() - 1, onClick = { val b = steps.getJSONObject(i + 1); steps.put(i + 1, s); steps.put(i, b); openStep = i + 1; version++ }) { Text("Down") }
                                TextButton(onClick = { steps.remove(i); openStep = -1; version++ }) { Text("Remove") }
                            }
                        }
                    }
                }
                Text("Add a step", fontWeight = FontWeight.SemiBold)
                FlowKinds.steps.forEach { k ->
                    TextButton(onClick = { steps.put(JSONObject().put("type", k.type)); openStep = steps.length() - 1; version++ }) { Text("+ ${k.label}") }
                }
                Row {
                    Button(onClick = { FlowEngine.save(c, f); editing = null; view = "list"; version++; note = "Saved." }) { Text("Save") }
                    TextButton(onClick = { editing = null; view = "list" }) { Text("Cancel") }
                }
                if (bump < 0) Text("")
            }

            "history" -> {
                val list = remember(version, historyFlow) { FlowEngine.history(c, historyFlow) }
                if (list.isEmpty()) Text("It has not run yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.take(30).forEach { r ->
                    Box2 {
                        Text("${r.optString("status").uppercase()}  ·  " + Instant.ofEpochMilli(r.optLong("t")).atZone(ZoneId.systemDefault()).format(fmt), fontWeight = FontWeight.SemiBold)
                        val l = r.optJSONArray("log") ?: JSONArray()
                        for (i in 0 until l.length()) Text(l.optString(i), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            "connect" -> {
                Text("Say which app. I will look up its official web API and draft the connection.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(appName, { appName = it }, label = { Text("App name (for example Todoist)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = appName.isNotBlank() && !building, onClick = {
                    building = true; note = "Looking it up…"; connectorDraft = null
                    scope.launch {
                        runCatching { FlowAi.connector(container, appName.trim()) }.onSuccess { connectorDraft = it; note = "" }.onFailure { note = it.message.orEmpty() }
                        building = false
                    }
                }) { Text(if (building) "Working…" else "Find out how") }
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
                connectorDraft?.let { d ->
                    Box2 {
                        Text(d.optString("name"), fontWeight = FontWeight.SemiBold)
                        Text("Address: ${d.optString("base")}", style = MaterialTheme.typography.bodySmall)
                        Text("Sign-in: ${d.optString("auth")}", style = MaterialTheme.typography.bodySmall)
                        Text(d.optString("notes"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (d.optString("tokenUrl").isNotBlank()) Text("Make the key here: ${d.optString("tokenUrl")}", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(token, { token = it }, label = { Text("Paste the key or token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(enabled = token.isNotBlank(), onClick = {
                        runCatching { Connectors.addCustom(c, d.optString("name"), d.optString("base"), d.optString("auth"), d.optString("test"), d.optString("notes"), token.trim()) }
                            .onSuccess { note = "Connected ${d.optString("name")}. Use it in a flow with the step \"Use a connected app\"."; token = ""; connectorDraft = null }
                            .onFailure { note = "Could not save: ${it.message}" }
                    }) { Text("Connect") }
                }
            }
        }
        Text("", Modifier.padding(bottom = 40.dp))
    }
}

object FlowsSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("flows", "Flows", "Build your own automations: triggers, steps, apps and AI, all on this phone", Icons.Outlined.AccountTree, Hub.Tools,
            "automation workflow n8n zapier ifttt trigger rules zap relay webhook connect apps") { container, _ -> FlowsScreen(container) },
    )
}
