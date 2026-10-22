package com.nizam.app.feature.brain2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.AppContainer
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.HoldToConfirm
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.launch

@Composable
fun FirewallScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    AtlasScreen(title = "Agent firewall", subtitle = "Switch off whole categories. Atlas can't talk its way past these.") {
        Firewall.RULES.forEach { r ->
            item(r.key + tick) {
                val blocked = Firewall.blocked(ctx, r.key)
                ListRow(r.label, supporting = (if (blocked) "Blocked · " else "Allowed · ") + r.blurb, icon = Icons.Outlined.Shield,
                    tint = if (blocked) MaterialTheme.colorScheme.error else Hub.Tools.tint(),
                    trailing = { Switch(!blocked, { v -> Firewall.set(ctx, r.key, !v); tick++ }) })
            }
        }
        item("note") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Text("Blocked attempts still appear in Activity, so you can see what Atlas tried. Content from websites and messages is also " +
                    "checked for hidden instructions before the agent sees it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SkillsScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var skills by remember { mutableStateOf(Skills.store(ctx).all()) }
    val recent = remember { Skills.recent(ctx) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var name by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<String?>(null) }
    fun reload() { skills = Skills.store(ctx).all() }
    AtlasScreen(title = "Skills", subtitle = "Things Atlas did that you saved, replayed in one tap or by name (\"run morning routine\")") {
        if (skills.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.AutoFixHigh, "No skills yet", "Ask Atlas to do a few things, then pick those steps below and save them as a skill.")) }
        skills.forEach { s ->
            item(s.optString("id")) {
                val steps = s.optJSONArray("steps")?.length() ?: 0
                ListRow(s.optString("name"), supporting = "$steps step${if (steps == 1) "" else "s"} · run ${s.optInt("runs")} times",
                    icon = Icons.Outlined.PlayArrow, trailing = {
                        Text("Delete", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(Space.s).pressable { Skills.store(ctx).remove(s.optString("id")); reload() })
                    }) {
                    running = s.optString("name")
                    scope.launch { val r = Skills.run(ctx, container, s); running = null; Feedback.show(r.take(120)); reload() }
                }
            }
        }
        if (running != null) item("run") { Text("Running $running…", Modifier.padding(horizontal = Space.page), style = MaterialTheme.typography.labelLarge) }
        item("rh") { SectionHeader("Recent actions") }
        if (recent.isEmpty()) item("re") { ListRow("Nothing yet", supporting = "Actions Atlas runs for you appear here") }
        recent.forEach { a ->
            item("r" + a.optString("id")) {
                val on = a.optString("id") in picked
                ListRow(a.optString("a").replace('_', ' '), supporting = a.optJSONObject("args")?.toString()?.take(80),
                    icon = if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked) {
                    picked = if (on) picked - a.optString("id") else picked + a.optString("id")
                }
            }
        }
        if (picked.isNotEmpty()) item("save") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name this skill") }, singleLine = true)
                Button(onClick = {
                    if (name.isNotBlank()) {
                        Skills.save(ctx, name, recent.filter { it.optString("id") in picked }); picked = emptySet(); name = ""; reload(); Feedback.show("Skill saved")
                    }
                }, modifier = Modifier.padding(top = Space.s)) { Text("Save ${picked.size} steps") }
            }
        }
    }
}

@Composable
fun PersonalModelScreen() {
    val ctx = LocalContext.current
    var facts by remember { mutableStateOf(PersonalModel.facts(ctx)) }
    var lessons by remember { mutableStateOf(PersonalModel.lessons(ctx)) }
    val log = remember(facts, lessons) { PersonalModel.changelog(ctx) }
    var add by remember { mutableStateOf("") }
    fun reload() { facts = PersonalModel.facts(ctx); lessons = PersonalModel.lessons(ctx) }
    AtlasScreen(title = "What Atlas knows", subtitle = "Every belief about you, in plain words. Delete any line and it's forgotten.") {
        item("fh") { SectionHeader("About you") }
        if (facts.isEmpty()) item("fe") { ListRow("Nothing yet", supporting = "Add something below, or Atlas learns as you use it") }
        facts.forEach { f ->
            item("f$f") {
                ListRow(f, icon = Icons.Outlined.Person, trailing = {
                    Text("Forget", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(Space.s).pressable { PersonalModel.forget(ctx, f); reload() })
                })
            }
        }
        item("add") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(add, { add = it }, Modifier.weight(1f), placeholder = { Text("e.g. I work 9–5 in Gulberg") }, singleLine = true)
                Button(onClick = { PersonalModel.addFact(ctx, add); add = ""; reload() }, enabled = add.isNotBlank()) { Text("Add") }
            }
        }
        item("lh") { SectionHeader("Lessons Atlas learned") }
        if (lessons.isEmpty()) item("le") { ListRow("None yet", supporting = "Each night Atlas reviews what went wrong and writes itself a rule") }
        lessons.forEach { l ->
            item("l$l") {
                ListRow(l, icon = Icons.Outlined.Lightbulb, trailing = {
                    Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(Space.s).pressable { PersonalModel.forget(ctx, l); reload() })
                })
            }
        }
        item("ch") { SectionHeader("What changed") }
        log.take(20).forEachIndexed { i, s -> item("c$i") { ListRow(s, icon = Icons.Outlined.History) } }
        item("wipe") {
            HoldToConfirm("forget everything", Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.l)) {
                PersonalModel.forgetAll(ctx); reload(); Feedback.show("Forgotten")
            }
        }
    }
}

@Composable
fun RadarScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<Radar.Signal>?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { list = Radar.signals(ctx, container) } }
    AtlasScreen(title = "Radar", subtitle = "Small trends caught early", loading = list == null) {
        if (list?.isEmpty() == true) item("e") { EmptyState(EmptySpec(Icons.Outlined.Radar, "All clear", "Nothing drifting in the wrong direction. Atlas checks weekly.")) }
        list.orEmpty().forEach { s ->
            item(s.title) {
                ListRow(s.title, supporting = s.detail, icon = if (s.bad) Icons.Outlined.TrendingDown else Icons.Outlined.Radar,
                    tint = if (s.bad) MaterialTheme.colorScheme.error else Hub.Work.tint()) { onOpen(s.route) }
            }
        }
    }
}
