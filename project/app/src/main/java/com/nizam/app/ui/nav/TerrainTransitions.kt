package com.nizam.app.ui.nav

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalAnimationApi::class)
object TerrainTransitions {
    val enterTransition: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
        fadeIn(animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) +
        scaleIn(initialScale = 0.98f, animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
    }
    
    val exitTransition: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
        fadeOut(animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) +
        scaleOut(targetScale = 0.96f, animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
    }
    
    val popEnterTransition: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
        fadeIn(animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) +
        scaleIn(initialScale = 0.96f, animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
    }
    
    val popExitTransition: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
        fadeOut(animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) +
        scaleOut(targetScale = 0.98f, animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
    }
}
