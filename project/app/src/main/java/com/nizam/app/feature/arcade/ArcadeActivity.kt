package com.nizam.app.feature.arcade

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView

/**
 * The game player: its own full-screen window with system bars hidden, the screen kept on, rotation that never
 * restarts the game, and physical controllers (Bluetooth or USB pads, phone clip-on controllers) wired straight
 * into the emulator, sticks and all.
 */
class ArcadeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("game")
        val game = ArcadeLibrary.list(this).firstOrNull { it.id == id }
        if (game == null) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        immersive()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8E97B8))) {
                var backTick by remember { mutableIntStateOf(0) }
                BackHandler { backTick++ }
                Box(Modifier.fillMaxSize().background(Color.Black)) { ArcadePlayer(game, backTick) { finish() } }
            }
        }
    }

    private fun immersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun fromController(e: android.view.InputEvent): Boolean {
        val s = e.source
        return s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            (s and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD && e.device?.isVirtual == false)
    }

    /** Controller buttons go to the game; the controller's own menu/back buttons open the pause menu. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val v = ArcadeSession.view
        if (v != null && fromController(event) && event.keyCode != KeyEvent.KEYCODE_BACK) {
            ArcadeSession.controller = true
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) return super.dispatchKeyEvent(KeyEvent(event.action, KeyEvent.KEYCODE_BACK))
            if (event.repeatCount == 0) v.sendKeyEvent(event.action, event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Analog sticks, triggers and the d-pad hat, as the emulator expects them. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val v = ArcadeSession.view
        if (v != null && fromController(event) && event.action == MotionEvent.ACTION_MOVE) {
            ArcadeSession.controller = true
            fun axis(a: Int): Float { val x = event.getAxisValue(a); return if (kotlin.math.abs(x) < 0.12f) 0f else x }
            v.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, axis(MotionEvent.AXIS_HAT_X), axis(MotionEvent.AXIS_HAT_Y))
            v.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y))
            v.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, axis(MotionEvent.AXIS_Z), axis(MotionEvent.AXIS_RZ))
            // analog triggers as L2/R2 presses
            trigger(v, KeyEvent.KEYCODE_BUTTON_L2, maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)))
            trigger(v, KeyEvent.KEYCODE_BUTTON_R2, maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)))
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private val held = HashMap<Int, Boolean>()
    private fun trigger(v: GLRetroView, code: Int, value: Float) {
        val down = value > 0.5f
        if (held[code] != down) { held[code] = down; v.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code) }
    }
}
