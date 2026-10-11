package com.nizam.app.feature.nx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.nizam.app.ui.design.Hub

/** Generated helper tools (Mega22). */
object Mega22 {
    private fun today() = LocalDay.today().toString()

    val all: List<Spec> = listOf(
        AiSpec("atlaslearned", "What Atlas Learned About You", "A weekly look at what Atlas thinks it knows, so you can correct it", Icons.Outlined.Psychology, Hub.Memory, "weekly review learned about me correct facts memory what does atlas know",
            listOf(F("use", "Include my Atlas data? (Yes sends your logs to your AI provider)", K.CHOICE, options = listOf("Yes", "No"), default = "Yes")),
            "You are a warm, honest, practical life analyst for one person. You are given their own recorded data when they allow it. Use ONLY facts in that data; never invent numbers or events. Say plainly when the data is too thin and what to start recording. Be short, concrete and kind. Give patterns as 'it looks like', not as certainty. For health, money or religion, give general information, not medical, financial or religious rulings.", tokens = 1200) { v ->
            "Write a short weekly note for me. 1) What you learned about me this week from my data and what you already remember. 2) What you may have gotten wrong or are unsure of. 3) Five short questions I can answer to correct you. Keep it warm and under 250 words.\n\n${LifeContext.digest(v["use"], v.values.joinToString(" "))}" }
    )
}
