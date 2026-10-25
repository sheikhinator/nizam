package com.nizam.app.feature.mind

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.atmosphere.AtmosphereBackdrop
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.luminex
import kotlinx.coroutines.launch

/**
 * Neurgraph - the substrate, made visible.
 *
 * Counts, the things you return to, and a manual consolidation you can run. It exists so
 * the rest of the sentient layer is not a black box: you can see what Atlas keeps.
 */
@Composable
fun NeurgraphScreen() {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = com.nizam.app.ui.design.rememberSafeScope()

    val loaded by androidx.compose.runtime.produceState((0 to 0) to emptyList<Neuron>(), version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Neurgraph.stats(context) }.getOrDefault(0 to 0) to runCatching { Neurgraph.core(context, 10) }.getOrElse { emptyList() } }
    }
    val stats = loaded.first
    val core = loaded.second
    val heaviest = core.firstOrNull()?.weight?.coerceAtLeast(0.1f) ?: 1f

    AtmosphereBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Neurgraph", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "The living graph under everything Atlas remembers.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("MEMORIES", stats.first.toString())
                Stat("CONNECTIONS", stats.second.toString())
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .luminex(RoundedCornerShape(22.dp), Ascend.reactorAmber, 0.7f)
                    .padding(18.dp)
            ) {
                Text("WHAT YOU RETURN TO", color = Ascend.reactorAmber, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (core.isEmpty()) {
                    Text(
                        "Nothing yet. Atlas starts keeping memories the moment you use the app.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                } else {
                    core.forEach { n ->
                        Column(Modifier.padding(bottom = 10.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    n.label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    n.type.name.lowercase(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth((n.weight / heaviest).coerceIn(0.05f, 1f))
                                        .height(3.dp)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(Ascend.reactorAmber.copy(alpha = 0.9f), Ascend.reactorRose.copy(alpha = 0.6f))
                                            )
                                        )
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (!working) {
                        working = true
                        note = null
                        scope.launch {
                            val r = runCatching { MemoryConsolidation.run(context) }
                            note = r.fold(
                                onSuccess = { it.before.toString() + " memories settled into " + it.after.toString() + "." },
                                onFailure = { "Could not consolidate right now." }
                            )
                            version++
                            working = false
                        }
                    }
                },
                enabled = !working,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (working) "Settling..." else "Consolidate now")
            }

            androidx.compose.material3.OutlinedButton(
                onClick = {
                    if (!working) { working = true; note = null
                        scope.launch {
                            val n = runCatching { MindBridge.sync(context, force = true) }.getOrDefault(0)
                            note = if (n > 0) "Brought in $n moments from your records." else "Nothing new in your records."
                            version++; working = false
                        } }
                },
                enabled = !working,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Bring in my latest records") }

            note?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }

            Text(
                "Consolidation fades what you never return to and keeps what you do. It runs on your phone, and only when you ask it to.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 30.dp)
            )
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .luminex(RoundedCornerShape(18.dp), Ascend.reactorCyan, 0.6f)
            .padding(16.dp)
    ) {
        Text(label, color = Ascend.reactorCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}
