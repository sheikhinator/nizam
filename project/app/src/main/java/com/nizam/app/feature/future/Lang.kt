package com.nizam.app.feature.future

import android.content.Context
import com.nizam.app.net.Router
import java.util.Locale

/**
 * Urdu-first Atlas: the language it speaks, listens in and writes to you. English, Urdu (اردو) or Roman Urdu
 * (how most Pakistanis text). Mixed speech ("kal 5 baje Ali ko remind karna") is understood in every mode.
 */
object Lang {
    val MODES = linkedMapOf("en" to "English", "ur" to "اردو", "roman" to "Roman Urdu", "pa" to "پنجابی (Punjabi)", "sd" to "سنڌي (Sindhi)", "ps" to "پښتو (Pashto)", "skr" to "سرائیکی (Saraiki)", "ar" to "العربية (Arabic)")

    private fun p(c: Context?) = c?.getSharedPreferences("lang", 0)
    fun mode(c: Context? = Router.appContext) = p(c)?.getString("mode", "en") ?: "en"
    fun set(c: Context, m: String) = p(c)?.edit()?.putString("mode", m)?.apply()

    /** The voice Atlas speaks with. Roman Urdu reads best with an Indian-English voice, which says Hinglish words naturally. */
    fun locale(c: Context? = Router.appContext): Locale = when (mode(c)) {
        "ur" -> Locale("ur", "PK")
        "pa" -> Locale("pa", "PK")
        "sd" -> Locale("sd", "PK")
        "ps" -> Locale("ps", "AF")
        "skr" -> Locale("ur", "PK")
        "ar" -> Locale("ar", "SA")
        "roman" -> Locale("en", "IN")
        else -> Locale.getDefault()
    }

    /** What speech recognition listens for; null keeps the phone's own language. */
    fun speechTag(c: Context? = Router.appContext): String? = when (mode(c)) { "en" -> null; "pa" -> "pa-PK"; "sd" -> "sd-PK"; "ps" -> "ps-AF"; "ar" -> "ar-SA"; else -> "ur-PK" }

    /** Added to every AI request so replies come back in the chosen language. */
    fun instruction(c: Context? = Router.appContext): String = when (mode(c)) {
        "ur" -> "Write everything meant for the user in Urdu (اردو script), natural and warm. Keep JSON keys, code, app names and commands in English."
        "roman" -> "Write everything meant for the user in Roman Urdu — Urdu in English letters, the way Pakistanis text (e.g. \"theek hai, kal 5 baje yaad dila doon ga\"). " +
            "Keep JSON keys, code, app names and commands in English."
        "pa" -> "Write everything meant for the user in Punjabi (Shahmukhi script, پنجابی), natural and warm. Keep JSON keys, code, app names and commands in English."
        "sd" -> "Write everything meant for the user in Sindhi (سنڌي), natural and warm. Keep JSON keys, code, app names and commands in English."
        "ps" -> "Write everything meant for the user in Pashto (پښتو), natural and warm. Keep JSON keys, code, app names and commands in English."
        "skr" -> "Write everything meant for the user in Saraiki (سرائیکی, Shahmukhi script), natural and warm; where unsure, use simple Urdu. Keep JSON keys, code, app names and commands in English."
        "ar" -> "Write everything meant for the user in clear Modern Standard Arabic (العربية). Keep JSON keys, code, app names and commands in English."
        else -> ""
    }
}
