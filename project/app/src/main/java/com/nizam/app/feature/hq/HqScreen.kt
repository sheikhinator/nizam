package com.nizam.app.feature.hq

import androidx.compose.runtime.collectAsState

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agent.Crew
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Turns the status hub's picture into what the 3D scene needs. */
private fun stateOf(o: JSONObject): HqState {
    fun arr(k: String) = o.optJSONArray(k) ?: JSONArray()
    val ag = arr("agents").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map {
        HqAgent(it.optString("id"), it.optString("name"), it.optString("emoji"), hexColor(it.optString("color")), it.optInt("room"), it.optInt("slot"),
            it.optString("status"), it.optString("pose"), it.optString("card"), it.optString("line"), it.optString("say"),
            it.optInt("level", 1), it.optString("persona", "careful"), it.optString("dept", "research"), it.optString("visit"), it.optBoolean("urgent"))
    }
    val plugs = arr("plugs").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    val meet = o.optJSONObject("meeting")
    return HqState(
        agents = ag, needs = arr("needs").let { a -> (0 until a.length()).map { a.optString(it) } },
        sky = o.optString("sky", "DAY"), hour = o.optDouble("hour", 12.0).toFloat(), weather = o.optString("weather", "clear"),
        event = if (o.isNull("event")) null else o.optString("event"), salah = o.optBoolean("salah"), qibla = o.optDouble("qibla", 255.0).toFloat(),
        loadshed = o.optBoolean("loadshed"), low = o.optBoolean("low"),
        meeting = meet?.optJSONArray("members")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        speaking = meet?.optString("speaking")?.ifBlank { null }, jar = o.optDouble("jar", 1.0).toFloat(),
        approvals = o.optInt("approvals"), changes = o.optInt("changes"), pcOnline = o.optBoolean("pcOnline"), celebrate = o.optInt("celebrate", -1),
        summary = o.optString("summary"), prayer = o.optString("prayer"), routine = o.optString("routine"),
        plugsOk = plugs.count { it.optBoolean("ok", false) }, plugsTotal = plugs.size, plugsKnown = o.optBoolean("plugsKnown"),
        topic = meet?.optString("topic").orEmpty(), thread = meet?.optString("thread")?.ifBlank { null },
        log = meet?.optJSONArray("log")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("who") to it.optString("text") } } ?: emptyList(),
        open = o.optBoolean("open", true), escalate = o.optBoolean("escalate", true), idleLife = o.optBoolean("idleLife", true),
        friends = o.optJSONObject("friends")?.let { f -> f.keys().asSequence().associateWith { k -> f.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList() } } ?: emptyMap(),
        leaving = arr("leaving").let { a -> (0 until a.length()).map { a.optString(it) } },
        meetingKind = meet?.optString("kind").orEmpty(), gathering = meet?.optBoolean("gathering") == true, boardText = o.optString("board"))
}

