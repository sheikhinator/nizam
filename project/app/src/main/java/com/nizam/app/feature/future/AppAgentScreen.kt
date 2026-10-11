package com.nizam.app.feature.future

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.phone.AtlasAccessibilityService
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun AppAgentScreen(container: AppContainer) {
    val c = LocalContext.current
    var goal by remember { mutableStateOf("") }
    val status by AppAgent.status.collectAsState()
    val log by AppAgent.log.collectAsState()
    var recipes by remember { mutableStateOf(AppAgent.recipes(c)) }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { goal = it } }
    val on = AtlasAccessibilityService.instance != null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("App agent", "Say it — Atlas does it inside your apps")
        if (!on) Group(Modifier.padding(bottom = 10.dp)) {
            Text("Needs screen control", style = MaterialTheme.typography.titleSmall)
            Text("Settings › Accessibility › Installed apps › Atlas › On. Atlas then sees the screen and taps for you — never inside banking, " +
                "payment or password apps, and always asking before sending, ordering or paying.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } },
                modifier = Modifier.padding(top = 8.dp)) { Text("Turn on screen control") }
        }
        OutlinedTextField(goal, { goal = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("“Send Ammi ‘on my way’ on WhatsApp”, “Play Coke Studio on YouTube”, “Find a power bank under Rs 3000 on Daraz”") })
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = goal.isNotBlank() && on, onClick = { AppAgent.start(c, container, goal.trim()) }) { Text("Do it") }
            OutlinedButton(onClick = { runCatching { listen.launch(com.nizam.app.feature.voice.speechIntent()) } }) { Text("🎤 Say it") }
            if (AppAgent.running) OutlinedButton(onClick = { AppAgent.stop("Stopped") }) { Text("Stop") }
        }
        Label("Try")
        ChipRow(listOf("Open YouTube and play Coke Studio", "Search Daraz for wireless earbuds under 3000", "Check my Careem ride price to the airport",
            "Send my location on WhatsApp to Ammi", "Turn on dark mode in Settings", "Find today's news on Dawn in Chrome"), null, { goal = it })
        if (status.isNotBlank() || log.isNotEmpty()) {
            Label("Now")
            Group {
                Text(status, style = MaterialTheme.typography.titleSmall)
                log.takeLast(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Label(if (recipes.isEmpty()) "Saved tasks appear here after they work once" else "Saved tasks — replay in seconds")
        recipes.forEach { r ->
            Group(Modifier.padding(vertical = 4.dp)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall)
                Text("${r.steps.size} steps · used ${r.uses}×" + if (r.schedule.isNotBlank()) " · ${r.schedule}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = on, onClick = { AppAgent.start(c, container, r.name) }) { Text("Run") }
                    OutlinedButton(onClick = {
                        val next = when { r.schedule.isBlank() -> "daily ${LocalTime.now().withSecond(0).withNano(0).toString().take(5)}"
                            r.schedule.startsWith("daily") -> "monthly ${LocalDate.now().dayOfMonth} ${r.schedule.substringAfter(' ')}"; else -> "" }
                        AppAgent.saveRecipes(c, AppAgent.recipes(c).map { if (it.name == r.name) it.copy(schedule = next) else it }); recipes = AppAgent.recipes(c)
                        Feedback.show(if (next.isBlank()) "Schedule off" else "Runs $next")
                    }) { Text(if (r.schedule.isBlank()) "Schedule daily" else if (r.schedule.startsWith("daily")) "Make monthly" else "Unschedule") }
                    OutlinedButton(onClick = { AppAgent.saveRecipes(c, AppAgent.recipes(c).filter { it.name != r.name }); recipes = AppAgent.recipes(c) }) { Text("Delete") }
                }
            }
        }
        Text("Scheduled tasks run on time when the phone is unlocked; if it's locked, Atlas sends a tap-to-run notification.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 14.dp))
        Spacer(Modifier.height(40.dp))
    }
}

/** From the 15-minute background check: runs any saved task whose time has come. */
object AgentSchedule {
    fun check(c: Context, container: AppContainer) {
        val p = c.getSharedPreferences("app_agent", 0)
        val now = LocalTime.now(); val today = LocalDate.now()
        AppAgent.recipes(c).filter { it.schedule.isNotBlank() }.forEach { r ->
            val parts = r.schedule.split(' ')
            val time = runCatching { LocalTime.parse(parts.last()) }.getOrNull() ?: return@forEach
            val due = when (parts.first()) { "daily" -> true; "monthly" -> today.dayOfMonth == parts.getOrNull(1)?.toIntOrNull(); else -> false }
            val mark = "ran:${r.name}:$today"
            if (!due || p.getBoolean(mark, false) || now.isBefore(time) || now.isAfter(time.plusMinutes(45))) return@forEach
            p.edit().putBoolean(mark, true).apply()
            val locked = c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            if (!locked && AtlasAccessibilityService.instance != null) android.os.Handler(android.os.Looper.getMainLooper()).post { AppAgent.start(c, container, r.name) }
            else com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 960,
                "Time for: ${r.name}", "Unlock and open Atlas › Superpowers › App agent to run it")
        }
    }
}
