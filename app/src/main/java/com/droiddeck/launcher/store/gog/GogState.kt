package com.droiddeck.launcher.store.gog

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The GOG store's state for the life of the process, so a download carries on - and its progress
 * shows - while the user moves around the launcher or plays something meanwhile. Downloads run in
 * [GogInstallService], which keeps the process in the foreground for as long as they take.
 */
object GogState {
    private val main = Handler(Looper.getMainLooper())

    var ready by mutableStateOf(false)
        private set
    var signedIn by mutableStateOf(false)
        private set
    /** The account's Windows games; null until loaded. */
    var library by mutableStateOf<List<GogApi.Game>?>(null)
        private set
    var libraryFailed by mutableStateOf(false)
        private set
    var loadingLibrary by mutableStateOf(false)
        private set
    var installed by mutableStateOf<List<GogManager.Installed>>(emptyList())
        private set
    /** What is running: "setup", "login", or a game id; null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var stage by mutableStateOf<String?>(null)
        private set
    var percent by mutableStateOf(-1)
        private set
    /** Download and installed sizes by game id; absent until asked, null while being read. */
    val sizes = mutableStateMapOf<String, GogManager.Sizes?>()

    @Volatile private var process: Process? = null
    @Volatile private var cancelled = false

    fun refresh(context: Context) {
        val app = context.applicationContext
        Thread({
            val r = GogManager.ready(app)
            val s = GogManager.signedIn(app)
            val list = GogManager.installed(app)
            main.post { ready = r; signedIn = s; installed = list }
        }, "gog-refresh").start()
    }

    fun loadLibrary(context: Context, force: Boolean = false) {
        if (loadingLibrary || (!force && library != null)) return
        val app = context.applicationContext
        loadingLibrary = true
        libraryFailed = false
        Thread({
            val token = GogManager.accessToken(app)
            val games = token?.let { GogApi.library(it) }
            main.post {
                loadingLibrary = false
                if (games != null) library = games else libraryFailed = true
            }
        }, "gog-library").start()
    }

    fun loadSizes(context: Context, id: String) {
        if (sizes.containsKey(id)) return
        val app = context.applicationContext
        sizes[id] = null
        Thread({
            val s = GogManager.sizes(app, id)
            main.post { sizes[id] = s; if (s == null) sizes.remove(id) }
        }, "gog-sizes").start()
    }

    private fun background(context: Context, what: String, label: String, work: (Context, (String, Int) -> Unit) -> String?) {
        if (busy != null) return
        val app = context.applicationContext
        busy = what; stage = "Starting…"; percent = -1
        Thread({
            val problem = try {
                work(app) { s, p -> main.post { stage = s; percent = p } }
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            val r = GogManager.ready(app)
            val s = GogManager.signedIn(app)
            val list = GogManager.installed(app)
            main.post {
                busy = null; stage = null; percent = -1
                ready = r; signedIn = s; installed = list
                if (problem != null) Toast.makeText(app, "$label: $problem", Toast.LENGTH_LONG).show()
            }
        }, "gog-$what").start()
    }

    fun setup(context: Context) = background(context, "setup", "GOG setup") { c, p -> GogManager.setup(c, p) }

    fun login(context: Context, code: String) = background(context, "login", "GOG sign-in") { c, p ->
        p("Signing in…", -1)
        GogManager.login(c, code).also { if (it == null) main.post { library = null; loadLibrary(c, force = true) } }
    }

    fun signOut(context: Context) {
        GogManager.signOut(context)
        library = null
        signedIn = false
    }

    fun uninstall(context: Context, game: GogManager.Installed) =
        background(context, game.id, game.name) { c, p -> p("Removing…", -1); GogManager.uninstall(c, game) }

    fun install(context: Context, game: GogApi.Game, base: GogManager.Base) {
        if (busy != null) return
        busy = game.id; stage = "Starting…"; percent = -1
        GogInstallService.start(context, GogInstallService.Job("install", game.id, game.title, base.id, null))
    }

    fun update(context: Context, game: GogManager.Installed) {
        if (busy != null) return
        busy = game.id; stage = "Starting…"; percent = -1
        GogInstallService.start(context, GogInstallService.Job("update", game.id, game.name, game.base.id, game.dir.path))
    }

    /** Stops the running download; installing again resumes it. */
    fun cancel() {
        cancelled = true
        process?.destroy()
    }

    /** The service's half of [install] and [update]; runs on the service's thread. */
    internal fun run(context: Context, job: GogInstallService.Job, notify: (String, Int) -> Unit) {
        val app = context.applicationContext
        cancelled = false
        main.post { busy = job.id }
        val base = GogManager.bases(app).firstOrNull { it.id == job.base }
        val problem = try {
            when {
                base == null -> "That storage is not available"
                job.kind == "update" -> {
                    val game = GogManager.installed(app).firstOrNull { it.dir.path == job.dir }
                    if (game == null) "The game is no longer installed"
                    else GogManager.update(app, game, { process = it }) { s, p -> main.post { stage = s; percent = p }; notify(s, p) }
                }
                else -> GogManager.install(app, job.id, base, { process = it }) { s, p -> main.post { stage = s; percent = p }; notify(s, p) }
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        } finally {
            process = null
        }
        val list = GogManager.installed(app)
        // GOG's own art for Steam to show; the Steam store's is the fallback if this finds none.
        if (problem == null) list.firstOrNull { it.id == job.id }?.let { runCatching { GogApi.saveArt(job.id, it.dir) } }
        main.post {
            busy = null; stage = null; percent = -1
            installed = list
            val message = when {
                cancelled -> "${job.title}: stopped. Install again to pick up where it left off."
                problem != null -> "${job.title}: $problem"
                job.kind == "update" -> "${job.title} is up to date."
                else -> "${job.title} is installed. It shows in Steam the next time Steam starts."
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }
}
