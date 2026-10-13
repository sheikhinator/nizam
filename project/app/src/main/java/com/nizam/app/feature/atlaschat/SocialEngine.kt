package com.nizam.app.feature.atlaschat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class Post(val id: String, val author: String, val ts: Long, val text: String, val img: String?, val parent: String?, val story: Boolean = false, val expires: Long = 0L)
class Profile(val name: String, val about: String)

/**
 * Atlas Social: public posts, photos, stories, likes, comments and follows, carried by the same relays as the messenger
 * and signed with the same Atlas ID. Posts are public to everyone using Atlas Social; private talk belongs in Messenger.
 */
class SocialEngine(private val chat: ChatEngine, private val persist: Persist, private val scope: CoroutineScope, private val now: () -> Long = { System.currentTimeMillis() }) {
    val rev = MutableStateFlow(0)
    val posts = ConcurrentHashMap<String, Post>()
    val likes = ConcurrentHashMap<String, MutableSet<String>>()
    val profiles = ConcurrentHashMap<String, Profile>()
    val following = LinkedHashSet<String>()
    val blocked = LinkedHashSet<String>()
    val hidden = LinkedHashSet<String>()
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    var about = ""; private set
    private var started = false
    private var saveJob: Job? = null
    private fun bump() { rev.value = rev.value + 1 }
    val me: String get() = chat.myPub.orEmpty()

