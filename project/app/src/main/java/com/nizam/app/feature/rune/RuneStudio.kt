package com.nizam.app.feature.rune

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.nx.Share
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.rememberSafeScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Everything the editor changes goes through here so undo/redo and live preview stay in step. */
class RuneEditor(initial: JSONObject, var id: String?) {
    var doc by mutableStateOf(initial)
    var selected by mutableStateOf<String?>(null)
    var replay by mutableIntStateOf(0)
    var dirty by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    private val undoS = ArrayList<String>()
    private val redoS = ArrayList<String>()
    private var lastKey = ""
    private var lastAt = 0L

    val root: JSONObject get() = doc.getJSONObject("root")
    fun node(id: String?): JSONObject? = id?.let { RuneModel.find(root, it) }
    val sel: JSONObject? get() = node(selected)
    val theme: RuneTheme? get() = RuneTheme.fromJson(doc.optJSONObject("theme"))

    fun edit(key: String? = null, change: (JSONObject) -> Unit) {
        val now = System.currentTimeMillis()
        if (key == null || key != lastKey || now - lastAt > 700) { undoS.add(doc.toString()); if (undoS.size > 80) undoS.removeAt(0); redoS.clear() }
        lastKey = key.orEmpty(); lastAt = now
        val d = JSONObject(doc.toString()); change(d); doc = d
        dirty = true; canUndo = undoS.isNotEmpty(); canRedo = redoS.isNotEmpty()
    }

    fun undo() { if (undoS.isEmpty()) return; redoS.add(doc.toString()); doc = JSONObject(undoS.removeAt(undoS.lastIndex)); lastKey = ""; canUndo = undoS.isNotEmpty(); canRedo = true; dirty = true }
    fun redo() { if (redoS.isEmpty()) return; undoS.add(doc.toString()); doc = JSONObject(redoS.removeAt(redoS.lastIndex)); lastKey = ""; canUndo = true; canRedo = redoS.isNotEmpty(); dirty = true }

    fun setProp(k: String, v: Any?) { val id = selected ?: return; edit("p:$id:$k") { d -> RuneModel.find(d.getJSONObject("root"), id)?.let { n -> if (v == null) n.remove(k) else n.put(k, v) } } }

    fun replace(newDoc: JSONObject) { edit { }; doc = RuneModel.sanitize(newDoc); selected = null; dirty = true; replay++ }

    fun add(n: JSONObject) {
        edit { d ->
            val r = d.getJSONObject("root")
            val target = selected?.let { RuneModel.find(r, it) }
            val parent = when {
                target == null -> r
                target.optString("t") in RuneModel.CONTAINERS -> target
                else -> RuneModel.parentOf(r, target.optString("id")) ?: r
            }
            if (RuneModel.count(r) + RuneModel.count(n) <= RuneModel.MAX_NODES) RuneModel.setKids(parent, RuneModel.kids(parent) + n)
        }
        selected = n.optString("id"); replay++
    }

    fun remove(id: String) { if (id == "root") return; edit { d -> RuneModel.parentOf(d.getJSONObject("root"), id)?.let { p -> RuneModel.setKids(p, RuneModel.kids(p).filter { it.optString("id") != id }) } }; if (selected == id) selected = null }

    fun duplicate(id: String) { edit { d -> val r = d.getJSONObject("root"); val p = RuneModel.parentOf(r, id) ?: return@edit; val src = RuneModel.find(r, id) ?: return@edit
        val ks = RuneModel.kids(p).toMutableList(); ks.add(ks.indexOfFirst { it.optString("id") == id } + 1, RuneModel.cloneFresh(src)); RuneModel.setKids(p, ks) } }

    fun move(id: String, delta: Int) { edit { d -> val p = RuneModel.parentOf(d.getJSONObject("root"), id) ?: return@edit
        val ks = RuneModel.kids(p).toMutableList(); val i = ks.indexOfFirst { it.optString("id") == id }; val j = i + delta
        if (i < 0 || j < 0 || j >= ks.size) return@edit; val x = ks.removeAt(i); ks.add(j, x); RuneModel.setKids(p, ks) } }

