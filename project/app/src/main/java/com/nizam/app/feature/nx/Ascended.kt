package com.nizam.app.feature.nx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Timeline
import com.nizam.app.ui.design.Hub

/**
 * Atlas Ascended: the living layer, reachable from search, the hubs and the agent.
 * These are not separate apps bolted on - they all read the same local graph.
 */
object Ascended {
    val all: List<Spec> = listOf(
        ScreenSpec(
            "constellation", "Mnemonic Constellation",
            "Your memories as a sky you can drift through, one light per thing that mattered",
            Icons.Outlined.AutoAwesome, Hub.Memory,
            "memory constellation stars sky graph recall drift mind palace"
        ) { _, _ -> com.nizam.app.feature.constellation.MnemonicConstellationScreen() },
        ScreenSpec(
            "chronoverse", "Chronoverse",
            "Every thread of your life on one timeline, grouped by how close it is to now",
            Icons.Outlined.Timeline, Hub.Memory,
            "timeline history chronoverse past when story years"
        ) { _, _ -> com.nizam.app.feature.chronoverse.ChronoverseScreen() },
        ScreenSpec(
            "anamnesis", "Anamnesis",
            "The book of you, written honestly from what you actually lived",
            Icons.Outlined.MenuBook, Hub.Memory,
            "book of you year review story autobiography chapters"
        ) { _, _ -> com.nizam.app.feature.anamnesis.AnamnesisScreen() },
        ScreenSpec(
            "shadow", "Cognitive Shadow",
            "The part of you that argues back, fairly, before you decide",
            Icons.Outlined.Psychology, Hub.Health,
            "shadow devil advocate decide doubt honest mirror courage"
        ) { _, _ -> com.nizam.app.feature.shadow.CognitiveShadowScreen() },
        ScreenSpec(
            "neurgraph", "Neurgraph",
            "The living graph under every memory: what Atlas keeps, fades and returns to",
            Icons.Outlined.Extension, Hub.Memory,
            "graph memory neurons synapses mind consolidation sleep forgetting"
        ) { c, _ -> com.nizam.app.feature.mind.NeurgraphScreen(c) },
        ScreenSpec(
            "capsule", "Time Capsules",
            "Letters to a version of you who does not exist yet, sealed until the day",
            Icons.Outlined.History, Hub.Memory,
            "time capsule letter future self sealed memory waiting"
        ) { _, _ -> com.nizam.app.feature.capsule.CapsulesScreen() },
    )
}
