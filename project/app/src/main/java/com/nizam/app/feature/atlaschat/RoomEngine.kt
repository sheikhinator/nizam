package com.nizam.app.feature.atlaschat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A channel (broadcast — only the owner posts) or a group (everyone posts). Public or private. */
class Room(
    val id: String,                 // the room's public key (its identity and address)
    var secret: String?,            // the room's secret key — only the owner holds it
    var name: String,
    var about: String = "",
    val channel: Boolean,           // true = channel (broadcast), false = group
    val private: Boolean,
    var sharedKey: String?,         // hex AES key for a private room (carried in the invite link)
    var ownerPub: String,
    var lastTs: Long = 0L,
    var unread: Int = 0,
    var pinned: Boolean = false,
    var muted: Boolean = false,
) {
    val mineToPost get() = !channel || true // display gate handled in UI; owner-only posting enforced there
    fun toJson(): JSONObject = JSONObject().put("id", id).put("sk", secret ?: "").put("nm", name).put("ab", about)
        .put("ch", channel).put("pv", private).put("key", sharedKey ?: "").put("ow", ownerPub)
        .put("lt", lastTs).put("un", unread).put("pi", pinned).put("mu", muted)
    companion object {
        fun fromJson(o: JSONObject) = Room(o.getString("id"), o.optString("sk").ifEmpty { null }, o.getString("nm"), o.optString("ab"),
            o.optBoolean("ch"), o.optBoolean("pv"), o.optString("key").ifEmpty { null }, o.optString("ow"),
            o.optLong("lt"), o.optInt("un"), o.optBoolean("pi"), o.optBoolean("mu"))
    }
}

const val KIND_ROOM = 42

/**
 * Channels and groups over the same open relays the 1:1 messenger uses. A room is a keypair; its public
 * key is its address. Messages are events tagged with that address. Private rooms carry a shared AES key
 * in their invite link and their contents are sealed with it, so relays only ever see random bytes.
 */
