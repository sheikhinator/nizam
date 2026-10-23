package com.nizam.app.feature.chronoverse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.atmosphere.AtmosphereBackdrop
import com.nizam.app.feature.mind.Neurgraph
import com.nizam.app.feature.mind.Neuron
import com.nizam.app.feature.mind.NeuronType
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.luminex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private data class Era(val title: String, val caption: String, val neurons: List<Neuron>)

/**
 * Chronoverse - every thread of your life on one timeline.
 *
 * One spine, grouped by distance from now rather than by app. Yesterday and last year
 * sit in the same column, which is the whole point: a life is not a set of modules.
 */
@Composable
fun ChronoverseScreen() {
    val context = LocalContext.current
    val neurons = remember { runCatching { Neurgraph.all(context) }.getOrElse { emptyList() } }
    val eras = remember(neurons) { group(neurons) }

    AtmosphereBackdrop {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp)) {
                Text("Chronoverse", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "One life, in order.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }

            if (eras.isEmpty()) {
                EmptyTimeline()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 40.dp)
                ) {
                    eras.forEach { era ->
                        item(key = era.title) { EraHeader(era) }
                        items(era.neurons, key = { it.id }) { n -> TimelineRow(n) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EraHeader(era: Era) {
    Column(Modifier.padding(top = 20.dp, bottom = 6.dp)) {
        Text(era.title, color = Ascend.reactorCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(era.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun TimelineRow(n: Neuron) {
    val color = colorFor(n.type)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(26.dp)) {
            Box(
                Modifier
                    .padding(top = 18.dp)
                    .size(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .width(1.dp)
                    .height(46.dp)
                    .background(
                        Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent))
                    )
            )
        }
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .luminex(RoundedCornerShape(18.dp), color, 0.5f)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                stamp(n.timestamp),
                color = color.copy(alpha = 0.9f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                n.label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (n.content.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    n.content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 3
                )
            }
        }
    }
}

@Composable
private fun EmptyTimeline() {
    Column(
        Modifier.fillMaxSize().padding(36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Nothing has happened yet.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "As you talk to Atlas, write, pray, spend and move, the timeline writes itself. Nothing leaves this phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun group(neurons: List<Neuron>): List<Era> {
    if (neurons.isEmpty()) return emptyList()
    val today = LocalDate.now()
    val buckets = LinkedHashMap<String, MutableList<Neuron>>()
    neurons.sortedByDescending { it.timestamp }.forEach { n ->
        val date = Instant.ofEpochMilli(n.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        val days = ChronoUnit.DAYS.between(date, today)
        val key = when {
            days <= 1 -> "Today and yesterday"
            days <= 7 -> "This week"
            days <= 31 -> "This month"
            days <= 365 -> "This year"
            else -> "Before"
        }
        buckets.getOrPut(key) { ArrayList() }.add(n)
    }
    val order = listOf("Today and yesterday", "This week", "This month", "This year", "Before")
    return order.mapNotNull { key ->
        val list = buckets[key] ?: return@mapNotNull null
        Era(
            title = key,
            caption = list.size.toString() + if (list.size == 1) " thread" else " threads",
            neurons = list.take(40)
        )
    }
}

private fun stamp(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))

private fun colorFor(t: NeuronType): Color = when (t) {
    NeuronType.PERSON -> Ascend.reactorRose
    NeuronType.PLACE -> Ascend.reactorEmerald
    NeuronType.EVENT -> Ascend.reactorCyan
    NeuronType.IDEA -> Ascend.reactorAmber
    NeuronType.EMOTION -> Ascend.reactorViolet
    NeuronType.DECISION -> Ascend.reactorIce
    NeuronType.GOAL -> Color(0xFFFFD98A)
    else -> Ascend.reactorCyan
}
