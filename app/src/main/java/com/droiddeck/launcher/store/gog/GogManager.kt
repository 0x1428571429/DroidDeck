package com.droiddeck.launcher.store.gog

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import org.json.JSONObject
import java.io.File

/**
 * GOG in the Linux runtime, through Heroic's gogdl (GOG's own download protocol, sign-in tokens,
 * resumable installs): putting gogdl there, signing in, and installing, updating and removing
 * games. A game lands in a GOG folder the Added Games scan reads, so the session puts it in Steam
 * as a shortcut like any other added game - the store only fetches; Steam still launches.
 *
 * Each command runs in a proot of its own (GuestCommand), so a download carries on whether or
 * not a session is up.
 */
object GogManager {
    private const val TAG = "GogManager"
    /** Heroic's gogdl release the app puts in the runtime: a Python zipapp, so any architecture. */
    const val GOGDL_VERSION = "1.3.0"
    private const val GOGDL_URL = "https://github.com/Heroic-Games-Launcher/heroic-gogdl/releases/download/v$GOGDL_VERSION/gogdl_linux_arm64"
    private const val GOGDL_SHA256 = "c49e1519146523ec94f33e2d21eedcc9a167004d7da218621e6d5fb84a7a0f4c"
    /** Where gogdl lives in the runtime (droiddeck-gog runs it from there). */
    private const val GOGDL = "opt/droiddeck/gogdl/gogdl"
    private const val HELPER = "/usr/local/bin/droiddeck-gog"
    /** The folder, in each place games can go, that store installs live under. */
    const val FOLDER = "GOG"
    /** Written by the helper into a game's folder before its download starts. */
    const val MARKER = ".droiddeck-gog.json"
    private const val RESUME = ".gogdl-resume"

    /** One store operation at a time. */
    @Volatile var busy: String? = null
        private set

    /** One place games can go: internal (the runtime's own disk) or the SD card's game library. */
    class Base(val id: String, val label: String, val host: File, val guest: String, val binds: List<String>)

    /** A GOG game in one of the [bases]; [complete] is false for a download that stopped partway. */
    class Installed(val id: String, val name: String, val dir: File, val base: Base, val complete: Boolean)

    /** The sign-in gogdl keeps (tokens), in the app's private files - never on shared storage. */
    fun authFile(context: Context) = File(context.filesDir, "gog/auth.json")

    private fun gogdl(context: Context) = File(LinuxRuntime.rootDir(context), GOGDL)
    private fun stamp(context: Context) = File(LinuxRuntime.rootDir(context), "$GOGDL.version")

    fun ready(context: Context): Boolean =
        gogdl(context).isFile && runCatching { stamp(context).readText().trim() }.getOrNull() == GOGDL_VERSION

    fun signedIn(context: Context): Boolean = authFile(context).isFile

    fun bases(context: Context): List<Base> {
        val internal = Base("internal", "Internal storage", File(LinuxRuntime.rootDir(context), "root/$FOLDER"), "/root/$FOLDER", emptyList())
        val sd = GameStorage.effective(context)?.let { lib ->
            Base("sd", lib.label, File(lib.path, FOLDER), "/mnt/droiddeck-sd/$FOLDER", listOf("${lib.path}:/mnt/droiddeck-sd"))
        }
        return listOfNotNull(internal, sd)
    }

