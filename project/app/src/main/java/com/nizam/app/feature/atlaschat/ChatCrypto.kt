package com.nizam.app.feature.atlaschat

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.fromHex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Key derivation and encryption building blocks (HKDF-SHA256, HMAC-SHA256, AES-256-GCM, X25519). */
internal object Prim {
    private val rnd = SecureRandom()
    fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { d -> parts.forEach { d.update(it) } }.digest()
    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0); var pos = 0; var i = 1
        while (pos < len) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, len - pos); System.arraycopy(t, 0, out, pos, n); pos += n; i++
        }
        return out
    }
    fun dhPrivate(): ByteArray = X25519.newPrivateKey()
    fun dhPublic(priv: ByteArray): ByteArray = X25519.publicKey(priv)
    fun dh(priv: ByteArray, pub: ByteArray): ByteArray = X25519.sharedSecret(priv, pub)
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); it.updateAAD(aad) }.doFinal(pt)
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); it.updateAAD(aad) }.doFinal(ct)
}

/** What other people need to start a private chat with you. Published on the relays and signed with your Atlas ID key. */
class PreKeyBundle(val nostrPub: ByteArray, val ik: ByteArray, val spk: ByteArray, val sig: ByteArray) {
    fun valid(): Boolean = runCatching { Bip340.verify(signedBytes(spk, ik), nostrPub, sig) }.getOrDefault(false)
    companion object {
        internal fun signedBytes(spk: ByteArray, ik: ByteArray) = Prim.sha256("atlas-spk-v1".toByteArray(), spk, ik)
    }
}

/** Your long-term secrets. They never leave the phone. */
class Identity(val nostrSecret: ByteArray, val ikPriv: ByteArray, val spkPriv: ByteArray) {
    val nostrPub: ByteArray by lazy { Bip340.publicKey(nostrSecret) }
    val ikPub: ByteArray by lazy { Prim.dhPublic(ikPriv) }
    val spkPub: ByteArray by lazy { Prim.dhPublic(spkPriv) }
    fun bundle() = PreKeyBundle(nostrPub, ikPub, spkPub, Bip340.sign(PreKeyBundle.signedBytes(spkPub, ikPub), nostrSecret))
    fun toJson() = JSONObject().put("n", nostrSecret.toHex()).put("i", ikPriv.toHex()).put("s", spkPriv.toHex())
    companion object {
        fun create() = Identity(Bip340.newSecretKey(), Prim.dhPrivate(), Prim.dhPrivate())
        fun fromJson(o: JSONObject) = Identity(o.getString("n").fromHex(), o.getString("i").fromHex(), o.getString("s").fromHex())
    }
}

/** A short code both people can compare in person or on a call to be sure nobody is in the middle. */
fun safetyNumber(ikA: ByteArray, ikB: ByteArray): String {
    val (a, b) = listOf(ikA, ikB).sortedBy { it.toHex() }
    var h = Prim.sha256("atlas-safety-v1".toByteArray(), a, b)
    repeat(2000) { h = Prim.sha256(h, a, b) }
    return (0 until 6).joinToString(" ") { i ->
        val v = ByteBuffer.wrap(h, i * 4, 4).int.toLong() and 0xFFFFFFFFL
        (v % 100000).toString().padStart(5, '0')
    }
}

/**
 * One private conversation: the Double Ratchet (the same design Signal uses). Every message gets its own key, keys are
 * thrown away after use, and the keys keep changing as people reply, so a stolen key exposes neither past nor future messages.
 */
