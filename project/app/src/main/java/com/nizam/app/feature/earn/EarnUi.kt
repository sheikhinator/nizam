package com.nizam.app.feature.earn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.compose.material.icons.outlined.Paid
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/** The Treasury look: its own palette, always dark, gold on deep emerald, numbers in mono. */
private object T {
    val ink = Color(0xFF050D0A); val deep = Color(0xFF0A1A14); val panel = Color(0xFF0F241C); val line = Color(0xFF1F3B30)
    val gold = Color(0xFFE8C46A); val goldDim = Color(0xFF9C8445); val mint = Color(0xFF3DDC97); val text = Color(0xFFE9F2EC)
    val dim = Color(0xFF8CA79A); val rose = Color(0xFFFF6B7D); val sky = Color(0xFF6CC4FF)
    val mono = FontFamily.Monospace; val serif = FontFamily.Serif
}

private val TABS = listOf("HQ", "Work", "Shop", "Money", "Grow", "Coach", "Setup")

@Composable
fun EarnScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    var tab by remember { mutableStateOf("HQ") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Earn.refreshRate(c) }; tick++ } }
    val refresh = { tick++ }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(T.ink, T.deep, T.ink)))) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item("head") { Header(c, tick) }
            item("tabs") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TABS.forEach { t -> Pill(t, tab == t) { tab = t } }
                }
            }
            item("body") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (tab) {
                        "HQ" -> Hq(c, tick) { tab = it }
                        "Work" -> WorkTab(container, tick, refresh)
                        "Shop" -> ShopTab(container, tick, refresh)
                        "Money" -> MoneyTab(container, tick, refresh)
                        "Grow" -> GrowTab(tick, refresh)
                        "Coach" -> CoachTab(container, tick, refresh)
                        else -> SetupTab(tick, refresh)
                    }
                }
            }
        }
    }
}

// ───────────────────────── pieces ─────────────────────────

@Composable private fun Label(t: String) = Text(t.uppercase(), color = T.goldDim, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(T.panel, T.deep)))
        .border(BorderStroke(1.dp, T.line), RoundedCornerShape(18.dp)).padding(16.dp)) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
