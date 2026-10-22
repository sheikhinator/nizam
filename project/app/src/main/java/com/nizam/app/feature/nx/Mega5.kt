package com.nizam.app.feature.nx

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/** Which calendar day something belongs to: a late night still counts as the day you were living. */
object LocalDay {
    private fun p(c: Context) = c.getSharedPreferences("localday", 0)
    fun endHour(c: Context?): Int = c?.let { p(it).getInt("end", 4) } ?: 4
    fun setEndHour(c: Context, h: Int) = p(c).edit().putInt("end", h.coerceIn(0, 6)).apply()
    fun today(c: Context? = com.nizam.app.net.Router.appContext): LocalDate = LocalDateTime.now().minusHours(endHour(c).toLong()).toLocalDate()
}

/** Private topics go to the phone's own model, not the internet. */
object Sensitivity {
    private fun p(c: Context) = c.getSharedPreferences("sensitivity", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun strict(c: Context) = p(c).getBoolean("strict", false)
    fun set(c: Context, on: Boolean, strict: Boolean) = p(c).edit().putBoolean("on", on).putBoolean("strict", strict).apply()
    fun extra(c: Context): List<String> = p(c).getString("words", "").orEmpty().split(",").map { it.trim().lowercase() }.filter { it.length > 2 }
    fun setExtra(c: Context, s: String) = p(c).edit().putString("words", s).apply()

    private val BUILTIN = Regex("\\b(diagnos\\w*|symptom\\w*|prescri\\w*|medication|therapy|therapist|depress\\w*|anxiety|suicid\\w*|pregnan\\w*|std|hiv|" +
        "password|passcode|pin code|otp|cnic|passport no|iban|account number|card number|cvv|salary slip|tax return|lawsuit|divorce|nikah nama|" +
        "sexual\\w*|abuse\\w*|addict\\w*)\\b", RegexOption.IGNORE_CASE)

    fun reason(c: Context, text: String): String? {
        BUILTIN.find(text)?.let { return it.value.lowercase() }
        val t = text.lowercase()
        return extra(c).firstOrNull { t.contains(it) }
    }

    /** Narrows the failover list for a sensitive prompt; leaves everything else untouched. */
    fun route(c: Context?, prompt: String, list: List<com.nizam.app.net.Router.Endpoint>): List<com.nizam.app.net.Router.Endpoint> {
        if (c == null || !on(c) || reason(c, prompt) == null) return list
        val local = list.filter { it.kind == "LOCAL" || it.kind == "FOLAX" }
        if (local.isNotEmpty()) return local
        if (strict(c)) throw IllegalStateException("This looks private, and you've asked Atlas to keep private topics on the phone. Download an offline model in Settings › AI models.")
        return list
    }
}

/** XMLTV programme guide for the IPTV channels you've added. */
object Epg {
    data class Show(val start: Long, val stop: Long, val title: String, val desc: String)
    private fun file(c: Context) = File(c.filesDir, "epg.json")
    fun source(c: Context) = c.getSharedPreferences("epg", 0).getString("url", "").orEmpty()
    fun setSource(c: Context, u: String) = c.getSharedPreferences("epg", 0).edit().putString("url", u.trim()).apply()

    private val FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private fun time(s: String): Long = runCatching { ZonedDateTime.parse(s.trim(), FMT).toInstant().toEpochMilli() }
        .getOrElse { runCatching { ZonedDateTime.parse(s.trim().take(14) + " +0000", FMT).toInstant().toEpochMilli() }.getOrDefault(0L) }

    /** Downloads and keeps the next day of programmes for channels you have; returns how many shows were kept. */
    suspend fun refresh(c: Context, url: String): Int = withContext(Dispatchers.IO) {
        val want = com.nizam.app.feature.iptv.IptvStore(c).allChannels().map { it.tvgId.lowercase() }.filter { it.isNotBlank() }.toSet()
        val now = System.currentTimeMillis(); val until = now + 26 * 3_600_000L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000; setRequestProperty("Accept-Encoding", "gzip") }
        val out = JSONObject(); var kept = 0
        conn.inputStream.use { raw ->
            val gz = url.endsWith(".gz", true) || conn.contentEncoding.equals("gzip", true)
            val input = if (gz) GZIPInputStream(raw.buffered()) else raw.buffered()
            val xp = android.util.Xml.newPullParser().apply { setInput(input, null) }
            var ch = ""; var start = 0L; var stop = 0L; var title = ""; var desc = ""; var tag = ""
            while (xp.next() != XmlPullParser.END_DOCUMENT) {
                when (xp.eventType) {
                    XmlPullParser.START_TAG -> { tag = xp.name
                        if (tag == "programme") { ch = xp.getAttributeValue(null, "channel").orEmpty(); start = time(xp.getAttributeValue(null, "start").orEmpty())
                            stop = time(xp.getAttributeValue(null, "stop").orEmpty()); title = ""; desc = "" } }
                    XmlPullParser.TEXT -> when (tag) { "title" -> if (title.isEmpty()) title = xp.text.trim(); "desc" -> if (desc.isEmpty()) desc = xp.text.trim().take(240) }
                    XmlPullParser.END_TAG -> { if (xp.name == "programme" && stop > now && start < until && (want.isEmpty() || ch.lowercase() in want)) {
                            val a = out.optJSONArray(ch.lowercase()) ?: JSONArray().also { out.put(ch.lowercase(), it) }
                            if (a.length() < 40) { a.put(JSONArray().put(start).put(stop).put(title).put(desc)); kept++ } }
                        tag = "" }
                }
            }
        }
        file(c).writeText(out.toString()); kept
    }

