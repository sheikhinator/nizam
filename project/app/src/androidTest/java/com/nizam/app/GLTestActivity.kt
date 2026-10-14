package com.nizam.app

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData

/** A bare host for a real GLRetroView, used by GameplayTest to run a game on the CI emulator. */
class GLTestActivity : ComponentActivity() {
    var view: GLRetroView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = intent.getStringExtra("core") ?: return
        val rom = intent.getStringExtra("rom") ?: return
        runCatching {
            val d = GLRetroViewData(this).apply {
                coreFilePath = core
                gameFilePath = rom
                systemDirectory = cacheDir.absolutePath
                savesDirectory = cacheDir.absolutePath
            }
            val v = GLRetroView(this, d)
            lifecycle.addObserver(v)
            val fl = FrameLayout(this)
            fl.addView(v)
            setContentView(fl)
            view = v
        }.onFailure { android.util.Log.e("GAMETEST", "GLRetroView create threw: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
