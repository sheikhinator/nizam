package com.nizam.app.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.OutlinedButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The skills library: record, write, save, run, repair. */
@Composable
fun SkillsProScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val v = SkillsPro.version
    val skills = remember(v) { SkillsPro.all(c) }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    var draft by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf("") }
    var describe by remember { mutableStateOf("") }
    var recorded by remember { mutableStateOf<List<JSONObject>?>(null) }

    // a recording just ended (from the floating pill) — turn it into a skill
    androidx.compose.runtime.LaunchedEffect(DemoRecorder.recording, DemoRecorder.count) { com.nizam.app.ui.design.guard {
        if (!DemoRecorder.recording && recorded == null && DemoRecorder.count > 0) {
            val steps = DemoRecorder.stop(); DemoRecorder.count = 0
            if (steps.isNotEmpty()) recorded = steps
        }
    } }
    draft?.let { d -> SkillEditor(container, d, onDone = { draft = null }); return }
    open?.let { s -> SkillDetail(container, s, back = { open = null }, edit = { draft = JSONObject(s.toString()); open = null }); return }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Skills", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Things that worked once, saved so the next run starts from done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Art3D("magic_wand", 60.dp)
        } }
        // teach by showing
        item { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFFDC2626).copy(alpha = 0.16f), MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)))).padding(16.dp)) {
            Text("🎬 Teach by showing", style = MaterialTheme.typography.titleMedium)
            Text("Tap Record, do the task once in any app, then tap the red pill. Atlas learns the steps, turns names and messages into inputs, and marks sends and payments for approval. Passwords and banking apps are never recorded.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(hint, { hint = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, placeholder = { Text("What is it for? (optional) e.g. order my usual from foodpanda") })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!DemoRecorder.recording) Button(onClick = {
                    val err = DemoRecorder.start(c)
                    if (err != null) Feedback.show(err) else { Feedback.show("Recording — do the task, then tap the red pill"); c.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("● Record") }
                else Button(onClick = { recorded = DemoRecorder.stop(); DemoRecorder.count = 0 }) { Text("■ Stop (${DemoRecorder.count} steps)") }
            }
        } }
        recorded?.let { r -> item { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(14.dp)) {
            Text("Recorded ${r.size} steps", style = MaterialTheme.typography.titleSmall)
            r.take(8).forEach { Text("• " + SkillsPro.describe(it), style = MaterialTheme.typography.bodySmall) }
            if (r.size > 8) Text("… and ${r.size - 8} more", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { busy = "Learning from your demo…"; scope.launch {
                    runCatching { SkillsPro.fromDemo(container, r, hint) }.onSuccess { draft = it; recorded = null; hint = "" }.onFailure { Feedback.failed("Learning", it) }; busy = null } },
                    enabled = busy == null) { Text("Turn into a skill") }
                OutlinedButton(onClick = { recorded = null }) { Text("Discard") }
            }
        } } }
        // write it in words, or keep what the agent just did
        item { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)).padding(14.dp)) {
            Text("✍️ Describe a skill", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(describe, { describe = it }, Modifier.fillMaxWidth().padding(top = 6.dp), minLines = 2,
                placeholder = { Text("Every time I say 'leaving office': message Ammi on WhatsApp, set a 30-min reminder to buy bread, open Careem to home") })
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { busy = "Writing it…"; scope.launch { runCatching { SkillsPro.write(container, describe) }.onSuccess { draft = it; describe = "" }
                    .onFailure { Feedback.failed("Writing", it) }; busy = null } }, enabled = describe.isNotBlank() && busy == null) { Text("Write it") }
                OutlinedButton(onClick = { busy = "Saving your last run…"; scope.launch {
                    runCatching { SkillsPro.fromRecent(container, System.currentTimeMillis() - 30 * 60_000L, "what I just asked for") }
                        .onSuccess { if (it == null) Feedback.show("Nothing ran in the last 30 minutes") else draft = it }.onFailure { Feedback.failed("Saving", it) }
                    busy = null } }, enabled = busy == null) { Text("Save my last run") }
            }
        } }
        busy?.let { b -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  $b") } } }
        if (skills.isNotEmpty()) item { Text("Your skills", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        itemsIndexed(skills, key = { _, s -> s.optString("id") }) { _, s ->
            val ok = s.optInt("okRuns"); val runs = s.optInt("runs")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)).clickable { open = s }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Text(when (s.optString("source")) { "demo" -> "🎬"; "agent" -> "🤖"; "written" -> "✍️"; else -> "⚡" }) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.optString("name"), style = MaterialTheme.typography.titleSmall)
                    Text(s.optString("desc").ifBlank { "${SkillsPro.steps(s).size} steps" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                if (runs > 0) Text("${ok * 100 / runs}%", style = MaterialTheme.typography.labelLarge, color = if (ok * 2 >= runs) Color(0xFF16A34A) else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SkillDetail(container: AppContainer, s0: JSONObject, back: () -> Unit, edit: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var s by remember { mutableStateOf(s0) }
    val inputs = remember { mutableStateMapOf<String, String>().apply { SkillsPro.params(s0).forEach { put(it.optString("key"), it.optString("example")) } } }
    var running by remember { mutableStateOf(false) }
    var at by remember { mutableIntStateOf(-1) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var ask by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
    ask?.let { (label, d) -> AlertDialog(onDismissRequest = { d.complete(false); ask = null },
        title = { Text("Go ahead?") }, text = { Text(label) },
        confirmButton = { TextButton(onClick = { d.complete(true); ask = null }) { Text("Approve") } },
        dismissButton = { TextButton(onClick = { d.complete(false); ask = null }) { Text("Stop") } }) }
    val steps = SkillsPro.steps(s)
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("‹ Skills", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { back() }.padding(vertical = 6.dp)) }
        item { Text(s.optString("name"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text(s.optString("desc"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (s.optString("when").isNotBlank()) item { Text("Triggers: ${s.optString("when")}", style = MaterialTheme.typography.labelMedium) }
        if (s.optString("rules").isNotBlank()) item { Text("Rules: ${s.optString("rules")}", style = MaterialTheme.typography.labelMedium) }
        item { Text("${s.optInt("runs")} runs · ${s.optInt("okRuns")} worked" + s.optString("last").takeIf { it.isNotBlank() }?.let { " · last $it" }.orEmpty(),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (SkillsPro.params(s).isNotEmpty()) item { Text("Inputs", style = MaterialTheme.typography.titleSmall) }
        SkillsPro.params(s).forEach { p -> item(p.optString("key")) {
            OutlinedTextField(inputs[p.optString("key")].orEmpty(), { inputs[p.optString("key")] = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(p.optString("label").ifBlank { p.optString("key") }) })
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { running = true; log = emptyList(); scope.launch {
                val r = SkillsPro.run(container, s, inputs.toMap(), 0, confirm = { label -> val d = CompletableDeferred<Boolean>(); ask = label to d; d.await() },
                    progress = { i, _ -> at = i })
                log = r.log; running = false; at = -1; s = SkillsPro.all(c).firstOrNull { it.optString("id") == s.optString("id") } ?: s
                Feedback.show(if (r.ok) "Done ✓" else "Stopped — see the log")
            } }, enabled = !running) { Text(if (running) "Running…" else "▶ Run") }
            OutlinedButton(onClick = edit) { Text("Edit") }
            OutlinedButton(onClick = { scope.launch { log = listOf("🧪 Simulating…"); log = listOf(runCatching { com.nizam.app.feature.wild.DryRun.explain(container,
                "Skill: ${s.optString("name")}\nInputs: ${inputs.toMap()}\nRules: ${s.optString("rules")}\nSteps:\n" + SkillsPro.steps(s).mapIndexed { i, st -> "${i + 1}. " + SkillsPro.describe(st) }.joinToString("\n")) }
                .getOrElse { Feedback.friendly(it) }) } }) { Text("Dry run") }
            OutlinedButton(onClick = { scope.launch { runCatching { SkillsPro.repair(container, s) }.onSuccess { SkillsPro.save(c, it); s = it; Feedback.show("Repaired — new version saved") }
                .onFailure { Feedback.failed("Repair", it) } } }) { Text("Repair") }
        } }
        item { Text("Steps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        itemsIndexed(steps) { i, st ->
            val cur = i == at
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (cur) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
                Text(SkillsPro.describe(st), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (cur) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        val shown = log.ifEmpty { s.optJSONArray("lastLog")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList() }
        if (shown.isNotEmpty()) { item { Text("Last run", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            shown.forEachIndexed { i, l -> item("l$i") { Text(l, style = MaterialTheme.typography.labelMedium) } } }
        val versions = s.optJSONArray("versions")
        if (versions != null && versions.length() > 0) item {
            Text("${versions.length()} earlier versions — restore the newest", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { val o = versions.getJSONObject(0); val n = JSONObject(s.toString()).put("steps", o.optJSONArray("steps")).put("rules", o.optString("rules"))
                    SkillsPro.save(c, n); s = n; Feedback.show("Restored") }.padding(vertical = 8.dp))
        }
        item { Text("Delete skill", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { SkillsPro.delete(c, s.optString("id")); back() }.padding(vertical = 12.dp)) }
    }
}

/** Review a new or edited skill: rename, set rules, choose which steps need approval, drop steps. */
@Composable
private fun SkillEditor(container: AppContainer, d: JSONObject, onDone: () -> Unit) {
    val c = LocalContext.current
    var name by remember { mutableStateOf(d.optString("name")) }
    var desc by remember { mutableStateOf(d.optString("desc")) }
    var trig by remember { mutableStateOf(d.optString("when")) }
    var rules by remember { mutableStateOf(d.optString("rules")) }
    var steps by remember { mutableStateOf(SkillsPro.steps(d)) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Review the skill", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true) }
        item { OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("What it does") }) }
        item { OutlinedTextField(trig, { trig = it }, Modifier.fillMaxWidth(), label = { Text("Say any of these to run it (comma-separated)") }) }
        item { OutlinedTextField(rules, { rules = it }, Modifier.fillMaxWidth(), label = { Text("Rules it follows") }, minLines = 2) }
        if (SkillsPro.params(d).isNotEmpty()) item { Text("Inputs: " + SkillsPro.params(d).joinToString { "{{${it.optString("key")}}}" }, style = MaterialTheme.typography.labelMedium) }
        item { Text("Steps — tap 🔒 to require your OK, ✕ to drop", style = MaterialTheme.typography.titleSmall) }
        itemsIndexed(steps) { i, st ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. " + SkillsPro.describe(st).substringBefore("  ·  needs"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(if (st.optBoolean("approve")) "🔒" else "🔓", modifier = Modifier.clickable {
                    steps = steps.mapIndexed { j, x -> if (j == i) JSONObject(x.toString()).put("approve", !x.optBoolean("approve")) else x } }.padding(6.dp))
                Text("✕", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { steps = steps.filterIndexed { j, _ -> j != i } }.padding(6.dp))
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = {
                SkillsPro.save(c, JSONObject(d.toString()).put("name", name.trim()).put("desc", desc).put("when", trig).put("rules", rules).put("steps", JSONArray(steps)))
                Feedback.show("Skill saved — say “${trig.split(',').firstOrNull()?.trim()?.ifBlank { name } ?: name}” or ask Atlas to run it"); onDone()
            }, enabled = name.isNotBlank() && steps.isNotEmpty()) { Text("Save skill") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        } }
    }
}
