package com.nizam.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The default look: soft neutrals rather than pure black or white (easier on the eyes over long
 * use), one calm accent, and surfaces that separate by the faintest change in tone instead of
 * borders and shadows.
 */
private val Calm = AtlasPalette(
    "", "Calm",
    surface = Color(0xFF0F1013), card = Color(0xFF17181C), ink = Color(0xFFECEDEF), inkMuted = Color(0xFF8C9099),
    primary = Color(0xFF5CC8A8), accent = Color(0xFFE0895E)
)
private val CalmLight = AtlasPalette(
    "", "Calm light",
    surface = Color(0xFFF6F6F3), card = Color(0xFFFFFFFF), ink = Color(0xFF16171A), inkMuted = Color(0xFF6A6E75),
    primary = Color(0xFF1E7A66), accent = Color(0xFFB9602F)
)

/**
 * Every Material colour role, derived from one palette. Material's own defaults for the roles we
 * don't mention are lavender, and they leak into the tab bar, sheets and fields — so all are set.
 */
fun schemeFor(p: AtlasPalette): ColorScheme {
    val light = p.surface.luminance() > 0.5f
    fun over(c: Color, a: Float) = c.copy(alpha = a).compositeOver(p.surface)
    val field = over(p.ink, if (light) 0.05f else 0.07f)
    val hairline = over(p.ink, if (light) 0.09f else 0.10f)
    val primaryTint = over(p.primary, if (light) 0.12f else 0.18f)
    val onPrimary = if (p.primary.luminance() > 0.45f) Color(0xFF0B0C0E) else Color.White
    return if (light) lightColorScheme(
        primary = p.primary, onPrimary = onPrimary,
        primaryContainer = primaryTint, onPrimaryContainer = p.ink,
        secondary = p.accent, onSecondary = Color.White,
        secondaryContainer = primaryTint, onSecondaryContainer = p.ink,
        tertiary = p.accent, onTertiary = Color.White,
        tertiaryContainer = over(p.accent, 0.14f), onTertiaryContainer = p.ink,
        background = p.surface, onBackground = p.ink,
        surface = p.card, onSurface = p.ink,
        surfaceVariant = field, onSurfaceVariant = p.inkMuted,
        surfaceTint = Color.Transparent,
        surfaceBright = p.card, surfaceDim = p.surface,
        surfaceContainerLowest = p.card, surfaceContainerLow = p.card,
        surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = field,
        inverseSurface = p.ink, inverseOnSurface = p.surface, inversePrimary = p.primary,
        outline = hairline, outlineVariant = hairline,
        error = DangerLight, onError = Color.White,
        errorContainer = over(DangerLight, 0.12f), onErrorContainer = p.ink,
        scrim = Color.Black
    ) else darkColorScheme(
        primary = p.primary, onPrimary = onPrimary,
        primaryContainer = primaryTint, onPrimaryContainer = p.ink,
        secondary = p.accent, onSecondary = Color(0xFF0B0C0E),
        secondaryContainer = primaryTint, onSecondaryContainer = p.ink,
        tertiary = p.accent, onTertiary = Color(0xFF0B0C0E),
        tertiaryContainer = over(p.accent, 0.18f), onTertiaryContainer = p.ink,
        background = p.surface, onBackground = p.ink,
        surface = p.card, onSurface = p.ink,
        surfaceVariant = field, onSurfaceVariant = p.inkMuted,
        surfaceTint = Color.Transparent,
        surfaceBright = over(p.ink, 0.10f), surfaceDim = p.surface,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.card,
        surfaceContainer = p.card, surfaceContainerHigh = over(p.ink, 0.08f), surfaceContainerHighest = field,
        inverseSurface = p.ink, inverseOnSurface = p.surface, inversePrimary = p.primary,
        outline = hairline, outlineVariant = hairline,
        error = DangerDark, onError = Color(0xFF0B0C0E),
        errorContainer = over(DangerDark, 0.18f), onErrorContainer = p.ink,
        scrim = Color.Black
    )
}

