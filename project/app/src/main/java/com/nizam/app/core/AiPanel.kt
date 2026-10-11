package com.nizam.app.core

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.nizam.app.ui.design.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.net.Ai
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reusable "ask AI about this" panel. Drop it into any module.
 * Answers are AI-generated: useful, not authoritative.
 */
@Composable
fun AiPanel(
    settings: SettingsRepository,
    label: String,
    contextPrompt: String,
    starterQuestion: String = "",
    system: String = "You are a knowledgeable, practical assistant. Be specific and concrete. " +
        "Use short paragraphs and lists. Do not pad with filler."
) {
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var question by remember { mutableStateOf(starterQuestion) }
    var answer by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Ask anything") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !loading && question.isNotBlank(),
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            answer = ""
                            try {
                                val key = settings.geminiApiKey.first()
                                val model = settings.geminiModel.first()
                                answer = Ai.generate(
            settings = settings,
                                    prompt = "$contextPrompt\n\nQuestion: $question",
                                    system = system,
                                )
                            } catch (e: Ai.MissingKeyException) {
                                error = "Add an AI key first — Settings › API keys."
                            } catch (e: Exception) {
                                error = e.message ?: "Request failed."
                            }
                            loading = false
                        }
                    }
                ) { Text("Ask") }
            }

            if (loading) {
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator()
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (answer.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(answer, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "AI-generated — verify anything that matters.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
