package com.nizam.app.feature.nx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.nizam.app.ui.design.Hub
import org.json.JSONObject
import java.time.LocalDate

/**
 * Mega batch 1: money, health, learning, mind, people, home, work, travel and making, built on the
 * spec engine so each one is a complete, saved, shareable tool.
 */
object Mega {
    private val LANG = listOf("English", "Urdu", "Roman Urdu")
    private fun today() = LocalDay.today().toString()
    private fun d(o: JSONObject, k: String) = runCatching { LocalDate.parse(o.optString(k)) }.getOrNull()

    val all: List<Spec> by lazy { money + health + learn + mind + people + home + work + travel + make }

    // ───────────────────────── money ─────────────────────────
    private val money = listOf(
        CalcSpec("impulse", "Impulse check", "What a want really costs you in hours of work", Icons.Outlined.ShoppingCartCheckout, Hub.Money, "impulse buy cooldown wishlist hours work",
            listOf(F("price", "Price", K.NUMBER, default = "15000"), F("salary", "Monthly take-home", K.NUMBER, default = "150000"), F("hours", "Work hours a month", K.NUMBER, default = "176"),
                F("save", "You save a month", K.NUMBER, default = "20000")),
            listOf("Your hourly earning" to "salary / hours", "Hours of work it costs" to "price / (salary / hours)", "Days of work" to "price / (salary / hours) / 8",
                "Share of a month's saving" to "price / save * 100"),
            "If it's still worth those hours after 24 hours, buy it. Add it to Wishlist cooldown to sleep on it."),
        LogSpec("wishcool", "Wishlist cooldown", "Want something? Wait 24 hours, then decide", Icons.Outlined.HourglassTop, Hub.Money, "wishlist cooldown impulse wait 24 hours",
            listOf(F("item", "What you want"), F("price", "Price", K.NUMBER), F("added", "Added", K.DATE, default = today()), F("st", "Decision", K.CHOICE, options = listOf("Waiting", "Bought", "Skipped"), default = "Waiting")),
            line = { o -> o.optString("item") to "${rs(num(o.optString("price")))} · ${o.optString("st")} · ${o.optString("added")}" },
            summary = { l -> listOf("Saved by skipping: ${rs(l.filter { it.optString("st") == "Skipped" }.sumOf { num(it.optString("price")) })}",
                "Waiting: ${l.count { it.optString("st") == "Waiting" }} · ready to decide: ${l.count { it.optString("st") == "Waiting" && (d(it, "added")?.isBefore(LocalDate.now()) == true) }}") }),
        AiSpec("snowball", "Debt payoff plan", "Snowball or avalanche: the fastest way out of debt", Icons.Outlined.TrendingDown, Hub.Money, "debt payoff snowball avalanche loan credit card plan",
            listOf(F("debts", "Your debts (name, balance, interest %, minimum)", K.LONG, hint = "Credit card, 120000, 36%, 6000\nBike loan, 80000, 18%, 7000"),
                F("extra", "Extra you can pay each month", K.NUMBER, default = "5000"), F("way", "Method", K.CHOICE, options = listOf("Avalanche (least interest)", "Snowball (quick wins)", "Compare both"), default = "Compare both")),
            "You are a careful personal-finance planner. Do the month-by-month maths correctly. Show the order of payoff, months to debt-free and total interest. Use PKR.") { v ->
            "Debts:\n${v["debts"]}\nExtra per month: ${v["extra"]}\nMethod: ${v["way"]}\nGive a payoff schedule summary table and clear next steps." },
        AiSpec("halalscreen", "Halal stock check", "Is a stock or fund Shariah-compliant, and why", Icons.Outlined.Verified, Hub.Money, "halal stock shariah compliant psx fund screening",
            listOf(F("name", "Company or fund", hint = "e.g. Systems Ltd, Meezan Islamic Fund, Apple")),
            "You explain Shariah screening (business activity, interest-bearing debt, interest income, cash and receivables ratios) using current, cited data. Say clearly when data is uncertain and that a qualified scholar or certified index has the final word.",
            web = true) { v -> "Screen ${v["name"]} for Shariah compliance with current figures and sources." },
        AiSpec("filer", "Tax return helper", "FBR filer checklist and what to declare", Icons.Outlined.RequestQuote, Hub.Money, "fbr filer tax return iris wealth statement atl",
            listOf(F("who", "You are", K.CHOICE, options = listOf("Salaried", "Freelancer", "Business", "Landlord", "Overseas Pakistani"), default = "Salaried"),
                F("details", "Your income and assets (rough)", K.LONG, optional = true)),
            "You help Pakistanis file their income tax return on FBR IRIS. Give an up-to-date step-by-step checklist, documents needed, wealth statement tips and deadlines, citing FBR sources. Not a substitute for a tax adviser.",
            web = true) { v -> "I'm ${v["who"]}. ${v["details"]}\nGive me my filing checklist for this tax year." },
        LogSpec("familybudget", "Family budget", "Who in the family spent what, against a shared budget", Icons.Outlined.FamilyRestroom, Hub.Money, "family budget shared spouse parents household",
            listOf(F("who", "Who"), F("cat", "Category", K.CHOICE, options = listOf("Groceries", "Bills", "School", "Health", "Transport", "Eating out", "Gifts", "Other"), default = "Groceries"),
                F("amt", "Amount", K.NUMBER), F("d", "Date", K.DATE, default = today())),
            line = { o -> "${o.optString("cat")} · ${rs(num(o.optString("amt")))}" to "${o.optString("who")} · ${o.optString("d")}" },
            summary = { l -> val m = l.filter { it.optString("d").startsWith(today().take(7)) }
                listOf("This month: ${rs(m.sumOf { num(it.optString("amt")) })}") + m.groupBy { it.optString("who").trim() }.map { (w, e) -> "$w: ${rs(e.sumOf { num(it.optString("amt")) })}" } }),
        CalcSpec("zakatquick", "Zakat on gold & cash", "Quick zakat with today's nisab", Icons.Outlined.Balance, Hub.Money, "zakat nisab gold silver cash 2.5%",
            listOf(F("cash", "Cash and bank", K.NUMBER, default = "0"), F("goldg", "Gold (grams)", K.NUMBER, default = "0"), F("goldrate", "Gold rate per gram", K.NUMBER, default = "28000"),
                F("silverrate", "Silver rate per gram", K.NUMBER, default = "330"), F("owed", "Debts due now", K.NUMBER, default = "0")),
            listOf("Zakatable wealth" to "cash + goldg*goldrate - owed", "Nisab (612.36 g silver)" to "612.36 * silverrate",
                "Zakat due (2.5%)" to "if(gt(cash + goldg*goldrate - owed, 612.36*silverrate), (cash + goldg*goldrate - owed) * 0.025, 0)"),
            "Uses the silver nisab, as many scholars advise for Pakistan. Update today's rates. The Zakat tab in Deen keeps your full yearly record."),
    )

