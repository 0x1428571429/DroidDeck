package com.droiddeck.launcher.store.gog

import org.json.JSONObject
import java.io.File

/**
 * What an installed GOG game says about itself: goggame-<id>.info in its folder, the file GOG Galaxy
 * and Heroic launch from. Its primary play task names the exe, the folder to start in and the
 * arguments, so a store-installed game needs no guessing at which .exe is the game.
 */
object GogGameInfo {
    class Task(val exe: File, val workingDir: File, val arguments: String)
    class Info(val id: String, val name: String, val task: Task?)

    /** The root game's info in [folder] (DLCs add their own files beside it), or null. */
    fun read(folder: File): Info? {
        val files = folder.listFiles { f -> f.isFile && f.name.startsWith("goggame-") && f.name.endsWith(".info") } ?: return null
        val parsed = files.sortedBy { it.name }.mapNotNull { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }
        val root = parsed.firstOrNull { it.optString("gameId").let { id -> id.isNotEmpty() && id == it.optString("rootGameId") } }
            ?: parsed.singleOrNull() ?: return null
        return parse(folder, root)
    }

    internal fun parse(folder: File, json: JSONObject): Info? {
        val id = json.optString("gameId").ifEmpty { return null }
        val name = json.optString("name").ifEmpty { folder.name }
        val tasks = json.optJSONArray("playTasks")
        val files = (0 until (tasks?.length() ?: 0)).mapNotNull { tasks!!.optJSONObject(it) }
            .filter { it.optString("type") == "FileTask" && it.optString("path").isNotEmpty() }
        // Galaxy's own choice: the primary game task; else the first game task; else the first at all.
        val chosen = files.firstOrNull { it.optBoolean("isPrimary") && it.optString("category", "game") == "game" }
            ?: files.firstOrNull { it.optString("category", "game") == "game" }
            ?: files.firstOrNull()
        val task = chosen?.let { t ->
            val exe = resolve(folder, t.optString("path")) ?: return@let null
            val dir = t.optString("workingDir").takeIf { it.isNotBlank() }?.let { resolve(folder, it) } ?: folder
            Task(exe, dir, t.optString("arguments").trim())
        }
        return Info(id, name, task)
    }

    /**
     * [relative] (a Windows path, backslashes and all) under [base], matched case-insensitively as
     * Windows would; null when no such file or folder exists.
     */
    internal fun resolve(base: File, relative: String): File? {
        var at = base
        for (part in relative.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }) {
            // The name as the folder lists it: a caseless filesystem (an SD card) would accept any
            // spelling, but the session sees the game through proot, where only the real one works.
            val names = at.listFiles().orEmpty()
            at = names.firstOrNull { it.name == part } ?: names.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return at
    }
}
