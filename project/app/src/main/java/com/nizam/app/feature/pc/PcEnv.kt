package com.nizam.app.feature.pc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nizam.app.feature.linux.LinuxEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Atlas's PC: a full Linux desktop on the phone. Any distro (pulled as an image), a desktop of your choice,
 * shown through a built-in VNC screen that you or Atlas can drive.
 */
object PcEnv {
    data class Choice(val id: String, val title: String, val image: String, val note: String, val emoji: String)
    val DISTROS = listOf(
        Choice("ubuntu", "Ubuntu", "ubuntu:24.04", "Most popular · apt", "🟠"),
        Choice("debian", "Debian", "debian:bookworm", "Rock solid · apt · Firefox ESR", "🌀"),
        Choice("kali", "Kali Linux", "kalilinux/kali-rolling", "Security tools · apt", "🐉"),
        Choice("fedora", "Fedora", "fedora:latest", "Newest software · dnf", "🎩"),
        Choice("opensuse", "openSUSE Tumbleweed", "opensuse/tumbleweed", "Rolling · zypper", "🦎"),
        Choice("arch", "Arch Linux ARM", "menci/archlinuxarm:latest", "Rolling, minimal · pacman", "🔷"),
        Choice("alpine", "Alpine", "alpine:latest", "Tiny · apk", "🏔"),
        Choice("rocky", "Rocky Linux", "rockylinux:9", "Enterprise · dnf", "⛰"),
    )
    val DESKTOPS = listOf(
        "xfce" to "XFCE · light, classic (recommended)", "lxqt" to "LXQt · lightest", "mate" to "MATE · traditional",
        "kde" to "KDE Plasma · beautiful, heavy", "none" to "No desktop · terminal only")
    val SIZES = listOf("1280x720", "1600x900", "1920x1080", "1080x1920")

    fun base(c: Context) = File(c.filesDir, "pc").apply { mkdirs() }
    fun root(c: Context, id: String) = File(base(c), id)
    private fun meta(c: Context, id: String) = File(root(c, id), ".atlas-pc.json")
    fun info(c: Context, id: String): JSONObject? = runCatching { JSONObject(meta(c, id).readText()) }.getOrNull()
    fun all(c: Context): List<JSONObject> = base(c).listFiles().orEmpty().mapNotNull { info(c, it.name) }.sortedByDescending { it.optLong("used") }
    fun ready(c: Context, id: String) = info(c, id)?.optBoolean("ready") == true
    fun preferred(c: Context): JSONObject? = all(c).firstOrNull { it.optBoolean("ready") }

    data class Progress(val id: String, val stage: String, val pct: Int = -1, val log: String = "", val error: String = "")
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    // ── installing ─────────────────────────────────────────────────────────────
    suspend fun install(c: Context, name: String, image: String, desktop: String, size: String): Boolean = withContext(Dispatchers.IO) {
        val id = name.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifBlank { "pc" } + "-" + (System.currentTimeMillis() % 100000)
        val r = root(c, id)
        try {
            if (!LinuxEnv.available(c)) error("This build of Atlas doesn't include the Linux runtime.")
            keepAlive(c, true)
            _progress.value = Progress(id, "Finding $image")
            r.mkdirs()
            Oci.pull(image, r) { st, p -> _progress.value = Progress(id, st, p) }
            prepare(r)
            meta(c, id).writeText(JSONObject().put("id", id).put("name", name).put("image", image).put("desktop", desktop).put("size", size)
                .put("ready", false).put("created", System.currentTimeMillis()).toString())
        } catch (e: Throwable) {
            keepAlive(c, false)
            _progress.value = Progress(id, "Couldn't finish", -1, error = e.message ?: e.javaClass.simpleName)
            return@withContext false
        }
        finishSetup(c, id)
    }

    /**
     * Installs the desktop. Android sometimes kills a long install when Atlas is in the background, so this keeps
     * a foreground service and the CPU awake, and picks up where it stopped (up to five tries) — also from "Finish setup".
     */
    suspend fun finishSetup(c: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val i = info(c, id) ?: return@withContext false
        val desktop = i.optString("desktop")
        try {
            keepAlive(c, true)
            if (desktop != "none") {
                val log = StringBuilder()
                var res = -1 to ""
                for (attempt in 1..5) {
                    _progress.value = Progress(id, "Installing the $desktop desktop, a browser and the screen server" + (if (attempt > 1) " · picking up where it stopped (try $attempt)" else "") + "…", -1, synchronized(log) { log.toString() })
                    res = shell(c, id, setupScript(desktop), 5400) { line ->
                        synchronized(log) { log.appendLine(line); if (log.length > 6000) log.delete(0, log.length - 4000) }
                        _progress.value = Progress(id, "Installing the $desktop desktop" + (if (attempt > 1) " (try $attempt)" else "") + "…", -1, synchronized(log) { log.toString() })
                    }
                    if (res.first == 0) break
                    // a network blip or a killed step: wait a moment and continue
                    kotlinx.coroutines.delay(3000)
                }
                if (res.first != 0) error("Setup stopped (exit ${res.first})" + (if (res.first == 137) " — Android stopped it. Keep Atlas open on this screen, then tap Finish setup; it continues from where it was." else "") + "\n\nThe last lines:\n" + res.second.takeLast(1200))
            }
            meta(c, id).writeText(info(c, id)!!.put("ready", true).toString())
            _progress.value = Progress(id, "Ready", 100)
            true
        } catch (e: Throwable) {
            _progress.value = Progress(id, "Couldn't finish", -1, error = e.message ?: e.javaClass.simpleName)
            false
        } finally { keepAlive(c, false) }
    }

