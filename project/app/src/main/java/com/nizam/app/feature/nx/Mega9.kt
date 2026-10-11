package com.nizam.app.feature.nx

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object Mega9 {
    private fun today() = LocalDay.today().toString()

    val all: List<Spec> by lazy { listOf(
        ScreenSpec("speedcheck", "Speed check", "Times Atlas's own data lookups and keeps a history", Icons.Outlined.Speed, Hub.Tools,
            "benchmark performance speed slow query history diagnostics") { c, open -> SpeedCheck(c) },
        LogSpec("safetymap", "Local safety map", "Your own notes on streets and places: when to go, when to avoid", Icons.Outlined.Shield, Hub.Travel,
            "safety map street area avoid night well lit women safe route",
            listOf(F("place", "Place or street"), F("when", "When", K.CHOICE, options = listOf("Any time", "Daytime only", "Avoid at night", "Avoid"), default = "Any time"),
                F("why", "Why", optional = true, hint = "dark, dogs, snatching reported, busy and well lit"),
                F("rating", "Feels safe (1–5)", K.CHOICE, options = listOf("1", "2", "3", "4", "5"), default = "3"), F("d", "Noted on", K.DATE, default = today())),
            line = { o -> val r = num(o.optString("rating")).toInt(); "${if (r >= 4) "🟢" else if (r == 3) "🟡" else if (r == 2) "🟠" else "🔴"} ${o.optString("place")}" to
                "${o.optString("when")}${if (o.optString("why").isNotBlank()) " · ${o.optString("why")}" else ""} · ${o.optString("d")}" },
            summary = { l -> listOfNotNull(
                l.filter { it.optString("when").startsWith("Avoid") }.takeIf { it.isNotEmpty() }?.let { a -> "Avoid: " + a.joinToString { it.optString("place") } },
                l.filter { num(it.optString("rating")) >= 4 }.takeIf { it.isNotEmpty() }?.let { a -> "Safe choices: " + a.take(6).joinToString { it.optString("place") } }) }),
        ScreenSpec("tvhandoff", "Play on TV", "Send what you're watching to a TV or cast app", Icons.Outlined.Cast, Hub.Media,
            "tv cast chromecast handoff play on tv smart tv dlna screen mirror") { c, open -> TvHandoff() },
        ScreenSpec("pantrysync", "Meal plan vs pantry", "What a plan needs, what you have, what to buy — and use up after cooking", Icons.Outlined.ShoppingCart, Hub.Home,
            "meal plan pantry reconcile shopping list ingredients have need buy") { c, open -> PantrySync(c, open) },
    ) }
}

