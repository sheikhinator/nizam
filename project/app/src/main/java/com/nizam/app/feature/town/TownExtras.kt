package com.nizam.app.feature.town

import androidx.compose.material.icons.outlined.Museum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/** Town history, expeditions with choices, and the civic calendar. */
object TownLife {
    private fun f(c: Context) = File(c.filesDir, "town.json")
    private fun load(c: Context) = runCatching { JSONObject(f(c).readText()) }.getOrElse { JSONObject() }

    /** One snapshot a day, for the time scrubber. */
    fun snapshot(c: Context, t: Town) = runCatching {
        val o = load(c); val a = o.optJSONArray("history") ?: JSONArray()
        val today = LocalDate.now().toString()
        if ((0 until a.length()).none { a.getJSONObject(it).optString("d") == today })
            a.put(JSONObject().put("d", today).put("level", t.facts.level).put("pop", t.sims.size).put("bld", t.buildings.size).put("size", t.size)
                .put("kinds", JSONArray(t.buildings.map { it.kind.name }.distinct())))
        f(c).writeText(o.put("history", a).toString())
    }
    fun history(c: Context): List<JSONObject> = load(c).optJSONArray("history")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    data class Route(val name: String, val risk: String, val needs: String, val ok: (TownFacts) -> Boolean, val win: String, val lose: String)

    val ROUTES = listOf(
        Route("Over the mountain pass", "Hard", "needs a strong week of training", { it.sets30 >= 20 },
            "They crossed the pass at dawn and found an old shepherd's hut full of carved toys.", "Snow closed the pass. They came back tired but wiser."),
        Route("Along the river", "Easy", "needs steady prayers", { it.prayers30 >= 60 },
            "The river led them to a waterwheel no one had used in years. They got it turning again.", "The river flooded its banks; they waited it out and came home."),
        Route("Through the old library ruins", "Medium", "needs study time", { it.study30 >= 120 },
            "Among the ruins they found a box of maps that nobody in town had ever seen.", "The ruins were too dark to read anything. Next time, with more lamps."),
        Route("To the night market", "Medium", "needs a 5-day streak", { it.best >= 5 },
            "They traded well and brought home spices and a lantern that hums.", "The market had moved on. They found its empty stalls and a single coin."),
    )

    fun expeditionToday(c: Context): JSONObject? = load(c).optJSONArray("expeditions")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }?.lastOrNull { it.optString("d") == LocalDate.now().toString() }
    fun expeditions(c: Context): List<JSONObject> = load(c).optJSONArray("expeditions")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    fun go(c: Context, t: Town, route: Route): JSONObject {
        val success = route.ok(t.facts)
        val who = t.sims.getOrNull(Random(LocalDate.now().toEpochDay()).nextInt(t.sims.size.coerceAtLeast(1)))?.name ?: "A traveller"
        val e = JSONObject().put("d", LocalDate.now().toString()).put("who", who).put("route", route.name).put("ok", success)
            .put("story", "$who took the route ${route.name.lowercase()}. " + if (success) route.win else route.lose)
        runCatching { val o = load(c); val a = o.optJSONArray("expeditions") ?: JSONArray(); a.put(e); f(c).writeText(o.put("expeditions", a).toString()) }
        return e
    }

    /** What's on in town this month. */
    fun calendar(): List<Pair<String, String>> {
        val today = LocalDate.now()
        val hijri = runCatching { java.time.chrono.HijrahDate.from(today) }.getOrNull()
        val hm = hijri?.get(java.time.temporal.ChronoField.MONTH_OF_YEAR)
        val list = mutableListOf<Pair<String, String>>()
        list += "Every Friday" to "Jumu'ah at the masjid, then the Friday market by the fountain"
        if (hm == 9) list += "This month" to "Ramadan: iftar tables on the main road every evening"
        if (hm == 10) list += "Shawwal" to "Eid fair: swings, sweets and lights for three days"
        if (hm == 12) list += "Dhul Hijjah" to "Qurbani and the Eid al-Adha feast"
        if (hm == 3) list += "Rabi' al-Awwal" to "Seerah evenings at the library"
        if (today.monthValue == 8) list += "14 August" to "Independence Day parade with green flags"
        if (today.monthValue in 3..4) list += "Spring" to "Basant kites over the rooftops"
        list += "First of the month" to "Town meeting: residents talk about how the founder's month went"
        return list
    }

    fun postcard(c: Context, t: Town): File {
        val w = 1200; val h = 800
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF7EC8E3.toInt(), 0xFFF6E7C1.toInt(), Shader.TileMode.CLAMP) })
        val cell = 520f / t.size; val ox = 340f; val oy = 180f
        cv.drawRect(ox, oy, ox + 520f, oy + 520f, Paint().apply { color = 0xFF8CBF6A.toInt() })
        val road = Paint().apply { color = 0xFFD8C7A0.toInt() }
        cv.drawRect(ox + t.road * cell, oy, ox + (t.road + 1) * cell, oy + 520f, road); cv.drawRect(ox, oy + t.road * cell, ox + 520f, oy + (t.road + 1) * cell, road)
        t.buildings.forEach { b ->
            val col = when (b.kind) { Kind.MASJID -> 0xFF2F8A73; Kind.LIBRARY -> 0xFF5B6BA8; Kind.MARKET -> 0xFFD98E4A; Kind.SCHOOL -> 0xFF8A4F6B; Kind.GYM -> 0xFF3E5C76
                Kind.FOUNTAIN -> 0xFF58A6C9; Kind.TOWER -> 0xFF5E3A26; Kind.BAKERY -> 0xFFC9A227; else -> 0xFFB5462E }
            cv.drawRect(ox + b.x * cell, oy + b.y * cell, ox + (b.x + b.w) * cell, oy + (b.y + b.h) * cell, Paint().apply { color = col.toInt() })
        }
        t.trees.forEach { (x, y) -> cv.drawCircle(ox + (x + 0.5f) * cell, oy + (y + 0.5f) * cell, cell * 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3F7D3A.toInt() }) }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2A3A.toInt(); textSize = 64f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2A3A.toInt(); textSize = 30f }
        cv.drawText("Greetings from ${t.facts.name.ifBlank { "my" }} town", 60f, 100f, title)
        cv.drawText("Level ${t.facts.level} · ${t.sims.size} residents · ${t.buildings.size} buildings · ${LocalDate.now()}", 60f, 150f, sub)
        val out = File(c.cacheDir, "town_postcard.png"); out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        return out
    }
}

