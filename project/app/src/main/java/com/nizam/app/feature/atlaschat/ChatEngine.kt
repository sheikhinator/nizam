package com.nizam.app.feature.atlaschat

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Where the app keeps its data. On the phone this is a file sealed with a key held by the Android Keystore. */
interface Persist { fun load(): ByteArray?; fun save(data: ByteArray) }

class Msg(
    val id: String, val mine: Boolean, val ts: Long, var text: String, val reply: String?,
    var status: Int = 0,            // 0 waiting to leave the phone, 1 sent, 2 delivered, 3 read
    var edited: Boolean = false, var deleted: Boolean = false, var expiresAt: Long = 0L,
    val reactions: MutableMap<String, String> = LinkedHashMap(), var outbox: String? = null,
    var system: Boolean = false,
    // Media: a photo, video or file. mediaPath is the local decrypted copy; mediaUrl/Key/Iv let the
    // recipient fetch and decrypt an uploaded blob when it isn't inline. mediaMime drives how it renders.
    var mediaMime: String? = null, var mediaName: String? = null, var mediaSize: Long = 0L,
    var mediaPath: String? = null, var mediaUrl: String? = null, var mediaKey: String? = null, var mediaIv: String? = null,
) {
    val hasMedia get() = mediaPath != null || mediaUrl != null
    fun toJson() = JSONObject().put("id", id).put("m", mine).put("ts", ts).put("t", text).put("re", reply ?: "").put("st", status)
        .put("ed", edited).put("de", deleted).put("ex", expiresAt).put("sy", system).put("ob", outbox ?: "")
        .put("mm", mediaMime ?: "").put("mn", mediaName ?: "").put("mz", mediaSize).put("mp", mediaPath ?: "")
        .put("mu", mediaUrl ?: "").put("mk", mediaKey ?: "").put("mv", mediaIv ?: "")
        .put("rx", JSONObject().also { o -> reactions.forEach { (k, v) -> o.put(k, v) } })
    companion object {
        fun fromJson(o: JSONObject): Msg {
            val rx = LinkedHashMap<String, String>()
            o.optJSONObject("rx")?.let { r -> r.keys().forEach { k -> rx[k] = r.getString(k) } }
            return Msg(o.getString("id"), o.getBoolean("m"), o.getLong("ts"), o.optString("t"), o.optString("re").ifEmpty { null }, o.optInt("st"),
                o.optBoolean("ed"), o.optBoolean("de"), o.optLong("ex"), rx, o.optString("ob").ifEmpty { null }, o.optBoolean("sy")).apply {
                mediaMime = o.optString("mm").ifEmpty { null }; mediaName = o.optString("mn").ifEmpty { null }; mediaSize = o.optLong("mz")
                mediaPath = o.optString("mp").ifEmpty { null }; mediaUrl = o.optString("mu").ifEmpty { null }
                mediaKey = o.optString("mk").ifEmpty { null }; mediaIv = o.optString("mv").ifEmpty { null }
            }
        }
    }
}

class SessRec(val sid: String, val session: Session, val created: Long, var lastRecv: Long, val initIk: String?, val initEk: String?) {
    fun toJson() = JSONObject().put("sid", sid).put("s", session.toJson()).put("c", created).put("lr", lastRecv).put("ii", initIk ?: "").put("ie", initEk ?: "")
    companion object {
        fun fromJson(o: JSONObject) = SessRec(o.getString("sid"), Session.fromJson(o.getJSONObject("s")), o.getLong("c"), o.getLong("lr"),
            o.optString("ii").ifEmpty { null }, o.optString("ie").ifEmpty { null })
    }
}

