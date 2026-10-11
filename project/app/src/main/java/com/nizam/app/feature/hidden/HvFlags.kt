package com.nizam.app.feature.hidden

/**
 * Turns the origins you type on an item — in performers or tags, e.g. "Arab", "American", "Russian", "Brazil" —
 * into flags for the corner of its thumbnail. Regions without one flag get a globe.
 */
object HvFlags {
    private fun flag(iso: String) = iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    private val WORDS: Map<String, String> = buildMap {
        fun c(iso: String, vararg w: String) = w.forEach { put(it, flag(iso)) }
        c("US", "american", "america", "usa", "us"); c("GB", "british", "uk", "english", "england", "scottish", "welsh"); c("RU", "russian", "russia")
        c("UA", "ukrainian", "ukraine"); c("BY", "belarusian", "belarus"); c("SA", "saudi", "arabian"); c("AE", "arab", "arabic", "emirati", "dubai", "uae")
        c("LB", "lebanese", "lebanon"); c("EG", "egyptian", "egypt"); c("MA", "moroccan", "morocco"); c("DZ", "algerian", "algeria"); c("TN", "tunisian", "tunisia")
        c("TR", "turkish", "turkey", "turk"); c("IR", "iranian", "persian", "iran"); c("IQ", "iraqi", "iraq"); c("SY", "syrian", "syria"); c("JO", "jordanian", "jordan")
        c("KW", "kuwaiti", "kuwait"); c("QA", "qatari", "qatar"); c("OM", "omani", "oman"); c("BH", "bahraini", "bahrain"); c("IL", "israeli", "israel")
        c("PK", "pakistani", "pakistan", "desi", "paki"); c("IN", "indian", "india"); c("BD", "bangladeshi", "bangladesh", "bengali"); c("AF", "afghan", "afghanistan")
        c("NP", "nepali", "nepal"); c("LK", "sri lankan", "srilankan"); c("CN", "chinese", "china"); c("JP", "japanese", "japan", "jav"); c("KR", "korean", "korea")
        c("TW", "taiwanese", "taiwan"); c("TH", "thai", "thailand"); c("PH", "filipina", "filipino", "philippines", "pinay"); c("VN", "vietnamese", "vietnam")
        c("ID", "indonesian", "indonesia"); c("MY", "malaysian", "malaysia"); c("SG", "singaporean", "singapore"); c("MN", "mongolian", "mongolia")
        c("KZ", "kazakh", "kazakhstan"); c("UZ", "uzbek", "uzbekistan"); c("AM", "armenian", "armenia"); c("GE", "georgian", "georgia")
        c("BR", "brazilian", "brazil", "brasil"); c("CO", "colombian", "colombia"); c("MX", "mexican", "mexico"); c("AR", "argentinian", "argentine", "argentina")
        c("VE", "venezuelan", "venezuela"); c("CU", "cuban", "cuba"); c("PR", "puerto rican"); c("DO", "dominican"); c("PE", "peruvian", "peru"); c("CL", "chilean", "chile")
        c("ES", "spanish", "spain"); c("FR", "french", "france"); c("DE", "german", "germany"); c("IT", "italian", "italy"); c("NL", "dutch", "netherlands", "holland")
        c("BE", "belgian", "belgium"); c("PL", "polish", "poland"); c("CZ", "czech", "czechia"); c("SK", "slovak", "slovakia"); c("HU", "hungarian", "hungary")
        c("RO", "romanian", "romania"); c("BG", "bulgarian", "bulgaria"); c("RS", "serbian", "serbia"); c("HR", "croatian", "croatia"); c("GR", "greek", "greece")
        c("PT", "portuguese", "portugal"); c("IE", "irish", "ireland"); c("SE", "swedish", "sweden"); c("NO", "norwegian", "norway"); c("DK", "danish", "denmark")
        c("FI", "finnish", "finland"); c("AT", "austrian", "austria"); c("CH", "swiss", "switzerland"); c("CA", "canadian", "canada"); c("AU", "australian", "australia")
        c("NZ", "kiwi", "new zealand"); c("NG", "nigerian", "nigeria"); c("ZA", "south african"); c("KE", "kenyan", "kenya"); c("ET", "ethiopian", "ethiopia"); c("GH", "ghanaian", "ghana")
        put("european", "🇪🇺"); put("europe", "🇪🇺"); put("asian", "🌏"); put("asia", "🌏"); put("latina", "🌎"); put("latino", "🌎"); put("latin", "🌎")
        put("african", "🌍"); put("africa", "🌍"); put("middle eastern", "🌙"); put("scandinavian", "❄"); put("slavic", "🪆")
    }

    /** The flags for whatever origins appear in these words (performers, tags), in order, without repeats. */
    fun of(words: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        words.forEach { w ->
            val parts = w.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
            // two-word origins first ("south african", "puerto rican", "sri lankan"), then single words
            parts.zipWithNext { a, b -> "$a $b" }.forEach { WORDS[it]?.let { f -> out += f } }
            parts.forEach { WORDS[it]?.let { f -> out += f } }
        }
        return out.toList()
    }

    fun of(i: Hv.Item) = of(i.performers + i.tags)
}
