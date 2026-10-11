package com.nizam.app.feature.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Share
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Your pet on the lock screen: a quiet, public notification with its mood and level. Opt-in. */
object PetLock {
    private fun p(c: Context) = c.getSharedPreferences("pet_lock", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun set(c: Context, v: Boolean) { p(c).edit().putBoolean("on", v).apply(); if (v) tick(c) else NotificationManagerCompat.from(c).cancel(ID) }
    private const val ID = 7501

    fun tick(c: Context) {
        if (!on(c)) return
        val pet = Companion.load(c); if (!pet.hatched) return
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33) return
        val mood = Companion.mood(pet, null, com.nizam.app.ui.design.CurrentAmbience.value)
        val line = when (mood) { Companion.Mood.SLEEPY -> "is dozing"; Companion.Mood.HAPPY, Companion.Mood.EXCITED -> "is happy to see you"
            Companion.Mood.BORED -> "is a little bored — come play"; Companion.Mood.SAD, Companion.Mood.WORRIED -> "misses you"; else -> "is hanging out" }
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
            android.app.PendingIntent.getActivity(c, ID, it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT) }
        val n = NotificationCompat.Builder(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("${emoji(pet.species)} ${pet.name} $line")
            .setContentText("Level ${Companion.level(Companion.xp(pet, null))} · ${pet.trait}")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MIN).setSilent(true).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).build()
        runCatching { NotificationManagerCompat.from(c).notify(ID, n) }
    }

    fun emoji(species: String) = when (species) { "Fox" -> "🦊"; "Owl" -> "🦉"; "Cat" -> "🐱"; "Dragon" -> "🐉"; "Axolotl" -> "🦎"; "Penguin" -> "🐧"
        "Bunny" -> "🐰"; "Slime" -> "🫧"; "Panda" -> "🐼"; "Lion" -> "🦁"; "Turtle" -> "🐢"; "Parrot" -> "🦜"; "Hamster" -> "🐹"; else -> "🐾" }
}

/** Pets meet: share a pet card, paste a friend's, and the two get acquainted. Only the card's fields are shared. */
object PetFriends {
    private const val HEAD = "ATLAS-PET:"
    private fun p(c: Context) = c.getSharedPreferences("pet_friends", 0)
    fun card(c: Context): String { val x = Companion.load(c)
        val o = JSONObject().put("name", x.name).put("species", x.species).put("hue", x.hue.toDouble()).put("acc", x.accessory).put("trait", x.trait)
            .put("lv", Companion.level(Companion.xp(x, null)))
        return HEAD + Base64.encodeToString(o.toString().toByteArray(), Base64.NO_WRAP) }
    fun parse(s: String): JSONObject? = runCatching { val t = s.trim().substringAfter(HEAD, "")
        JSONObject(String(Base64.decode(t.lines().first().trim(), Base64.DEFAULT))) }.getOrNull()?.takeIf { s.contains(HEAD) && it.has("species") }
    fun friends(c: Context): List<JSONObject> = runCatching { JSONArray(p(c).getString("list", "[]")) }.getOrElse { JSONArray() }.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun meet(c: Context, o: JSONObject) { val l = friends(c).filter { it.optString("name") != o.optString("name") || it.optString("species") != o.optString("species") }
        p(c).edit().putString("list", JSONArray(l + o.put("met", System.currentTimeMillis()).put("visits", (friends(c).firstOrNull { it.optString("name") == o.optString("name") }?.optInt("visits") ?: 0) + 1)).toString()).apply()
        Companion.save(c, Companion.load(c).let { it.copy(bonusXp = it.bonusXp + 10) }) }
    fun asPet(o: JSONObject) = Pet(hatched = true, species = o.optString("species", "Fox"), name = o.optString("name", "Friend"), hue = o.optDouble("hue", -1.0).toFloat(),
        accessory = o.optString("acc", "None"), trait = o.optString("trait", "Curious"))

    fun greeting(me: Pet, them: JSONObject): String {
        val t = them.optString("trait")
        return when (t) { "Shy" -> "${them.optString("name")} hides behind a cushion, then slowly comes over to sniff ${me.name}."
            "Cheeky" -> "${them.optString("name")} steals ${me.name}'s snack and runs a lap of the room!"
            "Brave" -> "${them.optString("name")} marches up and offers ${me.name} a very serious paw-shake."
            "Wise" -> "${them.optString("name")} shares an old saying; ${me.name} nods as if they understood."
            "Calm" -> "The two curl up side by side and watch the clouds for a while."
            else -> "${me.name} and ${them.optString("name")} circle each other, then start a game of chase." } + " (+10 XP)"
    }
}

