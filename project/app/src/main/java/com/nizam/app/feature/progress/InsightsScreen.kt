package com.nizam.app.feature.progress

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.AiPanel
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.Tones
import com.nizam.app.ui.theme.MonoNumber

@Composable
fun InsightsScreen(container: AppContainer) {
    var summary by remember { mutableStateOf<ProgressSummary?>(null) }
    var insights by remember { mutableStateOf<List<Insight>>(emptyList()) }

    LaunchedEffect(Unit) {
        val computed = Xp.summarise(container)
        summary = computed
        insights = Insights.compute(computed)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        ModuleTitle("Insights", "Patterns computed from your own history")

        summary?.let { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    "Level" to s.level.toString(),
                    "Streak" to "${s.streak}d",
                    "Best" to "${s.bestStreak}d",
                    "Total XP" to s.totalXp.toString()
                ).forEach { (label, value) ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(value, style = MonoNumber)
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        insights.forEach { insight ->
            val tones = Tones.forKey(insight.id)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = tones.tone.copy(alpha = 0.13f),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(Modifier.padding(16.dp)) {
                    Text(insight.glyph, style = MaterialTheme.typography.titleLarge, color = tones.accent)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(insight.headline, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(insight.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        summary?.let { s ->
            Spacer(Modifier.height(10.dp))
            Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(16.dp))
            AiPanel(
                settings = container.settingsRepository,
                label = "Ask about your patterns",
                contextPrompt = "The user's last 30 days, newest first. Each line is " +
                    "date, xp, tasks done, habits, prayers logged, sets, study minutes, lessons:\n" +
                    s.last30.joinToString("\n") {
                        "${it.date} ${it.xp} ${it.tasks} ${it.habits} ${it.prayers} ${it.sets} ${it.studyMinutes} ${it.lessons}"
                    } + "\nCurrent streak ${s.streak}, best ${s.bestStreak}, level ${s.level}.",
                starterQuestion = "What is the single biggest thing I should change?",
                system = "You analyse someone's real self-tracking data. Work only from the numbers " +
                    "given. Be specific and direct, name one or two concrete changes, and skip praise " +
                    "that isn't earned by the data."
            )
        }
        Spacer(Modifier.height(48.dp))
    }
}
