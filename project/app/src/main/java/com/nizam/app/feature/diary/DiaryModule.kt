package com.nizam.app.feature.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.nizam.app.AppContainer
import com.nizam.app.core.AiPanel
import com.nizam.app.core.LineChart
import com.nizam.app.core.Point
import com.nizam.app.core.StatRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.MultiChipRow
import com.nizam.app.core.prettyDate
import com.nizam.app.core.today
import com.nizam.app.core.vmFactory
import com.nizam.app.ui.theme.Spine
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Entity(tableName = "diary_entry")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localDate: String,
    val body: String,
    val mood: Int,
    val emotions: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "mood_checkin")
data class MoodCheckIn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localDate: String,
    val timestamp: Long = System.currentTimeMillis(),
    val mood: Int,
    val emotions: String = ""
)

@Dao
interface DiaryDao {
    @Query("DELETE FROM diary_entry WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("SELECT * FROM diary_entry ORDER BY localDate DESC, id DESC LIMIT 200")
    fun observeEntries(): Flow<List<DiaryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DiaryEntry)

    @Query("SELECT * FROM mood_checkin ORDER BY timestamp DESC LIMIT 100")
    fun observeCheckIns(): Flow<List<MoodCheckIn>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckIn(checkIn: MoodCheckIn)
}

class DiaryRepository(private val dao: DiaryDao) {
    fun entries() = dao.observeEntries()
    fun checkIns() = dao.observeCheckIns()
    suspend fun addEntry(body: String, mood: Int, emotions: List<String>) {
        dao.insert(DiaryEntry(localDate = today(), body = body.trim(), mood = mood, emotions = emotions.joinToString(",")))
    }
    suspend fun deleteEntry(id: Long) = dao.deleteEntry(id)

    suspend fun addCheckIn(mood: Int, emotions: List<String>) {
        dao.insertCheckIn(MoodCheckIn(localDate = today(), mood = mood, emotions = emotions.joinToString(",")))
    }
}

val EMOTIONS = listOf("Calm", "Grateful", "Focused", "Tired", "Anxious", "Frustrated", "Low", "Hopeful")

val PROMPTS = listOf(
    "What actually happened today?",
    "What did you learn?",
    "What are you grateful for?",
    "What would you do differently?",
    "What is taking up the most space in your head?"
)

class DiaryViewModel(private val repository: DiaryRepository) : ViewModel() {
    val entries: StateFlow<List<DiaryEntry>> = repository.entries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val checkIns: StateFlow<List<MoodCheckIn>> = repository.checkIns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addEntry(body: String, mood: Int, emotions: List<String>) {
        if (body.isBlank()) return
        viewModelScope.launch { repository.addEntry(body, mood, emotions) }
    }
    fun addCheckIn(mood: Int, emotions: List<String>) {
        viewModelScope.launch { repository.addCheckIn(mood, emotions) }
    }
    fun deleteEntry(id: Long) = viewModelScope.launch { repository.deleteEntry(id) }
}

