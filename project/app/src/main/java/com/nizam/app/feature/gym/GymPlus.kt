package com.nizam.app.feature.gym

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.nizam.app.core.today
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Gym, overhauled: what to lift next (progressive overload from your own last session), personal
 * records the moment they happen, a rest timer that buzzes, the plates to load, sets per muscle this
 * week against a sensible range, your own exercises one tap away, and cardio that finally has a screen.
 */
object GymPlus {
    var restEnd by mutableLongStateOf(0L)
    private fun p(c: Context) = c.getSharedPreferences("gym_plus", 0)
    fun restSecs(c: Context) = p(c).getInt("rest", 90)
    fun setRest(c: Context, s: Int) = p(c).edit().putInt("rest", s).apply()
    fun startRest(c: Context) { restEnd = System.currentTimeMillis() + restSecs(c) * 1000L }

    val MUSCLES = linkedMapOf(
        "Chest" to listOf("bench", "chest", "fly", "push-up", "pushup", "dip", "pec"),
        "Back" to listOf("row", "pull", "lat", "deadlift", "chin"),
        "Shoulders" to listOf("overhead", "shoulder", "lateral", "military", "arnold", "face pull", "ohp"),
        "Legs" to listOf("squat", "leg", "lunge", "deadlift", "calf", "hip thrust", "rdl"),
        "Arms" to listOf("curl", "tricep", "extension", "dip", "skull", "hammer"),
        "Core" to listOf("plank", "crunch", "ab", "core", "sit-up", "leg raise"))

    fun musclesOf(ex: String): List<String> = ex.lowercase().let { e -> MUSCLES.filter { (_, ws) -> ws.any { e.contains(it) } }.keys.toList() }

    /** The next set to aim for, from the last session of this exercise. */
    fun suggest(sets: List<WorkoutSet>, ex: String): Pair<Double, Int>? {
        val prev = sets.filter { it.exercise.equals(ex, true) && it.localDate != today() }
        val lastDay = prev.maxOfOrNull { it.localDate } ?: return null
        val top = prev.filter { it.localDate == lastDay }.maxWithOrNull(compareBy<WorkoutSet> { it.weight }.thenBy { it.reps }) ?: return null
        val step = if (top.weight >= 60) 2.5 else 1.25
        return if (top.reps >= 10) (top.weight + step) to 8 else top.weight to (top.reps + 1)
    }

    /** Plates per side for [total] on a 20 kg bar. */
    fun plates(total: Double, bar: Double = 20.0): List<Double>? {
        var side = (total - bar) / 2
        if (side < 0) return null
        val out = ArrayList<Double>()
        for (pl in listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)) while (side >= pl - 1e-6) { out += pl; side -= pl }
        return if (side > 0.01) null else out
    }

    fun fmt(w: Double) = if (w == w.roundToInt().toDouble()) w.roundToInt().toString() else "%.2f".format(w).trimEnd('0').trimEnd('.')
}

