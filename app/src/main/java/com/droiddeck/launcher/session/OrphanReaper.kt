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
 * the same uid. Those process trees are preserved; remaining same-uid processes are swept. Each
 * pid is checked by uid at kill time, never by name.
 */
object OrphanReaper {
    private const val TAG = "OrphanReaper"

    fun reap(reason: String, context: Context): Int {
        if (SessionProcess.hasSiblingProcess(context)) {
            Log.i(TAG, "skipped process sweep while a sibling app process is active - $reason")
            return 0
        }
        val packageName = context.packageName
        val me = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        val appProcesses = File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == me) return@mapNotNull null
            val name = try { File(entry, "cmdline").readText().substringBefore('\u0000') } catch (_: Exception) { "" }
            if (name == packageName || name.startsWith("$packageName:")) pid else null
        }.toSet()
        // Keep every other DroidDeck process and its Linux children. Main and external-display
        // sessions intentionally run side by side in separate Android processes.
        val parents = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles().orEmpty().forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val parent = parentOf(pid) ?: return@forEach
            parents.getOrPut(parent) { ArrayList() }.add(pid)
        }
        val protected = HashSet<Int>()
        val queue = ArrayDeque<Int>().apply { addAll(appProcesses) }
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!protected.add(pid)) continue
            (parents[pid] ?: continue).forEach(queue::addLast)
        }
        var killed = 0
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == me || pid <= 1 || pid in protected) return@forEach
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

    private fun parentOf(pid: Int): Int? {
        val stat = try { File("/proc/$pid/stat").readText() } catch (_: Exception) { return null }
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        return fields.getOrNull(1)?.toIntOrNull()
    }
}