    fun guide(c: Context): Map<String, List<Show>> = runCatching {
        val o = JSONObject(file(c).readText())
        o.keys().asSequence().associateWith { k -> val a = o.getJSONArray(k)
            (0 until a.length()).map { a.getJSONArray(it) }.map { Show(it.getLong(0), it.getLong(1), it.getString(2), it.optString(3)) }.sortedBy { it.start } }
    }.getOrDefault(emptyMap())
}

/** How long each app may be used per day; checked on the background tick. */
object Allowances {
    private fun p(c: Context) = c.getSharedPreferences("allowances", 0)
    fun all(c: Context): Map<String, Int> = p(c).all.filterKeys { it.startsWith("m:") }.mapKeys { it.key.removePrefix("m:") }.mapValues { (it.value as? Int) ?: 0 }
    fun set(c: Context, pkg: String, minutes: Int) = p(c).edit().apply { if (minutes <= 0) remove("m:$pkg") else putInt("m:$pkg", minutes) }.apply()
    fun granted(c: Context) = runCatching {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    fun usedToday(c: Context): Map<String, Int> = runCatching {
        val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        um.queryAndAggregateUsageStats(start, System.currentTimeMillis()).mapValues { (it.value.totalTimeInForeground / 60_000L).toInt() }
    }.getOrDefault(emptyMap())
    fun label(c: Context, pkg: String) = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** Called from the background worker: one gentle notification per app per day when its allowance runs out. */
    fun tick(c: Context) {
        if (!granted(c)) return
        val used = usedToday(c); val day = LocalDate.now().toString()
        all(c).forEach { (pkg, limit) ->
            val u = used[pkg] ?: 0
            if (u >= limit && p(c).getString("told:$pkg", "") != day) {
                p(c).edit().putString("told:$pkg", day).apply()
                runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 7300 + (pkg.hashCode() and 0xff), "Time's up for ${label(c, pkg)}", "You've used $u of your $limit minutes today.") }
            }
        }
    }
}

object Mega5 {
    private fun today() = LocalDay.today().toString()

