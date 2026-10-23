package com.nizam.app.feature.anamnesis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.atmosphere.AtmosphereBackdrop
import com.nizam.app.feature.mind.Neurgraph
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.luminex
import com.nizam.app.ui.design.reactorGlow

/**
 * Anamnesis - the book of you.
 *
 * Not a dashboard. A short, honest reading of your own record, generated from what is
 * actually on this phone. When there is not enough yet, it says so instead of inventing
 * a life for you.
 */
@Composable
fun AnamnesisScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("atlas", 0) }
    val firstSeen = remember { prefs.getLong("first_seen", System.currentTimeMillis()) }
    val days = remember { ((System.currentTimeMillis() - firstSeen) / 86400000L).coerceAtLeast(0L) }
    val neurons = remember { runCatching { Neurgraph.all(context) }.getOrElse { emptyList() } }
    val core = remember(neurons) { neurons.sortedByDescending { it.weight }.take(5) }
    val goals = remember(neurons) { neurons.filter { it.type.name == "GOAL" }.take(5) }
    val people = remember(neurons) { neurons.filter { it.type.name == "PERSON" }.take(5) }

    AtmosphereBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "The Book of You",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (days == 0L) "Chapter one, opened today." else days.toString() + " days in, and still being written.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            Chapter(
                title = "What you return to",
                body = if (core.isEmpty()) {
                    "Nothing has earned a place here yet. The things you come back to will appear on their own."
                } else {
                    "Lately the centre of your record has been " + core.map { it.label }.distinct().take(3).joinToString(", ") + ". Not because you said it mattered, but because you kept going back."
                },
                accent = Ascend.reactorAmber
            )

            Chapter(
                title = "The people in it",
                body = if (people.isEmpty()) {
                    "No one has been written in yet. Atlas only keeps what you choose to bring."
                } else {
                    people.map { it.label }.distinct().joinToString(", ") + " keep appearing in your days. That is usually how love shows up in a log."
                },
                accent = Ascend.reactorRose
            )

            Chapter(
                title = "What you are building",
                body = if (goals.isEmpty()) {
                    "You have not written a goal down here. That is allowed. Not every season is for building."
                } else {
                    "You are still carrying: " + goals.map { it.label }.distinct().joinToString(", ") + ". Some of these have outlived the mood that started them, which is the only real test."
                },
                accent = Ascend.reactorEmerald
            )

            Chapter(
                title = "The honest part",
                body = if (neurons.size < 25) {
                    "There is not enough here yet to tell you anything true about yourself, and Atlas will not pretend otherwise. Keep living; the book fills slowly."
                } else {
                    "The record says you have " + neurons.size.toString() + " things worth keeping. It does not say whether you were brave. Only you know that, and you already know the answer."
                },
                accent = Ascend.reactorViolet
            )

            Spacer(Modifier.height(28.dp))
            Text(
                "Written on your phone. Never sent anywhere.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun Chapter(title: String, body: String, accent: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .luminex(RoundedCornerShape(22.dp), accent, 0.7f)
            .reactorGlow(accent, 0.28f, RoundedCornerShape(22.dp))
            .padding(20.dp)
    ) {
        Text(title.uppercase(), color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            lineHeight = 23.sp
        )
    }
}
