package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Kills leftover Linux processes owned by this app while preserving live DroidDeck process trees.
 *
 * A session is a tree of processes under proot, and proot dies with the app only when the app's
 * own teardown runs. When the app is killed or crashes instead, the tree stays: proot holding the
 * rootfs, gamescope holding the GPU, Steam holding its lock file - and the next session never
 * comes up, or comes up beside a client that is already running. A user on a Thor Pro had to
 * force-stop the app before every launch. Everything of ours that should be alive between
 * sessions lives in Android processes, so another active session can own Linux children under
 * the same uid. Those trees are protected by skipping the sweep while a sibling app process is
 * active. Work started outside a session and still waiting - such as a Flatpak install - is
 * registered with [keep] and its process tree is spared. Each pid is checked by uid at kill time,
 * never by name.
 */
object OrphanReaper {
    private const val TAG = "OrphanReaper"
    private val kept = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Spares [pid] and its descendants until [release]. */
    fun keep(pid: Int) { if (pid > 0) kept.add(pid) }

    fun release(pid: Int) { kept.remove(pid) }

    fun reap(reason: String, context: Context): Int {
        if (SessionProcess.hasSiblingProcess(context)) {
            Log.i(TAG, "skipped process sweep while a sibling app process is active - $reason")
            return 0
        }
        val me = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        val spared = descendants(kept)
        var killed = 0
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == me || pid <= 1 || pid in spared) return@forEach
            if (uidOf(pid) != uid) return@forEach
            val name = try { File(entry, "cmdline").readText().substringBefore('\u0000') } catch (e: Exception) { "?" }
            try {
                android.os.Process.killProcess(pid)
                killed++
                Log.i(TAG, "killed leftover $pid ($name) - $reason")
            } catch (e: Exception) {
                Log.w(TAG, "could not kill $pid ($name)", e)
            }
        }
        if (killed > 0) Log.i(TAG, "$killed leftover process(es) from an earlier session - $reason")
        return killed
    }

    /** [roots] and every process below them, by each process's parent in /proc/<pid>/stat. */
    private fun descendants(roots: Set<Int>): Set<Int> {
        if (roots.isEmpty()) return emptySet()
        val parent = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val stat = try { File(entry, "stat").readText() } catch (e: Exception) { return@forEach }
            // "pid (comm) state ppid ...": comm may hold spaces and parentheses; the last ')' ends it.
            stat.substringAfterLast(") ").split(' ').getOrNull(1)?.toIntOrNull()?.let { parent[pid] = it }
        }
        val out = HashSet(roots)
        var grew = true
        while (grew) {
            grew = false
            for ((pid, ppid) in parent) if (ppid in out && out.add(pid)) grew = true
        }
        return out
    }

    private fun uidOf(pid: Int): Int {
        return try {
            File("/proc/$pid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }
                    ?.substringAfter("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() ?: -1
            }
        } catch (e: Exception) {
            -1
        }
    }

}