    fun installed(context: Context): List<Installed> = bases(context).flatMap { base ->
        base.host.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir -> installedAt(dir, base) }
    }.sortedBy { it.name.lowercase() }

    private fun installedAt(dir: File, base: Base): Installed? {
        val marker = runCatching { JSONObject(File(dir, MARKER).readText()) }.getOrNull()
        val info = GogGameInfo.read(dir)
        val id = marker?.optString("id")?.ifEmpty { null } ?: info?.id ?: return null
        val complete = info?.task != null && !File(dir, RESUME).exists()
        return Installed(id, info?.name ?: dir.name, dir, base, complete)
    }

    /** True for a folder the store put there and whose download has not finished: not a game yet. */
    fun incomplete(dir: File): Boolean =
        File(dir, MARKER).isFile && (File(dir, RESUME).exists() || GogGameInfo.read(dir)?.task == null)

    internal fun exclusive(what: String, block: () -> String?): String? {
        synchronized(this) {
            if (busy != null) return "Another GOG task is running"
            busy = what
        }
        try { return block() } finally { busy = null }
    }

    /** Downloads the pinned gogdl into the runtime and checks it. Null on success, else why not. */
    fun setup(context: Context, onProgress: (String, Int) -> Unit): String? {
        if (!LinuxRuntime.isInstalled(context)) return "Install the Linux runtime first"
        return exclusive("setup") {
            val target = gogdl(context)
            val part = File(target.path + ".part")
            target.parentFile?.mkdirs()
            onProgress("Downloading gogdl $GOGDL_VERSION", -1)
            if (!Downloader.downloadFile(GOGDL_URL, part, true) { f -> if (f >= 0) onProgress("Downloading gogdl $GOGDL_VERSION", (f * 100).toInt()) }) {
                return@exclusive "Could not download gogdl"
            }
            if (!Hashes.sha256(part).equals(GOGDL_SHA256, ignoreCase = true)) {
                part.delete()
                return@exclusive "gogdl did not match its checksum"
            }
            if (!part.renameTo(target)) return@exclusive "Could not put gogdl in place"
            target.setExecutable(true, false)
            stamp(context).writeText(GOGDL_VERSION)
            null
        }
    }

    /** Runs the helper. Commands that touch tokens pass no [logName]: their output stays off shared storage. */
    private fun helper(context: Context, args: List<String>, logName: String?, binds: List<String> = emptyList(),
                       onProcess: ((Process) -> Unit)? = null, onEvent: (JSONObject) -> Unit): Int {
        val argv = listOf("/usr/bin/python3", HELPER, args[0], authFile(context).path) + args.drop(1)
        return GuestCommand.run(context, argv, logName = logName, extraBinds = binds, onProcess = onProcess) { line ->
            val o = runCatching { JSONObject(line) }.getOrNull()
            if (o != null && o.has("e")) onEvent(o) else if (logName != null) Log.i(TAG, line)
        }
    }

    /** Trades the sign-in page's code for gogdl's tokens. Null on success, else why not. */
    fun login(context: Context, code: String): String? {
        if (!ready(context)) return "Set up GOG first"
        authFile(context).parentFile?.mkdirs()
        return exclusive("login") {
            var error: String? = null
            var done = false
            val status = helper(context, listOf("login", code), logName = null) { o ->
                when (o.optString("e")) {
                    "done" -> done = true
                    "error" -> error = o.optString("message")
                }
            }
            when {
                done -> null
                error != null -> error
                else -> "Signing in failed (exit $status)"
            }
        }
    }

    fun signOut(context: Context) {
        authFile(context).delete()
        token = null
    }

    private class Token(val value: String, val userId: String, val expires: Long)
    @Volatile private var token: Token? = null

    /** A current access token for GOG's APIs, refreshed by gogdl when due; null when signed out. */
    fun accessToken(context: Context): String? {
        token?.takeIf { it.expires * 1000 > System.currentTimeMillis() + 60_000 }?.let { return it.value }
        if (!ready(context) || !signedIn(context)) return null
        var fresh: Token? = null
        // Not exclusive: a token is wanted while a download runs, and gogdl only rewrites the
        // file when it refreshes.
        helper(context, listOf("token"), logName = null) { o ->
            if (o.optString("e") == "token") fresh = Token(o.optString("access_token"), o.optString("user_id"), o.optLong("expires"))
        }
        token = fresh
        return fresh?.value
    }

    class Sizes(val download: Long, val disk: Long)

    /** The Windows build's download and installed sizes, or null when it cannot be read. */
    fun sizes(context: Context, id: String): Sizes? {
        if (!ready(context)) return null
        var sizes: Sizes? = null
        helper(context, listOf("info", id), logName = null) { o ->
            if (o.optString("e") == "info") sizes = Sizes(o.optLong("download_size"), o.optLong("disk_size"))
        }
        return sizes
    }

    /**
     * Downloads [id] into [base]; a stopped download picks up where it left off. [onProcess] gets
     * the running command so the caller can stop it. Null on success, else why not.
     */
    fun install(context: Context, id: String, base: Base, onProcess: (Process) -> Unit, onProgress: (String, Int) -> Unit): String? =
        transfer(context, "install", listOf("install", id, base.guest), base, onProcess, onProgress)

    fun update(context: Context, game: Installed, onProcess: (Process) -> Unit, onProgress: (String, Int) -> Unit): String? {
        val guest = game.base.guest + "/" + game.dir.name
        return transfer(context, "update", listOf("update", game.id, guest), game.base, onProcess, onProgress)
    }

    private fun transfer(context: Context, verb: String, args: List<String>, base: Base,
                         onProcess: (Process) -> Unit, onProgress: (String, Int) -> Unit): String? {
        if (!ready(context)) return "Set up GOG first"
        if (!signedIn(context)) return "Sign in to GOG first"
        return exclusive("$verb:${args[1]}") {
            base.host.mkdirs()
            // proot binds the SD library onto this folder, as a session does.
            if (base.binds.isNotEmpty()) File(LinuxRuntime.rootDir(context), "mnt/droiddeck-sd").mkdirs()
            var error: String? = null
            var done = false
            var stage = if (verb == "update") "Updating" else "Downloading"
            onProgress("Preparing", -1)
            val status = helper(context, args, logName = "gog-$verb", binds = base.binds, onProcess = onProcess) { o ->
                when (o.optString("e")) {
                    "info" -> {
                        stage = "${if (verb == "update") "Updating" else "Downloading"} ${formatSize(o.optLong("download_size"))}"
                        onProgress(stage, 0)
                    }
                    "progress" -> onProgress(stage, o.optInt("percent"))
                    "error" -> error = o.optString("message")
                    "done" -> done = true
                }
            }
            when {
                done -> null
                error != null -> error
                else -> "Stopped (exit $status)"
            }
        }
    }

    /**
     * Removes the game's files and gogdl's record of them. The game's Proton prefix (its saves and
     * settings) stays where Steam keeps it, as for any added game taken out of the library.
     */
    fun uninstall(context: Context, game: Installed): String? = exclusive("uninstall:${game.id}") {
        if (!game.dir.deleteRecursively() && game.dir.exists()) return@exclusive "Could not remove ${game.dir.name}"
        File(LinuxRuntime.rootDir(context), "root/.config/heroic_gogdl/manifests/${game.id}").delete()
        null
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
