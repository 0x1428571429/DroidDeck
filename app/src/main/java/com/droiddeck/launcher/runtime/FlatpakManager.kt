package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.Environment
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.session.OrphanReaper
import com.droiddeck.launcher.session.SessionFiles
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.File

/**
 * Flatpak in the Linux runtime: putting it there (bannerlator-flatpak-setup), the apps installed
 * from Flathub, and installing, removing and updating them (bannerlator-flatpak). One per-user
 * installation at /root/.local/share/flatpak, so nothing needs the system helper or polkit.
 *
 * Every command runs in a proot of its own, outside any session: the store works whether or not
 * Steam or the desktop is up, and a session starting meanwhile leaves it running (OrphanReaper).
 */
object FlatpakManager {
    private const val TAG = "FlatpakManager"
    const val FLATPAK = "/usr/bin/flatpak"
    const val BWRAP = "/usr/local/bin/bannerlator-bwrap"
    /** The program the front end starts a Flatpak app through, in a session of its own. */
    const val LAUNCHER = "/usr/local/bin/bannerlator-flatpak-run"
    private const val HELPER = "/usr/local/bin/bannerlator-flatpak"
    private const val SETUP = "/usr/local/bin/bannerlator-flatpak-setup"
    private const val USER_DIR = "root/.local/share/flatpak"

    /** One store operation at a time: Flatpak locks its installation per transaction anyway. */
    @Volatile var busy: String? = null
        private set

    class App(val id: String, val name: String, val icon: File?, val branch: String, val summary: String?)

    /** Flatpak is in the runtime, with Flathub to install from. */
    fun ready(context: Context): Boolean {
        val root = LinuxRuntime.rootDir(context)
        return File(root, FLATPAK.substring(1)).isFile &&
            File(root, "var/cache/droiddeck-flatpak/installed").isFile &&
            (FileUtils.readString(File(root, "$USER_DIR/repo/config"))?.contains("[remote \"flathub\"]") == true)
    }

