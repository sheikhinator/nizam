package com.nizam.app.feature.jarvis

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.components.Thinking
import java.time.LocalDate

@Composable
fun BarakahScreen(container: AppContainer) {
    val c = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var days by remember { mutableStateOf<List<BarakahDay>>(emptyList()) }
    LaunchedEffect(refresh) { days = runCatching { Barakah.days(container, 30) }.getOrElse { emptyList() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Barakah", "How well today was lived — and what actually lifts your days")
        val today = days.lastOrNull()
        if (today == null) { Thinking(label = "Adding up your day"); return@Column }

        Row(verticalAlignment = Alignment.Bottom) {
            Text("${today.score}", fontSize = 64.sp, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            Text(" / 100 today", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp))
        }
        val week = days.takeLast(7).map { it.score }.average().toInt()
        Text("7-day average $week", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

        today.parts.forEach { part ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(part.label, style = MaterialTheme.typography.bodyLarge)
                    if (part.note.isNotBlank()) Text(part.note, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { part.earned.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                Spacer(Modifier.width(10.dp))
                val manualKey = part.key.takeIf { k -> Barakah.MANUAL.any { it.first == k } || (k == "charity" && !part.auto) }
                if (manualKey != null) Checkbox(Barakah.ticked(c, LocalDate.now(), manualKey), { on ->
                    Barakah.tick(c, LocalDate.now(), manualKey, on); refresh++
                })
                else Text("${part.weight}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("LAST 14 DAYS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            days.takeLast(14).forEach { d ->
                Box(Modifier.weight(1f).fillMaxHeight((d.score / 100f).coerceAtLeast(0.03f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (d.date == LocalDate.now()) 1f else 0.55f)))
            }
        }

        val drivers = Barakah.drivers(days)
        Text("WHAT LIFTS YOUR DAYS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
        if (drivers.isEmpty()) Text("After a couple of weeks of days, this shows which habits go with better days overall — from your own record, not advice.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        drivers.take(3).forEach { (label, lift) ->
            Text("On days with ${label.lowercase()}, the rest of your day scores $lift points higher.", style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 20.dp))
        Text("Salah-aware time", style = MaterialTheme.typography.titleMedium)
        Text("The day bends around prayer, not the other way round.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        var silence by remember { mutableStateOf(SalahGuard.silenceOn(c)) }
        var shift by remember { mutableStateOf(SalahGuard.shiftOn(c)) }
        var mins by remember { mutableStateOf(SalahGuard.minutes(c).toString()) }
        val nm = c.getSystemService(NotificationManager::class.java)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Silence the phone at each prayer", style = MaterialTheme.typography.bodyLarge)
                Text("Priority-only for the prayer, then back to normal. Jumu'ah gets 50 minutes. Needs prayer notifications on.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(silence, { on ->
                if (on && nm?.isNotificationPolicyAccessGranted != true) {
                    c.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    com.nizam.app.ui.components.Feedback.show("Allow Atlas Do Not Disturb access, then switch this on")
                } else { SalahGuard.setSilence(c, on); silence = on }
            })
        }
        if (silence) ChipRow(listOf("15", "20", "30", "40"), mins, { mins = it; SalahGuard.setMinutes(c, it.toInt()) }, labels = { "$it min" })
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Move events off prayer times", style = MaterialTheme.typography.bodyLarge)
                Text("When Atlas adds a calendar event that lands on a prayer, it moves it to just after — and tells you.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(shift, { SalahGuard.setShift(c, it); shift = it })
        }
        Text("The day planner already builds your schedule around the five prayers.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(80.dp))
    }
}
