package com.nizam.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.theme.DisplayFamily

/** Cards that shrink slightly under your thumb, the way App Store tiles do. */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    colour: Color? = null,
    content: @Composable () -> Unit
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, spring(dampingRatio = 0.55f), label = "press")
    Surface(
        shape = shape,
        color = colour ?: MaterialTheme.colorScheme.surface,
        modifier = modifier.scale(scale).selectable(selected = false, interactionSource = interaction, indication = null,
            onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onClick() })
    ) { content() }
}

/** The rounded-square gradient icon that makes a list of features read like a shelf of apps. */
@Composable
fun AppIcon(emoji: String, tint: Color, size: Dp = 56.dp) {
    // A proper app icon: a squircle lit from above (light at the top, deeper at the bottom), a soft
    // glow in its own colour, a glass highlight, and a crisp white glyph — emoji only when there's no glyph.
    val shape = RoundedCornerShape(size * 0.28f)
    val glyph = AppGlyphs.forEmoji(emoji)
    val top = androidx.compose.ui.graphics.lerp(tint, Color.White, 0.22f)
    val bottom = androidx.compose.ui.graphics.lerp(tint, Color.Black, 0.28f)
    Box(
        Modifier.size(size)
            .shadow(size * 0.14f, shape, ambientColor = tint, spotColor = tint)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, tint, bottom))),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.48f to Color.Transparent)))
        if (glyph != null) androidx.compose.material3.Icon(glyph, null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
        else Text(emoji, style = MaterialTheme.typography.headlineSmall.copy(fontSize = (size.value * 0.46f).sp))
    }
}

/** Emoji in the app's tile lists, drawn as real icons. */
object AppGlyphs {
    private val map: Map<String, androidx.compose.ui.graphics.vector.ImageVector> by lazy {
        val I = androidx.compose.material.icons.Icons.Rounded
        mapOf(
            "⚖️" to I.Gavel, "✅" to I.CheckCircle, "🕌" to I.NightsStay, "🤲" to I.VolunteerActivism, "📖" to I.MenuBook,
            "📜" to I.HistoryEdu, "💰" to I.AccountBalanceWallet, "🥗" to I.RestaurantMenu, "🏋️" to I.FitnessCenter,
            "🎓" to I.School, "🧠" to I.EmojiObjects, "🗒️" to I.StickyNote2, "📚" to I.LocalLibrary, "✍️" to I.Create,
            "💬" to I.FormatQuote, "🎥" to I.Videocam, "📸" to I.CameraAlt, "🎧" to I.Headphones, "🗣" to I.RecordVoiceOver,
            "🌤" to I.WbSunny, "🏘" to I.Home, "🔒" to I.Lock, "🛠" to I.Build, "🏦" to I.AccountBalance, "📷" to I.Scanner,
            "🧰" to I.HomeRepairService, "🌐" to I.Language, "📰" to I.Article, "⛑️" to I.LocalHospital, "🎬" to I.Movie,
            "🎵" to I.MusicNote, "🎙" to I.Mic, "📺" to I.LiveTv, "🔌" to I.Extension, "🛍" to I.LocalMall, "⚙️" to I.Settings,
            "🔑" to I.VpnKey, "🔍" to I.Search, "🧭" to I.Explore, "⏰" to I.Alarm, "💧" to I.Opacity, "😴" to I.Bedtime,
            "❤️" to I.Favorite, "📅" to I.Event, "🎯" to I.TrackChanges, "🌙" to I.DarkMode, "☀️" to I.LightMode, "🧘" to I.SelfImprovement,
            "🏃" to I.DirectionsRun, "💊" to I.LocalPharmacy, "🛒" to I.ShoppingCart, "✈️" to I.Flight, "🚗" to I.DirectionsCar,
            "📈" to I.TrendingUp, "💡" to I.Lightbulb, "🎮" to I.SportsEsports, "📝" to I.Edit, "👥" to I.Group, "🏠" to I.Home
        )
    }
    fun forEmoji(e: String) = map[e] ?: map[e.replace("\uFE0F", "")] ?: map[e + "\uFE0F"]
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle.uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily))
        }
        if (action != null && onAction != null) Text(action, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A full-width feature card with artwork gradient — the App Store "Today" look. */
@Composable
fun HeroCard(emoji: String, kicker: String, title: String, blurb: String, tint: Color, onClick: () -> Unit) {
    PressableCard(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(26.dp)) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(132.dp)
                    .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.25f), Color.Transparent))),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(emoji, style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(start = 20.dp))
            }
            Column(Modifier.padding(18.dp)) {
                Text(kicker.uppercase(), style = MaterialTheme.typography.labelSmall, color = tint)
                Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = DisplayFamily))
                Text(blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** An App Store list row: icon, name, blurb, and a pill button. */
@Composable
fun AppRow(emoji: String, name: String, blurb: String, tint: Color, action: String = "Open", onClick: () -> Unit) {
    PressableCard(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(emoji, tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(blurb, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)) {
                Text(action, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}


/** One place for the app's spacing and corner language, so screens stop drifting apart. */
object AtlasUi {
    val screenPadding = 18.dp
    val cardCorner = 10.dp
    val gap = 12.dp
    val bottomClearance = 72.dp     // clears the tab bar and the now-playing bar
}
