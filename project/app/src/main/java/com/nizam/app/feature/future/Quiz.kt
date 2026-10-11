package com.nizam.app.feature.future

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch
import org.json.JSONArray

data class QuizQ(val q: String, val options: List<String>, val answer: Int, val why: String)

@Composable
fun QuizScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    val p = remember { c.getSharedPreferences("quiz", 0) }
    var topic by remember { mutableStateOf("General knowledge") }
    var custom by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("Medium") }
    var qs by remember { mutableStateOf<List<QuizQ>>(emptyList()) }
    var i by remember { mutableIntStateOf(0) }
    var picked by remember { mutableIntStateOf(-1) }
    var score by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    fun start() {
        busy = true; qs = emptyList(); i = 0; picked = -1; score = 0
        val t = custom.ifBlank { topic }
        scope.launch {
            runCatching {
                val raw = Ai.generate(container.settingsRepository,
                    "Write 10 $level multiple-choice quiz questions about \"$t\". Vary them; no repeats of common trivia. JSON array only: " +
                        "[{\"q\":\"…\",\"options\":[\"…\",\"…\",\"…\",\"…\"],\"answer\":0,\"why\":\"one-line explanation\"}]",
                    "You write accurate, fair quiz questions with exactly one correct option.", 2500)
                val a = JSONArray(Ai.cleanJson(raw))
                (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                    QuizQ(o.optString("q"), o.optJSONArray("options")?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty(), o.optInt("answer"), o.optString("why")) }
                    .filter { it.options.size >= 2 && it.answer in it.options.indices }
            }.onSuccess { qs = it }.onFailure { Feedback.failed("Making the quiz", it) }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Quiz", "Best score: ${p.getInt("best", 0)}/10 · quizzes played: ${p.getInt("played", 0)}")
        if (qs.isEmpty()) {
            Label("Topic")
            ChipRow(listOf("General knowledge", "Pakistan", "Islam", "Science", "Cricket", "English words", "Maths", "History", "Geography", "Tech", "Movies"), topic, { topic = it; custom = "" })
            OutlinedTextField(custom, { custom = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("…or anything: Mughal empire, Python, F1") })
            Label("Level"); ChipRow(listOf("Easy", "Medium", "Hard", "Expert"), level, { level = it })
            Button(enabled = !busy, onClick = { start() }, modifier = Modifier.padding(top = 12.dp)) { Text("Start") }
            if (busy) Thinking(label = "Writing your questions")
        } else if (i >= qs.size) {
            Group(Modifier.padding(top = 12.dp)) {
                Text("$score / ${qs.size}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(when { score == qs.size -> "Perfect! 🏆"; score >= qs.size * 0.7 -> "Excellent"; score >= qs.size / 2 -> "Good — try harder next"; else -> "Keep learning" })
            }
            Button(onClick = { start() }, modifier = Modifier.padding(top = 10.dp)) { Text("Another round") }
            Button(onClick = { qs = emptyList() }, modifier = Modifier.padding(top = 6.dp)) { Text("Change topic") }
        } else {
            val q = qs[i]
            LinearProgressIndicator(progress = { (i + 1) / qs.size.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text("Question ${i + 1} of ${qs.size} · score $score", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(q.q, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            q.options.forEachIndexed { k, o ->
                val bg = when {
                    picked < 0 -> MaterialTheme.colorScheme.surface
                    k == q.answer -> Color(0xFF3DAA6D).copy(alpha = 0.3f)
                    k == picked -> MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                    else -> MaterialTheme.colorScheme.surface
                }
                Text(o, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                    .clickable(enabled = picked < 0) { picked = k; if (k == q.answer) score++ }.padding(14.dp))
            }
            if (picked >= 0) {
                Text(q.why, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                Button(onClick = {
                    i++; picked = -1
                    if (i >= qs.size) p.edit().putInt("played", p.getInt("played", 0) + 1).putInt("best", maxOf(p.getInt("best", 0), score)).apply()
                }) { Text(if (i == qs.size - 1) "See score" else "Next") }
            }
        }
        Spacer(Modifier.height(50.dp))
    }
}
