package com.nizam.app.feature.nx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.nizam.app.ui.design.Hub

/** Generated helper tools (Mega21). */
object Mega21 {
    private fun today() = LocalDay.today().toString()

    val all: List<Spec> = listOf(
        AiSpec("budgetfix", "Budget Doctor", "Find where your budget leaks", Icons.Outlined.Insights, Hub.Money, "budget doctor find where your budget leaks ",
            listOf(F("inc", "Income (monthly)", K.LONG), F("exp", "Expenses list or leave blank to use my data", K.LONG, optional = true), F("use", "Include my Atlas data? (Yes sends your logs to your AI provider)", K.CHOICE, options = listOf("No", "Yes"), default = "No")),
            "You are a warm, honest, practical life analyst for one person. You are given their own recorded data when they allow it. Use ONLY facts in that data; never invent numbers or events. Say plainly when the data is too thin and what to start recording. Be short, concrete and kind. Give patterns as 'it looks like', not as certainty. For health, money or religion, give general information, not medical, financial or religious rulings.", tokens = 1500) { v ->
            "Review my monthly budget. Income: ${v["inc"]}. Expenses: ${v["exp"]}. Find the 5 biggest leaks, realistic cuts with rupee amounts, and a new plan. Use my recorded spending if available.\n\n${LifeContext.digest(v["use"], v.values.joinToString(" "))}" }
    )
}
