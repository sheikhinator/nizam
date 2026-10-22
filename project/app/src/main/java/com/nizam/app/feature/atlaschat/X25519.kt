package com.nizam.app.feature.atlaschat

import java.math.BigInteger
import java.security.SecureRandom

/** X25519 key agreement (RFC 7748), written out here so the app needs no extra crypto library. */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val A24 = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    private fun decodeLE(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
    private fun encodeLE(v: BigInteger): ByteArray {
        val raw = v.toByteArray().reversedArray()
        return ByteArray(32).also { System.arraycopy(raw, 0, it, 0, minOf(raw.size, 32)) }
    }

    fun newPrivateKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    fun publicKey(priv: ByteArray): ByteArray = scalarMult(priv, BASE)

    fun sharedSecret(priv: ByteArray, theirPub: ByteArray): ByteArray {
        val out = scalarMult(priv, theirPub)
        require(out.any { it.toInt() != 0 }) { "invalid public key" }
        return out
    }

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        require(k.size == 32 && u.size == 32)
        val kc = k.copyOf().also { it[0] = (it[0].toInt() and 248).toByte(); it[31] = ((it[31].toInt() and 127) or 64).toByte() }
        val uc = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val kk = decodeLE(kc)
        val x1 = decodeLE(uc).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = kk.testBit(t)
            if (swap != bit) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = bit
            val a = (x2 + z2).mod(P); val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P); val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P); val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P); val cb = (c * b).mod(P)
            val s = (da + cb).mod(P); val df = (da - cb).mod(P)
            x3 = (s * s).mod(P)
            z3 = (x1 * (df * df).mod(P)).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e).mod(P)).mod(P)
        }
        if (swap) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
        return encodeLE((x2 * z2.modPow(P - BigInteger.valueOf(2), P)).mod(P))
    }
}
