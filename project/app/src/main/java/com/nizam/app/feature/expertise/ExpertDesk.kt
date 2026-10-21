package com.nizam.app.feature.expertise

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ResultActions
import com.nizam.app.feature.nx.Roads
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.ActionTile
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.LivingIcon
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What each field of expertise works with: the parts of the user's life it reads, the questions people
 * start with, and the Atlas tools it hands off to. One toolkit per sector, so a tax accountant's desk
 * shows money and receipts while a coach's shows sleep and workouts.
 */
object Toolkits {
    data class Tool(val label: String, val route: String, val icon: ImageVector)
    data class Kit(val reads: List<String>, val starters: List<String>, val tools: List<Tool>)

    private fun nx(id: String) = "nx/$id"

    fun of(sector: String): Kit = when (sector) {
        "Core" -> Kit(emptyList(),
            listOf("Look across my week and tell me the one thing to change", "Plan my next 7 days around my real commitments",
                "What's the biggest risk in my plans right now?", "Turn my open tasks into a clear order of work"),
            listOf(Tool("Tasks", "tasks", Icons.Outlined.Checklist), Tool("Planner", "plan", Icons.Outlined.CalendarMonth),
                Tool("Oracle", nx("oracle"), Icons.Outlined.AutoAwesome), Tool("Nexus", nx("nexus"), Icons.Outlined.Hub)))
        "Sales & Marketing" -> Kit(listOf("Client", "Post", "Idea", "Freelance", "Money"),
            listOf("Write a launch plan for my product with a 4-week calendar", "Score these leads and tell me who to call first",
                "Write 5 hooks for a post about my work", "Price my offer for the Pakistani market"),
            listOf(Tool("Content Studio", nx("ghost"), Icons.Outlined.Edit), Tool("Brand kit", nx("brandkit"), Icons.Outlined.Palette),
                Tool("Post maker", nx("post"), Icons.Outlined.Campaign), Tool("Client follow-ups", nx("clientfollow"), Icons.Outlined.Handshake)))
        "Customer Service" -> Kit(listOf("Client", "People", "Messenger", "Tension"),
            listOf("Draft a calm reply to this complaint", "Turn these complaints into an FAQ", "How do I say no to a customer politely?",
                "Write a follow-up for a customer who went quiet"),
            listOf(Tool("Reply coach", nx("replycoach"), Icons.Outlined.Reply), Tool("Tone translator", nx("tone"), Icons.Outlined.Translate),
                Tool("Channels", nx("channels"), Icons.Outlined.Forum), Tool("Inbox", "uniinbox", Icons.Outlined.Inbox)))
        "Healthcare" -> Kit(listOf("Symptom", "Medicine", "Sleep", "Mood", "Food", "Workouts", "Blood", "Pain"),
            listOf("Explain my lab report in plain words", "What questions should I ask my doctor about my symptoms?",
                "Look at my sleep and mood and tell me what stands out", "Make a medicine schedule I'll actually keep"),
            listOf(Tool("Medicines", "medicines", Icons.Outlined.Medication), Tool("Doctor brief", nx("doctorbrief"), Icons.Outlined.LocalHospital),
                Tool("Symptoms", nx("symptoms"), Icons.Outlined.MonitorHeart), Tool("Health records", nx("healthrec"), Icons.Outlined.FolderShared)))
        "Finance & Accounting" -> Kit(listOf("Money", "Bill", "Committee", "Debt", "Savings", "Tax", "Invoice", "Income"),
            listOf("Read my spending this month and find the leaks", "How much tax do I owe on my salary this year?",
                "Build me a budget from what I actually spend", "Should I pay off debt or save first?"),
            listOf(Tool("Money", "finance", Icons.Outlined.AccountBalanceWallet), Tool("Salary tax", nx("salarytax"), Icons.Outlined.Receipt),
                Tool("Money Story", nx("moneystory"), Icons.Outlined.AutoStories), Tool("Committees", "committees", Icons.Outlined.Groups)))
        "Ecommerce & Retail" -> Kit(listOf("Price", "Shop", "Money", "Client", "Product"),
            listOf("Write product descriptions for my items", "Set prices with a healthy margin", "Plan my store's Eid sale",
                "Which of my products earn the most?"),
            listOf(Tool("Product price", nx("smallbizprice"), Icons.Outlined.Sell), Tool("Margin", nx("margin"), Icons.Outlined.Percent),
                Tool("Descriptions", nx("productdesc"), Icons.Outlined.Description), Tool("Shop khata", nx("shopkhata"), Icons.Outlined.Book)))
        "Legal" -> Kit(listOf("Document", "Contract", "Rent", "Property", "Notes"),
            listOf("Read this contract and flag the risky clauses", "What are my rights as a tenant in Pakistan?",
                "Draft a legal notice for unpaid money", "What documents do I need for a property transfer?"),
            listOf(Tool("Contract reviewer", nx("contractreview"), Icons.Outlined.Gavel), Tool("Documents", "docexpiry", Icons.Outlined.Folder),
                Tool("Legal basics", nx("legalbasics"), Icons.Outlined.Balance), Tool("Letters", nx("letters"), Icons.Outlined.Mail)))
        "Real Estate" -> Kit(listOf("Property", "Rent", "Money", "Plot"),
            listOf("Should I buy or rent in my city?", "Is this rent increase fair?", "Check this plot deal for red flags",
                "What will this property really cost me per month?"),
            listOf(Tool("Buy or rent", nx("buyrent"), Icons.Outlined.House), Tool("Rent", nx("rent"), Icons.Outlined.Key),
                Tool("Plot area", nx("plot"), Icons.Outlined.Straighten), Tool("Property notes", nx("propertynotes"), Icons.Outlined.Notes)))
        "Education" -> Kit(listOf("Learn", "Study", "Exam", "Course", "School", "Reading", "Quran"),
            listOf("Make me a study plan for my exam", "Explain this topic like a good teacher", "Quiz me on what I studied this week",
                "Plan my child's homework week"),
            listOf(Tool("Learning", "learning", Icons.Outlined.School), Tool("Exam plan", nx("studyplan"), Icons.Outlined.EventNote),
                Tool("Flashcards", nx("flashcards"), Icons.Outlined.Style), Tool("Curriculum", nx("curriculum"), Icons.Outlined.Route)))
        "Hospitality" -> Kit(listOf("Event", "Food", "Guest", "Trip", "Money"),
            listOf("Plan a dinner for 20 guests on a budget", "Write a menu for my café", "How do I get better reviews?",
                "Plan a family trip that everyone enjoys"),
            listOf(Tool("Event planner", nx("event"), Icons.Outlined.Celebration), Tool("Guest hosting", nx("hosting"), Icons.Outlined.People),
                Tool("Trip planner", "travelplan", Icons.Outlined.Flight), Tool("Recipes", nx("recipes"), Icons.Outlined.Restaurant)))
        "Logistics & Supply Chain" -> Kit(listOf("Parcel", "Errands", "Vehicle", "Fuel", "Inventory"),
            listOf("Plan the cheapest route for my deliveries", "How much stock should I keep?", "Track down my late parcel",
                "Cut my fuel cost per delivery"),
            listOf(Tool("Errand router", nx("errandplan"), Icons.Outlined.Route), Tool("Parcels", nx("parcels"), Icons.Outlined.LocalShipping),
                Tool("Cost per km", nx("costperkm"), Icons.Outlined.LocalGasStation), Tool("Inventory", "inventory", Icons.Outlined.Inventory2)))
        "HR & Recruitment" -> Kit(listOf("Job", "CV", "Interview", "Staff", "Work"),
            listOf("Tailor my CV to this job", "Prepare me for an interview tomorrow", "Write a job ad for my shop", "How do I ask for a raise?"),
            listOf(Tool("CV maker", nx("cv"), Icons.Outlined.Badge), Tool("Interview sparring", nx("interviewspar"), Icons.Outlined.RecordVoiceOver),
                Tool("Job finder", nx("jobs"), Icons.Outlined.Work), Tool("Salary coach", nx("negotiate"), Icons.Outlined.Payments)))
        "Technology" -> Kit(listOf("Notes", "Project", "Tasks", "Code"),
            listOf("Explain this error and how to fix it", "Design the architecture for my app idea", "Review my code for bugs",
                "Which laptop should I buy for development?"),
            listOf(Tool("Error decoder", nx("errordecode"), Icons.Outlined.BugReport), Tool("Coding mentor", nx("codementor"), Icons.Outlined.Code),
                Tool("Chief", nx("chief"), Icons.Outlined.Construction), Tool("Gateway", nx("gateway"), Icons.Outlined.Cable)))
        "Construction & Property" -> Kit(listOf("Repair", "Home", "Maintenance", "Money", "Property"),
            listOf("Estimate the cost of building a room", "How many bricks and how much cement do I need?", "Plan my home renovation",
                "Check this contractor's quote"),
            listOf(Tool("Concrete & bricks", nx("concrete"), Icons.Outlined.Foundation), Tool("Tiles", nx("tiles"), Icons.Outlined.GridOn),
                Tool("Paint", nx("paint"), Icons.Outlined.FormatPaint), Tool("Quote compare", nx("quotecompare"), Icons.Outlined.CompareArrows)))
        "Automotive" -> Kit(listOf("Vehicle", "Fuel", "Petrol", "Maintenance", "Service"),
            listOf("When is my car's next service due?", "Why is my car making this noise?", "Is this used car a good deal?",
                "Cut my monthly fuel cost"),
            listOf(Tool("Vehicle", "vehicle", Icons.Outlined.DirectionsCar), Tool("Car health", nx("obd"), Icons.Outlined.CarRepair),
                Tool("Petrol & prices", nx("fuel"), Icons.Outlined.LocalGasStation), Tool("Service forecast", nx("odometer"), Icons.Outlined.Speed)))
        "Insurance" -> Kit(listOf("Insurance", "Health", "Vehicle", "Money", "Document"),
            listOf("Which insurance do I actually need?", "Explain my policy's exclusions", "Help me file this claim", "Compare these two health plans"),
            listOf(Tool("Policies", nx("insurance"), Icons.Outlined.Shield), Tool("Warranty claim", nx("warrantyclaim"), Icons.Outlined.Assignment),
                Tool("Documents", "docexpiry", Icons.Outlined.Folder), Tool("Emergency readiness", nx("emergencyready"), Icons.Outlined.HealthAndSafety)))
        "Government & Public Sector" -> Kit(listOf("Document", "Tax", "Bill", "Passport", "ID"),
            listOf("What documents do I need to renew my passport?", "How do I file my tax return?", "Write an application to the DC office",
                "How do I get a domicile certificate?"),
            listOf(Tool("Government guide", nx("govguide"), Icons.Outlined.AccountBalance), Tool("Official services", nx("govlinks"), Icons.Outlined.Link),
                Tool("Tax return", nx("filer"), Icons.Outlined.Receipt), Tool("Letters", nx("letters"), Icons.Outlined.Mail)))
        "Professional Services" -> Kit(listOf("Client", "Invoice", "Meeting", "Tasks", "Hours"),
            listOf("Scope this project and price it", "Write a proposal for this client", "Turn my meeting notes into actions",
                "How should I structure my consulting offer?"),
            listOf(Tool("Project scoper", nx("scoper"), Icons.Outlined.Rule), Tool("Invoices", nx("invoice"), Icons.Outlined.RequestQuote),
                Tool("Meeting to tasks", nx("minutes"), Icons.Outlined.Groups), Tool("Client Desk", nx("clientdesk"), Icons.Outlined.Handshake)))
        "Agriculture" -> Kit(listOf("Garden", "Weather", "Money", "Water"),
            listOf("What should I plant this season?", "Why are my plant's leaves turning yellow?", "Plan watering around the heat",
                "Is this crop worth it at today's prices?"),
            listOf(Tool("Garden", nx("garden"), Icons.Outlined.Yard), Tool("Weather", "weather", Icons.Outlined.WbSunny),
                Tool("Water tank", nx("watertank"), Icons.Outlined.WaterDrop), Tool("Market check", nx("marketcheck"), Icons.Outlined.ShowChart)))
        "Media & Entertainment" -> Kit(listOf("Post", "Video", "Music", "Watch", "Idea"),
            listOf("Write a script for a 60-second video", "Plan my YouTube channel's first month", "Give my podcast a name and format",
                "What should I watch tonight?"),
            listOf(Tool("Video script", nx("videoscript"), Icons.Outlined.Movie), Tool("Video studio", nx("videostudio"), Icons.Outlined.VideoLibrary),
                Tool("Cinema", "cinema", Icons.Outlined.Theaters), Tool("Music", "music", Icons.Outlined.MusicNote)))
        "Non-profit & Social" -> Kit(listOf("Giving", "Sadaqah", "Kind", "People", "Event"),
            listOf("Plan a fundraiser for my mohalla", "Write an appeal for donations", "How do I give zakat to the right people?",
                "Organise volunteers for an event"),
            listOf(Tool("Giving record", nx("sadaqahgiven"), Icons.Outlined.VolunteerActivism), Tool("Zakat", nx("zakatquick"), Icons.Outlined.Calculate),
                Tool("Neighbourhood help", nx("mohallahelp"), Icons.Outlined.Diversity3), Tool("Polls", nx("polls"), Icons.Outlined.Poll)))
        else -> Kit(emptyList(),
            listOf("What can you help me with?", "Look at my week with this expertise", "Give me a checklist to get started", "What should I watch out for?"),
            listOf(Tool("Training", nx("training"), Icons.Outlined.ModelTraining), Tool("Versions", nx("evolve"), Icons.Outlined.History)))
    }
}

