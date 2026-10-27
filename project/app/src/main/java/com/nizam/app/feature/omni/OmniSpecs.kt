package com.nizam.app.feature.omni

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub

/** The background heartbeat for every Omni feature: missions step, Atlas studies itself, black boxes open, daily prompts. */
object OmniTick {
    suspend fun run(c: Context) {
        runCatching { Ops.tick(c) }
        runCatching { Evolve.tick(c) }
        runCatching { BlackBox.tick(c) }
        runCatching { Echo.tick(c) }
        runCatching { Candid.tick(c) }
        runCatching { LifeBugs.tick(c) }
        runCatching { Village.tick(c) }
        runCatching { val p = c.getSharedPreferences("omni_git", 0); if (p.getString("day", "") != java.time.LocalDate.now().toString()) { LifeGit.commitMissing(c); p.edit().putString("day", java.time.LocalDate.now().toString()).apply() } }
        runCatching { Breathing.adapt(c) }
    }
}

private class Item(val id: String, val title: String, val blurb: String, val icon: ImageVector, val hub: Hub, val words: String, val section: String, val ui: @Composable () -> Unit)

private val ITEMS: List<Item> get() = listOf(
    // Agentic OS
    Item("kernel", "Atlas Kernel & Node", "Every Atlas function in one library; an MCP server so Claude and other agents can use your phone", Icons.Outlined.Memory, Hub.Tools, "kernel mcp server functions api agents node", "Agentic OS") { KernelScreen() },
    Item("genshell", "Generative Shell", "No apps: ask for any screen and Atlas builds it live", Icons.Outlined.Dashboard, Hub.Tools, "generative ui shell ask screen app-less build", "Agentic OS") { GenShellScreen() },
    Item("missions", "Missions", "Agents that work on one goal for days, with budget, memory and checkpoints", Icons.Outlined.RocketLaunch, Hub.Work, "mission long agent goal days background process", "Agentic OS") { MissionsScreen() },
    Item("ghostrun", "Ghost run", "See a storyboard of every tap before the agent touches your other apps", Icons.Outlined.Visibility, Hub.Tools, "ghost run storyboard preview agent plan apps", "Agentic OS") { GhostRunScreen() },
    Item("journal", "Action journal & undo", "Everything any agent did, with one-tap undo", Icons.Outlined.History, Hub.Tools, "journal undo actions log agent revert", "Agentic OS") { JournalScreen() },
    Item("screenmemory", "Screen memory", "Ask about anything you saw in other apps, remembered privately on the phone", Icons.Outlined.ScreenSearchDesktop, Hub.Memory, "screen memory rewind recall saw remember search", "Agentic OS") { ScreenMemoryScreen() },
    Item("agentcard", "Agent card", "Your agent's address: other agents can ask, book and negotiate; you approve", Icons.Outlined.Badge, Hub.Social, "agent card a2a agent to agent negotiate book availability", "Agentic OS") { AgentCardScreen() },
    Item("selfevolve", "Self-evolving Atlas", "Atlas studies its mistakes, writes rules for itself, and you approve", Icons.Outlined.AutoFixHigh, Hub.Tools, "self evolving improve learn mistakes rules changelog", "Agentic OS") { EvolveScreen() },
    Item("link", "Atlas Link", "Phone to phone, no internet: share notes and tools, beings visit", Icons.Outlined.Link, Hub.Social, "link nearby offline share bluetooth phone to phone", "Agentic OS") { LinkScreen() },
    // Your life as data
    Item("lifediff", "Life diff", "This week vs last, green and red, with blame for bad days", Icons.Outlined.Difference, Hub.Memory, "life diff week compare blame git", "Life as data") { LifeDiffScreen() },
    Item("lifegit", "Life git", "Every day a commit; branch your life to test a new routine", Icons.Outlined.AccountTree, Hub.Memory, "life git branch experiment routine merge revert commit", "Life as data") { LifeGitScreen() },
    Item("keystone", "Keystone finder", "The one small habit that predicts your best days", Icons.Outlined.Key, Hub.Memory, "keystone habit correlation best days pattern", "Life as data") { KeystoneScreen() },
    Item("parallelyou", "Parallel you", "Thousands of simulated futures; pick the you you want", Icons.Outlined.CallSplit, Hub.Memory, "parallel future simulate monte carlo branches", "Life as data") { ParallelYouScreen() },
    Item("whatif", "What if…", "Replay a past day with one thing changed", Icons.Outlined.Replay, Hub.Memory, "counterfactual what if replay day", "Life as data") { CounterfactualScreen() },
    Item("blackbox", "Decision black box", "Seal a decision; open it months later and score your judgement", Icons.Outlined.Inventory2, Hub.Memory, "decision black box seal judge score", "Life as data") { BlackBoxScreen() },
    Item("donelist", "Done list", "Everything you actually did today", Icons.Outlined.TaskAlt, Hub.Work, "done list today did reverse todo", "Life as data") { DoneListScreen() },
    Item("daysound", "Sound of your day", "Your day composed as music", Icons.Outlined.GraphicEq, Hub.Media, "sound music day sonify compose", "Life as data") { DaySoundScreen() },
    Item("monthmovie", "Month movie", "Your month as a narrated film with its own soundtrack", Icons.Outlined.Movie, Hub.Media, "month movie film documentary recap photos", "Life as data") { MonthMovieScreen() },
    // Senses
    Item("aura", "Phone aura", "A private sense of how you're doing from how you use the phone", Icons.Outlined.Spa, Hub.Health, "aura stress phone usage scrolling nudge", "Senses") { AuraScreen() },
    Item("mirror", "Morning mirror", "A selfie check-in and your day, read by a calm co-pilot", Icons.Outlined.Face, Hub.Health, "morning mirror selfie posture tired plan", "Senses") { MorningMirrorScreen() },
    Item("soundid", "Sound ID", "Identify birds, songs, or what's wrong with the car", Icons.Outlined.Hearing, Hub.Tools, "sound identify bird song car machine noise", "Senses") { SoundIdScreen() },
    Item("echo", "Atlas Echo", "Five seconds of the world around you, every day", Icons.Outlined.Mic, Hub.Memory, "echo sound diary ambient daily album", "Senses") { EchoScreen() },
    Item("convocoach", "Conversation coach", "Record a talk; see talk time, interruptions and what to say better", Icons.Outlined.RecordVoiceOver, Hub.Work, "conversation coach meeting interview record feedback", "Senses") { ConvoCoachScreen() },
    Item("fridgebrain", "Fridge brain", "Snap the fridge: what's there, what goes off first, tonight's meal", Icons.Outlined.Kitchen, Hub.Home, "fridge photo food expiry meal", "Senses") { FridgeBrainScreen() },
    Item("candid", "Candid me", "A random daily moment: capture real life, unposed", Icons.Outlined.CameraAlt, Hub.Memory, "candid random photo daily bereal", "Senses") { CandidScreen() },
    // Play
    Item("village", "The being's village", "Your robot's neighbours, gossip and daily newspaper", Icons.Outlined.Cottage, Hub.Media, "village neighbours robots soap opera newspaper", "Play") { VillageScreen() },
    Item("beingclasses", "Being classes", "Your robot teaches you what it learned, with a quiz", Icons.Outlined.School, Hub.Learn, "being classes lesson quiz learn robot", "Play") { BeingClassesScreen() },
    Item("lifearcade", "Life arcade", "Games made from your own life", Icons.Outlined.SportsEsports, Hub.Media, "arcade game quiz typing my life", "Play") { LifeArcadeScreen() },
    Item("oracletheatre", "Oracle theatre", "Two futures played side by side, narrated", Icons.Outlined.TheaterComedy, Hub.Memory, "oracle theatre future decision scenes", "Play") { OracleTheatreScreen() },
    Item("focustunnel", "Focus tunnel", "One task, a timer, and your being working beside you", Icons.Outlined.CenterFocusWeak, Hub.Work, "focus tunnel body doubling timer", "Play") { FocusTunnelScreen() },
    Item("storyrun", "Story run", "An audio adventure that reacts to your real pace", Icons.Outlined.DirectionsRun, Hub.Health, "story run zombies walk audio adventure steps", "Play") { StoryRunScreen() },
    Item("lifequests", "Life quests", "Real quests around your city with checkpoints and badges", Icons.Outlined.Flag, Hub.Travel, "quests city lahore checkpoints explore badges", "Play") { LifeQuestsScreen() },
    Item("adventure", "Random adventure", "A random point near you and a mission", Icons.Outlined.Explore, Hub.Travel, "random adventure randonaut explore nearby", "Play") { RandomAdventureScreen() },
    Item("stepout", "Step-out timer", "The safe moment for a chai break during the match", Icons.Outlined.SportsCricket, Hub.Media, "cricket break step out match live", "Play") { StepOutScreen() },
    // Tools
    Item("premortem", "Pre-mortem", "Imagine the plan failed; turn every cause into a guard", Icons.Outlined.Warning, Hub.Work, "premortem plan failure risks guard", "Tools") { PreMortemScreen() },
    Item("ranker", "Pairwise ranker", "Rank anything by picking between two", Icons.Outlined.Leaderboard, Hub.Tools, "rank compare pairwise elo best list", "Tools") { RankerScreen() },
    Item("holdforme", "Hold for me", "Dial a helpline and press through the menu for you", Icons.Outlined.PhoneInTalk, Hub.Tools, "hold helpline ivr dial menu lesco ptcl", "Tools") { HoldForMeScreen() },
    Item("subgraveyard", "Subscription graveyard", "Tombstones for cancelled subscriptions and what they saved", Icons.Outlined.MoneyOff, Hub.Money, "subscription graveyard cancelled saved", "Tools") { SubGraveyardScreen() },
    Item("lifebugs", "Life bug report", "File anything broken in life; Atlas chases it until fixed", Icons.Outlined.BugReport, Hub.Home, "bug report fix issue broken chase", "Tools") { LifeBugsScreen() },
    Item("crowdofyou", "Crowd of yous", "Five versions of you answer your question", Icons.Outlined.Groups, Hub.Memory, "crowd versions of you council advice", "Tools") { CrowdOfYouScreen() },
    Item("fajrmission", "Fajr mission alarm", "An alarm you can only stop by walking, scanning or reading the dua", Icons.Outlined.Alarm, Hub.Deen, "fajr alarm mission wake walk barcode dua", "Tools") { MissionAlarmScreen() },
    Item("notifjar", "Notification jar", "Heavy notifications sink, junk floats; shake to clear", Icons.Outlined.Notifications, Hub.Tools, "notification jar physics shake", "Tools") { NotificationJarScreen() },
    Item("xray", "X-ray lens", "Drag a lens over any number to see where it came from", Icons.Outlined.Search, Hub.Tools, "xray lens explain number source", "Tools") { XRayScreen() },
    Item("breathing", "Breathing interface", "Atlas changes shape with your state and the hour", Icons.Outlined.Air, Hub.Tools, "breathing adaptive ui shape stress maghrib", "Tools") { BreathingScreen() },
)

