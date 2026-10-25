package com.nizam.app.feature.attention

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.nizam.app.feature.nx.Roads
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.luminex
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Attention Mirror - where the hours actually went.
 *
 * It counts what the phone already recorded and shows the pull, not a productivity
 * score. Nothing here is graded; a mirror is allowed to be uncomfortable.
 */
@Composable
fun AttentionMirrorScreen() {
    val context = LocalContext.current
    val sources by androidx.compose.runtime.produceState(emptyList<Roads.Source>()) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Roads.sources(context) }.getOrElse { emptyList() } } }
    val kept = remember { runCatching { Neurgraph.stats(context).first }.getOrDefault(0) }
    val total = remember(sources) { sources.sumOf { it.count }.coerceAtLeast(1) }
    val top = remember(sources) { sources.sortedByDescending { it.count }.take(6) }
    val heaviest = top.firstOrNull()?.count?.coerceAtLeast(1) ?: 1

    AtmosphereBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Attention", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Where it actually went, not where you meant to put it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("MOMENTS", total.toString())
                Tile("KEPT", kept.toString())
            }

            if (top.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .luminex(RoundedCornerShape(22.dp), Ascend.reactorCyan, 0.6f)
                        .padding(20.dp)
                ) {
                    Text(
                        "Nothing to count yet.",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Use the app for a day. Atlas counts from your own records, and the picture appears on its own.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .luminex(RoundedCornerShape(22.dp), Ascend.reactorCyan, 0.7f)
                        .padding(18.dp)
                ) {
                    Text("THE PULL", color = Ascend.reactorCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    top.forEach { source ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    source.label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(5.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth((source.count.toFloat() / heaviest).coerceIn(0.04f, 1f))
                                            .height(4.dp)
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(source.hub.spine, Ascend.reactorViolet)
                                                )
                                            )
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                source.count.toString(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Text(
                        "Last opened " + ago(top.first().lastTs) + ".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }

            Text(
                "This is not a score. It is a mirror, and a mirror does not ask you to be better. It only shows you.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 30.dp)
            )
        }
    }
}

@Composable
private fun RowScope.Tile(label: String, value: String) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .luminex(RoundedCornerShape(18.dp), Ascend.reactorIce, 0.6f)
            .padding(16.dp)
    ) {
        Text(label, color = Ascend.reactorIce, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun ago(ts: Long): String {
    if (ts <= 0L) return "a while back"
    val minutes = ChronoUnit.MINUTES.between(
        Instant.ofEpochMilli(ts),
        Instant.now()
    ).coerceAtLeast(0L)
    return when {
        minutes < 60 -> minutes.toString() + " minutes ago"
        minutes < 1440 -> (minutes / 60).toString() + " hours ago"
        else -> (minutes / 1440).toString() + " days ago"
    }
}
