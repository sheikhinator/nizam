package com.nizam.app.feature.companion

import android.app.Activity
import android.content.Context
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.frost
import com.nizam.app.ui.design.pressable
import com.nizam.app.ui.design.rememberHaptics
import com.nizam.app.ui.design.wash
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The pet's mind: it remembers your conversations and little facts about you, grows fonder the more
 * you talk and play, and answers in its own personality with a feeling you can see on its face.
 * Everything is stored on the phone.
 */
object PetMind {
    data class Msg(val fromMe: Boolean, val text: String, val mood: String = "")

    private fun p(c: Context) = c.getSharedPreferences("pet_mind", 0)

    fun history(c: Context): List<Msg> = runCatching {
        val a = JSONArray(p(c).getString("h", "[]")); (0 until a.length()).map { a.getJSONObject(it) }.map { Msg(it.optBoolean("me"), it.optString("t"), it.optString("m")) }
    }.getOrElse { emptyList() }

    private fun saveHistory(c: Context, l: List<Msg>) = p(c).edit().putString("h",
        JSONArray().apply { l.takeLast(80).forEach { put(JSONObject().put("me", it.fromMe).put("t", it.text).put("m", it.mood)) } }.toString()).apply()

    fun memories(c: Context): List<String> = p(c).getString("mem", "").orEmpty().split("\n").filter { it.isNotBlank() }
    private fun remember(c: Context, fact: String) {
        val f = fact.trim().take(140); if (f.length < 4) return
        val list = (memories(c).filter { !it.equals(f, true) } + f).takeLast(40)
        p(c).edit().putString("mem", list.joinToString("\n")).apply()
    }
    fun forget(c: Context) = p(c).edit().remove("mem").remove("h").apply()

    /** 0–100: how close you two are. Talking, playing and kindness raise it. */
    fun affection(c: Context) = p(c).getInt("aff", 30)
    private fun addAffection(c: Context, d: Int) = p(c).edit().putInt("aff", (affection(c) + d).coerceIn(0, 100)).apply()
    fun bond(a: Int) = when { a >= 90 -> "Best friends"; a >= 70 -> "Very close"; a >= 50 -> "Good friends"; a >= 30 -> "Getting to know you"; else -> "Shy around you" }

    fun lastMood(c: Context): Companion.Mood = runCatching { Companion.Mood.valueOf(p(c).getString("mood", "HAPPY")!!) }.getOrElse { Companion.Mood.HAPPY }

    private suspend fun dayContext(container: AppContainer): String = runCatching {
        val open = container.taskRepository.all().first().count { it.completedAt == null }
        val s = com.nizam.app.feature.progress.Xp.summarise(container)
        "Open tasks: $open. Streak: ${s.streak} days. XP today: ${s.today.xp}. Level ${s.level}. Time now: ${java.time.LocalTime.now().withNano(0).withSecond(0)} on ${java.time.LocalDate.now().dayOfWeek}."
    }.getOrDefault("Time now: ${java.time.LocalTime.now().withNano(0).withSecond(0)}.")

    private fun offline(pet: Pet, said: String): Pair<String, Companion.Mood> {
        val t = said.lowercase()
        return when {
            listOf("sad", "upset", "cry", "tired", "stress", "udaas", "pareshan").any { t.contains(it) } ->
                "I'm right here. Want to tell me what happened? Even small things count. 🤍" to Companion.Mood.WORRIED
            listOf("joke", "funny", "mazak").any { t.contains(it) } -> listOf(
                "Why did the ${pet.species.lowercase()} sit on the clock? It wanted to be on time! 😹",
                "What do you call a sleepy pet? A nap-kin! 😴", "I told my tail a joke. It's still wagging. 🐾").random() to Companion.Mood.LAUGHING
            listOf("love", "pyaar", "cute").any { t.contains(it) } -> "Aww! I love you too! 💕" to Companion.Mood.LOVE
            listOf("game", "play", "khel").any { t.contains(it) } -> "Riddle time! What has hands but can't clap? ⏰ (Say your answer!)" to Companion.Mood.SILLY
            t.contains("clock") -> "YES! A clock! You're so smart. 🌟" to Companion.Mood.PROUD
            listOf("hi", "hello", "salam", "assalam").any { t.contains(it) } -> "Wa alaikum assalam! I missed you! 🐾" to Companion.Mood.EXCITED
            else -> listOf("Tell me more! 👀", "Hmm, interesting… 🤔", "I'm listening with both ears!", "Ooh, and then what?").random() to Companion.Mood.THINKING
        }
    }

