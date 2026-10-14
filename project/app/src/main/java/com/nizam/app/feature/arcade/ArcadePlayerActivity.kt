package com.nizam.app.feature.arcade

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nizam.app.ui.theme.NizamTheme

/**
 * The emulator runs here, in its own process (:arcade). Game cores are native code we can't fully
 * vet, so if one crashes hard, only this process dies — Atlas itself keeps running. The library
 * screen simply comes back.
 */
class ArcadePlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_GAME)
        val game = id?.let { gid -> runCatching { ArcadeLibrary.list(this).firstOrNull { it.id == gid } }.getOrNull() }
        if (game == null) { finish(); return }
        setContent {
            NizamTheme {
                Surface(Modifier.fillMaxSize()) {
                    ArcadePlayerWindow(game) { finish() }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_GAME = "game"
        fun launch(c: Context, gameId: String) {
            runCatching {
                c.startActivity(Intent(c, ArcadePlayerActivity::class.java).putExtra(EXTRA_GAME, gameId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