@Composable
fun DiaryScreen(container: AppContainer) {
    val vm: DiaryViewModel = viewModel(factory = vmFactory { DiaryViewModel(container.diaryRepository) })
    val entries by vm.entries.collectAsState()
    val checkIns by vm.checkIns.collectAsState()

    var body by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf(3) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var prompt by remember { mutableStateOf(MORE_PROMPTS.random()) }
    var diaryQuery by remember { mutableStateOf("") }

    val weekMoods = checkIns.take(14).map { it.mood }
    val avg = if (weekMoods.isEmpty()) 0.0 else weekMoods.average()

    val moodTrend = remember(entries, checkIns) {
        val combined = entries.map { it.localDate to it.mood.toDouble() } +
            checkIns.map { it.localDate to it.mood.toDouble() }
        combined.groupBy { it.first }
            .toSortedMap()
            .map { (date, values) -> Point(date.takeLast(5), values.map { it.second }.average()) }
            .takeLast(30)
    }
    val moodAverage = if (moodTrend.isEmpty()) 0.0 else moodTrend.map { it.value }.average()
    val emotionCounts = remember(entries, checkIns) {
        (entries.flatMap { it.emotions.split(",") } + checkIns.flatMap { it.emotions.split(",") })
            .map { it.trim() }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
    }
    val bestWeekday = remember(entries) {
        entries.mapNotNull { e ->
            runCatching { java.time.LocalDate.parse(e.localDate).dayOfWeek.name to e.mood.toDouble() }
                .getOrNull()
        }.groupBy { it.first }
            .filter { it.value.size >= 2 }
            .map { it.key to it.value.map { v -> v.second }.average() }
            .maxByOrNull { it.second }
    }


    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth()) {
        ModuleTitle("Diary", if (avg > 0) "Recent mood average ${"%.1f".format(avg)}" else "Write it down")

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { value ->
                FilterChip(
                    selected = mood == value,
                    onClick = { mood = value },
                    label = { Text(value.toString()) }
                )
            }
            Button(onClick = { vm.addCheckIn(mood, picked.toList()) }) { Text("Check in") }
        }

        Spacer(Modifier.height(8.dp))
        MultiChipRow(
            options = EMOTIONS,
            selected = picked,
            onToggle = { e -> picked = if (picked.contains(e)) picked - e else picked + e }
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text(prompt) },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            trailingIcon = { com.nizam.app.feature.nx.MicButton({ heard -> body = (body.trimEnd() + " " + heard).trim() }) }
        )
        androidx.compose.material3.TextButton(onClick = { prompt = (MORE_PROMPTS - prompt).random() }) { Text("Another prompt") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            vm.addEntry(body, mood, picked.toList())
            body = ""
            picked = emptySet()
        }) { Text("Save entry") }

        DiaryBoost(container, entries, checkIns)
        if (entries.size >= 2 || checkIns.size >= 2) {
            Spacer(Modifier.height(14.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Mood over time", style = MaterialTheme.typography.titleMedium)
                    LineChart(points = moodTrend, accent = Spine.Diary)
                    StatRow(
                        listOf(
                            "average" to "%.1f".format(moodAverage),
                            "entries" to entries.size.toString(),
                            "check-ins" to checkIns.size.toString()
                        )
                    )

                    if (emotionCounts.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("WHAT COMES UP MOST", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        emotionCounts.take(5).forEach { (emotion, count) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(emotion, style = MaterialTheme.typography.bodyMedium)
                                Text("▪".repeat(count.coerceAtMost(12)),
                                    style = MaterialTheme.typography.labelMedium, color = Spine.Diary)
                            }
                        }
                    }

                    bestWeekday?.let { (day, avg) ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Your mood runs highest on ${day.lowercase().replaceFirstChar { it.uppercase() }} " +
                                "(${"%.1f".format(avg)}).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        AiPanel(
            settings = container.settingsRepository,
            label = "Reflect on your entries",
            contextPrompt = "Here are the user's recent diary entries, newest first:\n" +
                entries.take(20).joinToString("\n") { "${it.localDate} (mood ${it.mood}): ${it.body}" },
            starterQuestion = "What patterns do you notice across these entries?",
            system = "You are a thoughtful, grounded reader of someone's private journal. Notice patterns " +
                "in what they write, be honest but kind, and never dramatise. You are not a therapist; " +
                "if something looks serious, gently suggest talking to someone they trust."
        )
        Spacer(Modifier.height(14.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        if (entries.size > 5) OutlinedTextField(value = diaryQuery, onValueChange = { diaryQuery = it }, singleLine = true,
            placeholder = { Text("Search ${entries.size} entries") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
        }

            items(entries.filter { diaryQuery.isBlank() || it.body.contains(diaryQuery.trim(), true) || it.emotions.contains(diaryQuery.trim(), true) }, key = { it.id }) { entry ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(prettyDate(entry.localDate), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Mood ${entry.mood}", style = MonoNumber)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(entry.body, style = MaterialTheme.typography.bodyLarge)
                    if (entry.emotions.isNotBlank()) {
                        Text(entry.emotions.replace(",", " · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "delete",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(role = Role.Button) { vm.deleteEntry(entry.id) }
                    )
                }
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
    }
}