private class Confetti(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var r: Float)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HqScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current.density
    val work = rememberWorkScope()
    val scene = remember { HqScene(Hq.ROOMS.map { HqRoomDef(it.name, it.emoji, hexColor(it.color)) }).also { it.cardW = 170f * density; it.cardH = 44f * density; it.sayH = 64f * density
        it.topInset = 150f * density; it.bottomInset = 150f * density } }
    val renderer = remember { Renderer() }
    val paint = remember { android.graphics.Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true } }
    val path = remember { android.graphics.Path() }
    var tick by remember { mutableLongStateOf(0L) }
    var loaded by remember { mutableStateOf(false) }
    var labelsVersion by remember { mutableIntStateOf(0) }
    var room by remember { mutableIntStateOf(0) }
    var explore by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Pair<String, String>?>(null) }
    var views by remember { mutableStateOf<List<Hq.View>>(emptyList()) }
    var summary by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(HqState()) }
    var needIdx by remember { mutableIntStateOf(0) }
    var act by remember { mutableStateOf<Pair<String, Pair<String, String>>?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    val confetti = remember { ArrayList<Confetti>() }
    var chatLine by remember { mutableStateOf("") }
    var hud by remember { mutableStateOf(Triple("", "", "")) }          // clock, floor, objective
    var chosenTarget by remember { mutableStateOf<String?>(null) }
    val version = Crew.version

    fun onPick(k: String, id: String) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        when (k) {
            "pet" -> toast = "🐶 Good dog!"
            "gong" -> toast = "🔔 Gong!"
            "screen" -> sheet = "company" to "overview"
            "coffee" -> toast = "☕ Fresh chai… ahh"
            "shelf" -> toast = "📖 A quiet moment with a good book"
            "board" -> sheet = "company" to "board"
            "company" -> sheet = "company" to "overview"
            "seat" -> scene.spots.firstOrNull { it.kind == "seat" && it.id == id && it.floor == scene.player.floor }?.let { scene.sit(it); toast = "🪑 Sitting down — push the stick to get up" }
                ?: run { sheet = null }
            "agent" -> { chosenTarget = id; sheet = k to id }
            else -> sheet = k to id
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1020))) {
        // the office itself, drawn by Atlas's own 3D renderer
        Canvas(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scene.drag(pan.x, pan.y); if (zoom != 1f) scene.pinch(zoom); lastTouch = System.nanoTime()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { o -> lastTouch = System.nanoTime(); scene.pick(o.x, o.y, 56f * density)?.let { (k, id) -> onPick(k, id) } }
            }) {
            @Suppress("UNUSED_VARIABLE") val frameTick = tick
            drawRect(Brush.verticalGradient(listOf(Color(0xFF000000.toInt() or scene.skyTop), Color(0xFF000000.toInt() or scene.skyBottom)), endY = size.height * 0.55f))
            if (scene.stars) for (i in 0 until 70) {
                val sx = ((i * 7919) % 1000) / 1000f * size.width; val sy = ((i * 104729) % 1000) / 1000f * size.height * 0.4f
                drawCircle(Color.White.copy(alpha = 0.7f), radius = 1.2f * density, center = Offset(sx, sy))
            }
            if (!loaded) return@Canvas
            scene.render(renderer, size.width, size.height)
            val f = renderer.frame
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    nc.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, f.tris * 6, f.verts, 0, null, 0, f.colors, 0, null, 0, 0, paint)
                } else {
                    // older Android can't draw coloured vertices with hardware acceleration: one small path per triangle
                    val fill = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.FILL }
                    for (i in 0 until f.tris) {
                        val o = i * 6
                        path.rewind(); path.moveTo(f.verts[o], f.verts[o + 1]); path.lineTo(f.verts[o + 2], f.verts[o + 3]); path.lineTo(f.verts[o + 4], f.verts[o + 5]); path.close()
                        fill.color = f.colors[i * 3]; nc.drawPath(path, fill)
                    }
                }
            }
            if (scene.raining) {
                val t = (System.nanoTime() / 1_000_000L % 100_000L) / 1000f
                for (i in 0 until 90) {
                    val x0 = ((i * 7919) % 1000) / 1000f * size.width
                    val y0 = ((((i * 104729) % 1000) / 1000f + t * 1.4f) % 1f) * size.height
                    drawLine(Color(0x99AECBFF), Offset(x0, y0), Offset(x0 - 3 * density, y0 + 14 * density), strokeWidth = 1.2f * density)
                }
            }
            for (p in confetti) drawRect(Color(p.color), topLeft = Offset(p.x, p.y), size = androidx.compose.ui.geometry.Size(5 * density, 8 * density))
        }

        // agents' cards, bubbles and signs, pinned to the 3D positions every frame
        val labelList = remember(labelsVersion) { scene.labels.values.toList() }
        for (l in labelList) key(l.key) {
                Box(Modifier.layout { m, cons ->
                    val p = m.measure(cons.copy(minWidth = 0, minHeight = 0))
                    layout(0, 0) {
                        @Suppress("UNUSED_VARIABLE") val frameTick = tick
                        if (l.shown) p.place((l.x - p.width / 2).toInt(), (l.y - p.height).toInt())
                    }
                }) { HqLabelChip(l) { if (l.kind == "agent" || l.kind == "say") onPick("agent", l.id) } }
        }

        // a soft shade behind the top controls so they read over any sky
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))

        // explore: a game-style HUD with a minimap, the time, and where to go next
        if (explore) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            MiniMapView(scene, tick, Modifier.size(118.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(hud.first, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)))
                Text(hud.second, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)))
                if (hud.third.isNotBlank()) Text(hud.third, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCCB45309)).padding(horizontal = 8.dp, vertical = 5.dp))
                val team = live.agents.count { a -> scene.agents[a.id]?.away == false }
                Text("👥 $team in · ${live.approvals} waiting · 🫙 ${(live.jar * 100).toInt()}%", color = Color(0xFFCBD5E1), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)))
            }
            RoundButton("🎥", "Leave", small = true) { explore = false; scene.switchMode("diorama") }
        }
        if (!explore) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Atlas HQ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(summary.ifBlank { "Opening the office…" }, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundButton(if (explore) "🎥" else "🚶", if (explore) "Diorama" else "Explore") {
                    explore = !explore; scene.switchMode(if (explore) "explore" else "diorama")
                    if (explore) Feedback.show("Explore: the stick walks, drag to look, walk up to things")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Hq.ROOMS.forEachIndexed { i, r ->
                    val here = views.filter { it.room == i }
                    val alert = here.any { it.status == "needs" }
                    val busy = here.count { it.status == "working" }
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (room == i) Color.White else Color(0xCC111827))
                        .border(if (alert) 2.dp else 0.dp, if (alert) Color(0xFFEF4444) else Color.Transparent, RoundedCornerShape(50))
                        .clickable { room = i; scene.goFloor(i) }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text("${r.emoji} ${r.name}" + (if (busy > 0) " · $busy" else ""), color = if (room == i) Color(0xFF111827) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // right rail
        if (!explore) Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundButton("🏢", "Company", small = true) { sheet = "company" to "overview" }
            RoundButton("🤝", "Table", small = true) { sheet = "table" to "" }
            RoundButton("📥", "Inbox", small = true, badge = live.approvals) { sheet = "inbox" to "" }
            RoundButton("🧾", "Changes", small = true) { sheet = "changes" to "" }
            RoundButton("📦", "Made", small = true, badge = com.nizam.app.feature.studio.Deliverables.jobs.collectAsState().value.size) { open("deliverables") }
            RoundButton("⏪", "Replay", small = true) { sheet = "replay" to "" }
        }

        // explore controls: a stick, a jump button and whatever you're standing next to
        if (explore) {
            var knob by remember { mutableStateOf(Offset.Zero) }
            Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 80.dp).size(130.dp).clip(CircleShape)
                .background(Color(0x22FFFFFF)).border(2.dp, Color(0x55FFFFFF), CircleShape)
                .pointerInput(Unit) {
                    val r = size.width / 2f
                    fun set(o: Offset) {
                        var x = (o.x - r) / r; var y = (o.y - r) / r
                        val l = kotlin.math.hypot(x, y); if (l > 1f) { x /= l; y /= l }
                        scene.joyX = x; scene.joyY = y; knob = Offset(x, y); lastTouch = System.nanoTime()
                    }
                    detectDragGestures(onDragStart = { set(it) }, onDragEnd = { scene.joyX = 0f; scene.joyY = 0f; knob = Offset.Zero },
                        onDragCancel = { scene.joyX = 0f; scene.joyY = 0f; knob = Offset.Zero }) { ch, _ -> set(ch.position) }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.offset((knob.x * 40).dp, (knob.y * 40).dp).size(52.dp).clip(CircleShape).background(Color(0xAAFFFFFF)))
            }
            if (kotlin.math.hypot(knob.x, knob.y) > 0.88f) Text("RUN", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 218.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCCEF4444)).padding(horizontal = 6.dp, vertical = 2.dp))
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 84.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundButton("📣", "Meeting", small = true) { sheet = "table" to "" }
                    RoundButton("🏢", "Company", small = true) { sheet = "company" to "overview" }
                }
                Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0x33FFFFFF)).border(2.dp, Color(0x55FFFFFF), CircleShape)
                    .clickable { scene.jump() }, contentAlignment = Alignment.Center) { Text(if (scene.player.sitting) "🧍" else "⤒", color = Color.White, fontSize = 24.sp) }
                act?.let { (label, target) ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xF26366F1)).clickable {
                        if (target.first == "pet") { scene.petDog(0f); toast = "🐶 Good dog!" }
                        else if (target.first == "gong") { scene.celebrate() } else onPick(target.first, target.second)
                    }.padding(horizontal = 18.dp, vertical = 12.dp)) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1) }
                }
            }
        }

        toast?.let { tx ->
            Text(tx, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 120.dp)
                .clip(RoundedCornerShape(50)).background(Color(0xCC0F1223)).padding(horizontal = 14.dp, vertical = 8.dp))
            LaunchedEffect(tx) { com.nizam.app.ui.design.guard { delay(2200); toast = null } }
        }

        // the team conversation happening right now, as it happens
        if (live.log.isEmpty() && chatLine.isNotBlank() && !explore) {
            Text("💬 $chatLine", color = Color(0xFFE2E8F0), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 70.dp, bottom = 72.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xCC0F172A)).clickable { sheet = "company" to "chat" }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
        if (live.log.isNotEmpty() && !explore) {
            Column(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 70.dp, bottom = 70.dp).fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Color(0xE60F172A))
                .clickable { open("nx/crew") }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                    Spacer(Modifier.width(6.dp))
                    Text("Live · " + live.topic.ifBlank { "Team at work" }, color = Color(0xFFA5B4FC), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                live.log.takeLast(3).forEach { (who, text) ->
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append("$who  ") }
                        withStyle(SpanStyle(color = Color(0xFFCBD5E1))) { append(text) }
                    }, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }

        // bottom: who needs me
        val waiting = Hq.needs(views)
        val urgent = waiting.count { it.status == "needs" }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
            .shadow(10.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
            .background(if (urgent > 0) Color(0xFFEF4444) else if (waiting.isNotEmpty()) Color(0xFF16A34A) else Color(0xE6111827))
            .clickable {
                if (waiting.isEmpty()) { Feedback.show("Nobody needs you right now"); return@clickable }
                val v = waiting[needIdx % waiting.size]; needIdx++
                chosenTarget = v.id
                if (explore) toast = "📍 ${v.emoji} ${v.name} marked — follow the marker" else { scene.focusAgent(v.id); room = scene.view.floor; sheet = "agent" to v.id }
            }.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(when {
                urgent > 0 -> "✋ Who needs me · $urgent"
                waiting.isNotEmpty() -> "✅ ${waiting.size} finished · read"
                else -> "☕ All quiet"
            }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }

    // stop drawing whenever the screen isn't showing
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> when (e) { Lifecycle.Event.ON_PAUSE -> active = false; Lifecycle.Event.ON_RESUME -> active = true; else -> {} } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // the frame loop: 60 fps while you touch it, 30 when idle, 20 on battery saver
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        var last = 0L; var acc = 0f; var celebrations = scene.celebrations; var labelKeys = 0
        val rnd = java.util.Random()
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                if (!active || !loaded) return@withFrameNanos
                val touching = explore || now - lastTouch < 4_000_000_000L || confetti.isNotEmpty()
                val fps = if (scene.state.low) 20f else if (touching) 60f else 30f
                acc += dt
                if (acc < 1f / fps - 0.002f) return@withFrameNanos
                val step = acc.coerceAtMost(0.1f); acc = 0f
                scene.step(step)
                if (scene.celebrations != celebrations) {
                    celebrations = scene.celebrations
                    val w = view.width.toFloat()
                    repeat(140) { confetti += Confetti(rnd.nextFloat() * w, -rnd.nextFloat() * 300f, (rnd.nextFloat() - 0.5f) * 120f, 150f + rnd.nextFloat() * 250f,
                        intArrayOf(0xFFEF4444.toInt(), 0xFFF59E0B.toInt(), 0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFFA855F7.toInt(), 0xFFEC4899.toInt())[it % 6], 0f) }
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    toast = "🎉 " + (scene.celebrateText.ifBlank { "Done!" })
                }
                if (confetti.isNotEmpty()) {
                    val hgt = view.height.toFloat()
                    confetti.forEach { it.x += it.vx * step; it.y += it.vy * step }
                    confetti.removeAll { it.y > hgt }
                }
                val a = when {
                    !explore -> null
                    scene.nearDog -> "🐶 Pat the dog" to ("pet" to "")
                    scene.nearAgent != null -> scene.agents[scene.nearAgent]?.let { "💬 ${it.a.emoji} ${it.a.name}" to ("agent" to it.a.id) }
                    scene.nearSpot != null -> scene.nearSpot!!.let { it.label to (it.kind to it.id) }
                    else -> null
                }
                if (a != act) act = a
                // a label appeared or went away: rebuild the label layer (rare)
                val kh = scene.labels.keys.hashCode()
                if (kh != labelKeys) { labelKeys = kh; labelsVersion++ }
                scene.hint?.let { toast = it; scene.hint = null; if (!explore) room = scene.view.floor }
                if (explore && scene.player.floor != room) room = scene.player.floor
                scene.waypoint = chosenTarget?.takeIf { scene.agents.containsKey(it) } ?: scene.state.needs.firstOrNull()
                if (explore && (now / 250_000_000L) % 2 == 0L) {
                    val clock = java.time.LocalTime.now(); val day = java.time.LocalDate.now().dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                    val fl = Hq.ROOMS.getOrNull(scene.player.floor)
                    val obj = scene.waypoint?.let { id -> scene.distanceTo(id)?.let { (d, df) ->
                        val who = scene.agents[id]?.a?.let { "${it.emoji} ${it.name}" } ?: ""
                        val why = if (scene.state.needs.contains(id)) "needs you" else "is waiting"
                        if (df != 0) "📍 $who $why · ${if (df > 0) "up" else "down"} ${kotlin.math.abs(df)} floor${if (kotlin.math.abs(df) > 1) "s" else ""} — take the elevator" else "📍 $who $why · ${d.toInt()} m"
                    } } ?: ""
                    val h = Triple("%d:%02d  %s".format(clock.hour, clock.minute, day), "${fl?.emoji ?: ""} ${fl?.name ?: ""}" + if (!scene.state.open) "  ·  after hours" else "", obj)
                    if (h != hud) hud = h
                }
                tick = now
            }
        }
    } }

    // feed the office: a fresh picture every 1.5 s
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        while (true) {
            val snap = runCatching { withContext(Dispatchers.IO) { Hq.snapshot(container) } }.getOrNull()
            if (snap != null) {
                val st = stateOf(snap)
                scene.celebrateText = snap.optString("celebrateText")
                scene.update(st)
                live = st
                if (!loaded) { scene.render(renderer, view.width.toFloat().coerceAtLeast(1f), view.height.toFloat().coerceAtLeast(1f)); loaded = true }
                views = Hq.lastViews
                summary = snap.optString("summary")
                val feed = snap.optJSONArray("chat")
                val last = if (feed != null && feed.length() > 0) feed.getJSONObject(feed.length() - 1) else null
                chatLine = scene.chatter.lastOrNull() ?: last?.let { it.optString("from") + ": " + it.optString("text") }.orEmpty()
            }
            delay(1500)
        }
    } }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { withContext(Dispatchers.IO) { Hq.refreshPlugs(c) } } }
    LaunchedEffect(version) { com.nizam.app.ui.design.guard { views = withContext(Dispatchers.IO) { Hq.views(c) } } }

    // ───────────── sheets ─────────────
    val s = sheet ?: return
    when (s.first) {
        "jar" -> { sheet = null; open("nx/agentbudget") }
        "plugs" -> { sheet = null; open("nx/connectors") }
        "routines" -> { sheet = null; open("schedules") }
        "pc" -> { sheet = null; open("nx/laptop") }
        else -> ModalBottomSheet(onDismissRequest = { sheet = null; scene.view.focus = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                when (s.first) {
                    "agent" -> AgentSheet(container, s.second, views, open) { sheet = null }
                    "elevator" -> {
                        Text("🛗 Elevator", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        Hq.ROOMS.forEachIndexed { i, r ->
                            val here = views.filter { it.room == i }
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { room = i; scene.goFloor(i); sheet = null }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${r.emoji}  ${r.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(here.joinToString(" ") { it.emoji }, fontSize = 16.sp)
                            }
                        }
                    }
                    "table" -> TableSheet(container, views) { sheet = null }
                    "company" -> CompanySheet(container, s.second.ifBlank { "overview" }, scene.chatter.toList(), { id -> sheet = "agent" to id }, { sheet = "hire" to "" }, open)
                    "hire" -> HireSheet(container) { sheet = null }
                    "inbox" -> InboxSheet(container)
                    "changes" -> ChangesSheet(container)
                    "replay" -> HqReplaySheet(container)
                }
            }
        }
    }
    // mark finished work as read once you open it
    if (s.first == "agent") LaunchedEffect(s.second) { com.nizam.app.ui.design.guard {
        views.firstOrNull { it.id == s.second && it.status == "done" }?.thread?.let { work.launch(Dispatchers.IO) { Hq.markSeen(c, it); Crew.version++ } }
    } }
}

