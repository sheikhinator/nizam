package com.nizam.app.feature.maps

import android.content.Context
import com.nizam.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import java.net.URLEncoder

/**
 * Maps without keys or accounts: OpenStreetMap data, styled tiles (Paper is the house style — a
 * minimal light map warmed to the colour of paper), Photon/Nominatim for search, and the public
 * OSRM routers for driving, cycling and walking directions with turn-by-turn steps.
 */
data class Place(val name: String, val detail: String, val point: GeoPoint, val kind: String = "",
                 val osm: String = "", val tags: Map<String, String> = emptyMap())
data class Step(val text: String, val distanceM: Double, val point: GeoPoint, val type: String = "", val modifier: String = "", val durationS: Double = 0.0)
data class Route(val points: List<GeoPoint>, val distanceM: Double, val durationS: Double, val steps: List<Step>, val via: String = "")

private fun JSONObject.tagMap(): Map<String, String> = keys().asSequence().associateWith { optString(it) }.filterValues { it.isNotBlank() }

class TemplateTiles(name: String, private val template: String, maxZoom: Int, copyright: String) :
    OnlineTileSourceBase(name, 0, maxZoom, 256, ".png", arrayOf(template), copyright) {
    override fun getTileURLString(pMapTileIndex: Long): String = template
        .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
        .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
        .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
        .replace("{s}", listOf("a", "b", "c")[(MapTileIndex.getX(pMapTileIndex) + MapTileIndex.getY(pMapTileIndex)) % 3])
}