@Composable
private fun AtlasOsScreen(open: (String) -> Unit) {
    Column {
        OCard { OTitle("Atlas OS"); OMuted("${ITEMS.size} new powers: the agentic layer, your life as data, new senses, play and tools.") }
        ITEMS.groupBy { it.section }.forEach { (sec, list) -> OTitle(sec)
            list.forEach { i -> OCard(onClick = { open("nx/${i.id}") }) { OText(i.title); OMuted(i.blurb) } } }
        OTitle("Already in Atlas (from the same lists)")
        listOf("timemachine" to "Time machine (scrub back through any day)", "energyplan" to "Energy-aware planner (your peak hours)", "promisekeeper" to "Promise Keeper",
            "contradictions" to "Contradiction Catcher", "negotiatecoach" to "Negotiation prep", "keyboard" to "Atlas keyboard", "haptics" to "Haptic language",
            "wishcool" to "Wishlist cooldown (the want jar)", "groupbrain" to "Group brain", "radiohost" to "Your radio show").forEach { (id, t) ->
            OCard(onClick = { open("nx/$id") }) { OText(t) } }
    }
}

val OmniSpecs: List<Spec> get() = ITEMS.map { i -> ScreenSpec(i.id, i.title, i.blurb, i.icon, i.hub, i.words) { _, _ -> i.ui() } } +
    ScreenSpec("atlasos", "Atlas OS", "Everything new: Kernel, Missions, Generative Shell, Life git, Parallel you, the village and 40 more", Icons.Outlined.Apps, Hub.Tools,
        "atlas os new omni all features agentic") { _, open -> AtlasOsScreen(open) }
