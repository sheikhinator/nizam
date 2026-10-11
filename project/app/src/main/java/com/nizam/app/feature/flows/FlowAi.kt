package com.nizam.app.feature.flows

import com.nizam.app.AppContainer
import com.nizam.app.feature.connect.Connectors
import com.nizam.app.net.Ai
import org.json.JSONArray
import org.json.JSONObject

/** Lets the user describe a flow or an app in plain words; the AI drafts it, the user reviews it. */
object FlowAi {
    private fun jsonFrom(text: String): JSONObject {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        require(a >= 0 && b > a) { "The AI did not return a usable answer. Try again with more detail." }
        return JSONObject(text.substring(a, b + 1))
    }

    /** Keeps only known blocks and known settings, so a bad draft cannot do anything unexpected. */
    private fun clean(kind: Kind?, o: JSONObject): JSONObject? {
        kind ?: return null
        val out = JSONObject().put("type", kind.type)
        kind.params.forEach { p -> o.opt(p.key)?.let { v -> if (v !== JSONObject.NULL) out.put(p.key, v.toString()) } }
        return out
    }

    suspend fun build(container: AppContainer, words: String): JSONObject {
        val c = container.appContext
        val apps = Connectors.connected(c).joinToString { "${it.id} (${it.name})" }.ifBlank { "none connected yet" }
        val prompt = """Design a phone automation for this request: $words

Building blocks:
${FlowKinds.guide()}
Connected apps (use the id as the connector step's "service"): $apps

Return ONLY JSON like {"name":"short name","trigger":{"type":"schedule","mode":"daily","time":"08:00"},"steps":[{"type":"ai","prompt":"...","out":"answer"},{"type":"notify","title":"...","body":"{{answer}}"}]}
Rules: at most 8 steps; use only the listed types and params; use {{...}} to pass results; put an "ask" step before anything that sends, spends or changes something outside the phone; if the request cannot be done with these blocks, build the closest safe version and say what is missing in a "log" step."""
        val o = jsonFrom(Ai.generate(container.settingsRepository, prompt, "You design automations. Reply with ONLY one JSON object.", 1400))
        val flow = JSONObject().put("name", o.optString("name").ifBlank { "New flow" }.take(60)).put("enabled", false).put("auto", false)
        val t = o.optJSONObject("trigger") ?: JSONObject()
        flow.put("trigger", clean(FlowKinds.trigger(t.optString("type")), t) ?: JSONObject().put("type", "manual"))
        val steps = JSONArray()
        val src = o.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until minOf(src.length(), 10)) { val s = src.optJSONObject(i) ?: continue; clean(FlowKinds.step(s.optString("type")), s)?.let { steps.put(it) } }
        require(steps.length() > 0) { "I could not turn that into steps. Try describing what should happen, step by step." }
        return flow.put("steps", steps)
    }

    /** Drafts the connection for any app that has a web API with a personal token or key. */
    suspend fun connector(container: AppContainer, appName: String): JSONObject {
        val prompt = """The user wants to connect the app "$appName" to their phone using its official web API and a personal token or key.
Use web search if needed. Return ONLY JSON: {"name":"$appName","base":"https://api base address, no trailing slash","auth":"how to send the key","test":"a GET path that answers 200 when the key is valid","tokenUrl":"page where the user makes the key","notes":"one or two lines on what the key needs"}
The auth field uses a tiny template: "H:Authorization=Bearer {token}" adds a header, "Q:api_key={token}" adds a query parameter, "B:user:{token}" is basic sign-in. {token} is the key the user pastes."""
        val o = jsonFrom(Ai.generate(container.settingsRepository, prompt, "You know web APIs. Reply with ONLY one JSON object.", 700, useWeb = true))
        require(o.optString("base").startsWith("https://")) { "I could not find a safe web address for that app." }
        return o
    }
}