    /** Move into the previous sibling (if it can hold children). */
    fun indent(id: String) { edit { d -> val r = d.getJSONObject("root"); val p = RuneModel.parentOf(r, id) ?: return@edit
        val ks = RuneModel.kids(p).toMutableList(); val i = ks.indexOfFirst { it.optString("id") == id }
        val prev = ks.getOrNull(i - 1) ?: return@edit
        if (prev.optString("t") !in RuneModel.CONTAINERS) return@edit
        val me = ks.removeAt(i); RuneModel.setKids(p, ks); RuneModel.setKids(prev, RuneModel.kids(prev) + me) } }

    /** Move out to the parent's level, right after the parent. */
    fun outdent(id: String) { edit { d -> val r = d.getJSONObject("root"); val p = RuneModel.parentOf(r, id) ?: return@edit; val gp = RuneModel.parentOf(r, p.optString("id")) ?: return@edit
        val me = RuneModel.find(r, id) ?: return@edit
        RuneModel.setKids(p, RuneModel.kids(p).filter { it.optString("id") != id })
        val gk = RuneModel.kids(gp).toMutableList(); gk.add(gk.indexOfFirst { it.optString("id") == p.optString("id") } + 1, me); RuneModel.setKids(gp, gk) } }

    /** Wrap a node in a new container. */
    fun wrap(id: String, type: String) { if (id == "root") return; edit { d -> val r = d.getJSONObject("root"); val p = RuneModel.parentOf(r, id) ?: return@edit
        val ks = RuneModel.kids(p).toMutableList(); val i = ks.indexOfFirst { it.optString("id") == id }; if (i < 0) return@edit
        val box = RuneModel.node(type, "gap" to 8); RuneModel.setKids(box, listOf(ks[i])); ks[i] = box; RuneModel.setKids(p, ks) } }
}

private val BLOCKS: List<Pair<String, () -> JSONObject>> = listOf(
    "Heading" to { RuneModel.node("text", "text" to "A bold heading", "size" to 32, "weight" to 700, "enter" to "rise") },
    "Paragraph" to { RuneModel.node("text", "text" to "Write something here. Use {{greeting}} or {{m:tasks_open}} for live values.", "size" to 16, "color" to "@ink/75", "lineH" to 1.4) },
    "Big clock" to { RuneModel.node("text", "text" to "{{time}}", "size" to 72, "weight" to 200, "spacing" to -2, "tfill" to "lin:90:@ink,@primary") },
    "Glass card" to { RuneModel.node("card", "glass" to true, "radius" to 24, "padding" to 16, "gap" to 6, "enter" to "rise", "press" to "lift").also { c ->
        RuneModel.setKids(c, listOf(RuneModel.node("text", "text" to "Card title", "size" to 18, "weight" to 600), RuneModel.node("text", "text" to "Card detail", "size" to 14, "color" to "@ink/65"))) } },
    "Stat tile" to { RuneModel.node("column", "fill" to "lin:140:@primary,@accent", "radius" to 24, "padding" to 16, "gap" to 2, "w" to 150, "enter" to "scale", "press" to "squish").also { c ->
        RuneModel.setKids(c, listOf(RuneModel.node("text", "text" to "TASKS LEFT", "size" to 12, "upper" to true, "spacing" to 1, "color" to "#FFFFFF/80"),
            RuneModel.node("text", "text" to "{{m:tasks_open}}", "size" to 38, "weight" to 700, "color" to "#FFFFFF"))) } },
    "Button row" to { RuneModel.node("row", "fillW" to true, "gap" to 10).also { c ->
        RuneModel.setKids(c, listOf(RuneModel.node("button", "text" to "Primary", "weightF" to 1, "radius" to 18), RuneModel.node("button", "text" to "Second", "weightF" to 1, "radius" to 18, "glass" to true))) } },
    "Prayer ring" to { RuneModel.node("progress", "value" to "{{m:prayers}}/5", "pstyle" to "ring", "w" to 120, "thick" to 12, "text" to "{{m:prayers}}/5", "size" to 22, "enter" to "pop") },
    "Chip row" to { RuneModel.node("row", "gap" to 8).also { c ->
        RuneModel.setKids(c, listOf(RuneModel.node("chip", "text" to "One"), RuneModel.node("chip", "text" to "Two"), RuneModel.node("chip", "text" to "Three"))) } },
    "Mesh hero" to { RuneModel.node("box", "fillW" to true, "h" to 200, "radius" to 28, "fill" to "mesh:#6C8CFF,#FF7AB6,#FFC86B,#3DF2B0", "fx" to "grain", "boxAlign" to "bottomstart").also { c ->
        RuneModel.setKids(c, listOf(RuneModel.node("text", "text" to "Hero", "size" to 34, "weight" to 700, "color" to "#FFFFFF", "padding" to 18))) } },
    "Shimmer card" to { RuneModel.node("card", "fill" to "lin:135:#6C8CFF,#C77DFF", "fx" to "shimmer", "radius" to 24, "padding" to 18, "h" to 110).also { c ->
        RuneModel.setKids(c, listOf(RuneModel.node("text", "text" to "Shimmer", "size" to 22, "weight" to 600, "color" to "#FFFFFF"))) } },
    "Bar chart" to { RuneModel.node("chart", "values" to "3,5,2,8,6,9,4", "cstyle" to "bar", "h" to 100, "fillW" to true) },
    "Glow orb" to { RuneModel.node("shape", "shape" to "circle", "w" to 90, "h" to 90, "fill" to "lin:135:@primary,@accent", "glow" to 24, "loop" to "pulse") }
)