private fun Pill(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, Modifier.clip(RoundedCornerShape(50)).background(if (on) T.gold else T.panel).border(1.dp, if (on) T.gold else T.line, RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp), color = if (on) T.ink else T.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun GoldButton(t: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(t, Modifier.clip(RoundedCornerShape(12.dp)).background(if (enabled) T.gold else T.line).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp), color = if (enabled) T.ink else T.dim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun Ghost(t: String, onClick: () -> Unit) {
    Text(t, Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, T.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), color = T.gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Field(v: String, on: (String) -> Unit, label: String, modifier: Modifier = Modifier, lines: Int = 1) {
    OutlinedTextField(v, on, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = T.text, unfocusedTextColor = T.text, focusedBorderColor = T.gold, unfocusedBorderColor = T.line,
            focusedLabelColor = T.gold, unfocusedLabelColor = T.dim, cursorColor = T.gold))
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = T.text, fontWeight = FontWeight.Medium); Text(sub, color = T.dim, fontSize = 12.sp) }
        Switch(on, set, colors = SwitchDefaults.colors(checkedThumbColor = T.ink, checkedTrackColor = T.gold, uncheckedTrackColor = T.panel, uncheckedBorderColor = T.line))
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60000
    return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(c: android.content.Context, tick: Int) {
    val feed = remember(tick) { Earn.feed(c).all().sortedByDescending { it.optLong("t") }.take(8) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            Text("Atlas", color = T.text, fontFamily = T.serif, fontSize = 30.sp)
            Text(" Earn", color = T.gold, fontFamily = T.serif, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$1 = Rs ${"%.1f".format(Earn.rate(c))}", color = T.dim, fontFamily = T.mono, fontSize = 12.sp)
        }
        // the live ticker of everything Atlas did for you
        val line = if (feed.isEmpty()) "Your earning engine is ready · Tonight Atlas hunts gigs, writes proposals, does work and builds products · You wake up to the report ·"
            else feed.joinToString("   ◆   ") { "${it.optString("icon")} ${it.optString("text")} · ${ago(it.optLong("t"))}" }
        Text(line, Modifier.fillMaxWidth().padding(top = 8.dp).background(T.panel).padding(vertical = 6.dp).basicMarquee(iterations = Int.MAX_VALUE),
            color = T.gold, fontFamily = T.mono, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun GoalRing(frac: Float, size: Int = 110) {
    Canvas(Modifier.size(size.dp)) {
        val s = Stroke(width = 12f, cap = StrokeCap.Round)
        drawArc(T.line, -220f, 260f, false, style = s, topLeft = Offset(8f, 8f), size = Size(this.size.width - 16f, this.size.height - 16f))
        drawArc(Brush.sweepGradient(listOf(T.mint, T.gold, T.mint)), -220f, 260f * frac.coerceIn(0f, 1f), false, style = s,
            topLeft = Offset(8f, 8f), size = Size(this.size.width - 16f, this.size.height - 16f))
    }
}

// ───────────────────────── HQ ─────────────────────────

@Composable
private fun Hq(c: android.content.Context, tick: Int, go: (String) -> Unit) {
    val s = remember(tick) { Earn.stats(c) }
    val goal = Earn.goal(c)
    val frac = (s.month / goal).toFloat()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Earned this month")
                com.nizam.app.ui.style.RollingNumber(s.month, { Earn.fmt(it) }, TextStyle(fontFamily = T.mono, fontSize = 30.sp, fontWeight = FontWeight.Bold), T.gold)
                Text("of ${Earn.fmt(goal)} goal · ${(frac * 100).toInt()}%", color = T.dim, fontSize = 12.sp)
                val vs = if (s.lastMonth > 0) (s.month - s.lastMonth) / s.lastMonth * 100 else null
                if (vs != null) Text("${if (vs >= 0) "▲" else "▼"} ${"%.0f".format(kotlin.math.abs(vs))}% vs last month", color = if (vs >= 0) T.mint else T.rose, fontSize = 12.sp)
            }
            Box(contentAlignment = Alignment.Center) {
                GoalRing(frac)
                Text("${(frac * 100).toInt()}%", color = T.text, fontFamily = T.mono, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Mini("Today", Earn.fmt(s.today)); Mini("7 days", Earn.fmt(s.week)); Mini("USD", Earn.usd(s.usdMonth)); Mini("All time", Earn.fmt(s.allTime))
        }
        if (s.pending > 0) Text("⏳ ${Earn.fmt(s.pending)} expected, not yet credited", color = T.sky, fontSize = 12.sp)
    }
    // while you slept
    val since = System.currentTimeMillis() - 16 * 3_600_000L
    val night = remember(tick) { Earn.feed(c).all().filter { it.optLong("t") > since }.sortedByDescending { it.optLong("t") } }
    Panel {
        Label(if (java.time.LocalTime.now().hour < 13) "While you slept" else "Last 16 hours")
        if (night.isEmpty()) Text("Nothing yet. Tonight between 1 and 5am Atlas hunts gigs, writes proposals, does accepted work and builds a product.", color = T.dim, fontSize = 13.sp)
        night.take(8).forEach { e ->
            Row { Text(e.optString("icon"), Modifier.width(26.dp)); Text(e.optString("text"), color = T.text, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(ago(e.optLong("t")), color = T.dim, fontSize = 11.sp, fontFamily = T.mono) }
        }
    }
    // needs your tap
    val w = remember(tick) { Earn.work(c).all() }
    val pr = remember(tick) { Earn.products(c).all() }
    val unsorted = remember(tick) { Earn.income(c).all().count { it.optString("status") == "unsorted" } }
    val props = w.count { it.optString("stage") == "lead" && it.optString("proposal").isNotBlank() }
    val ready = w.count { it.optString("stage") == "ready" }
    val publish = pr.count { it.optString("stage") == "built" }
    Panel {
        Label("Needs your tap")
        if (props + ready + publish + unsorted == 0) Text("All clear. Atlas is on it.", color = T.dim, fontSize = 13.sp)
        if (ready > 0) TapRow("✅", "$ready finished deliverable${if (ready > 1) "s" else ""} to check and send") { go("Work") }
        if (props > 0) TapRow("✍", "$props proposal${if (props > 1) "s" else ""} written, one tap to send") { go("Work") }
        if (publish > 0) TapRow("📦", "$publish product${if (publish > 1) "s" else ""} built, ready to publish") { go("Shop") }
        if (unsorted > 0) TapRow("❓", "$unsorted credit${if (unsorted > 1) "s" else ""} to sort: earnings or not?") { go("Money") }
    }
    // streams
    Panel {
        Label("Streams this month")
        val max = (s.byStream.values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        if (s.byStream.isEmpty()) Text("Your first rupee shows up here.", color = T.dim, fontSize = 13.sp)
        s.byStream.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k, color = T.text, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(T.line)) {
                    Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.03f, 1f)).height(10.dp).background(Brush.horizontalGradient(listOf(T.mint, T.gold))))
                }
                Text(Earn.fmt(v), color = T.gold, fontFamily = T.mono, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    Panel {
        Label("Last 30 days")
        if (s.daily.any { it > 0 }) com.nizam.app.ui.design.TrendLine(s.daily, Modifier.fillMaxWidth(), height = 110.dp, accent = T.gold, format = { Earn.fmt(it) })
        else Text("Daily earnings draw themselves here.", color = T.dim, fontSize = 13.sp)
    }
    Panel {
        Label("Pipeline")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Earn.STAGES.forEach { st -> val n = w.count { it.optString("stage") == st }
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(T.deep).border(1.dp, T.line, RoundedCornerShape(12.dp)).clickable { go("Work") }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$n", color = if (n > 0) T.gold else T.dim, fontFamily = T.mono, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(Earn.STAGE_LABEL[st] ?: st, color = T.dim, fontSize = 11.sp)
                } }
        }
        Text("${pr.size} products · ${pr.count { it.optString("stage") == "published" }} live · ${pr.sumOf { it.optInt("sales") }} sales", color = T.dim, fontSize = 12.sp)
    }
}

@Composable private fun Mini(l: String, v: String) = Column { Text(v, color = T.text, fontFamily = T.mono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(l, color = T.dim, fontSize = 11.sp) }

@Composable
private fun TapRow(icon: String, t: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.deep).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, Modifier.width(28.dp)); Text(t, color = T.text, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text("›", color = T.gold, fontSize = 20.sp)
    }
}

