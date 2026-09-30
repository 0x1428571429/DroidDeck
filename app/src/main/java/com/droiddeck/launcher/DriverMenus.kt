package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import java.io.File
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import com.droiddeck.launcher.gpu.DriverPairs
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.DriverRow
import com.droiddeck.launcher.ui.GpuDriversState
import com.droiddeck.launcher.ui.PairRow
import com.droiddeck.launcher.wayland.CompositorHost

/**
 * The GPU drivers: what this GPU is, the matched driver pairs the release repos offer for it, and
 * - in Auto, the default - keeping the recommended pair installed and set. Under Advanced, the
 * Linux (runtime) and Android (display) lists as before, each picked on its own. The launcher
 * screen keeps one and hands it to the Components page's GPU drivers tab.
 */
internal class DriverMenus(private val activity: Activity, private val ui: Handler) {
    var linuxRows by mutableStateOf<List<DriverRow>>(emptyList())
    /** The latest Banners-Turnip release as each driver menu offers it (see [refreshReleaseRows]). */
    var linuxDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    var androidDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    var releaseStatus by mutableStateOf("Not checked yet - tap refresh to look for new drivers")
    var releaseChecking by mutableStateOf(false)
    var canRestoreBundled by mutableStateOf(false)
    /** Asset name -> download percent, while it downloads. */
    private val releaseProgress = HashMap<String, Int>()
    var linuxSelected by mutableStateOf("")
    val gpu: GpuInfo = GpuInfo.detect()
    // Read in refreshDrivers/ensureAuto: the activity has no context yet while its fields are made.
    var mode by mutableStateOf(SessionPrefs.GPU_DRIVERS_AUTO)
    var pairRows by mutableStateOf<List<PairRow>>(emptyList())
    /** The pair being installed, and how far along. */
    var pairBusy by mutableStateOf<String?>(null)
    var pairPercent by mutableIntStateOf(-1)
    /** What Auto last did or found, for the line under the pair in use. */
    var autoStatus by mutableStateOf("")
    private var autoCheckedThisProcess = false

    fun state() = GpuDriversState(
        gpuName = gpu.name, gpuFamily = gpu.family.label, soc = gpu.soc, supportText = gpu.supportText,
        supported = gpu.support == GpuInfo.Support.TESTED, unsupported = gpu.support == GpuInfo.Support.UNSUPPORTED,
        auto = mode == SessionPrefs.GPU_DRIVERS_AUTO, pairs = pairRows, busy = pairBusy, percent = pairPercent,
        autoStatus = autoStatus, releaseStatus = releaseStatus, checking = releaseChecking,
        linuxRows = linuxRows, linuxSelected = linuxSelected, androidRows = androidRows, androidSelected = androidSelected,
        linuxDownloads = linuxDownloads, androidDownloads = androidDownloads, canRestoreBundled = canRestoreBundled,
    )
    var androidRows by mutableStateOf<List<DriverRow>>(emptyList())
    var androidSelected by mutableStateOf("")

    fun refreshDrivers() {
        val lm = LinuxVulkanDriverManager(activity)
        fun origin(id: String) = if (TurnipReleases.isDownloaded(activity, id)) DriverRow.DOWNLOADED else DriverRow.IMPORTED
        linuxRows = LinuxVulkanDriver.optionValues(activity).map { id ->
            if (id.isEmpty()) DriverRow("", "Runtime default", "the Turnip built into the runtime", false)
            else DriverRow(
                id, lm.getDriverName(id),
                listOfNotNull(
                    lm.getDriverVersion(id).takeIf { it.isNotEmpty() },
                    lm.getMinGlibc(id).takeIf { it.isNotEmpty() }?.let { "glibc $it+" },
                ).joinToString(" · "),
                true, origin(id),
            )
        }
        linuxSelected = SessionPrefs.linuxDriver(activity)
        mode = SessionPrefs.gpuDriverMode(activity)
        val td = TurnipDriver(activity)
        val auto = td.autoId()
        androidRows = buildList {
            add(DriverRow(
                TurnipDriver.AUTO, "Auto - picked by GPU",
                if (auto == "system") "system Vulkan: no bundled build for this GPU" else "${td.displayName(auto)} (bundled)",
                false,
            ))
            for (id in td.visibleBundled()) add(DriverRow(id, td.displayName(id), td.driverVersion(id), true, DriverRow.BUNDLED))
            for (id in td.enumerateImported()) add(DriverRow(id, td.displayName(id), td.driverVersion(id), true, origin(id)))
        }
        canRestoreBundled = td.hiddenBundled().isNotEmpty()
        androidSelected = SessionPrefs.androidDriver(activity)
        refreshReleaseRows()
        refreshPairs()
    }