    // ───────────────────────── health ─────────────────────────
    private val health = listOf(
        LogSpec("meds", "Medicines", "Doses, timings, stock and refills", Icons.Outlined.Medication, Hub.Health, "medicine dose pills tablet refill reminder",
            listOf(F("name", "Medicine"), F("dose", "Dose", hint = "500 mg"), F("when", "When", K.CHOICE, options = listOf("Morning", "Noon", "Evening", "Night", "Morning & night", "Three times", "As needed"), default = "Morning"),
                F("stock", "Tablets left", K.NUMBER, optional = true), F("perday", "Per day", K.NUMBER, default = "1"), F("for", "For whom", optional = true)),
            line = { o -> "${o.optString("name")} ${o.optString("dose")}" to "${o.optString("when")}${if (o.optString("for").isNotBlank()) " · ${o.optString("for")}" else ""}${
                if (o.optString("stock").isNotBlank()) " · ${(num(o.optString("stock")) / maxOf(1.0, num(o.optString("perday")))).toInt()} days left" else ""}" },
            summary = { l -> l.filter { it.optString("stock").isNotBlank() && num(it.optString("stock")) / maxOf(1.0, num(it.optString("perday"))) <= 5 }
                .map { "Refill soon: ${it.optString("name")}" }.ifEmpty { listOf("${l.size} medicines tracked") } }),
        LogSpec("symptoms", "Symptom diary", "What you felt, when, how bad: ready for the doctor", Icons.Outlined.Sick, Hub.Health, "symptom diary pain fever headache doctor",
            listOf(F("what", "Symptom"), F("sev", "Severity 1-10", K.NUMBER, default = "5"), F("d", "Date", K.DATE, default = today()), F("note", "Triggers / notes", K.LONG, optional = true)),
            line = { o -> "${o.optString("what")} · ${o.optString("sev")}/10" to "${o.optString("d")} ${o.optString("note")}" },
            summary = { l -> l.groupBy { it.optString("what").trim().lowercase() }.entries.sortedByDescending { it.value.size }.take(4)
                .map { (k, v) -> "${k.replaceFirstChar { it.uppercase() }}: ${v.size} times, avg ${"%.1f".format(v.map { num(it.optString("sev")) }.average())}/10" } }),
        AiSpec("labreport", "Lab report explainer", "Paste your results; get them in plain words", Icons.Outlined.Science, Hub.Health, "lab report blood test cbc lft hba1c explain",
            listOf(F("report", "Results (paste or type)", K.LONG, hint = "Hb 11.2 g/dL (13-17)\nHbA1c 6.1 %\nTSH 5.8 mIU/L"), F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You explain lab results in plain language: what each test measures, whether it's in range, what might cause it, and questions to ask the doctor. Never diagnose; flag anything urgent clearly.") { v ->
            "Explain these results in ${v["lang"]}:\n${v["report"]}" },
        AiSpec("fastplan", "Fasting health plan", "Sehri, iftar, water and energy for long fasts", Icons.Outlined.NightsStay, Hub.Health, "ramadan sehri iftar fasting diet hydration",
            listOf(F("goal", "Goal", K.CHOICE, options = listOf("Stay energetic", "Lose weight", "Diabetic-friendly", "Build muscle", "Exam season"), default = "Stay energetic"),
                F("work", "Your day", hint = "office 9-5, gym after taraweeh"), F("like", "Foods you like", optional = true)),
            "You are a dietitian who knows Pakistani food. Practical sehri and iftar menus, hydration schedule between iftar and sehri, and what to avoid. Medical conditions: advise checking with a doctor.") { v ->
            "Goal: ${v["goal"]}. Day: ${v["work"]}. Likes: ${v["like"]}. Give a 7-day plan." },
        LogSpec("kidsgrowth", "Kids' growth", "Height, weight, vaccines and milestones", Icons.Outlined.ChildCare, Hub.Health, "child baby growth vaccine epi milestone height weight",
            listOf(F("child", "Child"), F("kind", "Entry", K.CHOICE, options = listOf("Measurement", "Vaccine", "Milestone", "Doctor visit"), default = "Measurement"),
                F("h", "Height (cm)", K.NUMBER, optional = true), F("w", "Weight (kg)", K.NUMBER, optional = true), F("note", "Details", optional = true), F("d", "Date", K.DATE, default = today())),
            line = { o -> "${o.optString("child")} · ${o.optString("kind")}" to listOfNotNull(o.optString("h").takeIf { it.isNotBlank() }?.let { "$it cm" },
                o.optString("w").takeIf { it.isNotBlank() }?.let { "$it kg" }, o.optString("note").ifBlank { null }, o.optString("d")).joinToString(" · ") }),
        LogSpec("checkin", "Mind check-in", "A gentle weekly look at how you're really doing", Icons.Outlined.SelfImprovement, Hub.Health, "mental health mood anxiety stress check in wellbeing",
            listOf(F("mood", "Mood 1-10", K.NUMBER, default = "6"), F("sleep", "Sleep quality 1-10", K.NUMBER, default = "6"), F("stress", "Stress 1-10", K.NUMBER, default = "5"),
                F("note", "What's on your mind", K.LONG, optional = true), F("d", "Date", K.DATE, default = today())),
            line = { o -> "Mood ${o.optString("mood")} · stress ${o.optString("stress")}" to "${o.optString("d")} ${o.optString("note").take(60)}" },
            summary = { l -> val r = l.takeLast(4); val low = r.isNotEmpty() && r.all { num(it.optString("mood")) <= 3 }
                listOfNotNull("Recent mood ${"%.1f".format(r.map { num(it.optString("mood")) }.average().takeIf { !it.isNaN() } ?: 0.0)}/10",
                    if (low) "You've felt low for a while. Talking helps: Umang helpline 0311-7786264, or someone you trust." else null) }),
        CalcSpec("bmr", "Calories & water", "Daily calories, protein and water for your body", Icons.Outlined.LocalDrink, Hub.Health, "bmr tdee calories protein water intake",
            listOf(F("w", "Weight (kg)", K.NUMBER, default = "75"), F("h", "Height (cm)", K.NUMBER, default = "175"), F("age", "Age", K.NUMBER, default = "28"),
                F("male", "Male = 1, female = 0", K.NUMBER, default = "1"), F("act", "Activity 1.2 (desk) to 1.9 (very active)", K.NUMBER, default = "1.4")),
            listOf("Resting calories (BMR)" to "10*w + 6.25*h - 5*age + if(gt(male,0.5), 5, -161)", "Daily calories" to "(10*w + 6.25*h - 5*age + if(gt(male,0.5), 5, -161)) * act",
                "To lose 0.5 kg a week" to "(10*w + 6.25*h - 5*age + if(gt(male,0.5), 5, -161)) * act - 500", "Protein (g)" to "w * 1.6", "Water (litres)" to "w * 0.035"),
            "Mifflin-St Jeor estimate. Add water on hot days and when fasting."),
    )

    // ───────────────────────── learn ─────────────────────────
    private val learn = listOf(
        AiSpec("examplan", "Exam mode", "Syllabus → day-by-day plan → daily quizzes", Icons.Outlined.EventNote, Hub.Learn, "exam study plan syllabus revision schedule",
            listOf(F("exam", "Exam and subjects"), F("date", "Exam date", K.DATE), F("syllabus", "Syllabus / chapters", K.LONG), F("hours", "Hours a day", K.NUMBER, default = "4")),
            "You are a top exam coach. Make a realistic day-by-day plan to the exam date with revision rounds, mock tests and rest. Then give today's 10-question quiz.") { v ->
            "Exam: ${v["exam"]} on ${v["date"]} (today is ${today()}). ${v["hours"]} hours a day.\nSyllabus:\n${v["syllabus"]}" },
        ChatSpec("boardtutor", "Board tutor", "Matric, FSc, O and A level help, by the board's syllabus", Icons.Outlined.School, Hub.Learn, "matric fsc o level a level tutor physics chemistry maths biology",
            setup = listOf(F("level", "Level", K.CHOICE, options = listOf("Matric (9-10)", "FSc / ICS (11-12)", "O Level", "A Level", "Entry test (MDCAT/ECAT)"), default = "Matric (9-10)"),
                F("subject", "Subject", hint = "Physics"), F("lang", "Explain in", K.CHOICE, options = LANG, default = "English")),
            opener = "Which chapter or question shall we do?") { v ->
            "You are a patient ${v["subject"]} tutor for ${v["level"]} students in Pakistan, following the board's syllabus and exam style. Explain in ${v["lang"]} step by step, check understanding with a short question, and show how marks are given." },
        ChatSpec("englishcoach", "English speaking coach", "Conversation practice with gentle corrections", Icons.Outlined.RecordVoiceOver, Hub.Learn, "english speaking spoken conversation ielts fluency grammar",
            setup = listOf(F("level", "Your level", K.CHOICE, options = listOf("Beginner", "Intermediate", "Advanced", "IELTS prep"), default = "Intermediate"),
                F("topic", "Talk about", hint = "job interview, travel, daily life", optional = true)),
            opener = "Hi! Tell me about your day — in English, as much as you can.") { v ->
            "You are a friendly English conversation partner for a ${v["level"]} learner from Pakistan. Keep the conversation going about ${v["topic"]}. After each reply, add a short 'Better:' line correcting mistakes and one new useful phrase." },
        AiSpec("quranwords", "Quranic Arabic", "Learn the words that make up most of the Quran", Icons.Outlined.Translate, Hub.Learn, "arabic quran words vocabulary understand quran 80%",
            listOf(F("lesson", "Lesson", K.CHOICE, options = (1..30).map { "Lesson $it" }, default = "Lesson 1"), F("lang", "Explain in", K.CHOICE, options = listOf("English", "Urdu"), default = "Urdu")),
            "You teach the most frequent Quranic words in order of frequency, 10 words per lesson, with root, meaning, an ayah where it appears (with reference) and a quick quiz. Be accurate; don't invent ayahs.") { v ->
            "${v["lesson"]}: teach the next 10 most frequent Quranic words (continue the frequency order across lessons) in ${v["lang"]}." },
        ChatSpec("codementor", "Coding mentor", "Learn to code by building real things", Icons.Outlined.Code, Hub.Learn, "learn coding programming python javascript kotlin mentor",
            setup = listOf(F("lang", "Language", K.CHOICE, options = listOf("Python", "JavaScript", "Kotlin", "HTML & CSS", "SQL"), default = "Python"),
                F("goal", "What you want to build", hint = "a budget app, a website, automate Excel")),
            opener = "Let's build it together. What do you already know?") { v ->
            "You are a coding mentor teaching ${v["lang"]} by building: ${v["goal"]}. Small steps, one concept at a time, give exercises and review the learner's code kindly." },
        AiSpec("sprint", "Skill sprint", "A 14-day micro-course in any skill", Icons.Outlined.Bolt, Hub.Learn, "learn skill course 14 days micro course plan",
            listOf(F("skill", "Skill", hint = "public speaking, Excel, Urdu calligraphy"), F("mins", "Minutes a day", K.NUMBER, default = "15")),
            "You design tight, practical micro-courses. 14 days, each with a lesson, an exercise and a self-check. Day 14 is a small project.") { v ->
            "A 14-day course in ${v["skill"]} at ${v["mins"]} minutes a day." },
        ChatSpec("debatedojo", "Debate dojo", "Argue a motion against a sharp opponent and get scored", Icons.Outlined.Gavel, Hub.Learn, "debate argue practice motion rebuttal",
            setup = listOf(F("motion", "Motion", hint = "Social media does more harm than good"), F("side", "Your side", K.CHOICE, options = listOf("For", "Against"), default = "For")),
            opener = "Give your opening argument.") { v ->
            "You are a skilled debater arguing ${if (v["side"] == "For") "AGAINST" else "FOR"} the motion: ${v["motion"]}. Rebut the user's points sharply but fairly, one round at a time. After every round score them out of 10 on logic, evidence and delivery with one tip." },
        AiSpec("documentary", "Documentary", "Any topic as a narrated 10-minute documentary script", Icons.Outlined.Movie, Hub.Learn, "documentary explain history science topic narrated",
            listOf(F("topic", "Topic"), F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You write gripping, accurate documentary narration with scenes, facts and a strong ending. Mark [SCENE] changes.", web = true, tokens = 2400) { v ->
            "A 10-minute documentary on ${v["topic"]} in ${v["lang"]}. (Tip: order it from Deliverables as Audio to hear it narrated.)" },
    )

    // ───────────────────────── mind ─────────────────────────
    private val mind = listOf(
        LogSpec("decisions", "Decision journal", "Write the decision and why; review it later", Icons.Outlined.AltRoute, Hub.Memory, "decision journal review outcome",
            listOf(F("what", "Decision"), F("why", "Why, and what you expect", K.LONG), F("conf", "Confidence %", K.NUMBER, default = "70"),
                F("review", "Review on", K.DATE, default = LocalDate.now().plusMonths(3).toString()), F("outcome", "What actually happened", K.LONG, optional = true)),
            line = { o -> o.optString("what") to "${o.optString("conf")}% sure · review ${o.optString("review")}${if (o.optString("outcome").isNotBlank()) " · reviewed" else ""}" },
            summary = { l -> listOf("Due for review: ${l.count { it.optString("outcome").isBlank() && d(it, "review")?.isAfter(LocalDate.now()) == false }}") }),
        LogSpec("beliefs", "Belief tracker", "What you believe will happen, and whether it did", Icons.Outlined.Lightbulb, Hub.Memory, "prediction belief forecast calibration",
            listOf(F("claim", "I believe…"), F("p", "Probability %", K.NUMBER, default = "70"), F("by", "By", K.DATE), F("res", "Result", K.CHOICE, options = listOf("Open", "Came true", "Didn't"), default = "Open")),
            line = { o -> o.optString("claim") to "${o.optString("p")}% · by ${o.optString("by")} · ${o.optString("res")}" },
            summary = { l -> val done = l.filter { it.optString("res") != "Open" }
                if (done.isEmpty()) listOf("Resolve some beliefs to see how well-calibrated you are")
                else listOf("Right ${done.count { (num(it.optString("p")) >= 50) == (it.optString("res") == "Came true") }} of ${done.size} times") }),
        ChatSpec("board", "Board of mentors", "Your own CFO, coach, scholar and doctor on one question", Icons.Outlined.Groups, Hub.Memory, "advisors board mentors advice cfo coach",
            setup = listOf(F("seats", "Who sits on your board", default = "a CFO, a career coach, an Islamic scholar, a doctor, a wise grandmother")),
            opener = "What should the board weigh in on?") { v ->
            "You are a board of advisors: ${v["seats"]}. For each question, each member answers briefly in their own voice and expertise (labelled), then give a combined recommendation. Scholars give general guidance, not fatwas; doctors advise seeing a doctor for anything serious." },
        LogSpec("arc", "Character arc", "Traits you're working on, checked weekly", Icons.Outlined.Psychology, Hub.Memory, "character trait patience honesty self improvement weekly",
            listOf(F("trait", "Trait", hint = "patience"), F("score", "This week 1-10", K.NUMBER, default = "5"), F("moment", "A moment that tested it", K.LONG, optional = true), F("d", "Week of", K.DATE, default = today())),
            line = { o -> "${o.optString("trait")} · ${o.optString("score")}/10" to "${o.optString("d")} ${o.optString("moment").take(60)}" },
            summary = { l -> l.groupBy { it.optString("trait").trim().lowercase() }.map { (t, e) -> val s = e.map { num(it.optString("score")) }
                "${t.replaceFirstChar { it.uppercase() }}: ${s.first().toInt()} → ${s.last().toInt()}" } }),
        LogSpec("gratitude", "Gratitude jar", "One good thing a day, a book by the year's end", Icons.Outlined.VolunteerActivism, Hub.Memory, "gratitude shukr thankful journal jar",
            listOf(F("what", "Today I'm grateful for", K.LONG), F("d", "Date", K.DATE, default = today())),
            line = { o -> o.optString("what").take(80) to o.optString("d") },
            summary = { l -> listOf("${l.size} blessings in the jar · ${l.count { it.optString("d").startsWith(today().take(4)) }} this year") }),
        AiSpec("habitchain", "Habit chain", "Attach a new habit to one you already have", Icons.Outlined.Link, Hub.Memory, "habit stacking chain routine tiny habits",
            listOf(F("want", "New habit"), F("day", "Your usual day", K.LONG, hint = "Fajr, gym, office, tea at 5, Isha, phone in bed")),
            "You design habit stacks: anchor the new habit right after an existing one, make it tiny (2 minutes), add a cue and a reward, and plan for bad days.") { v ->
            "New habit: ${v["want"]}\nMy day: ${v["day"]}" },
        AiSpec("unstuck", "Get unstuck", "Turn a task you're avoiding into 2-minute starts", Icons.Outlined.PlayCircle, Hub.Memory, "procrastination avoid start task motivation",
            listOf(F("task", "What you're avoiding"), F("why", "Why it feels hard", optional = true)),
            "You beat procrastination. Break the task into absurdly small first steps (the first under 2 minutes), name the real block, and give a 25-minute plan. Warm, no lecturing.") { v ->
            "Task: ${v["task"]}. Why it's hard: ${v["why"]}" },
        AiSpec("parallel", "Parallel lives", "Simulate two choices five years forward", Icons.Outlined.CallSplit, Hub.Memory, "what if choice simulate future job city",
            listOf(F("a", "Path A"), F("b", "Path B"), F("you", "About you (age, money, family, goals)", K.LONG)),
            "You simulate realistic futures. For each path give years 1, 3 and 5: money, career, family, health and regrets, with the key uncertainties. Then what would tip the decision.") { v ->
            "Path A: ${v["a"]}\nPath B: ${v["b"]}\nMe: ${v["you"]}" },
    )

    // ───────────────────────── people ─────────────────────────
    private val people = listOf(
        AiSpec("replycoach", "Reply coach", "Draft a reply in your style, and check the tone", Icons.Outlined.Reply, Hub.Social, "reply message whatsapp tone check harsh",
            listOf(F("msg", "Message you got", K.LONG), F("want", "What you want to say", K.LONG, optional = true), F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You help people reply well: kind, clear, never passive-aggressive. Give two replies (short and fuller) and point out if the user's draft sounds harsh.") { v ->
            "They wrote:\n${v["msg"]}\nI want to say: ${v["want"]}\nReply in ${v["lang"]}." },
        AiSpec("condolence", "Condolence helper", "The right words, duas and help when someone passes", Icons.Outlined.Favorite, Hub.Social, "condolence death janaza taziyat grief",
            listOf(F("who", "Who passed and your relation to the family"), F("lang", "Language", K.CHOICE, options = LANG, default = "Urdu")),
            "You help someone offer condolences in a Pakistani Muslim context: a sincere message, the authentic duas (Inna lillahi..., dua for the deceased), and practical ways to help the family. Gentle and brief.") { v ->
            "${v["who"]}. Write in ${v["lang"]}." },
        LogSpec("familytree", "Family tree", "Relatives, how they're related, birthdays and stories", Icons.Outlined.AccountTree, Hub.Social, "family tree relatives khandan shajra",
            listOf(F("name", "Name"), F("rel", "Relation to you", hint = "Dada, Phuppo, cousin"), F("parent", "Child of", optional = true), F("born", "Born", K.DATE, optional = true),
                F("story", "A story or detail", K.LONG, optional = true)),
            line = { o -> o.optString("name") to "${o.optString("rel")}${if (o.optString("parent").isNotBlank()) " · child of ${o.optString("parent")}" else ""}" },
            summary = { l -> listOf("${l.size} relatives · ${l.groupBy { it.optString("parent").trim() }.size} families") }),
        LogSpec("wedding", "Wedding planner", "Guests, events, vendors and budget for shaadi", Icons.Outlined.Celebration, Hub.Social, "wedding shaadi mehndi barat walima guests vendors budget",
            listOf(F("kind", "Entry", K.CHOICE, options = listOf("Guest", "Vendor", "Expense", "Task"), default = "Guest"), F("name", "Name / item"),
                F("event", "Event", K.CHOICE, options = listOf("Mayun", "Mehndi", "Barat", "Walima", "Nikah", "All"), default = "All"),
                F("amt", "Amount or headcount", K.NUMBER, optional = true), F("st", "Status", K.CHOICE, options = listOf("Pending", "Confirmed", "Paid", "Done"), default = "Pending")),
            line = { o -> "${o.optString("kind")}: ${o.optString("name")}" to "${o.optString("event")} · ${o.optString("st")}${if (o.optString("amt").isNotBlank()) " · ${o.optString("amt")}" else ""}" },
            summary = { l -> listOf("Guests: ${l.filter { it.optString("kind") == "Guest" }.sumOf { maxOf(1.0, num(it.optString("amt"))) }.toInt()}",
                "Spent: ${rs(l.filter { it.optString("kind") == "Expense" || it.optString("kind") == "Vendor" }.sumOf { num(it.optString("amt")) })}",
                "Open tasks: ${l.count { it.optString("kind") == "Task" && it.optString("st") != "Done" }}") }),
        AiSpec("kidsstory", "Bedtime story", "A story starring your child, with a good lesson", Icons.Outlined.AutoStories, Hub.Social, "kids bedtime story children islamic moral",
            listOf(F("child", "Child's name and age"), F("about", "About", hint = "sharing, being brave, a trip to the moon"), F("lang", "Language", K.CHOICE, options = LANG, default = "Urdu")),
            "You write warm, imaginative bedtime stories for Muslim children, 5 minutes long, with the child as the hero and a gentle lesson from Islamic values. Age-appropriate words.") { v ->
            "For ${v["child"]}, about ${v["about"]}, in ${v["lang"]}." },
    )

    // ───────────────────────── home ─────────────────────────
    private val home = listOf(
        LogSpec("docwallet", "Document wallet", "CNIC, passport, licence, degrees: numbers and expiry", Icons.Outlined.Badge, Hub.Home, "cnic passport licence document expiry renew",
            listOf(F("doc", "Document", K.CHOICE, options = listOf("CNIC", "Passport", "Driving licence", "Vehicle registration", "Degree", "Insurance", "Visa", "Other"), default = "CNIC"),
                F("whose", "Whose"), F("no", "Number (last 4 is enough)", optional = true), F("exp", "Expires", K.DATE, optional = true)),
            sealed = true,
            line = { o -> "${o.optString("doc")} · ${o.optString("whose")}" to "${o.optString("no")}${if (o.optString("exp").isNotBlank()) " · expires ${o.optString("exp")}" else ""}" },
            summary = { l -> l.filter { d(it, "exp")?.isBefore(LocalDate.now().plusMonths(3)) == true }.map { "Renew soon: ${it.optString("doc")} (${it.optString("whose")}) · ${it.optString("exp")}" }
                .ifEmpty { listOf("Nothing expires in the next 3 months") } }),
        LogSpec("emergency", "Emergency card", "Blood group, allergies, conditions and contacts", Icons.Outlined.LocalHospital, Hub.Home, "emergency blood group allergy ice contact medical card",
            listOf(F("who", "Person"), F("blood", "Blood group", K.CHOICE, options = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-", "Unknown"), default = "Unknown"),
                F("allergy", "Allergies", optional = true), F("cond", "Conditions & medicines", optional = true), F("ice", "Emergency contact & number")),
            line = { o -> "${o.optString("who")} · ${o.optString("blood")}" to "ICE: ${o.optString("ice")}${if (o.optString("allergy").isNotBlank()) " · allergic: ${o.optString("allergy")}" else ""}" },
            summary = { listOf("Rescue 1122 · Edhi 115 · Police 15 · Fire 16") }),
        LogSpec("services", "Home services", "Trusted plumbers, electricians and more, with your ratings", Icons.Outlined.Handyman, Hub.Home, "plumber electrician carpenter ac technician mistri contact",
            listOf(F("trade", "Trade", K.CHOICE, options = listOf("Plumber", "Electrician", "AC technician", "Carpenter", "Painter", "Mechanic", "Cleaner", "Other"), default = "Plumber"),
                F("name", "Name"), F("phone", "Phone"), F("rate", "Rating 1-5", K.NUMBER, default = "4"), F("note", "Charges / notes", optional = true)),
            line = { o -> "${o.optString("name")} · ${o.optString("trade")}" to "${"★".repeat(num(o.optString("rate")).toInt().coerceIn(0, 5))} · ${o.optString("phone")} ${o.optString("note")}" }),
        AiSpec("moving", "Moving checklist", "Everything to do when changing house or city", Icons.Outlined.LocalShipping, Hub.Home, "moving house shifting city checklist",
            listOf(F("from", "From"), F("to", "To"), F("when", "Moving date", K.DATE), F("who", "Household", hint = "family of 4, cat")),
            "You make complete, practical moving checklists for Pakistan: utilities transfer (LESCO/K-Electric/SNGPL/PTCL), address changes (NADRA, bank), packing, movers, school, and week-by-week timing.") { v ->
            "Moving from ${v["from"]} to ${v["to"]} on ${v["when"]} (today ${today()}). Household: ${v["who"]}." },
        LogSpec("parking", "Where I parked", "Floor, row, landmark: never search again", Icons.Outlined.LocalParking, Hub.Home, "parking car where parked mall",
            listOf(F("where", "Where", hint = "Emporium, P2, near lift B"), F("d", "Date", K.DATE, default = today())),
            line = { o -> o.optString("where") to o.optString("d") }),
    )

    // ───────────────────────── work ─────────────────────────
    private val work = listOf(
        AiSpec("meetprep", "Meeting prep", "Who, history, goals and talking points before you walk in", Icons.Outlined.MeetingRoom, Hub.Work, "meeting prep agenda talking points",
            listOf(F("with", "Meeting with"), F("about", "About"), F("history", "What happened before", K.LONG, optional = true), F("goal", "What you want out of it")),
            "You prepare people for meetings: a 1-page brief with objectives, likely objections and answers, three questions to ask, and the one sentence to remember.") { v ->
            "With ${v["with"]} about ${v["about"]}. History: ${v["history"]}. Goal: ${v["goal"]}." },
        AiSpec("minutes", "Meeting to tasks", "Paste notes; get decisions, owners and deadlines", Icons.Outlined.Checklist, Hub.Work, "meeting minutes notes action items tasks",
            listOf(F("notes", "Meeting notes or transcript", K.LONG)),
            "Turn meeting notes into: summary (3 lines), decisions, action items as a table (task, owner, deadline), open questions, and a follow-up message to send.") { v -> v["notes"].orEmpty() },
        LogSpec("standup", "My daily standup", "Yesterday, today, blockers: 30 seconds a day", Icons.Outlined.ViewAgenda, Hub.Work, "standup daily yesterday today blockers",
            listOf(F("y", "Yesterday I", K.LONG), F("t", "Today I will", K.LONG), F("b", "Blocked by", optional = true), F("d", "Date", K.DATE, default = today())),
            line = { o -> o.optString("t").take(70) to "${o.optString("d")}${if (o.optString("b").isNotBlank()) " · blocked: ${o.optString("b")}" else ""}" },
            summary = { l -> listOf("${l.size} days logged · ${l.count { it.optString("b").isNotBlank() }} with blockers") }),
        ChatSpec("negotiate", "Salary negotiation coach", "Know your number, then practise the conversation", Icons.Outlined.Handshake, Hub.Work, "salary negotiation raise offer hr",
            setup = listOf(F("role", "Role and city"), F("offer", "Their offer / current pay"), F("want", "What you want")),
            web = true, opener = "First, let me size up the market. Then I'll play the HR manager.") { v ->
            "You coach salary negotiation for ${v["role"]}. Current offer ${v["offer"]}, wants ${v["want"]}. First give a realistic market range with sources, the anchor and walk-away numbers, then role-play the HR manager, pushing back realistically, and coach after each turn." },
        AiSpec("sidehustle", "Side-hustle finder", "Income ideas from your skills, with a 30-day plan", Icons.Outlined.Paid, Hub.Work, "side hustle income freelance earn online fiverr upwork",
            listOf(F("skills", "Your skills and interests", K.LONG), F("hours", "Hours a week", K.NUMBER, default = "10"), F("money", "Money you can invest", K.NUMBER, default = "0")),
            "You suggest realistic side incomes for someone in Pakistan (freelancing, local services, online selling), ranked by speed to first rupee, with a 30-day launch plan for the top pick.", web = true) { v ->
            "Skills: ${v["skills"]}\n${v["hours"]} hrs/week, budget ${v["money"]} PKR." },
        AiSpec("startup", "Startup in a box", "Validate an idea: market, model, risks and launch plan", Icons.Outlined.RocketLaunch, Hub.Work, "startup business idea validate market plan pitch",
            listOf(F("idea", "Your idea", K.LONG), F("city", "Where", default = "Pakistan")),
            "You are a seasoned startup advisor. Validate the idea with current data: problem, customers, competitors, pricing, unit economics, risks, and a 90-day launch plan. Be honest if it's weak. (Order a pitch deck from Deliverables when ready.)", web = true, tokens = 2400) { v ->
            "Idea: ${v["idea"]} in ${v["city"]}." },
        AiSpec("brandkit", "Brand kit", "Name, tagline, colours, voice and first posts", Icons.Outlined.Palette, Hub.Create, "brand name logo tagline colours business identity",
            listOf(F("biz", "Business"), F("vibe", "Vibe", hint = "premium, friendly, desi, minimal")),
            "You are a brand strategist. Give 10 name ideas (with domain-friendly picks), 5 taglines, a colour palette with hex codes, typography, brand voice rules, a logo brief for a designer, and 5 launch posts.") { v ->
            "Business: ${v["biz"]}. Vibe: ${v["vibe"]}." },
    )

    // ───────────────────────── travel ─────────────────────────
    private val travel = listOf(
        AiSpec("tripplan", "Trip autopilot", "Itinerary, budget, prayer stops, halal food and packing", Icons.Outlined.Luggage, Hub.Travel, "trip travel plan itinerary hunza murree umrah dubai",
            listOf(F("where", "Where"), F("days", "Days", K.NUMBER, default = "5"), F("who", "Who's going", default = "2 adults"), F("budget", "Budget (PKR)", K.NUMBER, optional = true),
                F("style", "Style", K.CHOICE, options = listOf("Budget", "Comfortable", "Luxury", "Family with kids", "Adventure"), default = "Comfortable")),
            "You plan trips day by day with current prices: transport, stays, food (halal), prayer times and masjids, weather, safety, packing list and total budget in PKR.", web = true, tokens = 2400) { v ->
            "${v["days"]} days in ${v["where"]} for ${v["who"]}, ${v["style"]}, budget ${v["budget"]}." },
        AiSpec("cityguide", "Walking guide", "An audio-style walking tour of any place", Icons.Outlined.DirectionsWalk, Hub.Travel, "walking tour guide city history audio",
            listOf(F("place", "Place", hint = "Walled City Lahore, Old Karachi, Istanbul"), F("mins", "Minutes", K.NUMBER, default = "60")),
            "You are a brilliant local guide. A walking route with stops in order, the story at each stop, hidden gems, where to eat, and where to pray.", web = true) { v ->
            "A ${v["mins"]}-minute walking tour of ${v["place"]}." },
    )

    // ───────────────────────── make ─────────────────────────
    private val make = listOf(
        AiSpec("newspaper", "My newspaper", "Today's front page written for you", Icons.Outlined.Newspaper, Hub.Media, "newspaper daily news personal front page",
            listOf(F("topics", "Your topics", default = "Pakistan, technology, cricket, economy, Islamic world"), F("city", "City", default = "Lahore")),
            "You are an editor making a personal front page from today's real news, with sources: lead story, 5 briefs, local news, one long read, one good-news story, weather.", web = true, tokens = 2200) { v ->
            "Today's front page (${today()}) for ${v["city"]}. Topics: ${v["topics"]}." },
        LogSpec("soundtrack", "Life soundtrack", "The songs and nasheeds tied to your memories", Icons.Outlined.LibraryMusic, Hub.Media, "song memory soundtrack music nasheed",
            listOf(F("song", "Song / nasheed"), F("memory", "The memory", K.LONG), F("when", "When", default = today().take(4))),
            line = { o -> o.optString("song") to "${o.optString("when")} · ${o.optString("memory").take(60)}" }),
        LogSpec("ethicalwill", "Values for my children", "Lessons and values to pass on, written over time", Icons.Outlined.HistoryEdu, Hub.Memory, "ethical will legacy values children advice",
            listOf(F("for", "For", default = "My children"), F("lesson", "The lesson", K.LONG), F("d", "Date", K.DATE, default = today())),
            sealed = true,
            line = { o -> o.optString("lesson").take(80) to "${o.optString("for")} · ${o.optString("d")}" }),
    )
}
