package com.nizam.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.nizam.app.R

/**
 * Inter: a neutral, quiet typeface. Inter Display (drawn for large sizes) carries titles; Inter Text
 * carries everything you read. Titles are semibold rather than heavy, and tracking tightens as size
 * grows — calm, clean, and easy on the eyes.
 *
 * Inter is © The Inter Project Authors, SIL Open Font License 1.1 (assets/licenses/Inter-OFL.txt).
 */
val BodyFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/** Titles. Screens that asked for the old serif now get Inter Display — one family, two optical sizes. */
val DisplayFamily = FontFamily(
    Font(R.font.interdisplay_semibold, FontWeight.Normal),
    Font(R.font.interdisplay_semibold, FontWeight.Medium),
    Font(R.font.interdisplay_semibold, FontWeight.SemiBold),
    Font(R.font.interdisplay_bold, FontWeight.Bold)
)

val MonoFamily = FontFamily.Monospace

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, weight: FontWeight, size: Int, line: Int, tracking: Double) = TextStyle(
    fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = tracking.em, lineHeightStyle = trim
)

// A restrained scale: one large title size per screen, then a clear step down.
val NizamTypography = Typography(
    displayLarge = style(DisplayFamily, FontWeight.SemiBold, 56, 60, -0.025),
    displayMedium = style(DisplayFamily, FontWeight.SemiBold, 44, 50, -0.022),
    displaySmall = style(DisplayFamily, FontWeight.SemiBold, 32, 38, -0.02),  // screen title
    headlineLarge = style(DisplayFamily, FontWeight.SemiBold, 30, 36, -0.018),
    headlineMedium = style(DisplayFamily, FontWeight.SemiBold, 26, 32, -0.016),
    headlineSmall = style(DisplayFamily, FontWeight.SemiBold, 22, 28, -0.012),
    titleLarge = style(DisplayFamily, FontWeight.SemiBold, 19, 25, -0.01),
    titleMedium = style(BodyFamily, FontWeight.Medium, 17, 23, -0.011),
    titleSmall = style(BodyFamily, FontWeight.SemiBold, 15, 20, -0.008),
    bodyLarge = style(BodyFamily, FontWeight.Normal, 17, 24, -0.011),        // body
    bodyMedium = style(BodyFamily, FontWeight.Normal, 15, 21, -0.006),       // secondary text
    bodySmall = style(BodyFamily, FontWeight.Normal, 13, 18, -0.002),        // small print
    labelLarge = style(BodyFamily, FontWeight.SemiBold, 15, 20, -0.006),     // buttons
    labelMedium = style(BodyFamily, FontWeight.Medium, 13, 18, 0.0),         // footnote, emphasised
    labelSmall = style(BodyFamily, FontWeight.Medium, 12, 16, 0.01)          // captions
)

/** Figures in tables and stats: tabular digits so columns line up. */
val MonoNumber = TextStyle(
    fontFamily = BodyFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    fontFeatureSettings = "tnum"
)