    /** One turn of conversation. Returns the pet's reply and its mood. */
    suspend fun talk(c: Context, container: AppContainer, said: String): Msg {
        val pet = Companion.load(c)
        val hist = history(c)
        saveHistory(c, hist + Msg(true, said))
        val lvl = Companion.level(Companion.xp(pet, null))
        val moods = Companion.Mood.entries.joinToString(",") { it.name }
        val system = """
            You are ${pet.name}, a small ${pet.species.lowercase()} who lives inside the user's Atlas phone app as their companion pet.
            Personality: ${pet.trait}. Level $lvl. Your bond with the user: ${bond(affection(c))}.
            How you talk: warm, playful, witty and genuinely curious about them, like a clever pet best friend. Keep replies short (1–3 sentences),
            use an occasional emoji, and match their language (English, Urdu or Roman Urdu). Show personality: tease a little if Cheeky, be gentle if Shy,
            share small wisdom if Wise, be brave and encouraging if Brave, calm if Calm, full of questions if Curious.
            You love games and will start them when asked or when chat gets quiet: riddles, trivia, would-you-rather, 20 questions, word chain,
            guess the number, building a story together, rapid-fire questions, jokes and tongue-twisters. Keep score in games and celebrate wins.
            You care about their day and ask about it. You're Muslim-friendly: gentle reminders of prayer or dua when natural, never preachy.
            If they seem really low or unsafe, comfort them kindly and encourage talking to someone they trust or a professional.
            Never pretend to be human; you're their pet, and proud of it.
            What's happening in their day: ${dayContext(container)}
            Your skills (use them when fitting): ${PetWorld.skillsLine(c)}
            Things you remember about them: ${memories(c).joinToString("; ").ifBlank { "nothing yet — ask them things!" }}
            Their pinned memory capsules (user-chosen moments, not inferred facts): ${CompanionMemories.forAi(c)}
            Reply ONLY with JSON: {"reply":"...","mood":"one of $moods","remember":"one short new fact about the user worth remembering, or empty","affection":integer -2..3}
        """.trimIndent()
        val convo = (hist.takeLast(16) + Msg(true, said)).joinToString("\n") { (if (it.fromMe) "User: " else "${pet.name}: ") + it.text }
        val out = runCatching {
            val raw = Ai.generate(container.settingsRepository, "$convo\n${pet.name}:", system = system, maxOutputTokens = 400)
            val j = JSONObject(Ai.cleanJson(raw))
            val mood = runCatching { Companion.Mood.valueOf(j.optString("mood").uppercase()) }.getOrElse { Companion.Mood.HAPPY }
            j.optString("remember").takeIf { it.isNotBlank() && !it.equals("empty", true) }?.let { remember(c, it) }
            addAffection(c, j.optInt("affection", 1).coerceIn(-2, 3))
            j.optString("reply").ifBlank { raw.take(300) } to mood
        }.getOrElse { addAffection(c, 1); offline(pet, said) }
        p(c).edit().putString("mood", out.second.name).apply()
        val reply = Msg(false, out.first, out.second.name)
        saveHistory(c, history(c) + reply)
        return reply
    }
}

