package com.nizam.app.feature.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer

/**
 * A small card on Today where Atlas chooses what to show: the one thing it noticed in your own
 * data, and the tools you usually open around this time. Hidden when it has nothing to say.
 */
@Composable
fun AtlasPicks(container: AppContainer, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { Roads.warm(container); runCatching { com.nizam.app.feature.forecast.Outlook.refresh(container) }; tick++ }
    val notice = remember(tick) { Noticer.scan(c).firstOrNull() }
    val usual = remember(tick) { Mind.now(c, 2) }
    var draft by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Atlas prepares a few steps before you ask, at most three times a day, using your AI provider
    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        val p = c.getSharedPreferences("picks_draft", 0)
        val day = java.time.LocalDate.now().toString()
        p.getString("${day}_${n.id}", null)?.let { draft = it; return@LaunchedEffect }
        if (p.getInt("count_$day", 0) >= 3) return@LaunchedEffect
        runCatching {
            com.nizam.app.net.Ai.generate(container.settingsRepository,
                "My own app noticed this about me: ${n.title}. ${n.detail} Give three short, practical steps I can take today. Plain words, one line each.",
                system = "You are a calm, practical helper. No lectures.", maxOutputTokens = 220)
        }.onSuccess { r ->
            draft = r
            p.edit().putString("${day}_${n.id}", r).putInt("count_$day", p.getInt("count_$day", 0) + 1).apply()
        }
    }
    if (notice == null && usual.isEmpty()) return
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(12.dp)) {
        if (notice != null) {
            Text("Atlas noticed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(notice.title, fontWeight = FontWeight.SemiBold)
            Text(notice.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            draft?.let { Text(it.replace(Regex("[#*_]"), ""), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            TextButton(onClick = { onOpen(notice.route) }) { Text("Open the helper") }
        }
        if (usual.isNotEmpty()) {
            Text("Usually around now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (notice != null) 4.dp else 0.dp))
            usual.forEach { s -> TextButton(onClick = { onOpen("nx/${s.id}") }) { Text(s.title) } }
        }
    }
}