// ───────────────────────── Work ─────────────────────────

@Composable
private fun WorkTab(container: AppContainer, tick: Int, refresh: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var stage by remember { mutableStateOf("lead") }
    var busy by remember { mutableStateOf(false) }
    var openJob by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val all = remember(tick) { Earn.work(c).all() }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Earn.STAGES.forEach { st -> Pill("${Earn.STAGE_LABEL[st]} ${all.count { it.optString("stage") == st }}", stage == st) { stage = st } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GoldButton(if (busy) "Hunting…" else "🎯 Hunt gigs now", !busy) {
            busy = true; scope.launch { runCatching { Earn.huntGigs(container, c) }.onSuccess { Feedback.show("Found $it new gigs") }.onFailure { Feedback.show(it.message ?: "Couldn't search now") }; busy = false; refresh() }
        }
        Ghost("+ Add a job") { adding = !adding }
    }
    if (adding) Panel {
        var t by remember { mutableStateOf("") }; var cl by remember { mutableStateOf("") }; var pay by remember { mutableStateOf("") }
        var cur by remember { mutableStateOf("USD") }; var brief by remember { mutableStateOf("") }
        Label("A job you got or found")
        Field(t, { t = it }, "Title"); Field(cl, { cl = it }, "Client / platform")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(pay, { pay = it.filter { ch -> ch.isDigit() || ch == '.' } }, "Pay", Modifier.weight(1f)); Spacer(Modifier.width(8.dp))
            Pill("USD", cur == "USD") { cur = "USD" }; Spacer(Modifier.width(4.dp)); Pill("PKR", cur == "PKR") { cur = "PKR" }
        }
        Field(brief, { brief = it }, "What they need", lines = 3)
        GoldButton("Add as won", t.isNotBlank()) {
            Earn.work(c).add(JSONObject().put("title", t.trim()).put("client", cl.trim()).put("pay", pay.toDoubleOrNull() ?: 0.0).put("cur", cur).put("brief", brief.trim())
                .put("stage", "won").put("score", 8).put("t", System.currentTimeMillis()))
            Earn.log(c, "🤝", "New job: ${t.trim().take(50)}"); adding = false; stage = "won"; refresh()
        }
    }
    val shown = all.filter { it.optString("stage") == stage }.sortedWith(compareByDescending<JSONObject> { it.optInt("score") }.thenByDescending { it.optLong("t") })
    if (shown.isEmpty()) Text(when (stage) { "lead" -> "No leads yet. Tap Hunt, or wait for tonight's hunt."; "working" -> "Move a won job here and Atlas works on it tonight."
        else -> "Nothing here." }, color = T.dim, fontSize = 13.sp)
    shown.forEach { j -> JobCard(c, j) { openJob = j.optString("id") } }
    openJob?.let { id -> all.firstOrNull { it.optString("id") == id }?.let { j -> JobSheet(container, j, refresh) { openJob = null } } }
}

@Composable
private fun JobCard(c: android.content.Context, j: JSONObject, onClick: () -> Unit) {
    Panel(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(j.optString("title"), color = T.text, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(j.optString("platform"), j.optString("client")).filter { it.isNotBlank() }.joinToString(" · "), color = T.dim, fontSize = 12.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                val pay = j.optDouble("pay", 0.0)
                if (pay > 0) Text(if (j.optString("cur") == "USD") Earn.usd(pay) else Earn.fmt(pay), color = T.gold, fontFamily = T.mono, fontWeight = FontWeight.Bold)
                Text("fit ${j.optInt("score", 5)}/10", color = T.mint, fontSize = 11.sp)
            }
        }
        val flags = listOfNotNull(if (j.optString("proposal").isNotBlank() && j.optString("stage") == "lead") "✍ proposal ready" else null,
            if (j.optString("deliverable").isNotBlank()) "✅ deliverable ready" else null)
        if (flags.isNotEmpty()) Text(flags.joinToString("  ·  "), color = T.gold, fontSize = 12.sp)
    }
}

