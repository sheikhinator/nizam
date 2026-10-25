package com.nizam.app.feature.aletheia

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

data class Belief(
    val id: String,
    val text: String,
    val confidence: Int,
    val status: String
)

private const val PREFS = "aletheia"
private const val KEY = "beliefs"

private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

fun loadBeliefs(c: Context): List<Belief> = runCatching {
    val arr = JSONArray(prefs(c).getString(KEY, "[]"))
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        Belief(
            id = o.optString("id"),
            text = o.optString("text"),
            confidence = o.optInt("confidence", 50).coerceIn(0, 100),
            status = o.optString("status", "HOLDING")
        )
    }
}.getOrDefault(emptyList())

private fun saveBeliefs(c: Context, list: List<Belief>) {
    val arr = JSONArray()
    list.forEach { b ->
        arr.put(
            JSONObject()
                .put("id", b.id)
                .put("text", b.text)
                .put("confidence", b.confidence)
                .put("status", b.status)
        )
    }
    prefs(c).edit().putString(KEY, arr.toString()).apply()
}

private fun addBelief(c: Context, text: String, confidence: Int) {
    if (text.isBlank()) return
    val now = System.currentTimeMillis()
    saveBeliefs(c, loadBeliefs(c) + Belief(now.toString(), text.trim(), confidence, "HOLDING"))
}

private fun deleteBelief(c: Context, id: String) = saveBeliefs(c, loadBeliefs(c).filter { it.id != id })

private fun setStatus(c: Context, id: String, status: String) {
    saveBeliefs(c, loadBeliefs(c).map { if (it.id == id) it.copy(status = status) else it })
}

/**
 * Aletheia - unconcealment.
 *
 * A private ledger of the things you believe without checking, with how sure you are.
 * Marking one as tested is the whole exercise; the app never argues with you, it only
 * keeps the receipt.
 */
@Composable
fun AletheiaScreen() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var confidence by remember { mutableIntStateOf(50) }
    val beliefs = remember(version) { loadBeliefs(context) }
    val holding = beliefs.filter { it.status == "HOLDING" }
    val checked = beliefs.filter { it.status != "HOLDING" }

    AtmosphereBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Aletheia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "What you believe without checking.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .luminex(RoundedCornerShape(22.dp), Ascend.reactorAmber, 0.7f)
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                    placeholder = { Text("Name something you assume is true.") },
                    label = { Text("An assumption") }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "How sure are you, honestly?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 50, 75, 100).forEach { level ->
                        val on = confidence == level
                        Text(
                            level.toString() + "%",
                            color = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Ascend.reactorAmber else MaterialTheme.colorScheme.surface)
                                .clickable { confidence = level }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        addBelief(context, draft, confidence)
                        draft = ""
                        version++
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Write it down")
                }
            }

            if (holding.isNotEmpty()) {
                Text("STILL HOLDING", color = Ascend.reactorAmber, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                holding.forEach { belief ->
                    BeliefCard(belief) { status ->
                        if (status == "DELETE") deleteBelief(context, belief.id) else setStatus(context, belief.id, status)
                        version++
                    }
                }
            }

            if (checked.isNotEmpty()) {
                Text("CHECKED", color = Ascend.reactorEmerald, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                checked.forEach { belief ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            .padding(16.dp)
                    ) {
                        Text(
                            belief.text,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (belief.status == "CHANGED") "Changed my mind" else "I tested it",
                            color = if (belief.status == "CHANGED") Ascend.reactorRose else Ascend.reactorEmerald,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(onClick = { deleteBelief(context, belief.id); version++ }) { Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            if (beliefs.isEmpty()) {
                Text(
                    "Nothing written yet. Everyone carries a few things they have never once checked.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            Text(
                "A belief you cannot test is a mood. That is allowed. Name it honestly and it stops running you quietly.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 30.dp)
            )
        }
    }
}

@Composable
private fun BeliefCard(belief: Belief, onStatus: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .luminex(RoundedCornerShape(20.dp), Ascend.reactorAmber, 0.6f)
            .padding(16.dp)
    ) {
        Text(
            belief.text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((belief.confidence / 100f).coerceIn(0.04f, 1f))
                        .height(4.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Ascend.reactorAmber, Ascend.reactorRose)
                            )
                        )
                )
            }
            Spacer(Modifier.height(0.dp))
            Text(
                belief.confidence.toString() + "%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onStatus("TESTED") }) { Text("I tested it", fontSize = 12.sp) }
            TextButton(onClick = { onStatus("CHANGED") }) { Text("I was wrong", fontSize = 12.sp) }
            TextButton(onClick = { onStatus("DELETE") }) { Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}
