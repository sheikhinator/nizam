package com.nizam.app.feature.next

import android.content.Context
import android.os.CountDownTimer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nizam.app.core.Heatmap
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import com.nizam.app.ui.design.pressable
import org.json.JSONObject
import java.time.LocalDate

// ── Duas ─────────────────────────────────────────────────────────────────────────────────────────

/** A library of everyday duas by occasion, with transliteration and meaning, and favourites. */
object Duas {
    data class Dua(val title: String, val arabic: String, val translit: String, val meaning: String, val group: String)
    val ALL = listOf(
        Dua("Before eating", "بِسْمِ اللَّهِ", "Bismillah", "In the name of Allah.", "Daily"),
        Dua("After eating", "الْحَمْدُ لِلَّهِ الَّذِي أَطْعَمَنَا وَسَقَانَا", "Alhamdulillahilladhi at'amana wa saqana", "All praise to Allah who fed us and gave us drink.", "Daily"),
        Dua("Leaving home", "بِسْمِ اللَّهِ تَوَكَّلْتُ عَلَى اللَّهِ", "Bismillahi tawakkaltu 'alallah", "In the name of Allah, I place my trust in Allah.", "Daily"),
        Dua("Entering home", "بِسْمِ اللَّهِ وَلَجْنَا", "Bismillahi walajna", "In the name of Allah we enter.", "Daily"),
        Dua("Before sleeping", "بِاسْمِكَ اللَّهُمَّ أَمُوتُ وَأَحْيَا", "Bismika Allahumma amutu wa ahya", "In Your name O Allah I die and I live.", "Daily"),
        Dua("Waking up", "الْحَمْدُ لِلَّهِ الَّذِي أَحْيَانَا", "Alhamdulillahilladhi ahyana", "All praise to Allah who gave us life after death.", "Daily"),
        Dua("Entering the toilet", "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْخُبُثِ وَالْخَبَائِثِ", "Allahumma inni a'udhu bika minal khubthi wal khaba'ith", "O Allah, I seek refuge in You from all evil.", "Daily"),
        Dua("Before wudu", "بِسْمِ اللَّهِ", "Bismillah", "In the name of Allah.", "Daily"),
        Dua("Travel", "سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا", "Subhanalladhi sakhkhara lana hadha", "Glory to Him who subjected this to us.", "Travel"),
        Dua("Riding a vehicle", "بِسْمِ اللَّهِ، الْحَمْدُ لِلَّهِ", "Bismillah, Alhamdulillah", "In the name of Allah; all praise to Allah.", "Travel"),
        Dua("Distress", "لَا إِلَهَ إِلَّا أَنْتَ سُبْحَانَكَ إِنِّي كُنْتُ مِنَ الظَّالِمِينَ", "La ilaha illa anta subhanaka inni kuntu minaz zalimin", "There is no god but You; glory to You; I was among the wrongdoers.", "Hardship"),
        Dua("Anxiety", "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْهَمِّ وَالْحَزَنِ", "Allahumma inni a'udhu bika minal hammi wal hazan", "O Allah, I seek refuge in You from anxiety and grief.", "Hardship"),
        Dua("Forgiveness", "أَسْتَغْفِرُ اللَّهَ", "Astaghfirullah", "I seek forgiveness from Allah.", "Hardship"),
        Dua("Rain", "اللَّهُمَّ صَيِّبًا نَافِعًا", "Allahumma sayyiban nafi'a", "O Allah, beneficial rain.", "Nature"),
        Dua("Seeing something you like", "مَا شَاءَ اللَّهُ", "Masha'Allah", "As Allah has willed.", "Daily"),
        Dua("Entering the market", "لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ", "La ilaha illallah wahdahu la sharika lah", "None has the right to be worshipped but Allah alone.", "Daily")
    )
    val GROUPS = ALL.map { it.group }.distinct()
    private fun p(c: Context) = c.getSharedPreferences("duas_fav", 0)
    fun fav(c: Context) = p(c).getStringSet("f", emptySet()).orEmpty()
    fun toggle(c: Context, t: String) = p(c).edit().putStringSet("f", fav(c).toMutableSet().apply { if (!add(t)) remove(t) }).apply()
}

@Composable
fun DuasScreen() {
    val ctx = LocalContext.current
    var group by remember { mutableStateOf("Favourites") }
    var fav by remember { mutableStateOf(Duas.fav(ctx)) }
    val groups = listOf("Favourites") + Duas.GROUPS
    val shown = if (group == "Favourites") Duas.ALL.filter { it.title in fav } else Duas.ALL.filter { it.group == group }
    val lp = remember { ctx.getSharedPreferences("duas_learned", 0) }
    var learned by remember { mutableStateOf(lp.getStringSet("set", emptySet())!!.toSet()) }
    var hideArabic by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(setOf<String>()) }
    AtlasScreen(title = "Duas", subtitle = "Everyday supplications, with meaning · ${learned.size} of ${Duas.ALL.size} learned by heart", hub = Hub.Deen) {
        item("mem") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Capsule(if (hideArabic) "✓ Memorise mode" else "Memorise mode", hideArabic) { hideArabic = !hideArabic; revealed = emptySet() }
            }
        }
        item("g") {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(groups) { g -> Capsule(g, g == group) { group = g } }
            }
        }
        if (shown.isEmpty()) item("e") {
            EmptyState(EmptySpec(Icons.Outlined.RadioButtonUnchecked, if (group == "Favourites") "No favourites yet" else "Empty", "Tap the heart on any dua to keep it here."))
        }
        shown.forEach { d ->
            item(d.title) {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (d.title in fav) "♥" else "♡", color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.pressable { Duas.toggle(ctx, d.title); fav = Duas.fav(ctx) }.padding(Space.xs))
                    }
                    val masked = hideArabic && d.title !in revealed
                    Text(if (masked) d.arabic.split(" ").firstOrNull().orEmpty() + " … (tap to check yourself)" else d.arabic, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().pressable { if (hideArabic) revealed = if (d.title in revealed) revealed - d.title else revealed + d.title }.padding(vertical = Space.s))
                    if (!masked) Text(d.translit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(d.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.s)) {
                        val on = d.title in learned
                        Capsule(if (on) "✓ Learned" else "Mark learned", on) {
                            learned = if (on) learned - d.title else learned + d.title
                            lp.edit().putStringSet("set", learned).apply()
                            if (!on) com.nizam.app.ui.design.Celebrate.now()
                        }
                        Capsule("Share", false) {
                            runCatching { ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, "${d.title}\n\n${d.arabic}\n\n${d.translit}\n\n${d.meaning}"), "Share dua").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            }
        }
    }
}

