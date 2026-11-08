package com.nizam.app.feature.rune

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Rune design is plain JSON, so it can be edited by hand, shared as text, or written by Atlas:
 * {"v":1,"name":"","theme":{bg,text,accent,accent2,radius},"vars":{},"root":{node}}
 * A node is {"id","t":type, ...props..., "kids":[nodes]}. Every property is optional.
 */
object RuneModel {
    val CONTAINERS = setOf("column", "row", "box", "grid", "hscroll", "vscroll", "card", "repeat")
    val TYPES = listOf("column", "row", "box", "card", "grid", "hscroll", "vscroll", "repeat", "text", "icon", "image", "button", "chip",
        "progress", "chart", "toggle", "slider", "input", "shape", "divider", "spacer")
    const val MAX_NODES = 500
    const val MAX_DEPTH = 14

    private var counter = 0
    fun newId(): String = "n" + (System.currentTimeMillis() % 100000000L).toString(36) + (counter++).toString(36)

    fun node(type: String, vararg props: Pair<String, Any?>): JSONObject {
        val o = JSONObject().put("id", newId()).put("t", type)
        props.forEach { (k, v) -> if (v != null) o.put(k, v) }
        if (type in CONTAINERS) o.put("kids", JSONArray())
        return o
    }

    fun kids(n: JSONObject): List<JSONObject> = n.optJSONArray("kids")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    fun setKids(n: JSONObject, list: List<JSONObject>) { n.put("kids", JSONArray().also { a -> list.forEach { a.put(it) } }) }

    fun find(root: JSONObject, id: String): JSONObject? {
        if (root.optString("id") == id) return root
        for (k in kids(root)) find(k, id)?.let { return it }
        return null
    }

    fun parentOf(root: JSONObject, id: String): JSONObject? {
        for (k in kids(root)) { if (k.optString("id") == id) return root; parentOf(k, id)?.let { return it } }
        return null
    }

    fun count(n: JSONObject): Int = 1 + kids(n).sumOf { count(it) }

    /** Copy a node with fresh ids all the way down. */
    fun cloneFresh(n: JSONObject): JSONObject {
        val c = JSONObject(n.toString())
        fun re(x: JSONObject) { x.put("id", newId()); kids(x).forEach { re(it) } }
        re(c)
        return c
    }

    fun flatten(root: JSONObject): List<Pair<JSONObject, Int>> {
        val out = ArrayList<Pair<JSONObject, Int>>()
        fun walk(n: JSONObject, d: Int) { out.add(n to d); kids(n).forEach { walk(it, d + 1) } }
        walk(root, 0)
        return out
    }

    fun blank(name: String = "Untitled"): JSONObject = JSONObject().put("v", 1).put("name", name)
        .put("vars", JSONObject())
        .put("root", node("column", "id" to "root", "fillW" to true, "fillH" to true, "padding" to 20, "gap" to 12, "fill" to "@surface"))

    /**
     * Makes any JSON (from Atlas, a paste, a shared design) safe to render: known types only, bounded size and depth,
     * every node has an id. Never throws.
     */
    fun sanitize(input: JSONObject): JSONObject {
        val doc = JSONObject()
        doc.put("v", 1).put("name", input.optString("name", "Untitled").take(60))
        input.optJSONObject("theme")?.let { doc.put("theme", it) }
        doc.put("vars", input.optJSONObject("vars") ?: JSONObject())
        var budget = MAX_NODES
        val seen = HashSet<String>()
        fun clean(n: JSONObject, depth: Int): JSONObject? {
            if (budget <= 0 || depth > MAX_DEPTH) return null
            val t = n.optString("t")
            if (t !in TYPES) return null
            budget--
            val o = JSONObject(n.toString())
            o.remove("kids")
            var id = o.optString("id")
            if (id.isBlank() || !seen.add(id)) { id = newId(); seen.add(id) }
            o.put("id", id)
            if (t in CONTAINERS) {
                val arr = JSONArray()
                kids(n).forEach { k -> clean(k, depth + 1)?.let { arr.put(it) } }
                o.put("kids", arr)
            }
            return o
        }
        val root = input.optJSONObject("root")?.let { clean(it, 0) } ?: blank().getJSONObject("root")
        doc.put("root", root)
        return doc
    }
}

