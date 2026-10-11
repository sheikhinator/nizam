package com.nizam.app.feature.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.NizamApplication
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.progress.Xp
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import kotlinx.coroutines.launch

/** Settings › Companion: everything about your companion — look, colour, personality, behaviour, stats. */
@Composable
fun CompanionSettings() {
    val c = LocalContext.current
    var pet by remember { mutableStateOf(Companion.load(c)) }
    var visible by remember { mutableStateOf(Companion.visible(c)) }
    var confirm by remember { mutableStateOf(false) }
    val container = (c.applicationContext as? NizamApplication)?.container
    var totalXp by remember { mutableIntStateOf(pet.lastTotalXp) }
    val scope = rememberCoroutineScope()
    fun update(p: Pet) { pet = p; Companion.save(c, p) }
    LaunchedEffect(container) {
        if (container != null) {
            totalXp = runCatching { Xp.summarise(container).totalXp }.getOrDefault(pet.lastTotalXp)
            update(Companion.load(c).copy(lastTotalXp = totalXp))
        }
    }
    fun setActivityGrowth(enabled: Boolean) {
        scope.launch {
            val latestTotal = container?.let { runCatching { Xp.summarise(it).totalXp }.getOrNull() } ?: totalXp
            totalXp = latestTotal
            val next = if (enabled) {
                pet.copy(growFromActivity = true, baseXp = (latestTotal - pet.activityXp).coerceAtLeast(0), lastTotalXp = latestTotal)
            } else {
                pet.copy(growFromActivity = false, activityXp = (latestTotal - pet.baseXp).coerceAtLeast(0), lastTotalXp = latestTotal)
            }
            update(next)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Companion", "Your creature — how it looks, what it's like, what it notices")
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Creature(pet, Companion.Mood.HAPPY, 0, Modifier.size((140 * pet.size).dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show on Today", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(visible, { visible = it; Companion.setVisible(c, it) })
        }

        Label("Name", Modifier.padding(top = 12.dp))
        OutlinedTextField(pet.name, { update(pet.copy(name = it.take(20))) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Label("Species", Modifier.padding(top = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Companion.SPECIES.forEach { sp ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(MaterialTheme.shapes.medium)
                    .background(if (sp == pet.species) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { update(pet.copy(species = sp, hatched = true)) }.padding(6.dp)) {
                    Creature(pet.copy(species = sp, hatched = true, accessory = "None"), Companion.Mood.CONTENT, 0, Modifier.size(64.dp))
                    Text(sp, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Label("Colour", Modifier.padding(top = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Natural", style = MaterialTheme.typography.labelMedium, color = if (pet.hue < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { update(pet.copy(hue = -1f)) }.padding(6.dp))
            listOf(0f, 25f, 45f, 90f, 150f, 190f, 220f, 265f, 300f, 330f).forEach { h ->
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color.hsv(h, 0.55f, 0.92f))
                    .then(if (pet.hue == h) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { update(pet.copy(hue = h)) })
            }
        }

        Label("Accessory", Modifier.padding(top = 12.dp))
        ChipRow(Companion.ACCESSORIES, pet.accessory, { update(pet.copy(accessory = it)) })

        Label("Size", Modifier.padding(top = 12.dp))
        Slider(pet.size, { update(pet.copy(size = it)) }, valueRange = 0.7f..1.4f)

        Label("Personality", Modifier.padding(top = 12.dp))
        ChipRow(Companion.TRAITS, pet.trait, { update(pet.copy(trait = it)) })
        Text(when (pet.trait) { "Cheeky" -> "Teases you into doing things."; "Wise" -> "Offers a gentle word of wisdom."; "Shy" -> "Soft-spoken, a little bashful."
            "Brave" -> "Pushes you to take on the hard thing."; "Calm" -> "Steady and reassuring."; else -> "Notices everything, asks about your day." },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Label("How much it talks", Modifier.padding(top = 12.dp))
        ChipRow(Companion.CHAT, pet.chat, { update(pet.copy(chat = it)) })

        Label("Care and growth", Modifier.padding(top = 12.dp))
        Group {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("No-penalty companion mode", style = MaterialTheme.typography.bodyLarge)
                    Text("Needs don’t decay and hunger nudges stop while you’re away.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(pet.careFree, { update(pet.copy(careFree = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Grow from my activity", style = MaterialTheme.typography.bodyLarge)
                    Text("Turn off to keep pet growth separate from your real-life XP.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(pet.growFromActivity, ::setActivityGrowth)
            }
        }

        Label("What it reacts to", Modifier.padding(top = 12.dp))
        Group {
            listOf(
                Triple("Prayer times", pet.reactPrayer) { v: Boolean -> update(pet.copy(reactPrayer = v)) },
                Triple("Weather", pet.reactWeather) { v: Boolean -> update(pet.copy(reactWeather = v)) },
                Triple("Streaks", pet.reactStreak) { v: Boolean -> update(pet.copy(reactStreak = v)) },
                Triple("Sleeps at night", pet.sleeps) { v: Boolean -> update(pet.copy(sleeps = v)) },
                Triple("Show level and XP", pet.showStats) { v: Boolean -> update(pet.copy(showStats = v)) },
            ).forEach { (label, on, set) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); Switch(on, set)
                }
            }
        }

        Label("Stats", Modifier.padding(top = 12.dp))
        Text("Level and XP grow from what you actually do — tasks, prayers, workouts, study. You can give it a head start:",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(pet.bonusXp.toFloat(), { update(pet.copy(bonusXp = it.toInt())) }, valueRange = 0f..2400f, steps = 19)
        Text("Bonus XP: ${pet.bonusXp} (Level +${pet.bonusXp / 120})", style = MaterialTheme.typography.labelMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
            OutlinedButton(onClick = { update(pet.copy(species = Companion.SPECIES.random(), trait = Companion.TRAITS.random(), hue = -1f)); Feedback.show("Surprise!") }) { Text("Surprise me") }
            OutlinedButton(onClick = { confirm = true }) { Text("Hatch a new egg") }
        }
        if (confirm) androidx.compose.material3.AlertDialog(onDismissRequest = { confirm = false },
            title = { Text("Start over with a new egg?") }, text = { Text("${pet.name} goes back into an egg — tap it on Today to hatch someone new.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { Companion.reEgg(c); pet = Companion.load(c); confirm = false }) { Text("New egg") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Keep ${pet.name}") } })
        Spacer(Modifier.height(96.dp))
    }
}
