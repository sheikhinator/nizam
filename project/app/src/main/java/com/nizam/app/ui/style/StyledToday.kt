package com.nizam.app.ui.style

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.parallax
import com.nizam.app.ui.design.pressable
import com.nizam.app.ui.design.wash
import com.nizam.app.ui.nav.Routes
import kotlin.math.absoluteValue

/** Everything the styled Todays show, gathered once by the Today screen. */
data class TodayData(
    val greeting: String,
    val dateLine: String,
    val nextPrayer: Pair<String, Int>?,        // name, minutes away
    val prayers: List<Pair<String, Boolean>>,  // Fajr..Isha, done
    val openTasks: List<String>,
    val openCount: Int,
    val doneToday: Int,
    val spentToday: Double,
    val spentMonth: Double,
    val currency: String,
    val kcal: Int, val kcalTarget: Int,
    val waterMl: Int, val waterTarget: Int,
    val sets: Int,
    val quote: String?,
    val quoteBy: String?,
    val insights: List<Pair<String, String>>,  // headline, body
    val unread: Int,
)

fun money(v: Double, cur: String) = "$cur " + "%,.0f".format(v)
fun countdown(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

/** The Today page in the chosen style (Classic is drawn by TodayScreen itself). */
@Composable
fun StyledToday(style: UiStyle, d: TodayData, container: AppContainer, onOpen: (String) -> Unit) {
    when (style) {
        UiStyle.DECK -> DeckToday(d, container, onOpen)
        UiStyle.BRIDGE -> BridgeToday(d, container, onOpen)
        UiStyle.MAGAZINE -> MagazineToday(d, container, onOpen)
        UiStyle.LAYERS -> LayersToday(d, container, onOpen)
        UiStyle.CLASSIC -> {}
    }
}

/** Numbers that roll up when they change, like an odometer. */
@Composable
fun RollingNumber(target: Double, format: (Double) -> String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    Text(format(a.value.toDouble()), style = style, color = color, maxLines = 1)
}

// ═════════════════════════════════════ LIVING DECK ═════════════════════════════════════
private data class DeckCard(val title: String, val hub: Hub, val route: String, val big: String, val small: String, val back: List<Pair<String, String>>, val progress: Float?)

@Composable
private fun DeckToday(d: TodayData, container: AppContainer, onOpen: (String) -> Unit) {
    val cards = remember(d) { listOf(
        DeckCard("Next prayer", Hub.Deen, Routes.PRAYER, d.nextPrayer?.first ?: "Done for today",
            d.nextPrayer?.let { "in " + countdown(it.second) } ?: "All five are behind you",
            d.prayers.map { (n, ok) -> n to if (ok) "prayed" else "—" }, d.prayers.count { it.second } / 5f),
        DeckCard("Money", Hub.Money, Routes.FINANCE, money(d.spentToday, d.currency), "spent today · " + money(d.spentMonth, d.currency) + " this month",
            listOf("Today" to money(d.spentToday, d.currency), "This month" to money(d.spentMonth, d.currency)), null),
        DeckCard("Tasks", Hub.Work, Routes.TASKS, "${d.openCount} open", if (d.doneToday > 0) "${d.doneToday} done today" else "Nothing ticked yet",
            d.openTasks.take(5).map { it to "" }, if (d.openCount + d.doneToday == 0) 0f else d.doneToday.toFloat() / (d.openCount + d.doneToday)),
        DeckCard("Body", Hub.Health, Routes.DIET, "${d.waterMl / 100 / 10.0} L", "water · ${d.kcal} kcal · ${d.sets} sets",
            listOf("Water" to "${d.waterMl} / ${d.waterTarget} ml", "Food" to "${d.kcal} / ${d.kcalTarget} kcal", "Training" to "${d.sets} sets"), d.waterMl / d.waterTarget.coerceAtLeast(1).toFloat()),
        DeckCard("Thought", Hub.Memory, Routes.QUOTES, d.quote?.take(90) ?: "Write one line about today", d.quoteBy ?: "",
            d.insights.take(4), null),
    ) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(d.dateLine, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(d.greeting, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            com.nizam.app.feature.agent.VoiceCommandBar(container, onOpen, Modifier.padding(top = 12.dp))
        }
        val pager = rememberPagerState(pageCount = { cards.size })
        HorizontalPager(pager, Modifier.fillMaxWidth().height(330.dp).padding(top = 14.dp), contentPadding = PaddingValues(horizontal = 44.dp), pageSpacing = 8.dp) { page ->
            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
            val o = offset.absoluteValue.coerceIn(0f, 1f)
            DeckCardView(cards[page], Modifier.graphicsLayer {
                scaleX = 1f - 0.12f * o; scaleY = 1f - 0.12f * o
                rotationZ = offset * -6f; rotationY = offset * 18f
                alpha = 1f - 0.35f * o; cameraDistance = 14f * density
            }, onOpen)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            repeat(cards.size) { i -> Box(Modifier.padding(3.dp).size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp).clip(CircleShape)
                .background(if (i == pager.currentPage) cards[i].hub.tint() else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))) }
        }
        Text("Swipe through · tap to open · hold to flip · fling up to jump in", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
        // a fanned hand of what you usually open now
        FannedTools(onOpen)
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun DeckCardView(card: DeckCard, modifier: Modifier, onOpen: (String) -> Unit) {
    var flipped by remember { mutableStateOf(false) }
    val rot by animateFloatAsState(if (flipped) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = 260f), label = "flip")
    var lift by remember { mutableStateOf(0f) }
    val tint = card.hub.tint()
    Box(modifier.fillMaxSize().graphicsLayer { rotationY = rot; translationY = lift; cameraDistance = 18f * density }
        .shadow(18.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp))
        .background(Brush.linearGradient(listOf(wash(tint, 0.55f), wash(tint, 0.18f))))
        .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(30.dp))
        .pointerInput(card.route) { detectTapGestures(onTap = { if (!flipped) onOpen(card.route) else flipped = false }, onLongPress = { flipped = !flipped }) }
        .pointerInput(card.route) { detectVerticalDragGestures(onDragEnd = { if (lift < -140f) onOpen(card.route); lift = 0f }, onDragCancel = { lift = 0f }) { ch, dy -> ch.consume(); lift = (lift + dy).coerceIn(-260f, 40f) } }
        .padding(22.dp)) {
        if (rot <= 90f) Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(card.hub.icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
                Text(card.title.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tint, modifier = Modifier.padding(start = 10.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(card.big, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(card.small, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            card.progress?.let { p ->
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(8.dp).clip(CircleShape).background(tint)) }
            }
        } else Column(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
            Text(card.title + " · details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tint)
            Spacer(Modifier.height(10.dp))
            card.back.ifEmpty { listOf("Nothing yet" to "") }.forEach { (a, b) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(a, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (b.isNotBlank()) Text(b, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 2)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Open ${card.title} ›", color = tint, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpen(card.route) })
        }
    }
}

@Composable
private fun FannedTools(onOpen: (String) -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current
    val likely = remember { com.nizam.app.feature.brain2.Habits2.likelyNow(c).mapNotNull { com.nizam.app.ui.screens.hubs.HubRegistry.byRoute(it) }.take(6)
        .ifEmpty { com.nizam.app.ui.screens.hubs.RecentFeatures.recent(c).mapNotNull { com.nizam.app.ui.screens.hubs.HubRegistry.byRoute(it) }.take(6) } }
    if (likely.isEmpty()) return
    Text("YOUR HAND", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 22.dp))
    Box(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        val n = likely.size
        likely.forEachIndexed { i, f ->
            val mid = (n - 1) / 2f
            val angle = (i - mid) * 9f
            Box(Modifier.offset(x = ((i - mid) * 48).dp, y = ((i - mid).absoluteValue * 6).dp).graphicsLayer { rotationZ = angle; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1.6f) }
                .size(86.dp, 120.dp).shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(wash(f.hub.tint(), 0.45f), wash(f.hub.tint(), 0.2f))))
                .pressable { onOpen(f.route) }.padding(10.dp)) {
                Column {
                    androidx.compose.material3.Icon(f.icon, null, tint = f.hub.tint(), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.weight(1f))
                    Text(f.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ═════════════════════════════════════ COMMAND BRIDGE ═════════════════════════════════════
@Composable
private fun BridgeToday(d: TodayData, container: AppContainer, onOpen: (String) -> Unit) {
    val K = StyleKit
    val t = rememberInfiniteTransition(label = "bridge")
    val scan by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "scan")
    val hour = java.time.LocalTime.now().hour
    val horizon = when (hour) { in 5..7 -> Color(0xFFFF8A4C); in 8..16 -> K.bridgeCyan; in 17..19 -> Color(0xFFFF5C8A); else -> Color(0xFF6C7FE0) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(K.bridgeInk, Color(0xFF07101A), K.bridgeInk)))) {
        // horizon line and a slow scan sweep
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height * 0.26f
            drawLine(Brush.horizontalGradient(listOf(Color.Transparent, horizon.copy(alpha = 0.55f), Color.Transparent)), Offset(0f, y), Offset(size.width, y), 2f)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, K.bridgeCyan.copy(alpha = 0.05f), Color.Transparent), startY = size.height * scan - 80f, endY = size.height * scan + 80f))
            for (i in 0..12) { val x = size.width * i / 12f; drawLine(K.bridgeLine.copy(alpha = 0.25f), Offset(x, y), Offset(size.width / 2 + (x - size.width / 2) * 3f, size.height), 1f) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ATLAS · BRIDGE   ${d.dateLine.uppercase()}", fontFamily = K.mono, fontSize = 11.sp, color = K.bridgeDim, letterSpacing = 1.sp)
            Text(d.greeting, fontFamily = K.mono, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = K.bridgeText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Readout("NEXT PRAYER", d.nextPrayer?.let { countdown(it.second) } ?: "CLEAR", d.nextPrayer?.first?.uppercase() ?: "ALL DONE", K.bridgeLime, Modifier.weight(1f)) { onOpen(Routes.PRAYER) }
                Readout("OUT TODAY", "%,.0f".format(d.spentToday), d.currency, K.bridgeAmber, Modifier.weight(1f)) { onOpen(Routes.FINANCE) }
                Readout("TASKS", "${d.openCount}", "OPEN", K.bridgeCyan, Modifier.weight(1f)) { onOpen(Routes.TASKS) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Panel("PRAYERS", K.bridgeLime, Modifier.weight(1f), { onOpen(Routes.PRAYER) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { d.prayers.forEach { (n, ok) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(3.dp)).background(if (ok) K.bridgeLime else K.bridgeLine))
                            Text(n.take(3).uppercase(), fontFamily = K.mono, fontSize = 9.sp, color = K.bridgeDim) } } }
                }
                Panel("HYDRATION", K.bridgeCyan, Modifier.weight(1f), { onOpen(Routes.DIET) }) { Gauge(d.waterMl / d.waterTarget.coerceAtLeast(1).toFloat(), K.bridgeCyan, "${d.waterMl} ml") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Panel("FUEL", K.bridgeAmber, Modifier.weight(1f), { onOpen(Routes.DIET) }) { Gauge(d.kcal / d.kcalTarget.coerceAtLeast(1).toFloat(), K.bridgeAmber, "${d.kcal} kcal") }
                Panel("TRAINING", K.bridgeRose, Modifier.weight(1f), { onOpen(Routes.GYM) }) { Gauge(d.sets / 12f, K.bridgeRose, "${d.sets} sets") }
            }
            Panel("QUEUE", K.bridgeCyan, Modifier.fillMaxWidth(), { onOpen(Routes.TASKS) }) {
                if (d.openTasks.isEmpty()) Text("> queue empty", fontFamily = K.mono, fontSize = 12.sp, color = K.bridgeDim)
                d.openTasks.take(5).forEachIndexed { i, s -> Text("%02d  %s".format(i + 1, s), fontFamily = K.mono, fontSize = 12.sp, color = K.bridgeText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Panel("LOG", K.bridgeDim, Modifier.fillMaxWidth(), { onOpen(Routes.INSIGHTS) }) {
                (d.insights.map { it.first } + listOfNotNull(d.quote?.let { "\"$it\"" })).ifEmpty { listOf("systems nominal") }.take(5)
                    .forEach { Text("> " + it, fontFamily = K.mono, fontSize = 12.sp, color = K.bridgeText.copy(alpha = 0.85f), maxLines = 2) }
            }
            Box(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, K.bridgeLine, RoundedCornerShape(10.dp))) {
                com.nizam.app.feature.agent.VoiceCommandBar(container, onOpen, Modifier) }
            Spacer(Modifier.height(110.dp))
        }
    }
}

@Composable
private fun Readout(label: String, value: String, unit: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val K = StyleKit
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(K.bridgePanel).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).pressable { onClick() }.padding(10.dp)) {
        Text(label, fontFamily = K.mono, fontSize = 9.sp, color = K.bridgeDim, letterSpacing = 1.sp)
        Text(value, fontFamily = K.mono, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(unit, fontFamily = K.mono, fontSize = 9.sp, color = color.copy(alpha = 0.7f), maxLines = 1)
    }
}

@Composable
private fun Panel(label: String, color: Color, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val K = StyleKit
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(K.bridgePanel.copy(alpha = 0.92f)).border(1.dp, K.bridgeLine, RoundedCornerShape(8.dp)).pressable { onClick() }.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text("  $label", fontFamily = K.mono, fontSize = 10.sp, color = K.bridgeDim, letterSpacing = 1.sp)
        }
        content()
    }
}

