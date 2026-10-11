package com.nizam.app.ui.design

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject

/** What Today knows, handed to the bento grid. */
data class BentoData(val prayer: Pair<String, Int>?, val spentToday: Double, val waterMl: Int, val waterTarget: Int, val openTasks: Int, val topTasks: List<String>, val quote: String?)

object Bento {
    data class Tile(val id: String, val wide: Boolean)
    val ALL = listOf("stack", "prayer", "weather", "money", "tasks", "water", "pet", "focus", "mood", "wisdom")
    private val DEFAULT = listOf(Tile("stack", true), Tile("prayer", false), Tile("weather", false), Tile("money", false), Tile("tasks", false),
        Tile("pet", true), Tile("water", false), Tile("focus", false), Tile("mood", false), Tile("wisdom", false))
    private fun p(c: Context) = c.getSharedPreferences("bento", 0)
    fun load(c: Context): List<Tile> = p(c).getString("t", null)?.split(',')?.mapNotNull { e -> e.split(':').takeIf { it.size == 2 && it[0] in ALL }?.let { Tile(it[0], it[1] == "w") } } ?: DEFAULT
    fun save(c: Context, l: List<Tile>) = p(c).edit().putString("t", l.joinToString(",") { "${it.id}:${if (it.wide) "w" else "s"}" }).apply()
    fun route(id: String) = when (id) { "prayer" -> "prayer"; "weather" -> "weather"; "money" -> "finance"; "tasks" -> "tasks"; "water" -> "diet"
        "pet" -> "nx/pethome"; "focus" -> "timer"; "mood" -> "nx/moodwheel"; "wisdom" -> "nx/wisdom"; else -> "" }
    fun auto(c: Context) = p(c).getBoolean("auto", true)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()
    /** Tiles in the order you tend to want them right now; the smart stack stays on top. */
    fun arranged(c: Context, l: List<Tile>): List<Tile> {
        if (!auto(c)) return l
        val score = l.associateWith { com.nizam.app.ui.screens.hubs.RecentFeatures.around(c, route(it.id)) }
        if (score.values.all { it < 3 }) return l                    // not enough history yet
        return l.filter { it.id == "stack" } + l.filter { it.id != "stack" }.sortedByDescending { score[it] ?: 0 }
    }
    fun why(c: Context, l: List<Tile>): String? {
        if (!auto(c)) return null
        val top = l.filter { it.id != "stack" }.maxByOrNull { com.nizam.app.ui.screens.hubs.RecentFeatures.around(c, route(it.id)) } ?: return null
        val n = com.nizam.app.ui.screens.hubs.RecentFeatures.around(c, route(top.id))
        if (n < 3) return null
        val h = java.time.LocalTime.now().hour
        val part = when (h) { in 4..11 -> "mornings"; in 12..16 -> "afternoons"; in 17..20 -> "evenings"; else -> "nights" }
        return "Arranged for now — you usually open ${label(top.id)} in the $part"
    }
    fun label(id: String) = when (id) { "stack" -> "Smart stack"; "prayer" -> "Prayer"; "weather" -> "Weather"; "money" -> "Money"; "tasks" -> "Tasks"
        "water" -> "Water"; "pet" -> "Pet"; "focus" -> "Focus"; "mood" -> "Mood"; else -> "Daily wisdom" }
}

