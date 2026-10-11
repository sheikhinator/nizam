package com.nizam.app.feature.hq

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.nizam.app.feature.studio.Deliverables
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.random.Random

/**
 * A rival company and paying clients. Nova Labs is a simulated competitor whose weekly output tracks
 * your own pace, so the race stays close; clients bring briefs that your company really makes, and
 * their payment and rating depend on what you think of the delivered work.
 */
object Rival {
    private fun p(c: Context) = c.getSharedPreferences("company_rival", Context.MODE_PRIVATE)
    fun week(d: LocalDate = LocalDate.now()) = d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** Our output this week: deliverables made plus finished client work, weighted. */
    fun ourScore(c: Context): Int {
        val start = LocalDate.now().with(java.time.DayOfWeek.MONDAY).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val made = Deliverables.load(c).count { it.created >= start }
        val paid = Clients.all(c).count { it.optString("state") == "paid" && it.optLong("paidAt") >= start }
        return made * 10 + paid * 15
    }

    /** Nova's score: last week's average of yours, with a seeded swing — beatable, never trivially. */
    fun theirScore(c: Context): Int {
        val w = week(); val r = Random(w * 31 + 7)
        val base = p(c).getInt("avg", 30).coerceAtLeast(20)
        val day = LocalDate.now().dayOfWeek.value
        return ((base * (0.7 + r.nextDouble() * 0.6)) * day / 7.0).toInt()
    }

    /** Closes past weeks: records wins and losses, and moves Nova's baseline toward your pace. */
    fun settle(c: Context) {
        val w = week(); val last = p(c).getInt("week", 0)
        if (last == 0) { p(c).edit().putInt("week", w).apply(); return }
        if (last == w) return
        val ours = p(c).getInt("ourLast", 0); val theirs = p(c).getInt("theirLast", 0)
        val won = ours >= theirs
        p(c).edit().putInt("week", w).putInt("wins", wins(c) + if (won) 1 else 0).putInt("losses", losses(c) + if (won) 0 else 1)
            .putInt("avg", ((p(c).getInt("avg", 30) * 2 + ours) / 3).coerceAtLeast(20)).apply()
        runCatching { Company.note(c, "rival", if (won) "We beat Nova Labs" else "Nova Labs edged us", "Week score $ours vs $theirs") }
    }
    fun record(c: Context, ours: Int, theirs: Int) = p(c).edit().putInt("ourLast", ours).putInt("theirLast", theirs).apply()
    fun wins(c: Context) = p(c).getInt("wins", 0)
    fun losses(c: Context) = p(c).getInt("losses", 0)

    private val MOVES = listOf("Nova Labs shipped a slide-deck generator.", "Nova poached a designer from a rival studio.", "Nova is pitching a bakery chain in Lahore.",
        "Nova's CEO posted about 'AI-first everything'.", "Nova launched weekly podcast summaries.", "Nova cut prices by 20% to win clients.",
        "Nova's app crashed during a client demo.", "Nova opened a Karachi office.", "Nova hired three junior analysts.", "Nova won an award for a pitch deck.")
    fun moves(): List<String> { val r = Random(week()); return MOVES.shuffled(r).take(3) }
}

object Clients {
    private fun p(c: Context) = c.getSharedPreferences("company_clients", Context.MODE_PRIVATE)
    fun all(c: Context): List<JSONObject> = runCatching { JSONArray(p(c).getString("list", "[]")) }.getOrElse { JSONArray() }.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun save(c: Context, l: List<JSONObject>) = p(c).edit().putString("list", JSONArray(l.takeLast(60)).toString()).apply()
    fun revenue(c: Context) = all(c).filter { it.optString("state") == "paid" }.sumOf { it.optInt("paid") }

    private val BRIEFS = listOf(
        Triple("Ali's Bakery", "A one-page price list for a home bakery with 12 items, cakes, rusks and nimko, in English and Urdu", 4000),
        Triple("Sana (student)", "A 10-slide deck explaining climate change effects on Pakistan's glaciers for a school presentation", 3500),
        Triple("Bilal Motors", "A spreadsheet to track 20 used cars: purchase price, repairs, asking price and profit", 5000),
        Triple("Hira Tutors", "A weekly study timetable PDF for O-level students with 6 subjects", 3000),
        Triple("Masjid committee", "A Ramadan iftar volunteer schedule and a short announcement", 2500),
        Triple("Usman (freelancer)", "A clean CV document for a junior Android developer with 2 years of experience", 4500),
        Triple("Green Valley School", "A narrated 2-minute explainer about handwashing for children", 6000),
        Triple("Kiran Boutique", "An Instagram caption pack of 15 posts for an Eid lawn collection", 3500),
        Triple("Farm house rental", "A booking policy document with prices, rules and cancellation terms", 3000),
        Triple("Cricket club", "A season fixtures sheet for 8 teams, round robin", 2500),
    )