@Composable
fun TownPlannerScreen(container: AppContainer) {
    val c = LocalContext.current
    var town by remember { mutableStateOf<Town?>(null) }
    var tab by remember { mutableStateOf("Plan") }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(version) { com.nizam.app.ui.design.guard {
        TownLayout.load(c)
        val facts = TownData.facts(container)
        town = Town(TownStore(c).seed(), facts).also { TownLife.snapshot(c, it) }
    } }
    val t = town ?: run { Text("Building the town…"); return }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Plan", "Residents", "Visitors", "Expedition", "Museum", "Calendar", "Time").forEach { x -> FilterChip(selected = tab == x, onClick = { tab = x }, label = { Text(x) }) }
        }
        when (tab) {
            "Plan" -> {
                Text("Architecture", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Classic", "Mughal", "Modern", "Coastal", "Desert").forEach { s -> FilterChip(selected = TownLayout.style == s, onClick = { TownLayout.style = s; TownLayout.save(c); version++ }, label = { Text(s) }) }
                }
                Text("Districts: where each civic building goes (rebuilds the map)", style = MaterialTheme.typography.titleSmall)
                Kind.entries.filter { it != Kind.HOME }.forEach { k ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${k.label}${if (k in t.unlocked) "" else " 🔒"}", Modifier.padding(top = 12.dp, end = 6.dp), style = MaterialTheme.typography.bodyMedium)
                        listOf("Centre", "North", "South", "East", "West").forEach { z ->
                            FilterChip(selected = (TownLayout.zones[k.name] ?: "Centre") == z, onClick = { TownLayout.zones = TownLayout.zones + (k.name to z); TownLayout.save(c); version++ }, label = { Text(z) })
                        }
                    }
                }
                Button(onClick = { val f = TownLife.postcard(c, t)
                    runCatching { c.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png")
                        .putExtra(android.content.Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f))
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send a postcard").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("📮 Send a postcard of the town") }
            }
            "Residents" -> {
                Text("Name your residents and give them jobs. They keep them as the town grows.", style = MaterialTheme.typography.bodySmall)
                t.sims.forEach { s ->
                    var name by remember(s.id) { mutableStateOf(s.name) }
                    var job by remember(s.id) { mutableStateOf(s.job) }
                    val stories = Adventures.all(c).filter { it.sim == s.name } + TownLife.expeditions(c).filter { it.optString("who") == s.name }.map { Adventure(it.optString("d"), s.name, it.optString("story"), "", "🧭") }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                        Text("${s.personality.label} · ${s.age}", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, label = { Text("Name") })
                            OutlinedTextField(job, { job = it }, Modifier.weight(1f), singleLine = true, label = { Text("Job") })
                        }
                        TextButton(onClick = { TownLayout.names = TownLayout.names + (s.id to name.trim()); TownLayout.jobs = TownLayout.jobs + (s.id to job.trim()); TownLayout.save(c) }) { Text("Save") }
                        if (stories.isNotEmpty()) { Text("Their story", style = MaterialTheme.typography.labelLarge); stories.takeLast(4).forEach { Text("${it.emoji} ${it.date}: ${it.story}", style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
            "Expedition" -> {
                val done = TownLife.expeditionToday(c)
                var result by remember { mutableStateOf(done) }
                if (result != null) Text("Today's expedition: ${result!!.optString("story")}", style = MaterialTheme.typography.bodyLarge)
                else {
                    Text("Choose today's route. Whether it succeeds depends on how your real month has gone.", style = MaterialTheme.typography.bodySmall)
                    TownLife.ROUTES.forEach { r ->
                        OutlinedButton(onClick = { result = TownLife.go(c, t, r) }, modifier = Modifier.fillMaxWidth()) { Text("${r.name} · ${r.risk} · ${r.needs}") }
                    }
                }
            }
            "Museum" -> {
                val finds = Adventures.all(c)
                Text("🏛️ The finds museum · ${finds.size} pieces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (finds.isEmpty()) Text("Have a strong day (40+ XP) and a resident will go on an adventure and bring something back.", style = MaterialTheme.typography.bodySmall)
                finds.reversed().forEach { a -> Text("${a.emoji} ${a.find.replaceFirstChar { it.uppercase() }} · found by ${a.sim} on ${a.date}", style = MaterialTheme.typography.bodyMedium) }
            }
            "Visitors" -> {
                // people you've actually shared something with, visiting as guests (names only, nothing else)
                val guests = remember {
                    val hh = com.nizam.app.feature.nx.Household.items(c).map { it.optString("by") }.filter { it.isNotBlank() && it != com.nizam.app.feature.nx.Household.me(c) }
                        .distinct().map { "🏠 $it" to "brings the groceries list over" }
                    val care = com.nizam.app.feature.next.JsonStore(c, "nx_carepack_log").all().map { it.optString("from") }.filter { it.isNotBlank() }
                        .distinct().map { "💚 $it" to "drops by to check on you" }
                    val clients = com.nizam.app.feature.hq.Clients.all(c).map { it.optString("client") }.distinct().map { "🤝 $it" to "came to see the work" }
                    val pets = com.nizam.app.feature.companion.PetFriends.friends(c).map { "${com.nizam.app.feature.companion.PetLock.emoji(it.optString("species"))} ${it.optString("name")}" to "is playing in the park" }
                    hh + care + clients + pets
                }
                if (guests.isEmpty()) Text("No visitors yet. Share a household list, a care pack, a client job or a pet card, and they'll visit town.", style = MaterialTheme.typography.bodyMedium)
                guests.forEach { (who, what) -> Text("$who $what", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
            }
            "Calendar" -> TownLife.calendar().forEach { (w, e) -> Column(Modifier.padding(vertical = 4.dp)) { Text(w, style = MaterialTheme.typography.labelLarge); Text(e, style = MaterialTheme.typography.bodyMedium) } }
            else -> {
                val hist = TownLife.history(c)
                if (hist.isEmpty()) { Text("The town's history starts today."); return@Column }
                var i by remember { mutableFloatStateOf((hist.size - 1).toFloat()) }
                val h = hist[i.toInt().coerceIn(0, hist.lastIndex)]
                Text(h.optString("d"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Level ${h.optInt("level")} · ${h.optInt("pop")} residents · ${h.optInt("bld")} buildings · ${h.optInt("size")}×${h.optInt("size")} map", style = MaterialTheme.typography.bodyLarge)
                Text("Had: " + (h.optJSONArray("kinds")?.let { a -> (0 until a.length()).joinToString { a.optString(it).lowercase() } } ?: ""), style = MaterialTheme.typography.bodySmall)
                if (hist.size > 1) Slider(i, { i = it }, valueRange = 0f..(hist.size - 1).toFloat(), steps = (hist.size - 2).coerceAtLeast(0))
            }
        }
    }
}

object TownExtrasSpecs {
    val all: List<com.nizam.app.feature.nx.Spec> = listOf(
        com.nizam.app.feature.nx.ScreenSpec("townplanner", "Town planner & museum", "Districts, architecture, residents, expeditions, finds museum, civic calendar, history and postcards",
            androidx.compose.material.icons.Icons.Outlined.Museum, com.nizam.app.ui.design.Hub.Social,
            "pixel town plan zoning district architecture style residents names expedition museum finds calendar history postcard") { c2, _ -> TownPlannerScreen(c2) }
    )
}