class RoomEngine(
    val net: Transport,
    private val persist: Persist,
    private val scope: CoroutineScope,
    private val chat: ChatEngine,
    private val notify: (title: String, body: String, roomId: String) -> Unit = { _, _, _ -> },
) {
    val rooms = LinkedHashMap<String, Room>()
    private val messages = HashMap<String, MutableList<Msg>>()
    val rev = MutableStateFlow(0)
    var openRoom: String? = null
    private val seen = LinkedHashSet<String>()
    private val sub = "rooms"
    private var started = false
    private var saveJob: Job? = null

    private fun me() = chat.myPub
    private fun mySecret() = chat.identity?.nostrSecret
    private fun myName() = chat.myName
    private fun bump() { rev.value = rev.value + 1 }
    private fun newId() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8).also { SecureRandom().nextBytes(it) })

    fun msgs(roomId: String): List<Msg> = synchronized(messages) { messages[roomId]?.sortedBy { it.ts } ?: emptyList() }

    fun load() {
        runCatching {
            val raw = persist.load() ?: return@runCatching
            val o = JSONObject(String(raw, Charsets.UTF_8))
            o.optJSONArray("rooms")?.let { a -> for (i in 0 until a.length()) Room.fromJson(a.getJSONObject(i)).let { rooms[it.id] = it } }
            o.optJSONObject("msgs")?.let { m -> m.keys().forEach { k -> val arr = m.getJSONArray(k); val l = ArrayList<Msg>(); for (i in 0 until arr.length()) l.add(Msg.fromJson(arr.getJSONObject(i))); messages[k] = l } }
        }
    }

    fun save() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.Default) { delay(400); saveNow() }
    }
    fun saveNow() {
        runCatching {
            val o = JSONObject()
            o.put("rooms", JSONArray(rooms.values.map { it.toJson() }))
            val m = JSONObject(); synchronized(messages) { messages.forEach { (k, v) -> m.put(k, JSONArray(v.takeLast(500).map { it.toJson() })) } }
            o.put("msgs", m)
            persist.save(o.toString().toByteArray(Charsets.UTF_8))
        }
    }

    fun start() {
        if (started) return; started = true
        net.onEvent = { s, ev -> if (s == sub) scope.launch(Dispatchers.Default) { runCatching { onEvent(ev) } } }
        net.start(chat.relays)
        resubscribe()
    }

    private fun resubscribe() {
        val ids = rooms.keys.toList()
        if (ids.isEmpty()) return
        net.subscribe(sub, JSONObject().put("kinds", JSONArray(listOf(KIND_ROOM))).put("#e", JSONArray(ids)).put("limit", 300))
    }

    // ── create / join ──
    fun create(name: String, channel: Boolean, private: Boolean): Room {
        val secret = Bip340.newSecretKey()
        val id = Bip340.publicKey(secret).toHex()
        val key = if (private) ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex() else null
        val r = Room(id, secret.toHex(), name.trim().ifEmpty { if (channel) "Channel" else "Group" }, "", channel, private, key, me().orEmpty())
        rooms[id] = r; saveNow(); resubscribe(); bump()
        return r
    }

    /** atlasroom:<c|g>:<roomId>:<sharedKeyOrEmpty>:<name> */
    fun inviteLink(r: Room): String {
        val kind = if (r.channel) "c" else "g"
        val key = r.sharedKey.orEmpty()
        val nm = Base64.getUrlEncoder().withoutPadding().encodeToString(r.name.toByteArray(Charsets.UTF_8))
        return "atlasroom:$kind:${r.id}:$key:$nm"
    }

    fun join(link: String): Room? {
        val parts = link.trim().removePrefix("atlasroom:").split(":")
        if (parts.size < 3) return null
        val channel = parts[0] == "c"
        val id = parts.getOrNull(1)?.takeIf { it.length == 64 } ?: return null
        val key = parts.getOrNull(2)?.ifEmpty { null }
        val name = runCatching { parts.getOrNull(3)?.ifEmpty { null }?.let { String(Base64.getUrlDecoder().decode(it)) } }.getOrNull() ?: (if (channel) "Channel" else "Group")
        rooms[id]?.let { return it }
        val r = Room(id, null, name, "", channel, key != null, key, "")
        rooms[id] = r; saveNow(); resubscribe(); bump()
        return r
    }

    fun leave(roomId: String) { rooms.remove(roomId); synchronized(messages) { messages.remove(roomId) }; saveNow(); resubscribe(); bump() }
    fun setPinned(roomId: String, v: Boolean) { rooms[roomId]?.pinned = v; saveNow(); bump() }
    fun setMuted(roomId: String, v: Boolean) { rooms[roomId]?.muted = v; saveNow(); bump() }
    fun markRead(roomId: String) { rooms[roomId]?.let { if (it.unread != 0) { it.unread = 0; saveNow(); bump() } } }
    /** In a channel you can post only if you own it; groups let everyone post. */
    fun canPost(r: Room): Boolean = !r.channel || (r.secret != null && r.ownerPub == me())

    // ── send / receive ──
    fun send(roomId: String, text: String, replyTo: String? = null): Msg? {
        val r = rooms[roomId] ?: return null
        val body = text.trim(); if (body.isEmpty()) return null
        val sec = mySecret() ?: return null
        if (!canPost(r)) return null
        val m = Msg(newId(), true, System.currentTimeMillis(), body, replyTo, 1).apply { senderName = myName(); senderPub = me() }
        synchronized(messages) { messages.getOrPut(roomId) { ArrayList() }.add(m) }
        r.lastTs = m.ts; bump(); saveNow()
        scope.launch(Dispatchers.Default) {
            val payload = JSONObject().put("k", "t").put("id", m.id).put("b", body).put("re", replyTo ?: "").put("nm", myName())
            val content = if (r.private && r.sharedKey != null) seal(r.sharedKey!!, payload.toString()) else payload.toString()
            // Channel posts are signed by the room key (so they read as "from the channel"); groups by the member.
            val signer = if (r.channel && r.secret != null) r.secret!!.fromHex() else sec
            val ev = NostrWire.build(signer, KIND_ROOM, listOf(listOf("e", roomId, "", "root")), content)
            net.publish(ev)
        }
        return m
    }

    private suspend fun onEvent(ev: JSONObject) {
        if (!NostrWire.verify(ev)) return
        if (ev.optInt("kind") != KIND_ROOM) return
        val evId = ev.getString("id"); if (!seen.add(evId)) return
        val roomId = NostrWire.tagsOf(ev).firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        val r = rooms[roomId] ?: return
        val senderPub = ev.getString("pubkey")
        // Channels are broadcast: only the room key's posts count.
        if (r.channel && senderPub != roomId) return
        val raw = ev.getString("content")
        val payloadStr = if (r.private && r.sharedKey != null) open(r.sharedKey!!, raw) ?: return else raw
        val p = runCatching { JSONObject(payloadStr) }.getOrNull() ?: return
        if (p.optString("k") != "t") return
        val msgId = p.optString("id").ifEmpty { evId }
        val list = synchronized(messages) { messages.getOrPut(roomId) { ArrayList() } }
        synchronized(messages) { if (list.any { it.id == msgId }) return }
        val mine = senderPub == me()
        val m = Msg(msgId, mine, ev.getLong("created_at") * 1000, p.optString("b"), p.optString("re").ifEmpty { null }, 2).apply {
            senderName = p.optString("nm").ifEmpty { if (r.channel) r.name else senderPub.take(8) }; this.senderPub = senderPub
        }
        synchronized(messages) { list.add(m) }
        r.lastTs = m.ts
        if (!mine && openRoom != roomId) { r.unread++; if (!r.muted) notify(r.name, (m.senderName ?: "") + ": " + m.text.take(80), roomId) }
        bump(); save()
    }

    // ── private-room sealing (AES-256-GCM with the shared key) ──
    private fun seal(keyHex: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyHex.fromHex(), "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
    private fun open(keyHex: String, b64: String): String? = runCatching {
        val all = Base64.getDecoder().decode(b64)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyHex.fromHex(), "AES"), GCMParameterSpec(128, all.copyOf(12)))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