    /** One line for the settings row that opens this: "Auto · WinNative · Balanced". */
    fun summary(): String {
        val active = pairRows.firstOrNull { it.active }?.name
        return (if (mode == SessionPrefs.GPU_DRIVERS_AUTO) "Auto" else "Manual") + (active?.let { " · $it" } ?: "")
    }

    /** The pairs the last check found, with what is installed and in use; this GPU's first. */
    fun refreshPairs() {
        val lm = LinuxVulkanDriverManager(activity)
        val td = TurnipDriver(activity)
        val recommended = DriverPairs.recommendedKey(gpu)
        pairRows = DriverPairs.from(TurnipReleases.cached(activity)).map { p ->
            val displayId = p.display?.let { TurnipReleases.installedId(activity, it, td::isInstalled) }
            val linuxId = p.linux?.let { TurnipReleases.installedId(activity, it, lm::isInstalled) }
            val mb = listOfNotNull(p.display, p.linux).sumOf { it.size } / 1_048_576.0
            PairRow(
                key = p.key, name = p.name, version = p.version,
                detail = if (!p.complete) "Only one half is published right now" else "%.0f MB for both".format(mb),
                recommended = p.key == recommended, suits = p.suits(gpu), complete = p.complete,
                installed = displayId != null && linuxId != null,
                active = displayId != null && linuxId != null &&
                    SessionPrefs.androidDriver(activity) == displayId && SessionPrefs.linuxDriver(activity) == linuxId,
            )
        }.sortedByDescending { it.recommended }
    }

    fun setMode(auto: Boolean) {
        mode = if (auto) SessionPrefs.GPU_DRIVERS_AUTO else SessionPrefs.GPU_DRIVERS_MANUAL
        SessionPrefs.setGpuDriverMode(activity, mode)
        autoStatus = ""
        if (auto) ensureAuto(force = false)
    }

    /**
     * Auto: make the recommended pair for this GPU the one installed and set. Looks online when
     * [force]d (refresh), or when the last check is a day old - Banners-Turnip rebuilds hourly, and
     * a new pair a day is plenty - otherwise works from the last check. Once per app start on its
     * own; the pair it replaces is removed, anything the user imported or picked is left alone.
     */
    fun ensureAuto(force: Boolean) {
        mode = SessionPrefs.gpuDriverMode(activity)
        if (mode != SessionPrefs.GPU_DRIVERS_AUTO || pairBusy != null || releaseChecking) return
        if (!force && autoCheckedThisProcess) return
        autoCheckedThisProcess = true
        val key = DriverPairs.recommendedKey(gpu)
        if (key == null) {
            autoStatus = "No drivers to set: ${gpu.supportText.lowercase()}"
            return
        }
        releaseChecking = true
        autoStatus = "Checking for the latest drivers…"
        Thread({
            val cached = TurnipReleases.cached(activity)
            val stale = cached == null || System.currentTimeMillis() - cached.checkedAt > 24 * 3_600_000L ||
                cached.assets.none { it.pair.isNotEmpty() }
            val problem = if (force || stale) runCatching { TurnipReleases.refresh(activity) }.exceptionOrNull()?.message else null
            ui.post {
                releaseChecking = false
                refreshReleaseRows()
                refreshPairs()
                val pair = DriverPairs.from(TurnipReleases.cached(activity)).firstOrNull { it.key == key }
                val row = pairRows.firstOrNull { it.key == key }
                when {
                    row?.active == true -> autoStatus = "Up to date" + (problem?.let { " (couldn't check: $it)" } ?: "")
                    pair == null || !pair.complete ->
                        autoStatus = problem?.let { "Couldn't check: $it" } ?: "The recommended drivers aren't published right now"
                    else -> installPair(pair, auto = true)
                }
            }
        }, "gpu-driver-auto").start()
    }

