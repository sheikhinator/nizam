package com.nizam.app.feature.atlaschat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps everything (your keys, contacts, messages) in one file sealed with a key that lives inside the phone's secure hardware. */
class KeystorePersist(ctx: Context, private val name: String = "atlaschat.vault") : Persist {
    private val file = File(ctx.filesDir, name)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    @Synchronized override fun load(): ByteArray? = runCatching {
        if (!file.exists()) return null
        val d = file.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, d.copyOf(12)))
        c.doFinal(d, 12, d.size - 12)
    }.getOrNull()

    @Synchronized override fun save(data: ByteArray) {
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            val tmp = File(file.parentFile, "$name.tmp")
            tmp.writeBytes(c.iv + c.doFinal(data))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object { private const val ALIAS = "atlas_chat_vault_v1" }
}

object ChatNotifier {
    private const val CHANNEL = "atlas_messenger"
    fun show(c: Context, title: String, body: String, pub: String) {
        runCatching {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atlas Messenger", NotificationManager.IMPORTANCE_HIGH))
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val intent = c.packageManager.getLaunchIntentForPackage(c.packageName)
            val pi = PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_HIGH).build()
            nm.notify(pub.hashCode(), n)
        }
    }
}

/** One messenger for the whole app. */
object AtlasChat {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var inst: ChatEngine? = null
    @Volatile var appInForeground = false

    fun get(c: Context): ChatEngine {
        inst?.let { return it }
        synchronized(this) {
            inst?.let { return it }
            val app = c.applicationContext
            val e = ChatEngine(RelayPool(), KeystorePersist(app), scope, { t, b, p -> if (!appInForeground || p.isEmpty()) ChatNotifier.show(app, t, b, p.ifEmpty { "request" }) })
            e.load(); e.start()
            inst = e
            if (e.registered) schedulePolling(app)
            return e
        }
    }

    @Volatile private var social: SocialEngine? = null
    fun social(c: Context): SocialEngine {
        social?.let { return it }
        synchronized(this) {
            social?.let { return it }
            val e = SocialEngine(get(c), KeystorePersist(c.applicationContext, "atlassocial.vault"), scope)
            e.load(); e.start(); social = e
            return e
        }
    }

    fun schedulePolling(c: Context) {
        runCatching {
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("atlas_chat_poll", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ChatPollWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}

/** Wakes up now and then to fetch anything that arrived while the app was closed, and shows a notification. */
class ChatPollWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val e = AtlasChat.get(applicationContext)
        if (!e.registered) return Result.success()
        delay(12_000)
        e.saveNow()
        return Result.success()
    }
}
