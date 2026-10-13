package com.nizam.app.feature.arcade

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub

object ArcadeSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("arcade", "Atlas Arcade", "Play Game Boy, GBA, NES, SNES, PS1, PSP, N64 and more, with a free games store",
            Icons.Outlined.SportsEsports, Hub.Media, "games emulator arcade psp ps1 playstation nintendo gameboy gba nes snes n64 retro rom play") { _, _ -> AtlasArcadeScreen() },
    )
}