object MapStyles {
    val NAMES = listOf("Paper", "Minimal", "Streets", "Dark", "Satellite", "Terrain")
    fun source(name: String) = when (name) {
        // OpenStreetMap's own tiles need no key; Paper, Minimal and Dark are restyled on the phone (see filter())
        "Minimal", "Paper", "Dark", "Streets" -> TemplateTiles("osm", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", 19, "© OpenStreetMap contributors")
        "Satellite" -> TemplateTiles("esri-imagery", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", 19, "© Esri, Maxar, Earthstar")
        else -> TemplateTiles("opentopo", "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png", 17, "© OpenStreetMap © OpenTopoMap")
    }
}

/** How each style recolours the OpenStreetMap tiles. */
fun styleFilter(style: String): android.graphics.ColorMatrix? {
    fun m(vararg f: Float) = android.graphics.ColorMatrix(f)
    return when (style) {
        // warm, faded paper: desaturated, then tinted sepia and lifted
        "Paper" -> android.graphics.ColorMatrix().apply { setSaturation(0.18f) }.apply {
            postConcat(m(0.93f, 0f, 0f, 0f, 22f,  0f, 0.9f, 0f, 0f, 18f,  0f, 0f, 0.78f, 0f, 10f,  0f, 0f, 0f, 1f, 0f)) }
        // clean light grey: no colour, softer contrast
        "Minimal" -> android.graphics.ColorMatrix().apply { setSaturation(0f) }.apply {
            postConcat(m(0.85f, 0f, 0f, 0f, 36f,  0f, 0.85f, 0f, 0f, 36f,  0f, 0f, 0.85f, 0f, 38f,  0f, 0f, 0f, 1f, 0f)) }
        // night: greyscale, inverted, with a cool tint
        "Dark" -> android.graphics.ColorMatrix().apply { setSaturation(0.1f) }.apply {
            postConcat(m(-0.82f, 0f, 0f, 0f, 225f,  0f, -0.82f, 0f, 0f, 228f,  0f, 0f, -0.8f, 0f, 238f,  0f, 0f, 0f, 1f, 0f)) }
        else -> null
    }
}

object MapPrefs {
    private fun p(c: Context) = c.getSharedPreferences("maps", 0)
    fun style(c: Context) = p(c).getString("style", "Paper").orEmpty()
    fun setStyle(c: Context, s: String) = p(c).edit().putString("style", s).apply()
    fun miles(c: Context) = p(c).getBoolean("miles", false)
    fun setMiles(c: Context, v: Boolean) = p(c).edit().putBoolean("miles", v).apply()
    fun voice(c: Context) = p(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) = p(c).edit().putBoolean("voice", v).apply()
    fun mode(c: Context) = p(c).getString("mode", "Drive").orEmpty()
    fun setMode(c: Context, v: String) = p(c).edit().putString("mode", v).apply()
    fun last(c: Context): GeoPoint? = p(c).getString("last", null)?.split(',')?.let { runCatching { GeoPoint(it[0].toDouble(), it[1].toDouble(), ) }.getOrNull() }
    fun setLast(c: Context, g: GeoPoint) = p(c).edit().putString("last", "${g.latitude},${g.longitude}").apply()

    fun saved(c: Context): List<Place> = runCatching { JSONArray(p(c).getString("saved", "[]")) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Place(it.optString("name"), it.optString("detail"), GeoPoint(it.optDouble("lat"), it.optDouble("lon")), it.optString("kind")) } }
    fun save(c: Context, pl: Place) = p(c).edit().putString("saved", JSONArray().apply {
        (listOf(pl) + saved(c).filter { it.name != pl.name && (pl.kind.isBlank() || it.kind != pl.kind) }).take(50).forEach {
            put(JSONObject().put("name", it.name).put("detail", it.detail).put("lat", it.point.latitude).put("lon", it.point.longitude).put("kind", it.kind)) }
    }.toString()).apply()
    fun unsave(c: Context, pl: Place) = p(c).edit().putString("saved", JSONArray().apply {
        saved(c).filter { it.name != pl.name }.forEach { put(JSONObject().put("name", it.name).put("detail", it.detail).put("lat", it.point.latitude).put("lon", it.point.longitude).put("kind", it.kind)) }
    }.toString()).apply()
    fun recents(c: Context) = p(c).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun addRecent(c: Context, q: String) = p(c).edit().putString("recent", (listOf(q) + recents(c).filter { it != q }).take(12).joinToString("\n")).apply()
}

object Geo {
    private val UA = mapOf("User-Agent" to "Atlas/1.0 (personal assistant app)")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Search-as-you-type via Photon, biased to where you are. */
    suspend fun suggest(q: String, near: GeoPoint?): List<Place> = runCatching {
        val bias = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" }.orEmpty()
        val o = JSONObject(Http.getJson("https://photon.komoot.io/api/?q=${enc(q)}&limit=8$bias", UA))
        val f = o.optJSONArray("features") ?: JSONArray()
        (0 until f.length()).map { f.getJSONObject(it) }.map { x ->
            val pr = x.getJSONObject("properties"); val c = x.getJSONObject("geometry").getJSONArray("coordinates")
            Place(pr.optString("name").ifBlank { pr.optString("street") },
                listOf(pr.optString("street"), pr.optString("district"), pr.optString("city"), pr.optString("state"), pr.optString("country"))
                    .filter { it.isNotBlank() }.distinct().joinToString(", "),
                GeoPoint(c.getDouble(1), c.getDouble(0)), pr.optString("osm_value"),
                osm = pr.optString("osm_type").take(1).uppercase() + pr.optString("osm_id"))
        }.filter { it.name.isNotBlank() }
    }.getOrElse { emptyList() }

    /** Full search via Nominatim (also finds categories like "pharmacy" within the visible area). */
    suspend fun search(q: String, box: DoubleArray?): List<Place> = runCatching {
        val vb = box?.let { "&viewbox=${it[0]},${it[1]},${it[2]},${it[3]}&bounded=1" }.orEmpty()
        val a = JSONArray(Http.getJson("https://nominatim.openstreetmap.org/search?q=${enc(q)}&format=jsonv2&limit=20&extratags=1$vb", UA))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Place(o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') },
                o.optString("display_name").substringAfter(", ", ""), GeoPoint(o.getDouble("lat"), o.getDouble("lon")), o.optString("type"),
                osm = o.optString("osm_type").take(1).uppercase() + o.optString("osm_id"), tags = o.optJSONObject("extratags")?.tagMap().orEmpty())
        }
    }.getOrElse { emptyList() }.ifEmpty { if (box != null) search(q, null) else emptyList() }

