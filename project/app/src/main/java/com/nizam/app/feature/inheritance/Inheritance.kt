package com.nizam.app.feature.inheritance

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.atmosphere.AtmosphereBackdrop
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.luminex
import org.json.JSONArray
import org.json.JSONObject

data class Bequest(
    val id: String,
    val kind: String,
    val text: String,
    val ts: Long
)

private const val PREFS = "inheritance"
private const val KEY = "bequests"

private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

fun loadBequests(c: Context): List<Bequest> = runCatching {
    val arr = JSONArray(prefs(c).getString(KEY, "[]"))
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        Bequest(
            id = o.optString("id"),
            kind = o.optString("kind", "LESSON"),
            text = o.optString("text"),
            ts = o.optLong("ts", 0L)
        )
    }.sortedByDescending { it.ts }
}.getOrDefault(emptyList())

private fun saveBequests(c: Context, list: List<Bequest>) {
    val arr = JSONArray()
    list.forEach { b ->
        arr.put(JSONObject().put("id", b.id).put("kind", b.kind).put("text", b.text).put("ts", b.ts))
    }
    prefs(c).edit().putString(KEY, arr.toString()).apply()
}

private fun deleteBequest(c: Context, id: String) = saveBequests(c, loadBequests(c).filter { it.id != id })

private fun addBequest(c: Context, kind: String, text: String) {
    if (text.isBlank()) return
    val now = System.currentTimeMillis()
    saveBequests(c, loadBequests(c) + Bequest(now.toString(), kind, text.trim(), now))
}

/**
 * Inheritance - the part of you someone else could carry.
 *
 * Not a will and not a vault. Values you want to be true of your life, lessons that cost
 * you something, and letters to people who will read them later. It stays on the phone.
 */
@Composable
fun InheritanceScreen() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var kind by remember { mutableStateOf("LESSON") }
    var draft by remember { mutableStateOf("") }
    val bequests = remember(version) { loadBequests(context) }

    AtmosphereBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Inheritance", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "What someone else could carry when you are not in the room.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("VALUE", "LESSON", "LETTER").forEach { option ->
                    val on = kind == option
                    Text(
                        option.lowercase().replaceFirstChar { it.uppercase() },
                        color = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) Ascend.reactorEmerald else MaterialTheme.colorScheme.surface)
                            .clickable { kind = option }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .luminex(RoundedCornerShape(22.dp), Ascend.reactorEmerald, 0.7f)
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    placeholder = {
                        Text(
                            when (kind) {
                                "VALUE" -> "What do you want to be true of how you lived?"
                                "LETTER" -> "Write to someone specific. They never have to read it."
                                else -> "What did it cost you to learn?"
                            }
                        )
                    },
                    label = { Text(when (kind) { "VALUE" -> "A value"; "LETTER" -> "A letter"; else -> "A lesson" }) }
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        addBequest(context, kind, draft)
                        draft = ""
                        version++
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Keep it")
                }
            }

            if (bequests.isEmpty()) {
                Text(
                    "Nothing here yet. Start with one sentence you would want read out loud.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            bequests.forEach { item ->
                val accent = when (item.kind) {
                    "VALUE" -> Ascend.reactorIce
                    "LETTER" -> Ascend.reactorRose
                    else -> Ascend.reactorEmerald
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .luminex(RoundedCornerShape(20.dp), accent, 0.6f)
                        .padding(18.dp)
                ) {
                    Text(
                        item.kind,
                        color = accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                    TextButton(onClick = { deleteBequest(context, item.id); version++ }) { Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            }

            Text(
                "Kept on this phone and nowhere else. Inheritance is not a will; it is the part of you that could survive being remembered.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 30.dp)
            )
        }
    }
}