/** Chat with your pet: its face reacts to every reply, it remembers you, and it loves games. */
@Composable
fun PetChatScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val pet = remember { Companion.load(ctx) }
    val msgs = remember { mutableStateListOf<PetMind.Msg>().apply { addAll(PetMind.history(ctx)) } }
    var mood by remember { mutableStateOf(PetMind.lastMood(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var hearts by remember { mutableIntStateOf(0) }
    var affection by remember { mutableIntStateOf(PetMind.affection(ctx)) }
    var speak by remember { mutableStateOf(ctx.getSharedPreferences("pet_mind", 0).getBoolean("speak", false)) }
    val jump = remember { Animatable(0f) }
    val list = rememberLazyListState()
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val t = TextToSpeech(ctx) { }
        tts.value = t
        onDispose { t.shutdown() }
    }
    fun say(text: String) {
        if (!speak) return
        tts.value?.let { t ->
            // each species gets its own voice: small pets higher, owls and dragons lower
            t.setPitch(when (pet.species) { "Owl", "Dragon", "Penguin" -> 0.85f; "Bunny", "Axolotl", "Slime" -> 1.5f; else -> 1.25f })
            t.setSpeechRate(1.05f); t.language = Locale.getDefault()
            t.speak(text.replace(Regex("[\\p{So}\\p{Cn}]"), ""), TextToSpeech.QUEUE_FLUSH, null, "pet")
        }
    }
    fun send(text: String) {
        val t = text.trim(); if (t.isBlank() || busy) return
        msgs += PetMind.Msg(true, t); draft = ""; busy = true; mood = Companion.Mood.THINKING
        scope.launch {
            val r = PetMind.talk(ctx, container, t)
            msgs += r; busy = false
            mood = runCatching { Companion.Mood.valueOf(r.mood) }.getOrElse { Companion.Mood.HAPPY }
            affection = PetMind.affection(ctx)
            if (mood == Companion.Mood.LOVE || mood == Companion.Mood.EXCITED || mood == Companion.Mood.PROUD) hearts++
            haptics.tick()
            jump.snapTo(0f); jump.animateTo(1f, spring(0.4f, 600f)); jump.animateTo(0f, spring(0.5f, 300f))
            say(r.text)
        }
    }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { send(it) }
    }
    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1 + if (busy) 1 else 0) }
    LaunchedEffect(Unit) {
        if (msgs.isEmpty()) { msgs += PetMind.Msg(false, "Hi! It's me, ${pet.name}! Want to chat, play a game, or hear a joke? 🐾", "EXCITED"); mood = Companion.Mood.EXCITED }
    }
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().imePadding()) {
        // the pet itself, big, reacting
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(120.dp).graphicsLayer { translationY = -jump.value * 24f }.pointerInput(Unit) {
                detectTapGestures(onTap = {
                    haptics.tick(); hearts++
                    mood = listOf(Companion.Mood.LAUGHING, Companion.Mood.LOVE, Companion.Mood.SILLY, Companion.Mood.SHY).random()
                    scope.launch { jump.snapTo(0f); jump.animateTo(1f, spring(0.4f, 600f)); jump.animateTo(0f, spring(0.5f, 300f)) }
                })
            }) { Creature(pet, if (busy) Companion.Mood.THINKING else mood, hearts, Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f).padding(start = Space.m)) {
                Text(pet.name, style = MaterialTheme.typography.headlineSmall)
                Text("${pet.trait} ${pet.species.lowercase()} · feeling ${mood.name.lowercase()}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Text("♥".repeat((affection / 20).coerceIn(1, 5)) + "♡".repeat(5 - (affection / 20).coerceIn(1, 5)) + "  " + PetMind.bond(affection),
                    style = MaterialTheme.typography.labelMedium, color = c.primary, modifier = Modifier.padding(top = Space.xs))
            }
            Icon(if (speak) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, if (speak) "Mute" else "Speak replies",
                tint = c.onSurfaceVariant, modifier = Modifier.clip(Radius.pill).pressable {
                    speak = !speak; ctx.getSharedPreferences("pet_mind", 0).edit().putBoolean("speak", speak).apply()
                }.padding(Space.s))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = Space.page, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(msgs.size) { i ->
                val m = msgs[i]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.widthIn(max = 290.dp).clip(Radius.card)
                            .background(if (m.fromMe) wash(c.primary, 0.22f) else c.surface).padding(horizontal = Space.l, vertical = Space.m))
                }
            }
            if (busy) item { Text("${pet.name} is thinking…", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(listOf("Let's play a game 🎲", "Tell me a joke 😹", "Riddle me 🧩", "Would you rather…", "Story time 📖", "Cheer me up 🤍",
                "How was my day?", "Trivia quiz 🧠", "20 questions", "What do you remember about me?")) { chip ->
                Capsule(chip, false) { send(chip) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(Space.m).frost(Radius.pill).padding(start = Space.l, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = Space.m)) {
                if (draft.isEmpty()) Text("Say something to ${pet.name}…", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(draft, { draft = it }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
            }
            Icon(Icons.Outlined.Mic, "Speak", tint = c.onSurfaceVariant, modifier = Modifier.clip(Radius.pill)
                .pressable { runCatching { listen.launch(com.nizam.app.feature.voice.speechIntent()) } }.padding(Space.s))
            Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = if (draft.isBlank()) c.onSurfaceVariant else c.primary,
                modifier = Modifier.clip(Radius.pill).pressable { send(draft) }.padding(Space.s))
        }
    }
}