@Composable
private fun SpeedCheck(container: AppContainer) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    val store = remember { JsonStore(c, "nx_speedcheck") }
    var hist by remember { mutableStateOf(store.all()) }
    var running by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Runs the same lookups the app does every day and records how long each took on this phone.", style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !running, onClick = { running = true; scope.launch {
            val r = withContext(Dispatchers.IO) {
                fun <T> t(block: () -> T): Long { val s = System.nanoTime(); block(); return (System.nanoTime() - s) / 1_000_000 }
                val o = JSONObject().put("at", System.currentTimeMillis())
                o.put("tasks", t { kotlinx.coroutines.runBlocking { container.taskRepository.all().first().size } })
                o.put("search", t { kotlinx.coroutines.runBlocking { container.dynamicRepository.search("a").size } })
                o.put("modules", t { kotlinx.coroutines.runBlocking { container.dynamicRepository.specs().first().size } })
                o.put("logs", t { c.filesDir.listFiles().orEmpty().filter { it.name.startsWith("nxlog_") && it.name.endsWith(".json") }
                    .forEach { f -> runCatching { JsonStore(c, f.name.removeSuffix(".json")).all() } } })
                o.put("tools", t { Nx.all.size })
                o
            }
            store.add(r); store.all().let { l -> l.take(maxOf(0, l.size - 30)).forEach { store.remove(it.optString("id")) } }
            hist = store.all(); running = false
        } }) { Text(if (running) "Measuring…" else "Run speed check") }
        val labels = listOf("tasks" to "Load tasks", "search" to "Search records", "modules" to "List modules", "logs" to "Read every log", "tools" to "Tool catalogue")
        hist.lastOrNull()?.let { last ->
            Text("Latest", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            labels.forEach { (k, l) ->
                val ms = last.optLong(k); val prev = hist.dropLast(1).map { it.optLong(k) }.takeIf { it.isNotEmpty() }?.average()
                val trend = prev?.let { p -> if (ms > p * 1.5 && ms - p > 20) " ▲ slower than usual (${p.toInt()} ms)" else if (ms < p * 0.67) " ▼ faster" else "" }.orEmpty()
                Text("${if (ms < 50) "🟢" else if (ms < 250) "🟡" else "🔴"} $l · $ms ms$trend")
            }
            Text("${hist.size} runs kept", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TvHandoff() {
    val c = LocalContext.current
    val recentIptv = remember { runCatching { com.nizam.app.feature.iptv.IptvStore(c).recent() }.getOrDefault(emptyList()) }
    var url by remember { mutableStateOf(recentIptv.firstOrNull().orEmpty()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Send a stream to an app that can cast (Web Video Caster, VLC, your TV's own app) or mirror the whole screen.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(url, { url = it }, label = { Text("Video or stream link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (recentIptv.isNotEmpty()) {
            Text("Recent channels", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            recentIptv.take(5).forEach { u -> TextButton(onClick = { url = u }) { Text(u.takeLast(48), maxLines = 1) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = url.startsWith("http"), onClick = {
                val mime = if (url.contains(".m3u8")) "application/x-mpegURL" else "video/*"
                runCatching { c.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime), "Play on TV with")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Send to a cast app") }
            TextButton(onClick = { runCatching { c.startActivity(Intent("android.settings.CAST_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }) { Text("Mirror screen") }
        }
        TextButton(enabled = url.isNotBlank(), onClick = { Share.text(c, url, "Watch this") }) { Text("Share link to the TV's browser") }
    }
}

@Composable
private fun PantrySync(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    val pantryStore = remember { JsonStore(c, "nxlog_pantry") }
    var pantry by remember { mutableStateOf(pantryStore.all()) }
    var plan by remember { mutableStateOf("") }
    var needs by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf("") }
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z\\u0600-\\u06FF ]"), " ").trim().removeSuffix("es").removeSuffix("s")
    fun inPantry(ing: String) = pantry.firstOrNull { p -> val a = norm(p.optString("item")); val b = norm(ing); a.isNotBlank() && b.isNotBlank() && (a.contains(b) || b.contains(a)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Paste a meal plan (from Pantry meal plan or anywhere). Atlas lists the ingredients and checks them against your Pantry log.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(plan, { plan = it }, label = { Text("Meal plan or recipe") }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp))
        Row {
            TextButton(enabled = plan.isNotBlank(), onClick = { scope.launch {
                status = "Reading ingredients…"
                status = runCatching {
                    val out = com.nizam.app.net.Ai.generate(container.settingsRepository, plan.take(6000),
                        "List every distinct ingredient this meal plan needs as a JSON array of short lowercase names (no quantities, no spices you'd always have like salt and water). Output only the JSON array.", 600)
                    needs = Regex("\\[[\\s\\S]*]").find(out)?.value?.let { JSONArray(it) }?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.distinct() }.orEmpty()
                    if (needs.isEmpty()) "Couldn't find ingredients in that." else ""
                }.getOrElse { "Didn't work: ${it.message}" }
            } }) { Text("Check against pantry") }
            TextButton(onClick = { open("nx/pantry") }) { Text("Open pantry") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (needs.isNotEmpty()) {
            val have = needs.mapNotNull { n -> inPantry(n)?.let { n to it } }
            val buy = needs.filter { inPantry(it) == null }
            Text("Have (${have.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            have.forEach { (n, p) -> val exp = p.optString("exp")
                Text("✓ $n${if (p.optString("qty").isNotBlank()) " · ${p.optString("qty")}" else ""}${if (exp.isNotBlank()) " · expires $exp" else ""}") }
            Text("To buy (${buy.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            buy.forEach { Text("☐ $it") }
            Row {
                if (buy.isNotEmpty()) TextButton(onClick = { Share.text(c, "Shopping list\n" + buy.joinToString("\n") { "☐ $it" }, "Shopping list") }) { Text("Share shopping list") }
                if (have.isNotEmpty()) TextButton(onClick = {
                    // after cooking: used-up items leave the pantry (undo by re-adding from the pantry log)
                    have.map { it.second.optString("id") }.distinct().forEach { pantryStore.remove(it) }
                    pantry = pantryStore.all(); status = "Removed ${have.size} used items from the pantry."
                }) { Text("Cooked — use these up") }
            }
            val soon = pantry.filter { p -> runCatching { LocalDate.parse(p.optString("exp")).isBefore(LocalDate.now().plusDays(4)) }.getOrDefault(false) }
                .filter { p -> have.none { it.second.optString("id") == p.optString("id") } }
            if (soon.isNotEmpty()) Text("Not in this plan but expiring soon: ${soon.joinToString { it.optString("item") }}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
