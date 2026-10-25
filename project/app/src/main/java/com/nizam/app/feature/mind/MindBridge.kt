package com.nizam.app.feature.mind

import android.content.Context
import com.nizam.app.feature.nx.Roads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * MindBridge - the seam between the apps you actually use and the mind that remembers them.
 *
 * Roads already knows how to read every log on the phone. This turns those entries into
 * neurons once, in one batch, so the constellation, the timeline and the book of you are
 * looking at real life instead of an empty graph. Nothing here invents a memory and
 * nothing leaves the device.
 */
object MindBridge {

    suspend fun sync(context: Context, limit: Int = 240, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        // reading every log is not free: at most every few hours unless asked
        val p = context.getSharedPreferences("mindbridge", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - p.getLong("last", 0L) < 4 * 3_600_000L) return@withContext 0
        p.edit().putLong("last", System.currentTimeMillis()).apply()
        val items = runCatching { Roads.items(context, 12) }.getOrElse { return@withContext 0 }
        if (items.isEmpty()) return@withContext 0

        val recent = items.sortedByDescending { it.ts }.take(limit)
        val neurons = ArrayList<Neuron>(recent.size)
        val edges = ArrayList<Triple<String, String, Float>>()
        val lastBySource = HashMap<String, String>()

        recent.forEach { item ->
            val label = title(item.text)
            if (label.isBlank()) return@forEach
            val id = "road_" + slug(item.source) + "_" + item.ts
            neurons.add(
                Neuron(
                    id = id,
                    type = typeFor(item.hub.name),
                    label = label,
                    content = item.text.take(320),
                    timestamp = item.ts,
                    weight = 1f,
                    emotion = "neutral",
                    accessed = item.ts
                )
            )
            val previous = lastBySource.put(item.source, id)
            if (previous != null && previous != id) edges.add(Triple(previous, id, 0.35f))
        }

        if (neurons.isEmpty()) return@withContext 0
        Neurgraph.addAll(context, neurons)
        Neurgraph.connectAll(context, edges)
        neurons.size
    }

    private fun title(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(72) ?: ""

    private fun slug(value: String): String =
        value.lowercase(Locale.US).map { ch -> if (ch.isLetterOrDigit()) ch else '_' }.joinToString("").take(24)

    private fun typeFor(hub: String): NeuronType = when (hub) {
        "Deen" -> NeuronType.MOMENT
        "Learn", "Create" -> NeuronType.IDEA
        "Social" -> NeuronType.CONVERSATION
        "Travel" -> NeuronType.PLACE
        "Memory" -> NeuronType.MOMENT
        "Health" -> NeuronType.EVENT
        "Money" -> NeuronType.EVENT
        "Work" -> NeuronType.EVENT
        else -> NeuronType.EVENT
    }
}