    private fun arch(): String = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
        "x86_64" -> "x86_64"
        else -> "aarch64"
    }

    /**
     * The installed apps, read from the installation itself: each app's deployed files carry the
     * desktop entry and icons it exports, so listing them needs no process in the runtime.
     */
    fun installedApps(context: Context): List<App> {
        val apps = File(LinuxRuntime.rootDir(context), "$USER_DIR/app")
        val arch = arch()
        return apps.listFiles()?.mapNotNull { dir ->
            val id = dir.name
            val archDir = File(dir, arch)
            val branch = archDir.listFiles()?.firstOrNull { File(it, "active").exists() } ?: return@mapNotNull null
            val active = File(branch, "active")
            val export = File(active, "export/share")
            val desktop = FileUtils.readString(File(export, "applications/$id.desktop"))
            val name = desktop?.let { entry(it, "Name") } ?: id.substringAfterLast('.')
            val summary = desktop?.let { entry(it, "Comment") }
            App(id, name, icon(export, id), branch.name, summary)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    fun isInstalled(context: Context, id: String): Boolean =
        File(LinuxRuntime.rootDir(context), "$USER_DIR/app/$id/${arch()}").listFiles()?.any { File(it, "active").exists() } == true

    /** A key of the desktop entry's main group ([Desktop Entry]), unlocalised. */
    private fun entry(desktop: String, key: String): String? {
        var inMain = false
        for (line in desktop.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("[")) { inMain = t == "[Desktop Entry]"; continue }
            if (inMain && t.startsWith("$key=")) return t.substringAfter('=').trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** The largest PNG the app exports (the front end draws no SVG). */
    private fun icon(export: File, id: String): File? {
        val hicolor = File(export, "icons/hicolor")
        return hicolor.listFiles()
            ?.mapNotNull { size -> size.name.substringBefore('x').toIntOrNull()?.let { it to File(size, "apps/$id.png") } }
            ?.filter { it.second.isFile }
            ?.maxByOrNull { it.first }?.second
    }

    /**
     * Runs [argv] in the runtime and hands each output line to [onLine]; returns the exit status.
     * [fakeRoot] is for the package tools, which refuse any uid but 0.
     */
    private fun runGuest(context: Context, argv: List<String>, fakeRoot: Boolean, onLine: (String) -> Unit): Int {
        val root = LinuxRuntime.rootDir(context)
        LinuxRuntime.writeAccounts(context)
        SessionFiles.stage(context, root)
        val runtimeDir = File(context.filesDir, ".flatpak-rt").apply { mkdirs() }
        val cmd = LinuxRuntime.prootPrefix(context, root, "/root", fakeRoot)
        LinuxRuntime.binds(context, null, runtimeDir, Environment.getExternalStorageDirectory(), null)
            .forEach { cmd.add("-b"); cmd.add(it) }
        cmd += listOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "PATH=/usr/local/bin:/usr/bin:/bin", "XDG_RUNTIME_DIR=${runtimeDir.path}",
            "XDG_DATA_HOME=/root/.local/share", "FLATPAK_BWRAP=$BWRAP",
        )
        cmd += argv
        val builder = ProcessBuilder(cmd).directory(root).redirectErrorStream(true)
        builder.environment().apply {
            put("PROOT_LOADER", LinuxRuntime.prootLoader(context).path)
            put("PROOT_TMP_DIR", context.cacheDir.path)
            if (SessionPrefs.prootNoSeccomp(context)) put("PROOT_NO_SECCOMP", "1")
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
        }
        val process = builder.start()
        val pid = HostProcess.pidOf(process)
        OrphanReaper.keep(pid)
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            return process.waitFor()
        } finally {
            OrphanReaper.release(pid)
        }
    }

    private inline fun <T> exclusive(what: String, block: () -> T): T? {
        synchronized(this) {
            if (busy != null) return null
            busy = what
        }
        try { return block() } finally { busy = null }
    }

    /** Puts Flatpak into the runtime and adds Flathub. Null on success, else what went wrong. */
    fun setup(context: Context, onProgress: (String, Int) -> Unit): String? {
        if (!LinuxRuntime.isInstalled(context)) return "Install the Linux runtime first"
        return exclusive("setup") {
            var failure: String? = null
            val status = runGuest(context, listOf("/bin/bash", SETUP), fakeRoot = true) { line ->
                Log.i(TAG, "setup: $line")
                when {
                    line.startsWith("== STEP ") -> onProgress(line.removePrefix("== STEP "), -1)
                    line.startsWith("== FAIL ") -> failure = line.removePrefix("== FAIL ")
                }
            }
            when {
                failure != null -> failure
                status != 0 -> "Flatpak setup failed (exit $status)"
                !ready(context) -> "Flatpak setup did not finish"
                else -> null
            }
        } ?: "Another store task is running"
    }

    /**
     * One store operation - install, uninstall or update - with its progress as a stage line and
     * an overall percentage across the steps Flatpak plans (a runtime, its extensions, the app).
     */
    private fun transaction(context: Context, verb: String, id: String?, onProgress: (String, Int) -> Unit): String? {
        if (!ready(context)) return "Set up Flatpak first"
        return exclusive("$verb:${id ?: "all"}") {
            var error: String? = null
            val argv = listOfNotNull("/usr/bin/python3", HELPER, verb, id)
            val status = runGuest(context, argv, fakeRoot = false) { line ->
                val o = runCatching { JSONObject(line) }.getOrNull()
                if (o == null) { Log.i(TAG, "$verb: $line"); return@runGuest }
                when (o.optString("e")) {
                    "op" -> onProgress(stage(o), overall(o, 0))
                    "progress" -> onProgress(stage(o), overall(o, o.optInt("percent")))
                    "error" -> { error = o.optString("message"); Log.w(TAG, "$verb $id: $error") }
                    "warning" -> Log.w(TAG, "$verb $id: ${o.optString("message")}")
                }
            }
            error ?: if (status != 0) "Flatpak exited with status $status" else null
        } ?: "Another store task is running"
    }

    private fun stage(o: JSONObject): String {
        val ref = o.optString("ref")
        val name = ref.split('/').getOrNull(1)?.substringAfterLast('.') ?: ""
        val step = if (o.optInt("n") > 1) " (${o.optInt("i")}/${o.optInt("n")})" else ""
        return when (o.optString("kind")) {
            "uninstall" -> "Removing $name$step"
            "update" -> "Updating $name$step"
            else -> "Installing $name$step"
        }.let { if (ref.isEmpty()) o.optString("status").ifEmpty { "Working" } else it }
    }

    /** The whole transaction's percentage: steps done, plus this one's share. */
    private fun overall(o: JSONObject, percent: Int): Int {
        val n = o.optInt("n").coerceAtLeast(1)
        val i = o.optInt("i").coerceIn(1, n)
        return (((i - 1) * 100 + percent.coerceIn(0, 100)) / n)
    }

    fun install(context: Context, id: String, onProgress: (String, Int) -> Unit) = transaction(context, "install", id, onProgress)
    fun uninstall(context: Context, id: String, onProgress: (String, Int) -> Unit) = transaction(context, "uninstall", id, onProgress)
    fun update(context: Context, id: String?, onProgress: (String, Int) -> Unit) = transaction(context, "update", id, onProgress)

    /** Installed apps with an update on Flathub, or null when that could not be checked. */
    fun updates(context: Context): Set<String>? {
        if (!ready(context)) return emptySet()
        var apps: Set<String>? = null
        exclusive("updates") {
            runGuest(context, listOf("/usr/bin/python3", HELPER, "updates"), fakeRoot = false) { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@runGuest
                if (o.optString("e") == "updates") {
                    val a = o.optJSONArray("apps")
                    apps = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()
                }
            }
        }
        return apps
    }
}
