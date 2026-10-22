package com.nizam.app.feature.maps

import android.Manifest
import android.content.Intent
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.nizam.app.core.ChipRow
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Label
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Lets search and the agent open Maps on a query. */
object MapsNav { @Volatile var query: String? = null }

private val KAABA = GeoPoint(21.4225, 39.8262)
private val GO_GREEN = Color(0xFF0F9D58)
private val MODES = listOf("Drive", "Bike", "Walk")
private fun clock(secondsFromNow: Double) = LocalTime.now().plusSeconds(secondsFromNow.toLong()).format(DateTimeFormatter.ofPattern("h:mm a"))

/** Which way the arrow points for a manoeuvre. */
private fun turnAngle(type: String, mod: String): Float = when {
    type == "arrive" -> 0f
    mod == "uturn" -> 180f
    mod == "sharp right" -> 135f
    mod == "right" -> 90f
    mod == "slight right" -> 45f
    mod == "slight left" -> -45f
    mod == "left" -> -90f
    mod == "sharp left" -> -135f
    else -> 0f
}

@Composable
private fun TurnIcon(step: Step?, tint: Color, size: Int = 40) {
    if (step?.type == "arrive") Icon(Icons.Rounded.Place, "Arrive", tint = tint, modifier = Modifier.size(size.dp))
    else Icon(Icons.Rounded.ArrowUpward, step?.modifier ?: "Straight", tint = tint,
        modifier = Modifier.size(size.dp).rotate(turnAngle(step?.type.orEmpty(), step?.modifier.orEmpty())))
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MapsScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = MaterialTheme.colorScheme.primary
    val routeBlue = Color(0xFF1A73E8)
    var style by remember { mutableStateOf(MapPrefs.style(c)) }
    var miles by remember { mutableStateOf(MapPrefs.miles(c)) }
    var voice by remember { mutableStateOf(MapPrefs.voice(c)) }
    var mode by remember { mutableStateOf(MapPrefs.mode(c)) }
    var traffic by remember { mutableStateOf(false) }
    var qibla by remember { mutableStateOf(false) }
    var layers by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Place>>(emptyList()) }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var resultsTitle by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Place?>(null) }
    var wikiImg by remember { mutableStateOf<String?>(null) }
    var wikiText by remember { mutableStateOf<String?>(null) }
    // directions
    var dest by remember { mutableStateOf<Place?>(null) }
    var origin by remember { mutableStateOf<Place?>(null) }         // null = your location
    var pickingStart by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf<List<Route>>(emptyList()) }
    var routeIdx by remember { mutableIntStateOf(0) }
    var modeTimes by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var routing by remember { mutableStateOf(false) }
    var showSteps by remember { mutableStateOf(false) }
    // navigation
    var navigating by remember { mutableStateOf(false) }
    var stepIdx by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf(0f) }
    var remainM by remember { mutableStateOf(0.0) }
    var remainS by remember { mutableStateOf(0.0) }
    var toNextM by remember { mutableStateOf(0.0) }
    var rerouting by remember { mutableStateOf(false) }
    var hasPerm by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> hasPerm = r.values.any { it } }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { if (!hasPerm) askPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } }
    val route = routes.getOrNull(routeIdx)

    // one MapView, configured once
    val map = remember {
        Configuration.getInstance().load(c, c.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = c.packageName
        Configuration.getInstance().tileDownloadThreads = 4.toShort()
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
        MapView(c).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0; maxZoomLevel = 20.0
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            controller.setZoom(15.0)
            controller.setCenter(MapPrefs.last(c) ?: GeoPoint(31.5204, 74.3587))
            overlays.add(RotationGestureOverlay(this).apply { isEnabled = true })
            overlays.add(ScaleBarOverlay(this).apply { setAlignBottom(true); setScaleBarOffset(40, 260) })
        }
    }
    val me = remember { MyLocationNewOverlay(GpsMyLocationProvider(c).apply { locationUpdateMinTime = 1000; locationUpdateMinDistance = 2f }, map)
        .apply { isDrawAccuracyEnabled = true } }
    // route: a white casing under a blue line, alternatives in grey
    val casing = remember { Polyline(map).apply { outlinePaint.strokeWidth = 22f; outlinePaint.color = android.graphics.Color.WHITE; outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND } }
    val routeLine = remember { Polyline(map).apply { outlinePaint.strokeWidth = 14f; outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND; outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND } }
    val altLines = remember { mutableListOf<Polyline>() }
    val qiblaLine = remember { Polyline(map).apply { outlinePaint.strokeWidth = 5f; outlinePaint.color = android.graphics.Color.rgb(61, 170, 109) } }
    val trafficOverlay = remember { mutableStateOf<TilesOverlay?>(null) }
    val pins = remember { mutableListOf<Marker>() }
    val pinIcon = remember(accent) { runCatching { androidx.core.content.ContextCompat.getDrawable(c, org.osmdroid.library.R.drawable.marker_default)?.mutate()?.apply { setTint(Color(0xFFEA4335).toArgb()) } }.getOrNull() }
    val dotIcon = remember(accent) { runCatching { androidx.core.content.ContextCompat.getDrawable(c, org.osmdroid.library.R.drawable.marker_default)?.mutate()?.apply { setTint(accent.toArgb()) } }.getOrNull() }

    fun clearPins() { pins.forEach { map.overlays.remove(it) }; pins.clear() }
    fun clearRouteLines() { map.overlays.remove(routeLine); map.overlays.remove(casing); altLines.forEach { map.overlays.remove(it) }; altLines.clear() }

    fun drawRoutes() {
        clearRouteLines()
        routes.forEachIndexed { i, r -> if (i != routeIdx) {
            val pl = Polyline(map).apply { setPoints(r.points); outlinePaint.strokeWidth = 12f; outlinePaint.color = android.graphics.Color.rgb(160, 170, 185)
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                setOnClickListener { _, _, _ -> routeIdx = i; drawRoutes(); true } }
            altLines += pl; map.overlays.add(0, pl)
        } }
        routes.getOrNull(routeIdx)?.let { r ->
            casing.setPoints(r.points); routeLine.setPoints(r.points); routeLine.outlinePaint.color = routeBlue.toArgb()
            map.overlays.add(altLines.size, casing); map.overlays.add(altLines.size + 1, routeLine)
        }
        map.invalidate()
    }

    fun loadDetails(p: Place) = scope.launch {
        wikiImg = null; wikiText = null
        val d = Geo.details(p)
        if (selected?.point == p.point) selected = d
        val (img, text) = Geo.wiki(d.tags)
        if (selected?.point == p.point) { wikiImg = img; wikiText = text }
    }

    fun directions(to: Place) = scope.launch {
        val from = origin?.point ?: me.myLocation ?: run {
            if (!hasPerm) askPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Feedback.show("Finding your location… or tap “From” to choose a start"); dest = to; return@launch }
        dest = to; routing = true; focused = false; results = emptyList()
        clearPins(); pins += Marker(map).apply { position = to.point; title = to.name; pinIcon?.let { icon = it }; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
        map.overlays.addAll(pins)
        runCatching { Geo.routes(from, to.point, mode) }.onSuccess { rs ->
            routes = rs; routeIdx = 0; stepIdx = 0; drawRoutes()
            runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(rs.flatMap { it.points }).increaseByScale(1.3f), true, 140) }
            // how long each other way of travelling would take, for the tabs
            modeTimes = mapOf(mode to rs.first().durationS)
            MODES.filter { it != mode }.map { m -> m to async { runCatching { Geo.route(from, to.point, m).durationS }.getOrNull() } }
                .forEach { (m, d) -> d.await()?.let { t -> modeTimes = modeTimes + (m to t) } }
        }.onFailure { routes = emptyList(); Feedback.show("Couldn't get directions: ${Feedback.friendly(it)}") }
        routing = false
    }

    fun select(p: Place, zoom: Boolean = true) {
        if (pickingStart) { origin = p; pickingStart = false; query = ""; focused = false; dest?.let { directions(it) }; return }
        selected = p; focused = false; results = results.takeIf { it.any { r -> r.point == p.point } } ?: emptyList()
        if (zoom) map.controller.animateTo(p.point, 16.5, 700L)
        if (results.isEmpty()) {
            clearPins()
            pins += Marker(map).apply { position = p.point; title = p.name; pinIcon?.let { icon = it }; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
            map.overlays.addAll(pins)
        }
        map.invalidate()
        loadDetails(p)
    }
    fun showResults(heading: String, list: List<Place>) {
        results = list; resultsTitle = heading; selected = null; clearPins()
        list.forEach { p -> pins += Marker(map).apply { position = p.point; title = p.name; (dotIcon ?: pinIcon)?.let { icon = it }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); setOnMarkerClickListener { _, _ -> select(p, zoom = false); true } } }
        map.overlays.addAll(pins)
        if (list.size > 1) runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(list.take(25).map { it.point }).increaseByScale(1.3f), true, 100) }
        else list.firstOrNull()?.let { select(it) }
        map.invalidate()
    }
    fun box(): DoubleArray = map.boundingBox.let { doubleArrayOf(it.lonWest, it.latNorth, it.lonEast, it.latSouth) }
    fun center(): GeoPoint = me.myLocation ?: (map.mapCenter as? GeoPoint) ?: GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
    fun search(q: String) = scope.launch {
        if (q.isBlank()) return@launch
        MapPrefs.addRecent(c, q); focused = false; searching = true
        // a category word ("petrol", "pharmacy near me") searches everything nearby; anything else is a place search
        val cat = NEARBY.firstOrNull { (label, _, _) -> q.contains(label.lowercase().removeSuffix("s"), true) || label.lowercase().startsWith(q.lowercase().trim().removeSuffix("s")) }
        val list = if (cat != null) runCatching { Geo.nearby(cat.second, box(), center()) }.getOrElse { Geo.search(q, box()) } else Geo.search(q, box())
        searching = false
        if (list.isEmpty()) Feedback.show("Nothing found for “$q” here — zoom out and try again") else showResults(cat?.first ?: q, list)
    }
    fun nearby(label: String, filter: String) = scope.launch {
        query = label; searching = true; focused = false
        runCatching { Geo.nearby(filter, box(), center()) }.onSuccess { list ->
            if (list.isEmpty()) Feedback.show("No $label in view — zoom out and try again") else showResults(label, list)
        }.onFailure { Feedback.show(Feedback.friendly(it)) }
        searching = false
    }
    fun endDirections() {
        routes = emptyList(); dest = null; origin = null; navigating = false; modeTimes = emptyMap(); showSteps = false
        clearRouteLines(); clearPins(); me.disableFollowLocation(); map.mapOrientation = 0f; map.keepScreenOn = false; map.invalidate()
    }
    fun share(name: String, g: GeoPoint) = c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
        "$name\nhttps://www.openstreetmap.org/?mlat=${g.latitude}&mlon=${g.longitude}#map=17/${g.latitude}/${g.longitude}"), "Share"))

    // style, paper tint, traffic and Qibla
    LaunchedEffect(style, dark) { com.nizam.app.ui.design.guard {
        val shown = if (dark && (style == "Paper" || style == "Minimal")) "Dark" else style
        map.setTileSource(MapStyles.source(shown))
        map.overlayManager.tilesOverlay.setColorFilter(styleFilter(shown)?.let { ColorMatrixColorFilter(it) })
        map.overlayManager.tilesOverlay.loadingBackgroundColor = if (dark) android.graphics.Color.rgb(20, 22, 26) else android.graphics.Color.rgb(242, 238, 228)
        map.overlayManager.tilesOverlay.loadingLineColor = android.graphics.Color.TRANSPARENT
        map.invalidate()
    } }
    LaunchedEffect(traffic) { com.nizam.app.ui.design.guard {
        trafficOverlay.value?.let { map.overlays.remove(it); it.onDetach(map) }; trafficOverlay.value = null
        if (traffic) {
            val key = com.nizam.app.net.Router.firstKey(c.getSharedPreferences("maps", 0).getString("tomtom", "").orEmpty())
            if (key.isBlank()) { traffic = false; Feedback.show("Live traffic needs a free TomTom key — add it in Settings › API keys"); return@LaunchedEffect }
            val src = TemplateTiles("tomtom-traffic", "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=$key&tileSize=256", 20, "© TomTom")
            val ov = TilesOverlay(MapTileProviderBasic(c, src), c).apply { loadingBackgroundColor = android.graphics.Color.TRANSPARENT; loadingLineColor = android.graphics.Color.TRANSPARENT }
            map.overlays.add(0, ov); trafficOverlay.value = ov; map.invalidate()
        }
    } }
    LaunchedEffect(qibla, hasPerm) { com.nizam.app.ui.design.guard {
        map.overlays.remove(qiblaLine)
        while (qibla) {
            me.myLocation?.let { qiblaLine.setPoints(listOf(it, KAABA)); if (qiblaLine !in map.overlays) map.overlays.add(qiblaLine); map.invalidate() }
            delay(5000)
        }
        map.invalidate()
    } }
    LaunchedEffect(hasPerm) { com.nizam.app.ui.design.guard {
        if (!hasPerm) return@LaunchedEffect
        if (me !in map.overlays) map.overlays.add(me)
        me.enableMyLocation()
        me.runOnFirstFix { map.post { if (selected == null && dest == null && MapsNav.query == null) me.myLocation?.let { map.controller.animateTo(it, 16.0, 800L); MapPrefs.setLast(c, it) } } }
    } }
    DisposableEffect(Unit) {
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false.also { focused = false }
            override fun longPressHelper(p: GeoPoint?): Boolean { p ?: return false; scope.launch { select(Geo.reverse(p), zoom = false) }; return true }
        }))
        MapsNav.query?.let { q -> MapsNav.query = null; query = q; search(q) }
        onDispose { map.mapCenter?.let { MapPrefs.setLast(c, GeoPoint(it.latitude, it.longitude)) }; me.disableMyLocation(); map.keepScreenOn = false; map.onDetach() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> when (e) { Lifecycle.Event.ON_RESUME -> map.onResume(); Lifecycle.Event.ON_PAUSE -> map.onPause(); else -> {} } }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    // live suggestions while typing
    LaunchedEffect(query, focused) { com.nizam.app.ui.design.guard {
        suggestions = emptyList()
        if (focused && query.trim().length >= 2) { delay(250); suggestions = Geo.suggest(query.trim(), center()) }
    } }
    // turn-by-turn: follow heading-up, advance steps, speak twice per turn, reroute when off course
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) { val t = TextToSpeech(c) { }; tts.value = t; onDispose { t.shutdown() } }
    fun say(s: String) { if (voice) tts.value?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "nav") }
    LaunchedEffect(navigating) { com.nizam.app.ui.design.guard {
        if (!navigating) return@LaunchedEffect
        map.keepScreenOn = true
        me.enableFollowLocation(); map.controller.setZoom(18.0)
        routes.getOrNull(routeIdx)?.steps?.firstOrNull()?.let { say(it.text) }
        var farSpoken = -1; var nearSpoken = -1; var offCount = 0
        while (navigating) {
            val r = routes.getOrNull(routeIdx) ?: break
            val here = me.myLocation
            val fix = me.lastFix
            speed = fix?.speed ?: 0f
            if (fix != null && fix.hasBearing() && speed > 1.5f) map.mapOrientation = -fix.bearing
            if (here != null && r.steps.isNotEmpty()) {
                // advance to the next manoeuvre once we've passed this one
                while (stepIdx < r.steps.lastIndex && here.distanceToAsDouble(r.steps[stepIdx].point) < 25) stepIdx++
                val next = r.steps[stepIdx]
                val d = here.distanceToAsDouble(next.point)
                toNextM = d
                remainM = d + r.steps.drop(stepIdx).sumOf { it.distanceM }
                remainS = r.steps.drop(stepIdx).sumOf { it.durationS } + d / maxOf(speed.toDouble(), if (mode == "Walk") 1.3 else if (mode == "Bike") 4.5 else 9.0)
                val far = if (mode == "Drive") 450.0 else 150.0
                if (d in 120.0..far && farSpoken != stepIdx) { farSpoken = stepIdx; say("In ${Geo.distance(d, miles)}, ${next.text}") }
                else if (d < 60 && nearSpoken != stepIdx) { nearSpoken = stepIdx; say(next.text) }
                if (stepIdx == r.steps.lastIndex && d < 30) { say("You've arrived at ${dest?.name.orEmpty()}"); navigating = false; break }
                // off the line for a few seconds: find a new way from here
                if (Geo.offRoute(here, r.points) > 55) offCount++ else offCount = 0
                if (offCount >= 3 && !rerouting) {
                    offCount = 0; rerouting = true; say("Rerouting")
                    dest?.let { to -> runCatching { Geo.routes(here, to.point, mode) }.onSuccess { rs -> routes = rs; routeIdx = 0; stepIdx = 0; farSpoken = -1; nearSpoken = -1; drawRoutes() } }
                    rerouting = false
                }
            }
            delay(1500)
        }
        map.keepScreenOn = false; map.mapOrientation = 0f
    } }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ map }, Modifier.fillMaxSize())

        // ── top ──
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (navigating && route != null) {
                val next = route.steps.getOrNull(stepIdx); val after = route.steps.getOrNull(stepIdx + 1)
                Surface(shape = RoundedCornerShape(20.dp), color = GO_GREEN, modifier = Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(20.dp))) {
                    Column {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(70.dp)) {
                                TurnIcon(next, Color.White, 44)
                                Text(Geo.distance(toNextM, miles), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Text(if (rerouting) "Finding a new route…" else next?.text ?: "Follow the route", color = Color.White,
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp), maxLines = 3)
                        }
                        if (after != null) Row(Modifier.fillMaxWidth().background(Color(0xFF0B7A45)).padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Then ", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                            TurnIcon(after, Color.White, 20)
                            Text(" " + after.text, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else if (dest != null && !pickingStart) {
                // Google-style directions card: from / to / swap, then a tab per way of travelling with its time
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp))) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                EndpointRow(Icons.Rounded.TripOrigin, routeBlue, origin?.name ?: "Your location") { pickingStart = true; focused = true; query = "" }
                                Spacer(Modifier.height(6.dp))
                                EndpointRow(Icons.Rounded.Place, Color(0xFFEA4335), dest?.name.orEmpty()) { selected = dest; endDirections() }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Close, "Close directions", Modifier.clip(CircleShape).clickable { endDirections() }.padding(6.dp))
                                if (origin != null) Icon(Icons.Rounded.SwapVert, "Swap", Modifier.clip(CircleShape).clickable {
                                    val o = origin; val d = dest; if (o != null && d != null) { origin = d; directions(o) } }.padding(6.dp))
                            }
                        }
                        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MODES.forEach { m ->
                                val on = m == mode
                                Surface(shape = RoundedCornerShape(50), color = if (on) routeBlue.copy(alpha = 0.14f) else Color.Transparent,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { mode = m; MapPrefs.setMode(c, m); dest?.let { directions(it) } }) {
                                    Text(listOf(when (m) { "Drive" -> "🚗"; "Bike" -> "🚲"; else -> "🚶" }, modeTimes[m]?.let { Geo.duration(it) } ?: m).joinToString(" "),
                                        color = if (on) routeBlue else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(28.dp))) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (pickingStart) Icons.Rounded.TripOrigin else Icons.Rounded.Search, null, tint = if (pickingStart) routeBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            if (query.isEmpty()) Text(if (pickingStart) "Choose a starting point" else "Search here",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            BasicTextField(query, { query = it; focused = true }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { search(query) }),
                                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focused = true })
                        }
                        if (searching) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else if (query.isNotEmpty() || pickingStart || results.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", Modifier.clip(CircleShape).clickable {
                            query = ""; results = emptyList(); clearPins(); map.invalidate(); if (pickingStart) { pickingStart = false; focused = false } }.padding(2.dp))
                    }
                }
                // suggestions / recents / saved
                AnimatedVisibility(focused && (suggestions.isNotEmpty() || query.isBlank()), enter = fadeIn(), exit = fadeOut()) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).shadow(4.dp, RoundedCornerShape(20.dp))) {
                        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                            val here = me.myLocation
                            if (query.isBlank()) {
                                if (pickingStart) ListRow(Icons.Rounded.MyLocation, "Your location", null) { origin = null; pickingStart = false; focused = false; dest?.let { directions(it) } }
                                MapPrefs.saved(c).forEach { p -> ListRow(when (p.kind) { "Home" -> Icons.Rounded.Home; "Work" -> Icons.Rounded.Work; "Parking" -> Icons.Rounded.LocalParking; else -> Icons.Rounded.Bookmark },
                                    if (p.kind in listOf("Home", "Work", "Parking")) p.kind else p.name, listOfNotNull(if (p.kind in listOf("Home", "Work", "Parking")) p.name else null,
                                        here?.let { Geo.distance(it.distanceToAsDouble(p.point), miles) }).joinToString(" · ").ifBlank { null }) { select(p) } }
                                MapPrefs.recents(c).forEach { r -> ListRow(Icons.Rounded.Schedule, r, null) { query = r; search(r) } }
                            } else suggestions.forEach { p -> ListRow(Icons.Rounded.Place, p.name,
                                listOfNotNull(here?.let { Geo.distance(it.distanceToAsDouble(p.point), miles) }, p.detail.ifBlank { null }).joinToString(" · ")) { query = p.name; select(p) } }
                        }
                    }
                }
                if (!focused && selected == null && results.isEmpty() && !pickingStart)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NEARBY.forEach { (label, filter, emoji) -> Pill("$emoji  $label") { nearby(label, filter) } }
                    }
            }
        }

        // ── right: layers, Qibla, my location ──
        if (!navigating) Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fab(Icons.Rounded.Layers, "Map style and layers") { layers = true }
            Fab(Icons.Rounded.Explore, "Qibla direction", on = qibla) { qibla = !qibla; if (qibla) Feedback.show("The green line points to the Kaaba") }
            Fab(Icons.Rounded.MyLocation, "My location") {
                if (!hasPerm) askPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else me.myLocation?.let { map.controller.animateTo(it, 17.0, 600L); map.mapOrientation = 0f } ?: Feedback.show("Finding you…")
            }
        } else Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fab(Icons.Rounded.RecordVoiceOver, if (voice) "Mute directions" else "Speak directions", on = voice) { voice = !voice; MapPrefs.setVoice(c, voice) }
            Fab(Icons.Rounded.MyLocation, "Re-centre") { me.enableFollowLocation(); map.controller.setZoom(18.0) }
        }

        // ── bottom ──
        val here = me.myLocation
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // navigating: time left, distance, arrival
            AnimatedVisibility(navigating && route != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Sheet {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Geo.duration(remainS), style = MaterialTheme.typography.headlineSmall, color = GO_GREEN, fontWeight = FontWeight.Bold)
                            Text("${Geo.distance(remainM, miles)} · ${clock(remainS)} · ${if (miles) "${(speed * 2.237).toInt()} mph" else "${(speed * 3.6).toInt()} km/h"}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFFEA4335), modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                            navigating = false; me.disableFollowLocation() }) {
                            Text("Exit", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                        }
                    }
                }
            }
            // route options
            AnimatedVisibility(!navigating && dest != null && !pickingStart, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Sheet {
                    if (routing && routes.isEmpty()) Text("Finding the best routes…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    routes.forEachIndexed { i, r ->
                        val on = i == routeIdx
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) routeBlue.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable { routeIdx = i; drawRoutes() }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Geo.duration(r.durationS), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                    color = if (i == 0) GO_GREEN else MaterialTheme.colorScheme.onSurface)
                                Text(listOfNotNull(Geo.distance(r.distanceM, miles), r.via.ifBlank { null }?.let { "via $it" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (i == 0) "Fastest route · arrive ${clock(r.durationS)}" else "${Geo.duration(r.durationS - routes[0].durationS)} slower · arrive ${clock(r.durationS)}",
                                    style = MaterialTheme.typography.labelMedium, color = if (i == 0) GO_GREEN else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (on) Surface(shape = RoundedCornerShape(50), color = routeBlue, modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                                if (origin != null) Feedback.show("Navigation follows you — starting from your location")
                                origin = null; navigating = true; stepIdx = 0; showSteps = false }) {
                                Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Text(" Start", color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    route?.let { r ->
                        Row(Modifier.fillMaxWidth().clickable { showSteps = !showSteps }.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.steps.size} steps", style = MaterialTheme.typography.labelLarge, color = routeBlue, modifier = Modifier.weight(1f))
                            Icon(if (showSteps) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, tint = routeBlue)
                        }
                        AnimatedVisibility(showSteps) {
                            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                                r.steps.forEach { s -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TurnIcon(s, MaterialTheme.colorScheme.onSurfaceVariant, 22)
                                    Text(s.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                    if (s.distanceM > 0) Text(Geo.distance(s.distanceM, miles), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) }
                            }
                        }
                    }
                }
            }
            // a place: photo, hours, and Google-style round actions
            AnimatedVisibility(dest == null && selected != null && !focused, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                selected?.let { p -> Sheet {
                    var saved by remember(p) { mutableStateOf(MapPrefs.saved(c).any { it.name == p.name && it.kind != "Home" && it.kind != "Work" }) }
                    var more by remember(p) { mutableStateOf(false) }
                    val phone = p.tags["phone"] ?: p.tags["contact:phone"]
                    val web = p.tags["website"] ?: p.tags["contact:website"] ?: p.tags["url"]
                    val hours = Hours.status(p.tags["opening_hours"])
                    Column(Modifier.animateContentSize().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(
                                    (p.tags["cuisine"]?.replace(';', ',')?.replace('_', ' ')?.let { "$it · " }.orEmpty() + p.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }).ifBlank { null },
                                    here?.let { Geo.distance(it.distanceToAsDouble(p.point), miles) }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                hours?.let { (open, text) -> Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                    color = if (open) GO_GREEN else Color(0xFFD93025), modifier = Modifier.padding(top = 2.dp)) }
                            }
                            Icon(Icons.Rounded.Close, "Close", Modifier.clip(CircleShape).clickable { selected = null; if (results.isEmpty()) { clearPins(); map.invalidate() } }.padding(4.dp))
                        }
                        Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            RoundAction(Icons.Rounded.Directions, "Directions", filled = true) { selected = null; directions(p) }
                            RoundAction(Icons.Rounded.Navigation, "Start") { selected = null; scope.launch { directions(p).join(); if (routes.isNotEmpty()) { navigating = true; stepIdx = 0 } } }
                            if (phone != null) RoundAction(Icons.Rounded.Call, "Call") { runCatching { c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.substringBefore(';').trim()))) } }
                            RoundAction(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (saved) "Saved" else "Save") {
                                if (saved) MapPrefs.unsave(c, p) else MapPrefs.save(c, p.copy(kind = if (p.kind == "Parking") "Parking" else "")); saved = !saved
                                Feedback.show(if (saved) "Saved to your places" else "Removed") }
                            RoundAction(Icons.Rounded.Share, "Share") { share(p.name, p.point) }
                            if (web != null) RoundAction(Icons.Rounded.Language, "Website") { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse(if (web.startsWith("http")) web else "https://$web"))) } }
                        }
                        wikiImg?.let { AsyncImage(it, p.name, contentScale = ContentScale.Crop, modifier = Modifier.padding(top = 14.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))) }
                        wikiText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = if (more) 20 else 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp).clickable { more = !more }) }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        if (p.detail.isNotBlank()) InfoRow(Icons.Rounded.Place, p.detail)
                        p.tags["opening_hours"]?.let { InfoRow(Icons.Rounded.Schedule, it.replace("; ", "\n")) }
                        phone?.let { InfoRow(Icons.Rounded.Call, it) { runCatching { c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + it.substringBefore(';').trim()))) } } }
                        web?.let { InfoRow(Icons.Rounded.Language, it.removePrefix("https://").removePrefix("http://").trimEnd('/')) {
                            runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (web.startsWith("http")) web else "https://$web"))) } } }
                        listOfNotNull(p.tags["wheelchair"]?.let { "Wheelchair: $it" }, p.tags["takeaway"]?.let { "Takeaway: $it" }, p.tags["delivery"]?.let { "Delivery: $it" },
                            p.tags["internet_access"]?.let { "Wi-Fi: $it" }, p.tags["fuel:diesel"]?.let { "Diesel: $it" }).takeIf { it.isNotEmpty() }
                            ?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
                        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("🏠  Set as Home") { MapPrefs.save(c, p.copy(kind = "Home")); Feedback.show("Home set") }
                            Pill("💼  Set as Work") { MapPrefs.save(c, p.copy(kind = "Work")); Feedback.show("Work set") }
                            Pill("🧭  From here") { origin = p; selected = null; Feedback.show("Now pick where to go") }
                            if (p.kind == "Parking") Pill("✕  Clear parking") { MapPrefs.unsave(c, p); selected = null; Feedback.show("Parking cleared") }
                        }
                        Text("%.5f, %.5f".format(p.point.latitude, p.point.longitude), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    }
                } }
            }
            // a list of results
            AnimatedVisibility(dest == null && selected == null && results.size > 1 && !focused, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Sheet {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(resultsTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${results.size} nearby", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                        results.forEach { p ->
                            val h = Hours.status(p.tags["opening_hours"])
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { select(p) }.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(p.tags["cuisine"]?.substringBefore(';')?.replace('_', ' ') ?: p.kind.replace('_', ' ').ifBlank { null }, p.detail.ifBlank { null })
                                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    h?.let { (open, t) -> Text(t, style = MaterialTheme.typography.labelMedium, color = if (open) GO_GREEN else Color(0xFFD93025)) }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    here?.let { Text(Geo.distance(it.distanceToAsDouble(p.point), miles), style = MaterialTheme.typography.labelLarge) }
                                    Icon(Icons.Rounded.Directions, "Directions", tint = routeBlue, modifier = Modifier.padding(top = 4.dp).clip(CircleShape).clickable { directions(p) }.padding(4.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
            }
            // idle: Home, Work, Parking, share where I am
            AnimatedVisibility(dest == null && selected == null && results.isEmpty() && !focused && !navigating, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val saved = MapPrefs.saved(c)
                    listOf("Home" to Icons.Rounded.Home, "Work" to Icons.Rounded.Work).forEach { (k, ic) ->
                        val p = saved.firstOrNull { it.kind == k }
                        QuickChip(ic, if (p != null) "$k · ${here?.let { Geo.distance(it.distanceToAsDouble(p.point), miles) } ?: "go"}" else "Set $k") {
                            if (p != null) directions(p) else Feedback.show("Search or long-press a place, then “Set as $k”") }
                    }
                    val park = saved.firstOrNull { it.kind == "Parking" }
                    QuickChip(Icons.Rounded.LocalParking, if (park != null) "My car" else "Save parking") {
                        if (park != null) select(park)
                        else here?.let { g -> MapPrefs.save(c, Place("Parking", "Parked at ${LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a"))}", g, "Parking")); Feedback.show("Parking spot saved") }
                            ?: Feedback.show("Finding your location…")
                    }
                    QuickChip(Icons.Rounded.Share, "Share my location") { here?.let { share("I'm here", it) } ?: Feedback.show("Finding your location…") }
                }
            }
        }
    }

    if (layers) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { layers = false }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Label("Map type")
            val swatch = mapOf("Paper" to Color(0xFFEFE6D2), "Minimal" to Color(0xFFE8EAED), "Streets" to Color(0xFFBFDDB5),
                "Dark" to Color(0xFF263238), "Satellite" to Color(0xFF3E5A3A), "Terrain" to Color(0xFFD9C9A3))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 8.dp)) {
                MapStyles.NAMES.forEach { n ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { style = n; MapPrefs.setStyle(c, n) }) {
                        Box(Modifier.size(width = 92.dp, height = 64.dp).clip(RoundedCornerShape(14.dp)).background(swatch[n] ?: Color.Gray)
                            .border(if (style == n) 3.dp else 0.dp, if (style == n) routeBlue else Color.Transparent, RoundedCornerShape(14.dp)))
                        Text(n, style = MaterialTheme.typography.labelMedium, color = if (style == n) routeBlue else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Label("Map details", Modifier.padding(top = 18.dp))
            SwitchRow("Live traffic", "Green flows, red is slow (TomTom, free key)", traffic) { traffic = it }
            SwitchRow("Qibla line", "A line from you to the Kaaba", qibla) { qibla = it }
            SwitchRow("Spoken directions", null, voice) { voice = it; MapPrefs.setVoice(c, it) }
            SwitchRow("Miles instead of kilometres", null, miles) { miles = it; MapPrefs.setMiles(c, it) }
            Label("Default travel mode", Modifier.padding(top = 12.dp))
            ChipRow(MODES, mode, { mode = it; MapPrefs.setMode(c, it) })
            com.nizam.app.ui.design.OutlinedButton(onClick = { runCatching { org.osmdroid.tileprovider.modules.SqlTileWriter().purgeCache() }; Feedback.show("Map cache cleared") },
                modifier = Modifier.padding(top = 14.dp)) { Text("Clear saved map tiles") }
            Text("Places you've viewed stay on the phone (up to 300 MB) and work offline. Map data © OpenStreetMap contributors · satellite © Esri · " +
                "terrain © OpenTopoMap · routing OSRM · search Photon, Nominatim & Overpass · photos Wikipedia",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(on, onChange)
    }
}

@Composable
private fun EndpointRow(icon: ImageVector, tint: Color, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, filled: Boolean = false, onClick: () -> Unit) {
    val blue = Color(0xFF1A73E8)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (filled) blue else blue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (filled) Color.White else blue)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = blue, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Color(0xFF1A73E8), modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun ListRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Sheet(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 20.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.size(10.dp))
            content()
        }
    }
}

@Composable
private fun QuickChip(icon: ImageVector, text: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, modifier = Modifier.shadow(4.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF1A73E8), modifier = Modifier.size(18.dp))
            Text(" $text", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, modifier = Modifier.shadow(3.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).clickable(onClick = onClick)) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun Fab(icon: ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(48.dp).shadow(6.dp, CircleShape).clip(CircleShape).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
    }
}