@Composable
private fun JobSheet(container: AppContainer, j: JSONObject, refresh: () -> Unit, close: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var busy by remember { mutableStateOf("") }
    var notes by remember(j.optString("id")) { mutableStateOf(j.optString("notes")) }
    val id = j.optString("id")
    fun move(st: String) { Earn.work(c).update(id) { it.put("stage", st) }; Earn.log(c, "➡", "${j.optString("title").take(40)} → ${Earn.STAGE_LABEL[st]}"); refresh() }
    AlertDialog(onDismissRequest = close, containerColor = T.deep, titleContentColor = T.text, textContentColor = T.text,
        title = { Text(j.optString("title"), fontFamily = T.serif) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Earn.STAGE_LABEL[j.optString("stage")]} · ${j.optString("platform")} ${j.optString("client")}", color = T.dim, fontSize = 12.sp)
                if (j.optString("why").isNotBlank()) Text("Why it fits: ${j.optString("why")}", color = T.mint, fontSize = 12.sp)
                Label("Brief"); Text(j.optString("brief").ifBlank { "—" }, fontSize = 13.sp)
                if (j.optString("link").isNotBlank()) Ghost("Open the listing ↗") { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(j.optString("link"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                Label("Proposal")
                if (j.optString("proposal").isNotBlank()) {
                    Text(j.optString("proposal"), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Ghost("Copy") { copy(c, j.optString("proposal")) }
                        Ghost("Share") { com.nizam.app.feature.nx.Share.text(c, j.optString("proposal"), "Proposal") }
                        if (j.optString("stage") == "lead") Ghost("I sent it") { move("proposed") }
                    }
                }
                Ghost(if (busy == "p") "Writing…" else if (j.optString("proposal").isBlank()) "✍ Write proposal" else "Rewrite") {
                    busy = "p"; scope.launch { runCatching { Earn.writeProposal(container, c, j) }; busy = ""; refresh() } }
                Label("Your notes for the work")
                Field(notes, { notes = it; Earn.work(c).update(id) { o -> o.put("notes", it) } }, "Details, files, client's answers", lines = 2)
                Label("Deliverable")
                if (j.optString("deliverable").isNotBlank()) {
                    Text(j.optString("deliverable").take(1600) + if (j.optString("deliverable").length > 1600) "…" else "", fontSize = 12.sp, fontFamily = T.mono)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Ghost("Copy") { copy(c, j.optString("deliverable")) }
                        Ghost("Save file") { shareFile(c, j.optString("title"), j.optString("deliverable")) }
                        Ghost("PDF") { runCatching { com.nizam.app.feature.nx.Share.file(c, com.nizam.app.feature.nx.Pdf.fromText(c, j.optString("title"), j.optString("deliverable")), "application/pdf") } }
                    }
                }
                Ghost(if (busy == "w") "Working… (a minute)" else if (j.optString("deliverable").isBlank()) "⚙ Do the work now" else "Redo the work") {
                    busy = "w"; scope.launch { runCatching { Earn.doWork(container, c, Earn.work(c).all().first { it.optString("id") == id }) }.onFailure { Feedback.show(it.message ?: "Couldn't finish") }; busy = ""; refresh() } }
                Label("Move to")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("proposed" to "Proposed", "won" to "Won", "working" to "Work on it tonight", "delivered" to "Delivered").forEach { (st, l) ->
                        Pill(l, j.optString("stage") == st) { move(st) } }
                }
                GoldButton("💰 Got paid") {
                    scope.launch {
                        Earn.addIncome(c, container, j.optString("client").ifBlank { j.optString("platform").ifBlank { j.optString("title").take(30) } }, "Gigs",
                            j.optDouble("pay", 0.0).takeIf { it > 0 } ?: 0.0, j.optString("cur", "USD"))
                        Earn.work(c).update(id) { it.put("stage", "paid").put("paid_t", System.currentTimeMillis()) }
                        com.nizam.app.ui.design.Celebrate.now(); refresh(); close()
                    }
                }
                Text("Remove this job", color = T.rose, fontSize = 12.sp, modifier = Modifier.clickable { Earn.work(c).remove(id); refresh(); close() }.padding(vertical = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close", color = T.gold) } })
}

private fun copy(c: android.content.Context, t: String) {
    (c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Atlas Earn", t))
    Feedback.show("Copied")
}

private fun shareFile(c: android.content.Context, title: String, body: String) = runCatching {
    val html = body.contains("<html", true) || body.contains("<!doctype", true)
    val raw = if (html) body.substring(body.indexOf('<').coerceAtLeast(0)).let { it.substring(0, (it.lastIndexOf("</html>", ignoreCase = true).takeIf { i -> i > 0 }?.plus(7)) ?: it.length) } else body
    val f = com.nizam.app.feature.nx.Share.out(c, title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(40).ifBlank { "Atlas" } + if (html) ".html" else ".txt")
    f.writeText(raw)
    com.nizam.app.feature.nx.Share.file(c, f, if (html) "text/html" else "text/plain")
}.onFailure { Feedback.show("Couldn't save the file") }

// ───────────────────────── Shop ─────────────────────────

private val PUBLISH = linkedMapOf("Gumroad" to "https://app.gumroad.com/products/new", "Payhip" to "https://payhip.com/", "Etsy" to "https://www.etsy.com/sell",
    "Adobe Stock" to "https://contributor.stock.adobe.com/", "Amazon KDP" to "https://kdp.amazon.com/", "Play Store" to "https://play.google.com/console",
    "RapidAPI" to "https://rapidapi.com/provider", "Chrome Web Store" to "https://chrome.google.com/webstore/devconsole", "Printify" to "https://printify.com/app/")

@Composable
private fun ShopTab(container: AppContainer, tick: Int, refresh: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var type by remember { mutableStateOf(Earn.PRODUCT_TYPES.first()) }
    var idea by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    val all = remember(tick) { Earn.products(c).all().sortedByDescending { it.optLong("t") } }
    Panel {
        Label("Product factory")
        Text("Atlas builds a complete product: the file itself, the store listing, keywords, price and launch posts. You publish with one tap.", color = T.dim, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Earn.PRODUCT_TYPES.forEach { t -> Pill(t, type == t) { type = t } } }
        Field(idea, { idea = it }, "Your idea (optional)")
        GoldButton(if (busy) "Building… (a minute)" else "📦 Build it", !busy) {
            busy = true; scope.launch { runCatching { Earn.makeProduct(container, c, type, idea) }.onSuccess { if (it == null) Feedback.show("Couldn't build that one, try again") else { idea = ""; Feedback.show("Built: ${it.optString("title")}") } }
                .onFailure { Feedback.show(it.message ?: "Couldn't build") }; busy = false; refresh() }
        }
    }
    val live = all.count { it.optString("stage") == "published" }
    Text("${all.size} products · $live live · ${all.sumOf { it.optInt("sales") }} sales · ${Earn.usd(all.sumOf { it.optDouble("revenue") })} revenue", color = T.dim, fontSize = 12.sp)
    all.forEach { pr ->
        Panel(Modifier.clickable { openId = pr.optString("id") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pr.optString("title"), color = T.text, fontWeight = FontWeight.SemiBold)
                    Text(pr.optString("type") + " · " + pr.optString("tagline"), color = T.dim, fontSize = 12.sp, maxLines = 2)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Earn.usd(pr.optDouble("price")), color = T.gold, fontFamily = T.mono, fontWeight = FontWeight.Bold)
                    val st = pr.optString("stage")
                    Text(if (st == "published") "● LIVE" else "○ ready", color = if (st == "published") T.mint else T.sky, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (pr.optInt("sales") > 0) Text("${pr.optInt("sales")} sales · ${Earn.usd(pr.optDouble("revenue"))}", color = T.mint, fontSize = 12.sp)
        }
    }
    openId?.let { id -> all.firstOrNull { it.optString("id") == id }?.let { pr -> ProductSheet(container, pr, refresh) { openId = null } } }
}

@Composable
private fun ProductSheet(container: AppContainer, pr: JSONObject, refresh: () -> Unit, close: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val id = pr.optString("id")
    var sale by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, containerColor = T.deep, titleContentColor = T.text, textContentColor = T.text,
        title = { Text(pr.optString("title"), fontFamily = T.serif) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pr.optString("tagline"), color = T.gold, fontSize = 13.sp)
                Label("The product"); Text(pr.optString("content").take(1400) + if (pr.optString("content").length > 1400) "…" else "", fontSize = 12.sp, fontFamily = T.mono)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Ghost("Save file") { shareFile(c, pr.optString("title"), pr.optString("content")) }; Ghost("Copy") { copy(c, pr.optString("content")) } }
                Label("Store listing"); Text(pr.optString("listing"), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Ghost("Copy listing") { copy(c, pr.optString("title") + "\n\n" + pr.optString("listing") + "\n\nTags: " + pr.optString("keywords")) } }
                Label("Keywords"); Text(pr.optString("keywords"), color = T.dim, fontSize = 12.sp)
                Label("Launch posts"); Text(pr.optString("launch"), fontSize = 13.sp)
                Row { Ghost("Share a post") { com.nizam.app.feature.nx.Share.text(c, pr.optString("launch"), pr.optString("title")) } }
                Label("Publish kit")
                val plats = pr.optJSONArray("platforms")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().ifEmpty { listOf("Gumroad") }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    plats.forEach { pl -> PUBLISH[pl]?.let { url -> Ghost("$pl ↗") { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } } }
                }
                Text("Copy the listing, save the file, open the store, paste and publish. About a minute.", color = T.dim, fontSize = 12.sp)
                if (pr.optString("stage") != "published") GoldButton("● I published it") {
                    Earn.products(c).update(id) { it.put("stage", "published").put("pub_t", System.currentTimeMillis()) }; Earn.log(c, "🚀", "Published: ${pr.optString("title").take(50)}"); refresh(); close() }
                Label("Record a sale")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(sale, { sale = it.filter { ch -> ch.isDigit() || ch == '.' } }, "Amount in USD", Modifier.weight(1f)); Spacer(Modifier.width(8.dp))
                    GoldButton("Add", sale.toDoubleOrNull() != null) {
                        val a = sale.toDouble()
                        scope.launch {
                            Earn.addIncome(c, container, pr.optString("title").take(40), "Products", a, "USD")
                            Earn.products(c).update(id) { it.put("sales", it.optInt("sales") + 1).put("revenue", it.optDouble("revenue") + a) }
                            sale = ""; com.nizam.app.ui.design.Celebrate.now(); refresh()
                        }
                    }
                }
                Text("Remove product", color = T.rose, fontSize = 12.sp, modifier = Modifier.clickable { Earn.products(c).remove(id); refresh(); close() }.padding(vertical = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close", color = T.gold) } })
}

// ───────────────────────── Money ─────────────────────────

@Composable
private fun MoneyTab(container: AppContainer, tick: Int, refresh: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val all = remember(tick) { Earn.income(c).all().sortedByDescending { it.optLong("t") } }
    var src by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }; var cur by remember { mutableStateOf("PKR") }
    var stream by remember { mutableStateOf("Gigs") }; var pending by remember { mutableStateOf(false) }
    var sms by remember { mutableStateOf(Earn.radarSms(c)) }; var notif by remember { mutableStateOf(Earn.radarNotif(c)) }; var toMoney by remember { mutableStateOf(Earn.toMoney(c)) }
    var token by remember { mutableStateOf(Earn.gumroad(c)) }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> sms = ok; Earn.setRadarSms(c, ok); if (!ok) Feedback.show("SMS reading stays off") }
    val unsorted = all.filter { it.optString("status") == "unsorted" }
    if (unsorted.isNotEmpty()) Panel {
        Label("To sort (${unsorted.size})")
        Text("Credits Atlas noticed but can't be sure are earnings (it never counts salary).", color = T.dim, fontSize = 12.sp)
        unsorted.take(10).forEach { u ->
            Text("${Earn.fmt(u.optDouble("pkr"))} · ${u.optString("source")}", color = T.text, fontFamily = T.mono, fontSize = 13.sp)
            Text(u.optString("note").take(120), color = T.dim, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Gigs", "Clients", "Products", "Affiliate", "Other").forEach { st -> Ghost(st) {
                    Earn.income(c).update(u.optString("id")) { it.put("status", "credited").put("stream", st) }
                    if (Earn.toMoney(c)) scope.launch { runCatching { container.financeRepository.add(u.optDouble("pkr"), "INCOME", "Earnings", u.optString("source")) } }
                    refresh() } }
                Ghost("Not earnings") { Earn.p(c).edit().putStringSet("sorted", Earn.p(c).getStringSet("sorted", emptySet())!! + u.optString("key")).apply()
                    Earn.income(c).remove(u.optString("id")); refresh() }
            }
        }
    }
    Panel {
        Label("Add money in")
        Field(src, { src = it }, "From (client, platform, product)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(amt, { amt = it.filter { ch -> ch.isDigit() || ch == '.' } }, "Amount", Modifier.weight(1f)); Spacer(Modifier.width(8.dp))
            Pill("PKR", cur == "PKR") { cur = "PKR" }; Spacer(Modifier.width(4.dp)); Pill("USD", cur == "USD") { cur = "USD" }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Earn.STREAMS.forEach { s -> Pill(s, stream == s) { stream = s } } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Pill("Credited", !pending) { pending = false }; Pill("Expected", pending) { pending = true } }
        if (cur == "USD" && amt.toDoubleOrNull() != null) Text("≈ ${Earn.fmt(Earn.pkr(c, amt.toDouble(), "USD"))} at today's rate", color = T.dim, fontSize = 12.sp)
        GoldButton("Add", src.isNotBlank() && amt.toDoubleOrNull() != null) {
            scope.launch { Earn.addIncome(c, container, src.trim(), stream, amt.toDouble(), cur, if (pending) "pending" else "credited"); src = ""; amt = ""; refresh() }
        }
    }
    val pend = all.filter { it.optString("status") == "pending" }
    if (pend.isNotEmpty()) Panel {
        Label("Expected")
        pend.forEach { e -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${e.optString("source")} · ${Earn.fmt(e.optDouble("pkr"))}", color = T.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Ghost("Received") { Earn.income(c).update(e.optString("id")) { it.put("status", "credited").put("date", LocalDate.now().toString()) }
                if (Earn.toMoney(c)) scope.launch { runCatching { container.financeRepository.add(e.optDouble("pkr"), "INCOME", "Earnings", e.optString("source")) } }
                Earn.log(c, "💰", "Credited ${Earn.fmt(e.optDouble("pkr"))} from ${e.optString("source")}"); refresh() }
        } }
    }
    Panel {
        Label("Credited")
        val cred = all.filter { it.optString("status") == "credited" }
        if (cred.isEmpty()) Text("Nothing credited yet.", color = T.dim, fontSize = 13.sp)
        cred.take(40).forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.optString("source"), color = T.text, fontSize = 13.sp, maxLines = 1)
                    Text("${e.optString("stream")} · ${com.nizam.app.core.prettyDate(e.optString("date"))}${if (e.optBoolean("auto")) " · auto" else ""}", color = T.dim, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("+" + Earn.fmt(e.optDouble("pkr")), color = T.mint, fontFamily = T.mono, fontSize = 13.sp)
                    if (e.optString("cur") == "USD") Text(Earn.usd(e.optDouble("amount")), color = T.dim, fontFamily = T.mono, fontSize = 11.sp)
                }
            }
        }
    }
    Panel {
        Label("Money radar")
        Toggle("Read credit SMS", "Bank, JazzCash, Easypaisa credits. Known platforms count straight away; anything else waits in To sort.", sms) { v ->
            if (v && ContextCompat.checkSelfPermission(c, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) runCatching { askSms.launch(Manifest.permission.READ_SMS) }
            else { sms = v; Earn.setRadarSms(c, v) } }
        val listening = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName) }
        Toggle("Read payment notifications", if (listening) "Payoneer, Fiverr, Upwork, Gumroad, Etsy, Wise… sales and payouts" else "Needs notification access for Atlas", notif) { v -> notif = v; Earn.setRadarNotif(c, v) }
        if (!listening) Ghost("Allow notification access ↗") { runCatching { c.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        Toggle("Also add to Money", "Every credit shows in your Money module as income", toMoney) { v -> toMoney = v; Earn.setToMoney(c, v) }
        Label("Gumroad sales (optional)")
        Field(token, { token = it; Earn.setGumroad(c, it) }, "Gumroad access token")
        Text("Gumroad → Settings → Advanced → Applications → generate a token. Atlas only reads sales.", color = T.dim, fontSize = 11.sp)
        if (token.isNotBlank()) Ghost("Sync sales now") { scope.launch { runCatching { Earn.syncGumroad(c, container) }.onSuccess { Feedback.show("$it new sales") }.onFailure { Feedback.show("Couldn't reach Gumroad") }; refresh() } }
        Text("Rate: $1 = Rs ${"%.2f".format(Earn.rate(c))} (updates every few hours)", color = T.dim, fontSize = 11.sp, fontFamily = T.mono)
    }
}