    /** Three offers each week, picked from the brief list. */
    fun offers(c: Context): List<Triple<String, String, Int>> {
        val taken = all(c).map { it.optString("brief") }.toSet()
        return BRIEFS.shuffled(Random(Rival.week() * 13)).filter { it.second !in taken }.take(3)
    }

    fun accept(container: AppContainer, offer: Triple<String, String, Int>) {
        val c = container.appContext
        val job = Deliverables.order(container, offer.second)
        save(c, all(c) + JSONObject().put("client", offer.first).put("brief", offer.second).put("fee", offer.third).put("job", job)
            .put("state", "working").put("at", System.currentTimeMillis()))
        runCatching { Company.note(c, "client", "New client: ${offer.first}", offer.second) }
    }

    fun deliveredItem(c: Context, o: JSONObject): Deliverables.Item? = Deliverables.load(c).firstOrNull { it.id == o.optString("job") || it.request == o.optString("brief") }

    /** You judge the work as the client: payment scales with your rating. */
    fun rate(c: Context, job: String, stars: Int) {
        save(c, all(c).map { o -> if (o.optString("job") != job) o else o.put("state", "paid").put("stars", stars)
            .put("paid", o.optInt("fee") * (stars.coerceIn(1, 5) + 5) / 10).put("paidAt", System.currentTimeMillis()) })
    }
}

@Composable
fun RivalTab(container: AppContainer) {
    val c = LocalContext.current
    remember { Rival.settle(c) }
    val ours = remember { Rival.ourScore(c) }; val theirs = remember { Rival.theirScore(c) }
    remember(ours, theirs) { Rival.record(c, ours, theirs) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This week vs Nova Labs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val total = (ours + theirs).coerceAtLeast(1)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Atlas $ours", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text("$theirs Nova") }
        LinearProgressIndicator(progress = { ours / total.toFloat() }, modifier = Modifier.fillMaxWidth())
        Text(when { ours > theirs -> "You're ahead. Keep shipping."; ours == theirs -> "Neck and neck."; else -> "Nova is ahead by ${theirs - ours}. One deliverable is 10 points, a paid client 15." },
            style = MaterialTheme.typography.bodyMedium)
        Text("Record: ${Rival.wins(c)} won · ${Rival.losses(c)} lost", style = MaterialTheme.typography.bodySmall)
        Text("Industry news", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Rival.moves().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun ClientsTab(container: AppContainer) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val jobs by Deliverables.jobs.collectAsState()
    val list = remember(tick, jobs) { Clients.all(c).reversed() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Revenue from clients: Rs ${"%,d".format(Clients.revenue(c))}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("New briefs this week", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        remember(tick) { Clients.offers(c) }.forEach { o ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${o.first} · Rs ${"%,d".format(o.third)}", fontWeight = FontWeight.SemiBold)
                Text(o.second, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { Clients.accept(container, o); tick++ }) { Text("Take the job") }
            }
        }
        if (list.isNotEmpty()) Text("Your clients", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        list.forEach { o ->
            val job = o.optString("job"); val live = jobs[job]; val item = Clients.deliveredItem(c, o)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${o.optString("client")} · ${o.optString("brief").take(60)}", fontWeight = FontWeight.SemiBold)
                when {
                    o.optString("state") == "paid" -> Text("Paid Rs ${"%,d".format(o.optInt("paid"))} · ${"★".repeat(o.optInt("stars"))}", style = MaterialTheme.typography.bodySmall)
                    item != null -> {
                        Text("Delivered — how would the client rate it?", style = MaterialTheme.typography.bodySmall)
                        Row { item.main(c)?.let { f -> TextButton(onClick = { runCatching { c.startActivity(Deliverables.openIntent(c, f)) } }) { Text("Open") } }
                            (1..5).forEach { s -> TextButton(onClick = { Clients.rate(c, job, s); tick++ }) { Text("$s★") } } }
                    }
                    live?.error != null -> Text("Hit a problem: ${live.error}", style = MaterialTheme.typography.bodySmall)
                    live != null -> { Text("${live.stage} · ${live.who}", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { live.fraction }, modifier = Modifier.fillMaxWidth()) }
                    else -> Text("Working on it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
