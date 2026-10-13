package com.nizam.app.ui.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mosque
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush as GBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Design tokens: the only spacing, corner, motion and colour-role values new screens use.
 * See DESIGN.md in the repository for the charter these serve.
 */

/** Spacing scale. Nothing else: if a gap isn't one of these, it's wrong. */
object Space {
    private val k get() = Density.factor
    val xxs get() = 2.dp * k
    val xs get() = 4.dp * k
    val s get() = 8.dp * k
    val m get() = 12.dp * k
    val l get() = 16.dp * k
    val xl get() = 24.dp * k
    val xxl get() = 32.dp * k
    val xxxl get() = 48.dp * k
    /** Side margin of every screen. */
    val page get() = 20.dp * k
}

/** How tightly screens are spaced: Compact, Comfortable or Large (for elders and low vision). Applies to screens opened after the change. */
object Density {
    @Volatile var factor = 1f
    fun load(c: android.content.Context) { factor = when (c.getSharedPreferences("density", 0).getString("level", "Comfortable")) { "Compact" -> 0.82f; "Large" -> 1.25f; else -> 1f } }
    fun set(c: android.content.Context, level: String) { c.getSharedPreferences("density", 0).edit().putString("level", level).apply(); load(c) }
    fun level(c: android.content.Context) = c.getSharedPreferences("density", 0).getString("level", "Comfortable") ?: "Comfortable"
}

/** Corner radii. Cards and sheets share one language with the theme's shapes. */
object Radius {
    val chip = RoundedCornerShape(10.dp)
    // squircles: continuous-curvature corners (see Kit2.Squircle)
    val field: androidx.compose.ui.graphics.Shape get() = Style.shape(14.dp)
    val card: androidx.compose.ui.graphics.Shape get() = Style.shape(20.dp)
    val tile: androidx.compose.ui.graphics.Shape get() = Style.shape(22.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val pill = RoundedCornerShape(50)
}

/** Motion: one easing family and a few springs, so everything moves the same way. */
object Motion {
    /** Emphasised deceleration: fast start, gentle landing. Used for things arriving. */
    val arrive = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    /** Things leaving: quick and quiet. */
    val leave = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    const val short = 160
    const val medium = 260
    const val long = 420
    /** Reduce motion: set from Comfort. Animations jump straight to their end. */
    @Volatile var still: Boolean = false
    /**
     * Calm: the clean, Muse-like default. Set from Comfort. When on, the busiest ornament
     * (edge-glow, morphing blobs, heavy parallax/mesh) renders quiet or static. The living sky,
     * companion and warm palette — Atlas's charm — are never touched by this.
     */
    @Volatile var calm: Boolean = true
    fun <T> press(): androidx.compose.animation.core.AnimationSpec<T> = if (still) snap() else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    fun <T> settle(): androidx.compose.animation.core.AnimationSpec<T> = if (still) snap() else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    fun <T> bouncy(): androidx.compose.animation.core.AnimationSpec<T> = if (still) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    fun <T> enter(): androidx.compose.animation.core.FiniteAnimationSpec<T> = if (still) snap() else tween(medium, easing = arrive)
    fun <T> exit(): androidx.compose.animation.core.FiniteAnimationSpec<T> = if (still) snap() else tween(short, easing = leave)
}

/**
 * The twelve Life hubs. Each keeps one accent (its "spine") on top of whichever theme is chosen,
 * so you always know where you are while the palette stays one family.
 */
enum class Hub(val label: String, val spine: Color, val icon: ImageVector) {
    Money("Money", Color(0xFFD4A24C), Icons.Outlined.AccountBalanceWallet),
    Deen("Deen", Color(0xFF3FA37C), Icons.Outlined.Mosque),
    Health("Health", Color(0xFF3DB2B0), Icons.Outlined.Favorite),
    Learn("Learn", Color(0xFF6C7FE0), Icons.Outlined.School),
    Home("Home", Color(0xFFC98B5A), Icons.Outlined.Home),
    Media("Media", Color(0xFFB36AD8), Icons.Outlined.PlayCircle),
    Memory("Memory", Color(0xFF8E97B8), Icons.Outlined.Lightbulb),
    Work("Work", Color(0xFF4F95D8), Icons.Outlined.WorkOutline),
    Social("Social", Color(0xFFE07A8C), Icons.Outlined.Groups),
    Tools("Tools", Color(0xFF8FA36B), Icons.Outlined.Build),
    Create("Create", Color(0xFFE38B4F), Icons.Outlined.Brush),
    Travel("Travel", Color(0xFF4DB0D6), Icons.Outlined.FlightTakeoff);

    /** The spine, nudged to stay readable on the current background. */
    @Composable
    @ReadOnlyComposable
    fun tint(): Color {
        val bg = MaterialTheme.colorScheme.background
        return if (bg.luminance() > 0.5f) spine.copy(red = spine.red * 0.78f, green = spine.green * 0.78f, blue = spine.blue * 0.78f) else spine
    }
}

/** A colour softly laid over the page, for tinted cards and icon wells. */
@Composable
@ReadOnlyComposable
fun wash(c: Color, amount: Float = 0.14f): Color = c.copy(alpha = amount).compositeOver(MaterialTheme.colorScheme.background)

/**
 * Frosted glass. A translucent surface, a faint top highlight and a hairline edge: reads as glass on
 * every Android version, without the cost of a live backdrop blur.
 */
fun Modifier.frost(shape: Shape = Radius.card, strength: Float = 0.86f): Modifier = composed {
    val c = MaterialTheme.colorScheme
    val light = c.background.luminance() > 0.5f
    val base = if (light) Color.White else c.surfaceBright
    val body = base.copy(alpha = if (light) strength else strength * 0.9f).compositeOver(c.background.copy(alpha = 0.6f))
    val shine = GBrush.verticalGradient(listOf(Color.White.copy(alpha = if (light) 0.55f else 0.07f), Color.Transparent))
    val edge = Color.White.copy(alpha = if (light) 0.7f else 0.09f)
    this.clip(shape).background(body).background(shine).border(BorderStroke(0.8.dp, edge), shape)
}

/** Haptics with meaning: a tick for selection, a firmer pulse for success, a double for warnings. */
class Haptics(private val view: View) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun press() { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    fun long() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    fun success() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }
    fun warn() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Elevation is expressed as tone, never as heavy shadows. */
object Depth {
    val none: Dp = 0.dp
    val raised: Dp = 2.dp
    val floating: Dp = 10.dp
}
