package com.nizam.app.feature.future

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import kotlinx.coroutines.delay
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Time-based one-time codes (RFC 6238), the same codes Google Authenticator shows. */
object Totp {
    data class Account(val id: String, val issuer: String, val name: String, val secret: String, val digits: Int = 6, val period: Int = 30, val algo: String = "SHA1")

    fun store(c: Context) = SealedList(c, "totp")
    fun accounts(c: Context) = store(c).all().map { Account(it.optString("id"), it.optString("issuer"), it.optString("name"), it.optString("secret"),
        it.optInt("digits", 6), it.optInt("period", 30), it.optString("algo", "SHA1")) }
    fun add(c: Context, a: Account) = store(c).add(JSONObject().put("id", a.id).put("issuer", a.issuer).put("name", a.name).put("secret", a.secret)
        .put("digits", a.digits).put("period", a.period).put("algo", a.algo))

    fun base32(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = s.uppercase().filter { it in alphabet }
        var buffer = 0; var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in clean) {
            buffer = (buffer shl 5) or alphabet.indexOf(ch); bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
    private fun base32Encode(b: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder(); var buffer = 0; var bits = 0
        for (x in b) { buffer = (buffer shl 8) or (x.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(alphabet[(buffer shr (bits - 5)) and 31]); bits -= 5 } }
        if (bits > 0) sb.append(alphabet[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun code(a: Account, now: Long = System.currentTimeMillis()): String {
        val counter = now / 1000 / a.period
        val msg = java.nio.ByteBuffer.allocate(8).putLong(counter).array()
        val alg = "Hmac" + a.algo.uppercase().replace("-", "")
        val h = Mac.getInstance(alg).apply { init(SecretKeySpec(base32(a.secret), alg)) }.doFinal(msg)
        val o = h.last().toInt() and 0xF
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var mod = 1; repeat(a.digits) { mod *= 10 }
        return (bin % mod).toString().padStart(a.digits, '0')
    }

    /** otpauth://totp/Issuer:name?secret=…  or a Google Authenticator export (otpauth-migration://). */
    fun parse(text: String): List<Account> {
        val t = text.trim()
        if (t.startsWith("otpauth-migration://")) return migration(t)
        if (!t.startsWith("otpauth://totp", true)) return emptyList()
        val u = Uri.parse(t)
        val label = Uri.decode(u.path?.trimStart('/').orEmpty())
        val issuer = u.getQueryParameter("issuer") ?: label.substringBefore(':', "")
        val secret = u.getQueryParameter("secret") ?: return emptyList()
        return listOf(Account(System.nanoTime().toString(), issuer, label.substringAfter(':').trim(), secret,
            u.getQueryParameter("digits")?.toIntOrNull() ?: 6, u.getQueryParameter("period")?.toIntOrNull() ?: 30, u.getQueryParameter("algorithm") ?: "SHA1"))
    }

    /** Google Authenticator's "Transfer accounts" QR: a small protobuf of every account. */
    private fun migration(t: String): List<Account> {
        val data = Uri.parse(t).getQueryParameter("data") ?: return emptyList()
        val bytes = Base64.decode(data.replace(' ', '+'), Base64.DEFAULT)
        val out = mutableListOf<Account>()
        fun fields(b: ByteArray, on: (Int, Int, Any) -> Unit) {
            var i = 0
            fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl s); if (x < 0x80) return r; s += 7 } }
            while (i < b.size) {
                val tag = varint().toInt(); val field = tag shr 3; val wire = tag and 7
                when (wire) {
                    0 -> on(field, 0, varint())
                    2 -> { val len = varint().toInt(); on(field, 2, b.copyOfRange(i, i + len)); i += len }
                    1 -> i += 8
                    5 -> i += 4
                    else -> return
                }
            }
        }
        fields(bytes) { f, w, v ->
            if (f == 1 && w == 2) {
                var secret = ByteArray(0); var name = ""; var issuer = ""; var algo = "SHA1"; var digits = 6; var type = 2
                fields(v as ByteArray) { g, _, x ->
                    when (g) {
                        1 -> secret = x as ByteArray
                        2 -> name = String(x as ByteArray)
                        3 -> issuer = String(x as ByteArray)
                        4 -> algo = when ((x as Long).toInt()) { 2 -> "SHA256"; 3 -> "SHA512"; else -> "SHA1" }
                        5 -> digits = if ((x as Long).toInt() == 2) 8 else 6
                        6 -> type = (x as Long).toInt()
                    }
                }
                if (type == 2 && secret.isNotEmpty()) out += Account(System.nanoTime().toString() + out.size, issuer, name.substringAfter(':'), base32Encode(secret), digits, 30, algo)
            }
        }
        return out
    }
}

@Composable
fun AuthenticatorScreen() {
    val c = LocalContext.current
    var accounts by remember { mutableStateOf(Totp.accounts(c)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scanning by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { now = System.currentTimeMillis(); delay(500) } } }
    fun addFrom(text: String) {
        val found = Totp.parse(text)
        if (found.isEmpty()) { Feedback.show("That isn't a 2FA code QR"); return }
        found.forEach { Totp.add(c, it) }; accounts = Totp.accounts(c)
        Feedback.show(if (found.size == 1) "Added ${found[0].issuer.ifBlank { found[0].name }}" else "Imported ${found.size} accounts")
    }
    if (scanning) {
        BackHandler { scanning = false }
        Box(Modifier.fillMaxSize()) {
            QrScanner(Modifier.fillMaxSize()) { code -> scanning = false; addFrom(code) }
            Text("Point at the QR code on the website — or at Google Authenticator's Transfer accounts screen",
                color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Authenticator", "Two-factor codes, sealed in this phone's secure chip")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scanning = true }) { Text("Scan QR") }
            OutlinedButton(onClick = { manual = !manual }) { Text("Enter a key") }
        }
        if (manual) {
            var issuer by remember { mutableStateOf("") }
            var key by remember { mutableStateOf("") }
            Group(Modifier.padding(top = 10.dp)) {
                OutlinedTextField(issuer, { issuer = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Account (e.g. Google — you@gmail.com)") })
                OutlinedTextField(key, { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Setup key") })
                Button(enabled = key.length >= 16, onClick = {
                    runCatching { Totp.base32(key); Totp.add(c, Totp.Account(System.nanoTime().toString(), issuer.substringBefore('—').trim(), issuer.substringAfter('—', "").trim(), key.replace(" ", ""))) }
                        .onSuccess { accounts = Totp.accounts(c); manual = false; Feedback.show("Added") }.onFailure { Feedback.show("That key doesn't look right") }
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Add") }
            }
        }
        Label(if (accounts.isEmpty()) "No accounts yet" else "Tap a code to copy it")
        accounts.forEach { a ->
            val code = runCatching { Totp.code(a, now) }.getOrDefault("------")
            val left = a.period - ((now / 1000) % a.period)
            Group(Modifier.padding(vertical = 4.dp).clickable {
                c.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                Feedback.show("Copied")
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.issuer.ifBlank { a.name }, style = MaterialTheme.typography.titleSmall)
                        if (a.issuer.isNotBlank() && a.name.isNotBlank()) Text(a.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(code.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                            color = if (left <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { left / a.period.toFloat() }, modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
                        Text("$left", style = MaterialTheme.typography.labelSmall)
                    }
                    Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp).clickable {
                        Totp.store(c).remove(a.id); accounts = Totp.accounts(c); Feedback.show("Removed ${a.issuer.ifBlank { a.name }}") }.padding(6.dp))
                }
            }
        }
        Text("Moving from Google Authenticator: open it › Transfer accounts › Export, then Scan QR here. Codes never leave this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        Spacer(Modifier.height(40.dp))
    }
}
