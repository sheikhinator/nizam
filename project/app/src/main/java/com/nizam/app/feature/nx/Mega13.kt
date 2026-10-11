package com.nizam.app.feature.nx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.nizam.app.ui.design.Hub
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Batch 13: everyday logs and AI helpers — parcels, wardrobe, tailor, insurance, grooming, caffeine, baby, rides, networking, Eid, errands, family quiz. */
object Mega13 {
    private fun today() = LocalDay.today().toString()
    private fun d(o: JSONObject, k: String) = runCatching { LocalDate.parse(o.optString(k)) }.getOrNull()
    private fun days(o: JSONObject, k: String) = d(o, k)?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }

    val all: List<Spec> by lazy { listOf(
        LogSpec("parcels", "Parcel tracker", "TCS, Leopards, Daraz and Pakistan Post parcels with expected dates", Icons.Outlined.LocalShipping, Hub.Home,
            "parcel courier tcs leopards daraz post tracking delivery order package cod",
            listOf(F("what", "What's in it"), F("carrier", "Carrier", K.CHOICE, options = listOf("TCS", "Leopards", "M&P", "Trax", "Daraz", "Pakistan Post", "BlueEx", "Other"), default = "TCS"),
                F("no", "Tracking number", optional = true), F("eta", "Expected", K.DATE, optional = true), F("status", "Status", K.CHOICE, options = listOf("Ordered", "Shipped", "Out for delivery", "Delivered", "Returned"), default = "Shipped"),
                F("cod", "Cash on delivery (PKR)", K.NUMBER, optional = true)),
            line = { o -> "${o.optString("what")} · ${o.optString("carrier")}" to "${o.optString("status")}${if (o.optString("no").isNotBlank()) " · ${o.optString("no")}" else ""}${if (o.optString("eta").isNotBlank()) " · due ${o.optString("eta")}" else ""}${if (num(o.optString("cod")) > 0) " · COD ${rs(num(o.optString("cod")))}" else ""}" },
            summary = { l -> val open = l.filter { it.optString("status") !in setOf("Delivered", "Returned") }
                listOf("${open.size} on the way" + (open.sumOf { num(it.optString("cod")) }.takeIf { it > 0 }?.let { " · keep ${rs(it)} ready for COD" } ?: "")) +
                    open.filter { (days(it, "eta") ?: 99) < 0 }.map { "Late: ${it.optString("what")} (${it.optString("carrier")} ${it.optString("no")})" } }),
        LogSpec("wardrobe", "Wardrobe & laundry", "What you own, how often it's worn, and what needs washing", Icons.Outlined.Checkroom, Hub.Home,
            "wardrobe clothes laundry wash outfit shirt kurta shalwar dress shoes closet",
            listOf(F("item", "Item"), F("kind", "Type", K.CHOICE, options = listOf("Kurta/Shalwar", "Shirt", "Trousers", "Dress", "Abaya/Scarf", "Jacket/Sweater", "Shoes", "Formal", "Sports", "Other"), default = "Shirt"),
                F("color", "Colour", optional = true), F("worn", "Times worn since wash", K.NUMBER, default = "0"), F("washed", "Last washed", K.DATE, optional = true)),
            line = { o -> "${o.optString("item")} · ${o.optString("kind")}" to "${o.optString("color")} · worn ${o.optString("worn")}× · ${o.optString("washed").ifBlank { "never logged washed" }}" },
            summary = { l -> val dirty = l.filter { num(it.optString("worn")) >= 3 }
                listOf("${l.size} items", if (dirty.isEmpty()) "Nothing due for the wash" else "Due for the wash (worn 3+ times): " + dirty.take(8).joinToString { it.optString("item") }) }),
        AiSpec("outfit", "What to wear", "An outfit from your own wardrobe for the day's weather and occasion", Icons.Outlined.Style, Hub.Home,
            "outfit what to wear clothes style occasion weather wardrobe",
            listOf(F("occasion", "Occasion", K.CHOICE, options = listOf("Office", "Eid / wedding", "Casual", "Jumu'ah", "Interview", "Travel", "Gym"), default = "Casual"),
                F("weather", "Weather", hint = "hot and humid, 34°C"), F("have", "What you have (or leave blank to use your Wardrobe)", K.LONG, optional = true)),
            "You are a practical stylist for Pakistani wardrobes. Suggest two complete outfits (clothes, shoes, one accessory), modest where relevant, suited to the weather and occasion. Use only items the user lists. One line on why each works.") { v ->
            "Occasion: ${v["occasion"]}\nWeather: ${v["weather"]}\nWardrobe: ${v["have"]?.ifBlank { "(use common Pakistani basics)" }}" },
        LogSpec("tailor", "Tailor & alterations", "What's with which tailor, when it's due and the agreed price", Icons.Outlined.ContentCut, Hub.Home,
            "tailor stitching alteration boutique suit kurta due date advance price",
            listOf(F("what", "Job", hint = "2 suits stitching"), F("tailor", "Tailor / shop"), F("due", "Ready by", K.DATE), F("price", "Agreed price (PKR)", K.NUMBER, optional = true),
                F("advance", "Advance paid", K.NUMBER, optional = true), F("done", "Collected?", K.CHOICE, options = listOf("No", "Yes"), default = "No")),
            line = { o -> "${o.optString("what")} · ${o.optString("tailor")}" to "ready ${o.optString("due")} · ${rs(num(o.optString("price")))} (advance ${rs(num(o.optString("advance")))})${if (o.optString("done") == "Yes") " · collected" else ""}" },
            summary = { l -> val open = l.filter { it.optString("done") != "Yes" }
                listOf("${open.size} jobs pending · balance owed ${rs(open.sumOf { num(it.optString("price")) - num(it.optString("advance")) })}") +
                    open.filter { (days(it, "due") ?: 99) <= 3 }.map { "Due soon: ${it.optString("what")} at ${it.optString("tailor")} (${it.optString("due")})" } }),
        LogSpec("insurance", "Insurance & policies", "Health, car, life and travel policies with renewal dates", Icons.Outlined.Security, Hub.Money,
            "insurance policy premium renewal claim health car life takaful jubilee efu state life",
            listOf(F("policy", "Policy"), F("insurer", "Insurer"), F("type", "Type", K.CHOICE, options = listOf("Health", "Car", "Life", "Takaful", "Home", "Travel", "Phone", "Other"), default = "Health"),
                F("premium", "Premium (PKR)", K.NUMBER), F("renewal", "Renews on", K.DATE), F("no", "Policy number", optional = true), F("claim", "Claim notes", K.LONG, optional = true)),
            sealed = true,
            line = { o -> "${o.optString("policy")} · ${o.optString("insurer")}" to "${o.optString("type")} · ${rs(num(o.optString("premium")))} · renews ${o.optString("renewal")}" },
            summary = { l -> listOf("Yearly premiums: ${rs(l.sumOf { num(it.optString("premium")) })}") + l.filter { (days(it, "renewal") ?: 999) in 0..30 }.map { "Renews in ${days(it, "renewal")} days: ${it.optString("policy")}" } }),
        LogSpec("grooming", "Haircut & grooming", "When you last had a haircut, trim or nails done, and when you're due", Icons.Outlined.Face, Hub.Health,
            "haircut barber salon grooming beard trim nails facial due cycle",
            listOf(F("kind", "What", K.CHOICE, options = listOf("Haircut", "Beard trim", "Nails", "Facial", "Waxing/threading", "Other"), default = "Haircut"), F("d", "Date", K.DATE, default = today()),
                F("cost", "Cost (PKR)", K.NUMBER, optional = true), F("every", "Repeat every (days)", K.NUMBER, default = "30")),
            line = { o -> o.optString("kind") to "${o.optString("d")} · next in ${o.optString("every")} days${if (num(o.optString("cost")) > 0) " · ${rs(num(o.optString("cost")))}" else ""}" },
            summary = { l -> l.groupBy { it.optString("kind") }.map { (k, e) -> val last = e.maxByOrNull { it.optString("d") }!!
                val due = d(last, "d")?.plusDays(num(last.optString("every")).toLong()); val left = due?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
                "$k: " + (left?.let { if (it < 0) "overdue by ${-it} days" else "due in $it days" } ?: "—") } }),
        LogSpec("caffeine", "Caffeine & sunlight", "Cups of tea/coffee and minutes outside, next to how you slept", Icons.Outlined.WbSunny, Hub.Health,
            "caffeine chai tea coffee sunlight outside vitamin d daylight energy drink",
            listOf(F("d", "Date", K.DATE, default = today()), F("cups", "Cups of chai/coffee/energy drinks", K.NUMBER, default = "0"), F("sun", "Minutes in daylight", K.NUMBER, default = "0"), F("last", "Last caffeine at (hour, 24h)", K.NUMBER, optional = true)),
            line = { o -> o.optString("d") to "${o.optString("cups")} cups · ${o.optString("sun")} min outside${if (o.optString("last").isNotBlank()) " · last at ${o.optString("last")}:00" else ""}" },
            summary = { l -> val w = l.sortedBy { it.optString("d") }.takeLast(7)
                listOf("7-day average: ${"%.1f".format(w.map { num(it.optString("cups")) }.average().takeIf { !it.isNaN() } ?: 0.0)} cups, ${(w.map { num(it.optString("sun")) }.average().takeIf { !it.isNaN() } ?: 0.0).toInt()} min daylight") +
                    listOfNotNull(if (w.any { num(it.optString("last")) >= 16 }) "Caffeine after 4pm on some days — it can cost you sleep." else null) }),
        LogSpec("babylog", "Baby & pregnancy log", "Feeds, sleep, nappies, weight, vaccines and appointments", Icons.Outlined.ChildFriendly, Hub.Health,
            "baby newborn feed sleep nappy diaper weight vaccine vaccination appointment pregnancy checkup",
            listOf(F("kind", "What", K.CHOICE, options = listOf("Feed", "Sleep", "Nappy", "Weight", "Vaccine", "Appointment", "Medicine", "Note"), default = "Feed"),
                F("d", "Date", K.DATE, default = today()), F("amount", "Amount / minutes / kg", K.NUMBER, optional = true), F("note", "Note", optional = true)),
            sealed = true,
            line = { o -> "${o.optString("kind")}${if (o.optString("amount").isNotBlank()) " · ${o.optString("amount")}" else ""}" to "${o.optString("d")}${if (o.optString("note").isNotBlank()) " · ${o.optString("note")}" else ""}" },
            summary = { l -> val t = l.filter { it.optString("d") == today() }.groupBy { it.optString("kind") }.map { (k, e) -> "$k ×${e.size}" }
                listOf("Today: " + t.joinToString().ifBlank { "nothing logged" }) + l.filter { it.optString("kind") == "Appointment" && (days(it, "d") ?: -1) in 0..7 }.map { "Coming up: ${it.optString("note")} (${it.optString("d")})" } }),
        LogSpec("rides", "Rides & commute cost", "Careem, Bykea, inDrive, bus or bike: what each way of getting around really costs", Icons.Outlined.DirectionsBus, Hub.Money,
            "ride careem uber bykea indrive rickshaw bus metro commute fare carpool cost",
            listOf(F("d", "Date", K.DATE, default = today()), F("how", "How", K.CHOICE, options = listOf("Careem", "Uber", "Bykea", "inDrive", "Rickshaw", "Bus/Metro", "Own bike", "Own car", "Carpool", "Walk"), default = "Careem"),
                F("route", "From → to", optional = true), F("fare", "Fare / fuel (PKR)", K.NUMBER), F("km", "Distance (km)", K.NUMBER, optional = true)),
            line = { o -> "${o.optString("how")} · ${rs(num(o.optString("fare")))}" to "${o.optString("d")}${if (o.optString("route").isNotBlank()) " · ${o.optString("route")}" else ""}" },
            summary = { l -> val m = l.filter { it.optString("d").startsWith(today().take(7)) }
                m.groupBy { it.optString("how") }.map { (h, e) -> "$h: ${rs(e.sumOf { num(it.optString("fare")) })} in ${e.size} trips" }.ifEmpty { listOf("No rides this month") } + listOf("Month total ${rs(m.sumOf { num(it.optString("fare")) })}") }),
        LogSpec("networking", "Networking notes", "Who you met, where, what you promised and when to follow up", Icons.Outlined.Handshake, Hub.Work,
            "networking contact met follow up promise business card conference meeting person",
            listOf(F("who", "Who"), F("where", "Where / event", optional = true), F("d", "Met on", K.DATE, default = today()), F("topic", "What you talked about", K.LONG, optional = true),
                F("promise", "What you promised", optional = true), F("follow", "Follow up on", K.DATE, optional = true)),
            line = { o -> "${o.optString("who")}${if (o.optString("where").isNotBlank()) " · ${o.optString("where")}" else ""}" to "${o.optString("d")}${if (o.optString("promise").isNotBlank()) " · promised: ${o.optString("promise")}" else ""}${if (o.optString("follow").isNotBlank()) " · follow up ${o.optString("follow")}" else ""}" },
            summary = { l -> l.filter { (days(it, "follow") ?: 999) <= 0 && it.optString("follow").isNotBlank() }.map { "Follow up with ${it.optString("who")} (${it.optString("promise").ifBlank { it.optString("topic").take(40) }})" }.ifEmpty { listOf("No follow-ups due") } }),
        LogSpec("eidplan", "Eid planner", "Clothes, gifts, food and Eidi with a budget", Icons.Outlined.Celebration, Hub.Home,
            "eid planner shopping clothes gifts eidi food budget fitr adha mehndi chand raat",
            listOf(F("item", "Item"), F("cat", "Category", K.CHOICE, options = listOf("Clothes", "Gifts", "Food", "Eidi", "Decor", "Travel", "Charity", "Other"), default = "Clothes"),
                F("for", "For whom", optional = true), F("cost", "Cost (PKR)", K.NUMBER), F("status", "Status", K.CHOICE, options = listOf("To buy", "Bought", "Given"), default = "To buy"),
                F("budget", "Total Eid budget (set on any one row)", K.NUMBER, optional = true)),
            line = { o -> "${o.optString("item")}${if (o.optString("for").isNotBlank()) " → ${o.optString("for")}" else ""}" to "${o.optString("cat")} · ${rs(num(o.optString("cost")))} · ${o.optString("status")}" },
            summary = { l -> val budget = l.maxOfOrNull { num(it.optString("budget")) } ?: 0.0; val plan = l.sumOf { num(it.optString("cost")) }; val done = l.filter { it.optString("status") != "To buy" }.sumOf { num(it.optString("cost")) }
                listOf("Planned ${rs(plan)} · spent ${rs(done)}" + if (budget > 0) " · budget ${rs(budget)} (${if (plan > budget) "over by ${rs(plan - budget)}" else "${rs(budget - plan)} left"})" else "") +
                    l.groupBy { it.optString("cat") }.map { (c, e) -> "$c: ${rs(e.sumOf { num(it.optString("cost")) })}" } }),
        AiSpec("errands", "Errands run", "Group the day's errands by area so you do them in one trip", Icons.Outlined.AltRoute, Hub.Travel,
            "errands trip route plan shopping bank pharmacy market area grouped order shortest",
            listOf(F("list", "Errands (one per line, with the area if you know it)", K.LONG, hint = "Bank – Gulberg\nPharmacy – near home\nCollect parcel – Johar Town"), F("start", "Starting from"), F("time", "Time available", hint = "3 hours"),
                F("mode", "Travelling by", K.CHOICE, options = listOf("Car", "Bike", "Rickshaw/ride", "Walk"), default = "Bike")),
            "You plan efficient errand runs in Pakistani cities. Group the errands by area, put them in a sensible order (banks and offices before closing time, perishables last, prayer times respected), give each a rough duration and say what to skip if time runs out.") { v ->
            "Errands:\n${v["list"]}\nStart: ${v["start"]}\nTime: ${v["time"]}\nBy: ${v["mode"]}" },
        AiSpec("familyquiz", "Family quiz night", "A quiz written from your own family stories and photos' captions", Icons.Outlined.Quiz, Hub.Social,
            "family quiz night game trivia memories questions kids cousins fun",
            listOf(F("facts", "Family facts and stories", K.LONG, hint = "Dadi's village is Chakwal. Ali broke his arm in 2016. We went to Murree in 2019 and the car got stuck…"),
                F("n", "Questions", K.NUMBER, default = "12"), F("who", "Who's playing", K.CHOICE, options = listOf("Kids", "Teens", "Adults", "Everyone"), default = "Everyone")),
            "You write a friendly family quiz using ONLY the facts given. Mix easy and tricky questions, a few multiple choice, a couple of 'who said it' or 'what year', and a bonus round. End with an answer key. Keep it kind: nothing embarrassing.") { v ->
            "Facts:\n${v["facts"]}\nQuestions: ${v["n"]}\nPlayers: ${v["who"]}" },
    ) }
}
