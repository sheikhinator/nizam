package com.nizam.app.ui.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color

/**
 * Atlas Ascended - the living design language.
 *
 * Living light, depth, and breath. Everything here is additive: existing tokens keep
 * working, and these give screens a shared vocabulary for "alive" that stays quiet.
 */
object Ascend {
    // Living light palette. Used sparingly - one accent per surface, never a rainbow.
    val reactorCyan = Color(0xFF4D9BFF)
    val reactorViolet = Color(0xFF9D7BFF)
    val reactorAmber = Color(0xFFFFB84D)
    val reactorRose = Color(0xFFFF6B9D)
    val reactorEmerald = Color(0xFF4DFFB8)
    val reactorIce = Color(0xFFB8E6FF)

    // Depth scale, in dp of visual lift.
    const val depthNear = 0f
    const val depthMid = 8f
    const val depthFar = 16f
    const val depthDistant = 24f

    // Breath timings. Slow enough to feel like a resting body.
    const val inhaleMs = 3200
    const val exhaleMs = 3200
    const val holdMs = 800

    // Timing scale.
    const val instant = 120
    const val swift = 200
    const val smooth = 320
    const val cinematic = 520
    const val dreamy = 800

    // Motion curves.
    val glideEase = CubicBezierEasing(0.25f, 0.8f, 0.25f, 1f)
    val swoopEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val driftEase = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

    // Springs.
    fun <T> lift(): AnimationSpec<T> = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    fun <T> float(): AnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessVeryLow)
    fun <T> crisp(): AnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
}
