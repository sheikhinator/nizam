package com.nizam.app.feature.missions

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.ModeNavigationStore
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.Tones
import com.nizam.app.core.glyphFor
import com.nizam.app.core.prettyDate
import com.nizam.app.core.vmFactory
import com.nizam.app.net.Ai
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MissionsViewModel(private val repository: MissionRepository) : ViewModel() {

    val goals: StateFlow<List<Goal>> = repository.goals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todayMissions: StateFlow<List<Mission>> = repository.today()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _progress = MutableStateFlow<Map<Long, Verifier.Progress>>(emptyMap())
    val progress: StateFlow<Map<Long, Verifier.Progress>> = _progress.asStateFlow()

    fun missionsFor(goalId: Long) = repository.forGoal(goalId)

    private val _checkMessage = MutableStateFlow<String?>(null)
    val checkMessage: StateFlow<String?> = _checkMessage.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun refreshProgress(announce: Boolean = false) {
        viewModelScope.launch {
            if (announce) _checking.value = true
            val newlyDone = repository.verifyToday()
            val map = mutableMapOf<Long, Verifier.Progress>()
            todayMissions.value.forEach { mission ->
                repository.progressFor(mission)?.let { map[mission.id] = it }
            }
            _progress.value = map
            if (announce) {
                val list = todayMissions.value
                val done = list.count { it.completedAt != null }
                _checkMessage.value = "Checked against your data · $done of ${list.size} complete" +
                    if (newlyDone > 0) " · $newlyDone just verified" else ""
                _checking.value = false
            }
        }
    }

    fun generateToday(force: Boolean, intensity: String = "NORMAL") {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                repository.generateDaily(force, intensity)
                refreshProgress()
            } catch (e: Ai.MissingKeyException) {
                _error.value = "Add an AI key first — Settings › API keys."
            } catch (e: Exception) {
                _error.value = "Could not generate missions: ${com.nizam.app.ui.components.Feedback.friendly(e)}"
            }
            _busy.value = false
        }
    }

    fun createGoal(title: String, why: String, deadline: String, onCreated: (Long) -> Unit) {
        if (title.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                onCreated(repository.createGoal(title, why, deadline))
            } catch (e: Ai.MissingKeyException) {
                _error.value = "Add an AI key first — Settings › API keys."
            } catch (e: Exception) {
                _error.value = "Could not build the plan: ${com.nizam.app.ui.components.Feedback.friendly(e)}"
            }
            _busy.value = false
        }
    }

    fun toggle(mission: Mission) = viewModelScope.launch { repository.toggle(mission) }
    fun delete(mission: Mission) = viewModelScope.launch { repository.deleteMission(mission) }
    fun deleteGoal(goal: Goal) = viewModelScope.launch { repository.deleteGoal(goal) }
    fun closeGoal(goal: Goal, done: Boolean) = viewModelScope.launch { repository.closeGoal(goal, done) }
}

