package com.nizam.app.feature.learning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
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
import androidx.room.Update
import com.nizam.app.AppContainer
import com.nizam.app.core.BarChart
import com.nizam.app.core.Point
import com.nizam.app.core.StatRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.today
import com.nizam.app.core.vmFactory
import com.nizam.app.ui.theme.Spine
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

@Entity(tableName = "track")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val why: String = "",
    val targetHours: Int = 100,
    val archived: Boolean = false
)

@Entity(tableName = "study_session")
data class StudySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val localDate: String,
    val minutes: Int,
    val note: String = ""
)

@Entity(tableName = "flashcard")
data class Flashcard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val front: String,
    val back: String,
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val dueDate: String = today(),
    val lapses: Int = 0
)

@Dao
interface LearningDao {
    @Query("SELECT * FROM track WHERE archived = 0 ORDER BY id ASC")
    fun observeTracks(): Flow<List<Track>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: Track): Long

    @Query("SELECT * FROM study_session ORDER BY localDate DESC LIMIT 200")
    fun observeSessions(): Flow<List<StudySession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: StudySession)

    @Query("SELECT * FROM flashcard WHERE dueDate <= :date ORDER BY dueDate ASC LIMIT 50")
    fun observeDue(date: String): Flow<List<Flashcard>>

    @Query("SELECT * FROM flashcard ORDER BY id DESC LIMIT 200")
    fun observeCards(): Flow<List<Flashcard>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: Flashcard)

    @Update
    suspend fun updateCard(card: Flashcard)

    @Query("DELETE FROM track WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    @Query("DELETE FROM flashcard WHERE trackId = :id")
    suspend fun deleteCardsFor(id: Long)
}

/** SM-2 scheduling. grade: 0 again, 3 hard, 4 good, 5 easy. */
fun scheduleCard(card: Flashcard, grade: Int): Flashcard {
    if (grade < 3) {
        return card.copy(
            intervalDays = 1,
            ease = maxOf(1.3, card.ease - 0.20),
            lapses = card.lapses + 1,
            dueDate = LocalDate.now().plusDays(1).toString()
        )
    }
    val newEase = maxOf(1.3, card.ease + (0.1 - (5 - grade) * (0.08 + (5 - grade) * 0.02)))
    val newInterval = when (card.intervalDays) {
        0 -> 1
        1 -> 6
        else -> (card.intervalDays * newEase).roundToInt()
    }
    return card.copy(
        ease = newEase,
        intervalDays = newInterval,
        dueDate = LocalDate.now().plusDays(newInterval.toLong()).toString()
    )
}

class LearningRepository(private val dao: LearningDao) {
    fun tracks() = dao.observeTracks()
    fun sessions() = dao.observeSessions()
    fun due(date: String) = dao.observeDue(date)
    fun cards() = dao.observeCards()

    suspend fun addTrack(name: String) = dao.insertTrack(Track(name = name.trim()))
    suspend fun logSession(trackId: Long, minutes: Int) =
        dao.insertSession(StudySession(trackId = trackId, localDate = today(), minutes = minutes))
    suspend fun addCard(trackId: Long, front: String, back: String) =
        dao.insertCard(Flashcard(trackId = trackId, front = front.trim(), back = back.trim()))
    suspend fun review(card: Flashcard, grade: Int) = dao.updateCard(scheduleCard(card, grade))

    suspend fun deleteTrack(track: Track) {
        dao.deleteCardsFor(track.id)
        dao.deleteTrack(track.id)
    }
}

val STARTER_TRACKS = listOf(
    "Programming", "Data & analytics", "Finance & accounting", "Arabic",
    "Mathematics", "Writing", "Supply chain", "Health science"
)

class LearningViewModel(private val repository: LearningRepository) : ViewModel() {
    val tracks: StateFlow<List<Track>> = repository.tracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sessions: StateFlow<List<StudySession>> = repository.sessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val due: StateFlow<List<Flashcard>> = repository.due(today())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTrack(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.addTrack(name) }
    }
    fun logSession(trackId: Long, minutes: String) {
        val m = minutes.toIntOrNull() ?: return
        viewModelScope.launch { repository.logSession(trackId, m) }
    }
    fun addCard(trackId: Long, front: String, back: String) {
        if (front.isBlank() || back.isBlank()) return
        viewModelScope.launch { repository.addCard(trackId, front, back) }
    }
    fun review(card: Flashcard, grade: Int) = viewModelScope.launch { repository.review(card, grade) }
    fun deleteTrack(track: Track) = viewModelScope.launch { repository.deleteTrack(track) }
}

