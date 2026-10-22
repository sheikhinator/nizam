package com.nizam.app.feature.glasses

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.xr.projected.ProjectedDeviceController
import androidx.xr.projected.ProjectedDisplayController
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import com.nizam.app.AppContainer
import com.nizam.app.NizamApplication
import com.nizam.app.data.db.Task
import com.nizam.app.feature.gym.WorkoutSet
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.ui.theme.NizamTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class GlassesLaunchState {
    CHECKING,
    READY,
    DISPLAY_NOT_SUPPORTED,
    PIN_LOCKED,
    LOST_MODE,
    ERROR
}

@OptIn(ExperimentalProjectedApi::class)
class AtlasGlassesCueActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private var launchState by mutableStateOf(GlassesLaunchState.CHECKING)
    private var launchMessage by mutableStateOf<String?>(null)
    private var accessCheck: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = (application as NizamApplication).container

        setContent {
            NizamTheme(darkTheme = true) {
                AtlasGlassesCueScreen(
                    container = container,
                    launchState = launchState,
                    launchMessage = launchMessage,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessCheck?.cancel()
        launchState = GlassesLaunchState.CHECKING
        launchMessage = null
        accessCheck = lifecycleScope.launch {
            try {
                if (com.nizam.app.feature.next.LostMode.active(this@AtlasGlassesCueActivity)) {
                    launchState = GlassesLaunchState.LOST_MODE
                    return@launch
                }
                if (container.settingsRepository.appPin.first().isNotBlank()) {
                    launchState = GlassesLaunchState.PIN_LOCKED
                    return@launch
                }

                ProjectedDeviceController.create(this@AtlasGlassesCueActivity).close()
                // This library version has no capability list: a display controller only opens on glasses with a screen.
                launchState = try {
                    ProjectedDisplayController.create(this@AtlasGlassesCueActivity).close()
                    GlassesLaunchState.READY
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    GlassesLaunchState.DISPLAY_NOT_SUPPORTED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("AtlasGlasses", "Could not check projected display support", error)
                launchMessage = "Android XR couldn't confirm display support. Try opening Atlas from display glasses."
                launchState = GlassesLaunchState.ERROR
            }
        }
    }
}

@Composable
private fun AtlasGlassesCueScreen(
    container: AppContainer,
    launchState: GlassesLaunchState,
    launchMessage: String?,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        when (launchState) {
            GlassesLaunchState.CHECKING ->
                GlassesStatusContent("Checking display support…", "Atlas is not using the camera or microphone.", onClose)
            GlassesLaunchState.DISPLAY_NOT_SUPPORTED ->
                GlassesStatusContent("Display not available", "Use Atlas on supported Android XR display glasses.", onClose)
            GlassesLaunchState.PIN_LOCKED ->
                GlassesStatusContent("Atlas PIN is enabled", "Glasses cues stay hidden because a separate glasses unlock flow is not available.", onClose)
            GlassesLaunchState.LOST_MODE ->
                GlassesStatusContent("Atlas is in Lost Mode", "Glasses cues stay hidden while Lost Mode is active.", onClose)
            GlassesLaunchState.ERROR ->
                GlassesStatusContent("Can't start glasses cues", launchMessage ?: "Try again from display glasses.", onClose)
            GlassesLaunchState.READY ->
                GlassesCueContent(container, onClose)
        }
    }
}

@Composable
private fun GlassesStatusContent(
    title: String,
    detail: String,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            detail,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.padding(top = 24.dp).heightIn(min = 56.dp)
        ) {
            Text("Close")
        }
    }
}

