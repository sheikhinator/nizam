package com.nizam.app.feature.modes

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.ModeNavigationStore
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.ui.design.Comfort
import com.nizam.app.ui.design.Hub

/** First-run (and any-time) setup: pick who you are, and Atlas arranges itself. Nothing is removed; it only changes what comes first. */
object Wizard {
    data class Persona(val id: String, val name: String, val emoji: String, val blurb: String, val hubs: List<Hub>, val elder: Boolean = false)
    val PERSONAS = listOf(
        Persona("student", "Student", "🎓", "Study, revision, prayer and money for the month", listOf(Hub.Learn, Hub.Deen, Hub.Work, Hub.Memory, Hub.Money)),
        Persona("parent", "Parent", "👨‍👩‍👧", "Home, family, budget, health and deen", listOf(Hub.Home, Hub.Social, Hub.Money, Hub.Health, Hub.Deen)),
        Persona("pro", "Professional", "💼", "Work, money, health and tools", listOf(Hub.Work, Hub.Money, Hub.Health, Hub.Tools, Hub.Deen)),
        Persona("business", "Business owner", "🏪", "Money, clients, people and tools", listOf(Hub.Money, Hub.Work, Hub.Social, Hub.Tools, Hub.Create)),
        Persona("elder", "Elder / simple", "🧓", "Big text, prayer, health and family only", listOf(Hub.Deen, Hub.Health, Hub.Home), elder = true),
        Persona("all", "Everything", "🌐", "Show every suite equally", emptyList()),
    )
    private fun p(c: Context) = c.getSharedPreferences("wizard", 0)
    fun done(c: Context) = p(c).getBoolean("done", false)
    fun interests(c: Context): List<Hub> = p(c).getString("hubs", "").orEmpty().split(",").mapNotNull { n -> Hub.entries.firstOrNull { it.name == n } }

    fun apply(c: Context, persona: Persona, language: String, autoModes: Boolean) {
        p(c).edit().putBoolean("done", true).putString("persona", persona.id).putString("hubs", persona.hubs.joinToString(",") { it.name }).apply()
        com.nizam.app.feature.future.Lang.set(c, language)
        AutoModes.setAuto(c, autoModes); AutoModes.ensure(c)
        if (persona.elder) { Comfort.update(c) { it.copy(elder = true, textScale = 1.3f) } }
        else Comfort.update(c) { it.copy(elder = false) }
        // Today shows the cards that suit them
    }
}

@Composable
private fun Choice(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = if (selected) c.primaryContainer else c.surface,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
fun WizardScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val c = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var persona by remember { mutableStateOf(Wizard.PERSONAS.last()) }
    var lang by remember { mutableStateOf("en") }
    var auto by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Step ${step + 1} of 4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (step) {
            0 -> { Text("Who is Atlas for?", style = MaterialTheme.typography.titleLarge)
                Wizard.PERSONAS.forEach { p -> Choice("${p.emoji}  ${p.name}", p.blurb, persona.id == p.id) { persona = p } } }
            1 -> { Text("Language", style = MaterialTheme.typography.titleLarge)
                listOf("en" to "English", "ur" to "اردو", "roman" to "Roman Urdu").forEach { (k, l) -> Choice(l, null, lang == k) { lang = k } }
                Text("This is the language Atlas writes in. You can change it anytime.", style = MaterialTheme.typography.bodySmall) }
            2 -> { Text("Modes", style = MaterialTheme.typography.titleLarge)
                Text("Atlas can switch between Morning, Work, Worship, Night and more by itself, using prayer times, your calendar and the charger. You can always override.", style = MaterialTheme.typography.bodyMedium)
                Choice("Switch automatically", "Recommended. You can override any time.", auto) { auto = true }
                Choice("I'll choose", "Atlas stays in the mode you pick.", !auto) { auto = false } }
            else -> { Text("Backup", style = MaterialTheme.typography.titleLarge)
                Text("Protect your data: set a backup phrase and Atlas saves an encrypted copy every week.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onOpen("nx/autobackup") }) { Text("Set up backup") }
                Text("You can do this later from You › Backup.", style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            if (step < 3) Button(onClick = { step++ }) { Text("Next") } else Button(onClick = { Wizard.apply(c, persona, lang, auto); onOpen(com.nizam.app.ui.nav.Routes.TODAY) }) { Text("Finish") }
        }
    }
}

object WizardSpecs {
    val all = listOf(
        ScreenSpec("setupwizard", "Setup wizard", "Choose who Atlas is for, language, modes and backup in one minute", Icons.Outlined.AutoFixHigh, Hub.Tools,
            "setup wizard persona onboarding first run personalise student parent professional elder") { c, open -> WizardScreen(c, open) },
        ScreenSpec("inbox", "Notification inbox", "Everything Atlas told you, with optional daily digests", Icons.Outlined.Inbox, Hub.Tools,
            "notification inbox digest bell reminders history") { _, open -> com.nizam.app.feature.notify.InboxScreen(open) },
        ScreenSpec("autobackup", "Automatic backup", "A weekly encrypted backup you can copy out of the phone", Icons.Outlined.Backup, Hub.Tools,
            "backup automatic weekly encrypted restore phrase copy drive") { _, _ -> com.nizam.app.feature.backup.AutoBackupScreen() },
        ScreenSpec("crashlog", "Crash & issue log", "Shows recent crashes and lets you share a report", Icons.Outlined.BugReport, Hub.Tools,
            "crash log issue report bug safe mode share diagnostics") { c, _ -> CrashLogScreen(c) },
    )
}

@Composable
private fun CrashLogScreen(container: AppContainer) {
    val c = LocalContext.current
    var text by remember { mutableStateOf(com.nizam.app.feature.safe.CrashGuard.report(c)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { TextButton(onClick = { com.nizam.app.feature.nx.Share.text(c, text, "Atlas crash report") }) { Text("Share report") }
            TextButton(onClick = { com.nizam.app.feature.safe.CrashGuard.clear(c); text = "No crashes recorded." }) { Text("Clear") } }
        Text(text.take(3000), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal)
    }
}
