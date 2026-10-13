package com.nizam.app.feature.atlaschat

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** BIP-340 Schnorr signatures on secp256k1: the signature scheme relays expect on every event. */
object Bip340 {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Pt(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16))
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val rnd = SecureRandom()

    private class Pt(val x: BigInteger, val y: BigInteger)

    private fun add(a: Pt?, b: Pt?): Pt? {
        if (a == null) return b
        if (b == null) return a
        val l = if (a.x == b.x) {
            if ((a.y + b.y).mod(P).signum() == 0) return null
            THREE * a.x * a.x * (TWO * a.y).modInverse(P)
        } else (b.y - a.y) * (b.x - a.x).modInverse(P)
        val lam = l.mod(P)
        val x3 = (lam * lam - a.x - b.x).mod(P)
        return Pt(x3, (lam * (a.x - x3) - a.y).mod(P))
    }

    private fun mul(k: BigInteger, p: Pt): Pt? {
        var r: Pt? = null
        var q: Pt? = p
        var e = k
        while (e.signum() > 0) {
            if (e.testBit(0)) r = add(r, q)
            q = add(q, q)
            e = e.shiftRight(1)
        }
        return r
    }

    private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { d -> parts.forEach { d.update(it) } }.digest()
    private fun tagged(tag: String, vararg parts: ByteArray): ByteArray { val t = sha(tag.toByteArray()); return sha(t, t, *parts) }
    private fun int(b: ByteArray) = BigInteger(1, b)
    private fun bytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val len = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - len, out, 32 - len, len)
        return out
    }

    private fun lift(x: BigInteger): Pt? {
        if (x >= P) return null
        val c = (x.pow(3) + BigInteger.valueOf(7)).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(TWO, P) != c) return null
        return Pt(x, if (y.testBit(0)) P - y else y)
    }

    fun newSecretKey(): ByteArray {
        while (true) {
            val b = ByteArray(32).also { rnd.nextBytes(it) }
            val d = int(b)
            if (d.signum() > 0 && d < N) return b
        }
    }

    fun publicKey(secret: ByteArray): ByteArray {
        val d = int(secret)
        require(d.signum() > 0 && d < N) { "bad secret key" }
        return bytes32(mul(d, G)!!.x)
    }

    fun sign(msg32: ByteArray, secret: ByteArray, aux: ByteArray = ByteArray(32).also { rnd.nextBytes(it) }): ByteArray {
        val d0 = int(secret)
        require(d0.signum() > 0 && d0 < N) { "bad secret key" }
        val pt = mul(d0, G)!!
        val d = if (pt.y.testBit(0)) N - d0 else d0
        val px = bytes32(pt.x)
        val t = bytes32(d.xor(int(tagged("BIP0340/aux", aux))))
        val k0 = int(tagged("BIP0340/nonce", t, px, msg32)).mod(N)
        require(k0.signum() != 0) { "bad nonce" }
        val r = mul(k0, G)!!
        val k = if (r.y.testBit(0)) N - k0 else k0
        val rx = bytes32(r.x)
        val e = int(tagged("BIP0340/challenge", rx, px, msg32)).mod(N)
        return rx + bytes32((k + e * d).mod(N))
    }

    fun verify(msg32: ByteArray, pub32: ByteArray, sig64: ByteArray): Boolean {
        if (pub32.size != 32 || sig64.size != 64 || msg32.size != 32) return false
        val p = lift(int(pub32)) ?: return false
        val r = int(sig64.copyOfRange(0, 32))
        val s = int(sig64.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(tagged("BIP0340/challenge", sig64.copyOfRange(0, 32), pub32, msg32)).mod(N)
        val res = add(mul(s, G), mul(N - e, p)) ?: return false
        return !res.y.testBit(0) && res.x == r
    }
}