    suspend fun reverse(g: GeoPoint): Place = runCatching {
        val o = JSONObject(Http.getJson("https://nominatim.openstreetmap.org/reverse?lat=${g.latitude}&lon=${g.longitude}&format=jsonv2&zoom=18", UA))
        Place(o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') }, o.optString("display_name").substringAfter(", ", ""), g)
    }.getOrElse { Place("Dropped pin", "%.5f, %.5f".format(g.latitude, g.longitude), g) }

    /** Directions, with up to two alternatives, fastest first. mode: Drive, Bike, Walk. */
    suspend fun routes(from: GeoPoint, to: GeoPoint, mode: String): List<Route> {
        val profile = when (mode) { "Walk" -> "routed-foot"; "Bike" -> "routed-bike"; else -> "routed-car" }
        val url = "https://routing.openstreetmap.de/$profile/route/v1/driving/${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
            "?overview=full&geometries=geojson&steps=true&alternatives=true"
        val o = JSONObject(Http.getJson(url, UA))
        val rs = o.optJSONArray("routes") ?: JSONArray()
        if (rs.length() == 0) throw IllegalStateException("No route found between these places")
        return (0 until rs.length()).map { parseRoute(rs.getJSONObject(it)) }.sortedBy { it.durationS }
    }
    suspend fun route(from: GeoPoint, to: GeoPoint, mode: String): Route = routes(from, to, mode).first()

    private fun parseRoute(r: JSONObject): Route {
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map { coords.getJSONArray(it).let { c -> GeoPoint(c.getDouble(1), c.getDouble(0)) } }
        val steps = mutableListOf<Step>()
        val roads = mutableMapOf<String, Double>()
        val legs = r.optJSONArray("legs") ?: JSONArray()
        for (l in 0 until legs.length()) {
            val st = legs.getJSONObject(l).optJSONArray("steps") ?: continue
            for (i in 0 until st.length()) {
                val s = st.getJSONObject(i); val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val road = s.optString("name")
                if (road.isNotBlank()) roads[road] = (roads[road] ?: 0.0) + s.optDouble("distance")
                steps += Step(describe(m.optString("type"), m.optString("modifier"), road), s.optDouble("distance"),
                    GeoPoint(loc.getDouble(1), loc.getDouble(0)), m.optString("type"), m.optString("modifier"), s.optDouble("duration"))
            }
        }
        val via = roads.entries.sortedByDescending { it.value }.take(2).joinToString(" and ") { it.key }
        return Route(pts, r.optDouble("distance"), r.optDouble("duration"), steps, via)
    }

    /**
     * What's nearby, straight from OpenStreetMap (Overpass): every restaurant, pump or pharmacy in view,
     * with its opening hours, phone and website — nearest first.
     */
    suspend fun nearby(filter: String, box: DoubleArray, center: GeoPoint?): List<Place> {
        // box = west, north, east, south
        val bbox = "${box[3]},${box[0]},${box[1]},${box[2]}"
        val q = "[out:json][timeout:20];(nwr$filter($bbox););out center tags 80;"
        val body = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter").firstNotNullOfOrNull { host ->
            runCatching { Http.getJson("$host?data=${enc(q)}", UA) }.getOrNull() } ?: throw IllegalStateException("Nearby search is busy — try again in a moment")
        val els = JSONObject(body).optJSONArray("elements") ?: JSONArray()
        return (0 until els.length()).map { els.getJSONObject(it) }.mapNotNull { e ->
            val tags = e.optJSONObject("tags")?.tagMap().orEmpty()
            val name = tags["name"] ?: tags["brand"] ?: return@mapNotNull null
            val lat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: return@mapNotNull null
            val lon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: return@mapNotNull null
            val kind = tags["amenity"] ?: tags["shop"] ?: tags["tourism"] ?: tags["leisure"] ?: ""
            val addr = listOfNotNull(tags["addr:housenumber"]?.let { n -> tags["addr:street"]?.let { "$n $it" } } ?: tags["addr:street"], tags["addr:city"]).joinToString(", ")
            Place(name, addr, GeoPoint(lat, lon), kind, osm = e.optString("type").take(1).uppercase() + e.optLong("id"), tags = tags)
        }.distinctBy { it.name + it.point.latitude.toString().take(7) }
            .sortedBy { p -> center?.distanceToAsDouble(p.point) ?: 0.0 }
    }

