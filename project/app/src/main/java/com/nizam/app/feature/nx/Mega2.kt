package com.nizam.app.feature.nx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.nizam.app.ui.design.Hub
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Mega batch 2: the backlog's tools for health, faith, learning, writing, work, home and travel. */
object Mega2 {
    private val LANG = listOf("English", "Urdu", "Roman Urdu")
    private fun today() = LocalDay.today().toString()
    private fun d(o: JSONObject, k: String) = runCatching { LocalDate.parse(o.optString(k)) }.getOrNull()
    private fun daysTo(o: JSONObject, k: String) = d(o, k)?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }

    val all: List<Spec> by lazy { health + faith + learn + write + work + home + travel }

    private val health = listOf(
        AiSpec("clinicpack", "Clinic visit pack", "One page for the doctor: symptoms, medicines, questions", Icons.Outlined.MedicalServices, Hub.Health, "doctor visit clinic appointment summary questions",
            listOf(F("why", "Reason for the visit"), F("since", "Since when / how it changed", K.LONG), F("meds", "Current medicines", optional = true), F("hist", "Relevant history", optional = true),
                F("worry", "What worries you most", optional = true)),
            "You prepare a concise, doctor-friendly one-page visit summary: presenting complaint, timeline, medicines, history, and the 5 most useful questions to ask. No diagnosis.") { v ->
            "Reason: ${v["why"]}\nTimeline: ${v["since"]}\nMedicines: ${v["meds"]}\nHistory: ${v["hist"]}\nWorry: ${v["worry"]}" },
        LogSpec("adherence", "Medicine adherence", "Did you take it? Streaks and missed doses", Icons.Outlined.TaskAlt, Hub.Health, "medicine taken missed dose adherence",
            listOf(F("med", "Medicine"), F("dose", "Dose time", K.CHOICE, options = listOf("Morning", "Noon", "Evening", "Night"), default = "Morning"),
                F("st", "Status", K.CHOICE, options = listOf("Taken", "Missed", "Late"), default = "Taken"), F("d", "Date", K.DATE, default = today())),
            line = { o -> "${o.optString("med")} · ${o.optString("dose")}" to "${o.optString("st")} · ${o.optString("d")}" },
            summary = { l -> val w = l.filter { (daysTo(it, "d") ?: -99) >= -7 }
                listOf("Last 7 days: ${w.count { it.optString("st") == "Taken" }} taken, ${w.count { it.optString("st") == "Missed" }} missed") }),
        AiSpec("trainingcycle", "Training cycle", "A multi-week programme with deloads and progression", Icons.Outlined.FitnessCenter, Hub.Health, "workout programme periodization mesocycle deload strength",
            listOf(F("goal", "Goal", K.CHOICE, options = listOf("Strength", "Muscle", "Fat loss", "Endurance", "Running 5k/10k"), default = "Muscle"),
                F("weeks", "Weeks", K.NUMBER, default = "8"), F("days", "Days a week", K.NUMBER, default = "4"), F("kit", "Equipment", default = "full gym"), F("level", "Level", K.CHOICE, options = listOf("Beginner", "Intermediate", "Advanced"), default = "Intermediate")),
            "You are a strength coach. Write a progressive multi-week programme with weekly targets, a deload, warm-ups, and how to adjust if a session is missed.") { v ->
            "${v["weeks"]}-week ${v["goal"]} cycle, ${v["days"]} days/week, ${v["kit"]}, ${v["level"]}." },
        CalcSpec("sleepreg", "Sleep regularity", "How steady your sleep is, and a target window", Icons.Outlined.Bedtime, Hub.Health, "sleep schedule regular bedtime wake",
            listOf(F("bed1", "Earliest bedtime this week (hour, 24h)", K.NUMBER, default = "23"), F("bed2", "Latest bedtime (hour, add 24 if after midnight)", K.NUMBER, default = "26"),
                F("wake1", "Earliest wake (hour)", K.NUMBER, default = "6"), F("wake2", "Latest wake (hour)", K.NUMBER, default = "9"), F("fajr", "Fajr time (hour)", K.NUMBER, default = "5")),
            listOf("Bedtime spread (hours)" to "bed2 - bed1", "Wake spread (hours)" to "wake2 - wake1", "Target bedtime" to "fajr + 24 - 7.5 - 0.25",
                "Regularity score /100" to "clamp(100 - (bed2 - bed1 + wake2 - wake1) * 12, 0, 100)"),
            "A steady window matters as much as hours. The target gives 7.5 hours before Fajr."),
    )

    private val faith = listOf(
        LogSpec("fasting", "Fasting log", "Ramadan, qaza, Mondays and Thursdays, white days", Icons.Outlined.DarkMode, Hub.Deen, "fast roza fasting log qaza nafl",
            listOf(F("kind", "Fast", K.CHOICE, options = listOf("Ramadan", "Qaza", "Monday/Thursday", "White days", "Arafah", "Ashura", "Shawwal six", "Other nafl"), default = "Monday/Thursday"),
                F("d", "Date", K.DATE, default = today()), F("note", "Note", optional = true)),
            line = { o -> o.optString("kind") to "${o.optString("d")} ${o.optString("note")}" },
            summary = { l -> listOf("This year: ${l.count { it.optString("d").startsWith(today().take(4)) }} fasts",
                "Qaza made up: ${l.count { it.optString("kind") == "Qaza" }}") }),
        LogSpec("worship", "My worship routine", "Your personal daily and weekly acts, ticked off", Icons.Outlined.Mosque, Hub.Deen, "routine worship wird daily adhkar tahajjud duha",
            listOf(F("act", "Act", hint = "Tahajjud, 1 juz, Surah Mulk at night"), F("d", "Date", K.DATE, default = today()), F("done", "Done", K.CHOICE, options = listOf("Yes", "Partly", "No"), default = "Yes")),
            line = { o -> o.optString("act") to "${o.optString("d")} · ${o.optString("done")}" },
            summary = { l -> l.groupBy { it.optString("act").trim().lowercase() }.map { (a, e) ->
                "${a.replaceFirstChar { it.uppercase() }}: ${e.count { it.optString("done") == "Yes" }} days" } }),
        LogSpec("reflect", "Ayah reflections", "Your own notes on ayahs that moved you", Icons.Outlined.EditNote, Hub.Deen, "ayah reflection tadabbur notes quran",
            listOf(F("ref", "Ayah", hint = "2:286"), F("note", "Reflection", K.LONG), F("d", "Date", K.DATE, default = today())),
            line = { o -> o.optString("ref") to "${o.optString("d")} · ${o.optString("note").take(70)}" }),
        AiSpec("hajj", "Hajj & Umrah companion", "Step-by-step rites, duas, packing and a day plan", Icons.Outlined.TravelExplore, Hub.Deen, "hajj umrah ihram tawaf saee rites makkah madinah",
            listOf(F("kind", "Journey", K.CHOICE, options = listOf("Umrah", "Hajj (Tamattu')", "Hajj (Ifrad)", "Hajj (Qiran)"), default = "Umrah"),
                F("when", "When", K.DATE, optional = true), F("who", "Who's going", default = "me"), F("lang", "Language", K.CHOICE, options = LANG, default = "Urdu")),
            "You guide pilgrims accurately per mainstream Sunni fiqh, noting where madhabs differ: each rite in order, its duas (Arabic with meaning), common mistakes, packing, health tips and a day-by-day plan. Advise confirming details with their group's scholar.") { v ->
            "${v["kind"]} for ${v["who"]}${v["when"]?.takeIf { it.isNotBlank() }?.let { " on $it" } ?: ""}, in ${v["lang"]}." },
        LogSpec("hajjpack", "Pilgrimage checklist", "Documents, ihram, medicines and items: ticked off", Icons.Outlined.Checklist, Hub.Deen, "hajj umrah packing checklist ihram",
            listOf(F("item", "Item"), F("st", "Status", K.CHOICE, options = listOf("Need", "Packed"), default = "Need")),
            line = { o -> o.optString("item") to o.optString("st") },
            summary = { l -> listOf("Packed ${l.count { it.optString("st") == "Packed" }} of ${l.size}") }),
    )

    private val learn = listOf(
        ChatSpec("viva", "Oral viva coach", "An examiner asks; you answer out loud; get marked", Icons.Outlined.RecordVoiceOver, Hub.Learn, "viva oral exam defense examiner questions",
            setup = listOf(F("topic", "Topic / thesis"), F("level", "Level", K.CHOICE, options = listOf("School", "University", "Thesis defence", "Job technical round"), default = "University")),
            opener = "I'm your examiner. Ready? First question coming.") { v ->
            "You are a fair but probing examiner for a ${v["level"]} viva on ${v["topic"]}. Ask one question at a time, follow up on weak answers, and after each answer give a mark out of 10 with what a top answer would add." },
        ChatSpec("pronounce", "Pronunciation lab", "Say it, get feedback on sounds and stress", Icons.Outlined.Mic, Hub.Learn, "pronunciation accent english arabic sounds",
            setup = listOf(F("lang", "Language", K.CHOICE, options = listOf("English", "Arabic (Quranic)", "French", "Turkish"), default = "English")),
            opener = "Type or dictate a word or sentence the way you'd say it, and I'll coach you.") { v ->
            "You are a pronunciation coach for ${v["lang"]}. For each word or sentence, give the IPA or a clear phonetic guide, mark the stress, point out sounds Urdu speakers often get wrong, and give a minimal-pair drill." },
        AiSpec("capstone", "Capstone builder", "Turn a course into a real project that proves the skill", Icons.Outlined.Construction, Hub.Learn, "course project capstone portfolio practice",
            listOf(F("course", "What you learned"), F("level", "Your level", K.CHOICE, options = listOf("Beginner", "Intermediate", "Advanced"), default = "Beginner")),
            "You design capstone projects: a realistic brief, milestones, a rubric, and how to present it in a portfolio.") { v -> "Course: ${v["course"]}. Level: ${v["level"]}." },
        AiSpec("newscompare", "News, side by side", "How different outlets report the same story", Icons.Outlined.CompareArrows, Hub.Learn, "news compare bias sources outlets",
            listOf(F("story", "Story or topic")),
            "You compare coverage across several outlets (local and international) with links: what each emphasises, what's disputed, what's verified, and loaded language. Neutral.", web = true) { v ->
            "Compare coverage of: ${v["story"]}" },
        AiSpec("prereq", "Learning map", "What to learn first, in order, to reach a goal", Icons.Outlined.AccountTree, Hub.Learn, "prerequisites roadmap learning path order",
            listOf(F("goal", "Where you want to get"), F("know", "What you already know", optional = true)),
            "You draw learning roadmaps: prerequisites in dependency order, what to skip given what they know, free resources for each step, and time estimates.") { v ->
            "Goal: ${v["goal"]}. Already know: ${v["know"]}" },
    )

    private val write = listOf(
        AiSpec("brief", "Writing brief", "Audience, purpose, angle and outline before you write", Icons.Outlined.Assignment, Hub.Create, "writing brief outline article essay plan",
            listOf(F("piece", "What you're writing"), F("aud", "For whom"), F("len", "Length", default = "1000 words")),
            "You are an editor. Produce a one-page brief: purpose, reader, key message, angle, structure with section beats, evidence needed, and a strong opening line.") { v ->
            "${v["piece"]} for ${v["aud"]}, ${v["len"]}." },
        LogSpec("styleguide", "My style guide", "Your own writing rules Atlas follows", Icons.Outlined.Rule, Hub.Create, "style guide writing rules voice",
            listOf(F("rule", "Rule", hint = "Say 'Pakistan', not 'Pak'; British spelling; no exclamation marks"), F("why", "Why", optional = true)),
            line = { o -> o.optString("rule") to o.optString("why") }),
        AiSpec("consistency", "Consistency pass", "Catch contradictions, names, dates and tense slips", Icons.Outlined.Spellcheck, Hub.Create, "consistency check proofread contradictions edit",
            listOf(F("text", "Your draft", K.LONG)),
            "You are a meticulous copy editor. List contradictions, inconsistent names/spellings/dates/numbers, tense and voice slips, and unsupported claims, with line quotes and fixes. Don't rewrite the whole text.") { v -> v["text"].orEmpty() },
        LogSpec("claims", "Claim & source ledger", "Every claim in your piece, with its source", Icons.Outlined.FactCheck, Hub.Create, "claims sources citations fact check research",
            listOf(F("claim", "Claim"), F("src", "Source / link"), F("st", "Status", K.CHOICE, options = listOf("Verified", "Needs source", "Disputed"), default = "Needs source")),
            line = { o -> o.optString("claim").take(70) to "${o.optString("st")} · ${o.optString("src")}" },
            summary = { l -> listOf("${l.count { it.optString("st") == "Verified" }} verified, ${l.count { it.optString("st") == "Needs source" }} need sources") }),
        AiSpec("longform", "Long-form desk", "Draft a long piece section by section with your brief", Icons.Outlined.Article, Hub.Create, "long form article essay chapter draft",
            listOf(F("brief", "Brief or outline", K.LONG), F("section", "Section to write now"), F("prev", "Previous section (for continuity)", K.LONG, optional = true),
                F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You are a skilled long-form writer. Write only the requested section, consistent with the brief and the previous section, in a natural human voice.", tokens = 2600) { v ->
            "Brief:\n${v["brief"]}\n\nPrevious:\n${v["prev"]}\n\nWrite this section in ${v["lang"]}: ${v["section"]}" },
    )

    private val work = listOf(
        CalcSpec("offers", "Job offer comparator", "Two offers, compared on what you actually keep", Icons.Outlined.CompareArrows, Hub.Work, "job offer compare salary benefits commute",
            listOf(F("s1", "Offer A monthly salary", K.NUMBER, default = "200000"), F("b1", "Offer A yearly bonus + benefits", K.NUMBER, default = "200000"), F("c1", "Offer A monthly commute/costs", K.NUMBER, default = "15000"),
                F("s2", "Offer B monthly salary", K.NUMBER, default = "180000"), F("b2", "Offer B yearly bonus + benefits", K.NUMBER, default = "400000"), F("c2", "Offer B monthly commute/costs", K.NUMBER, default = "5000")),
            listOf("Offer A per year (net of costs)" to "s1*12 + b1 - c1*12", "Offer B per year (net of costs)" to "s2*12 + b2 - c2*12",
                "Difference (A − B)" to "(s1*12 + b1 - c1*12) - (s2*12 + b2 - c2*12)"),
            "Before tax. Weigh growth, learning, hours and culture too; ask the board of mentors for a second view."),
        LogSpec("receivables", "Money owed to me", "Invoices sent, due dates and follow-ups", Icons.Outlined.ReceiptLong, Hub.Work, "receivables invoices unpaid client follow up",
            listOf(F("client", "Client"), F("amt", "Amount", K.NUMBER), F("due", "Due", K.DATE), F("st", "Status", K.CHOICE, options = listOf("Sent", "Reminded", "Paid", "Disputed"), default = "Sent")),
            line = { o -> "${o.optString("client")} · ${rs(num(o.optString("amt")))}" to "${o.optString("st")} · due ${o.optString("due")}" },
            summary = { l -> val open = l.filter { it.optString("st") != "Paid" }
                listOf("Outstanding: ${rs(open.sumOf { num(it.optString("amt")) })}", "Overdue: ${open.count { (daysTo(it, "due") ?: 1) < 0 }}") }),
        LogSpec("clientprofit", "Client profitability", "Hours and costs against what each client pays", Icons.Outlined.QueryStats, Hub.Work, "client profitability hours rate freelance",
            listOf(F("client", "Client"), F("hrs", "Hours", K.NUMBER), F("paid", "Paid", K.NUMBER), F("cost", "Costs", K.NUMBER, default = "0")),
            line = { o -> o.optString("client") to "${o.optString("hrs")} h · ${rs(num(o.optString("paid")) - num(o.optString("cost")))} net" },
            summary = { l -> l.groupBy { it.optString("client").trim() }.map { (c, e) -> val h = e.sumOf { num(it.optString("hrs")) }
                "$c: ${rs((e.sumOf { num(it.optString("paid")) - num(it.optString("cost")) }) / maxOf(1.0, h))}/hour" } }),
        CalcSpec("payslip", "Shift to payslip check", "Hours worked vs what you were paid", Icons.Outlined.Schedule, Hub.Work, "payslip overtime hours shift wage check",
            listOf(F("hours", "Regular hours", K.NUMBER, default = "176"), F("ot", "Overtime hours", K.NUMBER, default = "10"), F("rate", "Hourly rate", K.NUMBER, default = "800"),
                F("otx", "Overtime multiplier", K.NUMBER, default = "2"), F("paid", "Amount on payslip", K.NUMBER, default = "150000")),
            listOf("Should be" to "hours*rate + ot*rate*otx", "Difference" to "paid - (hours*rate + ot*rate*otx)"), "A negative difference means you may be underpaid; check deductions first."),
        LogSpec("estimates", "Estimate calibration", "How long you thought vs how long it took", Icons.Outlined.Timer, Hub.Work, "estimate planning fallacy time tracking accuracy",
            listOf(F("task", "Task"), F("est", "Estimated hours", K.NUMBER), F("act", "Actual hours", K.NUMBER, optional = true)),
            line = { o -> o.optString("task") to "est ${o.optString("est")}h${if (o.optString("act").isNotBlank()) " · took ${o.optString("act")}h" else ""}" },
            summary = { l -> val done = l.filter { it.optString("act").isNotBlank() && num(it.optString("est")) > 0 }
                if (done.isEmpty()) listOf("Finish a few tasks to see your multiplier")
                else listOf("Your tasks take ${"%.1f".format(done.sumOf { num(it.optString("act")) } / done.sumOf { num(it.optString("est")) })}× your estimate. Multiply future estimates by that.") }),
        AiSpec("valuesmatrix", "Values decision matrix", "Score options against what matters to you", Icons.Outlined.GridOn, Hub.Memory, "decision matrix values weighted options",
            listOf(F("options", "Options", K.LONG, hint = "Job in Dubai\nStay in Lahore\nStart business"), F("values", "What matters, with weight 1-5", K.LONG, hint = "Family 5\nMoney 4\nDeen 5\nGrowth 3")),
            "Build a weighted decision matrix: score each option 1-10 per value with a one-line reason, compute weighted totals, show the table, and say what would flip the result.") { v ->
            "Options:\n${v["options"]}\nValues:\n${v["values"]}" },
        LogSpec("experiments", "Personal experiments", "Try a change for two weeks and measure it", Icons.Outlined.Science, Hub.Memory, "experiment self test habit measure",
            listOf(F("change", "The change", hint = "No phone after Isha"), F("measure", "What you measure", hint = "sleep score"), F("phase", "Phase", K.CHOICE, options = listOf("Before", "During"), default = "During"),
                F("val", "Value today", K.NUMBER), F("d", "Date", K.DATE, default = today())),
            line = { o -> "${o.optString("change")} · ${o.optString("phase")}" to "${o.optString("measure")} ${o.optString("val")} · ${o.optString("d")}" },
            summary = { l -> l.groupBy { it.optString("change").trim() }.map { (c, e) ->
                val b = e.filter { it.optString("phase") == "Before" }.map { num(it.optString("val")) }; val du = e.filter { it.optString("phase") == "During" }.map { num(it.optString("val")) }
                if (b.isEmpty() || du.isEmpty()) "$c: log both phases" else "$c: ${"%.1f".format(b.average())} → ${"%.1f".format(du.average())}" } }),
        AiSpec("lapse", "Habit lapse debrief", "You slipped. What happened, and the restart plan", Icons.Outlined.Replay, Hub.Memory, "habit lapse missed relapse restart",
            listOf(F("habit", "Habit"), F("what", "What happened", K.LONG)),
            "You run a kind, practical lapse debrief: the trigger chain, what was in your control, one environment change, an 'if-then' plan, and a restart for today. No guilt.") { v ->
            "Habit: ${v["habit"]}\nWhat happened: ${v["what"]}" },
    )

    private val home = listOf(
        LogSpec("meters", "Utility meters", "Electricity, gas and water readings over time", Icons.Outlined.Speed, Hub.Home, "meter reading electricity gas water units",
            listOf(F("meter", "Meter", K.CHOICE, options = listOf("Electricity", "Gas", "Water"), default = "Electricity"), F("read", "Reading", K.NUMBER), F("d", "Date", K.DATE, default = today())),
            line = { o -> "${o.optString("meter")} · ${o.optString("read")}" to o.optString("d") },
            summary = { l -> l.groupBy { it.optString("meter") }.mapNotNull { (m, e) -> val s = e.sortedBy { it.optString("d") }
                if (s.size < 2) null else { val days = maxOf(1L, ChronoUnit.DAYS.between(d(s.first(), "d"), d(s.last(), "d")))
                    "$m: ${"%.1f".format((num(s.last().optString("read")) - num(s.first().optString("read"))) / days)} units/day" } } }),
        CalcSpec("odometer", "Service forecast", "When your car or bike needs its next service", Icons.Outlined.CarRepair, Hub.Home, "odometer service oil change km car bike",
            listOf(F("now", "Odometer now (km)", K.NUMBER, default = "48200"), F("last", "At last service (km)", K.NUMBER, default = "45000"), F("every", "Service every (km)", K.NUMBER, default = "5000"),
                F("perday", "Km per day", K.NUMBER, default = "40")),
            listOf("Km until service" to "every - (now - last)", "Days until service" to "(every - (now - last)) / perday"), "Oil changes for most cars in Pakistan: 3,000–5,000 km (mineral) or up to 10,000 km (synthetic)."),
        AiSpec("warrantyclaim", "Warranty claim packet", "What to say and send to get a repair or refund", Icons.Outlined.Gavel, Hub.Home, "warranty claim refund repair complaint consumer court",
            listOf(F("item", "Item, shop, purchase date"), F("problem", "Problem", K.LONG), F("have", "What you have", hint = "receipt, warranty card, photos")),
            "You write effective warranty claims for Pakistan: the claim message, documents checklist, escalation steps (brand service centre, then consumer court/PCP), and timelines.") { v ->
            "Item: ${v["item"]}\nProblem: ${v["problem"]}\nI have: ${v["have"]}" },
        LogSpec("rooms", "Room inventory", "What's in each room, cupboard and box", Icons.Outlined.Inventory2, Hub.Home, "inventory room storage where is box cupboard",
            listOf(F("item", "Item"), F("room", "Room"), F("spot", "Exact spot", hint = "top shelf, blue box"), F("val", "Value", K.NUMBER, optional = true)),
            line = { o -> o.optString("item") to "${o.optString("room")} · ${o.optString("spot")}" },
            summary = { l -> l.groupBy { it.optString("room").trim() }.map { (r, e) -> "$r: ${e.size} items" } }),
        LogSpec("returns", "Return windows", "Purchases you can still return, and by when", Icons.Outlined.AssignmentReturn, Hub.Home, "return refund exchange window daraz purchase",
            listOf(F("item", "Item"), F("shop", "Shop"), F("by", "Return by", K.DATE), F("st", "Status", K.CHOICE, options = listOf("Keeping", "Returning", "Returned"), default = "Keeping")),
            line = { o -> o.optString("item") to "${o.optString("shop")} · ${o.optString("st")} · by ${o.optString("by")}" },
            summary = { l -> l.filter { it.optString("st") != "Returned" && (daysTo(it, "by") ?: -1) in 0..3 }.map { "Decide soon: ${it.optString("item")} (${daysTo(it, "by")} days)" }
                .ifEmpty { listOf("Nothing closing in the next 3 days") } }),
        LogSpec("lent", "Lent things", "Books and items you lent, and when they're due back", Icons.Outlined.SwapHoriz, Hub.Home, "lent borrowed item book returned",
            listOf(F("item", "Item"), F("who", "Lent to / from"), F("dir", "", K.CHOICE, options = listOf("I lent it", "I borrowed it"), default = "I lent it"),
                F("back", "Due back", K.DATE, optional = true), F("st", "Status", K.CHOICE, options = listOf("Out", "Returned"), default = "Out")),
            line = { o -> o.optString("item") to "${o.optString("dir")} · ${o.optString("who")} · ${o.optString("st")}${if (o.optString("back").isNotBlank()) " · due ${o.optString("back")}" else ""}" },
            summary = { l -> listOf("Out: ${l.count { it.optString("st") == "Out" && it.optString("dir") == "I lent it" }} · you're holding ${l.count { it.optString("st") == "Out" && it.optString("dir") == "I borrowed it" }}") }),
        LogSpec("gifts", "Gift runway", "Occasion → idea → bought → given, with lead time", Icons.Outlined.CardGiftcard, Hub.Social, "gift occasion birthday eid wedding idea bought",
            listOf(F("who", "For"), F("occ", "Occasion"), F("when", "Date", K.DATE), F("idea", "Idea", optional = true),
                F("st", "Stage", K.CHOICE, options = listOf("Idea", "Chosen", "Bought", "Given"), default = "Idea"), F("amt", "Spent", K.NUMBER, optional = true)),
            line = { o -> "${o.optString("who")} · ${o.optString("occ")}" to "${o.optString("st")} · ${o.optString("when")} ${o.optString("idea")}" },
            summary = { l -> l.filter { it.optString("st") in setOf("Idea", "Chosen") && (daysTo(it, "when") ?: -1) in 0..10 }.map { "Get it soon: ${it.optString("who")} (${daysTo(it, "when")} days)" }
                .ifEmpty { listOf("Nothing urgent") } }),
        LogSpec("pets", "Pet care", "Food, vet, vaccines and grooming for each pet", Icons.Outlined.Pets, Hub.Home, "pet cat dog vet vaccine grooming food",
            listOf(F("pet", "Pet"), F("kind", "Entry", K.CHOICE, options = listOf("Vet visit", "Vaccine", "Deworming", "Grooming", "Food bought", "Weight"), default = "Vet visit"),
                F("note", "Details"), F("next", "Next due", K.DATE, optional = true)),
            line = { o -> "${o.optString("pet")} · ${o.optString("kind")}" to "${o.optString("note")}${if (o.optString("next").isNotBlank()) " · next ${o.optString("next")}" else ""}" }),
        LogSpec("carehandoff", "Care handoff board", "Who's looking after an elder or patient, and notes between shifts", Icons.Outlined.Groups, Hub.Home, "care elder patient handoff family shifts notes",
            listOf(F("for", "Caring for"), F("by", "Carer"), F("note", "Handoff note", K.LONG), F("d", "Date", K.DATE, default = today())),
            line = { o -> "${o.optString("by")} → ${o.optString("for")}" to "${o.optString("d")} · ${o.optString("note").take(70)}" }),
    )

    private val travel = listOf(
        LogSpec("tripwallet", "Trip wallet", "Tickets, bookings, numbers and addresses, offline", Icons.Outlined.AccountBalanceWallet, Hub.Travel, "trip wallet tickets booking pnr hotel offline",
            listOf(F("trip", "Trip"), F("kind", "Item", K.CHOICE, options = listOf("Flight", "Bus/Train", "Hotel", "Visa", "Insurance", "Contact", "Address"), default = "Flight"),
                F("ref", "Reference / PNR / number"), F("when", "When", K.DATE, optional = true), F("note", "Notes", optional = true)),
            line = { o -> "${o.optString("kind")} · ${o.optString("ref")}" to "${o.optString("trip")} ${o.optString("when")} ${o.optString("note")}" }),
        AiSpec("tripready", "Trip readiness check", "Before you leave: what's missing", Icons.Outlined.FactCheck, Hub.Travel, "trip ready checklist documents before travel",
            listOf(F("trip", "Trip (where, when, who)"), F("have", "What's already sorted", K.LONG)),
            "You run a pre-departure readiness review: documents and expiry, bookings, money and cards, health, home (gas off, keys, plants), packing gaps. Output a short checklist of what's still missing.") { v ->
            "Trip: ${v["trip"]}\nSorted: ${v["have"]}" },
    )
}
