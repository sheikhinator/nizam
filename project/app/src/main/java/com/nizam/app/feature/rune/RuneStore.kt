package com.nizam.app.feature.rune

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub
import org.json.JSONObject

/** Saved designs: one record each, {"id","name","ts","doc":{...}}. */
object RuneStore {
    private fun store(c: Context) = JsonStore(c.applicationContext, "rune_designs")

    fun all(c: Context): List<JSONObject> = store(c).all().sortedByDescending { it.optLong("ts") }

    fun get(c: Context, id: String): JSONObject? = store(c).all().firstOrNull { it.optString("id") == id }

    /** Creates or updates. Returns the id. */
    fun save(c: Context, id: String?, doc: JSONObject): String {
        val s = store(c)
        val clean = RuneModel.sanitize(doc)
        val name = clean.optString("name", "Untitled")
        if (id != null && s.all().any { it.optString("id") == id }) {
            s.update(id) { it.put("name", name).put("ts", System.currentTimeMillis()).put("doc", clean) }
            return id
        }
        val rec = s.add(JSONObject().put("name", name).put("ts", System.currentTimeMillis()).put("doc", clean))
        return rec.optString("id")
    }

    fun delete(c: Context, id: String) { store(c).remove(id) }

    fun duplicate(c: Context, id: String): String? {
        val src = get(c, id)?.optJSONObject("doc") ?: return null
        val copy = RuneModel.sanitize(JSONObject(src.toString()).put("name", src.optString("name") + " copy"))
        return save(c, null, copy)
    }
}

object RuneTemplates {
    class T(val slug: String, val doc: JSONObject)

    @Volatile private var cache: List<T>? = null

    fun all(c: Context): List<T> = cache ?: synchronized(this) {
        cache ?: runCatching {
            (c.assets.list("rune").orEmpty()).filter { it.endsWith(".json") }.sorted().mapNotNull { f ->
                runCatching { T(f.removeSuffix(".json"), RuneModel.sanitize(JSONObject(c.assets.open("rune/$f").bufferedReader().use { it.readText() }))) }.getOrNull()
            }
        }.getOrDefault(emptyList()).also { cache = it }
    }
}

/** Saved designs appear in search, hubs and the launcher exactly like built-in tools. */
object RuneDesigns {
    const val PREFIX = "rune_"

    fun specs(c: Context?): List<Spec> {
        val ctx = c ?: return emptyList()
        return runCatching { RuneStore.all(ctx).map { spec(it) } }.getOrDefault(emptyList())
    }

    fun find(id: String, c: Context?): Spec? {
        val ctx = c ?: return null
        if (!id.startsWith(PREFIX)) return null
        val rec = RuneStore.get(ctx, id.removePrefix(PREFIX)) ?: return null
        return spec(rec)
    }

    private fun spec(rec: JSONObject): Spec {
        val rid = rec.optString("id"); val name = rec.optString("name", "My design")
        return ScreenSpec(PREFIX + rid, name, "Your Rune design", Icons.Outlined.Dashboard, Hub.Tools, "rune design custom screen layout $name") { _, open ->
            RuneSavedScreen(rid, open)
        }
    }
}

val RuneSpecs: List<Spec> get() = listOf(
    ScreenSpec("rune", "Atlas Rune", "Design your own screens, themes and animations: unlimited", Icons.Outlined.AutoAwesome, Hub.Tools,
        "rune studio design ui layout theme animation custom builder gradient aurora glass motion widgets screens") { _, open -> RuneStudioScreen(open) }
)

/** Runs a saved design full screen. */
@Composable
fun RuneSavedScreen(id: String, open: (String) -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current
    val rec = androidx.compose.runtime.remember(id) { RuneStore.get(c, id) }
    val doc = rec?.optJSONObject("doc")
    if (doc == null) androidx.compose.material3.Text("This design was deleted.")
    else RuneView(doc, open, Modifier.fillMaxSize())
}

private fun Modifier.fillMaxSize(): Modifier = this.then(androidx.compose.foundation.layout.fillMaxSize())