    /** Hours, phone, website and more for a place found by search or a long-press. */
    suspend fun details(p: Place): Place {
        if (p.tags.isNotEmpty() || p.osm.length < 2) return p
        return runCatching {
            val a = JSONArray(Http.getJson("https://nominatim.openstreetmap.org/lookup?osm_ids=${p.osm}&format=jsonv2&extratags=1", UA))
            val o = a.optJSONObject(0) ?: return p
            p.copy(tags = o.optJSONObject("extratags")?.tagMap().orEmpty(), kind = p.kind.ifBlank { o.optString("type") })
        }.getOrElse { p }
    }

    /** A photo and a line about the place, when it has a Wikipedia article. */
    suspend fun wiki(tags: Map<String, String>): Pair<String?, String?> = runCatching {
        val w = tags["wikipedia"] ?: return Pair(tags["image"]?.takeIf { it.startsWith("http") }, null)
        val lang = w.substringBefore(':', "en"); val title = w.substringAfter(':')
        val o = JSONObject(Http.getJson("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_')).replace("+", "%20")}", UA))
        Pair(o.optJSONObject("thumbnail")?.optString("source") ?: o.optJSONObject("originalimage")?.optString("source"), o.optString("extract").takeIf { it.isNotBlank() })
    }.getOrElse { Pair(null, null) }

    private fun describe(type: String, mod: String, road: String): String {
        val on = if (road.isNotBlank()) " onto $road" else ""
        return when (type) {
            "depart" -> "Head ${mod.ifBlank { "out" }}" + if (road.isNotBlank()) " on $road" else ""
            "arrive" -> "You've arrived"
            "roundabout", "rotary" -> "At the roundabout, take the exit$on"
            "merge" -> "Merge$on"
            "on ramp" -> "Take the ramp$on"
            "off ramp" -> "Take the exit$on"
            "fork" -> "Keep ${mod.ifBlank { "ahead" }} at the fork$on"
            "end of road" -> "At the end of the road, turn ${mod}$on"
            "continue", "new name" -> "Continue$on"
            else -> when (mod) { "uturn" -> "Make a U-turn$on"; "straight" -> "Go straight$on"; "" -> "Continue$on"; else -> "Turn $mod$on" }
        }
    }

    fun distance(m: Double, miles: Boolean): String = if (miles) {
        val mi = m / 1609.34; if (mi < 0.2) "${(m * 3.281).toInt()} ft" else "%.1f mi".format(mi)
    } else if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000)

    fun duration(s: Double): String { val m = (s / 60).toInt().coerceAtLeast(1); return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }

    /** Nearest distance (m) from a point to a route line — how far off course you are. */
    fun offRoute(here: GeoPoint, pts: List<GeoPoint>): Double {
        if (pts.size < 2) return 0.0
        var best = Double.MAX_VALUE
        val lat0 = Math.toRadians(here.latitude); val k = 111_320.0
        fun xy(g: GeoPoint) = Pair((g.longitude - here.longitude) * k * Math.cos(lat0), (g.latitude - here.latitude) * k)
        for (i in 0 until pts.size - 1) {
            val (ax, ay) = xy(pts[i]); val (bx, by) = xy(pts[i + 1])
            val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len
            val tc = t.coerceIn(0.0, 1.0)
            val px = ax + tc * dx; val py = ay + tc * dy
            best = minOf(best, Math.sqrt(px * px + py * py))
        }
        return best
    }
}

