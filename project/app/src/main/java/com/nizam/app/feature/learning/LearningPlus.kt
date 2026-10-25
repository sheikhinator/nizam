package com.nizam.app.feature.learning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Learning, overhauled: a study timer that logs itself, progress toward the track's target with an
 * ETA at your real pace, a study streak, cards made for you from notes or a topic, and a quick quiz
 * built offline from your own cards.
 */
@Composable
fun LearningBoost(container: AppContainer, track: Track, sessions: List<StudySession>) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val cs = MaterialTheme.colorScheme
    val p = remember { c.getSharedPreferences("learning_plus", 0) }
    var startedAt by remember(track.id) { mutableLongStateOf(p.getLong("timer_${track.id}", 0L)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { com.nizam.app.ui.design.guard { while (startedAt > 0) { delay(1000); now = System.currentTimeMillis() } } }
    val mine = sessions.filter { it.trackId == track.id }
    val total = mine.sumOf { it.minutes }
    val last28 = mine.filter { it.localDate >= LocalDate.now().minusDays(27).toString() }.sumOf { it.minutes }
    val perWeek = last28 / 4.0
    val days = mine.map { it.localDate }.toSet()
    val streak = remember(days) { var n = 0; var d = LocalDate.now(); if (d.toString() !in days) d = d.minusDays(1); while (d.toString() in days && n < 1000) { n++; d = d.minusDays(1) }; n }

    com.nizam.app.ui.design.Group(Modifier.padding(vertical = 8.dp)) {
        // timer
        if (startedAt == 0L) TextButton(onClick = { startedAt = System.currentTimeMillis(); p.edit().putLong("timer_${track.id}", startedAt).apply() }) {
            Text("⏱ Start studying ${track.name}") }
        else Row(verticalAlignment = Alignment.CenterVertically) {
            val secs = ((now - startedAt) / 1000).coerceAtLeast(0)
            Text("Studying · ${secs / 3600}:${"%02d".format(secs / 60 % 60)}:${"%02d".format(secs % 60)}", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = cs.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val m = ((System.currentTimeMillis() - startedAt) / 60000).toInt()
                startedAt = 0; p.edit().remove("timer_${track.id}").apply()
                if (m >= 1) scope.launch { runCatching { container.learningRepository.logSession(track.id, m) }; Feedback.show("Logged $m min on ${track.name}") }
                else Feedback.show("Under a minute, not logged")
            }) { Text("Stop & log") }
        }
        // target
        val target = track.targetHours * 60
        Text("Toward ${track.targetHours}h: ${total / 60}h ${total % 60}m", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((total.toFloat() / target).coerceIn(0.01f, 1f)).height(6.dp).background(cs.primary))
        }
        val eta = if (perWeek > 0 && total < target) LocalDate.now().plusDays(((target - total) / perWeek * 7).toLong()) else null
        Text(listOfNotNull("${"%.1f".format(perWeek / 60)}h a week lately", eta?.let { "done around ${it.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))}" },
            if (streak > 0) "🔥 $streak-day streak" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)

        CardMaker(container, track)
        QuickQuiz(container, track)
    }
}

@Composable
private fun CardMaker(container: AppContainer, track: Track) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var src by remember(track.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val made = remember(track.id) { mutableStateListOf<Pair<String, String>>() }
    val pick = remember(track.id) { mutableStateListOf<Boolean>() }
    Text("✦ Make cards for me", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    OutlinedTextField(src, { src = it }, Modifier.fillMaxWidth(), maxLines = 5, placeholder = { Text("Paste notes, or just name a topic") },
        trailingIcon = { com.nizam.app.feature.nx.MicButton({ heard -> src = (src + " " + heard).trim() }) })
    TextButton(onClick = {
        busy = true
        scope.launch {
            runCatching {
                val raw = Ai.generate(container.settingsRepository, "Make 6 to 12 study flashcards for the subject \"${track.name}\" from this material (or about this topic if it's short):\n$src\n\n" +
                    "Each card tests one fact or idea. Output only JSON: {\"cards\":[{\"front\":\"question\",\"back\":\"short answer\"}]}",
                    system = "You write precise, testable flashcards. Output only JSON.", maxOutputTokens = 1200)
                val a = com.nizam.app.feature.nx.EngineX.json(raw)?.optJSONArray("cards")
                (0 until (a?.length() ?: 0)).mapNotNull { i -> a!!.optJSONObject(i)?.let { o -> (o.optString("front") to o.optString("back")).takeIf { it.first.isNotBlank() && it.second.isNotBlank() } } }
            }.onSuccess { l -> made.clear(); pick.clear(); made.addAll(l); pick.addAll(l.map { true }); if (l.isEmpty()) Feedback.show("Couldn't make cards from that") }
                .onFailure { Feedback.show(it.message ?: "Couldn't make cards") }
            busy = false
        }
    }, enabled = src.isNotBlank() && !busy) { Text(if (busy) "Making cards…" else "Make cards") }
    made.forEachIndexed { i, (f, b) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(pick.getOrElse(i) { false }, { pick[i] = it })
            Column(Modifier.weight(1f)) { Text(f, style = MaterialTheme.typography.bodyMedium); Text(b, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (made.isNotEmpty()) TextButton(onClick = {
        val chosen = made.filterIndexed { i, _ -> pick.getOrElse(i) { false } }
        scope.launch { chosen.forEach { (f, b) -> runCatching { container.learningRepository.addCard(track.id, f, b) } }; Feedback.show("Added ${chosen.size} cards"); made.clear(); pick.clear(); src = "" }
    }) { Text("Add ${pick.count { it }} to ${track.name}") }
}

/** Five multiple-choice questions from your own cards, other cards' answers as the wrong options. No internet. */
@Composable
private fun QuickQuiz(container: AppContainer, track: Track) {
    val flow = remember { container.learningRepository.cards() }
    val all by flow.collectAsState(initial = emptyList())
    val cards = all.filter { it.trackId == track.id }
    var quiz by remember(track.id) { mutableStateOf<List<Pair<Flashcard, List<String>>>?>(null) }
    var idx by remember(track.id) { mutableStateOf(0) }
    var score by remember(track.id) { mutableStateOf(0) }
    var picked by remember(track.id) { mutableStateOf<String?>(null) }
    if (cards.size < 4) return
    val cs = MaterialTheme.colorScheme
    val q = quiz
    if (q == null) {
        TextButton(onClick = {
            quiz = cards.shuffled().take(5).map { card -> card to (cards.filter { it.id != card.id }.map { it.back }.distinct().shuffled().take(3) + card.back).shuffled() }
            idx = 0; score = 0; picked = null
        }) { Text("🎯 Quick quiz (${cards.size} cards)") }
        return
    }
    if (idx >= q.size) {
        Text("Score: $score / ${q.size}", fontWeight = FontWeight.SemiBold, color = cs.primary, modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = { quiz = null }) { Text("Done") }
        return
    }
    val (card, opts) = q[idx]
    Text("Q${idx + 1}. ${card.front}", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
    opts.forEach { o ->
        val right = o == card.back
        Text(o, Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
            .background(when { picked == null -> cs.surfaceVariant.copy(alpha = 0.5f); right -> Color(0xFF2E9E6A).copy(alpha = 0.35f); o == picked -> cs.error.copy(alpha = 0.3f); else -> cs.surfaceVariant.copy(alpha = 0.3f) })
            .clickable(enabled = picked == null) { picked = o; if (right) score++ }.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
    if (picked != null) TextButton(onClick = { idx++; picked = null }) { Text(if (idx + 1 < q.size) "Next" else "See score") }
}
