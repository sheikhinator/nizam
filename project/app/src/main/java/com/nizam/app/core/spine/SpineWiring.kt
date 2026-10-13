package com.nizam.app.core.spine

import com.nizam.app.AppContainer
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * SpineWiring — the one place every organ plugs its *slice* into [Self].
 *
 * Features broadcast events from their own write-points via [Pulse]; here we register the
 * read-side: short, live summaries each organ contributes to the shared sense of self. Called once
 * from the Application after the container is built. Each provider is cheap, one-shot, and guarded
 * by Self, so a slow or empty organ never stalls the briefing.
 *
 * Additive by design: adding an organ is one `Self.contribute(...)` block, nothing else changes.
 */
object SpineWiring {
    fun install(container: AppContainer) {
        val monthStart = LocalDate.now().withDayOfMonth(1).toString()

        // Money — this month's spend and income so far, in the owner's currency (PKR).
        Self.contribute("money") {
            val txns = container.financeRepository.since(monthStart).first()
            val spent = txns.filter { it.kind == "EXPENSE" }.sumOf { it.amount }
            val earned = txns.filter { it.kind == "INCOME" }.sumOf { it.amount }
            if (txns.isEmpty()) ""
            else "spent PKR ${"%,.0f".format(spent)} this month" +
                (if (earned > 0) ", in PKR ${"%,.0f".format(earned)}" else "")
        }

        // Prayer — how many of the five are logged today.
        Self.contribute("prayer") {
            val day = container.prayerRepository.day(LocalDate.now().toString()).first()
            if (day == null) ""
            else {
                val done = listOf(day.fajr, day.dhuhr, day.asr, day.maghrib, day.isha)
                    .count { it != "NOT_PRAYED" }
                if (done == 0) "" else "$done/5 prayers logged today"
            }
        }

        // Tasks — how many are still open.
        Self.contribute("tasks") {
            val open = container.taskRepository.all().first()
                .count { it.completedAt == null && it.deletedAt == null }
            if (open == 0) "" else "$open open task${if (open == 1) "" else "s"}"
        }

        // Habits — how many of today's habits are kept so far.
        Self.contribute("habits") {
            val today = LocalDate.now().toString()
            val active = container.habitRepository.habits().first().count { !it.archived }
            val kept = container.habitRepository.entriesFor(today).first().size
            if (active == 0) "" else "$kept/$active habits kept today"
        }

        // Diet — how many meals are logged today (neutral count, no targets).
        Self.contribute("diet") {
            val meals = container.dietRepository.day(LocalDate.now().toString()).first().size
            if (meals == 0) "" else "$meals meal${if (meals == 1) "" else "s"} logged today"
        }

        // Fitness — whether a workout or cardio was logged today.
        Self.contribute("fitness") {
            val today = LocalDate.now().toString()
            val lifted = container.gymRepository.sets().first().any { it.localDate == today }
            val cardio = container.gymRepository.cardio().first().any { it.localDate == today }
            if (lifted || cardio) "trained today" else ""
        }

        // Goals & missions — active goals and today's mission progress.
        Self.contribute("goals") {
            val active = container.missionRepository.goals().first().size
            val missions = container.missionRepository.today().first()
            val done = missions.count { it.completedAt != null }
            val parts = mutableListOf<String>()
            if (active > 0) parts += "$active active goal${if (active == 1) "" else "s"}"
            if (missions.isNotEmpty()) parts += "$done/${missions.size} missions today"
            parts.joinToString(" · ")
        }

        // Self-authoring — how many modules Atlas has grown for this life (self-awareness).
        Self.contribute("modules") {
            val custom = container.dynamicRepository.specs().first().count { !it.builtIn }
            if (custom == 0) "" else "$custom self-made module${if (custom == 1) "" else "s"}"
        }

        wireReactions(container)
    }

    /**
     * The Reactor's first reflex: fold notable events into the [World] graph, so the organism
     * builds its own memory of the life it shares. Deliberately silent and safe — it records
     * knowledge, it never acts or notifies. Actions (under a trust budget, with receipts) plug
     * into this same observer in a later, explicit batch.
     */
    private fun wireReactions(container: AppContainer) {
        val ctx = container.appContext
        Spine.reactor()?.onSignal { signal ->
            when (signal.domain) {
                "money" -> {
                    val category = signal.data.optString("category").ifBlank { "Uncategorised" }
                    World.put(ctx, World.Node("category:$category", "spend_category", category))
                    // Self-evolving help: if one category keeps recurring, Atlas offers to grow a
                    // budget-tracker for it — once a day, gated by the trust budget, logged.
                    val recurring = Pulse.recentIn("money", 40).count { it.data.optString("category") == category }
                    if (recurring >= 6 && TrustBudget.allows(ctx, "money", TrustBudget.Level.NOTIFY)) {
                        val gate = ctx.getSharedPreferences("spine_suggest", 0)
                        val once = "budget_${category}_${java.time.LocalDate.now()}"
                        if (!gate.getBoolean(once, false)) {
                            gate.edit().putBoolean(once, true).apply()
                            runCatching {
                                com.nizam.app.feature.notify.Notifications.post(
                                    ctx, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 272,
                                    "A pattern in $category",
                                    "You've been logging $category a lot. Want Atlas to set a budget and watch it for you?"
                                )
                            }
                            Receipts.record(ctx, "money", "suggestion", "Offered a budget for $category", reversible = false)
                        }
                    }
                }
                "goals" -> if (signal.kind == "achieved" && signal.summary.isNotBlank()) {
                    World.put(
                        ctx,
                        World.Node(
                            "goal:${signal.summary}", "goal", signal.summary,
                            org.json.JSONObject().put("status", "achieved")
                        )
                    )
                    // Even the organism's own memory-keeping is logged, so nothing it does is silent.
                    Receipts.record(ctx, "goals", "noted-milestone", "Recorded achieved goal: ${signal.summary}")
                    // Aliveness: if the owner has granted at least NOTIFY for goals, Atlas celebrates
                    // the milestone — and records that it did. A nudge is never silent either.
                    if (TrustBudget.allows(ctx, "goals", TrustBudget.Level.NOTIFY)) {
                        runCatching {
                            com.nizam.app.feature.notify.Notifications.post(
                                ctx, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 270,
                                "Goal reached 🎉",
                                "You hit: ${signal.summary}. That's a real one — well done."
                            )
                        }
                        Receipts.record(ctx, "goals", "nudge", "Congratulated on: ${signal.summary}", reversible = false)
                    }
                }
                "system" -> if (signal.kind == "grew" && signal.summary.isNotBlank()) {
                    // The organism grew a new part of itself — remember it and log the growth.
                    World.put(
                        ctx,
                        World.Node(
                            "module:${signal.data.optString("key").ifBlank { signal.summary }}",
                            "module", signal.summary
                        )
                    )
                    Receipts.record(ctx, "self-authoring", "grew-module", "Atlas added a new module: ${signal.summary}")
                }
            }
        }
    }
}
