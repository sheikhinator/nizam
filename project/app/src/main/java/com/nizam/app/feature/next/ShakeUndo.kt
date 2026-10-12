package com.nizam.app.feature.next

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.ui.components.Feedback
import kotlin.math.sqrt

/**
 * Shake the phone to undo whatever was just captured — a quick safety net for a stray swipe or a
 * mis-dictated note. Only looks at the last few seconds, so an old shake can't undo something new.
 */
@Composable
fun ShakeToUndo() {
    val c = LocalContext.current
    DisposableEffect(Unit) {
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastShakeAt = 0L
        val listener = object : SensorEventListener {
            var lastX = 0f; var lastY = 0f; var lastZ = 0f; var lastT = 0L
            override fun onSensorChanged(e: SensorEvent) {
                val now = System.currentTimeMillis()
                if (lastT == 0L) { lastT = now; lastX = e.values[0]; lastY = e.values[1]; lastZ = e.values[2]; return }
                if (now - lastT < 80) return
                val dt = now - lastT
                val speed = sqrt(((e.values[0] - lastX).let { it * it } + (e.values[1] - lastY).let { it * it } + (e.values[2] - lastZ).let { it * it }).toDouble()) / dt * 10000
                lastT = now; lastX = e.values[0]; lastY = e.values[1]; lastZ = e.values[2]
                if (speed > 1200 && now - lastShakeAt > 1500) {
                    lastShakeAt = now
                    val gone = Capture.store(c).all().maxByOrNull { it.optLong("t", 0L) }
                    if (gone != null && now - gone.optLong("t", 0L) < 30_000) {
                        Capture.store(c).remove(gone.optString("id"))
                        Feedback.show("Undone: ${gone.optString("title").ifBlank { "that capture" }}")
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm?.unregisterListener(listener) }
    }
}
