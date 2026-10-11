package com.nizam.app.feature.icon

// Generated: every ink × background pairing with readable contrast (≥3:1). Each has a compiled
// launcher resource and a manifest alias — Android can't draw launcher icons at runtime.

data class IconInk(val key: String, val name: String, val color: Long)
data class IconBackground(val key: String, val name: String, val color: Long)

object IconCombos {
    val INKS = listOf(
        IconInk("coral", "Coral", 0xFFE0603A),
        IconInk("gold", "Gold", 0xFFD4A24C),
        IconInk("crimson", "Crimson", 0xFFD7263D),
        IconInk("white", "White", 0xFFEDEFF5),
        IconInk("emerald", "Emerald", 0xFF4FC3A1),
        IconInk("violet", "Violet", 0xFF9B8CD6),
        IconInk("sky", "Sky", 0xFF4FA3E0),
        IconInk("teal", "Teal", 0xFF2EC4B6),
        IconInk("rose", "Rose", 0xFFE86A9A),
        IconInk("orange", "Orange", 0xFFF28C28),
        IconInk("lime", "Lime", 0xFFA3D94A),
        IconInk("silver", "Silver", 0xFFB8BEC8),
        IconInk("black", "Black", 0xFF111111),
        IconInk("navy", "Navy", 0xFF1F3A6B)
    )
    val BACKGROUNDS = listOf(
        IconBackground("midnight", "Midnight", 0xFF0B0F1A),
        IconBackground("black", "Black", 0xFF000000),
        IconBackground("charcoal", "Charcoal", 0xFF23262D),
        IconBackground("navy", "Navy", 0xFF0B1B3A),
        IconBackground("wine", "Wine", 0xFF2A0A12),
        IconBackground("forest", "Forest", 0xFF0A2018),
        IconBackground("cream", "Cream", 0xFFF3EEE2),
        IconBackground("white", "White", 0xFFFFFFFF)
    )
    /** Keys of the combos that exist, as "x_<ink>_<background>". */
    val KEYS: Set<String> = setOf(
        "x_coral_midnight",
        "x_coral_black",
        "x_coral_charcoal",
        "x_coral_navy",
        "x_coral_wine",
        "x_coral_forest",
        "x_coral_cream",
        "x_coral_white",
        "x_gold_midnight",
        "x_gold_black",
        "x_gold_charcoal",
        "x_gold_navy",
        "x_gold_wine",
        "x_gold_forest",
        "x_crimson_midnight",
        "x_crimson_black",
        "x_crimson_charcoal",
        "x_crimson_navy",
        "x_crimson_wine",
        "x_crimson_forest",
        "x_crimson_cream",
        "x_crimson_white",
        "x_white_midnight",
        "x_white_black",
        "x_white_charcoal",
        "x_white_navy",
        "x_white_wine",
        "x_white_forest",
        "x_emerald_midnight",
        "x_emerald_black",
        "x_emerald_charcoal",
        "x_emerald_navy",
        "x_emerald_wine",
        "x_emerald_forest",
        "x_violet_midnight",
        "x_violet_black",
        "x_violet_charcoal",
        "x_violet_navy",
        "x_violet_wine",
        "x_violet_forest",
        "x_sky_midnight",
        "x_sky_black",
        "x_sky_charcoal",
        "x_sky_navy",
        "x_sky_wine",
        "x_sky_forest",
        "x_teal_midnight",
        "x_teal_black",
        "x_teal_charcoal",
        "x_teal_navy",
        "x_teal_wine",
        "x_teal_forest",
        "x_rose_midnight",
        "x_rose_black",
        "x_rose_charcoal",
        "x_rose_navy",
        "x_rose_wine",
        "x_rose_forest",
        "x_rose_white",
        "x_orange_midnight",
        "x_orange_black",
        "x_orange_charcoal",
        "x_orange_navy",
        "x_orange_wine",
        "x_orange_forest",
        "x_lime_midnight",
        "x_lime_black",
        "x_lime_charcoal",
        "x_lime_navy",
        "x_lime_wine",
        "x_lime_forest",
        "x_silver_midnight",
        "x_silver_black",
        "x_silver_charcoal",
        "x_silver_navy",
        "x_silver_wine",
        "x_silver_forest",
        "x_black_cream",
        "x_black_white",
        "x_navy_cream",
        "x_navy_white"
    )

    fun key(ink: String, background: String) = "x_${ink}_$background"

    val VARIANTS: List<IconVariant> = INKS.flatMap { i ->
        BACKGROUNDS.mapNotNull { b ->
            if (key(i.key, b.key) in KEYS) IconVariant(key(i.key, b.key), "${i.name} on ${b.name}", i.color, b.color) else null
        }
    }
}