object PetExtras {
    val all = listOf(
        ScreenSpec("petar", "Pet in your room (AR)", "See your pet in the camera view; drag it around, tap to pet", Icons.Outlined.ViewInAr, Hub.Social,
            "pet ar camera augmented reality room buddy") { _, _ -> PetArScreen() },
        ScreenSpec("petlock", "Pet on lock screen", "A quiet card with your pet's mood on the lock screen", Icons.Outlined.ScreenLockPortrait, Hub.Social,
            "pet lock screen notification buddy mood") { _, _ -> PetLockScreen() },
        ScreenSpec("petfriends", "Pet playdates", "Swap pet cards with friends and let your pets meet", Icons.Outlined.Diversity1, Hub.Social,
            "pet friends meet playdate buddies share card") { _, _ -> PetFriendsScreen() },
    )
}

@Composable
private fun PetArScreen() {
    val c = LocalContext.current; val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pet = remember { Companion.load(c) }
    var hearts by remember { mutableStateOf(0) }
    var dx by remember { mutableStateOf(0f) }; var dy by remember { mutableStateOf(0f) }
    if (!pet.hatched) { Text("Hatch your pet first (Pet home).", style = MaterialTheme.typography.bodyMedium); return }
    if (!granted) { Column { Text("${pet.name} wants to see your room. The camera view stays on the phone — nothing is recorded.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") } }; return }
    Box(Modifier.fillMaxWidth().height(520.dp)) {
        AndroidView(factory = { ctx -> PreviewView(ctx).also { view ->
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({ runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } }, ContextCompat.getMainExecutor(ctx))
        } }, modifier = Modifier.fillMaxWidth().height(520.dp))
        Creature(pet, if (hearts > 0) Companion.Mood.LOVE else Companion.mood(pet, null, null), hearts,
            Modifier.align(Alignment.Center).offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }.size(170.dp)
                .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); dx += d.x; dy += d.y } }
                .pointerInput(Unit) { detectTapGestures { hearts++ } })
        Text("Drag ${pet.name} onto a table or the floor · tap to pet", Modifier.align(Alignment.BottomCenter).padding(10.dp),
            style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun PetLockScreen() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(PetLock.on(c)) }
    val pet = remember { Companion.load(c) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("A silent card shows ${PetLock.emoji(pet.species)} ${pet.name}'s mood on your lock screen, updated every so often. It doesn't buzz or show anything private.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Show on lock screen", Modifier.weight(1f)); Switch(on, { on = it; PetLock.set(c, it) }) }
        if (!pet.hatched) Text("Hatch your pet first.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PetFriendsScreen() {
    val c = LocalContext.current
    val me = remember { Companion.load(c) }
    var paste by remember { mutableStateOf("") }
    var friends by remember { mutableStateOf(PetFriends.friends(c)) }
    var meeting by remember { mutableStateOf<JSONObject?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!me.hatched) { Text("Hatch your pet first.", style = MaterialTheme.typography.bodyMedium); return@Column }
        TextButton(onClick = { Share.text(c, "Let our pets meet! Open Atlas › Pet playdates and paste:\n${PetFriends.card(c)}", "${me.name} wants a playdate") }) {
            Text("Send ${me.name}'s card to a friend") }
        OutlinedTextField(paste, { paste = it }, label = { Text("Paste a friend's pet card") }, modifier = Modifier.fillMaxWidth())
        val other = PetFriends.parse(paste)
        if (paste.isNotBlank() && other == null) Text("That isn't a pet card.", style = MaterialTheme.typography.bodySmall)
        other?.let { o -> TextButton(onClick = { PetFriends.meet(c, o); meeting = o; friends = PetFriends.friends(c); paste = "" }) {
            Text("Let ${me.name} meet ${o.optString("name")}") } }
        meeting?.let { o ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Creature(me, Companion.Mood.EXCITED, 1, Modifier.size(120.dp))
                Creature(PetFriends.asPet(o), Companion.Mood.HAPPY, 1, Modifier.size(120.dp))
            }
            Text(PetFriends.greeting(me, o), style = MaterialTheme.typography.bodyMedium)
        }
        if (friends.isNotEmpty()) Text("${me.name}'s friends", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        friends.sortedByDescending { it.optLong("met") }.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${PetLock.emoji(f.optString("species"))} ${f.optString("name")} · ${f.optString("trait")} · Lv ${f.optInt("lv")}", Modifier.weight(1f))
                Text("${f.optInt("visits")} visits", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { meeting = f }) { Text("Play") }
            }
        }
    }
}
