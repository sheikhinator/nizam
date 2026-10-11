package com.nizam.app.feature.flows

import org.json.JSONObject

data class Param(val key: String, val label: String, val hint: String = "", val long: Boolean = false)
data class Kind(val type: String, val label: String, val blurb: String, val params: List<Param>)

/** Every kind of trigger and step a flow can use. The editor and the AI builder both read this list. */
object FlowKinds {
    val steps = listOf(
        Kind("ai", "Ask the AI", "Write, summarise, extract or decide with AI",
            listOf(Param("prompt", "What to ask", "You can use {{trigger.text}} or {{last}}", true), Param("data", "Use my Atlas data? (yes or no)", "no"), Param("out", "Save the answer as", "answer"))),
        Kind("notify", "Notify me", "Show a notification",
            listOf(Param("title", "Title"), Param("body", "Message", "", true))),
        Kind("ask", "Ask my OK first", "Pause until you approve in the inbox",
            listOf(Param("title", "What are you approving?"), Param("body", "Details", "", true))),
        Kind("http", "Call a web address", "Any web service with an address and a key",
            listOf(Param("method", "Method", "GET"), Param("url", "Web address"), Param("headers", "Headers, one per line", "Authorization: Bearer ...", true), Param("body", "Body", "", true), Param("out", "Save the reply as", "reply"))),
        Kind("connector", "Use a connected app", "Any app you connected in Connectors",
            listOf(Param("service", "App (name or id)"), Param("method", "Method", "GET"), Param("path", "Path", "/v1/things"), Param("query", "Query, one per line as name=value", "", true), Param("body", "Body", "", true), Param("out", "Save the reply as", "reply"))),
        Kind("atlas", "Tell Atlas to do something", "Atlas's own assistant carries it out",
            listOf(Param("text", "What should Atlas do?", "", true))),
        Kind("roads", "Remember in Roads", "Add a line to your shared data so insights can use it",
            listOf(Param("source", "Source name", "Flows"), Param("text", "What to remember", "", true))),
        Kind("set", "Set a value", "Keep a value to use in later steps",
            listOf(Param("var", "Name"), Param("value", "Value", "", true))),
        Kind("if", "Only continue if", "Skip the next steps when the check fails",
            listOf(Param("left", "Look at", "{{last}}"), Param("op", "Check", "contains, equals, has, more, less"), Param("right", "Compare with"), Param("skip", "Steps to skip if it fails", "1"))),
        Kind("log", "Write to the run log", "A note in the history",
            listOf(Param("text", "Text", "", true))),
        Kind("relay_send", "Send to a relay channel", "Push a message to another app or device through ntfy",
            listOf(Param("topic", "Channel"), Param("title", "Title"), Param("message", "Message", "", true))),
    )

    val triggers = listOf(
        Kind("manual", "By hand", "Only when you press Run", emptyList()),
        Kind("schedule", "On a schedule", "Every so many minutes, or daily at a time",
            listOf(Param("mode", "Mode", "every or daily"), Param("minutes", "Every how many minutes", "60"), Param("time", "Daily at", "08:00"))),
        Kind("relay", "A message on a relay channel", "Something outside posts to your private channel",
            listOf(Param("topic", "Channel name"))),
        Kind("notification", "A notification arrives", "From any app on this phone",
            listOf(Param("app", "App name contains"), Param("contains", "Text contains"))),
        Kind("notice", "Atlas notices something", "When one of Atlas's hints appears",
            listOf(Param("contains", "Hint title contains"))),
    )

    fun step(type: String) = steps.firstOrNull { it.type == type }
    fun trigger(type: String) = triggers.firstOrNull { it.type == type }

    /** A plain sentence for a step or trigger. */
    fun sentence(k: Kind?, o: JSONObject): String {
        if (k == null) return "Unknown step"
        val detail = k.params.firstOrNull { o.optString(it.key).isNotBlank() }?.let { o.optString(it.key).replace("\n", " ").take(70) }.orEmpty()
        return if (detail.isBlank()) k.label else "${k.label}: $detail"
    }

    /** What the AI builder is told about the building blocks. */
    fun guide(): String = buildString {
        append("Triggers (type and params):\n")
        triggers.forEach { append("- ${it.type}: ${it.blurb}. params: ${it.params.joinToString { p -> p.key }}\n") }
        append("Steps (type and params):\n")
        steps.forEach { append("- ${it.type}: ${it.blurb}. params: ${it.params.joinToString { p -> p.key }}\n") }
        append("In text params use {{trigger.text}}, {{trigger.title}}, {{trigger.app}}, {{last}} (the previous step's result) and any name saved by an earlier step (out/var).\n")
    }
}
