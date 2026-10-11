package com.nizam.app.feature.future

import com.nizam.app.net.Router

/**
 * The privacy shield for online AI: before a request leaves the phone, personal identifiers (phone numbers,
 * CNICs, card numbers, IBANs, emails) are swapped for placeholders like [PHONE_1]; the answer comes back with
 * the real values put back. The cloud model never sees them, yet actions still get the real number.
 */
object Privacy {
    class Map { val real = LinkedHashMap<String, String>(); private val counts = HashMap<String, Int>()
        fun tag(kind: String, value: String): String {
            real.entries.firstOrNull { it.value == value }?.let { return it.key }
            val n = (counts[kind] ?: 0) + 1; counts[kind] = n
            return "[${kind}_$n]".also { real[it] = value }
        }
    }

    private val RULES = listOf(
        "EMAIL" to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        "IBAN" to Regex("\\bPK\\d{2}[A-Z]{4}\\d{16}\\b"),
        "CNIC" to Regex("\\b\\d{5}-\\d{7}-\\d\\b"),
        "CARD" to Regex("\\b(?:\\d[ -]?){13,19}\\b"),
        "PHONE" to Regex("(?<![\\w\\d])(?:\\+92|0092|0)3\\d{2}[ -]?\\d{7}(?!\\d)|(?<![\\w\\d])\\+\\d{1,3}[ -]?\\d{3,4}[ -]?\\d{3,4}[ -]?\\d{3,4}(?!\\d)"),
    )

    fun on() = Router.appContext?.getSharedPreferences("privacy", 0)?.getBoolean("shield", true) ?: true
    fun set(on: Boolean) = Router.appContext?.getSharedPreferences("privacy", 0)?.edit()?.putBoolean("shield", on)?.apply()

    fun redact(text: String?, m: Map): String? {
        if (text.isNullOrEmpty()) return text
        var out: String = text
        for ((kind, re) in RULES) out = re.replace(out) { r ->
            // a card-like run of digits must pass the Luhn check, or it's probably just a big number
            if (kind == "CARD" && !luhn(r.value.filter(Char::isDigit))) r.value else m.tag(kind, r.value)
        }
        return out
    }

    fun restore(text: String, m: Map): String { var out = text; m.real.forEach { (k, v) -> out = out.replace(k, v) }; return out }

    private fun luhn(d: String): Boolean {
        if (d.length !in 13..19) return false
        var sum = 0; var alt = false
        for (i in d.indices.reversed()) { var n = d[i] - '0'; if (alt) { n *= 2; if (n > 9) n -= 9 }; sum += n; alt = !alt }
        return sum % 10 == 0
    }

    /** For streamed answers: holds back a half-arrived "[PHONE_" until its "]" comes, then restores it. */
    class Restorer(private val m: Map, private val emit: (String) -> Unit) {
        private val buf = StringBuilder()
        fun push(piece: String) {
            buf.append(piece)
            val open = buf.lastIndexOf("[")
            val cut = if (open >= 0 && buf.indexOf("]", open) < 0 && buf.length - open < 20) open else buf.length
            if (cut > 0) { emit(restore(buf.substring(0, cut), m)); buf.delete(0, cut) }
        }
        fun flush() { if (buf.isNotEmpty()) { emit(restore(buf.toString(), m)); buf.clear() } }
    }
}