// ── Habits ───────────────────────────────────────────────────────────────────────────────────────

/** Simple daily habits with a streak and a year heatmap. Separate from the fuller Tasks habits. */
@Composable
fun HabitsScreen() {
    val ctx = LocalContext.current
    val store = remember { JsonStore(ctx, "habits2") }
    var list by remember { mutableStateOf(store.all()) }
    var name by remember { mutableStateOf("") }
    val today = LocalDate.now().toString()
    fun reload() { list = store.all() }
    fun done(o: JSONObject) = o.optJSONArray("days")?.let { a -> (0 until a.length()).any { a.getString(it) == today } } ?: false
    fun streak(o: JSONObject): Int {
        val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        var n = 0; var d = LocalDate.now()
        while (days.contains(d.toString())) { n++; d = d.minusDays(1) }
        return n
    }
    AtlasScreen(title = "Habits", subtitle = "One tap a day; watch the streak grow", hub = Hub.Work) {
        if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.CheckCircle, "No habits yet", "Add a small daily habit below — read, walk, dhikr.")) }
        list.forEach { o ->
            val id = o.optString("id"); val isDone = done(o)
            item(id) {
                ListRow(o.optString("name"), supporting = "${streak(o)}-day streak",
                    icon = if (isDone) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    tint = if (isDone) Hub.Deen.tint() else MaterialTheme.colorScheme.outline,
                    trailing = {
                        Text("Delete", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(Space.s).pressable { store.remove(id); reload() })
                    }) {
                    store.update(id) { x ->
                        val a = x.optJSONArray("days") ?: org.json.JSONArray()
                        val has = (0 until a.length()).any { a.getString(it) == today }
                        x.put("days", if (has) org.json.JSONArray((0 until a.length()).map { a.getString(it) }.filter { it != today }) else a.put(today))
                    }; reload()
                    store.all().firstOrNull { it.optString("id") == id }?.let { if (!isDone && streak(it) in setOf(7, 30, 100, 365)) com.nizam.app.ui.design.Celebrate.now() }
                }
            }
            item("hm$id") {
                val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                val vals = (0 until 91).map { i -> if (LocalDate.now().minusDays((90 - i).toLong()).toString() in days) 1.0 else 0.0 }
                Box(Modifier.padding(horizontal = Space.page, vertical = Space.xs)) { Heatmap(vals, Hub.Deen.tint(), columns = 13) }
            }
        }
        item("add") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), placeholder = { Text("New habit") }, singleLine = true)
                    Button(onClick = { if (name.isNotBlank()) { store.add(JSONObject().put("name", name.trim()).put("days", org.json.JSONArray())); name = ""; reload() } }) { Text("Add") }
                }
            }
        }
    }
}

// ── Timer & Pomodoro ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TimerScreen() {
    var minutes by remember { mutableStateOf(25) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = com.nizam.app.ui.design.FocusTimer.endsAt > 0L
    androidx.compose.runtime.LaunchedEffect(running) { com.nizam.app.ui.design.guard { while (com.nizam.app.ui.design.FocusTimer.endsAt > 0L) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } } }
    val remaining = (com.nizam.app.ui.design.FocusTimer.endsAt - now).coerceAtLeast(0)
    AtlasScreen(title = "Timer", subtitle = "Focus blocks. The countdown follows you in the tab bar.", hub = Hub.Health) {
        item("clock") {
            Box(Modifier.fillMaxWidth().padding(Space.xl), contentAlignment = Alignment.Center) {
                Text(if (running) "%02d:%02d".format(remaining / 60_000, (remaining % 60_000) / 1000) else "$minutes:00",
                    style = MaterialTheme.typography.displayLarge)
            }
        }
        item("presets") {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(listOf(5, 10, 15, 25, 45, 60)) { m -> Capsule("$m min", minutes == m && !running) { minutes = m } }
            }
        }
        item("btns") {
            Row(Modifier.padding(horizontal = Space.page, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (!running) Button(onClick = { com.nizam.app.ui.design.FocusTimer.start(minutes) }) { Text("Start $minutes min") }
                else OutlinedButton(onClick = { com.nizam.app.ui.design.FocusTimer.stop() }) { Text("Stop") }
            }
        }
        item("note") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Text("Pomodoro: 25 minutes of focus, then a 5-minute break. Four rounds, then a longer rest.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
