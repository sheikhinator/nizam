package com.nizam.app.feature.agent

import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.net.Router
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Atlas learns you. After a conversation it quietly picks out anything lasting — preferences, people
 * and relationships, goals, routines, health or money facts you mentioned, how you like answers — and
 * adds it to its memory (Me › Memory shows and deletes these). Next time, it already knows.
 */
object Learner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun learn(container: AppContainer, said: String, reply: String) {
        if (said.trim().length < 12) return
        scope.launch(Router.quiet.asContextElement(true)) {
            runCatching {
                val known = container.agentMemory.notes().first().map { it.text.lowercase() }
                val out = Ai.generate(container.settingsRepository,
                    "USER SAID:\n${said.take(1500)}\n\nATLAS REPLIED:\n${reply.take(800)}\n\nALREADY KNOWN:\n${known.take(40).joinToString("\n")}",
                    SYSTEM, 200)
                out.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }
                    .filter { it.length in 8..200 && !it.equals("none", true) && known.none { k -> k.contains(it.lowercase().take(30)) } }
                    .take(3).forEach { container.agentMemory.remember(it, "learned") }
            }
        }
    }

    private const val SYSTEM = """You maintain a personal assistant's long-term memory about its user.
From the exchange, list only NEW, lasting facts about the user worth remembering for months: preferences, people in
their life and relationships, goals, commitments, routines, health, work, money habits, how they like to be answered.
One short fact per line starting with "- ", written about the user ("Prefers short answers", "Sister is Ayesha").
Skip one-off requests, anything already known, and anything sensitive they didn't choose to share. If nothing, reply: none"""
}