@Composable
fun LearningScreen(container: AppContainer) {
    val vm: LearningViewModel = viewModel(factory = vmFactory { LearningViewModel(container.learningRepository) })
    val tracks by vm.tracks.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val due by vm.due.collectAsState()

    var newTrack by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Long?>(null) }
    var minutes by remember { mutableStateOf("") }
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    val weeklyStudy = remember(sessions) {
        (7 downTo 0).map { weeksAgo ->
            val start = java.time.LocalDate.now().minusWeeks(weeksAgo.toLong()).minusDays(6).toString()
            val end = java.time.LocalDate.now().minusWeeks(weeksAgo.toLong()).toString()
            Point(
                if (weeksAgo == 0) "now" else "-${weeksAgo}w",
                sessions.filter { it.localDate in start..end }.sumOf { it.minutes }.toDouble()
            )
        }
    }

    val activeTrack = tracks.firstOrNull { it.id == selected } ?: tracks.firstOrNull()
    val card = due.firstOrNull()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth()) {
        ModuleTitle("Learning", "Tracks, hours and spaced repetition")

        if (card != null) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Review · ${due.size} due", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(card.front, style = MaterialTheme.typography.bodyLarge)
                    if (revealed) {
                        Spacer(Modifier.height(8.dp))
                        Text(card.back, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "Again", 3 to "Hard", 4 to "Good", 5 to "Easy").forEach { (g, label) ->
                                Button(onClick = { vm.review(card, g); revealed = false }) { Text(label) }
                            }
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { revealed = true }) { Text("Show answer") }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newTrack, onValueChange = { newTrack = it },
                label = { Text("New track") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.addTrack(newTrack); newTrack = "" }) { Text("Add") }
        }

        if (tracks.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Starter ideas", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                STARTER_TRACKS.take(3).forEach { s ->
                    FilterChip(selected = false, onClick = { vm.addTrack(s) }, label = { Text(s) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                STARTER_TRACKS.drop(3).take(3).forEach { s ->
                    FilterChip(selected = false, onClick = { vm.addTrack(s) }, label = { Text(s) })
                }
            }
        }

        if (activeTrack != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tracks.take(4).forEach { t ->
                    FilterChip(
                        selected = activeTrack.id == t.id,
                        onClick = { selected = t.id },
                        label = { Text(t.name) }
                    )
                }
            }

            LearningBoost(container, activeTrack, sessions)
            val trackMinutes = sessions.filter { it.trackId == activeTrack.id }.sumOf { it.minutes }
            Spacer(Modifier.height(8.dp))
            Text("${trackMinutes / 60}h ${trackMinutes % 60}m logged on ${activeTrack.name}", style = MonoNumber)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = minutes, onValueChange = { minutes = it },
                    label = { Text("Minutes studied") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.logSession(activeTrack.id, minutes); minutes = "" }) { Text("Log") }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = front, onValueChange = { front = it },
                label = { Text("Card front") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = back, onValueChange = { back = it },
                label = { Text("Card back") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                vm.addCard(activeTrack.id, front, back); front = ""; back = ""
            }) { Text("Add flashcard") }
        }

        if (sessions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Study minutes per week", style = MaterialTheme.typography.titleMedium)
                    BarChart(points = weeklyStudy, accent = Spine.Learning)
                    StatRow(
                        listOf(
                            "this week" to "${weeklyStudy.lastOrNull()?.value?.toInt() ?: 0}m",
                            "total" to "${sessions.sumOf { it.minutes } / 60}h",
                            "cards due" to due.size.toString()
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

            items(tracks, key = { it.id }) { track ->
                val mins = sessions.filter { it.trackId == track.id }.sumOf { it.minutes }
                Row(
                    Modifier.fillMaxWidth().clickable { selected = track.id }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(track.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${mins / 60}h", style = MonoNumber)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "delete",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(role = Role.Button) { vm.deleteTrack(track) }
                    )
                }
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
    }
}