// ───────────────────────── Grow ─────────────────────────

@Composable
private fun GrowTab(tick: Int, refresh: () -> Unit) {
    val c = LocalContext.current
    val s = remember(tick) { Earn.stats(c) }
    val f = remember(tick) { Earn.fund(c).all().sortedBy { it.optString("date") } }
    var v by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("%.0f".format(Earn.goal(c))) }
    val sweep = Earn.p(c).getInt("sweep", 30)
    Panel {
        Label("Monthly goal")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(goal, { goal = it.filter(Char::isDigit) }, "Goal in Rs", Modifier.weight(1f)); Spacer(Modifier.width(8.dp))
            GoldButton("Set", goal.toDoubleOrNull() != null) { Earn.setGoal(c, goal.toDouble()); Feedback.show("Goal set"); refresh() }
        }
        val days = LocalDate.now().lengthOfMonth() - LocalDate.now().dayOfMonth + 1
        val need = (Earn.goal(c) - s.month).coerceAtLeast(0.0)
        Text(if (need <= 0) "Goal reached. Raise it?" else "${Earn.fmt(need)} to go · ${Earn.fmt(need / days)} a day for $days days", color = T.mint, fontSize = 13.sp)
    }
    Panel {
        Label("Your money, earning too")
        Text("Put a share of what you earn into a savings or Shariah-compliant money market fund so it earns while you sleep. Atlas tracks it; it never moves money.",
            color = T.dim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10, 20, 30, 50).forEach { pct -> Pill("$pct%", sweep == pct) { Earn.p(c).edit().putInt("sweep", pct).apply(); refresh() } }
        }
        Text("Sweep this month: ${Earn.fmt(s.month * sweep / 100)} of ${Earn.fmt(s.month)} earned", color = T.gold, fontFamily = T.mono, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(v, { v = it.filter { ch -> ch.isDigit() || ch == '.' } }, "Fund value today (Rs)", Modifier.weight(1f)); Spacer(Modifier.width(8.dp))
            GoldButton("Log", v.toDoubleOrNull() != null) {
                Earn.fund(c).all().filter { it.optString("date") == LocalDate.now().toString() }.forEach { Earn.fund(c).remove(it.optString("id")) }
                Earn.fund(c).add(JSONObject().put("date", LocalDate.now().toString()).put("value", v.toDouble())); v = ""; refresh() }
        }
        if (f.size >= 2) {
            val gain = f.last().optDouble("value") - f.first().optDouble("value")
            Text("${if (gain >= 0) "+" else ""}${Earn.fmt(gain)} since ${com.nizam.app.core.prettyDate(f.first().optString("date"))} (includes what you added)", color = T.mint, fontSize = 13.sp)
            com.nizam.app.ui.design.TrendLine(f.map { it.optDouble("value") }, Modifier.fillMaxWidth(), height = 90.dp, accent = T.mint, format = { Earn.fmt(it) })
        } else if (f.size == 1) Text("Fund: ${Earn.fmt(f.first().optDouble("value"))}. Log it again in a few days to see it grow.", color = T.dim, fontSize = 12.sp)
        Text("Not investment advice. Check a fund's own documents and fees before investing.", color = T.dim, fontSize = 11.sp)
    }
}