    val all: List<Spec> by lazy { listOf(
        ScreenSpec("epg", "TV guide", "What's on now and next on your IPTV channels (XMLTV)", Icons.Outlined.LiveTv, Hub.Media,
            "epg xmltv tv guide schedule now next programme iptv") { c, open -> EpgScreen(open) },
        ScreenSpec("privaterouting", "Private topics stay on the phone", "Health, money and personal questions go to the offline model", Icons.Outlined.Shield, Hub.Tools,
            "sensitivity routing private local offline model health money privacy") { c, open -> SensitivityScreen() },
        ScreenSpec("dayline", "When my day ends", "Late-night entries count toward the day you were living", Icons.Outlined.Bedtime, Hub.Tools,
            "local day attribution midnight late night day ends") { c, open -> DayLineScreen() },
        ScreenSpec("allowances", "App time allowances", "Daily minutes per app, with a nudge when they run out", Icons.Outlined.HourglassBottom, Hub.Tools,
            "app time limit allowance screen time usage per app") { c, open -> AllowancesScreen() },
        LogSpec("tempready", "Temperature & pulse", "Morning temperature, resting pulse and sleep against your own baseline", Icons.Outlined.Thermostat, Hub.Health,
            "readiness temperature resting heart rate sleep recovery fever",
            listOf(F("d", "Date", K.DATE, default = today()), F("temp", "Temperature °C", K.NUMBER, hint = "36.6"), F("hr", "Resting pulse", K.NUMBER, optional = true),
                F("sleep", "Hours slept", K.NUMBER, optional = true), F("sore", "Soreness / tiredness", K.CHOICE, options = listOf("1", "2", "3", "4", "5"), default = "1")),
            line = { o -> "${o.optString("d")} · ${o.optString("temp")}°C" to "pulse ${o.optString("hr").ifBlank { "–" }} · sleep ${o.optString("sleep").ifBlank { "–" }}h · tired ${o.optString("sore")}/5" },
            summary = { l -> readiness(l) }),
        LogSpec("episodes", "Series progress", "Where you are in every show and how much is left", Icons.Outlined.Tv, Hub.Media,
            "series episode progress tv show season watched next episode",
            listOf(F("show", "Show"), F("season", "Season", K.NUMBER, default = "1"), F("ep", "Last episode watched", K.NUMBER), F("of", "Episodes this season", K.NUMBER, optional = true),
                F("d", "Date", K.DATE, default = today())),
            line = { o -> o.optString("show") to "S${o.optString("season")}E${o.optString("ep")}${if (o.optString("of").isNotBlank()) " of ${o.optString("of")}" else ""} · ${o.optString("d")}" },
            summary = { l -> l.groupBy { it.optString("show").trim().lowercase() }.mapNotNull { (_, e) ->
                val last = e.maxWithOrNull(compareBy({ num(it.optString("season")) }, { num(it.optString("ep")) })) ?: return@mapNotNull null
                val of = num(last.optString("of")); val ep = num(last.optString("ep"))
                "${last.optString("show")}: next S${last.optString("season")}E${(ep + 1).toInt()}" + if (of > 0) " · ${(ep * 100 / of).toInt()}% of the season, ${(of - ep).toInt()} left" else "" }.take(8) }),
    ) }

    private fun readiness(l: List<JSONObject>): List<String> {
        if (l.isEmpty()) return emptyList()
        val sorted = l.sortedBy { it.optString("d") }
        val last = sorted.last(); val base = sorted.dropLast(1).takeLast(14)
        fun median(xs: List<Double>) = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val bt = median(base.map { num(it.optString("temp")) }.filter { it > 30 })
        val bh = median(base.map { num(it.optString("hr")) }.filter { it > 20 })
        var score = 100.0; val why = mutableListOf<String>()
        val t = num(last.optString("temp")); val h = num(last.optString("hr")); val s = num(last.optString("sleep")); val sore = num(last.optString("sore"))
        if (bt != null && t > 30 && t - bt >= 0.3) { score -= (t - bt) / 0.3 * 15; why += "temperature +${"%.1f".format(t - bt)}°C over your normal" }
        if (bh != null && h > 20 && h - bh >= 3) { score -= (h - bh) * 4; why += "pulse ${(h - bh).toInt()} above normal" }
        if (s in 0.1..6.99) { score -= (7 - s) * 8; why += "short sleep" }
        if (sore > 1) { score -= (sore - 1) * 6; why += "tiredness $sore/5" }
        val sc = score.coerceIn(0.0, 100.0).toInt()
        val verdict = when { t >= 37.8 -> "Possible fever — rest, fluids, and see a doctor if it lasts."; sc >= 80 -> "Ready: a good day for hard work or training."
            sc >= 60 -> "Steady: normal day, keep training light."; else -> "Low: recover today, go easy." }
        return listOf("Readiness $sc/100 — $verdict", if (why.isEmpty()) "Everything near your baseline" else why.joinToString(" · "),
            "Baseline from ${base.size} days" + (bt?.let { " · normal temp ${"%.1f".format(it)}°C" } ?: "") + (bh?.let { " · normal pulse ${it.toInt()}" } ?: ""))
    }
}

