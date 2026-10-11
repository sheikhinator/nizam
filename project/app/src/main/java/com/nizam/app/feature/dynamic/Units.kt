package com.nizam.app.feature.dynamic

/**
 * Typed units for number fields: "500 g" in a kg field is 0.5, "2 lakh" in a PKR field is 200000,
 * "90 min" in an hours field is 1.5. Values stay as typed; only calculations convert.
 */
object Units {
    private data class U(val dim: String, val factor: Double)

    private val TABLE: Map<String, U> = buildMap {
        fun add(dim: String, f: Double, vararg names: String) = names.forEach { this[it] = U(dim, f) }
        add("mass", 1.0, "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms")
        add("mass", 0.001, "g", "gm", "gms", "gram", "grams")
        add("mass", 40.0, "maund", "mann", "mun")
        add("mass", 0.45359237, "lb", "lbs", "pound", "pounds")
        add("mass", 0.01166, "tola", "tolas")
        add("length", 1.0, "m", "metre", "metres", "meter", "meters")
        add("length", 0.01, "cm")
        add("length", 0.001, "mm")
        add("length", 1000.0, "km", "kms")
        add("length", 0.3048, "ft", "feet", "foot")
        add("length", 0.0254, "in", "inch", "inches")
        add("length", 1609.344, "mile", "miles", "mi")
        add("volume", 1.0, "l", "litre", "litres", "liter", "liters", "ltr")
        add("volume", 0.001, "ml")
        add("volume", 3.78541, "gallon", "gallons", "gal")
        add("time", 1.0, "h", "hr", "hrs", "hour", "hours")
        add("time", 1 / 60.0, "min", "mins", "minute", "minutes")
        add("time", 1 / 3600.0, "s", "sec", "secs", "second", "seconds")
        add("time", 24.0, "day", "days", "d")
        add("time", 168.0, "week", "weeks", "wk")
        add("area", 1.0, "sqft", "sq ft", "square feet")
        add("area", 225.0, "marla", "marlas")
        add("area", 4500.0, "kanal", "kanals")
        add("area", 10.7639, "sqm", "m2", "square metre", "square meters")
        add("count", 1.0, "pkr", "rs", "rupees", "x", "pcs", "")
        add("count", 1000.0, "k", "thousand", "hazar", "hazaar")
        add("count", 100000.0, "lakh", "lac", "lakhs")
        add("count", 10000000.0, "crore", "cr", "crores")
    }

    private val NUM = Regex("^\\s*(-?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([a-zA-Z ]*?)\\s*\\.?\\s*$")

    /** The value expressed in the field's own unit, or null if it can't be read. */
    fun toNumber(value: String, fieldUnit: String): Double? {
        val m = NUM.find(value.trim()) ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].trim().lowercase()
        if (unit.isEmpty()) return n
        val from = TABLE[unit] ?: return null
        val target = TABLE[fieldUnit.trim().lowercase()] ?: return if (from.dim == "count") n * from.factor else null
        if (from.dim != target.dim) return null
        return n * from.factor / target.factor
    }

    /** True when the text has a unit Atlas can't fit to the field (shown as a gentle warning). */
    fun mismatch(value: String, fieldUnit: String): Boolean =
        value.isNotBlank() && value.trim().toDoubleOrNull() == null && toNumber(value, fieldUnit) == null
}
