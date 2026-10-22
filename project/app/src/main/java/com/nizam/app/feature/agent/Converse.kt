package com.nizam.app.feature.agent

import com.nizam.app.AppContainer
import com.nizam.app.feature.attach.Attachment
import com.nizam.app.net.Router
import com.nizam.app.net.Streaming

/**
 * The fast path: a question answered in one streamed call, with the user's live data as context.
 *
 * The router sends only non-action messages here. If the model still sees that doing something is
 * needed, it answers with the single word ACT, which never reaches the screen, and the caller hands
 * the message to the full agent instead.
 */
object Converse {

    private const val SYSTEM = """You are Atlas, the user's personal chief of staff living in their life app.
Answer directly, specifically and briefly, using their real data below. Never invent numbers you
weren't given. Plain conversational language; markdown only when it genuinely helps (lists, steps).

If answering properly would require DOING something — changing their app, the phone, sending,
opening, creating, logging, searching the web or using a connected tool — reply with exactly the
word ACT and nothing else. Never pretend you did an action."""

    /** Streams an answer through [onDelta]. Returns the full answer, or null when the agent should act. */
    suspend fun chat(
        c: AppContainer,
        message: String,
        attachments: List<Attachment> = emptyList(),
        extraContext: String = "",
        contextParts: List<RunContextPart>? = null,
        onDelta: (String) -> Unit
    ): String? {
        val selectedParts = contextParts?.filter { it.included }
        val memory = selectedParts?.firstOrNull { it.id == "memories" }?.content
            ?: if (contextParts == null && !ChatModes.private) c.agentMemory.recall() else ""
        val history = selectedParts?.firstOrNull { it.id == "conversation" }?.content
            ?: if (contextParts == null && !ChatModes.private) c.agentMemory.history(10) else ""
        val snapshot = selectedParts?.filter { it.id.startsWith("snapshot_") }?.joinToString("\n") { it.content }
            ?: if (contextParts == null) Snapshot.of(c) else ""
        val connectedTools = selectedParts?.firstOrNull { it.id == "connected_tools" }?.content.orEmpty()
        val knowledgeSources = selectedParts?.firstOrNull { it.id == "knowledge_sources" }?.content.orEmpty()
        val prompt = buildString {
            if (memory.isNotBlank()) appendLine(memory)
            appendLine()
            appendLine("Their app right now:")
            appendLine(snapshot)
            if (knowledgeSources.isNotBlank()) {
                appendLine()
                appendLine("Relevant saved records and documents. Cite a bracketed source when you rely on it; if it does not answer the question, say so.")
                appendLine(knowledgeSources)
            }
            if (connectedTools.isNotBlank()) {
                appendLine()
                appendLine("Connected-tool descriptions:")
                appendLine(connectedTools)
            }
            if (extraContext.isNotBlank()) { appendLine(); appendLine(extraContext) }
            if (history.isNotBlank()) {
                appendLine()
                appendLine("Conversation so far:")
                appendLine(history)
            }
            appendLine()
            append("Them: ").append(message.ifBlank { "Look at what I attached and help me with it." })
        }
        if (contextParts != null) ContextReceipts.recordParts(c.appContext, message, contextParts)
        else ContextReceipts.record(c.appContext, message, prompt)
        // Hold the first few characters back until we know it isn't the ACT hand-off signal
        val held = StringBuilder()
        var released = false
        val full = Streaming.reply(
            c.settingsRepository, prompt, SYSTEM + ChatModes.personaPrompt(),
            maxTokens = if (ChatModes.think) 3000 else if (attachments.isEmpty()) 2000 else 2400,
            attachments = attachments, context = c.appContext, lane = Router.Lane.CHAT
        ) { piece ->
            if (released) onDelta(piece)
            else {
                held.append(piece)
                val t = held.trimStart()
                if (t.length >= 4 && !t.startsWith("ACT")) { released = true; onDelta(held.toString()) }
            }
        }
        if (full.trim().trimEnd('.', '!').equals("ACT", ignoreCase = false)) return null
        if (!released) onDelta(held.toString())
        return full.trim()
    }
}