    fun load() {
        val o = persist.load()?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: return
        about = o.optString("about")
        fun fill(k: String, into: MutableSet<String>) { o.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) into.add(a.getString(i)) } }
        fill("fo", following); fill("bl", blocked); fill("hi", hidden)
    }

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(400); persist.save(JSONObject().put("about", about).put("fo", JSONArray(following.toList())).put("bl", JSONArray(blocked.toList())).put("hi", JSONArray(hidden.toList())).toString().toByteArray()) }
    }

    fun start() {
        if (started || !chat.registered) return
        started = true
        val net = chat.net
        val prev = net.onEvent
        net.onEvent = { sub, ev -> prev?.invoke(sub, ev); if (sub.startsWith("soc")) scope.launch(Dispatchers.Default) { onEvent(ev) } }
        val week = now() / 1000 - 7 * 86400
        net.subscribe("soc-feed", JSONObject().put("kinds", JSONArray(listOf(1))).put("#t", JSONArray(listOf(TAG, STORY_TAG))).put("since", week).put("limit", 300))
        net.subscribe("soc-react", JSONObject().put("kinds", JSONArray(listOf(7, 5))).put("#t", JSONArray(listOf(TAG))).put("since", week).put("limit", 1000))
        scope.launch { delay(1500); publishProfile() }
        scope.launch { while (isActive) { delay(4000); askProfiles(); sweep() } }
    }

    private fun sweep() { val t = now() / 1000; if (posts.values.removeAll { it.story && it.expires in 1..t }) bump() }

    private fun askProfiles() {
        val need = (posts.values.map { it.author } + following).distinct().filter { !profiles.containsKey(it) && asked.add(it) }.take(60)
        if (need.isEmpty()) return
        scope.launch(Dispatchers.Default) {
            chat.net.query(JSONObject().put("kinds", JSONArray(listOf(0))).put("authors", JSONArray(need)).put("limit", 100), 5000).forEach { onEvent(it) }
        }
    }

    fun name(pub: String) = if (pub == me) chat.myName else profiles[pub]?.name?.ifEmpty { null } ?: chat.contacts[pub]?.name ?: pub.take(8)

    private fun onEvent(e: JSONObject) {
        val id = e.optString("id"); if (!seen.add(id)) return
        if (!NostrWire.verify(e)) return
        val author = e.optString("pubkey"); if (author in blocked) return
        val tags = NostrWire.tagsOf(e)
        fun tag(n: String) = tags.firstOrNull { it.size > 1 && it[0] == n }?.get(1)
        when (e.optInt("kind")) {
            0 -> runCatching { val c = JSONObject(e.getString("content")); profiles[author] = Profile(c.optString("name").take(30), c.optString("about").take(200)) }
            1 -> {
                val isStory = tags.any { it.size > 1 && it[0] == "t" && it[1] == STORY_TAG }
                if (!isStory && tags.none { it.size > 1 && it[0] == "t" && it[1] == TAG }) return
                val exp = tag("expiration")?.toLongOrNull() ?: 0L
                if (isStory && (exp == 0L || exp < now() / 1000)) return
                val img = tag("img")?.takeIf { it.length < 140_000 }
                if (id in deleted) return
                posts[id] = Post(id, author, e.optLong("created_at") * 1000, e.optString("content").take(2000), img, tag("reply"), isStory, exp)
            }
            7 -> tag("e")?.let { target -> likes.getOrPut(target) { ConcurrentHashMap.newKeySet() }.let { s -> if (e.optString("content") == "-") s.remove(author) else s.add(author) } }
            5 -> tags.filter { it.size > 1 && it[0] == "e" }.forEach { t -> posts[t[1]]?.let { p -> if (p.author == author) { posts.remove(t[1]); deleted.add(t[1]) } } }
        }
        bump()
    }

    private fun sign(kind: Int, tags: List<List<String>>, content: String): JSONObject = NostrWire.build(chat.identity!!.nostrSecret, kind, tags, content)
    private fun publish(e: JSONObject) { chat.net.publish(e); scope.launch(Dispatchers.Default) { onEvent(JSONObject(e.toString())) } }

    fun publishProfile() { if (chat.registered) chat.net.publish(sign(0, emptyList(), JSONObject().put("name", chat.myName).put("about", about).toString())) }
    fun setAbout(text: String) { about = text.take(200); save(); publishProfile(); bump() }

    fun post(text: String, imgB64: String?, story: Boolean = false) {
        if (!chat.registered || (text.isBlank() && imgB64 == null)) return
        scope.launch(Dispatchers.Default) {
            val tags = ArrayList<List<String>>()
            tags.add(listOf("t", if (story) STORY_TAG else TAG))
            if (story) tags.add(listOf("expiration", (now() / 1000 + 86400).toString()))
            if (imgB64 != null) tags.add(listOf("img", imgB64))
            publish(sign(1, tags, text.trim()))
        }
    }

    fun comment(parent: Post, text: String) {
        if (text.isBlank() || !chat.registered) return
        scope.launch(Dispatchers.Default) { publish(sign(1, listOf(listOf("t", TAG), listOf("e", parent.id, "", "reply"), listOf("reply", parent.id), listOf("p", parent.author)), text.trim())) }
    }

    fun toggleLike(p: Post) {
        if (!chat.registered) return
        val set = likes.getOrPut(p.id) { ConcurrentHashMap.newKeySet() }
        val on = me !in set
        if (on) set.add(me) else set.remove(me)
        bump()
        scope.launch(Dispatchers.Default) { chat.net.publish(sign(7, listOf(listOf("t", TAG), listOf("e", p.id), listOf("p", p.author)), if (on) "+" else "-")) }
    }

    fun delete(p: Post) {
        if (p.author != me) return
        posts.remove(p.id); deleted.add(p.id); bump()
        scope.launch(Dispatchers.Default) { chat.net.publish(sign(5, listOf(listOf("e", p.id), listOf("t", TAG)), "")) }
    }

    fun follow(pub: String, on: Boolean) { if (on) following.add(pub) else following.remove(pub); save(); bump() }
    fun block(pub: String) { blocked.add(pub); following.remove(pub); posts.values.removeAll { it.author == pub }; save(); bump() }
    fun unblock(pub: String) { blocked.remove(pub); save(); bump() }
    fun hide(id: String) { hidden.add(id); save(); bump() }

    // ── views ──
    fun feed(onlyFollowing: Boolean): List<Post> = posts.values.filter { !it.story && it.parent == null && it.id !in hidden && it.author !in blocked && (!onlyFollowing || it.author in following || it.author == me) }.sortedByDescending { it.ts }
    fun comments(id: String): List<Post> = posts.values.filter { it.parent == id && it.author !in blocked }.sortedBy { it.ts }
    fun storiesByAuthor(): Map<String, List<Post>> = posts.values.filter { it.story && it.author !in blocked }.sortedBy { it.ts }.groupBy { it.author }
    fun byAuthor(pub: String): List<Post> = feed(false).filter { it.author == pub }

    companion object { const val TAG = "atlassocial"; const val STORY_TAG = "atlasstory" }
}
