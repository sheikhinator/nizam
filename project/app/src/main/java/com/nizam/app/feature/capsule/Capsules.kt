package com.nizam.app.feature.capsule

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class Capsule(
    val id: String,
    val text: String,
    val createdAt: Long,
    val sealedUntil: Long,
    val opened: Boolean
)

private const val KEY = "capsules"

private fun prefs(c: Context) = c.getSharedPreferences("capsules", Context.MODE_PRIVATE)

fun loadCapsules(c: Context): List<Capsule> = runCatching {
    val arr = JSONArray(prefs(c).getString(KEY, "[]"))
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        Capsule(
            id = o.optString("id"),
            text = o.optString("text"),
            createdAt = o.optLong("createdAt"),
            sealedUntil = o.optLong("sealedUntil"),
            opened = o.optBoolean("opened")
        )
    }.sortedBy { it.sealedUntil }
}.getOrDefault(emptyList())

private fun save(c: Context, list: List<Capsule>) {
    val arr = JSONArray()
    list.forEach { cap ->
        arr.put(
            JSONObject()
                .put("id", cap.id)
                .put("text", cap.text)
                .put("createdAt", cap.createdAt)
                .put("sealedUntil", cap.sealedUntil)
                .put("opened", cap.opened)
        )
    }
    prefs(c).edit().putString(KEY, arr.toString()).apply()
}

fun sealCapsule(c: Context, text: String, days: Int): Capsule {
    val now = System.currentTimeMillis()
    val cap = Capsule(
        id = now.toString(),
        text = text.trim(),
        createdAt = now,
        sealedUntil = now + days.coerceAtLeast(1) * 86_400_000L,
        opened = false
    )
    save(c, loadCapsules(c) + cap)
    return cap
}

fun deleteCapsule(c: Context, id: String) { save(c, loadCapsules(c).filter { it.id != id }) }

fun openCapsule(c: Context, id: String) {
    save(c, loadCapsules(c).map { if (it.id == id) it.copy(opened = true) else it })
}

fun daysLeft(cap: Capsule): Long {
    val today = LocalDate.now()
    val due = Instant.ofEpochMilli(cap.sealedUntil).atZone(ZoneId.systemDefault()).toLocalDate()
    return ChronoUnit.DAYS.between(today, due).coerceAtLeast(0L)
}

/**
 * Time capsules - letters to a version of you who does not exist yet.
 *
 * Sealed locally with a date. Atlas cannot read them back early, and they are never
 * uploaded. The waiting is the feature.
 */
@Composable
fun CapsulesScreen() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var writing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(30) }
    var revealed by remember { mutableStateOf<String?>(null) }
    val capsules = remember(version) { loadCapsules(context) }
    val ready = capsules.filter { it.sealedUntil <= System.currentTimeMillis() }.size

    AtmosphereBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Time Capsules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (capsules.isEmpty()) "Write to someone who has not met this version of you yet."
                else capsules.size.toString() + " sealed, " + ready.toString() + " ready to open.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            if (writing) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .luminex(RoundedCornerShape(22.dp), Ascend.reactorIce, 0.7f)
                        .padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        placeholder = { Text("Tell them what today was actually like. No performance.") },
                        label = { Text("For future you") }
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 30, 90, 365).forEach { d ->
                            val on = days == d
                            Text(
                                if (d == 365) "1 year" else d.toString() + " days",
                                color = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (on) Ascend.reactorIce else MaterialTheme.colorScheme.surface)
                                    .clickable { days = d }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (draft.isNotBlank()) {
                                sealCapsule(context, draft, days)
                                draft = ""
                                writing = false
                                version++
                            }
                        }) { Text("Seal it") }
                        TextButton(onClick = { writing = false }) { Text("Not now") }
                    }
                }
            } else {
                Button(onClick = { writing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Write a capsule")
                }
            }

            capsules.forEach { cap ->
                val isReady = cap.sealedUntil <= System.currentTimeMillis()
                val isRevealed = revealed == cap.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .luminex(RoundedCornerShape(20.dp), if (isReady) Ascend.reactorEmerald else Ascend.reactorViolet, 0.6f)
                        .clickable {
                            if (isReady) {
                                revealed = if (isRevealed) null else cap.id
                                if (!cap.opened) { openCapsule(context, cap.id); version++ }
                            }
                        }
                        .padding(18.dp)
                ) {
                    Text(
                        if (isReady) "READY" else "SEALED UNTIL " + Instant.ofEpochMilli(cap.sealedUntil).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                        color = if (isReady) Ascend.reactorEmerald else Ascend.reactorViolet,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            isRevealed -> cap.text
                            isReady -> "Tap to open what you wrote " + daysAgo(cap.createdAt) + " days ago."
                            else -> daysLeft(cap).toString() + " days to go. You wrote this " + daysAgo(cap.createdAt) + " days ago."
                        },
                        color = if (isRevealed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                    if (isRevealed || !isReady) TextButton(onClick = { deleteCapsule(context, cap.id); if (revealed == cap.id) revealed = null; version++ }) {
                        Text(if (isReady) "Delete" else "Break the seal and delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            }

            Text(
                "Sealed on this phone. Atlas will not open them early, and nothing is sent anywhere.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 30.dp)
            )
        }
    }
}

private fun daysAgo(ts: Long): Long =
    ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate(),
        LocalDate.now()
    ).coerceAtLeast(0L)