@Composable
private fun BentoCell(title: String, value: String, sub: String?, art: String, hub: Hub, modifier: Modifier, onClick: () -> Unit) {
    val tint = hub.tint()
    Box(modifier.height(132.dp).styledCard(Radius.tile).background(Brush.linearGradient(listOf(tint.copy(alpha = 0.16f), tint.copy(alpha = 0.02f)))).pressable(onClick = onClick)) {
        Art3D(art, 64.dp, Modifier.align(Alignment.TopEnd).padding(6.dp), float = false, shadow = false)
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Bold)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun TodayBento(d: BentoData, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var tiles by remember { mutableStateOf(Bento.load(ctx)) }
    var editing by remember { mutableStateOf(false) }
    val weather = remember { runCatching { JSONObject(ctx.getSharedPreferences("weather_pro", 0).getString("last", null)!!).getJSONObject("current") }.getOrNull() }
    val pet = remember { com.nizam.app.feature.companion.Companion.load(ctx) }
    val mood = remember { ctx.getSharedPreferences("mood_wheel", 0).getString("today_${java.time.LocalDate.now()}", null) }
    fun save(l: List<Tile>) { tiles = l; Bento.save(ctx, l) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (editing) "Done" else "Edit", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.pressable { editing = !editing }.padding(6.dp))
        }
        // pack tiles two to a row; wide tiles take a whole row
        val shown = if (editing) tiles else remember(tiles) { Bento.arranged(ctx, tiles) }
        if (!editing) Bento.why(ctx, tiles)?.let { Text("✨ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp)) }
        val rows = mutableListOf<List<Bento.Tile>>(); var pending: Bento.Tile? = null
        shown.forEach { t -> if (t.wide) { pending?.let { rows += listOf(it); pending = null }; rows += listOf(t) } else if (pending == null) pending = t else { rows += listOf(pending!!, t); pending = null } }
        pending?.let { rows += listOf(it) }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    Box(Modifier.weight(1f)) {
                        when (t.id) {
                            "stack" -> SmartStack(d, Modifier.fillMaxWidth(), onOpen)
                            "prayer" -> BentoCell("Prayer", d.prayer?.first ?: "Done today", d.prayer?.let { (_, m) -> if (m >= 60) "in ${m / 60}h ${m % 60}m" else "in $m min" } ?: "Rest well",
                                "mosque", Hub.Deen, Modifier.fillMaxWidth()) { onOpen("prayer") }
                            "weather" -> BentoCell("Weather", weather?.let { "${it.optDouble("temperature_2m").toInt()}°" } ?: "—",
                                weather?.let { com.nizam.app.feature.next.Weather.describe(it.optInt("weather_code")) } ?: "Tap to load",
                                Art.forWeather(weather?.optInt("weather_code") ?: 1, weather?.optInt("is_day", 1) == 0), Hub.Travel, Modifier.fillMaxWidth()) { onOpen("weather") }
                            "money" -> BentoCell("Spent today", "Rs " + "%,d".format(d.spentToday.toLong()), "Tap for the month", "money_bag", Hub.Money, Modifier.fillMaxWidth()) { onOpen("finance") }
                            "tasks" -> BentoCell("Tasks", "${d.openTasks} open", d.topTasks.firstOrNull(), "spiral_calendar", Hub.Work, Modifier.fillMaxWidth()) { onOpen("tasks") }
                            "water" -> BentoCell("Water", "${d.waterMl / 1000.0} L", "of ${d.waterTarget / 1000.0} L", "droplet", Hub.Health, Modifier.fillMaxWidth()) { onOpen("diet") }
                            "pet" -> BentoCell(pet.name, if (pet.hatched) "Level ${com.nizam.app.feature.companion.Companion.level(com.nizam.app.feature.companion.Companion.xp(pet, null))} ${pet.species}" else "An egg",
                                "Feed, play, dress up", "paw_prints", Hub.Social, Modifier.fillMaxWidth()) { onOpen("nx/pethome") }
                            "focus" -> BentoCell("Focus", if (FocusTimer.running) "On" else "25 min", "Start a session", "hourglass_done", Hub.Work, Modifier.fillMaxWidth()) { onOpen("timer") }
                            "mood" -> BentoCell("Mood", mood ?: "How are you?", "Tap to check in", Art.MOODS.firstOrNull { it.second == mood }?.first ?: "smiling_face_with_smiling_eyes", Hub.Health, Modifier.fillMaxWidth()) { onOpen("nx/moodwheel") }
                            else -> BentoCell("Wisdom", d.quote?.take(60) ?: "A thought for today", null, "light_bulb", Hub.Memory, Modifier.fillMaxWidth()) { onOpen("nx/wisdom") }
                        }
                        if (editing) Row(Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), Radius.pill).padding(horizontal = 6.dp)) {
                            val i = tiles.indexOf(t)
                            Text("↑", Modifier.pressable { if (i > 0) save(tiles.toMutableList().apply { add(i - 1, removeAt(i)) }) }.padding(6.dp))
                            Text("↓", Modifier.pressable { if (i < tiles.lastIndex) save(tiles.toMutableList().apply { add(i + 1, removeAt(i)) }) }.padding(6.dp))
                            Text(if (t.wide) "▭" else "▬", Modifier.pressable { save(tiles.map { if (it == t) it.copy(wide = !it.wide) else it }) }.padding(6.dp))
                            Text("✕", Modifier.pressable { save(tiles - t) }.padding(6.dp))
                        }
                    }
                }
                if (row.size == 1 && !row.first().wide) Spacer(Modifier.weight(1f))
            }
        }
        if (editing) {
            var auto by remember { mutableStateOf(Bento.auto(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                androidx.compose.material3.Switch(auto, { auto = it; Bento.setAuto(ctx, it) })
                Text("  Rearrange by time of day", style = MaterialTheme.typography.bodyMedium)
            }
            val hidden = Bento.ALL.filter { id -> tiles.none { it.id == id } }
            if (hidden.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hidden.forEach { id -> Capsule("+ ${Bento.label(id)}", false) { save(tiles + Bento.Tile(id, false)) } }
            }
        }
    }
}

/** One tile, several cards: it flips through what matters right now. */
@Composable
private fun SmartStack(d: BentoData, modifier: Modifier, onOpen: (String) -> Unit) {
    val cards = buildList {
        d.prayer?.let { (n, m) -> add(Triple("Next prayer", "$n in ${if (m >= 60) "${m / 60}h ${m % 60}m" else "$m min"}", "prayer" to "mosque")) }
        d.topTasks.firstOrNull()?.let { add(Triple("Up next", it, "tasks" to "memo")) }
        if (d.waterMl < d.waterTarget / 2) add(Triple("Hydrate", "You're at ${d.waterMl} ml", "diet" to "droplet"))
        d.quote?.let { add(Triple("For today", it.take(70), "nx/wisdom" to "light_bulb")) }
        add(Triple("Your week", "See it as a story", "nx/stories" to "sparkles"))
    }
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(cards.size) { while (true) { delay(5000); if (!Motion.still && cards.isNotEmpty()) i = (i + 1) % cards.size } }
    val card = cards.getOrNull(i % cards.size.coerceAtLeast(1)) ?: return
    AnimatedContent(card, modifier, transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) }, label = "stack") { c ->
        val (title, value, dest) = c
        Box(Modifier.fillMaxWidth().height(132.dp).styledCard(Radius.tile)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f))))
            .pressable { onOpen(dest.first) }) {
            Art3D(dest.second, 92.dp, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            Column(Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 110.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${(i % cards.size) + 1} of ${cards.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private typealias Tile = Bento.Tile
