package com.nizam.app.feature.expertise

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.nizam.app.feature.nx.LogSpec
import com.nizam.app.feature.nx.F
import com.nizam.app.feature.nx.K
import com.nizam.app.feature.nx.Roads
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub
import java.time.LocalDate
import java.time.ZoneId

/** Numbers worked out on the phone from Roads, shared by every lens. */
object LifeStats {
    private const val DAY = 86_400_000L
    private val money = Regex("^(\\w+) Rs ([\\d,]+) on (.+?)(?: \\(| on \\d{4}|$)")
    private fun since(days: Int) = LocalDate.now().minusDays(days.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun recent(items: List<Roads.Item>, days: Int) = since(days).let { from -> items.filter { it.ts >= from } }

    fun spent(items: List<Roads.Item>, days: Int): Double = recent(items, days).filter { it.source == "Money" }.sumOf { i ->
        money.find(i.text)?.takeIf { it.groupValues[1] == "expense" }?.groupValues?.get(2)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
    }

    fun earned(items: List<Roads.Item>, days: Int): Double = recent(items, days).filter { it.source == "Money" }.sumOf { i ->
        money.find(i.text)?.takeIf { it.groupValues[1] == "income" }?.groupValues?.get(2)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
    }

    fun topCategory(items: List<Roads.Item>, days: Int): String? = recent(items, days).filter { it.source == "Money" }.mapNotNull { i ->
        money.find(i.text)?.takeIf { it.groupValues[1] == "expense" }?.let { it.groupValues[3].trim() to (it.groupValues[2].replace(",", "").toDoubleOrNull() ?: 0.0) }
    }.groupBy({ it.first }, { it.second }).maxByOrNull { it.value.sum() }?.key

    fun prayerRate(items: List<Roads.Item>, days: Int): Int? {
        val p = recent(items, days).filter { it.source == "Prayer" }.mapNotNull { Regex("(\\d) of 5").find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        return if (p.isEmpty()) null else (p.sum() * 100 / (p.size * 5))
    }

    fun moodAvg(items: List<Roads.Item>, days: Int): Double? {
        val m = recent(items, days).filter { it.source == "Mood check-ins" || it.source == "Diary" }
            .mapNotNull { Regex("mood (\\d+(?:\\.\\d)?)").find(it.text)?.groupValues?.get(1)?.toDoubleOrNull() }
        return if (m.isEmpty()) null else m.average()
    }

    fun tasks(items: List<Roads.Item>): Pair<Int, Int> {
        val t = items.filter { it.source == "Tasks" }
        return t.count { it.text.contains("[open]") } to t.count { it.text.contains("[done]") }
    }

    fun count(items: List<Roads.Item>, source: String, days: Int) = recent(items, days).count { it.source.contains(source, true) }

    fun activeDays(items: List<Roads.Item>, days: Int) = recent(items, days).map { it.ts / DAY }.toSet().size

    fun rs(v: Double) = com.nizam.app.feature.nx.rs(v)

    // ready-made tile sets
    val moneyTiles: (List<Roads.Item>) -> List<Pair<String, String>> = { i ->
        listOfNotNull("Spent · 30 days" to rs(spent(i, 30)), "Earned · 30 days" to rs(earned(i, 30)), topCategory(i, 30)?.let { "Most on" to it })
    }
    val lifeTiles: (List<Roads.Item>) -> List<Pair<String, String>> = { i ->
        listOfNotNull(
            prayerRate(i, 7)?.let { "Prayers · 7 days" to "$it%" },
            moodAvg(i, 7)?.let { "Mood · 7 days" to "%.1f".format(it) },
            "Spent · 7 days" to rs(spent(i, 7)),
            tasks(i).let { (o, d) -> "Tasks open / done" to "$o / $d" },
            "Habit check-ins · 7 days" to "${count(i, "Habits", 7)}",
            "Workouts · 7 days" to "${count(i, "Workouts", 7) + count(i, "Cardio", 7)}",
        )
    }
    val healthTiles: (List<Roads.Item>) -> List<Pair<String, String>> = { i ->
        listOfNotNull(moodAvg(i, 7)?.let { "Mood · 7 days" to "%.1f".format(it) },
            "Workouts · 14 days" to "${count(i, "Workouts", 14) + count(i, "Cardio", 14)}",
            "Food logged · 7 days" to "${count(i, "Food", 7)} days",
            "Habit check-ins · 7 days" to "${count(i, "Habits", 7)}")
    }
    val deenTiles: (List<Roads.Item>) -> List<Pair<String, String>> = { i ->
        listOfNotNull(prayerRate(i, 7)?.let { "Prayers · 7 days" to "$it%" }, prayerRate(i, 30)?.let { "Prayers · 30 days" to "$it%" },
            "Days logged · 30" to "${activeDays(i, 30)}")
    }
    val workTiles: (List<Roads.Item>) -> List<Pair<String, String>> = { i ->
        tasks(i).let { (o, d) -> listOf("Open tasks" to "$o", "Done" to "$d", "Active days · 14" to "${activeDays(i, 14)}") }
    }
    val activityTiles: (List<Roads.Item>) -> List<Pair<String, String>> = { i ->
        listOf("Entries · 7 days" to "${recent(i, 7).size}", "Entries · 30 days" to "${recent(i, 30).size}", "Active days · 30" to "${activeDays(i, 30)}")
    }
}

/**
 * The 100 features the merge made possible. Each reads from at least three parts of Atlas through Roads and
 * writes back only through the approval inbox. 41 are new screens; the rest upgrade a feature Atlas already
 * had, in place: that feature's ✦ dock gains the new power, so nothing appears twice.
 */
object LifeFeatures {
    private val L = LifeStats

    /** New screens, registered in the catalogue. */
    val lenses: List<Lens> = listOf(
        // Life OS and planning
        Lens("lifecompass", "Life Compass", "One weekly reading across money, health, deen, learning and people, naming the biggest lever for next week",
            Icons.Outlined.Explore, Hub.Tools, emptyList(),
            "Give a weekly Life Compass: one line each for Money, Health, Deen, Learning, People and Work (trend arrow ↑ → ↓ and why), " +
                "then 'The biggest lever for next week' — one specific change with the expected effect across hubs.",
            "mind", "Mind", "life wheel weekly reading balance lever", stats = L.lifeTiles),
        Lens("lifeadmin", "Life Admin Queue", "Renewals, bills, documents and deadlines in one queue, each with its next action ready",
            Icons.Outlined.Inbox, Hub.Home, listOf("Document", "Bill", "Warranty", "Insurance", "Expiry", "Subscription", "Tasks", "Rent", "Return"),
            "Build one admin queue ordered by urgency: each line is the item, its date or how overdue, and the exact next action (what to bring, who to call, what to pay).",
            "docexpiry", "Expiry dates", "renewals deadlines paperwork queue admin", stats = L.workTiles),
        Lens("planb", "Plan B", "Every plan gets a fallback, ready before it's needed",
            Icons.Outlined.AltRoute, Hub.Work, listOf("Tasks", "Money", "Prayer", "Weather", "Mood"),
            "For the plan they describe, find the three most likely ways it fails given their real data (money, time, energy, prayers, weather) and give a prepared Plan B for each, plus the early warning sign to watch.",
            "plan", "Planner", "fallback backup risk contingency", input = "Which plan needs a Plan B?"),
        // Money
        Lens("moneystory", "Money Story", "This month's money in plain words: what changed and why",
            Icons.Outlined.AutoStories, Hub.Money, listOf("Money", "Mood", "Diary", "Bill", "Committee", "Subscription"),
            "Tell this month's money story in plain words: what changed against last month, the three reasons why (link to events in their diary, mood or plans when the data shows it), one thing that went well and one to change.",
            "finance", "Money", "monthly spending explained story", stats = L.moneyTiles),
        Lens("moneymilestones", "Money Milestones", "Real money milestones, celebrated by the pet, the town and the sky",
            Icons.Outlined.EmojiEvents, Hub.Money, listOf("Money", "Savings", "Debt", "Goal", "Net worth", "Committee"),
            "List the money milestones they actually reached (savings totals, debts cleared, no-spend streaks, goals) with dates, then the next three milestones with how far away each is.",
            "finance", "Money", "celebrate savings milestone debt free", stats = L.moneyTiles),
        // Health
        Lens("adaptiveworkout", "Adaptive Workouts", "Tomorrow's workout shaped by sleep, soreness, prayer times and weather",
            Icons.Outlined.FitnessCenter, Hub.Health, listOf("Workouts", "Cardio", "Sleep", "Mood", "Prayer", "Energy", "Pain"),
            "Plan tomorrow's workout: choose intensity from recent load, sleep and mood; give the session (exercises, sets x reps or minutes), the best time slot between prayers, and a lighter swap if they wake tired.",
            "gym", "Gym", "training plan intensity recovery", stats = L.healthTiles),
        Lens("habitstack", "Habit Stacking", "New habits tied to anchors you already keep, with the stick rate measured",
            Icons.Outlined.Link, Hub.Health, listOf("Habits", "Prayer", "Workouts", "Tasks"),
            "Find their strongest anchors (prayers kept, habits done most days, gym days). Propose up to three habit stacks 'After X, I will Y' using those anchors, and report the stick rate of existing habits.",
            "habits2", "Habits", "anchor stack after fajr routine", stats = L.healthTiles),
        // Mind, learning and growth
        Lens("skilltree", "Skill Tree", "Every skill you practise in one tree, earning XP from real practice",
            Icons.Outlined.AccountTree, Hub.Learn, listOf("Learn", "Language", "Chess", "Course", "Study", "Workouts", "Reading", "Quran"),
            "Draw a skill tree as an indented list: each skill with a level (1–10) and XP earned from the practice logged, branches for sub-skills, and the next unlock for the three most active skills.",
            "learning", "Learning", "xp level skills progress tree", stats = L.activityTiles),
        Lens("curriculum", "Personal Curriculum", "A 12-month learning path toward any goal, with sessions placed in the planner",
            Icons.Outlined.School, Hub.Learn, listOf("Learn", "Course", "Study", "Tasks", "Reading"),
            "Design a 12-month curriculum for the goal: quarters, monthly themes, weekly session count that fits the free time their data shows, resources, and checkpoints. Start with what they already know from their logs.",
            "courses", "Courses", "learning path syllabus year plan", input = "What do you want to be able to do in a year?"),
        Lens("interestradar", "Interest Radar", "Notices what you keep reading and watching, and offers to go deeper",
            Icons.Outlined.Radar, Hub.Learn, listOf("Notes", "Read", "News", "Watch", "Podcast", "Library", "Book"),
            "Find the topics that keep coming back in what they save, read and watch. For the top three, show the evidence, then offer a deeper path (a course, a book, a project) sized to their time.",
            "radar", "Radar", "curiosity topics deeper interests"),
        Lens("knowledgegaps", "Knowledge Gaps Map", "What you know, against what your goals need",
            Icons.Outlined.TravelExplore, Hub.Learn, listOf("Notes", "Learn", "Goal", "Course", "Study", "Tasks"),
            "Map knowledge: what their notes and learning show they know, what their goals and tasks need, and the gaps between, ranked by how much each gap blocks a goal. Give the first lesson for the biggest gap.",
            "learning", "Learning", "gaps skills needed goals map"),
        // Faith and character
        Lens("charactermirror", "Character Mirror", "A private monthly reflection on patience, honesty and generosity",
            Icons.Outlined.Face, Hub.Deen, listOf("Diary", "Mood", "Giving", "Sadaqah", "Kind", "Tension", "Promise", "Favours"),
            "Write a gentle, private monthly character reflection on patience, honesty, generosity and keeping promises, drawn only from their own entries, with one example each and one small practice for next month. Never preach.",
            "mirror", "Mirror", "akhlaq character reflection monthly", stats = L.deenTiles),
        // People and relationships
        Lens("keepintouch", "Keep-in-touch Rhythm", "A contact rhythm per person, with natural moments to reach out",
            Icons.Outlined.Groups, Hub.Social, listOf("People", "Birthday", "Notes", "Diary", "Visit", "Messenger", "Networking"),
            "From the people in their records, suggest a rhythm per person (weekly, monthly, seasonal), who is overdue, and a natural reason to reach out to each overdue person this week (a birthday, something they mentioned, an event).",
            "people", "People", "stay in touch relatives friends rhythm"),
        Lens("familysync", "Family Sync", "Family calendar, chores and groceries, lined up across everyone's Atlas",
            Icons.Outlined.FamilyRestroom, Hub.Home, listOf("Family", "Chores", "Household", "Pantry", "Tasks", "Birthday"),
            "Line up the family's week: shared events, who does which chore, the grocery list from pantry and plans, and any clashes. Fair rota, prayer times respected.",
            "familyboard", "Family board", "family calendar chores groceries shared"),
        Lens("couplespace", "Couple Space", "Shared goals, date ideas and anniversaries, with your spouse's consent",
            Icons.Outlined.Favorite, Hub.Social, listOf("Birthday", "Anniversar", "Gift", "Diary", "Goal", "Money"),
            "Plan for the couple: upcoming dates that matter, three date ideas that fit their budget and calendar, one shared goal check-in, and a small kindness for this week. Warm and respectful.",
            "atlas-circle", "Atlas Circle", "spouse wife husband marriage date ideas"),
        Lens("surpriseplan", "Surprise Planner", "Small surprises for people you love, prepared for your approval",
            Icons.Outlined.Celebration, Hub.Social, listOf("People", "Birthday", "Gift", "Notes", "Money"),
            "Pick the people with a moment coming up and plan one small surprise each: what, why it fits them (from what's recorded), cost within their budget, and the steps.",
            "people", "People", "surprise gift idea loved ones"),
        // Home, travel and daily life
        Lens("hometwin", "Home Twin", "Rooms, appliances, warranties and manuals, with maintenance scheduled",
            Icons.Outlined.House, Hub.Home, listOf("Room", "Appliance", "Warranty", "Maintenance", "Repair", "Inventory", "Generator", "Meter"),
            "Describe the home as a twin: per room, the appliances and their age or warranty, what is due for maintenance and when, and the three most urgent jobs with rough costs.",
            "inventory", "Home inventory", "home maintenance appliances warranty rooms"),
        Lens("grocerybrain", "Grocery Brain", "Pantry plus meal plan plus prices gives the cheapest basket",
            Icons.Outlined.LocalGroceryStore, Hub.Home, listOf("Pantry", "Meal", "Price", "Bazaar", "Money", "Food"),
            "Build this week's cheapest grocery basket: what's needed from the meal plan minus the pantry, quantities, where it's been cheapest by their price records, and the total estimate.",
            "nx/pantry", "Pantry", "groceries basket shopping list prices"),
        Lens("emergencyready", "Emergency Readiness", "Documents, contacts, first-aid kit and savings buffer, scored",
            Icons.Outlined.HealthAndSafety, Hub.Home, listOf("Document", "Emergency", "Money", "Savings", "Medicine", "Insurance"),
            "Score emergency readiness out of 100 across documents, emergency contacts, first-aid and medicines, savings buffer (months of spending covered) and insurance, with the three fixes that raise the score most.",
            "emergency", "Emergency", "preparedness score safety kit buffer", stats = L.moneyTiles),
        // Work, projects and creation
        Lens("projectos", "Project OS", "Any project gets goals, tasks, files and Atlas as its team",
            Icons.Outlined.Folder, Hub.Work, listOf("Tasks", "Notes", "Project", "Board", "Meeting"),
            "Turn the project into an operating plan: goal, milestones with dates, the next ten tasks in order, risks, and which Atlas expertise to bring in for each part.",
            "tasks", "Tasks", "project plan milestones team", input = "Which project?", stats = L.workTiles),
        Lens("livingcv", "Living CV", "A CV and portfolio kept current from what you achieve in Atlas",
            Icons.Outlined.Badge, Hub.Work, listOf("Wins", "Progress", "Course", "Learn", "Project", "Tasks", "Lessons"),
            "Update their CV from real achievements: new bullet points in action-result form with numbers, new skills with evidence, and a two-line profile summary. Only use what's recorded.",
            "nx/cv", "CV maker", "resume portfolio achievements update"),
        Lens("outcomesprint", "Outcome Sprints", "Focus sprints scored by what they produced, not minutes",
            Icons.Outlined.Timer, Hub.Work, listOf("Tasks", "Focus", "Study", "Deep work", "Wins"),
            "Score their recent focus by outcomes: what each sprint or work day produced (tasks done, wins), the hours that produce most, and a plan of three outcome sprints for tomorrow with a clear 'done' for each.",
            "timer", "Timer", "focus sprint pomodoro outcomes productivity", stats = L.workTiles),
        Lens("clientdesk", "Client Desk", "Clients, quotes, invoices and deadlines for freelance or business work",
            Icons.Outlined.Handshake, Hub.Work, listOf("Client", "Invoice", "Freelance", "Money owed", "Receivable", "Tasks", "Quote"),
            "Run the client desk: each client with open work, deadlines, money owed and how overdue, which invoice to send or chase today, and a polite follow-up line for each overdue one.",
            "nx/invoice", "Invoice maker", "freelance clients invoices quotes business", stats = L.moneyTiles),
        // Fun, motivation and your world
        Lens("lifeseasons", "Life Seasons", "Three-month seasons with quests drawn from your real goals",
            Icons.Outlined.Park, Hub.Tools, listOf("Goal", "Habits", "Learn", "Money", "Workouts", "Quran"),
            "Name this season (three months) with a theme from their goals, then five quests with measurable targets from their data, current progress for each, and the season's boss challenge.",
            "missions", "Missions", "quests season theme game", stats = L.lifeTiles),
        Lens("questboard", "Daily Quest Board", "Three quests a day, drawn from across your hubs",
            Icons.Outlined.SportsScore, Hub.Tools, emptyList(),
            "Give today's three quests: one for body or deen, one for money or work, one for people or learning. Each is small, specific, finishable today and based on what their data shows is slipping.",
            "missions", "Missions", "daily quests three tasks game", stats = L.lifeTiles),
        Lens("petevolution", "Pet Evolution", "Your pet evolves from your real habits",
            Icons.Outlined.Pets, Hub.Tools, listOf("Habits", "Prayer", "Workouts", "Mood", "Learn"),
            "Describe the pet's evolution stage from their real habits: which traits grew (calm from prayer, strength from workouts, wisdom from learning), what the next evolution needs, and a cheerful line from the pet.",
            "companion", "Companion", "pet grows evolves habits", stats = L.lifeTiles),
        Lens("growingtown", "Growing Town", "Pixel Town unlocks buildings from your real achievements",
            Icons.Outlined.LocationCity, Hub.Tools, listOf("Wins", "Habits", "Money", "Learn", "Workouts", "Prayer"),
            "Decide what the town unlocks from real achievements: list new buildings earned (with the achievement behind each), buildings close to unlocking and exactly what earns them.",
            "town", "Town", "town buildings unlock achievements", stats = L.lifeTiles),
        Lens("challenges", "Friendly Challenges", "Opt-in challenges with friends through Atlas Circle",
            Icons.Outlined.Stars, Hub.Social, listOf("Habits", "Workouts", "Steps", "Reading", "Quran"),
            "Suggest three friendly two-week challenges they could invite friends to, based on habits they already keep: rules, a fair scoring, and a short invite message for Atlas Circle.",
            "atlas-circle", "Atlas Circle", "challenge friends competition streaks"),
        Lens("lifemap", "Life Map", "Your life over the years: places, people and milestones",
            Icons.Outlined.Map, Hub.Memory, listOf("Place", "Trip", "Diary", "People", "Wins", "Memories", "Notes"),
            "Build a life map as a timeline grouped by year and place: milestones, people who mattered, and places, with one line each. End with the chapters that are still unwritten from their goals.",
            "maps", "Maps", "timeline places people milestones years"),
        Lens("signaturemoments", "Signature Moments", "Rare celebrations when several parts of life line up on a great day",
            Icons.Outlined.AutoAwesome, Hub.Tools, emptyList(),
            "Find days where several hubs lined up well (all prayers, a workout, money under budget, good mood, a win). Celebrate the best one with specifics, and name the pattern that made those days great.",
            "nx/scoreboard", "Life scoreboard", "great days celebration best day pattern", stats = L.lifeTiles),
    )

    /** Upgrades to features Atlas already had: they appear inside that feature, from its ✦ dock. */
    val upgrades: Map<String, Lens> = listOf(
        "goalplan" to Lens("goaldecomposer", "Goal Decomposer", "Any goal becomes a savings rule, a skill plan, tasks and checkpoints across hubs",
            Icons.Outlined.AccountTree, Hub.Work, listOf("Money", "Goal", "Learn", "Tasks"),
            "Decompose the goal across Atlas: a monthly savings rule sized from their real income and spending, a skill plan, the first five tasks, and checkpoints at 1, 3 and 6 months.",
            input = "Which goal?", stats = L.moneyTiles),
        "seasons" to Lens("seasonplanner", "Season Planner", "This quarter planned around Ramadan, Eid, exams, weddings and weather",
            Icons.Outlined.CalendarMonth, Hub.Home, listOf("Tasks", "Money", "Birthday", "Wedding", "Exam", "Event"),
            "Plan the next three months around the Islamic calendar (Ramadan, Eid dates), exams, weddings and the season's weather: what to prepare when, money to set aside, and what to move out of the busy weeks."),
        "energyplan" to Lens("energyscheduler", "Live re-plan by energy", "Places tasks by sleep, readiness and prayer anchors, and re-plans when you fall behind",
            Icons.Outlined.Bolt, Hub.Work, listOf("Tasks", "Sleep", "Energy", "Mood", "Prayer", "Workouts"),
            "Re-plan the rest of today: hardest tasks into their high-energy window (from their energy, sleep and mood data), easy ones in low windows, everything snapped between prayer times, and what to move to tomorrow if they're behind.",
            stats = L.workTiles),
        "weekinreview" to Lens("sundayreview", "Sunday Atlas Review", "The week reviewed from every hub, ending with three commitments to seal",
            Icons.Outlined.RateReview, Hub.Tools, emptyList(),
            "Write the Sunday review: a short paragraph per hub (only hubs with data), the week's best moment, what slipped, and exactly three commitments for next week, each measurable, ready to seal in Commit.",
            stats = L.lifeTiles),
        "decisions" to Lens("decisionledger", "Decision outcomes", "Atlas revisits each big decision at 3, 6 and 12 months",
            Icons.Outlined.Gavel, Hub.Work, listOf("Decision", "Diary", "Money", "Mood"),
            "For each decision in the journal older than 3 months, compare the reasoning given with what happened since (from diary, money, mood), and say whether it is working out. List which decisions are due for a 3, 6 or 12 month check."),
        "experiments" to Lens("lifeexperiments", "Experiment results", "Each two-week experiment measured against mood, energy and spending",
            Icons.Outlined.Science, Hub.Health, listOf("experiment", "Mood", "Money", "Sleep", "Energy", "Food"),
            "For each experiment, compare the two weeks during with the two weeks before on mood, energy, sleep and spending, with the numbers, and give a verdict: keep, drop or extend.", stats = L.healthTiles),
        "whatifmoney" to Lens("purchasesim", "Purchase Simulator", "Before buying: the effect on goals, runway, committee turns and the 12-month forecast",
            Icons.Outlined.ShoppingCart, Hub.Money, listOf("Money", "Goal", "Committee", "Debt", "Savings", "Subscription"),
            "Simulate the purchase: effect on this month's budget, savings runway (months), each goal's date, committee turns and debts, cash or instalments compared, and a 12-month balance sketch. End with buy now, wait (until when) or skip.",
            input = "What are you thinking of buying, and the price?", stats = L.moneyTiles),
        "goals" to Lens("inflationgoals", "Inflation-true goals", "Goals adjusted for inflation and the rupee rate",
            Icons.Outlined.TrendingUp, Hub.Money, listOf("Goal", "Savings", "Money", "Rates"),
            "Restate each savings goal in real terms: today's target, what it will really cost by its date at Pakistan's current inflation (search the latest CPI) and rupee rate if priced in dollars, and the new monthly saving needed.",
            web = true, stats = L.moneyTiles),
        "cashflowforecast" to Lens("cashflowweather", "Cash-flow Weather", "30 days of balance drawn like a weather strip, with storm warnings",
            Icons.Outlined.WbSunny, Hub.Money, listOf("Money", "Bill", "Rent", "Committee", "Subscription", "Salary"),
            "Forecast the next 30 days as weather: one line per week with ☀️ 🌤 🌧 ⛈ for balance health, the expected low point and its date, storms (bills, committee, rent), and what to move to avoid a shortfall.",
            stats = L.moneyTiles),
        "billnegotiate" to Lens("billcutter", "Bill Cutter", "Bills and subscriptions you can lower, with the call script or email ready",
            Icons.Outlined.ContentCut, Hub.Money, listOf("Bill", "Subscription", "Money", "Electric", "Internet", "Insurance"),
            "Find the bills and subscriptions they could lower or cancel, ranked by yearly saving, and for the top two write the exact call script or email.", stats = L.moneyTiles),
        "occasionsplan" to Lens("occasionbudget", "Occasion Budgeter", "Money set aside for birthdays, Eid and weddings, with gift ideas from what each person likes",
            Icons.Outlined.Cake, Hub.Money, listOf("Birthday", "Gift", "Eid", "Wedding", "Money", "People"),
            "List upcoming occasions with a set-aside amount each (from what they spent on similar ones), the monthly saving to cover them all, and one gift idea per person from what's recorded about them.", stats = L.moneyTiles),
        "moneymood" to Lens("moodspend", "Mood-Spend Link", "How sleep and mood drive impulse spending, with a nudge at risky hours",
            Icons.Outlined.Mood, Hub.Money, listOf("Money", "Mood", "Sleep", "Diary"),
            "Compare spending on low-mood or short-sleep days with good days, find the risky hours and categories, and set one gentle rule for those moments.", stats = L.moneyTiles),
        "familybudget" to Lens("householdmoney", "Household Money Space", "A family budget, private item by item",
            Icons.Outlined.Home, Hub.Money, listOf("Money", "Family", "Household", "Ration", "Bill"),
            "Draft the household budget: shared costs by category from their records, a fair split, items that stay private, and where the household overspent this month.", stats = L.moneyTiles),
        "pricebook" to Lens("pricememory", "Price Memory", "Remembers what you paid for everything and flags a high price",
            Icons.Outlined.PriceCheck, Hub.Money, listOf("Price", "Money", "Receipt", "Bazaar"),
            "From everything they paid, list the usual price for each repeated item, the cheapest place or time, and any recent purchase that was clearly above their usual.", input = "Checking a price? (optional)"),
        "readiness" to Lens("bodytwin", "Body Twin", "One model of sleep, diet, training, steps and mood forecasting tomorrow's energy",
            Icons.Outlined.Accessibility, Hub.Health, listOf("Sleep", "Food", "Workouts", "Cardio", "Steps", "Mood", "Energy"),
            "Act as their body twin: forecast tomorrow's energy (low, steady, high) from recent sleep, food, training load, steps and mood, explain the two biggest drivers, and give the one change tonight that most improves tomorrow.", stats = L.healthTiles),
        "pantryplan" to Lens("pantrymeals", "Pantry Meal Planner", "The week's meals from pantry, budget, macros and family tastes",
            Icons.Outlined.Restaurant, Hub.Health, listOf("Pantry", "Food", "Meal", "Money", "Recipe", "Protein"),
            "Plan seven days of meals using pantry first, within their food spending, with rough protein per day, family-friendly and halal; then the shopping list for what's missing."),
        "doctorbrief" to Lens("doctorvisit", "Doctor brief, across hubs", "Symptoms, medicines, sleep and food in one page for your doctor — never a diagnosis",
            Icons.Outlined.LocalHospital, Hub.Health, listOf("Symptom", "Pain", "Medicine", "Sleep", "Food", "Blood", "Sugar", "Mood"),
            "Prepare a one-page brief for a doctor: timeline of symptoms, medicines taken, related sleep, food and mood changes, measurements, and five questions to ask. No diagnosis, no treatment advice."),
        "screendiary" to Lens("screenmood", "Screen-Mood Link", "Which apps drain your mood and energy",
            Icons.Outlined.PhoneAndroid, Hub.Health, listOf("Screen", "App", "Mood", "Sleep", "Energy"),
            "Connect screen time with mood, sleep and energy: which apps and hours line up with worse days, one swap for each draining habit, and a gentle limit to try."),
        "heatsafety" to Lens("heatguard", "Heat Guard", "Heat index plus your activity sets water and timing reminders",
            Icons.Outlined.Thermostat, Hub.Health, listOf("Workouts", "Cardio", "Steps", "Water", "Fasting", "Commute"),
            "Check today's heat index for their city (search), then set water targets and the safe times for workouts, errands and commute from their routine, with signs of heat illness to watch.", web = true),
        "eyebreaks" to Lens("focusbreaks", "Focus Body Breaks", "Posture and eye breaks timed to your focus sessions",
            Icons.Outlined.SelfImprovement, Hub.Health, listOf("Focus", "Study", "Deep work", "Pain", "Posture"),
            "Time posture and eye breaks to their usual focus sessions: when, which 60-second move, and how often, adjusted for any back, neck or eye complaints they logged."),
        "elderhealth" to Lens("familycare", "Family Care", "Parents' medicines and appointments, with their consent",
            Icons.Outlined.FamilyRestroom, Hub.Health, listOf("Elder", "Medicine", "Parent", "Appointment", "Care"),
            "Summarise care for family members they look after: medicines and timings, upcoming appointments, anything missed, and the week's caring tasks shared fairly."),
        "sleepdebt" to Lens("sleepbank", "Sleep Debt Bank", "Sleep debt tracked, and paid back in your week",
            Icons.Outlined.Bedtime, Hub.Health, listOf("Sleep", "Prayer", "Mood", "Energy", "Tasks"),
            "Work out their sleep debt over 14 days against their need, then schedule the repayment across the week (earlier nights, a qailulah nap after Dhuhr) around Fajr and their tasks."),
        "lessonslog" to Lens("lessonsfromlife", "Lessons From My Life", "Your own mistakes and decisions turned into lessons and flashcards",
            Icons.Outlined.Lightbulb, Hub.Learn, listOf("Lesson", "Mistake", "Decision", "Diary", "Regret"),
            "Turn their own recorded mistakes and decisions into five lessons, each with the moment it came from, and a flashcard (question → answer) to review."),
        "book" to Lens("booktoaction", "Book to Action", "After a book: three actions scheduled, and checked later",
            Icons.Outlined.MenuBook, Hub.Learn, listOf("Book", "Reading", "Library", "Notes", "Tasks"),
            "For the book they finished or are reading, pick three actions from its ideas that fit their life now, with when to do each and how Atlas will check in.", input = "Which book?"),
        "teachtolearn" to Lens("teachback", "Teach-back grading", "You explain a topic; Atlas grades understanding and fills the gaps",
            Icons.Outlined.RecordVoiceOver, Hub.Learn, listOf("Learn", "Notes", "Study"),
            "Grade their explanation out of 10 for accuracy, completeness and clarity, list what's missing or wrong, then give the corrected explanation in five lines and two questions to test it.",
            input = "Explain the topic in your own words"),
        "debatepartner" to Lens("debateresearch", "Debate with real research", "Atlas argues the other side, with sources",
            Icons.Outlined.Forum, Hub.Learn, listOf("Notes", "Beliefs", "Decision"),
            "Argue the strongest opposite case to their position with current sources (search), then list where they are right, where the evidence is against them, and one question to sit with.",
            input = "Your position", web = true),
        "readinglist" to Lens("readingplan", "Reading Plan", "A yearly reading goal planned against your real free time",
            Icons.Outlined.Book, Hub.Learn, listOf("Reading", "Book", "Library", "Commute", "Tasks"),
            "Plan the year's reading: a realistic number of books for their free time, pages per day, the best reading slots (commute, after Fajr, before bed), and the order of their list."),
        "careercompass" to Lens("careerlive", "Career Compass, live", "Skills, market demand, next roles and applications for any career",
            Icons.Outlined.Work, Hub.Work, listOf("Learn", "Course", "Wins", "Job", "CV", "Skills"),
            "Using their logged skills and wins plus current market demand in Pakistan and remote work (search), give three next roles with fit %, the skill gap for each, and the next application step.", web = true),
        "prayerplanner" to Lens("salahday", "Salah-shaped Day", "Your day drawn around prayer windows, tasks snapping into the gaps",
            Icons.Outlined.Mosque, Hub.Deen, listOf("Prayer", "Tasks", "Workouts", "Meeting"),
            "Draw today as blocks between prayer windows (Fajr–Dhuhr, Dhuhr–Asr, Asr–Maghrib, Maghrib–Isha), placing their open tasks into the gaps by size and energy, with buffer before each prayer.", stats = L.deenTiles),
        "sadaqahgiven" to Lens("gooddeeds", "Good Deeds Ledger", "A private record of sadaqah, help given and family visits",
            Icons.Outlined.VolunteerActivism, Hub.Deen, listOf("Giving", "Sadaqah", "Kind", "Visit", "Favours", "Money"),
            "Summarise their good deeds privately: giving this month and year, help given, family visits, and one gentle idea for next week. No praise-seeking language; keep it between them and Allah."),
        "quranpages" to Lens("khatamplan", "Khatam Planner", "Quran completion fitted to free time, adjusting when behind",
            Icons.Outlined.AutoStories, Hub.Deen, listOf("Quran", "Prayer", "Tasks"),
            "Fit a khatam to their real free time: pages per day, after which prayers, the finish date, and a catch-up plan if they're behind. Never quote verses."),
        "duamoment" to Lens("duamoments", "Dua moments", "The right moments for duas today: a journey, an exam, rain",
            Icons.Outlined.Favorite, Hub.Deen, listOf("Trip", "Exam", "Tasks", "Meeting", "Travel"),
            "From their plans today, list the moments that call for a dua (travel, exam, a hard conversation, rain) and point to Atlas's Duas screen for each — do not write the dua text yourself."),
        "fridayprep" to Lens("jumuahprep", "Jumu'ah prep", "Friday checklist, early-leave reminder and khutbah notes into Memory",
            Icons.Outlined.Mosque, Hub.Deen, listOf("Tasks", "Meeting", "Notes", "Prayer"),
            "Prepare this Friday: the sunnah checklist, when to leave for Jumu'ah from their schedule and commute, meetings to move, and a note template for the khutbah's points."),
        "fastplan" to Lens("fastingday", "Fasting Day Planner", "Sunnah and Ramadan fasts with meals and workouts adjusted",
            Icons.Outlined.NightsStay, Hub.Deen, listOf("Fasting", "Food", "Workouts", "Sleep", "Water"),
            "Plan the next fasting day: suhoor and iftar menus from their food habits, water spread, workout moved to after iftar or lighter, and sleep adjusted."),
        "masjidlive" to Lens("masjidboard", "Masjid Board", "Your masjid's events, straight into your plans",
            Icons.Outlined.Event, Hub.Deen, listOf("Masjid", "Prayer", "Event", "Tasks"),
            "List the masjid events and jamaat changes that matter to them this week and where each fits in their schedule; suggest which to attend."),
        "gratitudeplus" to Lens("gratitudeweave", "Gratitude Weave", "Gratitude entries resurface on hard days, matched to your mood",
            Icons.Outlined.Favorite, Hub.Memory, listOf("Gratitude", "Mood", "Diary", "Kind"),
            "Find their recent hard days from mood and diary, and for each, the gratitude entries that speak to it. Close with three past gratitudes to reread today."),
        "beforemeet" to Lens("conversationprep", "Conversation prep, across hubs", "Before meeting someone: last talk, their news, promises made",
            Icons.Outlined.Forum, Hub.Social, listOf("People", "Notes", "Promise", "Diary", "Messenger", "Birthday"),
            "Before meeting the person: when they last spoke and about what, their news, promises either side, things to ask about, and one thing to avoid.", input = "Who are you meeting?"),
        "event" to Lens("eventcoplan", "Event Co-planner", "Gatherings planned with guests' tastes, a split budget and drafted invites",
            Icons.Outlined.Celebration, Hub.Social, listOf("People", "Money", "Split", "Food", "Event"),
            "Plan the gathering: guest list with tastes or diets from their records, menu, budget and a fair split, timeline, and a short invite message ready to approve.", input = "What's the gathering?"),
        "cooldownqueue" to Lens("messagecooldown", "Message Cooldown, with context", "Before an angry message goes: wait or reword, using your mood and history",
            Icons.Outlined.AcUnit, Hub.Social, listOf("Mood", "Tension", "Diary", "People"),
            "Read the draft with their current mood and past tensions with this person. Say whether to send, wait (how long) or reword, and give a calmer version that keeps their point.", input = "Paste the message"),
        "socialenergy" to Lens("socialmeter", "Social Energy Meter", "Social load against recharge time",
            Icons.Outlined.BatteryChargingFull, Hub.Social, listOf("Event", "Meeting", "People", "Mood", "Sleep", "Visit"),
            "Measure social load this week (gatherings, meetings, visits) against recharge (sleep, quiet days, mood), give the meter as a % and when to protect a quiet evening."),
        "favours" to Lens("favourbalance", "Favour balance", "Favours given and received, with reminders to return kindness",
            Icons.Outlined.Handshake, Hub.Social, listOf("Favours", "Kind", "People", "Giving"),
            "Show the favour balance by person, kindnesses not yet returned, and one natural way to return each this month. Never transactional in tone."),
        "errandplan" to Lens("errandrouter", "Errand Router", "Errands grouped by place and opening hours into one route",
            Icons.Outlined.Route, Hub.Home, listOf("Errands", "Tasks", "Shop", "Bill", "Parcel", "Returns"),
            "Group open errands by area into one route ordered by opening hours, avoiding prayer times and traffic peaks, with the total time estimate."),
        "loadsheddingplan" to Lens("outagechores", "Outage-aware Chores", "Laundry, charging and pumping planned around outages",
            Icons.Outlined.Power, Hub.Home, listOf("Load", "Generator", "Chores", "Water", "Tasks"),
            "Plan the day's power-hungry chores (laundry, ironing, pumping water, charging) into the hours with electricity from their outage pattern, and what to do during cuts."),
        "tripplan" to Lens("tripautopilot", "Trip Autopilot", "Budget, packing, documents, prayers on the way, weather and offline maps",
            Icons.Outlined.Flight, Hub.Travel, listOf("Trip", "Money", "Document", "Vehicle", "Prayer"),
            "Prepare the trip end to end: budget from their spending, packing list, documents to check, prayer stops on the route, weather (search), and what to download for offline.", input = "Where and when?", web = true),
        "commute" to Lens("commutecoach", "Commute Coach", "When to leave and what to listen to on the way",
            Icons.Outlined.Commute, Hub.Travel, listOf("Commute", "Rides", "Podcast", "Learn", "Meeting"),
            "Set today's commute: when to leave for their first commitment with traffic in mind, and a listening plan that fits the minutes (a podcast, a lesson, Quran recitation)."),
        "odometer" to Lens("vehicleforecast", "Vehicle Forecast", "The next service and its cost, predicted from your logs",
            Icons.Outlined.DirectionsCar, Hub.Travel, listOf("Vehicle", "Fuel", "Petrol", "Maintenance", "Service", "Money"),
            "Predict the next service date and cost from their kilometres per week and past services, monthly fuel cost trend, and any part due soon."),
        "rainycal" to Lens("weatherday", "Weather-aware Day", "Rain or heat changes your plans automatically",
            Icons.Outlined.Umbrella, Hub.Home, listOf("Tasks", "Errands", "Workouts", "Commute", "Laundry"),
            "Check today's and tomorrow's weather for their city (search) and rework plans: what moves indoors, what to do earlier, laundry, and commute changes.", web = true),
        "moving" to Lens("moveplanner", "Move Planner", "Moving house or city as one checklist across every hub",
            Icons.Outlined.LocalShipping, Hub.Home, listOf("Document", "Money", "Tasks", "Bill", "School", "Inventory"),
            "Build the move as one checklist by week: documents and address changes, bills to transfer, budget, packing by room, schools or work, and settling in.", input = "Moving from where to where, and when?"),
        "jobsearchplan" to Lens("jobpipeline", "Job Hunt Pipeline", "Applications with research, tailored letters and interview prep",
            Icons.Outlined.Work, Hub.Work, listOf("Job", "CV", "Interview", "Tasks", "Learn"),
            "Run the job hunt as a pipeline: stages with each application, what's next for each, two new roles to apply for this week (search), and interview prep for the nearest one.", web = true),
        "minutes" to Lens("meetingbrain", "Meeting Brain", "Prep, notes and actions that become tasks",
            Icons.Outlined.Groups, Hub.Work, listOf("Meeting", "Notes", "Tasks", "People"),
            "For recent and upcoming meetings: prep points, decisions taken, open actions with owners, and which actions still aren't tasks."),
        "incubator" to Lens("ideagarden", "Idea Garden", "Ideas grow: research added, viability scored, next step suggested",
            Icons.Outlined.Spa, Hub.Create, listOf("Idea", "Notes", "Capture"),
            "Tend the idea garden: each saved idea with a viability score (0–10) and why, research worth adding (search), and the single next step for the two strongest.", web = true),
        "ghost" to Lens("contentstudio", "Content Studio", "Posts and scripts in your own voice, on a calendar",
            Icons.Outlined.Edit, Hub.Create, listOf("Notes", "Post", "Idea", "Wins", "Diary"),
            "Plan two weeks of content in their own voice from their notes, ideas and wins: a calendar with platform, hook and outline for each post."),
        "skillincome" to Lens("skillbridge", "Skill-to-Income Bridge", "New skills linked to real earning opportunities",
            Icons.Outlined.AttachMoney, Hub.Work, listOf("Learn", "Course", "Freelance", "Income", "Side"),
            "Link their growing skills to real earning opportunities now (search freelance and local demand): three offers they could sell, a starting price in rupees, and where to find the first client.", web = true),
        "worktime" to Lens("balanceguard", "Balance Guard", "Work hours tracked, burnout warned, family and prayer time protected",
            Icons.Outlined.Balance, Hub.Work, listOf("Hours", "Work", "Mood", "Sleep", "Prayer", "Family"),
            "Check balance: work hours against sleep, mood, prayers kept and family time; a burnout risk level with reasons; and three boundaries to set this week.", stats = L.lifeTiles),
        "genui" to Lens("livinganswers", "Living Answers", "Answers that arrive as small screens: tables, steps and cards",
            Icons.Outlined.Dashboard, Hub.Tools, emptyList(),
            "Answer the question as a living screen in Markdown: a one-line answer, then a table or checklist (- [ ] items) with their own numbers, then next steps.", input = "Ask anything about your life"),
        "whynumbers" to Lens("explainnumber", "Explain any number", "Tap a number, see where it came from",
            Icons.Outlined.Calculate, Hub.Tools, emptyList(),
            "Explain where the number comes from: which entries make it up (list them with dates and amounts), the calculation, and whether anything looks wrong or missing.", input = "Which number? (e.g. 'Rs 42,000 spent this month')"),
        "parallel" to Lens("whatifsim", "What-If Simulator", "Change job or move city: effects on money, time, health and family, side by side",
            Icons.Outlined.CompareArrows, Hub.Tools, emptyList(),
            "Simulate the change against their real life: a side-by-side table (now vs after) for money per month, time per week, health habits, deen routine and family time, the biggest risk, and a reversible first step.",
            input = "What if I…", stats = L.lifeTiles),
        "townplanner" to Lens("museum", "Achievement Museum", "A room of your real milestones",
            Icons.Outlined.Museum, Hub.Tools, listOf("Wins", "Badges", "Progress", "Money", "Quran", "Learn"),
            "Curate their achievement museum: halls by life area, each exhibit a real milestone with its date and a one-line plaque, and the empty plinths waiting for next milestones."),
        "moodmusic" to Lens("focusambience", "Focus Ambience", "Sky, music and haptics shift together for focus",
            Icons.Outlined.Headphones, Hub.Media, listOf("Focus", "Mood", "Music", "Study"),
            "Suggest a focus ambience for now from their mood and the task: a soundscape or recitation, volume, session length, and how the sky theme should shift."),
        "onthisdaydeep" to Lens("memorylane", "Memory Lane", "On this day across photos, diary, money and places",
            Icons.Outlined.History, Hub.Memory, listOf("Diary", "Money", "Notes", "Place", "Trip", "Memories"),
            "Look back at this date in past months and years across diary, money, notes and places: what was happening, how it compares to now, and one small thing worth remembering."),
    ).toMap()

    /** Intention notes (niyyah): a ledger, so every lens and Oracle can read them. */
    val niyyah = LogSpec("niyyah", "Intention Notes", "A niyyah set for big tasks, recalled when you start", Icons.Outlined.Spa, Hub.Deen,
        "niyyah intention purpose task", fields = listOf(F("task", "For"), F("intention", "Intention", K.LONG), F("date", "Date", K.DATE)),
        line = { o -> o.optString("task") to o.optString("intention").take(90) },
        summary = { l -> listOf("${l.size} intentions set") })

    val specs: List<Spec> get() = lenses.map { it.spec() } + niyyah

    fun attachedTo(id: String): Lens? = upgrades[id]

    val count get() = lenses.size + upgrades.size + 1
}