private val TABS = listOf("Layers", "Add", "Style", "Motion", "Theme", "Code")

@Composable
fun RuneStudioScreen(open: (String) -> Unit) {
    val c = LocalContext.current
    var session by remember { mutableStateOf<RuneEditor?>(null) }
    LaunchedEffectOnce { RuneMotionPrefs.load(c) }
    val s = session
    if (s == null) RuneGallery(open) { session = it } else RuneEditorScreen(s, open) { session = null }
}

@Composable
private fun LaunchedEffectOnce(block: () -> Unit) { androidx.compose.runtime.LaunchedEffect(Unit) { block() } }

// ───────────────────────── gallery ─────────────────────────

@Composable
private fun RuneGallery(open: (String) -> Unit, edit: (RuneEditor) -> Unit) {
    val c = LocalContext.current
    val scope = rememberSafeScope()
    var saved by remember { mutableStateOf(RuneStore.all(c)) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf(RuneMotionPrefs.speed) }
    var fx by remember { mutableStateOf(RuneMotionPrefs.effects) }
    val templates = remember { RuneTemplates.all(c) }
    val m = MaterialTheme.colorScheme

    AtlasScreen(title = "Atlas Rune", subtitle = "Design any screen, theme and animation. Everything is editable.") {
        item("new") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ edit(RuneEditor(RuneModel.blank(), null)) }, Modifier.weight(1f)) { Text("New blank") }
                    OutlinedButton({
                        val clip = runCatching { (c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
                        val j = runCatching { JSONObject(clip) }.getOrNull()
                        if (j == null || !j.has("root")) msg = "Copy a Rune design first, then tap Import." else edit(RuneEditor(RuneModel.sanitize(j), null))
                    }, Modifier.weight(1f)) { Text("Import") }
                }
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("Describe a screen and Atlas designs it") }, minLines = 2)
                Button({
                    busy = true; msg = ""
                    scope.launch {
                        val d = RuneAi.design(c, prompt, null)
                        busy = false
                        if (d == null) msg = "Couldn't design that. Check your AI key in Settings, or rephrase." else edit(RuneEditor(d, null).also { it.dirty = true })
                    }
                }, Modifier.fillMaxWidth(), enabled = !busy && prompt.isNotBlank()) { Text(if (busy) "Designing…" else "Design it for me") }
                if (msg.isNotEmpty()) Text(msg, color = m.error, fontSize = 13.sp)
            }
        }
        item("motionprefs") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Global motion", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                RuneBool("Effects (shaders, glow, loops)", fx) { fx = it; RuneMotionPrefs.effects = it; RuneMotionPrefs.save(c) }
                RuneNum("Animation speed", speed, 0.25f, 3f, 0.05f, speed != 1f, { speed = it; RuneMotionPrefs.speed = it; RuneMotionPrefs.save(c) }, { speed = 1f; RuneMotionPrefs.speed = 1f; RuneMotionPrefs.save(c) })
            }
        }
        item("mine-h") { Text("My designs", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 18.sp) }
        if (saved.isEmpty()) item("mine-e") { Text("Nothing yet. Start from a template below, or describe one above.", Modifier.padding(horizontal = 16.dp), color = m.onSurfaceVariant) }
        items(saved.size, key = { "d" + saved[it].optString("id") }) { i ->
            val rec = saved[i]; val rid = rec.optString("id"); val doc = rec.optJSONObject("doc") ?: JSONObject()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(com.nizam.app.ui.design.labShape(18.dp)).background(m.surfaceVariant).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(rec.optString("name"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("${RuneModel.count(doc.optJSONObject("root") ?: JSONObject())} parts", fontSize = 12.sp, color = m.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ open("nx/rune_$rid") }) { Text("Open") }
                    TextButton({ edit(RuneEditor(JSONObject(doc.toString()), rid)) }) { Text("Edit") }
                    TextButton({ RuneStore.duplicate(c, rid); saved = RuneStore.all(c) }) { Text("Copy") }
                    TextButton({ Share.text(c, doc.toString(), rec.optString("name")) }) { Text("Share") }
                    TextButton({ RuneStore.delete(c, rid); saved = RuneStore.all(c) }) { Text("Delete") }
                }
            }
        }
        item("tpl-h") { Text("Templates", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontWeight = FontWeight.SemiBold, fontSize = 18.sp) }
        item("tpl") {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(templates, key = { it.slug }) { t ->
                    Column(Modifier.clickable { edit(RuneEditor(JSONObject(t.doc.toString()), null).also { it.dirty = true }) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(width = 150.dp, height = 270.dp).clip(com.nizam.app.ui.design.labShape(18.dp)).border(1.dp, m.outline.copy(alpha = 0.3f), com.nizam.app.ui.design.labShape(18.dp))) {
                            Box(Modifier.requiredSize(360.dp, 648.dp).graphicsLayer { scaleX = 150f / 360f; scaleY = 150f / 360f; transformOrigin = TransformOrigin(0f, 0f) }) {
                                RuneView(t.doc, {}, Modifier.fillMaxSize())
                            }
                            Box(Modifier.fillMaxSize().clickable { edit(RuneEditor(JSONObject(t.doc.toString()), null).also { it.dirty = true }) })
                        }
                        Text(t.doc.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        item("end") { Spacer(Modifier.height(80.dp)) }
    }
}

// ───────────────────────── editor ─────────────────────────

@Composable
private fun RuneEditorScreen(ed: RuneEditor, open: (String) -> Unit, close: () -> Unit) {
    val c = LocalContext.current
    var tab by remember { mutableStateOf("Layers") }
    val m = MaterialTheme.colorScheme
    fun save(): String { val id = RuneStore.save(c, ed.id, ed.doc); ed.id = id; ed.dirty = false; return id }
    fun leave() { if (ed.dirty) save(); close() }
    BackHandler { leave() }
    val hooks = remember(ed.selected) { EditHooks(ed.selected) { id -> ed.selected = id } }
    Column(Modifier.fillMaxSize().background(m.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ leave() }) { Text("←") }
            OutlinedTextField(ed.doc.optString("name"), { v -> ed.edit("name") { d -> d.put("name", v.take(60)) } }, Modifier.weight(1f), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp))
            TextButton({ ed.undo() }, enabled = ed.canUndo) { Text("↶") }
            TextButton({ ed.redo() }, enabled = ed.canRedo) { Text("↷") }
            TextButton({ ed.replay++ }) { Text("▶") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ save(); RuneActions.toast(c, "Saved") }, Modifier.weight(1f)) { Text(if (ed.dirty) "Save" else "Saved") }
            OutlinedButton({ val id = save(); open("nx/rune_$id") }, Modifier.weight(1f)) { Text("Run full screen") }
        }
        Box(Modifier.fillMaxWidth().weight(0.9f).padding(10.dp).clip(com.nizam.app.ui.design.labShape(20.dp)).border(1.dp, m.outline.copy(alpha = 0.3f), com.nizam.app.ui.design.labShape(20.dp))) {
            RuneView(ed.doc, open, Modifier.fillMaxSize(), hooks, ed.replay)
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(TABS) { t -> RuneChip(t, tab == t) { tab = t } }
        }
        Column(Modifier.fillMaxWidth().weight(1.1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                "Layers" -> LayersTab(ed)
                "Add" -> AddTab(ed)
                "Style" -> InspectorTab(ed, setOf("Content", "Text", "Layout", "Surface", "Transform", "Behaviour"))
                "Motion" -> MotionTab(ed)
                "Theme" -> ThemeTab(ed)
                "Code" -> CodeTab(ed)
            }
        }
    }
}

