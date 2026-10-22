package com.nizam.app.feature.future

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.finance.Txn
import com.nizam.app.feature.money.BankSms
import com.nizam.app.feature.money.BankTxn
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/**
 * Money autopilot: every bank, JazzCash and Easypaisa message is logged the moment it arrives (or offered with
 * one tap), duplicates are caught, categories learn from your corrections, budgets warn you before you cross them,
 * and the month is forecast — so you hear "you'll be short by the 25th" while there's still time.
 */
object MoneyPilot {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val SOURCES = setOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging", "com.transsion.message",
        "com.techlab.mobilewallet.jazzcash.customer", "pk.com.telenor.phoenix", "com.hbl.android.hblmobilebanking", "com.meezanbank.meezan",
        "com.ubl.digital", "com.mcb.mcbislamic", "com.sadapay.app", "com.nayapay.app", "com.bankalfalah.alfamobile")
    private val recent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun p(c: Context) = c.getSharedPreferences("moneypilot", 0)
    fun live(c: Context) = p(c).getBoolean("live", true)
    fun setLive(c: Context, v: Boolean) = p(c).edit().putBoolean("live", v).apply()

    fun category(c: Context, merchant: String): String =
        p(c).getString("cat:" + merchant.lowercase().trim(), null) ?: BankSms.category(merchant)
    fun learn(c: Context, merchant: String, category: String) = p(c).edit().putString("cat:" + merchant.lowercase().trim(), category).apply()

    /** From Atlas's notification listener: SMS apps and wallets/banks. */
    fun onNotification(c: Context, pkg: String, title: String, text: String) {
        if (!live(c) || !BankSms.enabled(c)) return
        if (pkg !in SOURCES && !pkg.contains("bank", true) && !pkg.contains("wallet", true) && !pkg.contains("messag", true)) return
        val t = BankSms.parse("$title. $text", System.currentTimeMillis(), System.nanoTime()) ?: return
        // the same payment often arrives twice (SMS and the bank app): once is enough
        val sig = "${t.kind}:${t.amount}:${t.merchant.lowercase().take(12)}"
        if ((System.currentTimeMillis() - (recent[sig] ?: 0)) < 15 * 60_000) return
        recent[sig] = System.currentTimeMillis()
        if (BankSms.auto(c)) scope.launch { log(c, t) } else offer(c, t)
    }

    private fun offer(c: Context, t: BankTxn) {
        val id = (t.id and 0x7FFF_FFFF).toInt()
        p(c).edit().putString("pending:$id", JSONObject().put("a", t.amount).put("m", t.merchant).put("k", t.kind).put("d", t.date).toString()).apply()
        val log = PendingIntent.getBroadcast(c, id, Intent(c, MoneyReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE).setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("${if (t.kind == "INCOME") "+" else "−"} Rs ${"%,.0f".format(t.amount)} · ${t.merchant}")
            .setContentText("Log it as ${category(c, t.merchant)}?").addAction(0, "Log it", log).setAutoCancel(true).build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(c).notify(1400 + (id and 0xFFF), n) }
    }

    fun logPending(c: Context, id: Int) {
        val o = p(c).getString("pending:$id", null)?.let { JSONObject(it) } ?: return
        p(c).edit().remove("pending:$id").apply()
        scope.launch { log(c, BankTxn(o.optDouble("a"), o.optString("m"), o.optString("k"), o.optString("d"), "", System.nanoTime())) }
    }

    suspend fun log(c: Context, t: BankTxn) {
        val container = com.nizam.app.feature.jarvis.Background.container(c)
        val cat = category(c, t.merchant)
        container.financeRepository.addOn(t.date, t.amount, t.kind, cat, t.merchant)
        if (t.kind == "EXPENSE") checkBudget(c, container, cat)
    }

    /** Warns once at 80% and once at 100% of a category's monthly budget. */
    private suspend fun checkBudget(c: Context, container: AppContainer, cat: String) {
        val budget = container.financeRepository.budgets().first().firstOrNull { it.category == cat }?.monthlyAmount ?: return
        if (budget <= 0) return
        val month = YearMonth.now()
        val spent = container.financeRepository.since(month.atDay(1).toString()).first().filter { it.kind == "EXPENSE" && it.category == cat }.sumOf { it.amount }
        val pct = spent / budget
        for (level in listOf(1.0, 0.8)) {
            val mark = "warn:$cat:$month:$level"
            if (pct >= level && !p(c).getBoolean(mark, false)) {
                p(c).edit().putBoolean(mark, true).apply()
                com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 1500 + (cat.hashCode() and 0xFF),
                    if (level >= 1.0) "Over your $cat budget" else "80% of your $cat budget used",
                    "Rs ${"%,.0f".format(spent)} of Rs ${"%,.0f".format(budget)} this month")
                break
            }
        }
    }

    // ── the forecast ───────────────────────────────────────────────────────────────────────────
    data class Outlook(val income: Double, val spent: Double, val projected: Double, val endBalance: Double, val daysLeft: Int,
                       val subscriptions: List<Pair<String, Double>>, val jumps: List<String>, val top: List<Pair<String, Double>>, val vsLast: List<String>)

    suspend fun outlook(container: AppContainer): Outlook {
        val today = LocalDate.now(); val month = YearMonth.now()
        val all = container.financeRepository.since(month.minusMonths(3).atDay(1).toString()).first()
        fun inMonth(t: Txn, m: YearMonth) = t.localDate.startsWith(m.toString())
        val now = all.filter { inMonth(it, month) }
        val last = all.filter { inMonth(it, month.minusMonths(1)) }
        val income = maxOf(now.filter { it.kind == "INCOME" }.sumOf { it.amount }, last.filter { it.kind == "INCOME" }.sumOf { it.amount })
        val spent = now.filter { it.kind == "EXPENSE" }.sumOf { it.amount }
        val perDay = spent / today.dayOfMonth.coerceAtLeast(1)
        val projected = perDay * month.lengthOfMonth()
        // the same merchant, a similar amount, in at least two of the last three months: a subscription
        val subs = all.filter { it.kind == "EXPENSE" && it.note.isNotBlank() }.groupBy { it.note.lowercase().take(20) }.mapNotNull { (_, l) ->
            val months = l.groupBy { it.localDate.take(7) }
            if (months.size < 2) return@mapNotNull null
            val amts = months.values.map { m -> m.sumOf { it.amount } }
            if (amts.maxOrNull()!! > amts.minOrNull()!! * 1.15 || l.size > months.size * 2) null else l.first().note to amts.average()
        }.sortedByDescending { it.second }
        val jumps = now.filter { it.kind == "EXPENSE" && it.category == "Bills" }.groupBy { it.note.lowercase().take(20) }.mapNotNull { (k, l) ->
            val before = all.filter { it.kind == "EXPENSE" && it.note.lowercase().take(20) == k && !inMonth(it, month) }.groupBy { it.localDate.take(7) }.values.map { m -> m.sumOf { it.amount } }
            val cur = l.sumOf { it.amount }
            if (before.isNotEmpty() && cur > before.average() * 1.3) "${l.first().note}: Rs ${"%,.0f".format(cur)} — ${((cur / before.average() - 1) * 100).toInt()}% more than usual" else null
        }
        val top = now.filter { it.kind == "EXPENSE" }.groupBy { it.category }.map { it.key to it.value.sumOf { t -> t.amount } }.sortedByDescending { it.second }
        val lastByCat = last.filter { it.kind == "EXPENSE" }.groupBy { it.category }.mapValues { it.value.sumOf { t -> t.amount } }
        val vs = top.mapNotNull { (k, v) -> lastByCat[k]?.takeIf { it > 0 }?.let { lv ->
            val proj = v / today.dayOfMonth * month.lengthOfMonth(); val ch = ((proj / lv - 1) * 100).toInt()
            if (kotlin.math.abs(ch) >= 20) "$k: on track for ${if (ch > 0) "+" else ""}$ch% vs last month" else null } }
        return Outlook(income, spent, projected, income - projected, month.lengthOfMonth() - today.dayOfMonth, subs, jumps, top, vs)
    }
}

class MoneyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        MoneyPilot.logPending(c, id)
        androidx.core.app.NotificationManagerCompat.from(c).cancel(1400 + (id and 0xFFF))
    }
}

@Composable
fun MoneyPilotScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var o by remember { mutableStateOf<MoneyPilot.Outlook?>(null) }
    var advice by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(MoneyPilot.live(c)) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { o = runCatching { MoneyPilot.outlook(container) }.getOrNull() } }
    fun rs(v: Double) = "Rs " + "%,.0f".format(v)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Money autopilot", "Where this month is heading — while there's time to steer")
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Log payments as they happen", style = MaterialTheme.typography.titleSmall)
                    Text(if (BankSms.enabled(c)) "Bank, JazzCash and Easypaisa messages — ${if (BankSms.auto(c)) "logged automatically" else "one tap to log"}"
                        else "Turn on “Read bank messages” in Finance › Auto-logging first", style = MaterialTheme.typography.bodySmall)
                }
                Switch(live, { live = it; MoneyPilot.setLive(c, it) })
            }
        }
        val x = o
        if (x == null) Thinking(label = "Reading your month") else {
            Label("This month")
            Group {
                Text("Spent ${rs(x.spent)} · income ${rs(x.income)}", style = MaterialTheme.typography.titleMedium)
                Text("At this pace: ${rs(x.projected)} by month end", style = MaterialTheme.typography.bodyLarge)
                Text(if (x.endBalance >= 0) "You'd finish about ${rs(x.endBalance)} ahead" else "⚠️ You'd be about ${rs(-x.endBalance)} short — ${x.daysLeft} days to fix it",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (x.endBalance >= 0) Color(0xFF3DAA6D) else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                if (x.income == 0.0) Text("No income logged yet — add your salary in Finance for a real forecast.", style = MaterialTheme.typography.bodySmall)
            }
            if (x.top.isNotEmpty()) { Label("Biggest categories"); Group { x.top.take(6).forEach { (k, v) -> Text("$k · ${rs(v)}", style = MaterialTheme.typography.bodyMedium) }
                x.vsLast.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            if (x.subscriptions.isNotEmpty()) { Label("Subscriptions & repeats · ${rs(x.subscriptions.sumOf { it.second })}/month")
                Group { x.subscriptions.take(10).forEach { (k, v) -> Text("$k · ${rs(v)}/month", style = MaterialTheme.typography.bodyMedium) } } }
            if (x.jumps.isNotEmpty()) { Label("Bills that jumped"); Group { x.jumps.forEach { Text("⚠️ $it", style = MaterialTheme.typography.bodyMedium) } } }
            Button(onClick = {
                advice = "…"
                scope.launch { advice = runCatching { Ai.generate(container.settingsRepository,
                    "My month: income ${rs(x.income)}, spent ${rs(x.spent)} so far, projected ${rs(x.projected)}, ${x.daysLeft} days left. " +
                        "By category: ${x.top.joinToString { "${it.first} ${rs(it.second)}" }}. Subscriptions: ${x.subscriptions.joinToString { "${it.first} ${rs(it.second)}" }}. " +
                        "Bill jumps: ${x.jumps.joinToString()}.\nGive me the 3 most effective, specific cuts or moves for the rest of this month, with the rupees each saves. Short.",
                    maxOutputTokens = 500) }.getOrElse { "Couldn't advise right now: ${Feedback.friendly(it)}" } }
            }, modifier = Modifier.padding(top = 14.dp)) { Text("What should I cut?") }
            if (advice.isNotBlank()) Group(Modifier.padding(top = 8.dp)) { Text(advice, style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.height(50.dp))
    }
}