// Soft, even corners: one language for fields, cards and sheets.
val NizamShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NizamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    paletteKey: String = "",
    customPaletteJson: String = "",
    content: @Composable () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val comfort = com.nizam.app.ui.design.Comfort.state(ctx)
    com.nizam.app.ui.design.Style.load(ctx)
    val look = com.nizam.app.ui.design.Style.s
    val palette = when {
        paletteKey.isBlank() -> if (darkTheme) paletteFor("instruments") else paletteFor("labpaper")
        paletteKey == "custom" -> customPaletteFrom(customPaletteJson) ?: paletteFor("void")
        else -> paletteFor(paletteKey)
    }
    var scheme = schemeFor(palette)
    // Material You: colours drawn from your wallpaper (Android 12 and later)
    if (comfort.wallpaperColours && android.os.Build.VERSION.SDK_INT >= 31) {
        val dark = palette.surface.luminance() < 0.5f
        val you = if (dark) androidx.compose.material3.dynamicDarkColorScheme(ctx) else androidx.compose.material3.dynamicLightColorScheme(ctx)
        scheme = scheme.copy(primary = you.primary, onPrimary = you.onPrimary, primaryContainer = you.primaryContainer,
            onPrimaryContainer = you.onPrimaryContainer, secondary = you.secondary, tertiary = you.tertiary)
    }
    // True black for AMOLED screens: saves battery and looks deeper at night
    if (comfort.oled && scheme.background.luminance() < 0.5f) {
        scheme = scheme.copy(background = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
            surface = Color(0xFF0C0C0E), surfaceContainerLow = Color(0xFF0C0C0E), surfaceContainer = Color(0xFF0C0C0E))
    }
    // High contrast: pure text on the background, stronger outlines
    if (comfort.highContrast) {
        val dark = scheme.background.luminance() < 0.5f
        val ink = if (dark) Color.White else Color.Black
        scheme = scheme.copy(onBackground = ink, onSurface = ink, onSurfaceVariant = ink.copy(alpha = 0.85f),
            outline = ink.copy(alpha = 0.7f), outlineVariant = ink.copy(alpha = 0.45f))
    }
    var type = (if (comfort.textScale == 1f) NizamTypography else NizamTypography.scaled(comfort.textScale)).styled(look.font, look.expressive)
    if (comfort.easyRead) type = type.easyReading()
    if (comfort.boldText) type = type.bolder()
    MaterialTheme(
        colorScheme = scheme,
        typography = type,
        shapes = NizamShapes
    ) {
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(comfort.keepAwake) {
            view.keepScreenOn = comfort.keepAwake
            onDispose { }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides (if (comfort.bigTouch) 60.dp else 48.dp)
        ) {
            if (comfort.warmTint) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    content()
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                        .background(Color(0x22FF8A00)))
                }
            } else content()
        }
    }
}

/** Every text style, grown or shrunk together (the Text size setting). */
fun androidx.compose.material3.Typography.scaled(k: Float): androidx.compose.material3.Typography {
    fun androidx.compose.ui.text.TextStyle.x() = copy(fontSize = fontSize * k,
        lineHeight = if (lineHeight == androidx.compose.ui.unit.TextUnit.Unspecified) lineHeight else lineHeight * k)
    return copy(
        displayLarge = displayLarge.x(), displayMedium = displayMedium.x(), displaySmall = displaySmall.x(),
        headlineLarge = headlineLarge.x(), headlineMedium = headlineMedium.x(), headlineSmall = headlineSmall.x(),
        titleLarge = titleLarge.x(), titleMedium = titleMedium.x(), titleSmall = titleSmall.x(),
        bodyLarge = bodyLarge.x(), bodyMedium = bodyMedium.x(), bodySmall = bodySmall.x(),
        labelLarge = labelLarge.x(), labelMedium = labelMedium.x(), labelSmall = labelSmall.x()
    )
}

/** Easy reading: calmer, wider letter and line spacing for tired eyes and dyslexia. */
fun androidx.compose.material3.Typography.easyReading(): androidx.compose.material3.Typography {
    fun androidx.compose.ui.text.TextStyle.e() = copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
        letterSpacing = 0.6.sp, lineHeight = if (lineHeight == androidx.compose.ui.unit.TextUnit.Unspecified) lineHeight else lineHeight * 1.2f)
    return copy(
        headlineLarge = headlineLarge.e(), headlineMedium = headlineMedium.e(), headlineSmall = headlineSmall.e(),
        titleLarge = titleLarge.e(), titleMedium = titleMedium.e(), titleSmall = titleSmall.e(),
        bodyLarge = bodyLarge.e(), bodyMedium = bodyMedium.e(), bodySmall = bodySmall.e(),
        labelLarge = labelLarge.e(), labelMedium = labelMedium.e(), labelSmall = labelSmall.e()
    )
}

/** Bold text: a heavier weight on body and labels for readability. */
fun androidx.compose.material3.Typography.bolder(): androidx.compose.material3.Typography {
    fun androidx.compose.ui.text.TextStyle.b() = copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    return copy(bodyLarge = bodyLarge.b(), bodyMedium = bodyMedium.b(), bodySmall = bodySmall.b(),
        labelLarge = labelLarge.b(), labelMedium = labelMedium.b(), labelSmall = labelSmall.b())
}

/** The chosen typeface everywhere, and heavier, tighter display type when "expressive" is on. */
fun androidx.compose.material3.Typography.styled(font: String, expressive: Boolean): androidx.compose.material3.Typography {
    val fam = when (font) { "Serif" -> androidx.compose.ui.text.font.FontFamily.Serif; "Mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
        "Condensed" -> androidx.compose.ui.text.font.FontFamily.SansSerif; else -> null }
    fun androidx.compose.ui.text.TextStyle.f(bold: Boolean = false): androidx.compose.ui.text.TextStyle {
        var t = if (fam != null) copy(fontFamily = fam) else this
        if (font == "Condensed") t = t.copy(letterSpacing = (-0.3).sp)
        if (bold && expressive) t = t.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, letterSpacing = (-0.6).sp)
        return t
    }
    return copy(displayLarge = displayLarge.f(true), displayMedium = displayMedium.f(true), displaySmall = displaySmall.f(true),
        headlineLarge = headlineLarge.f(true), headlineMedium = headlineMedium.f(true), headlineSmall = headlineSmall.f(true),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f())
}