@Composable
private fun GlassesCueContent(
    container: AppContainer,
    onClose: () -> Unit
) {
    val tasksFlow = remember(container) { container.taskRepository.all() }
    val workoutsFlow = remember(container) { container.gymRepository.sets() }
    val latitudeFlow = remember(container) { container.settingsRepository.latitude }
    val longitudeFlow = remember(container) { container.settingsRepository.longitude }
    val methodFlow = remember(container) { container.settingsRepository.prayerMethod }
    val hanafiFlow = remember(container) { container.settingsRepository.hanafiAsr }

    val tasks by tasksFlow.collectAsState(initial = emptyList())
    val workouts by workoutsFlow.collectAsState(initial = emptyList())
    val latitude by latitudeFlow.collectAsState(initial = 31.5204)
    val longitude by longitudeFlow.collectAsState(initial = 74.3587)
    val method by methodFlow.collectAsState(initial = "KARACHI")
    val hanafiAsr by hanafiFlow.collectAsState(initial = true)

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedCue by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        while (true) {
            delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    } }

    val nextTask = remember(tasks, nowMillis) { chooseNextTask(tasks, nowMillis) }
    val nextPrayer = remember(nowMillis, latitude, longitude, method, hanafiAsr) {
        findNextPrayer(nowMillis, latitude, longitude, method, hanafiAsr)
    }
    val cues = listOf(
        GlassesCue(
            label = "NEXT STEP",
            title = nextTask?.title ?: "No open tasks",
            detail = nextTask?.let { taskDueHint(it, nowMillis) } ?: "Add a task in Atlas to see it here."
        ),
        workoutCue(workouts.firstOrNull()),
        GlassesCue(
            label = "REMINDER",
            title = nextPrayer?.name ?: "No prayer time available",
            detail = nextPrayer?.let { formatPrayerHint(it, nowMillis) }
                ?: "Check your location and prayer method in Atlas."
        )
    )
    val cue = cues[selectedCue % cues.size]

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "ATLAS",
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
            color = MaterialTheme.colorScheme.primary
        )

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                cue.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Text(
                cue.title,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 34.sp, lineHeight = 40.sp),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                cue.detail,
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { selectedCue = (selectedCue + 1) % cues.size },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) {
                    Text("Next cue")
                }
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = 56.dp)
                ) {
                    Text("Close")
                }
            }
            Text(
                "Tap to cycle through your cues",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class GlassesCue(
    val label: String,
    val title: String,
    val detail: String
)

private data class PrayerCue(
    val name: String,
    val time: ZonedDateTime
)

private fun chooseNextTask(tasks: List<Task>, nowMillis: Long): Task? =
    tasks.asSequence()
        .filter { it.completedAt == null && it.deletedAt == null }
        .minWithOrNull(
            compareBy<Task> { it.due ?: Long.MAX_VALUE }
                .thenByDescending { it.priority }
                .thenBy { it.createdAt }
        )

private fun taskDueHint(task: Task, nowMillis: Long): String {
    val due = task.due ?: return "Open task · no due time"
    if (due <= nowMillis) return "Overdue"
    val minutes = ((due - nowMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
    if (minutes < 60) return "Due in $minutes min"
    val zone = ZoneId.systemDefault()
    val dueDate = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return when (dueDate) {
        today -> "Due today"
        today.plusDays(1) -> "Due tomorrow"
        else -> "Due ${dueDate.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))}"
    }
}

private fun workoutCue(set: WorkoutSet?): GlassesCue {
    if (set == null) {
        return GlassesCue("WORKOUT", "No workout logged", "Log a set in Atlas to see your latest cue.")
    }
    val weight = if (set.weight % 1.0 == 0.0) {
        set.weight.toInt().toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", set.weight)
    }
    val date = runCatching {
        LocalDate.parse(set.localDate).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
    }.getOrDefault(set.localDate)
    return GlassesCue("WORKOUT", set.exercise, "$weight kg × ${set.reps} reps · $date")
}

private fun findNextPrayer(
    nowMillis: Long,
    latitude: Double,
    longitude: Double,
    methodName: String,
    hanafiAsr: Boolean
): PrayerCue? {
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val method = runCatching { PrayerTimes.Method.valueOf(methodName) }
        .getOrDefault(PrayerTimes.Method.KARACHI)
    val candidates = mutableListOf<PrayerCue>()

    for (offset in 0..1) {
        val date = now.toLocalDate().plusDays(offset.toLong())
        val times = runCatching {
            PrayerTimes.calculate(date, latitude, longitude, method, hanafiAsr, zone)
        }.getOrNull() ?: continue
        val prayers = listOf(
            "Fajr" to times.fajr,
            "Dhuhr" to times.dhuhr,
            "Asr" to times.asr,
            "Maghrib" to times.maghrib,
            "Isha" to times.isha
        )
        prayers.forEach { (name, decimalHours) ->
            if (!decimalHours.isFinite()) return@forEach
            val minuteOfDay = (decimalHours * 60.0).roundToInt().coerceIn(0, 1439)
            val prayerTime = date.atStartOfDay(zone).plusMinutes(minuteOfDay.toLong())
            if (prayerTime.isAfter(now)) candidates += PrayerCue(name, prayerTime)
        }
    }
    return candidates.minByOrNull { it.time.toInstant() }
}

private fun formatPrayerHint(cue: PrayerCue, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val dayLabel = if (cue.time.toLocalDate() == today) "Today" else "Tomorrow"
    val timeLabel = cue.time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    return "$dayLabel · $timeLabel"
}