@Composable
private fun H(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun EpgScreen(open: (String) -> Unit) {
    val c = LocalContext.current; val scope = com.nizam.app.ui.design.rememberSafeScope()
    var url by remember { mutableStateOf(Epg.source(c)) }
    var guide by remember { mutableStateOf(Epg.guide(c)) }
    var status by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    val names = remember { com.nizam.app.feature.iptv.IptvStore(c).allChannels().associate { it.tvgId.lowercase() to it.name } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(url, { url = it }, label = { Text("XMLTV guide link (.xml or .xml.gz)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { Epg.setSource(c, url); status = "Downloading…"
                scope.launch { status = runCatching { "Kept ${Epg.refresh(c, url)} shows for the next day" }.getOrElse { "Couldn't load the guide: ${it.message}" }; guide = Epg.guide(c) } },
                enabled = url.startsWith("http")) { Text("Update guide") }
            TextButton(onClick = { open(com.nizam.app.ui.nav.Routes.IPTV) }) { Text("Channels") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (names.isEmpty()) Text("Add an IPTV playlist first; the guide matches channels by their tvg-id.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(q, { q = it }, label = { Text("Find a channel or show") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val now = System.currentTimeMillis(); val fmt = DateTimeFormatter.ofPattern("HH:mm")
        fun hm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(fmt)
        guide.entries.mapNotNull { (id, shows) ->
            val cur = shows.firstOrNull { it.start <= now && it.stop > now }; val next = shows.firstOrNull { it.start > now }
            if (cur == null && next == null) null else Triple(names[id] ?: id, cur, next)
        }.filter { (n, cur, next) -> q.isBlank() || listOfNotNull(n, cur?.title, next?.title).any { it.contains(q, true) } }
            .sortedBy { it.first.lowercase() }.take(150).forEach { (n, cur, next) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(n, fontWeight = FontWeight.SemiBold)
                    cur?.let { val pct = ((now - it.start) * 100 / maxOf(1L, it.stop - it.start)).toInt()
                        Text("Now · ${it.title} (${hm(it.start)}–${hm(it.stop)}, $pct%)", style = MaterialTheme.typography.bodyMedium) }
                    next?.let { Text("Next · ${hm(it.start)} ${it.title}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        if (guide.isEmpty()) Text("No guide yet. Many IPTV providers publish an XMLTV link next to their playlist.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SensitivityScreen() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(Sensitivity.on(c)) }
    var strict by remember { mutableStateOf(Sensitivity.strict(c)) }
    var words by remember { mutableStateOf(Sensitivity.extra(c).joinToString(", ")) }
    var test by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("When a question touches health, money, passwords or family matters, Atlas answers it with the offline model on this phone. Numbers and IDs are already masked for online models either way.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(on, { on = it; Sensitivity.set(c, on, strict) }); Text("  Keep private topics on the phone") }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(strict, { strict = it; Sensitivity.set(c, on, strict) }, enabled = on)
            Text("  Never send them online, even with no offline model") }
        OutlinedTextField(words, { words = it; Sensitivity.setExtra(c, it) }, label = { Text("Extra private words (comma separated)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(test, { test = it }, label = { Text("Try a question") }, modifier = Modifier.fillMaxWidth())
        if (test.isNotBlank()) Text(Sensitivity.reason(c, test)?.let { "🔒 Private (\"$it\") — would stay on the phone" } ?: "🌐 Not private — the fastest model answers",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DayLineScreen() {
    val c = LocalContext.current
    var h by remember { mutableStateOf(LocalDay.endHour(c)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Entries you make after midnight but before this hour count toward the previous day — so a 1 am expense or journal belongs to the evening you were living.", style = MaterialTheme.typography.bodyMedium)
        Row { (0..6).forEach { v -> TextButton(onClick = { h = v; LocalDay.setEndHour(c, v) }) { Text(if (v == 0) "Midnight" else "${v}am", fontWeight = if (v == h) FontWeight.Bold else FontWeight.Normal) } } }
        Text("Right now it's ${LocalDay.today(c)} for Atlas.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AllowancesScreen() {
    val c = LocalContext.current
    var granted by remember { mutableStateOf(Allowances.granted(c)) }
    var limits by remember { mutableStateOf(Allowances.all(c)) }
    var used by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(granted) { com.nizam.app.ui.design.guard { if (granted) used = withContext(Dispatchers.IO) { Allowances.usedToday(c) } } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!granted) {
            Text("Atlas needs Usage access to see how long you've used each app today. Nothing leaves the phone.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { c.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Allow usage access") }
            TextButton(onClick = { granted = Allowances.granted(c) }) { Text("I've allowed it") }
            return@Column
        }
        H("With an allowance")
        if (limits.isEmpty()) Text("None yet — tap + on an app below.", style = MaterialTheme.typography.bodySmall)
        limits.forEach { (pkg, m) -> val u = used[pkg] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (u >= m) "⛔" else "⏳"} ${Allowances.label(c, pkg)} · $u / $m min", Modifier.weight(1f))
                TextButton(onClick = { Allowances.set(c, pkg, m - 15); limits = Allowances.all(c) }) { Text("−15") }
                TextButton(onClick = { Allowances.set(c, pkg, m + 15); limits = Allowances.all(c) }) { Text("+15") }
            } }
        H("Most used today")
        used.filter { it.value > 0 && it.key != c.packageName && it.key !in limits }.entries.sortedByDescending { it.value }.take(15).forEach { (pkg, u) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Allowances.label(c, pkg)} · $u min", Modifier.weight(1f))
                TextButton(onClick = { Allowances.set(c, pkg, maxOf(15, (u / 15) * 15)); limits = Allowances.all(c) }) { Text("+ allowance") }
            } }
    }
}