    @Volatile private var wake: android.os.PowerManager.WakeLock? = null
    @Volatile var installing = false
    /** The foreground service plus a CPU wake lock, so Android lets a long install finish. */
    private fun keepAlive(c: Context, on: Boolean) {
        installing = on
        if (on) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, PcService::class.java)) }
            if (wake?.isHeld != true) wake = runCatching { (c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "atlas:pc-setup").apply { acquire(2 * 60 * 60_000L) } }.getOrNull()
        } else {
            runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
            if (running == null) runCatching { c.stopService(Intent(c, PcService::class.java)) }
        }
    }

    fun remove(c: Context, id: String) { stop(c); Tar.rm(root(c, id)) }

    private fun prepare(r: File) {
        File(r, "etc").mkdirs()
        runCatching { android.system.Os.remove(File(r, "etc/resolv.conf").path) }
        File(r, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(r, "etc/hosts").writeText("127.0.0.1 localhost atlas-pc\n::1 localhost ip6-localhost ip6-loopback\n")
        File(r, "root").mkdirs(); File(r, "tmp").mkdirs(); File(r, "root/projects").mkdirs()
        runCatching { android.system.Os.chmod(File(r, "tmp").path, "1777".toInt(8)) }
        File(r, "etc/apt/apt.conf.d").takeIf { File(r, "etc/apt").exists() }?.let { it.mkdirs(); File(it, "01-atlas").writeText("APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDPkg::Options { \"--force-confdef\"; \"--force-confold\"; };\n") }
        // ubuntu/debian images drop docs and refuse some setup steps in containers; a normal desktop wants them
        File(r, "etc/dpkg/dpkg.cfg.d/excludes").delete()
        File(r, "usr/sbin/policy-rc.d").let { if (it.parentFile?.exists() == true) { it.writeText("#!/bin/sh\nexit 101\n"); it.setExecutable(true) } }
    }

    /** Installs the desktop, a browser and Xvnc with whatever package manager the distro has. */
    private fun setupScript(desktop: String): String = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        if command -v apt-get >/dev/null; then
          dpkg --configure -a || true
          apt-get update
          case "$desktop" in
            xfce) P="xfce4 xfce4-terminal xfce4-goodies" ;;
            lxqt) P="lxqt-core lxqt-config qterminal openbox" ;;
            mate) P="mate-desktop-environment-core mate-terminal" ;;
            kde)  P="kde-plasma-desktop konsole" ;;
          esac
          B=firefox-esr; apt-cache show firefox-esr >/dev/null 2>&1 || B=falkon
          apt-get install -y -f || true
          nice -n 10 apt-get install -y --no-install-recommends ${'$'}P dbus-x11 tigervnc-standalone-server tigervnc-tools xdotool x11-utils fonts-dejavu-core ca-certificates sudo nano curl wget git python3 ${'$'}B
        elif command -v dnf >/dev/null; then
          case "$desktop" in xfce) G="xfce-desktop" ;; lxqt) G="lxqt-desktop" ;; mate) G="mate-desktop" ;; kde) G="kde-desktop" ;; esac
          dnf -y install epel-release 2>/dev/null || true
          dnf -y group install ${'$'}G --setopt=install_weak_deps=False || dnf -y groupinstall ${'$'}G
          dnf -y install tigervnc-server-minimal dbus-x11 xdotool firefox dejavu-sans-fonts git python3 nano curl
        elif command -v pacman >/dev/null; then
          pacman-key --init >/dev/null 2>&1 || true; pacman-key --populate >/dev/null 2>&1 || true
          case "$desktop" in xfce) P="xfce4 xfce4-goodies" ;; lxqt) P="lxqt openbox" ;; mate) P="mate mate-terminal" ;; kde) P="plasma-desktop konsole" ;; esac
          nice -n 10 pacman -Syu --noconfirm --needed ${'$'}P tigervnc xdotool firefox ttf-dejavu git python nano curl
        elif command -v zypper >/dev/null; then
          case "$desktop" in xfce) T=xfce ;; lxqt) T=lxqt ;; mate) T=mate ;; kde) T=kde_plasma ;; esac
          zypper -n refresh
          zypper -n install -t pattern ${'$'}T || true
          zypper -n install tigervnc xorg-x11-Xvnc xdotool MozillaFirefox dejavu-fonts dbus-1-x11 git python3 nano curl
        elif command -v apk >/dev/null; then
          sed -i 's/^#\(.*community\)/\1/' /etc/apk/repositories || true
          apk update
          case "$desktop" in xfce) P="xfce4 xfce4-terminal" ;; lxqt) P="lxqt-desktop openbox" ;; mate) P="mate-desktop-environment" ;; kde) P="plasma-desktop konsole" ;; esac
          apk add ${'$'}P tigervnc dbus dbus-x11 xdotool firefox font-dejavu git python3 nano curl bash
        else
          echo "Unknown package manager"; exit 1
        fi
        mkdir -p /root/Desktop
        echo "Atlas PC is ready"
    """.trimIndent()

    // ── running ────────────────────────────────────────────────────────────────
    private fun cmd(c: Context, id: String, inner: List<String>): List<String> {
        val r = root(c, id).path
        return listOf(LinuxEnv.proot(c).path, "--kill-on-exit", "--link2symlink", "--sysvipc", "-0", "-r", r,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${File(r, "tmp").path}:/dev/shm",
            "-b", "${LinuxEnv.projects(c).path}:/root/projects",
            "-w", "/root", "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm-256color", "LANG=C.UTF-8", "DISPLAY=:1",
            "XDG_RUNTIME_DIR=/tmp/runtime-root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") + inner
    }

    private fun process(c: Context, id: String, script: String): ProcessBuilder {
        val sh = if (File(root(c, id), "bin/bash").exists() || File(root(c, id), "usr/bin/bash").exists()) "/bin/bash" else "/bin/sh"
        return ProcessBuilder(cmd(c, id, listOf(sh, "-lc", script))).redirectErrorStream(true).also { it.environment().clear(); it.environment().putAll(LinuxEnv.env(c)) }
    }

    /** Runs a command on the PC (also used by Atlas while it works). */
    suspend fun shell(c: Context, id: String, script: String, timeoutSec: Long = 120, onLine: ((String) -> Unit)? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val p = process(c, id, script).start()
        val sb = StringBuilder()
        val t = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { l -> synchronized(sb) { if (sb.length < 300_000) sb.appendLine(l) }; onLine?.invoke(l) } } }.apply { start() }
        val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        t.join(2000)
        (if (done) p.exitValue() else -1) to synchronized(sb) { sb.toString() }
    }

    // the running desktop
    var running by mutableStateOf<String?>(null)
    @Volatile private var desktopProc: Process? = null
    const val PORT = 5901

    private fun session(desktop: String) = when (desktop) {
        "lxqt" -> "startlxqt"; "mate" -> "mate-session"; "kde" -> "startplasma-x11"; else -> "startxfce4"
    }

    /** Starts the desktop and its screen server (kept alive by a small foreground service). */
    fun start(c: Context, id: String) {
        if (running == id && desktopProc?.isAlive == true) return
        stop(c)
        val i = info(c, id) ?: return
        val (w, h) = i.optString("size", "1280x720").split('x').map { it.toIntOrNull() ?: 1280 }.let { it[0] to it.getOrElse(1) { 720 } }
        val script = """
            mkdir -p /tmp/.X11-unix /tmp/runtime-root; chmod 1777 /tmp/.X11-unix; chmod 700 /tmp/runtime-root
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
            Xvnc :1 -geometry ${w}x$h -depth 24 -SecurityTypes None -localhost -rfbport $PORT -AlwaysShared -AcceptSetDesktopSize >/tmp/xvnc.log 2>&1 &
            for i in 1 2 3 4 5 6 7 8 9 10; do [ -e /tmp/.X11-unix/X1 ] && break; sleep 0.5; done
            exec dbus-launch --exit-with-session ${session(i.optString("desktop"))} >/tmp/desktop.log 2>&1
        """.trimIndent()
        desktopProc = process(c, id, script).start().also { p -> Thread { runCatching { p.inputStream.bufferedReader().forEachLine { } } }.start() }
        running = id
        meta(c, id).writeText(i.put("used", System.currentTimeMillis()).toString())
        runCatching { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, PcService::class.java)) }
    }

    fun stop(c: Context) {
        desktopProc?.destroy(); desktopProc = null
        running = null
        Vnc.disconnect()
        if (!installing) runCatching { c.stopService(Intent(c, PcService::class.java)) }
    }

    fun alive() = desktopProc?.isAlive == true
}

/** Keeps Atlas's PC running while you switch apps. */
class PcService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        // always go to the foreground first: Android requires it for every start, even one that only stops
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("atlas_pc", "Atlas's PC", NotificationManager.IMPORTANCE_LOW))
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
            val open = android.app.PendingIntent.getActivity(this, 0, launch, android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "atlas_pc").setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(if (PcEnv.installing) "Setting up Atlas's PC…" else "Atlas's PC is running")
                .setContentText("Open Atlas to see the desktop").setContentIntent(open).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(5710, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(5710, n)
        }
        if (i?.action == "stop" || (PcEnv.running == null && !PcEnv.installing)) {
            runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { super.onDestroy() }
}
