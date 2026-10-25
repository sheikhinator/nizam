package com.nizam.app.feature.hadith

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.next.JsonStore
import org.json.JSONObject
import java.time.LocalDate

/**
 * Hadith, overhauled: save the ones that move you, one of your saved hadith each day, and share
 * the exact text with its reference. Everything shown is copied from the source, never written by AI.
 */
object HadithSaved {
    private fun store(c: Context) = JsonStore(c, "hadith_saved")
    fun all(c: Context) = store(c).all().sortedByDescending { it.optLong("t") }
    fun isSaved(c: Context, h: Hadith) = store(c).all().any { it.optString("ref") == h.reference && it.optString("en") == h.textEn }
    fun toggle(c: Context, h: Hadith) {
        val s = store(c)
        val hit = s.all().firstOrNull { it.optString("ref") == h.reference && it.optString("en") == h.textEn }
        if (hit != null) s.remove(hit.optString("id"))
        else s.add(JSONObject().put("en", h.textEn).put("ar", h.textAr).put("narrator", h.narrator).put("ref", h.reference).put("grade", h.grade).put("t", System.currentTimeMillis()))
    }
    fun remove(c: Context, id: String) = store(c).remove(id)
    fun ofTheDay(c: Context): JSONObject? = all(c).sortedBy { it.optLong("t") }.let { l -> if (l.isEmpty()) null else l[(LocalDate.now().toEpochDay() % l.size).toInt()] }
    fun share(c: Context, en: String, ref: String, grade: String) = runCatching {
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "$en\n\n— $ref" + if (grade.isNotBlank()) " ($grade)" else ""), "Share hadith").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Save and share buttons under each hadith in the reader. */
@Composable
fun HadithActions(h: Hadith) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val saved = remember(tick, h.id) { HadithSaved.isSaved(c, h) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { HadithSaved.toggle(c, h); tick++ }) { Text(if (saved) "★ Saved" else "☆ Save") }
        TextButton(onClick = { HadithSaved.share(c, h.textEn, h.reference, h.grade) }) { Text("Share") }
    }
}

/** On the Hadith home: today's hadith from your saved ones, and the saved list. */
@Composable
fun HadithSavedPane() {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val all = remember(tick) { HadithSaved.all(c) }
    val today = remember(tick) { HadithSaved.ofTheDay(c) }
    var showAll by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (today == null) {
            Text("Tap ☆ Save on any hadith you read. One of your saved hadith will greet you here each day.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
        Text("Today, from your saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (today.optString("ar").isNotBlank()) Text(today.optString("ar"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Text(today.optString("en"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        Text(today.optString("ref") + today.optString("grade").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = { HadithSaved.share(c, today.optString("en"), today.optString("ref"), today.optString("grade")) }) { Text("Share") }
            TextButton(onClick = { showAll = 1 - showAll }) { Text(if (showAll == 1) "Hide saved" else "All saved (${all.size})") }
        }
        if (showAll == 1) all.forEach { o ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(o.optString("en"), style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                Row {
                    Text(o.optString("ref"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = { HadithSaved.remove(c, o.optString("id")); tick++ }) { Text("Remove") }
                }
            }
        }
        }
        Text(" ", fontWeight = FontWeight.Normal)
    }
}
