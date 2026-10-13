package com.nizam.app.core.spine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Receipts — a durable, reviewable log of everything Atlas does on its own.
 *
 * The other half of trust: every autonomous act writes one honest line here, so the owner can open
 * one screen and see exactly what Atlas did, when, and whether it can be undone. No silent action.
 * This is "trust receipts" from the vision, kept deliberately simple — a rolling local log.
 */
object Receipts {
    private const val MAX = 500

    private fun file(c: Context) = File(c.filesDir, "atlas_receipts.json")

    @Synchronized
    private fun load(c: Context): JSONArray =
        runCatching { JSONArray(file(c).readText()) }.getOrElse { JSONArray() }

    @Synchronized
    private fun save(c: Context, log: JSONArray) {
        runCatching { file(c).writeText(log.toString()) }
    }

    /** Record one thing Atlas did by itself. [reversible] drives the Undo affordance on the review screen. */
    @Synchronized
    fun record(c: Context, domain: String, action: String, detail: String, reversible: Boolean = true) {
        val log = load(c)
        log.put(
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("domain", domain)
                .put("action", action)
                .put("detail", detail)
                .put("reversible", reversible)
        )
        while (log.length() > MAX) log.remove(0)
        save(c, log)
    }

    /** Most recent first, for the review screen. */
    fun recent(c: Context, limit: Int = 50): List<JSONObject> {
        val log = load(c)
        val out = ArrayList<JSONObject>()
        for (i in maxOf(0, log.length() - limit) until log.length()) out.add(log.getJSONObject(i))
        return out.asReversed()
    }
}
