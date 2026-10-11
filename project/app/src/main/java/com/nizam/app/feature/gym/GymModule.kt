package com.nizam.app.feature.gym

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.nizam.app.ui.design.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.nizam.app.AppContainer
import com.nizam.app.core.AiPanel
import com.nizam.app.core.BarChart
import com.nizam.app.core.LineChart
import com.nizam.app.core.Point
import com.nizam.app.core.StatRow
import com.nizam.app.core.ModuleTitle
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
import kotlin.math.roundToInt

@Entity(tableName = "workout_set")
data class WorkoutSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localDate: String,
    val exercise: String,
    val weight: Double,
    val reps: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "cardio")
data class Cardio(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localDate: String,
    val type: String,
    val minutes: Int,
    val distanceKm: Double = 0.0
)

@Dao
interface GymDao {
    @Query("SELECT * FROM workout_set ORDER BY localDate DESC, id DESC LIMIT 400")
    fun observeSets(): Flow<List<WorkoutSet>>

    @Query("SELECT * FROM workout_set ORDER BY localDate ASC, id ASC")
    suspend fun allSetsForHealthConnect(): List<WorkoutSet>

    @Query("SELECT * FROM workout_set WHERE exercise = :exercise ORDER BY id DESC LIMIT 20")
    fun observeForExercise(exercise: String): Flow<List<WorkoutSet>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(set: WorkoutSet)

    @Delete
    suspend fun delete(set: WorkoutSet)

    @Query("SELECT * FROM cardio ORDER BY localDate DESC LIMIT 60")
    fun observeCardio(): Flow<List<Cardio>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCardio(cardio: Cardio)
}

class GymRepository(private val dao: GymDao) {
    fun sets() = dao.observeSets()
    suspend fun setsForHealthConnect() = dao.allSetsForHealthConnect()
    fun cardio() = dao.observeCardio()
    suspend fun addSet(exercise: String, weight: Double, reps: Int) {
        dao.insert(WorkoutSet(localDate = today(), exercise = exercise.trim(), weight = weight, reps = reps))
    }
    suspend fun remove(set: WorkoutSet) = dao.delete(set)
    suspend fun addCardio(type: String, minutes: Int, km: Double) {
        dao.insertCardio(Cardio(localDate = today(), type = type, minutes = minutes, distanceKm = km))
    }
}

val COMMON_EXERCISES = listOf(
    "Bench press", "Squat", "Deadlift", "Overhead press", "Barbell row",
    "Pull-up", "Dip", "Lat pulldown", "Leg press", "Bicep curl"
)

/** Epley estimate. */
fun oneRepMax(weight: Double, reps: Int): Double =
    if (reps <= 1) weight else weight * (1 + reps / 30.0)

class GymViewModel(private val repository: GymRepository) : ViewModel() {
    val sets: StateFlow<List<WorkoutSet>> = repository.sets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addSet(exercise: String, weight: String, reps: String) {
        val w = weight.toDoubleOrNull() ?: return
        val r = reps.toIntOrNull() ?: return
        if (exercise.isBlank()) return
        viewModelScope.launch { repository.addSet(exercise, w, r) }
    }

    fun delete(set: WorkoutSet) = viewModelScope.launch { repository.remove(set) }
}