@Composable
private fun Gauge(fraction: Float, color: Color, label: String) {
    val a by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(900), label = "gauge")
    Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(70.dp)) {
            val st = Stroke(7f, cap = StrokeCap.Round)
            drawArc(StyleKit.bridgeLine, 135f, 270f, false, style = st)
            drawArc(color, 135f, 270f * a, false, style = st)
        }
        Text(label, fontFamily = StyleKit.mono, fontSize = 10.sp, color = StyleKit.bridgeText, textAlign = TextAlign.Center)
    }
}

// ═════════════════════════════════════ MAGAZINE ═════════════════════════════════════
@Composable
private fun MagazineToday(d: TodayData, container: AppContainer, onOpen: (String) -> Unit) {
    val serif = StyleKit.serif
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val issue = java.time.LocalDate.now().dayOfYear
    val headline = buildList {
        d.nextPrayer?.let { add("${it.first} in ${countdown(it.second)}") }
        add("${d.openCount} task${if (d.openCount == 1) "" else "s"} ahead")
        if (d.spentToday > 0) add(money(d.spentToday, d.currency) + " spent")
    }.joinToString(" · ")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("ATLAS", fontFamily = serif, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, modifier = Modifier.weight(1f))
            Text("No. $issue", fontFamily = serif, fontStyle = FontStyle.Italic, color = muted)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(ink)); Spacer(Modifier.height(2.dp)); Box(Modifier.fillMaxWidth().height(1.dp).background(ink))
        Text(d.dateLine.uppercase(), fontFamily = serif, fontSize = 11.sp, letterSpacing = 2.sp, color = muted, modifier = Modifier.padding(vertical = 6.dp))
        Text(headline, fontFamily = serif, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Text(d.greeting + ". Here is your day, in print.", fontFamily = serif, fontStyle = FontStyle.Italic, fontSize = 16.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
        com.nizam.app.feature.agent.VoiceCommandBar(container, onOpen, Modifier.padding(top = 14.dp))
        // lead story
        Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(wash(Hub.Deen.tint(), 0.14f)).pressable { onOpen(Routes.PRAYER) }.padding(18.dp)) {
            Text("THE LEAD", fontFamily = serif, fontSize = 11.sp, letterSpacing = 2.sp, color = Hub.Deen.tint(), fontWeight = FontWeight.Bold)
            Text(d.nextPrayer?.first ?: "The prayers are done", fontFamily = serif, fontSize = 48.sp, fontWeight = FontWeight.Black)
            Text(d.nextPrayer?.let { "arrives in ${countdown(it.second)}. ${d.prayers.count { p -> p.second }} of five so far today." } ?: "All five prayed. A quiet evening ahead.",
                fontFamily = serif, fontSize = 16.sp, color = muted)
        }
        // two columns of stories
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Story("MONEY", money(d.spentToday, d.currency), "spent today; ${money(d.spentMonth, d.currency)} across the month.", Hub.Money.tint()) { onOpen(Routes.FINANCE) }
                Story("BODY", "${d.waterMl / 100 / 10.0} litres", "of water, ${d.kcal} kcal eaten and ${d.sets} sets trained.", Hub.Health.tint()) { onOpen(Routes.DIET) }
            }
            Box(Modifier.width(1.dp).height(260.dp).background(muted.copy(alpha = 0.3f)))
            Column(Modifier.weight(1f)) {
                Story("WORK", "${d.openCount} open", if (d.openTasks.isEmpty()) "Nothing waiting." else d.openTasks.take(3).joinToString("; ") + ".", Hub.Work.tint()) { onOpen(Routes.TASKS) }
                Story("DONE", "${d.doneToday} ticked", "off the list so far today.", accent) { onOpen(Routes.TASKS) }
            }
        }
        d.quote?.let { q ->
            Box(Modifier.padding(vertical = 20.dp).fillMaxWidth().height(1.dp).background(muted.copy(alpha = 0.3f)))
            Text("“$q”", fontFamily = serif, fontStyle = FontStyle.Italic, fontSize = 24.sp, lineHeight = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().pressable { onOpen(Routes.QUOTES) })
            d.quoteBy?.takeIf { it.isNotBlank() }?.let { Text("— $it", fontFamily = serif, color = muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
        }
        if (d.insights.isNotEmpty()) {
            Text("IN BRIEF", fontFamily = serif, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp))
            d.insights.take(4).forEach { (h, b) ->
                Row(Modifier.padding(top = 8.dp)) {
                    Text("■ ", color = accent, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    Column { Text(h, fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text(b, fontFamily = serif, fontSize = 13.sp, color = muted, maxLines = 3) }
                }
            }
        }
        Spacer(Modifier.height(130.dp))
    }
}

@Composable
private fun Story(kicker: String, title: String, body: String, color: Color, onClick: () -> Unit) {
    Column(Modifier.padding(bottom = 16.dp).pressable { onClick() }) {
        Text(kicker, fontFamily = StyleKit.serif, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = color)
        Text(title, fontFamily = StyleKit.serif, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
        Text(body, fontFamily = StyleKit.serif, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
}

// ═════════════════════════════════════ LIVING LAYERS ═════════════════════════════════════
@Composable
private fun LayersToday(d: TodayData, container: AppContainer, onOpen: (String) -> Unit) {
    val hour = java.time.LocalTime.now().hour
    val (top, bottom, orb) = when (hour) {
        in 5..7 -> Triple(Color(0xFFFFB38A), Color(0xFF6A5ACD), Color(0xFFFFE0B2))
        in 8..15 -> Triple(Color(0xFF6EC6FF), Color(0xFF2B5CB8), Color(0xFFFFF59D))
        in 16..18 -> Triple(Color(0xFFFFB74D), Color(0xFF8E3B6B), Color(0xFFFFCC80))
        else -> Triple(Color(0xFF1A2350), Color(0xFF05060F), Color(0xFFE3F2FD))
    }
    val t = rememberInfiniteTransition(label = "layers")
    val drift by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Box(Modifier.fillMaxSize()) {
        // the sky, the sun or moon, and far hills: each on its own depth
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top.copy(alpha = 0.55f), bottom.copy(alpha = 0.35f), MaterialTheme.colorScheme.background))))
        Canvas(Modifier.fillMaxSize().parallax(0.4f)) {
            drawCircle(Brush.radialGradient(listOf(orb, orb.copy(alpha = 0f)), center = Offset(size.width * (0.78f + drift * 0.03f), size.height * 0.12f), radius = 160f),
                radius = 160f, center = Offset(size.width * (0.78f + drift * 0.03f), size.height * 0.12f))
            drawCircle(orb.copy(alpha = 0.9f), 34f, Offset(size.width * (0.78f + drift * 0.03f), size.height * 0.12f))
        }
        Canvas(Modifier.fillMaxSize().parallax(0.8f)) {
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(0f, size.height * 0.34f)
                cubicTo(size.width * 0.3f, size.height * 0.28f, size.width * 0.6f, size.height * 0.38f, size.width, size.height * 0.31f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(p, Brush.verticalGradient(listOf(bottom.copy(alpha = 0.35f), Color.Transparent), startY = size.height * 0.28f, endY = size.height * 0.6f))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.parallax(0.2f).padding(top = 8.dp)) {
                Text(d.dateLine, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(d.greeting, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            com.nizam.app.feature.agent.VoiceCommandBar(container, onOpen, Modifier)
            // nearest layer: the most important thing right now
            FloatCard(depth = 1.4f, elevation = 26.dp, tint = Hub.Deen.tint(), onClick = { onOpen(Routes.PRAYER) }) {
                Text("NEXT", style = MaterialTheme.typography.labelMedium, color = Hub.Deen.tint(), fontWeight = FontWeight.Bold)
                Text(d.nextPrayer?.first ?: "All prayers done", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
                d.nextPrayer?.let { Text("in " + countdown(it.second), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { d.prayers.forEach { (n, ok) ->
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) Hub.Deen.tint() else Hub.Deen.tint().copy(alpha = 0.2f))) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatCard(Modifier.weight(1f), depth = 1.0f, elevation = 16.dp, tint = Hub.Money.tint(), onClick = { onOpen(Routes.FINANCE) }) {
                    Text("SPENT", style = MaterialTheme.typography.labelSmall, color = Hub.Money.tint(), fontWeight = FontWeight.Bold)
                    RollingNumber(d.spentToday, { money(it, d.currency) }, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), MaterialTheme.colorScheme.onSurface)
                    Text("today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FloatCard(Modifier.weight(1f), depth = 1.0f, elevation = 16.dp, tint = Hub.Work.tint(), onClick = { onOpen(Routes.TASKS) }) {
                    Text("TASKS", style = MaterialTheme.typography.labelSmall, color = Hub.Work.tint(), fontWeight = FontWeight.Bold)
                    RollingNumber(d.openCount.toDouble(), { "%.0f open".format(it) }, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), MaterialTheme.colorScheme.onSurface)
                    Text("${d.doneToday} done", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FloatCard(depth = 0.6f, elevation = 8.dp, tint = Hub.Health.tint(), onClick = { onOpen(Routes.DIET) }) {
                Text("BODY", style = MaterialTheme.typography.labelSmall, color = Hub.Health.tint(), fontWeight = FontWeight.Bold)
                listOf(Triple("Water", d.waterMl / d.waterTarget.coerceAtLeast(1).toFloat(), "${d.waterMl} ml"), Triple("Food", d.kcal / d.kcalTarget.coerceAtLeast(1).toFloat(), "${d.kcal} kcal"),
                    Triple("Training", d.sets / 12f, "${d.sets} sets")).forEach { (n, f, v) ->
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(70.dp))
                        Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(Hub.Health.tint().copy(alpha = 0.15f))) {
                            Box(Modifier.fillMaxWidth(f.coerceIn(0.02f, 1f)).height(8.dp).clip(CircleShape).background(Hub.Health.tint())) }
                        Text(v, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            d.quote?.let { q -> FloatCard(depth = 0.3f, elevation = 2.dp, tint = Hub.Memory.tint(), onClick = { onOpen(Routes.QUOTES) }) {
                Text("“$q”", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic) } }
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
private fun FloatCard(modifier: Modifier = Modifier.fillMaxWidth(), depth: Float, elevation: androidx.compose.ui.unit.Dp, tint: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier.parallax(depth).shadow(elevation, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)).background(Brush.linearGradient(listOf(tint.copy(alpha = 0.16f), Color.Transparent)))
        .border(1.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(26.dp)).pressable { onClick() }.padding(18.dp)) { content() }
}
