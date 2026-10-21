package com.nizam.app.ui.design

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Depth you can feel: tilt the phone and the layers of a screen shift a few pixels against each other,
 * like looking into glass. One gyroscope listener while a screen that uses it is visible; smoothed so it
 * floats rather than jitters; off under Reduce motion and when the phone has no gyroscope.
 */
@Composable
fun rememberTilt(): State<Offset> {
    val ctx = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    if (Motion.still) return tilt
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        var base: FloatArray? = null
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        var sx = 0f; var sy = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                val b = base ?: ori.copyOf().also { base = it }
                // pitch and roll away from how the phone was held when the screen opened, clamped to ±0.35 rad
                val dx = (ori[2] - b[2]).coerceIn(-0.35f, 0.35f) / 0.35f
                val dy = (ori[1] - b[1]).coerceIn(-0.35f, 0.35f) / 0.35f
                sx += (dx - sx) * 0.12f; sy += (dy - sy) * 0.12f
                // the resting point drifts slowly toward the current hold, so a new posture becomes the new centre
                b[1] += (ori[1] - b[1]) * 0.004f; b[2] += (ori[2] - b[2]) * 0.004f
                tilt.value = Offset(sx, sy)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}

/**
 * Moves this layer with the tilt. [depth] > 0 floats toward you (moves with the tilt), < 0 sits behind
 * (moves against it). Read during drawing only, so nothing recomposes as the phone moves.
 */
fun Modifier.parallax(tilt: State<Offset>, depth: Float): Modifier = graphicsLayer {
    val t = tilt.value
    translationX = t.x * depth * 6.dp.toPx()
    translationY = t.y * depth * 6.dp.toPx()
}
