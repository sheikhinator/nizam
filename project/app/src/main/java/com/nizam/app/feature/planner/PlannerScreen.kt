package com.nizam.app.feature.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.phone.PhoneActions
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

private fun colourFor(kind: String) = when (kind) {
    "prayer" -> Color(0xFF4FC3A1); "study" -> Color(0xFF9B8CD6); "work" -> Color(0xFF58A6C9)
    "gym" -> Color(0xFFD98E4A); "break" -> Color(0xFF8C96AC); else -> Color(0xFFE0B14A)
}

@Composable
fun PlannerScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var blocks by remember { mutableStateOf(Planner.load(context)) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val now = LocalTime.now().toString().take(5)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Plan my day", "Tasks and missions fitted around your prayers and work")
        OutlinedTextField(note, { note = it }, label = { Text("Anything special today? (optional)") },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true; status = null
                blocks = runCatching { Planner.plan(container, context, note) }
                    .getOrElse { status = "Couldn't plan: ${com.nizam.app.ui.components.Feedback.friendly(it)}"; blocks }
                busy = false
            }
        }) { Text(if (busy) "Planning…" else if (blocks.isEmpty()) "Plan the rest of today" else "Re-plan") }

        blocks.forEach { b ->
            val current = now >= b.start && now < b.end
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("${b.start}\n${b.end}", style = MonoNumber, modifier = Modifier.width(56.dp),
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.width(4.dp).height(44.dp).background(colourFor(b.kind), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.title, style = MaterialTheme.typography.bodyLarge,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(b.kind + if (current) " · now" else "", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (blocks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                var ok = 0
                blocks.filter { it.kind != "break" && it.start >= now }.forEach { b ->
                    val mins = runCatching {
                        java.time.Duration.between(LocalTime.parse(b.start), LocalTime.parse(b.end)).toMinutes().toInt()
                    }.getOrElse { 30 }
                    if (PhoneActions.addEvent(context, b.title, LocalDate.now().toString(), b.start, mins, "").ok) ok++
                }
                status = if (ok > 0) "Added $ok blocks to your calendar." else "Calendar access needed — grant it in Me > Phone control."
            }) { Text("Add to my calendar") }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(60.dp))
    }
}