@Composable
fun MissionsScreen(container: AppContainer) {
    val context = LocalContext.current
    val modeStore = remember(context) { ModeNavigationStore.get(context) }
    val modeState by modeStore.state.collectAsState()
    val vm: MissionsViewModel = viewModel(
        factory = vmFactory { MissionsViewModel(container.missionRepository) }
    )
    val goals by vm.goals.collectAsState()
    val missions by vm.todayMissions.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val progress by vm.progress.collectAsState()
    val checking by vm.checking.collectAsState()
    val checkMessage by vm.checkMessage.collectAsState()

    var openGoal by remember { mutableStateOf<Goal?>(null) }
    var newGoal by remember { mutableStateOf("") }
    var why by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }

    LaunchedEffect(missions.size) { com.nizam.app.ui.design.guard { vm.refreshProgress() } }

    val goal = openGoal
    if (goal != null) {
        GoalDetail(vm = vm, goal = goal, onBack = { openGoal = null })
        return
    }

    val done = missions.count { it.completedAt != null }
    val earned = missions.filter { it.completedAt != null }.sumOf { it.xp }
    val available = missions.sumOf { it.xp }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Missions", "Concrete actions, checked against your own data")
        val intensityHint = when (modeState.activeMode.missionIntensity) {
            "GENTLE" -> "Gentle pace · fewer, smaller actions that leave room for recovery."
            "FOCUSED" -> "Focused pace · one priority action gets a realistic timebox."
            else -> "Normal pace · balanced actions calibrated to your recent activity."
        }
        Text(
            "${modeState.activeMode.name} · ${modeState.activeMode.missionIntensity.lowercase().replaceFirstChar { it.uppercase() }}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(intensityHint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Today", style = MaterialTheme.typography.titleLarge)
                    Text("$earned / $available XP", style = MonoNumber)
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (missions.isEmpty()) 0f else done.toFloat() / missions.size },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        vm.generateToday(missions.isNotEmpty(), modeState.activeMode.missionIntensity)
                    }) {
                        Text(
                            when {
                                busy -> "Thinking…"
                                missions.isEmpty() -> "Generate today's missions"
                                else -> "Regenerate"
                            }
                        )
                    }
                    OutlinedButton(enabled = !checking, onClick = { vm.refreshProgress(announce = true) }) {
                        Text(if (checking) "Checking…" else "Check progress")
                    }
                }
            }
        }

        checkMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))

        missions.forEach { mission ->
            MissionCard(
                mission = mission, progress = progress[mission.id],
                onToggle = { vm.toggle(mission) }, onDelete = { vm.delete(mission) }
            )
        }

        if (missions.isEmpty() && !busy) {
            Text(
                "No missions yet today. Generate a set, or add a goal below and get a whole campaign.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(16.dp))

        Text("GOALS", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = newGoal, onValueChange = { newGoal = it },
            label = { Text("What do you want to happen?") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = why, onValueChange = { why = it },
            label = { Text("Why it matters (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = deadline, onValueChange = { deadline = it },
            label = { Text("By when — e.g. this week, 30 Sep") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Button(
            enabled = !busy && newGoal.isNotBlank(),
            onClick = {
                vm.createGoal(newGoal, why, deadline) { }
                newGoal = ""; why = ""; deadline = ""
            }
        ) { Text(if (busy) "Planning…" else "Build the plan") }

        if (busy) {
            Spacer(Modifier.height(10.dp))
            CircularProgressIndicator()
        }

        Spacer(Modifier.height(16.dp))
        goals.forEach { g ->
            val tones = Tones.forKey(g.title)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = tones.tone.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clickable { openGoal = g }
            ) {
                Row(Modifier.padding(16.dp)) {
                    Text(glyphFor(g.title), style = MaterialTheme.typography.titleLarge, color = tones.accent)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(g.title, style = MaterialTheme.typography.titleMedium)
                        if (g.deadline.isNotBlank()) {
                            Text(
                                "by ${g.deadline}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun MissionCard(
    mission: Mission,
    progress: Verifier.Progress?,
    onToggle: () -> Unit,
    onDelete: () -> Unit = {}
) {
    val tones = Tones.forKey(mission.title)
    val complete = mission.completedAt != null
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (complete) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = complete, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f)) {
                    Text(
                        mission.title,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (complete) TextDecoration.LineThrough else null
                    )
                    Text(
                        "${Difficulty.label(mission.difficulty)} · ${mission.xp} XP" +
                            if (mission.autoVerified) " · verified" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = tones.accent
                    )
                }
                androidx.compose.material3.IconButton(onClick = onDelete) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Delete,
                        contentDescription = "Delete mission",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (mission.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(mission.detail, style = MaterialTheme.typography.bodyLarge)
            }
            if (mission.rationale.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    mission.rationale,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (progress != null && mission.metric != "MANUAL") {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        if (progress.target <= 0) 0f
                        else (progress.current / progress.target).toFloat().coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${progress.current.toInt()} / ${progress.target.toInt()} ${mission.unit}".trim(),
                    style = MonoNumber,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GoalDetail(vm: MissionsViewModel, goal: Goal, onBack: () -> Unit) {
    val missions by vm.missionsFor(goal.id).collectAsState(initial = emptyList())
    val tones = Tones.forKey(goal.title)
    val done = missions.count { it.completedAt != null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to missions")
            }
            Text("Goal", style = MaterialTheme.typography.titleMedium)
        }
        Text(goal.title, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFamily))
        if (goal.deadline.isNotBlank()) {
            Text(
                "by ${goal.deadline}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (goal.strategy.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = tones.tone.copy(alpha = 0.12f)) {
                Text(goal.strategy, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("$done / ${missions.size} missions done", style = MonoNumber)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (missions.isEmpty()) 0f else done.toFloat() / missions.size },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(18.dp))

        var lastDate = ""
        missions.forEach { mission ->
            if (mission.localDate != lastDate) {
                lastDate = mission.localDate
                Spacer(Modifier.height(8.dp))
                Text(
                    prettyDate(mission.localDate).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
            MissionCard(
                mission = mission, progress = null,
                onToggle = { vm.toggle(mission) }, onDelete = { vm.delete(mission) }
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.closeGoal(goal, true); onBack() }) { Text("Goal achieved") }
            OutlinedButton(onClick = { vm.closeGoal(goal, false); onBack() }) { Text("Drop it") }
            OutlinedButton(onClick = { vm.deleteGoal(goal); onBack() }) { Text("Delete") }
        }
        Spacer(Modifier.height(48.dp))
    }
}