class Contact(
    val pub: String, var name: String, var ik: String, var spk: String, var sig: String,
    val sessions: MutableList<SessRec> = ArrayList(), var unread: Int = 0, var disappear: Int = 0,
    var blocked: Boolean = false, var muted: Boolean = false, var verified: Boolean = false, var lastTs: Long = 0L, var pinned: Boolean = false,
) {
    fun bundle() = PreKeyBundle(pub.fromHex(), ik.fromHex(), spk.fromHex(), sig.fromHex())
    fun toJson() = JSONObject().put("p", pub).put("n", name).put("ik", ik).put("sp", spk).put("sg", sig).put("un", unread).put("di", disappear)
        .put("bl", blocked).put("mu", muted).put("ve", verified).put("lt", lastTs).put("pi", pinned).put("ss", JSONArray(sessions.map { it.toJson() }))
    companion object {
        fun fromJson(o: JSONObject): Contact {
            val c = Contact(o.getString("p"), o.getString("n"), o.getString("ik"), o.getString("sp"), o.getString("sg"), ArrayList(), o.optInt("un"), o.optInt("di"),
                o.optBoolean("bl"), o.optBoolean("mu"), o.optBoolean("ve"), o.optLong("lt"), o.optBoolean("pi"))
            o.optJSONArray("ss")?.let { a -> for (i in 0 until a.length()) c.sessions.add(SessRec.fromJson(a.getJSONObject(i))) }
            return c
        }
    }
}

/** Someone you have not added who wrote to you. Nothing is shown until you accept. */
class ChatRequest(val contact: Contact, val msgs: MutableList<Msg>, val ts: Long) {
    fun toJson() = JSONObject().put("c", contact.toJson()).put("ts", ts).put("m", JSONArray(msgs.map { it.toJson() }))
    companion object {
        fun fromJson(o: JSONObject): ChatRequest {
            val m = ArrayList<Msg>(); o.optJSONArray("m")?.let { a -> for (i in 0 until a.length()) m.add(Msg.fromJson(a.getJSONObject(i))) }
            return ChatRequest(Contact.fromJson(o.getJSONObject("c")), m, o.getLong("ts"))
        }
    }
}

class Found(val pub: String, val name: String, val bundle: PreKeyBundle)

val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.snort.social")
val USERNAME_RE = Regex("^[a-z0-9_]{3,20}$")

