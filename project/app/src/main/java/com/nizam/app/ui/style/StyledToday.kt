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
    if (style != UiStyle.CLASSIC) SciToday(style, d, container, onOpen)
}

/** Numbers that roll up when they change, like an odometer. */
@Composable
fun RollingNumber(target: Double, format: (Double) -> String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    Text(format(a.value.toDouble()), style = style, color = color, maxLines = 1)
}