// ───────────────────────── Coach ─────────────────────────

@Composable
private fun CoachTab(container: AppContainer, tick: Int, refresh: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var busy by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf(Earn.skills(c)) }
    val text = Earn.p(c).getString("coach", "").orEmpty()
    Panel {
        Label("Your earning coach")
        if (text.isBlank()) Text("Every Sunday night Atlas reviews your numbers and tells you what to double down on. Or ask now.", color = T.dim, fontSize = 13.sp)
        else { Text("Updated ${ago(Earn.p(c).getLong("coach_t", 0))} ago", color = T.dim, fontSize = 11.sp); com.nizam.app.feature.nx.Md(text) }
        GoldButton(if (busy) "Thinking…" else "🧭 Coach me now", !busy) { busy = true; scope.launch { runCatching { Earn.coach(container, c) }.onFailure { Feedback.show(it.message ?: "Couldn't coach now") }; busy = false; refresh() } }
    }
    Panel {
        Label("What you can sell")
        Field(skills, { skills = it; Earn.setSkills(c, it) }, "Your skills (Atlas hunts and builds around these)", lines = 3)
    }
}

// ───────────────────────── Setup ─────────────────────────

private val ACCOUNTS = listOf(
    Triple("Payoneer", "Receive USD from platforms and clients; withdraw to your bank", "https://www.payoneer.com/"),
    Triple("Gumroad", "Sell templates and digital products", "https://gumroad.com/"),
    Triple("Payhip", "Another digital shop, lower fees on some plans", "https://payhip.com/"),
    Triple("Fiverr", "Gigs: translation, dashboards, data", "https://www.fiverr.com/"),
    Triple("Upwork", "Bigger client jobs", "https://www.upwork.com/"),
    Triple("Adobe Stock", "AI image sets (label them as AI)", "https://contributor.stock.adobe.com/"),
    Triple("Amazon KDP", "Planners and books (disclose AI content)", "https://kdp.amazon.com/"),
    Triple("Google Play Console", "Publish apps Atlas builds", "https://play.google.com/console"),
    Triple("RapidAPI", "Sell API subscriptions", "https://rapidapi.com/provider"),
    Triple("Daraz affiliate", "Commission on products you recommend", "https://www.daraz.pk/"),
    Triple("Printify", "Print-on-demand, no stock", "https://printify.com/"))

