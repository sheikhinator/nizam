package com.nizam.app.core.spine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * World — a small on-device knowledge graph of the owner's life.
 *
 * Entities (people, places, accounts, habits, things) are [Node]s; how they connect ("lives_in",
 * "pays", "belongs_to", "member_of") are [Edge]s. Any feature can add to it, and Atlas reasons
 * *across* domains by walking it — the difference between many separate trackers and one companion
 * that can say "fuel's low, the dollar's up, and you've a trip Thursday."
 *
 * Backed by a single JSON file so it needs no database migration to exist. It stays small by design;
 * when it grows, this is the seam where a proper store slots in behind the same API.
 */
object World {
    data class Node(val id: String, val type: String, val label: String, val props: JSONObject = JSONObject())
    data class Edge(val from: String, val rel: String, val to: String)

    private fun file(c: Context) = File(c.filesDir, "world_graph.json")

    @Synchronized
    private fun load(c: Context): JSONObject =
        runCatching { JSONObject(file(c).readText()) }
            .getOrElse { JSONObject().put("nodes", JSONArray()).put("edges", JSONArray()) }

    @Synchronized
    private fun store(c: Context, graph: JSONObject) {
        runCatching { file(c).writeText(graph.toString()) }
    }

    private fun nodeJson(n: Node) =
        JSONObject().put("id", n.id).put("type", n.type).put("label", n.label).put("props", n.props)

    private fun nodeOf(o: JSONObject) =
        Node(o.optString("id"), o.optString("type"), o.optString("label"), o.optJSONObject("props") ?: JSONObject())

    /** Add or update an entity by id. */
    @Synchronized
    fun put(c: Context, node: Node) {
        val graph = load(c)
        val nodes = graph.optJSONArray("nodes") ?: JSONArray()
        var replaced = false
        for (i in 0 until nodes.length()) {
            if (nodes.getJSONObject(i).optString("id") == node.id) { nodes.put(i, nodeJson(node)); replaced = true; break }
        }
        if (!replaced) nodes.put(nodeJson(node))
        graph.put("nodes", nodes)
        store(c, graph)
    }

    /** Connect two entities. Duplicate edges are ignored. */
    @Synchronized
    fun relate(c: Context, edge: Edge) {
        val graph = load(c)
        val edges = graph.optJSONArray("edges") ?: JSONArray()
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            if (e.optString("from") == edge.from && e.optString("rel") == edge.rel && e.optString("to") == edge.to) return
        }
        edges.put(JSONObject().put("from", edge.from).put("rel", edge.rel).put("to", edge.to))
        graph.put("edges", edges)
        store(c, graph)
    }

    /** All entities, optionally of one [type]. */
    fun nodes(c: Context, type: String? = null): List<Node> {
        val nodes = load(c).optJSONArray("nodes") ?: JSONArray()
        return (0 until nodes.length()).map { nodeOf(nodes.getJSONObject(it)) }
            .filter { type == null || it.type == type }
    }

    fun node(c: Context, id: String): Node? = nodes(c).firstOrNull { it.id == id }

    /** Edges leaving [from], optionally of one relation. */
    fun related(c: Context, from: String, rel: String? = null): List<Edge> {
        val edges = load(c).optJSONArray("edges") ?: JSONArray()
        return (0 until edges.length()).map { Edge(it.let { i -> edges.getJSONObject(i).optString("from") },
            edges.getJSONObject(it).optString("rel"), edges.getJSONObject(it).optString("to")) }
            .filter { it.from == from && (rel == null || it.rel == rel) }
    }
}