    /** Manual: install (if need be) and set both halves of a pair. */
    fun selectPair(key: String) {
        if (pairBusy != null) return
        val pair = DriverPairs.from(TurnipReleases.cached(activity)).firstOrNull { it.key == key && it.complete } ?: return
        installPair(pair, auto = false)
    }

    /**
     * Download what is missing of [pair], then set both halves - only once both are in, so a
     * failed download never leaves a half-changed pair. Auto's own downloads are recorded, and the
     * ones the new pair replaces are removed.
     */
    private fun installPair(pair: DriverPairs.DriverPair, auto: Boolean) {
        val display = pair.display ?: return
        val linux = pair.linux ?: return
        pairBusy = pair.key
        pairPercent = 0
        if (auto) autoStatus = "Downloading ${pair.name} ${pair.version}…"
        Thread({
            val lm = LinuxVulkanDriverManager(activity)
            val td = TurnipDriver(activity)
            var downloaded = false
            val result = runCatching {
                val halves = listOf(display, linux)
                halves.mapIndexed { i, asset ->
                    TurnipReleases.installedId(activity, asset) { id -> if (asset.linux) lm.isInstalled(id) else td.isInstalled(id) }
                        ?: installAsset(asset) { pct -> ui.post { pairPercent = (i * 100 + pct) / halves.size } }.also { downloaded = true }
                }
            }
            ui.post {
                pairBusy = null
                pairPercent = -1
                result.onSuccess { (displayId, linuxId) ->
                    val displayChanged = SessionPrefs.androidDriver(activity) != displayId
                    SessionPrefs.setAndroidDriver(activity, displayId)
                    SessionPrefs.setLinuxDriver(activity, linuxId)
                    val restart = if (displayChanged && CompositorHost.isStarted) " The display driver applies after DroidDeck restarts." else ""
                    if (auto) {
                        val previous = SessionPrefs.gpuAutoInstalled(activity)
                        for (id in previous - setOf(displayId, linuxId)) {
                            if (lm.isInstalled(id)) lm.removeDriver(id) else td.remove(id)
                            TurnipReleases.forget(activity, id)
                        }
                        SessionPrefs.setGpuAutoInstalled(activity, setOf(displayId, linuxId))
                        autoStatus = (if (downloaded) "Updated to" else "Switched to") + " ${pair.name} ${pair.version}.$restart"
                    } else {
                        android.widget.Toast.makeText(activity, "Using ${pair.name} ${pair.version}.$restart", android.widget.Toast.LENGTH_LONG).show()
                    }
                }.onFailure { e ->
                    Log.w(TAG, "driver pair ${pair.key}", e)
                    val why = if (e is IllegalArgumentException) e.message else "Download failed: ${e.message}"
                    if (auto) autoStatus = why ?: "Download failed"
                    else android.widget.Toast.makeText(activity, why, android.widget.Toast.LENGTH_LONG).show()
                }
                refreshDrivers()
            }
        }, "gpu-driver-pair").start()
    }

    /** One release asset, downloaded, checked and installed through the importer; returns its id. */
    private fun installAsset(asset: TurnipReleases.Asset, progress: (Int) -> Unit): String {
        var file: File? = null
        try {
            file = TurnipReleases.download(activity, asset, progress)
            val uri = Uri.fromFile(file)
            val id = if (asset.linux) LinuxVulkanDriverManager(activity).installDriver(uri, asset.name)
                     else TurnipDriver(activity).installFromZip(uri, asset.name)
            TurnipReleases.recordDownload(activity, asset, id)
            return id
        } finally {
            file?.let { com.droiddeck.launcher.core.FileUtils.delete(it) }
        }
    }

