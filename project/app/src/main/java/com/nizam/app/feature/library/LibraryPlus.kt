package com.nizam.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Library, overhauled: continue where you stopped, a yearly reading goal, a reading streak built from
 * the pages you turn, and "what should I read next?" from what you finished and how you rated it.
 */
object LibraryPlus {
    private fun p(c: android.content.Context) = c.getSharedPreferences("library_plus", 0)
    fun goal(c: android.content.Context) = p(c).getInt("goal_${LocalDate.now().year}", 12)
    fun setGoal(c: android.content.Context, n: Int) = p(c).edit().putInt("goal_${LocalDate.now().year}", n.coerceIn(1, 365)).apply()

    /** Notices finished books and pages read since last look, so goals and streaks need no extra logging. */
    fun observe(c: android.content.Context, books: List<Book>) {
        val e = p(c).edit(); val today = LocalDate.now().toString()
        books.forEach { b ->
            if (b.status == "FINISHED" && !p(c).contains("fin_${b.id}")) e.putString("fin_${b.id}", today)
            val seen = p(c).getInt("page_${b.id}", -1)
            if (seen >= 0 && b.lastPage > seen) e.putInt("read_$today", p(c).getInt("read_$today", 0) + (b.lastPage - seen))
            e.putInt("page_${b.id}", b.lastPage)
        }
        e.apply()
    }
    fun finishedThisYear(c: android.content.Context, books: List<Book>) = books.count { b -> p(c).getString("fin_${b.id}", null)?.startsWith(LocalDate.now().year.toString()) == true }
    fun pagesOn(c: android.content.Context, d: LocalDate) = p(c).getInt("read_$d", 0)
    fun streak(c: android.content.Context): Int {
        var n = 0; var d = LocalDate.now(); if (pagesOn(c, d) == 0) d = d.minusDays(1)
        while (pagesOn(c, d) > 0 && n < 1000) { n++; d = d.minusDays(1) }
        return n
    }
}

@Composable
fun LibraryBoost(container: AppContainer, books: List<Book>, open: (Book) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    LaunchedEffect(books) { com.nizam.app.ui.design.guard { LibraryPlus.observe(c, books) } }
    var goal by remember { mutableIntStateOf(LibraryPlus.goal(c)) }
    val done = LibraryPlus.finishedThisYear(c, books)
    val streak = remember(books) { LibraryPlus.streak(c) }
    val week = remember(books) { (0..6).sumOf { LibraryPlus.pagesOn(c, LocalDate.now().minusDays(it.toLong())) } }
    var recs by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        books.filter { it.status == "READING" && it.fileUri.isNotBlank() }.maxByOrNull { it.progressPercent * 1000L + it.lastPage }?.let { b ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.primary.copy(alpha = 0.12f)).clickable { open(b) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Continue reading", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    Text(b.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${b.progressPercent}% · page ${b.lastPage + 1}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Text("▶", style = MaterialTheme.typography.titleLarge, color = cs.primary)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${LocalDate.now().year} goal: $done of $goal books", fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((done.toFloat() / goal).coerceIn(0.01f, 1f)).height(6.dp).background(cs.primary))
                }
                Text("$week pages this week · ${if (streak > 0) "🔥 $streak-day reading streak" else "read a page today to start a streak"}",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = { goal = (goal - 1).coerceAtLeast(1); LibraryPlus.setGoal(c, goal) }) { Text("−") }
            TextButton(onClick = { goal += 1; LibraryPlus.setGoal(c, goal) }) { Text("+") }
        }
        TextButton(onClick = {
            if (recs != null) { recs = null; return@TextButton }
            busy = true
            scope.launch {
                runCatching {
                    val fin = books.filter { it.status == "FINISHED" }.joinToString("; ") { "${it.title}${if (it.author.isNotBlank()) " by ${it.author}" else ""}${if (it.rating > 0) " (${it.rating}/5)" else ""}" }
                    val todo = books.filter { it.status == "TO_READ" }.joinToString("; ") { it.title }
                    Ai.generate(container.settingsRepository, "Books I finished (with my ratings): ${fin.ifBlank { "none yet" }}.\nOn my to-read shelf: ${todo.ifBlank { "nothing" }}.\n" +
                        "Recommend what I should read next: first pick one from my own shelf if it fits and say why, then suggest 4 new books I'd likely love, each with one line on why, based on my taste.",
                        system = "You are a thoughtful librarian. Real books only, with authors.", maxOutputTokens = 700, useWeb = false)
                }.onSuccess { recs = it }.onFailure { Feedback.show(it.message ?: "Couldn't suggest") }
                busy = false
            }
        }) { Text(if (busy) "Thinking…" else if (recs != null) "Hide suggestions" else "✦ What should I read next?") }
        recs?.let { com.nizam.app.feature.nx.Md(it) }
    }
}
