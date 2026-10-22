package com.nizam.app.feature.arcade

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub

object ArcadeSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("arcade", "Atlas Arcade", "21 consoles built in: Game Boy to PSP, N64, Dreamcast, Saturn and arcade, a free games store and controller support",
            Icons.Outlined.SportsEsports, Hub.Media, "games emulator arcade psp ps1 playstation nintendo gameboy gba nes snes n64 ds dreamcast saturn sega neo geo mame fbneo controller gamepad retro rom play") { _, _ -> AtlasArcadeScreen() },
    )
}