/** A game-style minimap: the floor turns with you, people are dots, and a marker shows where to go. */
@Composable
private fun MiniMapView(scene: HqScene, tick: Long, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Canvas(modifier.shadow(8.dp, shape).clip(shape).background(Color(0xE60B1220)).border(2.dp, Color(0x66FFFFFF), shape)) {
        @Suppress("UNUSED_VARIABLE") val t = tick
        val mm = scene.minimap()
        val scale = size.minDimension / 10f
        val cx = size.width / 2; val cy = size.height / 2
        val cy0 = kotlin.math.cos(mm.yaw); val sy0 = kotlin.math.sin(mm.yaw)
        fun sx(x: Float, z: Float) = cx + ((x - mm.px) * -cy0 + (z - mm.pz) * sy0) * scale
        fun sy(x: Float, z: Float) = cy - ((x - mm.px) * sy0 + (z - mm.pz) * cy0) * scale
        fun poly(x0: Float, x1: Float, z0: Float, z1: Float, color: Color) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(sx(x0, z0), sy(x0, z0)); lineTo(sx(x1, z0), sy(x1, z0)); lineTo(sx(x1, z1), sy(x1, z1)); lineTo(sx(x0, z1), sy(x0, z1)); close()
            }
            drawPath(p, color)
        }
        poly(-HqScene.W / 2, HqScene.W / 2, -HqScene.D / 2, HqScene.D / 2, Color(0xFF1E293B))
        if (mm.floor == 0) poly(-40f, 40f, HqScene.D / 2, 30f, Color(0xFF14361F))
        for (s in mm.solids) poly(s[0], s[1], s[2], s[3], Color(0xFF475569))
        poly(-6.7f, -5.1f, -4.8f, -3.4f, Color(0xFF64748B))                                                   // elevator
        if (mm.dogHere) drawCircle(Color(0xFFD9A066), 3.dp.toPx(), Offset(sx(mm.dogX, mm.dogZ), sy(mm.dogX, mm.dogZ)))
        for (d in mm.dots) {
            val o = Offset(sx(d.x, d.z), sy(d.x, d.z))
            drawCircle(Color(0xFF000000.toInt() or (HqScene.STATUS[d.status] ?: 0x9CA3AF)), 5.5f.dp.toPx(), o)
            drawCircle(Color(0xFF000000.toInt() or d.color), 3.8f.dp.toPx(), o)
        }
        // where to go
        val tg = mm.target
        if (tg != null) {
            val onFloor = mm.targetFloor == mm.floor
            val tx = if (onFloor) tg[0] else -5.9f; val tz = if (onFloor) tg[1] else -3.2f
            var ox = sx(tx, tz) - cx; var oy = sy(tx, tz) - cy
            val lim = size.minDimension / 2 - 10.dp.toPx()
            val l = kotlin.math.hypot(ox, oy); if (l > lim) { ox = ox / l * lim; oy = oy / l * lim }
            val c = Offset(cx + ox, cy + oy); val r = 6.dp.toPx()
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }, Color(0xFFFBBF24))
        }
        // you: an arrow pointing the way you face
        val r = 7.dp.toPx()
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(cx, cy - r); lineTo(cx + r * 0.7f, cy + r * 0.7f); lineTo(cx, cy + r * 0.3f); lineTo(cx - r * 0.7f, cy + r * 0.7f); close() }, Color(0xFFF97316))
        drawCircle(Color.White.copy(alpha = 0.25f), size.minDimension / 2 - 2.dp.toPx(), Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

/** A card over an agent's head, a speech bubble, a floor sign or a wall board. */
@Composable
private fun HqLabelChip(l: HqLabel, onClick: () -> Unit) {
    when (l.kind) {
        "agent" -> {
            val dot = Color(0xFF000000.toInt() or (HqScene.STATUS[l.status] ?: 0x9CA3AF))
            Column(Modifier.widthIn(max = 170.dp).shadow(4.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Color(0xEEFFFFFF))
                .border(if (l.status == "needs") 2.dp else 0.dp, if (l.status == "needs") Color(0xFFEF4444) else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(5.dp))
                    Text(l.text, color = Color(0xFF0F172A), fontSize = if (l.near) 11.sp else 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (l.level >= 0 && l.near) {
                        Spacer(Modifier.width(4.dp))
                        Text(HqScene.LEVEL_NAMES[l.level.coerceIn(0, 4)], color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF000000.toInt() or HqScene.LEVEL_COLORS[l.level.coerceIn(0, 4)])).padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                    if (l.urgent) Text(" 🔥", fontSize = 10.sp)
                }
                if (l.sub.isNotBlank()) Text(l.sub, color = Color(0xFF334155), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (l.line.isNotBlank() && l.near) Text(l.line, color = Color(0xFF64748B), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        "say" -> Column(Modifier.widthIn(max = 220.dp)) {
            Column(Modifier.shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Color.White)
                .border(2.dp, Color(0xFF000000.toInt() or l.color), RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(l.text, color = Color(0xFF000000.toInt() or l.color), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(l.line, color = Color(0xFF0F172A), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            }
            Box(Modifier.padding(start = 18.dp).size(10.dp).graphicsLayer { rotationZ = 45f; translationY = -6f }.background(Color.White))
        }
        "sign" -> Text(l.text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF000000.toInt() or l.color)).padding(horizontal = 8.dp, vertical = 3.dp))
        "board" -> Column(Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xE61E1B4B)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(l.text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (l.sub.isNotBlank()) Text(l.sub, color = Color(0xFFCBD5E1), fontSize = 9.sp, maxLines = 1)
        }
        else -> Text(l.text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoundButton(emoji: String, label: String, small: Boolean = false, badge: Int = 0, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(Modifier.size(if (small) 40.dp else 46.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color(0xE6111827)).clickable(onClick = onClick),
                contentAlignment = Alignment.Center) { Text(emoji, fontSize = if (small) 17.sp else 20.sp) }
            if (badge > 0) Text("$badge", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Color(0xFFEF4444)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Text(label, color = Color.White, fontSize = if (small) 9.sp else 10.sp, fontWeight = FontWeight.SemiBold,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 4f)))
    }
}

@Composable
private fun AgentSheet(container: AppContainer, id: String, views: List<Hq.View>, open: (String) -> Unit, close: () -> Unit) {
    val c = LocalContext.current
    val work = rememberWorkScope()
    val v = views.firstOrNull { it.id == id } ?: run { Text("This agent has left the office."); return }
    val a = remember(id) { Crew.agents(c).firstOrNull { it.id == id } }
    var text by remember { mutableStateOf("") }
    val version = Crew.version
    val thread = remember(v.thread, version) { v.thread?.let { Crew.thread(c, it) } }
    val approvals = remember(v.thread, version) {
        ApprovalInbox.pending(c).filter { (v.thread != null && it.optString("thread") == v.thread) || (a?.lead == true && it.optString("thread").isBlank()) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(v.emoji, fontSize = 34.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${v.role} · ${Hq.ROOMS[v.room].emoji} ${Hq.ROOMS[v.room].name}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        val (label, col) = when (v.status) {
            "working" -> "Working" to Color(0xFF3B82F6); "needs" -> "Needs you" to Color(0xFFEF4444); "done" -> "Done" to Color(0xFF22C55E)
            "asleep" -> "Out of budget" to Color(0xFF6B7280); else -> "Free" to Color(0xFF9CA3AF)
        }
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(col).padding(horizontal = 10.dp, vertical = 5.dp))
    }
    ProfileBlock(container, id, close)
    if (v.card.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(v.card, fontWeight = FontWeight.SemiBold); Text(v.line, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }

    approvals.forEach { ap ->
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22EF4444)).padding(12.dp)) {
            Text("Approve: ${ap.optString("action").replace('_', ' ')}", fontWeight = FontWeight.Bold)
            Text("${ap.optString("risk")} risk · ${ap.optString("why")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ap.optString("purpose").takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { work.launch { val r = withContext(Dispatchers.IO) { ApprovalInbox.decide(container, ap.optString("id"), true) }; Feedback.show(r); Crew.version++ } }) { Text("Approve") }
                OutlinedButton(onClick = { work.launch { withContext(Dispatchers.IO) { ApprovalInbox.decide(container, ap.optString("id"), false) }; Feedback.show("Declined"); Crew.version++ } }) { Text("Decline") }
            }
        }
    }

    // the latest from its thread
    thread?.let { t ->
        val msgs = (t.optJSONArray("messages") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            .filter { it.optString("from") == id || it.optString("to") == id || it.optString("kind") == "final" }.takeLast(5)
        if (msgs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Latest", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            msgs.forEach { m ->
                val from = if (m.optString("from") == id) v.name else if (m.optString("from") == "you") "You" else Crew.agents(c).firstOrNull { it.id == m.optString("from") }?.name ?: "Atlas"
                Text("$from: ${m.optString("text").take(400)}", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text(if (v.status == "needs") "Answer ${v.name}…" else "Give ${v.name} a task…") }, maxLines = 4)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(enabled = text.isNotBlank(), onClick = {
            val live = v.thread != null && thread?.optString("status") in setOf("running", "needs you", "waiting", "idle")
            if (live) Crew.say(container, v.thread!!, "@${v.name} $text")
            else Crew.start(container, if (a?.lead == true) text else "Ask ${v.name} to handle this: $text", if (a?.lead == true) null else listOf("lead", id))
            Feedback.show("Sent to ${v.name}"); text = ""; Crew.version++
        }) { Text(if (v.status == "needs") "Answer" else "Send") }
        OutlinedButton(onClick = { if (text.isNotBlank()) { Hq.enqueue(container, text, id); text = ""; Feedback.show("Queued for ${v.name}") } }, enabled = text.isNotBlank()) { Text("Queue") }
        if (v.status == "working" && v.thread != null) TextButton(onClick = { Crew.stop(c, v.thread); Feedback.show("Stopped") }) { Text("Stop") }
    }
    Spacer(Modifier.height(12.dp))
    Text("Desk on floor", fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Hq.ROOMS.forEachIndexed { i, r -> Capsule("${r.emoji} ${r.name}", v.room == i) { Hq.setRoom(c, id, i) } }
    }
    TextButton(onClick = { close(); open("nx/crew") }, modifier = Modifier.padding(top = 6.dp)) { Text("Open the team's full threads") }
}

@Composable
private fun TableSheet(container: AppContainer, views: List<Hq.View>, close: () -> Unit) {
    val c = LocalContext.current
    var pattern by remember { mutableStateOf(Hq.PATTERNS[0]) }
    var picked by remember { mutableStateOf(views.filter { !it.id.equals("lead") }.take(2).map { it.id }.toSet()) }
    var topic by remember { mutableStateOf("") }
    val live = remember(Crew.version) { Hq.tableThread(c)?.let { Crew.thread(c, it) }?.takeIf { it.optString("status") == "running" } }
    Text("🤝 Meeting table", style = MaterialTheme.typography.titleLarge)
    if (live != null) {
        Text("In session: ${live.optString("goal").substringAfter("Topic: ").take(120)}", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        OutlinedButton(onClick = { Hq.endMeeting(c); Feedback.show("Meeting ended"); close() }, modifier = Modifier.padding(top = 8.dp)) { Text("End the meeting") }
        Spacer(Modifier.height(14.dp))
        Text("Or start another", fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    Hq.PATTERNS.forEach { p ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p == pattern) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { pattern = p }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.emoji, fontSize = 22.sp); Spacer(Modifier.width(10.dp))
            Column { Text(p.name, fontWeight = FontWeight.SemiBold); Text(p.blurb, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Who sits down (the lead always chairs)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        views.filter { it.id != "lead" }.forEach { v -> Capsule("${v.emoji} ${v.name}", v.id in picked) { picked = if (v.id in picked) picked - v.id else if (picked.size < 5) picked + v.id else picked } }
    }
    OutlinedTextField(topic, { topic = it }, Modifier.fillMaxWidth().padding(top = 10.dp), placeholder = { Text("What should they work on?") }, maxLines = 4)
    Button(enabled = topic.isNotBlank() && picked.isNotEmpty(), modifier = Modifier.padding(top = 10.dp), onClick = {
        Hq.startMeeting(container, pattern, picked.toList(), topic); Feedback.show("${pattern.name} started — watch the table"); close()
    }) { Text("Start ${pattern.name.lowercase()}") }
}

@Composable
private fun InboxSheet(container: AppContainer) {
    val c = LocalContext.current
    val work = rememberWorkScope()
    val version = Crew.version
    var tick by remember { mutableIntStateOf(0) }
    val pending = remember(version, tick) { ApprovalInbox.pending(c) }
    val queue = remember(version, tick) { Hq.queued(c) }
    var text by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(Hq.limit(c)) }
    LazyColumn(Modifier.fillMaxWidth().height(520.dp)) {
        item { Text("📥 Inbox", style = MaterialTheme.typography.titleLarge) }
        item { Text("Approvals · ${pending.size}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
        if (pending.isEmpty()) item { Text("Nothing waiting for your OK.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        pending.forEach { ap -> item(ap.optString("id")) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                Text("${ap.optString("source")}: ${ap.optString("action").replace('_', ' ')}", fontWeight = FontWeight.SemiBold)
                Text("${ap.optString("risk")} risk · ${ap.optString("why")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { work.launch { Feedback.show(withContext(Dispatchers.IO) { ApprovalInbox.decide(container, ap.optString("id"), true) }); tick++; Crew.version++ } }) { Text("Approve") }
                    OutlinedButton(onClick = { work.launch { withContext(Dispatchers.IO) { ApprovalInbox.decide(container, ap.optString("id"), false) }; tick++; Crew.version++ } }) { Text("Decline") }
                }
            }
        } }
        item {
            Text("Task queue · ${queue.size}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text("Drop tasks here and walk away. A team picks the next one up whenever fewer than $limit are running.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf(0 to "Paused", 1 to "1 at once", 2 to "2", 3 to "3", 4 to "4").forEach { (n, l) -> Capsule(l, limit == n) { limit = n; Hq.setLimit(c, n); Hq.pump(container); tick++ } }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("A task for the team…") }, maxLines = 3)
            Button(enabled = text.isNotBlank(), modifier = Modifier.padding(top = 6.dp), onClick = { Hq.enqueue(container, text); text = ""; tick++; Feedback.show("Queued") }) { Text("Add to queue") }
        }
        queue.forEach { q -> item(q.optLong("at")) {
            val at = q.optLong("at")
            var assignMenu by remember(at) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("• " + q.optString("text"), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Assigned: ${Crew.agents(c).firstOrNull { it.id == q.optString("agent") }?.name ?: "Auto-route"}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { Hq.reorderQueued(c, at, -1); tick++ }) { Text("↑") }
                    TextButton(onClick = { Hq.reorderQueued(c, at, 1); tick++ }) { Text("↓") }
                    TextButton(onClick = { assignMenu = true }) { Text("Assign") }
                    TextButton(onClick = { Hq.dequeue(c, at); tick++ }) { Text("Remove") }
                }
                androidx.compose.material3.DropdownMenu(expanded = assignMenu, onDismissRequest = { assignMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Auto-route") }, onClick = {
                        Hq.assignQueued(c, at, null); assignMenu = false; tick++
                    })
                    Crew.agents(c).forEach { agent ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text("${agent.emoji} ${agent.name}") }, onClick = {
                            Hq.assignQueued(c, at, agent.id); assignMenu = false; tick++
                        })
                    }
                }
            }
        } }
    }
}

@Composable
private fun ChangesSheet(container: AppContainer) {
    val c = LocalContext.current
    val list = remember(Crew.version) { Hq.changes(c) }
    LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
        item {
            Text("🧾 What agents changed", style = MaterialTheme.typography.titleLarge)
            Text("The last 24 hours. Undo asks the team to reverse it, and anything risky still comes to you first.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (list.isEmpty()) item { Text("No changes yet today.", modifier = Modifier.padding(top = 12.dp)) }
        list.forEach { ch -> item("${ch.t}${ch.text.hashCode()}") {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ch.who, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(ch.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ch.t)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = {
                    Crew.start(container, "Undo this change the team made earlier, exactly and only this: \"${ch.text}\". Ask me before anything risky.")
                    Feedback.show("Asked the team to undo it")
                }) { Text("Undo") }
            }
        } }
    }
}