class ChatEngine(
    val net: Transport,
    private val persist: Persist,
    private val scope: CoroutineScope,
    private val notify: (title: String, body: String, pub: String) -> Unit = { _, _, _ -> },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val mediaDir: java.io.File? = null,
) {
    var identity: Identity? = null; private set
    var myName: String = ""; private set
    var relays: MutableList<String> = DEFAULT_RELAYS.toMutableList(); private set
    var sendReadReceipts = true
    var openChat: String? = null
    val rev = MutableStateFlow(0)
    val contacts = LinkedHashMap<String, Contact>()
    val messages = HashMap<String, MutableList<Msg>>()
    val requests = ArrayList<ChatRequest>()
    private val seen = LinkedHashSet<String>()
    private var lastSeen = 0L
    private var claimedAt = 0L
    private val saveLock = Any()
    private var saveJob: Job? = null
    private var started = false
    private val inboxSub = "inbox"
    /** One thing at a time touches the encryption state, and sends leave in the order they were written. */
    private val lock = Mutex()
    private val outQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private fun enqueue(block: suspend () -> Unit) { outQueue.trySend(block) }

    val myPub: String? get() = identity?.nostrPub?.toHex()
    val registered: Boolean get() = identity != null && myName.isNotEmpty()
    private fun bump() { rev.value = rev.value + 1 }
    private fun newId() = Prim.random(8).toHex()
    fun msgs(pub: String): List<Msg> = synchronized(messages) { messages[pub]?.toList() ?: emptyList() }

    // ───────────────────────── saving ─────────────────────────
    fun load() {
        val raw = persist.load() ?: return
        val o = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull() ?: return
        identity = o.optJSONObject("id")?.let { Identity.fromJson(it) }
        myName = o.optString("name"); claimedAt = o.optLong("claim"); lastSeen = o.optLong("ls"); sendReadReceipts = o.optBoolean("rr", true)
        o.optJSONArray("relays")?.let { a -> relays = (0 until a.length()).map { a.getString(it) }.toMutableList() }
        o.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) seen.add(a.getString(i)) }
        o.optJSONArray("contacts")?.let { a -> for (i in 0 until a.length()) Contact.fromJson(a.getJSONObject(i)).let { contacts[it.pub] = it } }
        o.optJSONObject("msgs")?.let { m -> m.keys().forEach { k -> val a = m.getJSONArray(k); messages[k] = (0 until a.length()).map { Msg.fromJson(a.getJSONObject(it)) }.toMutableList() } }
        o.optJSONArray("req")?.let { a -> for (i in 0 until a.length()) requests.add(ChatRequest.fromJson(a.getJSONObject(i))) }
        bump()
    }

    fun save() {
        synchronized(saveLock) {
            saveJob?.cancel()
            saveJob = scope.launch { delay(350); saveNow() }
        }
    }

    fun saveNow() {
        val o = JSONObject().put("name", myName).put("claim", claimedAt).put("ls", lastSeen).put("rr", sendReadReceipts)
        identity?.let { o.put("id", it.toJson()) }
        o.put("relays", JSONArray(relays)).put("seen", JSONArray(seen.toList().takeLast(3000)))
        synchronized(messages) {
            o.put("contacts", JSONArray(contacts.values.map { it.toJson() }))
            o.put("msgs", JSONObject().also { m -> messages.forEach { (k, v) -> m.put(k, JSONArray(v.map { it.toJson() })) } })
            o.put("req", JSONArray(requests.map { it.toJson() }))
        }
        persist.save(o.toString().toByteArray(Charsets.UTF_8))
    }

    // ───────────────────────── connecting ─────────────────────────
    fun start() {
        if (started) return
        started = true
        scope.launch { for (job in outQueue) lock.withLock { runCatching { job() } } }
        net.onEvent = { sub, ev -> if (sub == inboxSub) scope.launch(Dispatchers.Default) { onEvent(ev) } }
        net.start(relays)
        identity?.let { subscribeInbox() }
        scope.launch {
            while (isActive) {
                delay(15_000)
                sweep(); retryOutbox()
                if (registered && now() - lastBundle > 30 * 60_000L) publishBundle()
            }
        }
        if (registered) scope.launch { delay(2500); publishBundle() }
    }

    fun stop() { started = false; net.stop() }

    private fun subscribeInbox() {
        val me = myPub ?: return
        net.subscribe(inboxSub, JSONObject().put("kinds", JSONArray(listOf(KIND_MSG))).put("#p", JSONArray(listOf(me)))
            .put("since", maxOf(0L, lastSeen - 7200)))
    }

    fun setRelays(list: List<String>) {
        relays = list.map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct().toMutableList().ifEmpty { DEFAULT_RELAYS.toMutableList() }
        net.start(relays); save(); bump()
    }

    // ───────────────────────── your Atlas ID ─────────────────────────
    private fun bundleEvent(): JSONObject {
        val id = identity!!; val b = id.bundle()
        val content = JSONObject().put("v", 1).put("n", myName).put("ik", b.ik.toHex()).put("spk", b.spk.toHex()).put("sig", b.sig.toHex()).put("c", claimedAt)
        return NostrWire.build(id.nostrSecret, KIND_BUNDLE, listOf(listOf("d", D_TAG), listOf("t", "atlaschat"), listOf("t", "atlasuser:$myName")), content.toString())
    }

    private var lastBundle = 0L
    fun publishBundle() { if (registered) { lastBundle = now(); net.publish(bundleEvent()) } }

    private fun parseBundle(e: JSONObject): Found? = runCatching {
        if (!NostrWire.verify(e) || e.getInt("kind") != KIND_BUNDLE) return null
        val c = JSONObject(e.getString("content"))
        val pub = e.getString("pubkey")
        val b = PreKeyBundle(pub.fromHex(), c.getString("ik").fromHex(), c.getString("spk").fromHex(), c.getString("sig").fromHex())
        if (!b.valid()) null else Found(pub, c.optString("n"), b)
    }.getOrNull()

    private fun claimTime(e: JSONObject) = runCatching { JSONObject(e.getString("content")).optLong("c", e.getLong("created_at") * 1000) }.getOrDefault(Long.MAX_VALUE)

    suspend fun lookupUser(name: String): Found? {
        val n = name.trim().removePrefix("@").lowercase()
        if (!USERNAME_RE.matches(n)) return null
        val evs = net.query(JSONObject().put("kinds", JSONArray(listOf(KIND_BUNDLE))).put("#t", JSONArray(listOf("atlasuser:$n"))).put("limit", 20))
        return evs.filter { e -> NostrWire.tagsOf(e).any { it.size > 1 && it[0] == "t" && it[1] == "atlasuser:$n" } }
            .mapNotNull { e -> parseBundle(e)?.let { f -> Triple(claimTime(e), f.pub, f) } }
            .filter { it.third.name == n }
            .minWithOrNull(compareBy({ it.first }, { it.second }))?.third
    }

    suspend fun lookupId(pubHex: String): Found? {
        val evs = net.query(JSONObject().put("kinds", JSONArray(listOf(KIND_BUNDLE))).put("authors", JSONArray(listOf(pubHex))).put("#d", JSONArray(listOf(D_TAG))).put("limit", 5))
        return evs.sortedByDescending { it.optLong("created_at") }.firstNotNullOfOrNull { parseBundle(it) }?.takeIf { it.pub == pubHex }
    }

    /** Accepts "@name", "name", "atlas:<id>" or a bare 64-character id. */
    suspend fun find(input: String): Found? = withContext(Dispatchers.Default) {
        val t = input.trim()
        val id = t.removePrefix("atlas:").trim().lowercase()
        if (id.length == 64 && id.all { it in "0123456789abcdef" }) lookupId(id) else lookupUser(t)
    }

    /** Creates your identity and claims the name. Returns an error message, or null on success. */
    suspend fun register(wanted: String): String? = withContext(Dispatchers.Default) {
        registerInner(wanted)
    }

    private suspend fun registerInner(wanted: String): String? {
        val n = wanted.trim().removePrefix("@").lowercase()
        if (!USERNAME_RE.matches(n)) return "Use 3 to 20 letters, numbers or underscores."
        if (net.connected() == 0) { delay(2500); if (net.connected() == 0) return "No connection to the relays yet. Check your internet and try again." }
        val taken = lookupUser(n)
        val existing = identity
        if (taken != null && taken.pub != existing?.nostrPub?.toHex()) return "@$n is already taken."
        val id = existing ?: Identity.create()
        identity = id; myName = n; if (claimedAt == 0L) claimedAt = now()
        net.publish(bundleEvent())
        subscribeInbox()
        save(); bump()
        return null
    }

    fun atlasId(): String = "atlas:" + (myPub ?: "")

    // ───────────────────────── contacts ─────────────────────────
    fun addContact(f: Found): Contact {
        val c = contacts.getOrPut(f.pub) {
            Contact(f.pub, f.name.ifEmpty { f.pub.take(8) }, f.bundle.ik.toHex(), f.bundle.spk.toHex(), f.bundle.sig.toHex())
        }
        if (c.ik != f.bundle.ik.toHex()) { /* their identity key changed: keep the old chat, mark as unverified */ c.verified = false }
        synchronized(messages) { messages.getOrPut(f.pub) { ArrayList() } }
        save(); bump()
        return c
    }

    fun removeContact(pub: String) { synchronized(messages) { contacts.remove(pub); messages.remove(pub) }; save(); bump() }
    fun setBlocked(pub: String, v: Boolean) { contacts[pub]?.blocked = v; save(); bump() }
    fun setMuted(pub: String, v: Boolean) { contacts[pub]?.muted = v; save(); bump() }
    fun setPinned(pub: String, v: Boolean) { contacts[pub]?.pinned = v; save(); bump() }
    fun setVerified(pub: String, v: Boolean) { contacts[pub]?.verified = v; save(); bump() }
    fun renameContact(pub: String, name: String) { contacts[pub]?.name = name.trim().ifEmpty { pub.take(8) }; save(); bump() }
    fun safety(pub: String): String? { val c = contacts[pub] ?: return null; val me = identity ?: return null; return safetyNumber(me.ikPub, c.ik.fromHex()) }

    fun acceptRequest(r: ChatRequest) {
        requests.remove(r)
        contacts[r.contact.pub] = r.contact
        synchronized(messages) { messages.getOrPut(r.contact.pub) { ArrayList() }.addAll(r.msgs) }
        r.contact.unread = r.msgs.count { !it.mine }
        r.contact.lastTs = r.msgs.maxOfOrNull { it.ts } ?: now()
        val ids = r.msgs.filter { !it.mine }.map { it.id }
        if (ids.isNotEmpty()) enqueue { dispatch(r.contact, payloadBase("r").put("s", "d").put("ids", JSONArray(ids))) }
        save(); bump()
    }

    fun declineRequest(r: ChatRequest, block: Boolean = false) { requests.remove(r); save(); bump() }

    // ───────────────────────── sending ─────────────────────────
    private fun sidOf(ikInitiator: ByteArray, ek: ByteArray) = Prim.sha256("atlas-sid-v1".toByteArray(), ikInitiator, ek).copyOf(8).toHex()

    private suspend fun ensureSession(c: Contact): SessRec? {
        c.sessions.maxByOrNull { maxOf(it.lastRecv, it.created) }?.let { return it }
        val me = identity ?: return null
        val b = (if (c.ik.isNotEmpty()) c.bundle().takeIf { it.valid() } else null) ?: lookupId(c.pub)?.bundle ?: return null
        val (sess, ek) = Session.initiate(me, b)
        val rec = SessRec(sidOf(me.ikPub, ek), sess, now(), 0L, me.ikPub.toHex(), ek.toHex())
        c.sessions.add(rec)
        return rec
    }

    /** Seals a payload for one person and returns the relay event, or null when no secure channel could be set up. */
    private suspend fun seal(c: Contact, payload: JSONObject): JSONObject? {
        val rec = ensureSession(c) ?: return null
        val inner = rec.session.encrypt(payload.toString().toByteArray(Charsets.UTF_8))
        val packet = if (rec.initEk != null && rec.lastRecv == 0L)
            byteArrayOf(0) + rec.initIk!!.fromHex() + rec.initEk.fromHex() + inner
        else byteArrayOf(1) + rec.sid.fromHex() + inner
        val content = Base64.getEncoder().encodeToString(packet)
        return NostrWire.build(Bip340.newSecretKey(), KIND_MSG, listOf(listOf("p", c.pub)), content)
    }

    private suspend fun transmit(c: Contact, payload: JSONObject): String? {
        val ev = seal(c, payload) ?: return null
        save()
        return ev.toString()
    }

    private fun payloadBase(k: String) = JSONObject().put("k", k).put("fn", myPub).put("nm", myName).put("ts", now())

    fun send(pub: String, text: String, replyTo: String? = null): Msg? {
        val c = contacts[pub] ?: return null
        val body = text.trim(); if (body.isEmpty()) return null
        val m = Msg(newId(), true, now(), body, replyTo, 0, expiresAt = if (c.disappear > 0) now() + c.disappear * 1000L else 0L)
        synchronized(messages) { messages.getOrPut(pub) { ArrayList() }.add(m) }
        c.lastTs = m.ts; bump(); save()
        val p = payloadBase("t").put("id", m.id).put("b", body).put("re", replyTo ?: "").put("x", c.disappear)
        enqueue { dispatch(c, p, m) }
        return m
    }

    /** Sends a photo, video or file. Small photos ride inline (fully E2E); larger media is encrypted and uploaded. */
    fun sendMedia(pub: String, bytes: ByteArray, mime: String, name: String, caption: String = "", replyTo: String? = null): Msg? {
        val c = contacts[pub] ?: return null
        val m = Msg(newId(), true, now(), caption.trim(), replyTo, 0, expiresAt = if (c.disappear > 0) now() + c.disappear * 1000L else 0L)
        m.mediaMime = mime; m.mediaName = name; m.mediaSize = bytes.size.toLong()
        runCatching { mediaDir?.let { d -> d.mkdirs(); java.io.File(d, m.id).apply { writeBytes(bytes) }.also { m.mediaPath = it.absolutePath } } }
        synchronized(messages) { messages.getOrPut(pub) { ArrayList() }.add(m) }
        c.lastTs = m.ts; bump(); save()
        val inline = mime.startsWith("image/") && bytes.size <= 90_000
        enqueue {
            val p = payloadBase("t").put("id", m.id).put("b", caption.trim()).put("re", replyTo ?: "").put("x", c.disappear)
                .put("mm", mime).put("mn", name).put("mz", bytes.size)
            if (inline) {
                p.put("img", Base64.getEncoder().encodeToString(bytes))
            } else {
                val enc = runCatching { AtlasMedia.encrypt(bytes) }.getOrNull()
                val url = enc?.let { AtlasMedia.upload(it.first) }
                if (enc == null || url == null) { m.outbox = null; bump(); save(); return@enqueue }
                m.mediaUrl = url; m.mediaKey = enc.second; m.mediaIv = enc.third; save()
                p.put("mu", url).put("mk", enc.second).put("mv", enc.third)
            }
            dispatch(c, p, m)
        }
        return m
    }

    /** Downloads and decrypts an uploaded blob the first time it's shown; returns the local path. */
    suspend fun fetchMedia(m: Msg): String? {
        m.mediaPath?.let { if (java.io.File(it).exists()) return it }
        val url = m.mediaUrl ?: return null; val k = m.mediaKey ?: return null; val iv = m.mediaIv ?: return null
        val d = mediaDir ?: return null
        val cipher = AtlasMedia.download(url) ?: return null
        val plain = runCatching { AtlasMedia.decrypt(cipher, k, iv) }.getOrNull() ?: return null
        val f = java.io.File(d.apply { mkdirs() }, m.id).apply { writeBytes(plain) }
        m.mediaPath = f.absolutePath; bump(); save(); return f.absolutePath
    }

    private suspend fun dispatch(c: Contact, payload: JSONObject, m: Msg? = null) {
        val evJson = transmit(c, payload)
        if (evJson == null) { m?.let { it.outbox = null; bump() }; return }
        val n = net.publish(JSONObject(evJson))
        if (m != null) { if (n > 0) m.status = maxOf(m.status, 1) else m.outbox = evJson; bump(); save() }
    }

    private fun retryOutbox() {
        var changed = false
        synchronized(messages) { messages.values.flatten().filter { it.mine && it.outbox != null } }.forEach { m ->
            val n = runCatching { net.publish(JSONObject(m.outbox!!)) }.getOrDefault(0)
            if (n > 0) { m.outbox = null; m.status = maxOf(m.status, 1); changed = true }
        }
        if (changed) { bump(); save() }
    }

    fun react(pub: String, msgId: String, emoji: String) {
        val c = contacts[pub] ?: return
        val m = msgs(pub).firstOrNull { it.id == msgId } ?: return
        val me = myPub ?: return
        if (emoji.isEmpty() || m.reactions[me] == emoji) m.reactions.remove(me) else m.reactions[me] = emoji
        bump(); save()
        enqueue { dispatch(c, payloadBase("x").put("t", msgId).put("e", m.reactions[me] ?: "")) }
    }

    fun edit(pub: String, msgId: String, text: String) {
        val c = contacts[pub] ?: return
        val m = msgs(pub).firstOrNull { it.id == msgId && it.mine && !it.deleted } ?: return
        val t = text.trim(); if (t.isEmpty() || t == m.text) return
        m.text = t; m.edited = true; bump(); save()
        enqueue { dispatch(c, payloadBase("e").put("t", msgId).put("b", t)) }
    }

    fun delete(pub: String, msgId: String, forEveryone: Boolean) {
        val c = contacts[pub] ?: return
        val list = synchronized(messages) { messages[pub] } ?: return
        val m = list.firstOrNull { it.id == msgId } ?: return
        if (forEveryone && m.mine) {
            m.deleted = true; m.text = ""; m.reactions.clear()
            enqueue { dispatch(c, payloadBase("d").put("t", msgId)) }
        } else synchronized(messages) { list.remove(m) }
        bump(); save()
    }

    fun setDisappear(pub: String, seconds: Int) {
        val c = contacts[pub] ?: return
        c.disappear = seconds
        systemNote(pub, if (seconds == 0) "Disappearing messages turned off" else "Messages now disappear after ${describe(seconds)}")
        enqueue { dispatch(c, payloadBase("dt").put("x", seconds)) }
        save(); bump()
    }

    fun clearChat(pub: String) { synchronized(messages) { messages[pub]?.clear() }; contacts[pub]?.unread = 0; save(); bump() }

    fun markRead(pub: String) {
        val c = contacts[pub] ?: return
        val unread = msgs(pub).filter { !it.mine && !it.system && it.status < 3 }
        c.unread = 0
        if (unread.isNotEmpty()) {
            unread.forEach { it.status = 3 }
            if (sendReadReceipts && !c.blocked) enqueue { dispatch(c, payloadBase("r").put("s", "r").put("ids", JSONArray(unread.map { it.id }))) }
            save()
        }
        bump()
    }

    fun describe(sec: Int) = when { sec % 604800 == 0 -> "${sec / 604800} week"; sec % 86400 == 0 -> "${sec / 86400} day"; sec % 3600 == 0 -> "${sec / 3600} hour"; sec % 60 == 0 -> "${sec / 60} minute"; else -> "$sec seconds" }.let { if (it.startsWith("1 ")) it else it + "s" }

    private fun systemNote(pub: String, text: String) {
        synchronized(messages) { messages.getOrPut(pub) { ArrayList() }.add(Msg(newId(), false, now(), text, null, 3, system = true)) }
    }

    private fun sweep() {
        val t = now(); var changed = false
        synchronized(messages) { messages.values.forEach { l -> if (l.removeAll { it.expiresAt in 1..t }) changed = true } }
        if (changed) { bump(); save() }
    }

    // ───────────────────────── receiving ─────────────────────────
    suspend fun onEvent(ev: JSONObject) = lock.withLock { onEventLocked(ev) }

    private suspend fun onEventLocked(ev: JSONObject) {
        val id = ev.optString("id")
        synchronized(seen) { if (!seen.add(id)) return }
        if (!NostrWire.verify(ev)) return
        val me = myPub ?: return
        if (NostrWire.tagsOf(ev).none { it.size > 1 && it[0] == "p" && it[1] == me }) return
        lastSeen = maxOf(lastSeen, ev.optLong("created_at"))
        val packet = runCatching { Base64.getDecoder().decode(ev.getString("content")) }.getOrNull() ?: return
        if (packet.size < 10) return
        val identity = identity ?: return
        var rec: SessRec? = null; var owner: Contact? = null; var fresh: SessRec? = null
        val inner: ByteArray
        if (packet[0].toInt() == 0) {
            if (packet.size < 66 + 56) return
            val ik = packet.copyOfRange(1, 33); val ek = packet.copyOfRange(33, 65)
            inner = packet.copyOfRange(65, packet.size)
            val sid = sidOf(ik, ek)
            owner = contacts.values.firstOrNull { c -> c.sessions.any { it.sid == sid } }
            rec = owner?.sessions?.firstOrNull { it.sid == sid }
            if (rec == null) {
                owner = contacts.values.firstOrNull { it.ik == ik.toHex() }
                val s = runCatching { Session.respond(identity, ik, ek) }.getOrNull() ?: return
                fresh = SessRec(sid, s, now(), 0L, null, null); rec = fresh
            }
        } else {
            val sid = packet.copyOfRange(1, 9).toHex()
            inner = packet.copyOfRange(9, packet.size)
            owner = contacts.values.firstOrNull { c -> c.sessions.any { it.sid == sid } }
            rec = owner?.sessions?.firstOrNull { it.sid == sid }
            if (rec == null) { pendingFor(sid)?.let { r -> handlePending(r, inner, sid); return }; return }
        }
        val plain = rec!!.session.decrypt(inner) ?: return
        val p = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        val fromPub = p.optString("fn")
        if (owner != null) {
            if (fromPub != owner.pub) return
            if (fresh != null) owner.sessions.add(fresh)
            rec.lastRecv = now()
            if (owner.blocked) return
            apply(owner, p)
        } else {
            // someone new: confirm their key really belongs to the Atlas ID they claim before showing anything
            if (fromPub.length != 64 || contacts.containsKey(fromPub)) return
            val found = lookupId(fromPub) ?: return
            if (found.bundle.ik.toHex() != packet.copyOfRange(1, 33).toHex()) return
            rec.lastRecv = now()
            val c = Contact(fromPub, p.optString("nm").ifEmpty { found.name }.ifEmpty { fromPub.take(8) }, found.bundle.ik.toHex(), found.bundle.spk.toHex(), found.bundle.sig.toHex())
            c.sessions.add(rec)
            val existing = requests.firstOrNull { it.contact.pub == fromPub }
            val req = existing ?: ChatRequest(c, ArrayList(), now()).also { requests.add(it) }
            if (existing != null) existing.contact.sessions.let { if (it.none { s -> s.sid == rec.sid }) it.add(rec) }
            if (p.optString("k") == "t") req.msgs.add(Msg(p.optString("id"), false, p.optLong("ts", now()), p.optString("b"), p.optString("re").ifEmpty { null }, 2))
            notify("Message request", "${c.name} wants to chat", "")
            save(); bump()
        }
        save()
    }

    private fun pendingFor(sid: String): ChatRequest? = requests.firstOrNull { r -> r.contact.sessions.any { it.sid == sid } }

    private fun handlePending(r: ChatRequest, inner: ByteArray, sid: String) {
        val rec = r.contact.sessions.first { it.sid == sid }
        val plain = rec.session.decrypt(inner) ?: return
        val p = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        if (p.optString("fn") != r.contact.pub) return
        if (p.optString("k") == "t") r.msgs.add(Msg(p.optString("id"), false, p.optLong("ts", now()), p.optString("b"), p.optString("re").ifEmpty { null }, 2))
        save(); bump()
    }

    private fun apply(c: Contact, p: JSONObject) {
        val pub = c.pub
        val list = synchronized(messages) { messages.getOrPut(pub) { ArrayList() } }
        when (p.optString("k")) {
            "t" -> {
                val id = p.optString("id"); if (id.isEmpty() || list.any { it.id == id }) return
                val x = p.optInt("x")
                val m = Msg(id, false, p.optLong("ts", now()).coerceAtMost(now()), p.optString("b"), p.optString("re").ifEmpty { null }, if (openChat == pub) 3 else 2,
                    expiresAt = if (x > 0) now() + x * 1000L else 0L)
                if (p.optString("mm").isNotEmpty()) {
                    m.mediaMime = p.optString("mm"); m.mediaName = p.optString("mn").ifEmpty { null }; m.mediaSize = p.optLong("mz")
                    val img = p.optString("img")
                    if (img.isNotEmpty()) runCatching {
                        val bytes = Base64.getDecoder().decode(img)
                        mediaDir?.let { d -> d.mkdirs(); java.io.File(d, m.id).apply { writeBytes(bytes) }.also { fl -> m.mediaPath = fl.absolutePath } }
                    } else {
                        m.mediaUrl = p.optString("mu").ifEmpty { null }; m.mediaKey = p.optString("mk").ifEmpty { null }; m.mediaIv = p.optString("mv").ifEmpty { null }
                    }
                }
                synchronized(messages) { list.add(m) }
                c.lastTs = now()
                if (p.optString("nm").isNotEmpty() && c.name == c.pub.take(8)) c.name = p.optString("nm")
                if (openChat == pub) { if (sendReadReceipts) enqueue { dispatch(c, payloadBase("r").put("s", "r").put("ids", JSONArray(listOf(id)))) } }
                else {
                    c.unread++
                    enqueue { dispatch(c, payloadBase("r").put("s", "d").put("ids", JSONArray(listOf(id)))) }
                    if (!c.muted) notify(c.name, (if (m.text.isNotEmpty()) m.text else if (m.hasMedia) "📎 " + (m.mediaName ?: "Media") else "").take(120), pub)
                }
            }
            "r" -> {
                val level = if (p.optString("s") == "r") 3 else 2
                val ids = p.optJSONArray("ids") ?: return
                val set = (0 until ids.length()).map { ids.getString(it) }.toSet()
                synchronized(messages) { list.filter { it.mine && it.id in set }.forEach { it.status = maxOf(it.status, level) } }
            }
            "x" -> list.firstOrNull { it.id == p.optString("t") }?.let { m ->
                val e = p.optString("e"); if (e.isEmpty()) m.reactions.remove(pub) else m.reactions[pub] = e.take(8)
            }
            "e" -> list.firstOrNull { it.id == p.optString("t") && !it.mine && !it.deleted }?.let { it.text = p.optString("b"); it.edited = true }
            "d" -> list.firstOrNull { it.id == p.optString("t") && !it.mine }?.let { it.deleted = true; it.text = ""; it.reactions.clear() }
            "dt" -> { val s = p.optInt("x"); c.disappear = s; systemNote(pub, if (s == 0) "${c.name} turned off disappearing messages" else "${c.name} set messages to disappear after ${describe(s)}") }
        }
        bump()
    }

    companion object {
        const val KIND_MSG = 1059
        const val KIND_BUNDLE = 30078
        const val D_TAG = "atlas-chat"
    }
}