/** Categories for "nearby", as Overpass filters. */
val NEARBY = listOf(
    Triple("Restaurants", "[\"amenity\"~\"^(restaurant|fast_food)$\"]", "🍽"),
    Triple("Petrol", "[\"amenity\"=\"fuel\"]", "⛽"),
    Triple("Mosques", "[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]", "🕌"),
    Triple("Cafés", "[\"amenity\"=\"cafe\"]", "☕"),
    Triple("Groceries", "[\"shop\"~\"^(supermarket|convenience|greengrocer)$\"]", "🛒"),
    Triple("ATMs", "[\"amenity\"~\"^(atm|bank)$\"]", "🏧"),
    Triple("Pharmacies", "[\"amenity\"=\"pharmacy\"]", "💊"),
    Triple("Hospitals", "[\"amenity\"~\"^(hospital|clinic|doctors)$\"]", "🏥"),
    Triple("Parking", "[\"amenity\"=\"parking\"]", "🅿"),
    Triple("Hotels", "[\"tourism\"~\"^(hotel|guest_house|motel)$\"]", "🏨"),
    Triple("EV charging", "[\"amenity\"=\"charging_station\"]", "🔌"),
    Triple("Parks", "[\"leisure\"=\"park\"]", "🌳"),
    Triple("Gyms", "[\"leisure\"=\"fitness_centre\"]", "🏋"),
    Triple("Shopping", "[\"shop\"~\"^(mall|department_store|clothes)$\"]", "🛍"),
)

/**
 * Reads OpenStreetMap opening_hours ("Mo-Fr 09:00-17:00; Sa 10:00-14:00", "24/7", "Mo-Su 08:00-02:00") and
 * says what Google would: "Open · closes 22:00", "Closed · opens 09:00". Null when it can't tell.
 */
object Hours {
    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private fun days(spec: String): Set<Int> {
        if (spec.isBlank()) return (0..6).toSet()
        val out = mutableSetOf<Int>()
        spec.split(',').forEach { part ->
            val p = part.trim()
            if ('-' in p) { val a = DAYS.indexOf(p.substringBefore('-').take(2)); val b = DAYS.indexOf(p.substringAfter('-').take(2))
                if (a >= 0 && b >= 0) { var i = a; while (true) { out += i; if (i == b) break; i = (i + 1) % 7 } } }
            else DAYS.indexOf(p.take(2)).takeIf { it >= 0 }?.let { out += it }
        }
        return out
    }
    private fun mins(t: String) = t.trim().split(':').let { (it[0].toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.take(2)?.toIntOrNull() ?: 0) }

    /** Ranges (start, end) in minutes for each weekday; later rules replace earlier ones, as the format says. */
    private fun table(oh: String): Map<Int, List<Pair<Int, Int>>>? {
        val t = mutableMapOf<Int, List<Pair<Int, Int>>>()
        for (rule in oh.split(';').map { it.trim() }.filter { it.isNotBlank() }) {
            if (rule == "24/7") { (0..6).forEach { t[it] = listOf(0 to 1440) }; continue }
            val m = Regex("^((?:(?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?,?)*)\\s*(.*)$").find(rule) ?: return null
            val d = days(m.groupValues[1]); val times = m.groupValues[2].trim()
            if (times.equals("off", true) || times.equals("closed", true)) { d.forEach { t[it] = emptyList() }; continue }
            val ranges = times.split(',').mapNotNull { r -> r.trim().takeIf { '-' in it }?.let { mins(it.substringBefore('-')) to mins(it.substringAfter('-')) } }
            if (ranges.isEmpty()) return null
            d.forEach { t[it] = ranges }
        }
        return t
    }

    fun status(oh: String?, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Pair<Boolean, String>? {
        oh ?: return null
        if (oh.trim() == "24/7") return true to "Open 24 hours"
        val t = runCatching { table(oh) }.getOrNull() ?: return null
        val day = now.dayOfWeek.value - 1; val m = now.hour * 60 + now.minute
        fun fmt(x: Int) = "%02d:%02d".format((x / 60) % 24, x % 60)
        // yesterday's late-night hours running past midnight
        t[(day + 6) % 7].orEmpty().firstOrNull { (a, b) -> b < a && m < b }?.let { return true to "Open · closes ${fmt(it.second)}" }
        t[day].orEmpty().firstOrNull { (a, b) -> if (b <= a) m >= a else m in a until b }?.let { return true to "Open · closes ${fmt(it.second)}" }
        t[day].orEmpty().filter { it.first > m }.minByOrNull { it.first }?.let { return false to "Closed · opens ${fmt(it.first)}" }
        for (i in 1..7) t[(day + i) % 7].orEmpty().minByOrNull { it.first }?.let {
            return false to "Closed · opens ${if (i == 1) "tomorrow" else DAYS[(day + i) % 7]} ${fmt(it.first)}" }
        return false to "Closed"
    }
}