@Composable
fun GymScreen(container: AppContainer) {
    val vm: GymViewModel = viewModel(factory = vmFactory { GymViewModel(container.gymRepository) })
    val sets by vm.sets.collectAsState()

    var exercise by remember { mutableStateOf(COMMON_EXERCISES.first()) }
    var weight by remember { mutableStateOf("") }
    var reps by remember { mutableStateOf("") }
    var genPrompt by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var genStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val todaySets = sets.filter { it.localDate == today() }
    val todayVolume = todaySets.sumOf { it.weight * it.reps }
    val weekStart = java.time.LocalDate.now().minusDays(6).toString()
    val weekSets = sets.filter { it.localDate >= weekStart }

    // Volume per week for the last eight weeks
    val weeklyVolume = remember(sets) {
        (7 downTo 0).map { weeksAgo ->
            val start = java.time.LocalDate.now().minusWeeks(weeksAgo.toLong()).minusDays(6)
            val end = java.time.LocalDate.now().minusWeeks(weeksAgo.toLong())
            val volume = sets.filter {
                it.localDate >= start.toString() && it.localDate <= end.toString()
            }.sumOf { it.weight * it.reps }
            Point(if (weeksAgo == 0) "now" else "-${weeksAgo}w", volume)
        }
    }

    // Estimated 1RM over time for whichever exercise is selected
    val exerciseSets = sets.filter { it.exercise.equals(exercise, true) }
    val progression = remember(sets, exercise) {
        exerciseSets.groupBy { it.localDate }
            .toSortedMap()
            .map { (date, daySets) ->
                Point(date.takeLast(5), daySets.maxOf { oneRepMax(it.weight, it.reps) })
            }
            .takeLast(20)
    }
    val bestE1rm = exerciseSets.maxOfOrNull { oneRepMax(it.weight, it.reps) } ?: 0.0
    val exerciseSessions = exerciseSets.map { it.localDate }.distinct().size
    val topSet = exerciseSets.maxByOrNull { it.weight }
        ?.let { "${it.weight.roundToInt()}×${it.reps}" } ?: "—"
    val progressionChange = if (progression.size >= 2) {
        val delta = progression.last().value - progression.first().value
        (if (delta >= 0) "+" else "") + "${delta.roundToInt()} kg"
    } else ""

    val lastForExercise = sets.firstOrNull { it.exercise == exercise && it.localDate != today() }
    val best = sets.filter { it.exercise == exercise }
        .maxByOrNull { oneRepMax(it.weight, it.reps) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth()) {
        ModuleTitle("Gym", "Log sets as you go")

        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                StatRow(
                    listOf(
                        "sets" to todaySets.size.toString(),
                        "volume" to "${todayVolume.roundToInt()} kg",
                        "week" to weekSets.size.toString()
                    )
                )

                // Weekly volume, oldest week first — the number that actually predicts progress
                Spacer(Modifier.height(10.dp))
                Text("WEEKLY VOLUME", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BarChart(points = weeklyVolume, accent = Spine.Gym)
            }
        }

        if (progression.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$exercise — estimated 1RM",
                            style = MaterialTheme.typography.titleMedium)
                        Text("${progressionChange}", style = MonoNumber, color = Spine.Gym)
                    }
                    LineChart(points = progression, accent = Spine.Gym)
                    StatRow(
                        listOf(
                            "best e1RM" to "${bestE1rm.roundToInt()} kg",
                            "sessions" to exerciseSessions.toString(),
                            "top set" to topSet
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            COMMON_EXERCISES.take(3).forEach { e ->
                FilterChip(selected = exercise == e, onClick = { exercise = e }, label = { Text(e) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            COMMON_EXERCISES.drop(3).take(3).forEach { e ->
                FilterChip(selected = exercise == e, onClick = { exercise = e }, label = { Text(e) })
            }
        }
        OutlinedTextField(
            value = exercise,
            onValueChange = { exercise = it },
            label = { Text("Exercise") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (lastForExercise != null) {
            Text(
                "Last time: ${lastForExercise.weight} kg × ${lastForExercise.reps}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (best != null) {
            Text(
                "Best e1RM: ${oneRepMax(best.weight, best.reps).roundToInt()} kg",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = weight, onValueChange = { weight = it },
                label = { Text("kg") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = reps, onValueChange = { reps = it },
                label = { Text("Reps") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.addSet(exercise, weight, reps)
                reps = ""
            }) { Text("Log set") }
            OutlinedButton(
                enabled = !generating,
                onClick = {
                    scope.launch {
                        generating = true; genStatus = null
                        genStatus = try {
                            container.moduleGenerator.generateWorkout(genPrompt)
                        } catch (e: Exception) { e.message }
                        generating = false
                    }
                }
            ) { Text(if (generating) "Writing…" else "✦  Write my session") }
            if (generating) CircularProgressIndicator()
        }
        OutlinedTextField(
            value = genPrompt, onValueChange = { genPrompt = it },
            label = { Text("Session focus (optional) — e.g. push day, 45 min") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        genStatus?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))
        AiPanel(
            settings = container.settingsRepository,
            label = "Training help",
            contextPrompt = "Recent sets, newest first: " +
                sets.take(40).joinToString("; ") { "${it.localDate} ${it.exercise} ${it.weight}kg x ${it.reps}" },
            starterQuestion = "Look at my progression and tell me what to change.",
            system = "You are an experienced strength coach. Analyse the actual numbers given, name " +
                "specific problems, and give concrete programming changes. Flag anything that looks " +
                "like an injury risk."
        )
        Spacer(Modifier.height(12.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)

        if (sets.isEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "No sets logged yet. Log one above, or tap \"Write my session\" and Atlas builds " +
                    "the whole workout.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
            }
        }

            items(sets, key = { it.id }) { set ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(set.exercise, style = MaterialTheme.typography.bodyLarge)
                        Text(prettyDate(set.localDate), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${set.weight} × ${set.reps}", style = MonoNumber)
                    IconButton(onClick = { vm.delete(set) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete set")
                    }
                }
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
    }
}