/**
 * One expert's desk. Its own starter questions, a live card of what it can see in your life, the tools it
 * hands off to, worked examples and its version history; and the full Atlas chat with this expertise pinned.
 */
@Composable
fun ExpertDesk(expert: Expertise.Expert, container: AppContainer, open: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val kit = remember(expert.sector) { Toolkits.of(expert.sector) }
    var talking by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<Roads.Item>>(emptyList()) }
    var reading by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var exQ by remember { mutableStateOf("") }
    var exA by remember { mutableStateOf("") }
    var teaching by remember { mutableStateOf(false) }
    val examples = remember(version) { Training.of(ctx, expert.id) }
    val improved = remember(version) { Versions.override(ctx, expert.id) != null }
    val tint = expert.hub.tint()

    BackHandler { if (talking) talking = false else onBack() }
    DisposableEffect(expert.id) {
        Expertise.pin(expert)
        Pulse.emit(Pulse.Kind.THINKING, "Thinking as ${expert.name}", expert.sector, expert.hub.label)
        onDispose { Expertise.pin(null) }
    }
    LaunchedEffect(expert.id) {
        runCatching { Roads.warm(container) }
        items = withContext(Dispatchers.IO) {
            Roads.items(ctx, 40).filter { i -> kit.reads.isEmpty() || kit.reads.any { k -> i.source.contains(k, true) || i.hub.label.equals(k, true) } }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (talking) talking = false else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            LivingIcon(if (expert.sector == "Core") Icons.Outlined.AutoAwesome else expert.hub.icon, tint, size = 40.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f).padding(start = Space.s)) {
                Text(expert.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text("Atlas · ${expert.sector}${if (improved) " · improved" else ""}", style = MaterialTheme.typography.labelMedium, color = tint)
            }
        }
        Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Capsule("Desk", !talking) { talking = false }
            Capsule("Talk", talking) { talking = true }
        }
        if (talking) {
            Box(Modifier.weight(1f)) { com.nizam.app.feature.agent.CuratorScreen(container) }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.page, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(expert.role, style = MaterialTheme.typography.bodyLarge)
            if (expert.fields.isNotBlank()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                expert.fields.split(',').map { it.trim().replace('_', ' ') }.filter { it.isNotBlank() }.forEach { Capsule(it, false) {} }
            }

            Text("Start with", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            kit.starters.forEach { s ->
                com.nizam.app.ui.design.ListRow(s, icon = Icons.Outlined.ChatBubbleOutline, tint = tint) { Expertise.queueAsk(s); talking = true }
            }

            Group {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("From your life", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${items.size} entries", style = MaterialTheme.typography.labelMedium, color = tint)
                }
                if (items.isEmpty()) Text("Nothing logged yet in the parts of Atlas this expertise reads (${kit.reads.joinToString(", ").ifBlank { "everything" }}).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                items.take(5).forEach { Text("· ${it.source}: ${it.text.take(110)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.size(Space.s))
                Button(onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            Ai.generate(container.settingsRepository,
                                "Today is ${java.time.LocalDate.now()}.\nThe user's recorded data, shared by them:\n${Lenses.dataText(items, 4000).ifBlank { "(nothing yet)" }}\n\n" +
                                    "Task: as a ${expert.name.lowercase()}, read this and give the three most useful observations and one recommended next step. " +
                                    "If there's too little data, say what to log.",
                                Expertise.block(expert) + Training.block(ctx, expert.id), 900)
                        }.onSuccess { reading = it }.onFailure { Feedback.show(it.message ?: "Couldn't read it") }
                        busy = false
                    }
                }, enabled = !busy) { Text(if (busy) "Reading…" else "Read my life as ${expert.name}") }
            }
            reading?.let { r ->
                Group { Md(r) }
                ResultActions(expert.name, r)
                OutlinedButton(onClick = { scope.launch {
                    val n = runCatching { Lenses.proposeTasks(container, expert.name, r) }.getOrDefault(0)
                    Feedback.show(if (n == 0) "Nothing to turn into tasks" else "$n tasks are waiting for your approval", "Review") { open("nx/approvalinbox") }
                } }) { Text("Turn into tasks") }
            }

            if (kit.tools.isNotEmpty()) {
                Text("Works with", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                kit.tools.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        row.forEach { t -> ActionTile(t.icon, t.label, Modifier.weight(1f), tint = tint) { open(t.route) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Text("Bring in more minds", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                ActionTile(Icons.Outlined.Diversity3, "Swarm", Modifier.weight(1f), tint = tint, hint = "Many answer, one synthesis") { open("nx/swarm") }
                ActionTile(Icons.Outlined.Forum, "Syndicate", Modifier.weight(1f), tint = tint, hint = "Experts consult each other") { open("nx/syndicate") }
            }

            Group {
                Text("Worked examples · ${examples.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Atlas follows the shape of examples you approve. You can also keep any good answer from the chat.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                examples.take(3).forEach { o -> Text("Q: ${o.optString("q").take(80)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.xs)) }
                if (teaching) {
                    OutlinedTextField(exQ, { exQ = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("A question") }, minLines = 2)
                    OutlinedTextField(exA, { exA = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("The ideal answer") }, minLines = 4)
                }
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (teaching) Button(onClick = {
                        Training.add(ctx, expert.id, exQ, exA); exQ = ""; exA = ""; teaching = false; version++
                        Feedback.show("Example kept")
                    }, enabled = exQ.isNotBlank() && exA.isNotBlank()) { Text("Keep") }
                    OutlinedButton(onClick = { teaching = !teaching }) { Text(if (teaching) "Cancel" else "Add an example") }
                }
            }

            if (improved) Group {
                Text("Improved by Orion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(Versions.of(ctx, expert.id).firstOrNull()?.optString("why").orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    Versions.reset(ctx, expert.id); version++
                    Feedback.show("Back to the original")
                }, Modifier.padding(top = Space.s)) { Text("Restore the original") }
            }
            Spacer(Modifier.size(Space.xxl))
        }
    }
}