    /**
     * Import off the main thread - a driver zip is a few MB and the glibc check reads the whole
     * library - then say what happened. A refusal's message is the user-facing reason.
     */
    fun importDriver(uri: Uri, linux: Boolean) {
        val name = activity.displayNameOf(uri)
        Thread({
            val problem = try {
                if (linux) LinuxVulkanDriverManager(activity).installDriver(uri, name)
                else TurnipDriver(activity).installFromZip(uri, name)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "driver import", e)
                "Import failed: ${e.message}"
            }
            ui.post {
                android.widget.Toast.makeText(
                    activity, problem ?: "Imported ${name ?: "driver"}",
                    if (problem != null) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                ).show()
                refreshDrivers()
            }
        }, "import-driver").start()
    }

    /**
     * Delete an imported or downloaded driver. A mode still set to it goes back to its default, so a
     * session never starts on a driver that is gone; a release download is forgotten, so the menu
     * offers it again.
     */
    fun deleteDriver(id: String, linux: Boolean) {
        if (linux) {
            LinuxVulkanDriverManager(activity).removeDriver(id)
            if (SessionPrefs.linuxDriver(activity) == id) SessionPrefs.setLinuxDriver(activity, "")
        } else {
            val td = TurnipDriver(activity)
            if (id in TurnipDriver.BUNDLED) td.hideBundled(id) else td.remove(id)
            if (SessionPrefs.androidDriver(activity) == id) SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
        }
        TurnipReleases.forget(activity, id)
        android.widget.Toast.makeText(activity, "Deleted ${id}", android.widget.Toast.LENGTH_SHORT).show()
        refreshDrivers()
    }

    /** The download entries and the refresh line, from what the last check found. */
    fun refreshReleaseRows() {
        val check = TurnipReleases.cached(activity)
        val lm = LinuxVulkanDriverManager(activity)
        val td = TurnipDriver(activity)
        fun rows(linux: Boolean) = check?.assets.orEmpty()
            .filter { it.linux == linux }
            .filter { a -> TurnipReleases.installedId(activity, a) { id -> if (linux) lm.isInstalled(id) else td.isInstalled(id) } == null }
            .map { a ->
                val mb = "%.1f MB".format(a.size / 1_048_576.0)
                com.droiddeck.launcher.ui.DownloadRow(a.name, "${a.source} ${a.tag}", "${a.label} · $mb", releaseProgress[a.name])
            }
        linuxDownloads = rows(linux = true)
        androidDownloads = rows(linux = false)
        if (!releaseChecking) releaseStatus = when (check) {
            null -> "Not checked yet - tap refresh to look for new drivers"
            else -> "Latest: " + check.latest.joinToString(" · ") { "${it.first} ${it.second}" } +
                (if (check.failed.isEmpty()) "" else " · ${check.failed.joinToString()} unreachable") +
                " · checked ${ago(check.checkedAt)}"
        }
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    /** Only when the user taps refresh: nothing goes online on its own. */
    fun checkLatestTurnip() {
        if (releaseChecking) return
        releaseChecking = true
        releaseStatus = "Checking Banners-Turnip and WinNative…"
        Thread({
            val problem = try { TurnipReleases.refresh(activity); null } catch (e: Exception) {
                Log.w(TAG, "latest Turnip check", e); e.message ?: "check failed"
            }
            ui.post {
                releaseChecking = false
                refreshReleaseRows()
                refreshPairs()
                if (problem != null) releaseStatus = "Couldn't check: $problem"
            }
        }, "turnip-release-check").start()
    }

    /** Download one release driver and import it through the same importer a picked zip uses. */
    fun downloadReleaseDriver(assetName: String) {
        val asset = TurnipReleases.cached(activity)?.assets?.firstOrNull { it.name == assetName } ?: return
        if (releaseProgress.containsKey(assetName)) return
        releaseProgress[assetName] = 0
        refreshReleaseRows()
        Thread({
            var file: java.io.File? = null
            val problem = try {
                file = TurnipReleases.download(activity, asset) { pct ->
                    ui.post { releaseProgress[assetName] = pct; refreshReleaseRows() }
                }
                val uri = Uri.fromFile(file)
                val id = if (asset.linux) LinuxVulkanDriverManager(activity).installDriver(uri, asset.name)
                         else TurnipDriver(activity).installFromZip(uri, asset.name)
                TurnipReleases.recordDownload(activity, asset, id)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "release driver download", e)
                "Download failed: ${e.message}"
            } finally {
                file?.let { com.droiddeck.launcher.core.FileUtils.delete(it) }
            }
            ui.post {
                releaseProgress.remove(assetName)
                android.widget.Toast.makeText(
                    activity, problem ?: "Installed ${asset.name.removeSuffix(".zip")} - pick it in the menu",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                refreshDrivers()
            }
        }, "download-turnip").start()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

internal fun Context.displayNameOf(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment else try {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (e: Exception) {
    null
}