@Composable
private fun SetupTab(tick: Int, refresh: () -> Unit) {
    val c = LocalContext.current
    var night by remember { mutableStateOf(Earn.nightOn(c)) }
    val done = remember(tick) { Earn.p(c).getStringSet("accounts", emptySet())!!.toSet() }
    Panel {
        Label("Overnight engine")
        Toggle("Work while I sleep", "1–5am: hunt gigs, write proposals, finish accepted work, build a product. Sunday: coaching. 7am: your report.", night) { v -> night = v; Earn.setNight(c, v) }
    }
    Panel {
        Label("Connect once (${done.size}/${ACCOUNTS.size})")
        ACCOUNTS.forEach { (n, d, url) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (n in done) T.mint else T.panel).border(1.dp, T.line, CircleShape)
                    .clickable { val now = Earn.p(c).getStringSet("accounts", emptySet())!!.toMutableSet().apply { if (n in this) remove(n) else add(n) }
                        Earn.p(c).edit().putStringSet("accounts", now).apply(); refresh() }, contentAlignment = Alignment.Center) { if (n in done) Text("✓", color = T.ink, fontSize = 12.sp) }
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(n, color = T.text, fontWeight = FontWeight.Medium); Text(d, color = T.dim, fontSize = 11.sp) }
                Ghost("Open ↗") { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
        }
    }
    Panel {
        Label("Atlas's promises")
        listOf("Nothing is sent, posted, published or paid without your tap.", "No bots on Fiverr, Upwork, survey or ad sites: that gets accounts banned.",
            "No trading with your money.", "Salary and personal credits never count as earnings; unclear ones wait for you.",
            "Your passwords are never asked for. Credits come from messages you allow.").forEach { Text("◆ $it", color = T.text, fontSize = 13.sp) }
    }
}

val EarnSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    com.nizam.app.feature.nx.ScreenSpec("earn", "Atlas Earn", "Your earning platform: gigs, products, apps and money in PKR and USD, worked on while you sleep",
        androidx.compose.material.icons.Icons.Outlined.Paid, com.nizam.app.ui.design.Hub.Money,
        "earn money income freelance gigs products sell passive side income dollars payoneer fiverr upwork gumroad earnings") { container, open -> EarnScreen(container, open) })
