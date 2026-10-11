package com.nizam.app.feature.cinema

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Picture-in-picture: the video keeps playing in a small floating window when you leave the player. */
object Pip {
    /** True while the app is shrunk to the floating window; screens hide their chrome. */
    var active by mutableStateOf(false)
    /** Set by a video player while something is playing; leaving the app then floats the video. */
    var armed = false
    var aspect = Rational(16, 9)

    private fun params(auto: Boolean): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(aspect.numerator.coerceIn(1, 239), aspect.denominator.coerceIn(1, 100)).let {
            // Android refuses ratios outside 1:2.39 .. 2.39:1
            val r = it.toFloat(); if (r > 2.39f) Rational(239, 100) else if (r < 0.42f) Rational(100, 239) else it })
        .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(auto) }
        .build()

    fun arm(a: Activity?, on: Boolean, w: Int = 16, h: Int = 9) {
        armed = on
        if (w > 0 && h > 0) aspect = Rational(w, h)
        if (a != null && Build.VERSION.SDK_INT >= 31) runCatching { a.setPictureInPictureParams(params(on)) }
    }

    fun enter(a: Activity?): Boolean {
        a ?: return false
        if (!a.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return runCatching { a.enterPictureInPictureMode(params(armed)) }.getOrDefault(false)
    }
}