/** What each property is, so the studio can build its inspector from one table. */
class PropDef(val key: String, val label: String, val kind: String, val group: String, val types: Set<String>? = null,
              val options: List<String> = emptyList(), val min: Float = 0f, val max: Float = 100f, val step: Float = 1f, val def: String = "")

object RuneProps {
    private val TXT = setOf("text", "button", "chip")
    val all: List<PropDef> = listOf(
        // content
        PropDef("text", "Text", "string", "Content", setOf("text", "button", "chip")),
        PropDef("name", "Icon", "choice", "Content", setOf("icon"), options = RuneIcons.names),
        PropDef("emoji", "Emoji or symbol", "string", "Content", setOf("icon")),
        PropDef("url", "Image address", "string", "Content", setOf("image")),
        PropDef("value", "Value (0–1, or 3/5, or {{m:prayers}}/5)", "string", "Content", setOf("progress")),
        PropDef("pstyle", "Progress look", "choice", "Content", setOf("progress"), listOf("bar", "ring", "arc", "dots")),
        PropDef("thick", "Thickness", "num", "Content", setOf("progress"), min = 2f, max = 40f),
        PropDef("values", "Numbers (comma separated)", "string", "Content", setOf("chart")),
        PropDef("cstyle", "Chart look", "choice", "Content", setOf("chart"), listOf("bar", "line", "area", "dots")),
        PropDef("items", "Items (separate with |)", "string", "Content", setOf("repeat")),
        PropDef("dir", "Direction", "choice", "Content", setOf("repeat"), listOf("column", "row")),
        PropDef("cols", "Columns", "num", "Content", setOf("grid"), min = 1f, max = 6f),
        PropDef("bind", "Variable name", "string", "Content", setOf("toggle", "slider", "input")),
        PropDef("hint", "Hint", "string", "Content", setOf("input")),
        PropDef("shape", "Shape", "choice", "Content", setOf("shape"), listOf("circle", "rect", "blob", "ring", "diamond", "pill")),
        // text style
        PropDef("size", "Text size", "num", "Text", setOf("text", "button", "chip", "icon"), min = 8f, max = 160f, def = "16"),
        PropDef("weight", "Weight", "num", "Text", TXT, min = 100f, max = 900f, step = 100f, def = "400"),
        PropDef("color", "Text colour", "color", "Text", setOf("text", "button", "chip", "icon", "progress", "chart")),
        PropDef("font", "Font", "choice", "Text", TXT, listOf("sans", "serif", "mono", "cursive")),
        PropDef("talign", "Align", "choice", "Text", setOf("text"), listOf("start", "center", "end")),
        PropDef("italic", "Italic", "bool", "Text", TXT),
        PropDef("upper", "UPPERCASE", "bool", "Text", TXT),
        PropDef("spacing", "Letter spacing", "num", "Text", TXT, min = -2f, max = 12f, step = 0.5f),
        PropDef("lineH", "Line height", "num", "Text", setOf("text"), min = 0.8f, max = 2.4f, step = 0.1f),
        PropDef("maxLines", "Max lines", "num", "Text", setOf("text"), min = 0f, max = 12f),
        PropDef("tfill", "Gradient text", "fill", "Text", setOf("text")),
        // layout
        PropDef("fillW", "Fill width", "bool", "Layout"),
        PropDef("fillH", "Fill height", "bool", "Layout"),
        PropDef("w", "Width", "num", "Layout", min = 0f, max = 600f),
        PropDef("h", "Height", "num", "Layout", min = 0f, max = 900f),
        PropDef("weightF", "Share of space (weight)", "num", "Layout", min = 0f, max = 6f, step = 0.5f),
        PropDef("aspect", "Aspect ratio (w/h)", "num", "Layout", min = 0f, max = 4f, step = 0.1f),
        PropDef("padding", "Padding", "num", "Layout", min = 0f, max = 80f),
        PropDef("padH", "Padding sideways", "num", "Layout", min = 0f, max = 80f),
        PropDef("padV", "Padding up/down", "num", "Layout", min = 0f, max = 80f),
        PropDef("margin", "Margin", "num", "Layout", min = 0f, max = 80f),
        PropDef("gap", "Gap between children", "num", "Layout", CONT, min = 0f, max = 60f),
        PropDef("align", "Align children", "choice", "Layout", CONT, listOf("start", "center", "end", "stretch")),
        PropDef("justify", "Spread children", "choice", "Layout", setOf("column", "row", "card", "repeat"), listOf("start", "center", "end", "between", "around", "evenly")),
        PropDef("boxAlign", "Place children", "choice", "Layout", setOf("box"), listOf("topstart", "top", "topend", "start", "center", "end", "bottomstart", "bottom", "bottomend")),
        PropDef("scroll", "Scrolls", "bool", "Layout", setOf("column", "card")),
        // surface
        PropDef("fill", "Fill", "fill", "Surface"),
        PropDef("radius", "Corner radius", "num", "Surface", min = 0f, max = 120f),
        PropDef("borderW", "Border width", "num", "Surface", min = 0f, max = 12f, step = 0.5f),
        PropDef("border", "Border colour", "color", "Surface"),
        PropDef("glass", "Glass", "bool", "Surface"),
        PropDef("fx", "Surface effect", "choice", "Surface", options = listOf("none", "grain", "shimmer", "scan", "sparkle", "gloss", "vignette", "frost")),
        PropDef("fxSpeed", "Effect / fill speed", "num", "Surface", min = 0.2f, max = 6f, step = 0.1f),
        PropDef("glow", "Glow size", "num", "Surface", min = 0f, max = 60f),
        PropDef("glowC", "Glow colour", "color", "Surface"),
        PropDef("elev", "Shadow", "num", "Surface", min = 0f, max = 40f),
        PropDef("blur", "Blur (soft shapes)", "num", "Surface", min = 0f, max = 80f),
        PropDef("clip", "Clip to shape", "bool", "Surface"),
        // transform
        PropDef("alpha", "Opacity", "num", "Transform", min = 0f, max = 1f, step = 0.05f, def = "1"),
        PropDef("scale", "Scale", "num", "Transform", min = 0.2f, max = 3f, step = 0.05f, def = "1"),
        PropDef("rotate", "Rotate", "num", "Transform", min = -180f, max = 180f),
        PropDef("offX", "Shift sideways", "num", "Transform", min = -300f, max = 300f),
        PropDef("offY", "Shift up/down", "num", "Transform", min = -300f, max = 300f),
        // motion
        PropDef("enter", "Enter", "choice", "Motion", options = RuneAnimNames.enter),
        PropDef("enterMs", "Enter time (ms)", "num", "Motion", min = 100f, max = 3000f, step = 50f, def = "520"),
        PropDef("enterDelay", "Enter delay (ms)", "num", "Motion", min = 0f, max = 3000f, step = 50f),
        PropDef("enterAmt", "Enter distance", "num", "Motion", min = 0f, max = 4f, step = 0.1f, def = "1"),
        PropDef("stagger", "Stagger children (ms)", "num", "Motion", CONT, min = 0f, max = 600f, step = 10f),
        PropDef("loop", "Loop", "choice", "Motion", options = RuneAnimNames.loop),
        PropDef("loopMs", "Loop time (ms)", "num", "Motion", min = 300f, max = 20000f, step = 100f, def = "3000"),
        PropDef("loopAmt", "Loop strength", "num", "Motion", min = 0f, max = 4f, step = 0.1f, def = "1"),
        PropDef("press", "On press", "choice", "Motion", options = RuneAnimNames.press),
        PropDef("spring", "Spring feel", "choice", "Motion", options = RuneSprings.names),
        // behaviour
        PropDef("on", "When tapped", "string", "Behaviour"),
        PropDef("onLong", "When held", "string", "Behaviour"),
        PropDef("showIf", "Show only if (variable, !variable, var==value)", "string", "Behaviour"),
        PropDef("desc", "Spoken label", "string", "Behaviour")
    )

    private val CONT: Set<String> get() = RuneModel.CONTAINERS

    fun forType(t: String): List<PropDef> = all.filter { it.types == null || t in it.types }
}

/** Curated icon names (Material outlined) a design can use. */
object RuneIcons {
    val names = listOf("star", "heart", "home", "bolt", "moon", "sun", "cloud", "mosque", "book", "check", "add", "close", "search", "settings",
        "person", "group", "wallet", "chart", "timer", "alarm", "water", "fitness", "food", "music", "play", "pause", "camera", "mail", "chat",
        "phone", "map", "lock", "bell", "fire", "leaf", "gift", "flag", "bulb", "rocket", "palette")
}