@Composable
private fun Pill(text: String, on: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(text, Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.primary.copy(alpha = 0.16f) else cs.surfaceVariant.copy(alpha = 0.6f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = if (on) cs.primary else cs.onSurfaceVariant)
}

/** Your own exercises first, then the common ones. */
@Composable
fun ExercisePicker(sets: List<WorkoutSet>, exercise: String, pick: (String) -> Unit) {
    val mine = remember(sets) { sets.groupingBy { it.exercise.trim() }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (mine + COMMON_EXERCISES + listOf("Lateral raise", "Tricep pushdown", "Romanian deadlift", "Leg curl", "Calf raise", "Plank", "Hip thrust"))
            .distinctBy { it.lowercase() }.forEach { e -> Pill(e, exercise.equals(e, true)) { pick(e) } }
    }
}

/** Coach card: next-set target, steppers, plates, rest timer. */
@Composable
fun GymCoach(sets: List<WorkoutSet>, exercise: String, weight: String, reps: String, setWeight: (String) -> Unit, setReps: (String) -> Unit) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val sug = remember(sets, exercise) { GymPlus.suggest(sets, exercise) }
    com.nizam.app.ui.design.Group(Modifier.padding(vertical = 8.dp)) {
        sug?.let { (w, r) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Aim for ${GymPlus.fmt(w)} kg × $r", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("Progressive overload from your last session", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = { setWeight(GymPlus.fmt(w)); setReps(r.toString()) }) { Text("Use") }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val w = weight.toDoubleOrNull() ?: 0.0
            Pill("−2.5") { setWeight(GymPlus.fmt((w - 2.5).coerceAtLeast(0.0))) }
            Pill("+2.5") { setWeight(GymPlus.fmt(w + 2.5)) }
            Box(Modifier.width(8.dp))
            val r = reps.toIntOrNull() ?: 0
            Pill("−1 rep") { setReps((r - 1).coerceAtLeast(0).toString()) }
            Pill("+1 rep") { setReps((r + 1).toString()) }
        }
        weight.toDoubleOrNull()?.takeIf { it >= 20 }?.let { w ->
            val pl = GymPlus.plates(w)
            Text(if (pl == null) "Can't load ${GymPlus.fmt(w)} kg exactly on a 20 kg bar"
                else if (pl.isEmpty()) "Just the bar" else "Each side: " + pl.joinToString(" + ") { GymPlus.fmt(it) },
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        RestTimer()
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            var rest by remember { mutableStateOf(GymPlus.restSecs(c)) }
            Text("Rest", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            listOf(60, 90, 120, 180).forEach { s -> Pill(if (s < 120) "${s}s" else "${s / 60}m", rest == s) { rest = s; GymPlus.setRest(c, s) } }
        }
    }
}

@Composable
private fun RestTimer() {
    val c = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val end = GymPlus.restEnd
    LaunchedEffect(end) {
        com.nizam.app.ui.design.guard {
            var buzzed = false
            while (end > 0 && System.currentTimeMillis() < end + 1500) {
                delay(250); now = System.currentTimeMillis()
                if (!buzzed && now >= end) {
                    buzzed = true
                    runCatching {
                        val v = c.getSystemService(android.os.Vibrator::class.java)
                        v?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
                    }
                    Feedback.show("Rest's over. Next set")
                }
            }
        }
    }
    val left = ((end - now) / 1000).toInt()
    if (end > 0 && left >= 0) {
        val cs = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Resting ${left / 60}:${"%02d".format(left % 60)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = if (left <= 10) Color(0xFFE8A13A) else cs.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = { GymPlus.restEnd += 30_000 }) { Text("+30s") }
            TextButton(onClick = { GymPlus.restEnd = 0 }) { Text("Skip") }
        }
    }
}

/** Today's session grouped by exercise, and hard sets per muscle this week. */
@Composable
fun GymWeek(sets: List<WorkoutSet>) {
    val cs = MaterialTheme.colorScheme
    val t = today()
    val session = remember(sets) { sets.filter { it.localDate == t }.reversed().groupBy { it.exercise } }
    val since = LocalDate.now().minusDays(6).toString()
    val perMuscle = remember(sets) {
        val m = LinkedHashMap<String, Int>(); GymPlus.MUSCLES.keys.forEach { m[it] = 0 }
        sets.filter { it.localDate >= since }.forEach { s -> GymPlus.musclesOf(s.exercise).forEach { k -> m[k] = (m[k] ?: 0) + 1 } }
        m
    }
    com.nizam.app.ui.design.Group(Modifier.padding(vertical = 8.dp)) {
        if (session.isNotEmpty()) {
            Text("Today's session", fontWeight = FontWeight.SemiBold)
            session.forEach { (ex, l) ->
                Text("$ex  ·  " + l.joinToString("  ") { "${GymPlus.fmt(it.weight)}×${it.reps}" }, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp))
            }
        }
        Text("Sets per muscle, last 7 days", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = if (session.isNotEmpty()) 12.dp else 0.dp))
        Text("10–20 hard sets a week is a common growth range", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        perMuscle.forEach { (m, n) ->
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m, Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
                    val col = when { n == 0 -> cs.surfaceVariant; n < 10 -> Color(0xFFE8A13A); n <= 20 -> Color(0xFF2E9E6A); else -> cs.error }
                    Box(Modifier.fillMaxWidth((n / 20f).coerceIn(0.02f, 1f)).height(8.dp).background(col))
                }
                Text("$n", Modifier.width(32.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Cardio was stored but never had a screen: now it does. */
@Composable
fun CardioCard(container: AppContainer) {
    val flow = remember { container.gymRepository.cardio() }
    val list by androidx.compose.runtime.produceState(initialValue = emptyList<Cardio>(), flow) { flow.collect { value = it } }
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var type by remember { mutableStateOf("Walk") }
    var mins by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    com.nizam.app.ui.design.Group(Modifier.padding(vertical = 8.dp)) {
        Text("Cardio", fontWeight = FontWeight.SemiBold)
        val weekMin = list.filter { it.localDate >= LocalDate.now().minusDays(6).toString() }.sumOf { it.minutes }
        Text("$weekMin of 150 active minutes this week", style = MaterialTheme.typography.bodySmall, color = if (weekMin >= 150) cs.primary else cs.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Walk", "Run", "Cycle", "Swim", "Treadmill", "Cricket", "Football", "Badminton").forEach { t -> Pill(t, type == t) { type = t } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(mins, { mins = it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine = true, label = { Text("Minutes") })
            Box(Modifier.width(8.dp))
            OutlinedTextField(km, { km = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f), singleLine = true, label = { Text("km (optional)") })
            TextButton(onClick = {
                val m = mins.toIntOrNull() ?: return@TextButton
                scope.launch { runCatching { container.gymRepository.addCardio(type, m, km.toDoubleOrNull() ?: 0.0) }; mins = ""; km = ""; Feedback.show("$type logged") }
            }, enabled = (mins.toIntOrNull() ?: 0) > 0) { Text("Log") }
        }
        list.take(5).forEach { x ->
            Text("${com.nizam.app.core.prettyDate(x.localDate)} · ${x.type} · ${x.minutes} min" + (if (x.distanceKm > 0) " · ${"%.1f".format(x.distanceKm)} km" +
                (if (x.minutes > 0) " (${"%.1f".format(x.distanceKm / (x.minutes / 60.0))} km/h)" else "") else ""),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
