package com.nizam.app.feature.rune

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Icons for Rune designs, three ways:
 *  1. any Material icon by name (thousands), in five styles
 *  2. emoji or symbols
 *  3. your own drawing: paste SVG path data
 */
object RuneIconLib {
    private val cache = HashMap<String, ImageVector?>()

    /** The short names older designs and templates use. */
    private val alias = mapOf(
        "heart" to "FavoriteBorder", "moon" to "NightsStay", "sun" to "WbSunny", "book" to "MenuBook", "wallet" to "AccountBalanceWallet",
        "chart" to "BarChart", "water" to "WaterDrop", "fitness" to "FitnessCenter", "food" to "Restaurant", "camera" to "CameraAlt",
        "chat" to "ChatBubbleOutline", "bell" to "Notifications", "fire" to "LocalFireDepartment", "leaf" to "Eco", "gift" to "CardGiftcard",
        "bulb" to "Lightbulb", "rocket" to "RocketLaunch", "group" to "Groups", "check" to "Check", "play" to "PlayArrow", "bolt" to "Bolt",
        "palette" to "Palette", "flag" to "Flag", "lock" to "Lock", "map" to "Map", "phone" to "Phone", "mail" to "Mail", "mosque" to "Mosque"
    )

    fun pascal(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "Star"
        alias[t.lowercase()]?.let { return it }
        if (t.any { it == '_' || it == '-' || it == ' ' }) return t.split('_', '-', ' ').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
        return t.replaceFirstChar { it.uppercase() }
    }

    fun vector(name: String, style: String = "outlined"): ImageVector? {
        val st = style.lowercase().let { if (it in setOf("outlined", "filled", "rounded", "sharp", "twotone")) it else "outlined" }
        val pn = pascal(name)
        val key = "$st/$pn"
        return synchronized(cache) { cache.getOrPut(key) { load(st, pn) } }
    }

    private fun load(style: String, pn: String): ImageVector? {
        fun tryStyle(s: String): ImageVector? = runCatching {
            val cap = s.replaceFirstChar { it.uppercase() }
            val recv = Class.forName("androidx.compose.material.icons.Icons\$$cap")
            val inst = recv.getField("INSTANCE").get(null)
            val kt = Class.forName("androidx.compose.material.icons.$s.${pn}Kt")
            kt.getMethod("get$pn", recv).invoke(null, inst) as? ImageVector
        }.getOrNull()
        return tryStyle(style) ?: tryStyle("outlined") ?: tryStyle("filled")
    }

    /** A starting set for the picker. Anything else can be typed by name. */
    val catalog: List<String> = listOf(
        "Star", "StarBorder", "Favorite", "FavoriteBorder", "Home", "Bolt", "NightsStay", "WbSunny", "Cloud", "Mosque", "MenuBook", "Book", "Check", "CheckCircle", "Add", "AddCircle", "Close", "Search",
        "Settings", "Person", "Groups", "AccountBalanceWallet", "Payments", "CreditCard", "Savings", "BarChart", "PieChart", "ShowChart", "TrendingUp", "TrendingDown", "Timer", "Alarm", "Schedule", "Today", "CalendarMonth", "Event",
        "WaterDrop", "FitnessCenter", "DirectionsRun", "DirectionsWalk", "DirectionsBike", "SelfImprovement", "Spa", "Restaurant", "LocalCafe", "Fastfood", "Cake", "MusicNote", "Headphones", "Mic", "VolumeUp", "PlayArrow", "Pause", "Stop", "SkipNext",
        "CameraAlt", "Photo", "Videocam", "Movie", "Mail", "Chat", "ChatBubbleOutline", "Forum", "Call", "Phone", "Message", "Send", "Share", "Map", "Place", "Navigation", "Explore", "Flight", "DirectionsCar", "Train",
        "Lock", "LockOpen", "Key", "Shield", "Security", "Fingerprint", "Visibility", "VisibilityOff", "Notifications", "NotificationsActive", "LocalFireDepartment", "Eco", "Grass", "Pets", "Park", "CardGiftcard", "Flag", "Lightbulb", "RocketLaunch", "Palette",
        "Brush", "Edit", "EditNote", "Create", "Description", "Article", "Note", "StickyNote2", "Folder", "Attachment", "Link", "QrCode", "Print", "Download", "Upload", "Cloud", "CloudDone", "Sync", "Refresh", "Delete",
        "Menu", "MoreVert", "MoreHoriz", "ArrowBack", "ArrowForward", "ArrowUpward", "ArrowDownward", "ExpandMore", "ExpandLess", "ChevronRight", "ChevronLeft", "KeyboardArrowDown", "OpenInNew", "Fullscreen", "ZoomIn", "FilterList", "Sort", "Tune",
        "Info", "Help", "Warning", "Error", "Done", "DoneAll", "Block", "ThumbUp", "ThumbDown", "EmojiEmotions", "SentimentSatisfied", "SentimentDissatisfied", "Mood", "Celebration", "EmojiEvents", "WorkspacePremium", "Verified", "Diamond", "Casino", "SportsEsports",
        "School", "MenuBook", "Translate", "Language", "Public", "Science", "Psychology", "Calculate", "Code", "Terminal", "Memory", "Smartphone", "Computer", "Watch", "Tv", "Wifi", "Bluetooth", "BatteryFull", "BatteryChargingFull", "PowerSettingsNew",
        "ShoppingCart", "ShoppingBag", "Store", "LocalOffer", "Receipt", "AccountBalance", "Work", "Business", "Handshake", "VolunteerActivism", "Mosque", "Church", "TempleBuddhist", "Bedtime", "LightMode", "DarkMode", "Thermostat", "Air", "Umbrella", "AcUnit",
        "Anchor", "Apartment", "Bed", "Bathtub", "Weekend", "Kitchen", "Cottage", "Hotel", "Luggage", "Hiking", "Kayaking", "Sailing", "Surfing", "Skateboarding", "Sports", "SportsSoccer", "SportsCricket", "Pool", "Coffee", "Lunch"
    ).distinct()
    val names: List<String> get() = catalog
}

/** Draws pasted SVG path data (24-unit box by default) as an icon. */
@Composable
fun RuneSvgIcon(d: String, vb: Float, fill: Boolean, strokeW: Float, color: Color, sizeDp: Float) {
    val path = remember(d) { runCatching { PathParser().parsePathString(d).toPath() }.getOrNull() }
    Canvas(Modifier.size(sizeDp.dp)) {
        val p = path ?: return@Canvas
        val k = size.width / vb.coerceAtLeast(1f)
        withTransform({ scale(k, k, Offset.Zero) }) {
            if (fill) drawPath(p, color) else drawPath(p, color, style = Stroke(strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

/** Search any icon, or type its Material name directly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuneIconPicker(current: String, style: String, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val m = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(current, onPick, singleLine = true, label = { Text("Icon name (any Material icon)", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Search the picker", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
        val list = remember(q) { val k = q.trim().replace(" ", "").lowercase(); RuneIconLib.catalog.filter { k.isEmpty() || it.lowercase().contains(k) }.take(60) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { nm ->
                val v = remember(nm, style) { RuneIconLib.vector(nm, style) }
                if (v != null) Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (RuneIconLib.pascal(current) == nm) m.primary.copy(alpha = 0.25f) else m.surfaceVariant)
                    .border(1.dp, m.outline.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).clickable { onPick(nm) }, Alignment.Center) { Icon(v, nm, Modifier.size(24.dp), m.onSurface) }
            }
        }
    }
}

