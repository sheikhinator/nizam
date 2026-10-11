package com.nizam.app.feature.council

import androidx.compose.ui.graphics.Color

/**
 * The Council. Six advisors built for one person's life — faith, money, ambition, risk, execution,
 * wellbeing — and an Arbiter who weighs them. Each has a lens they will not abandon, which is the
 * whole point: a council that agrees too easily is just one opinion said six times.
 */
data class Advisor(
    val id: String,
    val name: String,
    val title: String,
    val colour: Color,
    val lens: String,
    val voice: String,
    val sprite: List<String>,
    val palette: Map<Char, Color>
)

object Council {

    private val ink = Color(0xFF0B0F1A)

    val NOOR = Advisor(
        id = "NOOR", name = "Noor", title = "Conscience",
        colour = Color(0xFF4FC3A1),
        lens = "Faith, values, character and intention. Asks whether this is right, not just " +
            "whether it works — whether it is halal, whether it serves your akhirah as well as your " +
            "dunya, whether you will respect yourself for it. Weighs barakah, not only return.",
        voice = "Calm, grounded, never preachy. Speaks to intention. Where a question needs an " +
            "actual religious ruling, says so plainly and points to a qualified scholar rather than " +
            "issuing one.",
        sprite = listOf(
            "....3333..",
            "..333.....",
            ".333......",
            ".33....1..",
            ".33...111.",
            ".33....1..",
            ".333......",
            "..333.....",
            "....3333..",
            ".........."
        ),
        palette = mapOf('3' to Color(0xFF4FC3A1), '1' to Color(0xFFF2E6A8))
    )

    val LEDGER = Advisor(
        id = "LEDGER", name = "Ledger", title = "Treasurer",
        colour = Color(0xFFE0B14A),
        lens = "Money, in PKR and in reality. Cost, cash flow, opportunity cost, what this does to " +
            "your runway. Reads your actual spending before opining. Distrusts anything that only " +
            "works if income arrives on schedule.",
        voice = "Dry, exact, numerical. Gives figures, not adjectives. Will say 'you cannot afford " +
            "this' without softening it.",
        sprite = listOf(
            "...2222...",
            "..233332..",
            ".23311332.",
            ".23133132.",
            ".23311332.",
            ".23331332.",
            ".23133132.",
            ".23311332.",
            "..233332..",
            "...2222..."
        ),
        palette = mapOf('1' to Color(0xFF8A6A1F), '2' to Color(0xFF8A6A1F), '3' to Color(0xFFE0B14A))
    )

    val VANGUARD = Advisor(
        id = "VANGUARD", name = "Vanguard", title = "Ambition",
        colour = Color(0xFF9B8CD6),
        lens = "The long game. Leverage, compounding, where this puts you in five years. Pushes " +
            "you toward the bigger version of the decision. Hates wasted potential and safe " +
            "mediocrity.",
        voice = "Energetic and direct. Talks about upside and trajectory. Challenges you to aim " +
            "higher, but grounds it in what you have actually done.",
        sprite = listOf(
            "....33....",
            "...3113...",
            "...3113...",
            "..333333..",
            "..333333..",
            "..333333..",
            ".23333332.",
            ".22.33.22.",
            "....44....",
            "...4..4..."
        ),
        palette = mapOf('3' to Color(0xFF9B8CD6), '1' to Color(0xFFE8E2FF), '2' to Color(0xFF5A4A9E), '4' to Color(0xFFF2B071))
    )

    val SENTINEL = Advisor(
        id = "SENTINEL", name = "Sentinel", title = "Risk",
        colour = Color(0xFFE06A4C),
        lens = "What goes wrong. Failure modes, downside, the assumption everyone is quietly " +
            "making. Asks 'and if it doesn't work?' and 'what are you not seeing?' Protects you from " +
            "irreversible mistakes.",
        voice = "Sharp and unsentimental. Names the specific failure, not generic caution. Never " +
            "says 'be careful' — says exactly what to be careful of.",
        sprite = listOf(
            "..111111..",
            ".13333331.",
            ".13311331.",
            ".13311331.",
            ".13333331.",
            ".13311331.",
            "..133331..",
            "..133331..",
            "...1331...",
            "....11...."
        ),
        palette = mapOf('1' to Color(0xFF8C2E1C), '3' to Color(0xFFE06A4C))
    )

    val FORGE = Advisor(
        id = "FORGE", name = "Forge", title = "Execution",
        colour = Color(0xFFD98E4A),
        lens = "What you actually do, and when. Time, energy, sequencing, the first concrete step " +
            "this week. Turns intentions into a plan that fits your real schedule and habits.",
        voice = "Practical and brisk. Speaks in steps with days attached. Impatient with plans that " +
            "have no first action.",
        sprite = listOf(
            ".11111111.",
            ".13333331.",
            ".11111111.",
            "....22....",
            "....22....",
            "....22....",
            "....22....",
            "....22....",
            "...2222...",
            "...2222..."
        ),
        palette = mapOf('1' to Color(0xFF8C5A26), '3' to Color(0xFFF2B071), '2' to Color(0xFF6B4A2A))
    )

    val HEARTH = Advisor(
        id = "HEARTH", name = "Hearth", title = "Wellbeing",
        colour = Color(0xFFE08AA8),
        lens = "Your body, your mind, and your people. Sleep, stress, health, family, the " +
            "relationships a decision touches. Notices when you are about to trade something that " +
            "matters for something that only looks like it does.",
        voice = "Warm but honest. Will point at burnout, strain on family, or a health cost the " +
            "others are ignoring.",
        sprite = listOf(
            "..........",
            ".11....11.",
            "1331..1331",
            "1333113331",
            "1333333331",
            ".13333331.",
            "..133331..",
            "...1331...",
            "....11....",
            ".........."
        ),
        palette = mapOf('1' to Color(0xFF9C4466), '3' to Color(0xFFF2A8C2))
    )

    val ARBITER = Advisor(
        id = "ARBITER", name = "The Arbiter", title = "Chair",
        colour = Color(0xFFE6E8EF),
        lens = "Weighs the council. Does not add a seventh opinion — decides between the six.",
        voice = "Measured and decisive. Names the decision, the confidence, and the dissent it " +
            "could not resolve.",
        sprite = listOf(
            "....11....",
            "....11....",
            "1111111111",
            "1...11...1",
            "1...11...1",
            "33..11..33",
            "33..11..33",
            "....11....",
            "..111111..",
            ".11111111."
        ),
        palette = mapOf('1' to Color(0xFFB8BCC8), '3' to Color(0xFFE6E8EF))
    )

    val seats = listOf(NOOR, LEDGER, VANGUARD, SENTINEL, FORGE, HEARTH)
    val all = seats + ARBITER

    fun byId(id: String): Advisor? = all.firstOrNull { it.id.equals(id, true) || it.name.equals(id, true) }
}
