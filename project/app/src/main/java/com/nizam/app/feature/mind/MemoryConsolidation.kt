package com.nizam.app.feature.mind

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ConsolidationResult(
    val before: Int,
    val after: Int,
    val synapses: Int,
    val core: List<Neuron>
)

/**
 * Consolidation - what sleep does for memory, done while the phone charges.
 *
 * It never invents anything. It lets weak, old things fade and keeps what you actually
 * return to, so the constellation stays readable after a year instead of becoming noise.
 */
object MemoryConsolidation {
    suspend fun run(context: Context): ConsolidationResult = withContext(Dispatchers.IO) {
        val before = Neurgraph.stats(context).first
        Neurgraph.decay(context)
        val after = Neurgraph.stats(context)
        ConsolidationResult(
            before = before,
            after = after.first,
            synapses = after.second,
            core = Neurgraph.core(context, 12)
        )
    }

    /** Cheap enough to call from a maintenance pass; keeps the graph from growing unbounded. */
    suspend fun tidy(context: Context) = withContext(Dispatchers.IO) {
        Neurgraph.decay(context, 0.99f)
    }
}
