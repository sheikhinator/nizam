package com.nizam.app.feature.mind

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Neurgraph - one directed graph under the whole mind.
 *
 * A node is a person, a place, a moment, an idea, a decision. An edge is a relationship
 * with weight, kind and time. Everything Atlas notices lands here first, and every other
 * sentient screen reads from it, so there is one memory instead of ten lists.
 *
 * Stored as two small JSON files in app-private storage. No network, no schema migration.
 */
data class Neuron(
    val id: String,
    val type: NeuronType,
    val label: String,
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val weight: Float = 1f,
    val emotion: String = "neutral",
    val accessed: Long = System.currentTimeMillis()
)

enum class NeuronType { PERSON, PLACE, EVENT, IDEA, EMOTION, DECISION, MOMENT, GOAL, CONVERSATION, THOUGHT }

data class Synapse(
    val from: String,
    val to: String,
    val strength: Float,
    val type: String,
    val timestamp: Long
)

object Neurgraph {
    private const val MAX_NODES = 4000

    private val neurons = LinkedHashMap<String, Neuron>()
    private val synapses = ArrayList<Synapse>()

    @Volatile private var loaded = false

    private fun dir(c: Context) = File(c.filesDir, "neurgraph").also { it.mkdirs() }
    private fun neuronsFile(c: Context) = File(dir(c), "neurons.json")
    private fun synapsesFile(c: Context) = File(dir(c), "synapses.json")

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        runCatching {
            val nf = neuronsFile(c)
            if (nf.exists()) {
                val arr = JSONArray(nf.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val n = Neuron(
                        id = o.getString("id"),
                        type = runCatching { NeuronType.valueOf(o.getString("type")) }.getOrDefault(NeuronType.THOUGHT),
                        label = o.optString("label"),
                        content = o.optString("content"),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                        weight = o.optDouble("weight", 1.0).toFloat(),
                        emotion = o.optString("emotion", "neutral"),
                        accessed = o.optLong("accessed", System.currentTimeMillis())
                    )
                    neurons[n.id] = n
                }
            }
            val sf = synapsesFile(c)
            if (sf.exists()) {
                val arr = JSONArray(sf.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    synapses.add(
                        Synapse(
                            from = o.getString("from"),
                            to = o.getString("to"),
                            strength = o.optDouble("strength", 0.5).toFloat(),
                            type = o.optString("type", "related"),
                            timestamp = o.optLong("timestamp", 0L)
                        )
                    )
                }
            }
        }
        loaded = true
    }

    @Synchronized
    fun add(c: Context, neuron: Neuron) {
        load(c)
        neurons[neuron.id] = neuron
        if (neurons.size > MAX_NODES) {
            val drop = neurons.values.sortedBy { it.weight * (if (it.accessed > System.currentTimeMillis() - 604800000L) 4f else 1f) }
                .take(neurons.size - MAX_NODES)
            drop.forEach { neurons.remove(it.id) }
        }
        save(c)
    }

    @Synchronized
    fun connect(c: Context, from: String, to: String, strength: Float = 0.5f, type: String = "related") {
        load(c)
        if (from == to) return
        if (!neurons.containsKey(from) || !neurons.containsKey(to)) return
        val existing = synapses.indexOfFirst { it.from == from && it.to == to }
        if (existing >= 0) {
            val old = synapses[existing]
            synapses[existing] = old.copy(strength = (old.strength + strength).coerceAtMost(1f), timestamp = System.currentTimeMillis())
        } else {
            synapses.add(Synapse(from, to, strength.coerceIn(0f, 1f), type, System.currentTimeMillis()))
        }
        save(c)
    }

    @Synchronized
    fun all(c: Context): List<Neuron> {
        load(c)
        return neurons.values.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun core(c: Context, limit: Int = 12): List<Neuron> {
        load(c)
        return neurons.values.sortedByDescending { it.weight }.take(limit)
    }

    @Synchronized
    fun traverse(c: Context, startId: String, depth: Int = 2): List<Neuron> {
        load(c)
        val visited = LinkedHashSet<String>()
        val out = ArrayList<Neuron>()
        fun walk(id: String, d: Int) {
            if (d > depth || !visited.add(id)) return
            neurons[id]?.let { out.add(it) }
            synapses.forEach { s ->
                if (s.from == id) walk(s.to, d + 1)
                if (s.to == id) walk(s.from, d + 1)
            }
        }
        walk(startId, 0)
        return out
    }

    @Synchronized
    fun recall(c: Context, query: String, limit: Int = 10): List<Neuron> {
        load(c)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return neurons.values
            .filter { it.label.lowercase().contains(q) || it.content.lowercase().contains(q) }
            .sortedByDescending { it.weight * (if (it.accessed > System.currentTimeMillis() - 86400000L) 1.5f else 1f) }
            .take(limit)
    }

    @Synchronized
    fun stats(c: Context): Pair<Int, Int> {
        load(c)
        return neurons.size to synapses.size
    }

    /**
     * Sleep for the graph: faint memories fade, strong ones stay, and anything genuinely
     * forgotten lets go. Run when charging, never on a timer you would notice.
     */
    @Synchronized
    fun decay(c: Context, factor: Float = 0.97f) {
        load(c)
        val now = System.currentTimeMillis()
        val iterator = neurons.entries.iterator()
        while (iterator.hasNext()) {
            val n = iterator.next().value
            val ageDays = (now - n.timestamp) / 86400000f
            val next = (n.weight * factor).coerceAtLeast(0.01f)
            if (next < 0.08f && ageDays > 30f) {
                iterator.remove()
            } else {
                neurons[n.id] = n.copy(weight = next)
            }
        }
        save(c)
    }

    @Synchronized
    fun touch(c: Context, id: String) {
        load(c)
        val n = neurons[id] ?: return
        neurons[id] = n.copy(accessed = System.currentTimeMillis(), weight = (n.weight + 0.05f).coerceAtMost(5f))
        save(c)
    }

    private fun save(c: Context) {
        runCatching {
            val nf = JSONArray()
            neurons.values.forEach { n ->
                nf.put(
                    JSONObject()
                        .put("id", n.id)
                        .put("type", n.type.name)
                        .put("label", n.label)
                        .put("content", n.content)
                        .put("timestamp", n.timestamp)
                        .put("weight", n.weight.toDouble())
                        .put("emotion", n.emotion)
                        .put("accessed", n.accessed)
                )
            }
            neuronsFile(c).writeText(nf.toString())

            val sf = JSONArray()
            synapses.takeLast(20000).forEach { s ->
                sf.put(
                    JSONObject()
                        .put("from", s.from)
                        .put("to", s.to)
                        .put("strength", s.strength.toDouble())
                        .put("type", s.type)
                        .put("timestamp", s.timestamp)
                )
            }
            synapsesFile(c).writeText(sf.toString())
        }
    }
}
