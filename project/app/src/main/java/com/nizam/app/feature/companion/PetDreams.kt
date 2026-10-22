package com.nizam.app.feature.companion

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.net.Ai
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/**
 * Buddy dreams: each morning your pet tells the short dream it had, woven from how yesterday really
 * went. Kept in a dream journal you can scroll back through.
 */
object PetDreams {
    private fun store(c: Context) = JsonStore(c, "pet_dreams")

    fun all(c: Context): List<JSONObject> = store(c).all().sortedByDescending { it.optString("d") }
    fun last(c: Context): JSONObject? = all(c).firstOrNull()

    /** Yesterday in a few facts, from what Atlas already knows. */
    private suspend fun yesterday(container: AppContainer): String {
        val c = container.appContext
        val y = LocalDate.now().minusDays(1)
        val facts = mutableListOf<String>()
        runCatching { com.nizam.app.feature.deen.library.DailyPath.themeFor(y).let { facts += "the day's Quranic theme was \"${it.title}\"" } }
        runCatching { if (com.nizam.app.feature.deen.library.DailyPath.isDone(c, y)) facts += "they completed their daily Quran mission" }
        runCatching { com.nizam.app.ui.design.CurrentAmbience.value?.let { facts += "the weather was ${it.weather}" } }
        return facts.joinToString("; ").ifBlank { "an ordinary day" }
    }

    suspend fun dreamFor(container: AppContainer): JSONObject {
        val c = container.appContext
        val today = LocalDate.now().toString()
        last(c)?.takeIf { it.optString("d") == today }?.let { return it }
        val pet = Companion.load(c)
        val facts = yesterday(container)
        val text = runCatching {
            Ai.generate(container.settingsRepository,
                "You are ${pet.name}, a ${pet.trait.lowercase()} little ${pet.species.lowercase()} who lives in the user's phone. Yesterday: $facts. " +
                    "Tell the dream you had last night in 5-7 short, whimsical sentences, first person, gentle and kind, with one tiny hopeful message for today at the end. No emojis.",
                "You write cosy, imaginative children's-book prose.", 400)
        }.getOrElse {
            val places = listOf("a lantern-lit bazaar", "a cloud that tasted of dates", "the top of a minaret", "a river of stars", "a library where books hummed")
            "I dreamed I was in ${places.random()}. You were there too, and every time you did something kind a little light came on. " +
                "When I woke up, one light was still glowing. I think it's for today."
        }
        val o = JSONObject().put("d", today).put("text", text).put("pet", pet.name)
        store(c).add(o)
        return o
    }
}

@Composable
fun PetDreamsScreen(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    var list by remember { mutableStateOf(PetDreams.all(c)) }
    var busy by remember { mutableStateOf(false) }
    val todayDone = list.firstOrNull()?.optString("d") == LocalDate.now().toString()
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { if (!todayDone && Companion.load(c).hatched) { busy = true; runCatching { PetDreams.dreamFor(container) }; list = PetDreams.all(c); busy = false } } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!Companion.load(c).hatched) Text("Your pet hasn't hatched yet. Tap the egg on Today three times.", style = MaterialTheme.typography.bodyMedium)
        if (busy) Text("${Companion.load(c).name} is waking up and remembering…", style = MaterialTheme.typography.bodyMedium)
        if (!busy && !todayDone && Companion.load(c).hatched) Button(onClick = { busy = true; work.launch { runCatching { PetDreams.dreamFor(container) }; list = PetDreams.all(c); busy = false } }) { Text("Ask about last night's dream") }
        list.forEach { d ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("🌙 ${d.optString("d")}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(d.optString("text"), style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