class Session private constructor(
    private var rootKey: ByteArray,
    private var dhsPriv: ByteArray,
    private var dhsPub: ByteArray,
    private var dhr: ByteArray?,
    private var cks: ByteArray?,
    private var ckr: ByteArray?,
    private var ns: Int, private var nr: Int, private var pn: Int,
    private val skipped: LinkedHashMap<String, ByteArray>,
    val ad: ByteArray,
) {
    private fun kdfRk(dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val o = Prim.hkdf(dhOut, rootKey, "atlas-ratchet-v1".toByteArray(), 64)
        return o.copyOfRange(0, 32) to o.copyOfRange(32, 64)
    }
    private fun kdfCk(ck: ByteArray) = Prim.hmac(ck, byteArrayOf(2)) to Prim.hmac(ck, byteArrayOf(1))   // next chain key, message key

    private fun keyAndNonce(mk: ByteArray): Pair<ByteArray, ByteArray> {
        val o = Prim.hkdf(mk, ByteArray(32), "atlas-msg-v1".toByteArray(), 44)
        return o.copyOfRange(0, 32) to o.copyOfRange(32, 44)
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val (next, mk) = kdfCk(cks ?: error("waiting for the other person's first message"))
        cks = next
        val header = ByteBuffer.allocate(40).put(dhsPub).putInt(pn).putInt(ns).array()
        ns++
        val (k, n) = keyAndNonce(mk)
        return header + Prim.seal(k, n, ad + header, pad(plain))
    }

    /** Returns the plain bytes, or null when the message is forged, damaged, too old or a repeat. State changes only on success. */
    fun decrypt(packet: ByteArray): ByteArray? {
        if (packet.size < 40 + 16) return null
        val backup = toJson()
        return try { decryptInner(packet) } catch (e: Exception) { restore(backup); null }
    }

    private fun decryptInner(packet: ByteArray): ByteArray? {
        val header = packet.copyOfRange(0, 40)
        val body = packet.copyOfRange(40, packet.size)
        val theirDh = header.copyOfRange(0, 32)
        val bb = ByteBuffer.wrap(header, 32, 8)
        val theirPn = bb.int; val theirN = bb.int
        if (theirPn < 0 || theirN < 0 || theirN > MAX_SKIP + 1000 || theirPn > MAX_SKIP + 1000) return null
        val id = theirDh.toHex() + ":" + theirN
        skipped.remove(id)?.let { mk ->
            val (k, n) = keyAndNonce(mk)
            return unpad(Prim.open(k, n, ad + header, body))
        }
        if (dhr == null || !theirDh.contentEquals(dhr!!)) {
            skipKeys(theirPn)
            ratchetStep(theirDh)
        }
        skipKeys(theirN)
        val (next, mk) = kdfCk(ckr ?: return null)
        ckr = next; nr++
        val (k, n) = keyAndNonce(mk)
        return unpad(Prim.open(k, n, ad + header, body))
    }

    private fun skipKeys(until: Int) {
        val chain = ckr ?: return
        if (until - nr > MAX_SKIP) throw IllegalStateException("too many skipped messages")
        var ck = chain
        while (nr < until) {
            val (next, mk) = kdfCk(ck)
            skipped[dhr!!.toHex() + ":" + nr] = mk
            while (skipped.size > MAX_STORED_SKIPPED) skipped.remove(skipped.keys.first())
            ck = next; nr++
        }
        ckr = ck
    }

    private fun ratchetStep(theirDh: ByteArray) {
        pn = ns; ns = 0; nr = 0
        dhr = theirDh
        val (rk1, ck1) = kdfRk(Prim.dh(dhsPriv, theirDh))
        rootKey = rk1; ckr = ck1
        dhsPriv = Prim.dhPrivate(); dhsPub = Prim.dhPublic(dhsPriv)
        val (rk2, ck2) = kdfRk(Prim.dh(dhsPriv, theirDh))
        rootKey = rk2; cks = ck2
    }

    private fun restore(json: JSONObject) {
        val s = fromJson(json)
        rootKey = s.rootKey; dhsPriv = s.dhsPriv; dhsPub = s.dhsPub; dhr = s.dhr; cks = s.cks; ckr = s.ckr
        ns = s.ns; nr = s.nr; pn = s.pn; skipped.clear(); skipped.putAll(s.skipped)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("rk", rootKey.toHex()).put("dp", dhsPriv.toHex()).put("dq", dhsPub.toHex())
        .put("dr", dhr?.toHex() ?: "").put("cs", cks?.toHex() ?: "").put("cr", ckr?.toHex() ?: "")
        .put("ns", ns).put("nr", nr).put("pn", pn).put("ad", ad.toHex())
        .put("sk", JSONObject().also { o -> skipped.forEach { (k, v) -> o.put(k, v.toHex()) } })

    companion object {
        const val MAX_SKIP = 1000
        const val MAX_STORED_SKIPPED = 2000
        private const val BLOCK = 128

        private fun pad(p: ByteArray): ByteArray {
            val total = ((p.size + 4 + BLOCK - 1) / BLOCK) * BLOCK
            return ByteBuffer.allocate(total).putInt(p.size).put(p).array()
        }
        private fun unpad(b: ByteArray): ByteArray? {
            if (b.size < 4) return null
            val len = ByteBuffer.wrap(b).int
            return if (len < 0 || len > b.size - 4) null else b.copyOfRange(4, 4 + len)
        }

        fun fromJson(o: JSONObject): Session {
            fun opt(k: String) = o.optString(k).takeIf { it.isNotEmpty() }?.fromHex()
            val sk = LinkedHashMap<String, ByteArray>()
            o.optJSONObject("sk")?.let { m -> m.keys().forEach { k -> sk[k] = m.getString(k).fromHex() } }
            return Session(o.getString("rk").fromHex(), o.getString("dp").fromHex(), o.getString("dq").fromHex(), opt("dr"), opt("cs"), opt("cr"),
                o.getInt("ns"), o.getInt("nr"), o.getInt("pn"), sk, o.getString("ad").fromHex())
        }

        // ── starting a conversation (X3DH: three Diffie-Hellman exchanges mixed into one shared secret) ──
        private val F = ByteArray(32) { 0xFF.toByte() }
        private fun master(vararg dhs: ByteArray) = Prim.hkdf(dhs.fold(F) { a, b -> a + b }, ByteArray(32), "atlas-x3dh-v1".toByteArray(), 32)

        /** First message to someone: returns the session and the one-time key the other side needs to derive the same secret. */
        fun initiate(me: Identity, their: PreKeyBundle): Pair<Session, ByteArray> {
            require(their.valid()) { "This Atlas ID's key bundle is not signed correctly" }
            val ek = Prim.dhPrivate(); val ekPub = Prim.dhPublic(ek)
            val sk = master(Prim.dh(me.ikPriv, their.spk), Prim.dh(ek, their.ik), Prim.dh(ek, their.spk))
            val dhs = Prim.dhPrivate()
            val s = Session(sk, dhs, Prim.dhPublic(dhs), their.spk, null, null, 0, 0, 0, LinkedHashMap(), me.ikPub + their.ik)
            val (rk, ck) = s.kdfRk(Prim.dh(dhs, their.spk))
            s.rootKey = rk; s.cks = ck
            return s to ekPub
        }

        fun respond(me: Identity, theirIk: ByteArray, theirEk: ByteArray): Session {
            val sk = master(Prim.dh(me.spkPriv, theirIk), Prim.dh(me.ikPriv, theirEk), Prim.dh(me.spkPriv, theirEk))
            return Session(sk, me.spkPriv, me.spkPub, null, null, null, 0, 0, 0, LinkedHashMap(), theirIk + me.ikPub)
        }
    }
}