@Composable
private fun LayersTab(ed: RuneEditor) {
    val m = MaterialTheme.colorScheme
    Text("Tap a layer (or tap in the preview) to select it. Use the buttons to arrange.", fontSize = 12.sp, color = m.onSurfaceVariant)
    RuneModel.flatten(ed.root).forEach { (n, depth) ->
        val id = n.optString("id"); val on = id == ed.selected
        val label = n.optString("t") + (n.optString("text").takeIf { it.isNotBlank() }?.let { " · " + it.take(22) } ?: "")
        Row(Modifier.fillMaxWidth().clip(com.nizam.app.ui.design.labShape(10.dp)).background(if (on) m.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { ed.selected = id }.padding(start = (depth * 14).dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp, color = if (on) m.primary else m.onSurface, maxLines = 1)
        }
        if (on && id != "root") Row(Modifier.padding(start = (depth * 14).dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton({ ed.move(id, -1) }) { Text("▲") }; TextButton({ ed.move(id, 1) }) { Text("▼") }
            TextButton({ ed.indent(id) }) { Text("→") }; TextButton({ ed.outdent(id) }) { Text("←") }
            TextButton({ ed.duplicate(id) }) { Text("⧉") }; TextButton({ ed.wrap(id, "box") }) { Text("▢") }
            TextButton({ ed.remove(id) }) { Text("✕", color = m.error) }
        }
    }
}

@Composable
private fun AddTab(ed: RuneEditor) {
    val m = MaterialTheme.colorScheme
    Text("Adds inside the selected container (or next to the selected item).", fontSize = 12.sp, color = m.onSurfaceVariant)
    Text("Ready-made blocks", fontWeight = FontWeight.SemiBold)
    RuneChoice(BLOCKS.map { it.first }, "") { name -> BLOCKS.firstOrNull { it.first == name }?.let { ed.add(it.second()) } }
    Text("Building parts", fontWeight = FontWeight.SemiBold)
    RuneChoice(RuneModel.TYPES, "") { t ->
        val n = when (t) {
            "text" -> RuneModel.node("text", "text" to "New text")
            "button" -> RuneModel.node("button", "text" to "Button")
            "chip" -> RuneModel.node("chip", "text" to "Chip")
            "icon" -> RuneModel.node("icon", "name" to "star", "size" to 32)
            "image" -> RuneModel.node("image", "url" to "https://picsum.photos/400/300", "w" to 200, "h" to 140, "radius" to 16)
            "grid" -> RuneModel.node("grid", "cols" to 2, "gap" to 10)
            "card" -> RuneModel.node("card")
            "progress" -> RuneModel.node("progress", "value" to "0.6", "thick" to 10)
            "shape" -> RuneModel.node("shape", "shape" to "circle", "fill" to "lin:135:@primary,@accent")
            "repeat" -> RuneModel.node("repeat", "items" to "One|Two|Three", "gap" to 8).also { r -> RuneModel.setKids(r, listOf(RuneModel.node("text", "text" to "{{n}}. {{item}}"))) }
            "toggle" -> RuneModel.node("toggle", "bind" to "flag", "text" to "Switch")
            "slider" -> RuneModel.node("slider", "bind" to "level")
            "input" -> RuneModel.node("input", "bind" to "text", "hint" to "Type here")
            else -> RuneModel.node(t)
        }
        ed.add(n)
    }
}

@Composable
private fun InspectorTab(ed: RuneEditor, groups: Set<String>) {
    val n = ed.sel
    val m = MaterialTheme.colorScheme
    if (n == null) { Text("Select something in the preview or in Layers to style it.", color = m.onSurfaceVariant); return }
    val theme = ed.theme ?: appRuneTheme()
    val t = n.optString("t")
    Text("$t  ·  ${n.optString("id")}", fontSize = 12.sp, color = m.onSurfaceVariant)
    val defs = RuneProps.forType(t)
    listOf("Content", "Text", "Layout", "Surface", "Transform", "Behaviour").filter { it in groups }.forEach { g ->
        val list = defs.filter { it.group == g }
        if (list.isNotEmpty()) RuneSection(g, open0 = g == "Content" || g == "Surface") {
            list.forEach { d -> RunePropEditor(d, n, theme) { k, v -> ed.setProp(k, v) } }
        }
    }
}

@Composable
private fun MotionTab(ed: RuneEditor) {
    val c = LocalContext.current
    val m = MaterialTheme.colorScheme
    var speed by remember { mutableStateOf(RuneMotionPrefs.speed) }
    Button({ ed.replay++ }, Modifier.fillMaxWidth()) { Text("▶  Replay animations") }
    RuneNum("Global speed", speed, 0.25f, 3f, 0.05f, speed != 1f, { speed = it; RuneMotionPrefs.speed = it; RuneMotionPrefs.save(c); ed.replay++ }, { speed = 1f; RuneMotionPrefs.speed = 1f; RuneMotionPrefs.save(c) })
    val n = ed.sel
    if (n == null) { Text("Select a part to give it an entrance, a loop, a press effect and a spring feel.", color = m.onSurfaceVariant); return }
    val theme = ed.theme ?: appRuneTheme()
    RuneProps.forType(n.optString("t")).filter { it.group == "Motion" }.forEach { d -> RunePropEditor(d, n, theme) { k, v -> ed.setProp(k, v); if (k.startsWith("enter")) ed.replay++ } }
}

@Composable
private fun ThemeTab(ed: RuneEditor) {
    val c = LocalContext.current
    val scope = rememberSafeScope()
    val m = MaterialTheme.colorScheme
    val own = ed.doc.optJSONObject("theme")
    val cur = ed.theme ?: appRuneTheme()
    RuneBool("This design has its own theme", own != null) { on ->
        ed.edit { d -> if (on) d.put("theme", JSONObject().put("bg", RuneColor.hex(cur.surface)).put("accent", RuneColor.hex(cur.primary)).put("accent2", RuneColor.hex(cur.accent))) else d.remove("theme") }
    }
    if (own != null) {
        Text("Presets", fontWeight = FontWeight.SemiBold)
        RuneChoice(RunePresets.themes.map { it.name }, "") { nme ->
            RunePresets.themes.firstOrNull { it.name == nme }?.let { p -> ed.edit { d -> d.put("theme", JSONObject().put("bg", p.bg).put("accent", p.accent).put("accent2", p.accent2).apply { if (p.text.isNotBlank()) put("text", p.text) }) } }
        }
        fun th(k: String, v: String) = ed.edit("theme:$k") { d -> val t = d.optJSONObject("theme") ?: JSONObject().also { d.put("theme", it) }; if (v.isEmpty()) t.remove(k) else t.put(k, v) }
        RuneColorField("Background", own.optString("bg"), cur) { th("bg", it) }
        RuneColorField("Main accent", own.optString("accent"), cur) { th("accent", it) }
        RuneColorField("Second accent", own.optString("accent2"), cur) { th("accent2", it) }
        RuneColorField("Text (empty = automatic)", own.optString("text"), cur) { th("text", it) }
        RuneNum("Corner roundness", own.optDouble("radius", 1.0).toFloat(), 0f, 2.5f, 0.05f, own.has("radius"), { v -> ed.edit("theme:r") { d -> d.optJSONObject("theme")?.put("radius", v.toDouble()) } }, { ed.edit { d -> d.optJSONObject("theme")?.remove("radius") } })
    }
    Text("Contrast: text ${"%.1f".format(RuneColor.contrast(cur.ink, cur.surface))}:1 · accent ${"%.1f".format(RuneColor.contrast(cur.primary, cur.surface))}:1 (text is always pushed to at least 7:1, muted text 4.5:1)", fontSize = 12.sp, color = m.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(cur.surface, cur.card, cur.ink, cur.muted, cur.primary, cur.accent).forEach { col -> Box(Modifier.size(34.dp).clip(com.nizam.app.ui.design.labShape(10.dp)).background(col).border(1.dp, m.outline.copy(alpha = 0.3f), com.nizam.app.ui.design.labShape(10.dp))) }
    }
    Button({
        scope.launch {
            val ct = com.nizam.app.feature.omni.O.container(c)
            ct.settingsRepository.setCustomPalette(cur.toPaletteJson(ed.doc.optString("name", "Rune")))
            ct.settingsRepository.setAccent("custom")
            RuneActions.toast(c, "Applied to all of Atlas")
        }
    }, Modifier.fillMaxWidth()) { Text("Use this theme for all of Atlas") }
}

@Composable
private fun CodeTab(ed: RuneEditor) {
    val c = LocalContext.current
    val scope = rememberSafeScope()
    val m = MaterialTheme.colorScheme
    var text by remember(ed.doc) { mutableStateOf(runCatching { ed.doc.toString(1) }.getOrDefault("")) }
    var err by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Text("Ask Atlas to change this design", fontWeight = FontWeight.SemiBold)
    OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("e.g. make it calmer, add a Quran streak card, use gold accents") }, minLines = 2)
    Button({
        busy = true; err = ""
        scope.launch { val d = RuneAi.design(c, prompt, ed.doc); busy = false; if (d == null) err = "Couldn't change it. Try again." else { ed.replace(d); prompt = "" } }
    }, Modifier.fillMaxWidth(), enabled = !busy && prompt.isNotBlank()) { Text(if (busy) "Working…" else "Apply with Atlas") }
    Text("Design code (edit anything)", fontWeight = FontWeight.SemiBold)
    RuneText("JSON", text, long = true, mono = true) { text = it }
    if (err.isNotEmpty()) Text(err, color = m.error, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ val j = runCatching { JSONObject(text) }.getOrNull(); if (j == null || !j.has("root")) err = "That isn't a valid design." else { err = ""; ed.replace(j) } }, Modifier.weight(1f)) { Text("Apply code") }
        OutlinedButton({ runCatching { (c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Rune", ed.doc.toString())) }; RuneActions.toast(c, "Copied") }, Modifier.weight(1f)) { Text("Copy") }
        OutlinedButton({ Share.text(c, ed.doc.toString(), ed.doc.optString("name")) }, Modifier.weight(1f)) { Text("Share") }
    }
    Text("Properties and values: fills like lin:135:#6C8CFF,@accent · mesh:… · aurora:… · liquid:…; bindings like {{time}} {{m:prayers}}; taps like open:atlasos ;; haptic.", fontSize = 12.sp, color = m.onSurfaceVariant)
}

/** Atlas designs for you: it writes the same JSON you can edit by hand, then it is checked and cleaned before use. */
object RuneAi {
    private const val GRAMMAR = """You design phone screens as JSON for the Atlas Rune engine. Output only one JSON object:
{"v":1,"name":"short title","theme":{"bg":"#hex","accent":"#hex","accent2":"#hex","text":"#hex optional"},"vars":{},"root":{node}}
A node is {"id":"unique","t":TYPE, ...props..., "kids":[nodes]} (kids only for container types).
TYPES: column,row,box(stacked layers),card,grid(cols),hscroll,vscroll,repeat(items:"a|b|c", first kid is the template using {{item}} {{n}}),text,icon,image,button,chip,progress,chart,toggle,slider,input,shape,divider,spacer.
Layout props: fillW,fillH (true), w,h (dp), weightF (share of a row/column), aspect, padding,padH,padV,margin, gap, align(start|center|end|stretch), justify(start|center|end|between|around|evenly), boxAlign(topstart|top|topend|start|center|end|bottomstart|bottom|bottomend), scroll(true for tall columns), offX,offY,scale,rotate,alpha.
Surface props: fill, radius, borderW, border, glass(true), fx(grain|shimmer|scan|sparkle|gloss|vignette|frost), fxSpeed, glow(dp), glowC, elev, blur (for soft decorative shapes), clip.
FILL strings: "#RRGGBB", "#AARRGGBB", "@primary|@accent|@ink|@muted|@card|@surface" (add /NN for alpha, e.g. "@ink/60"), "lin:135:#a,#b,#c", "rad:#a,#b", "sweep:#a,#b,#a", "mesh:#a,#b,#c,#d" (animated), "aurora:#a,#b,#c" (animated), "liquid:#a,#b,#c" (animated GPU), "none".
Text props: text, size(sp), weight(100-900), color (fill-style colour), font(sans|serif|mono|cursive), talign, italic, upper, spacing, lineH, maxLines, tfill (gradient fill for text).
Other: icon{name: ANY Material icon name in snake_case or PascalCase e.g. "favorite","mosque","local_fire_department","rocket_launch" (thousands exist), istyle: outlined|filled|rounded|sharp|twotone, OR emoji:"🌙", OR svg:"<path d data>" with vb (box size, default 24) and sfill}; icons can be badges: give them fill, radius, padding, progress{value:"0.6" or "{{m:prayers}}/5", pstyle:bar|ring|arc|dots, thick, text}, chart{values:"1,2,3",cstyle:bar|line|area|dots}, shape{shape:circle|rect|blob|ring|diamond|pill}, toggle/slider/input{bind:"varname"}.
Motion props: enter(fade|rise|drop|left|right|scale|pop|flip|spin|bounce|blurzoom|swing), enterMs, enterDelay, stagger (on a container: delays children in turn), loop(float|pulse|breathe|spin|wiggle|swing|heartbeat|glow|drift), loopMs, loopAmt, press(squish|lift|dip|glow|tilt), spring(snappy|soft|bouncy|slow|heavy|elastic).
Live values in any text: {{time}} {{time12}} {{sec}} {{date}} {{day}} {{hijri}} {{greeting}} {{battery}} {{m:tasks_open}} {{m:tasks_done}} {{m:prayers}} {{m:spent}} {{m:income}} {{m:habits}} {{m:water_ml}} {{m:gym_sets}} {{m:mood}} {{v:varname}}.
Taps ("on"): open:atlasos | open:themestudio | open:rune | url:https://… | copy:text | share:text | set:var=value | toggle:var | inc:var | toast:text | haptic | ask:question (answer appears in {{v:answer}}); chain with ;; . showIf: "varname" or "!varname" or "var==value".
Rules: be beautiful and specific. Use a clear hierarchy, generous spacing, 1-2 accent colours, animated fills (mesh/aurora/liquid) for hero areas only, glass cards over gradients, enter animations with stagger, readable contrast. The root should be a column or box with fillW and fillH true. Never invent numbers: use live values or leave placeholders. 25 to 80 nodes."""

    suspend fun design(c: android.content.Context, request: String, base: JSONObject?): JSONObject? {
        val prompt = buildString {
            append(GRAMMAR).append("\n\n")
            if (base != null) append("Current design (change it as asked, keep what is not mentioned):\n").append(base.toString().take(14000)).append("\n\n")
            append("Request: ").append(request)
        }
        val j = com.nizam.app.feature.omni.O.aiJson(c, prompt, 6000) ?: return null
        if (!j.has("root")) return null
        return RuneModel.sanitize(j)
    }
}
