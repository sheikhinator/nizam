package com.nizam.app.feature.onboarding

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
import com.nizam.app.ui.design.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.ui.theme.DisplayFamily
import kotlinx.coroutines.launch

private data class Question(
    val id: String,
    val title: String,
    val subtitle: String,
    val options: List<Pair<String, String>>,
    val multi: Boolean = false
)

private val QUESTIONS = listOf(
    Question(
        "focus", "What are you actually here to fix?",
        "Pick as many as are true. This sets what the home screen leads with.",
        listOf(
            "Discipline" to "I start things and drop them",
            "Money" to "I don't know where it goes",
            "Health" to "Training and eating are inconsistent",
            "Knowledge" to "I want to learn seriously",
            "Faith" to "I want my prayers consistent",
            "Clarity" to "My head is noisy"
        ),
        multi = true
    ),
    Question(
        "rhythm", "When does your day actually start?",
        "The plan gets built around this, not around an ideal morning.",
        listOf(
            "before_fajr" to "Before Fajr",
            "early" to "Early morning",
            "mid" to "Mid-morning",
            "late" to "I'm a night person"
        )
    ),
    Question(
        "capacity", "How much can you honestly give this?",
        "Be realistic. An overcommitted plan is why the last attempt died.",
        listOf(
            "30" to "30 minutes a day",
            "60" to "About an hour",
            "120" to "Two hours",
            "180" to "Three hours or more"
        )
    ),
    Question(
        "obstacle", "What usually breaks it?",
        "Knowing the failure mode is worth more than knowing the goal.",
        listOf(
            "energy" to "I run out of energy",
            "time" to "Work eats everything",
            "motivation" to "It stops feeling urgent",
            "chaos" to "Something always comes up"
        )
    )
)

@Composable
fun OnboardingScreen(settings: SettingsRepository, onDone: () -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var index by remember { mutableStateOf(-1) }
    val answers = remember { AnswerStore() }
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("2200") }
    var water by remember { mutableStateOf("3000") }
    var targetError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(40.dp))

        when {
            index == -1 -> {
                Text(
                    "Atlas",
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = DisplayFamily)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "One app for the whole of it — prayer, money, training, food, study, reading, " +
                        "and your own head. Everything stays on this phone.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("What should I call you?") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { index = 0 }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch { settings.completeOnboarding(name, 2200, 3000, "") }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Skip setup") }
            }

            index < QUESTIONS.size -> {
                val q = QUESTIONS[index]
                LinearProgressIndicator(
                    progress = { (index + 1f) / (QUESTIONS.size + 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Text(q.title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    q.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))

                q.options.forEach { (value, label) ->
                    val selected = answers.selected(q.id, value)
                    OutlinedButton(
                        onClick = { answers.toggle(q.id, value, q.multi) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            (if (selected) "✓  " else "") + label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index > 0) {
                        OutlinedButton(onClick = { index-- }) { Text("Back") }
                    }
                    Button(
                        enabled = answers.any(q.id),
                        onClick = { index++ }
                    ) { Text("Continue") }
                }
            }

            else -> {
                Text("Two numbers and you're in", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "You can change these any time in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = kcal, onValueChange = { kcal = it; targetError = null },
                    label = { Text("Daily calorie target") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = water, onValueChange = { water = it; targetError = null },
                    label = { Text("Daily water target (ml)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                targetError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        val kcalValue = kcal.toIntOrNull()
                        val waterValue = water.toIntOrNull()
                        if (kcalValue == null || waterValue == null ||
                            !settings.targetsAreValid(kcalValue, waterValue)
                        ) {
                            targetError = "Use whole-number targets between 1 and 100,000."
                        } else {
                            scope.launch {
                                settings.completeOnboarding(
                                    name = name,
                                    kcal = kcalValue,
                                    waterMl = waterValue,
                                    focus = answers.joined("focus")
                                )
                            }
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open Atlas") }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}

/** Tiny answer store so the survey stays dependency-free. */
private class AnswerStore {
    private val map = androidx.compose.runtime.mutableStateMapOf<String, MutableList<String>>()
    fun toggle(question: String, value: String, multi: Boolean) {
        val current = map[question] ?: mutableListOf()
        val next = when {
            current.contains(value) -> current.filter { it != value }.toMutableList()
            multi -> (current + value).toMutableList()
            else -> mutableListOf(value)
        }
        map[question] = next
    }
    fun selected(question: String, value: String) = map[question]?.contains(value) == true
    fun any(question: String) = map[question]?.isNotEmpty() == true
    fun joined(question: String) = map[question]?.joinToString(",").orEmpty()
}
