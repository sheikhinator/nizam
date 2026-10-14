package com.nizam.app.feature.future

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.ui.components.AppRow
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.theme.DisplayFamily

/** One of Atlas's newer abilities: a screen of its own, reachable from Apps, Search and the agent. */
data class Power(val id: String, val emoji: String, val name: String, val blurb: String, val words: String, val group: String, val tint: Long)

object Powers {
    val ALL = listOf(
        Power("circle", "⭕", "Circle to Search", "Circle anything on screen, in any app, and ask", "circle to search screen lens what is this", "Senses", 0xFF1A73E8),
        Power("lens", "👁", "Lens", "Point the camera and ask — translate, medicine, homework, plants", "camera lens vision look identify translate menu medicine homework", "Senses", 0xFF0F9D58),
        Power("live", "🎙", "Live voice", "A real spoken conversation — interrupt any time", "live voice talk conversation speak hands free", "Senses", 0xFFE8115B),
        Power("translator", "🌐", "Translator", "Speak — they hear it in their language", "translate translator conversation interpreter language", "Senses", 0xFF3D8BD9),
        Power("ar", "🧭", "AR view", "The Qibla and your places, floating where they really are", "ar augmented reality qibla direction camera compass places", "Senses", 0xFF4F8A6B),
        Power("photos", "🖼", "Photo search", "Find any photo by describing it", "photo search gallery find picture receipt cnic screenshot", "Senses", 0xFFD4A24C),
        Power("earbuds", "🎧", "Earbud mode", "Messages whispered into your earbuds, hands-free", "earbuds headphones whisper read messages hands free ambient", "Senses", 0xFF9B8CD6),
        Power("agent", "🤖", "App automations", "Your saved & scheduled app tasks — the Atlas chat runs them in your apps", "app agent automations saved scheduled tasks do it for me operate apps automate whatsapp youtube careem daraz", "Agent", 0xFF1A73E8),
        Power("twin", "💬", "Digital twin", "Replies in your own words; answers for you while busy", "digital twin reply whatsapp auto reply busy style catch up missed", "Agent", 0xFF25D366),
        Power("guardian", "🛡", "Guardian", "Scam shield, SOS, crash & fall detection, family alerts", "guardian sos emergency scam fraud crash fall safety family", "Agent", 0xFFD93025),
        Power("money", "📈", "Money autopilot", "Payments logged as they happen; month-end forecast", "money autopilot expenses forecast budget subscriptions bills jazzcash easypaisa", "Agent", 0xFF0F9D58),
        Power("image", "🎨", "Imagine", "Make any picture from a sentence — free, no key", "image picture generate draw art poster wallpaper", "Create", 0xFFB45FD6),
        Power("summarise", "⚡", "Summarise", "YouTube videos and web pages, in seconds — or read aloud", "summary summarize youtube video article link tldr read aloud", "Create", 0xFFE0A83A),
        Power("authenticator", "🔐", "Authenticator", "Your 2FA codes, sealed in the phone's secure chip", "2fa otp totp codes authenticator login two factor", "Protect", 0xFF3D8BD9),
        Power("documents", "🪪", "Documents", "CNIC, licence, passport, papers — with expiry reminders", "documents cnic id license licence passport degree warranty papers wallet", "Protect", 0xFF4F8A6B),
        Power("pakistan", "🇵🇰", "Pakistan today", "Dollar, gold, petrol, cricket, bills and load-shedding", "dollar rate gold price petrol cricket score psl bill lesco sngpl k-electric load shedding", "Everyday", 0xFF0B7A45),
        Power("chess", "♟", "Chess", "Play Atlas at three levels", "chess game play", "Play", 0xFF6B5B4F),
        Power("quiz", "🧠", "Quiz", "Endless quizzes on any subject, made for you", "quiz trivia test mcq questions learn", "Play", 0xFFD9573D),
        Power("booster", "🎮", "Game booster", "One tap: memory freed, calls silenced, stats on screen", "game booster gaming mode fps performance free ram", "Play", 0xFFE8115B),
    )

    fun get(id: String) = ALL.firstOrNull { it.id == id }
}

@Composable
fun PowersScreen(onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.padding(top = 18.dp, bottom = 4.dp)) {
                Text("Superpowers", style = MaterialTheme.typography.displaySmall.copy(fontFamily = DisplayFamily))
                Text("The newest things Atlas can do", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { PowerSettings() }
        Powers.ALL.groupBy { it.group }.forEach { (group, list) ->
            item { Label(group) }
            items(list, key = { it.id }) { p ->
                Column(Modifier.padding(vertical = 5.dp)) { AppRow(p.emoji, p.name, p.blurb, Color(p.tint)) { onOpen("power/${p.id}") } }
            }
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

/** Opens one power by id. */
@Composable
fun PowerScreen(id: String, container: AppContainer, onOpen: (String) -> Unit) {
    when (id) {
        "circle" -> CircleSetupScreen()
        "lens" -> LensScreen(container)
        "live" -> LiveVoiceScreen(container)
        "translator" -> TranslatorScreen(container)
        "ar" -> ArViewScreen()
        "photos" -> PhotoSearchScreen()
        "earbuds" -> EarbudsScreen(container)
        "agent" -> AppAgentScreen(container)
        "twin" -> TwinScreen(container)
        "guardian" -> GuardianScreen(container)
        "money" -> MoneyPilotScreen(container)
        "image" -> ImagineScreen(container)
        "summarise" -> SummariseScreen(container)
        "authenticator" -> AuthenticatorScreen()
        "documents" -> DocumentsScreen(container)
        "pakistan" -> PakistanScreen(container)
        "chess" -> ChessScreen()
        "quiz" -> QuizScreen(container)
        "booster" -> BoosterScreen()
        else -> Text("Coming soon", Modifier.padding(24.dp))
    }
}

/** Language and the privacy shield — they change how every AI answer works, so they sit at the top. */
@Composable
private fun PowerSettings() {
    val c = androidx.compose.ui.platform.LocalContext.current
    var lang by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Lang.mode(c)) }
    var shield by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Privacy.on()) }
    com.nizam.app.ui.design.Group(Modifier.padding(top = 12.dp)) {
        Text("Atlas speaks", style = MaterialTheme.typography.titleSmall)
        com.nizam.app.core.ChipRow(Lang.MODES.values.toList(), Lang.MODES[lang], { v -> lang = Lang.MODES.entries.first { it.value == v }.key; Lang.set(c, lang) })
        Text("Replies, voice and listening follow this. Mixed Urdu-English is understood either way.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Privacy shield for online AI", style = MaterialTheme.typography.titleSmall)
                Text("Phone numbers, CNICs, cards, IBANs and emails are replaced before anything leaves the phone",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(shield, { shield = it; Privacy.set(it) })
        }
    }
}
