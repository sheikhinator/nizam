package com.nizam.app.feature.shadow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.atmosphere.AtmosphereBackdrop
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.luminex
import com.nizam.app.ui.design.reactorGlow

private data class Mirror(
    val realm: String,
    val against: String,
    val forYou: String,
    val question: String
)

private val mirrors = listOf(
    Mirror(
        "Money",
        "You call it being careful, but you have been checking the balance like a wound. Buying nothing is not the same as being safe.",
        "You have spent on almost nothing for yourself this month. That is not recklessness; that is someone carrying other people quietly.",
        "Is this fear, or is this care? Say it out loud and hear which one sounds true."
    ),
    Mirror(
        "Work",
        "You keep saying you will start properly on Monday. This is the fourth Monday. The plan is not the problem; the wanting is split.",
        "You have kept going through weeks that would have stopped most people. Wanting rest is not the same as giving up.",
        "If nobody would ever know you did it, would you still want this?"
    ),
    Mirror(
        "Love",
        "You are kind to everyone and honest with almost no one. That is not peace, that is a very well-managed distance.",
        "You forgive faster than most people deserve. That is a strength until the day it becomes a habit of abandoning yourself.",
        "What would you say if you were not afraid of being too much?"
    ),
    Mirror(
        "Self",
        "You are comparing your behind-the-scenes to everybody's highlight reel again. You know it does not work, and you do it anyway.",
        "You have changed more this year than you give yourself credit for. You just refuse to count the quiet wins.",
        "Would you speak to a friend the way you spoke to yourself today?"
    ),
    Mirror(
        "Discipline",
        "You are waiting to feel ready. You have been waiting a while. Readiness is mostly a story you tell to delay the first ten minutes.",
        "You showed up on the days you did not want to. That is the entire skill, and you already have some of it.",
        "What is the smallest version of this you could do today and still call it done?"
    )
)

/**
 * Cognitive Shadow - the part of you that argues back.
 *
 * Two voices, both fair: the case against the impulse, and the case for you. It never
 * insults and never flatters. The question at the bottom is the actual feature.
 */
@Composable
fun CognitiveShadowScreen() {
    var index by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var impulse by remember { mutableStateOf("") }
    var mine by remember { mutableStateOf<Mirror?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    val mirror = mine ?: mirrors[index]

    AtmosphereBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF14121C))
                        .reactorGlow(Ascend.reactorRose, 0.5f, RoundedCornerShape(50))
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("The Shadow", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("The part of you that argues back", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }

            // your own decision, argued both ways
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .luminex(RoundedCornerShape(22.dp), Ascend.reactorIce, 0.7f)
                    .padding(16.dp)
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = impulse, onValueChange = { impulse = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("What are you about to decide or do?") },
                    placeholder = { Text("e.g. quit my job to start the gold business") },
                    minLines = 2
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.Button(enabled = impulse.isNotBlank() && !thinking, onClick = {
                    thinking = true; problem = ""
                    scope.launch {
                        val r = runCatching {
                            val container = (context.applicationContext as com.nizam.app.NizamApplication).container
                            val raw = com.nizam.app.net.Ai.generate(container.settingsRepository,
                                "The user is about to: " + impulse.trim() + "\n\nGive two fair voices and one question. Reply as JSON " +
                                "{\"against\":\"the honest case against the impulse, 2-3 sentences, never insulting\",\"for\":\"the honest case for them, 2-3 sentences, never flattering\",\"question\":\"one question that cuts to what they really want\"}",
                                system = "You are the user's cognitive shadow: fair, direct, kind. Output only JSON.", maxOutputTokens = 400)
                            val o = org.json.JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
                            Mirror("Yours", o.getString("against"), o.optString("for"), o.optString("question"))
                        }
                        r.onSuccess { mine = it }.onFailure { problem = "Couldn't hear the shadow right now. Check your AI key and try again." }
                        thinking = false
                    }
                }) { Text(if (thinking) "Listening…" else "Hear both sides") }
                if (problem.isNotBlank()) Text(problem, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                mirrors.forEachIndexed { i, m ->
                    val on = mine == null && i == index
                    Text(
                        m.realm,
                        color = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) Ascend.reactorIce else MaterialTheme.colorScheme.surface)
                            .clickable { index = i; mine = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Voice(
                label = "THE CASE AGAINST THE IMPULSE",
                body = mirror.against,
                accent = Ascend.reactorRose
            )
            Voice(
                label = "THE CASE FOR YOU",
                body = mirror.forYou,
                accent = Ascend.reactorEmerald
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .luminex(RoundedCornerShape(22.dp), Ascend.reactorViolet, 0.7f)
                    .padding(20.dp)
            ) {
                Text("SIT WITH THIS", color = Ascend.reactorViolet, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    mirror.question,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 24.sp
                )
            }

            Text(
                "The Shadow is a mirror, not a judge. It argues with your impulses, never with your worth.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 30.dp)
            )
        }
    }
}

@Composable
private fun Voice(label: String, body: String, accent: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .luminex(RoundedCornerShape(20.dp), accent, 0.6f)
            .padding(18.dp)
    ) {
        Text(label, color = accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 22.sp)
    }
}
