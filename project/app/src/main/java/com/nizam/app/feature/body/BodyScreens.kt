package com.nizam.app.feature.body

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.hourToClock
import com.nizam.app.feature.growth.FocusFlag
import com.nizam.app.feature.insight.FocusStore
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.feature.voice.Speaker
import com.nizam.app.net.Ai
import com.nizam.app.net.Vision
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

// ── Snap your plate ───────────────────────────────────────────────────────────
@Composable
fun SnapPlateScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val shot = remember { File(c.cacheDir, "plate.jpg") }
    val uri = remember { FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", shot) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var slot by remember { mutableStateOf(when (LocalTime.now().hour) { in 4..10 -> "Breakfast"; in 11..15 -> "Lunch"; in 16..18 -> "Snack"; else -> "Dinner" }) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; status = "Looking at your plate…"; result = null
            runCatching {
                val bmp = BitmapFactory.decodeFile(shot.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                    ?: throw IllegalStateException("Couldn't read the photo.")
                val raw = Vision.ask(container.settingsRepository,
                    "Identify the food on this plate (South Asian dishes are common: roti, daal, salan, biryani, etc). Estimate " +
                        "portion sizes realistically from the plate and utensils. Return ONLY JSON: " +
                        "{\"items\":[{\"name\":\"...\",\"portion\":\"...\",\"kcal\":0,\"protein\":0}],\"kcal\":0,\"protein\":0," +
                        "\"confidence\":\"low|medium|high\",\"note\":\"one line\"}", bmp)
                result = runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrElse {
                    // some models answer in prose; pull the JSON object out of it
                    val block = Regex("\\{[\\s\\S]*\\}").find(raw)?.value
                        ?: throw IllegalStateException("the model replied in text, not JSON — try again or use a vision model")
                    JSONObject(block)
                }
                status = null
            }.onFailure {
                status = "Couldn't read the plate: " + com.nizam.app.ui.components.Feedback.friendly(it) + ". Tap the photo button to retry."
                com.nizam.app.ui.components.Feedback.failed("Plate reading", it)
            }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Snap your plate", "Photograph a meal, get calories and protein, log it in one tap")
        ChipRow(listOf("Breakfast", "Lunch", "Snack", "Dinner"), slot, { slot = it })
        Button(enabled = !busy, onClick = { camera.launch(uri) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("📷  Take a photo") }
        Text("Uses your AI's vision — Gemini works out of the box; other providers need a model that accepts images.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
        result?.let { r ->
            val items = r.optJSONArray("items")
            Card {
                Text("${r.optInt("kcal")} kcal · ${r.optInt("protein")}g protein", style = MaterialTheme.typography.titleLarge)
                Text("Confidence: ${r.optString("confidence")}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (items != null) for (i in 0 until items.length()) items.getJSONObject(i).let {
                    Text("· ${it.optString("name")} (${it.optString("portion")}) — ${it.optInt("kcal")} kcal", style = MaterialTheme.typography.bodyMedium)
                }
                if (r.optString("note").isNotBlank()) Text(r.optString("note"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch {
                        val itemCount = items?.length()?.takeIf { it > 0 } ?: 1
                        var logged = 0
                        var skipped = 0
                        for (i in 0 until itemCount) {
                            val hasItems = items?.length()?.let { it > 0 } == true
                            val item = if (hasItems) {
                                try {
                                    items!!.getJSONObject(i)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    skipped++
                                    continue
                                }
                            } else null
                            val name = item?.optString("name") ?: "Meal"
                            val kcal = item?.optDouble("kcal", Double.NaN) ?: r.optDouble("kcal", Double.NaN)
                            val protein = item?.optDouble("protein", 0.0) ?: r.optDouble("protein", 0.0)
                            val saved = if (!kcal.isFinite() || kcal <= 0 || !protein.isFinite() || protein < 0) {
                                false
                            } else try {
                                container.dietRepository.add(slot, name, kcal, protein)
                                true
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (saved) logged++ else skipped++
                        }
                        status = when {
                            logged == 0 -> "No meal items were logged. Check the scan values."
                            skipped > 0 -> "Logged $logged item(s) to $slot; skipped $skipped invalid item(s)."
                            else -> "Logged $logged item(s) to $slot."
                        }
                        if (logged > 0) result = null
                    }
                }) { Text("Log to $slot") }
            }
            Text("Photo estimates can be off by 20–30%. Edit in Diet if something looks wrong.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(60.dp))
    }
}

// ── Gym voice coach ───────────────────────────────────────────────────────────
@Composable
fun VoiceCoachScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val speaker = remember { Speaker(c) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    var exercise by remember { mutableStateOf("Bench press") }
    var sets by remember { mutableStateOf("4") }
    var reps by remember { mutableStateOf("8") }
    var weight by remember { mutableStateOf("") }
    var rest by remember { mutableStateOf("90") }
    var current by remember { mutableStateOf(0) }   // 0 = not started
    var resting by remember { mutableStateOf(0) }
    var log by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(exercise) { com.nizam.app.ui.design.guard {
        container.gymRepository.sets().first().firstOrNull { it.exercise.equals(exercise, true) }?.let { weight = it.weight.toString() }
    } }
    LaunchedEffect(resting) { com.nizam.app.ui.design.guard {
        if (resting <= 0) return@LaunchedEffect
        delay(1000)
        if (resting == 11) speaker.speak("Ten seconds")
        if (resting == 1) speaker.speak("Set $current. $weight kilos, $reps reps. Go.")
        resting--
    } }
    fun announce() = speaker.speak("Set $current of $sets. $exercise, $weight kilos, $reps reps. Go when ready.")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Gym voice coach", "Earbuds in, phone in your pocket — Atlas calls the sets and rests")
        if (current == 0) {
            OutlinedTextField(exercise, { exercise = it }, label = { Text("Exercise") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                OutlinedTextField(sets, { sets = it.filter(Char::isDigit) }, label = { Text("Sets") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(reps, { reps = it.filter(Char::isDigit) }, label = { Text("Reps") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(weight, { weight = it }, label = { Text("kg") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("Rest between sets", style = MaterialTheme.typography.labelMedium)
            ChipRow(listOf("60", "90", "120", "180"), rest, { rest = it }, labels = { "${it}s" })
            Button(onClick = { current = 1; announce() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start") }
        } else {
            Card {
                Text(if (resting > 0) "Rest" else "Set $current of $sets", style = MaterialTheme.typography.titleMedium)
                Text(if (resting > 0) "${resting}s" else "$weight kg × $reps", fontSize = 56.sp)
                Text(exercise, style = MaterialTheme.typography.bodyLarge)
            }
            if (resting == 0) Button(onClick = {
                scope.launch {
                    container.gymRepository.addSet(exercise, weight.toDoubleOrNull() ?: 0.0, reps.toIntOrNull() ?: 0)
                    log = log + "Set $current: $weight × $reps"
                    if (current >= (sets.toIntOrNull() ?: 1)) { speaker.speak("That's the last set. Well done."); current = 0 }
                    else { current++; resting = rest.toIntOrNull() ?: 90; speaker.speak("Logged. Rest ${rest} seconds.") }
                }
            }, modifier = Modifier.fillMaxWidth().height(72.dp)) { Text("✓  Done — log it", fontSize = 20.sp) }
            else OutlinedButton(onClick = { resting = 1 }) { Text("Skip rest") }
            OutlinedButton(onClick = { current = 0; resting = 0 }) { Text("End session") }
        }
        log.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(60.dp))
    }
}

// ── Flip to focus: phone face-down grows a tree in your town ─────────────────
object Grove {
    fun count(c: Context) = c.getSharedPreferences("grove", 0).getInt("trees", 0)
    fun plant(c: Context) = c.getSharedPreferences("grove", 0).edit().putInt("trees", count(c) + 1).apply()
}

@Composable
fun FlipFocusScreen() {
    val c = LocalContext.current
    var minutes by remember { mutableStateOf(25) }
    var running by remember { mutableStateOf(false) }
    var faceDown by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0) }
    var pickedUpFor by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    DisposableEffect(running) {
        (c as? ComponentActivity)?.window?.let { w -> if (running) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val sm = c.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { faceDown = e.values[2] < -8.0f }   // gravity pointing out of the screen
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (running) sm.registerListener(listener, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(listener) }
    }
    LaunchedEffect(running) { com.nizam.app.ui.design.guard {
        while (running) {
            delay(1000)
            if (faceDown) { elapsed++; pickedUpFor = 0 } else if (elapsed > 0) pickedUpFor++
            if (pickedUpFor >= 10) { running = false; result = "🥀 Picked up — the sapling wilted. ${elapsed / 60} min still counted." ; FocusStore(c).log(elapsed / 60, "Flip focus") }
            if (elapsed >= minutes * 60) {
                running = false; Grove.plant(c); FocusStore(c).log(minutes, "Flip focus")
                result = "🌳 A tree was planted in your town. You now have ${Grove.count(c)}."
            }
        }
    } }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (!running) "🌱" else if (faceDown) "🌿" else "⚠️", fontSize = 90.sp)
        if (!running) {
            Text("Flip to focus", style = MaterialTheme.typography.headlineMedium)
            Text("Start, then put your phone face-down. Keep it down and a tree grows in your town. " +
                "Pick it up for more than 10 seconds and the sapling wilts.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRow(listOf("15", "25", "45", "60"), "$minutes", { minutes = it.toInt() }, labels = { "$it min" })
            Button(onClick = { elapsed = 0; pickedUpFor = 0; result = null; running = true; FocusFlag.set(c, System.currentTimeMillis() + minutes * 60_000L) }) {
                Text("Start")
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp)) }
            Text("🌳 ${Grove.count(c)} trees grown", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        } else {
            Text("%02d:%02d".format((minutes * 60 - elapsed) / 60, (minutes * 60 - elapsed) % 60), fontSize = 56.sp)
            Text(if (faceDown) "Growing… keep it down" else "Put the phone face-down", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { running = false; FocusFlag.set(c, 0) }, modifier = Modifier.padding(top = 16.dp)) { Text("Give up") }
        }
    }
}

// ── Bedside mode ──────────────────────────────────────────────────────────────
@Composable
fun BedsideScreen(container: AppContainer, onExit: () -> Unit) {
    val c = LocalContext.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    var prayer by remember { mutableStateOf("") }
    var firstTask by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        val w = (c as? ComponentActivity)?.window
        val old = w?.attributes?.screenBrightness
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        w?.attributes = w?.attributes?.apply { screenBrightness = 0.02f }
        onDispose {
            w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w?.attributes = w?.attributes?.apply { screenBrightness = old ?: -1f }
        }
    }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        val s = container.settingsRepository
        val date = if (LocalTime.now().hour >= 20) LocalDate.now().plusDays(1) else LocalDate.now()
        runCatching {
            val t = PrayerTimes.calculate(date, s.latitude.first(), s.longitude.first(),
                runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI }, s.hanafiAsr.first())
            prayer = "Fajr ${hourToClock(t.fajr)}"
        }
        firstTask = container.taskRepository.all().first().firstOrNull { it.completedAt == null }?.title.orEmpty()
        while (true) { now = LocalTime.now(); delay(15_000) }
    } }
    Column(Modifier.fillMaxSize().background(Color.Black).clickable(role = Role.Button) { onExit() }.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("%02d:%02d".format(now.hour, now.minute), color = Color(0xFF8C3B32), fontSize = 110.sp)  // red spares night vision
        Text(prayer, color = Color(0xFF5A3A36), fontSize = 22.sp)
        if (firstTask.isNotBlank()) Text("First thing: $firstTask", color = Color(0xFF4A3330), fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
        Text("🌙", fontSize = 28.sp, modifier = Modifier.padding(top = 30.dp))
        Text("tap to exit", color = Color(0xFF2A1F1E), fontSize = 12.sp, modifier = Modifier.padding(top = 30.dp))
    }
}

// ── Energy check ──────────────────────────────────────────────────────────────
object Energy {
    fun today(c: Context) = c.getSharedPreferences("energy", 0).getString(LocalDate.now().toString(), "").orEmpty()
    fun set(c: Context, v: String) = c.getSharedPreferences("energy", 0).edit().putString(LocalDate.now().toString(), v).apply()
    fun forAi(c: Context) = today(c).takeIf { it.isNotBlank() }?.let {
        "Their energy today: $it. " + when (it) { "Empty" -> "Plan a light day — protect the essentials, drop the rest, no guilt."
            "Half" -> "Plan a moderate day with real breaks."; else -> "They can take on demanding work." }
    }.orEmpty()
}

@Composable
fun EnergyCheck() {
    val c = LocalContext.current
    var e by remember { mutableStateOf(Energy.today(c)) }
    if (e.isNotBlank()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("How's your energy this morning?", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Full", "Half", "Low").forEach { v ->
                com.nizam.app.ui.design.Capsule(v, false) { Energy.set(c, if (v == "Low") "Empty" else v); e = v }
            }
        }
    }
}
