package com.droiddeck.launcher.session

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Identifies the dedicated Android process used to host a second-display Linux session. */
object SessionProcess {
    private const val TAG = "SessionProcess"
    private const val SECONDARY_SUFFIX = ":externalDisplay"
    private const val MAIN_SESSION_LOCK = ".droiddeck-main-session.lock"
    private const val EXTERNAL_SESSION_LOCK = ".droiddeck-external-session.lock"

    @Volatile private var heldLock: FileLock? = null
    @Volatile private var heldLockFile: RandomAccessFile? = null

    fun isExternalDisplay(context: Context): Boolean = processName(context) == context.packageName + SECONDARY_SUFFIX

    /**
     * Keeps the sibling session discoverable even when Android restricts cross-process /proc
     * reads. The service holds this process-specific file lock for the whole Linux session.
     */
    @Synchronized
    fun acquireSessionLock(context: Context): Boolean {
        if (heldLock?.isValid == true) return true
        val path = File(context.filesDir, ownLockName(context))
        val file = RandomAccessFile(path, "rw")
        val lock = try {
            file.channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            Log.w(TAG, "another session process already owns ${path.path}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "could not lock ${path.path}", e)
            null
        }
        if (lock == null) {
            file.close()
            return false
        }
        heldLockFile = file
        heldLock = lock
        return true
    }

    @Synchronized
    fun releaseSessionLock() {
        try {
            heldLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "could not release the session lock", e)
        }
        try {
            heldLockFile?.close()
        } catch (e: Exception) {
            Log.w(TAG, "could not close the session lock file", e)
        }
        heldLock = null
        heldLockFile = null
    }

    /** A sibling lock means its guest process tree must not be swept as an orphan. */
    fun hasSiblingSession(context: Context): Boolean = isLocked(File(context.filesDir, siblingLockName(context)))

    /** Includes an idle app process during display handoff, so legacy name-based sweeps stay safe. */
    fun hasSiblingProcess(context: Context): Boolean {
        if (hasSiblingSession(context)) return true
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val processes = try {
            manager.runningAppProcesses ?: return true
        } catch (e: Exception) {
            Log.w(TAG, "could not inspect sibling app processes; skipping cleanup", e)
            return true
        }
        return processes.any { process ->
            process.pid != android.os.Process.myPid() &&
                (process.processName == context.packageName || process.processName.startsWith(context.packageName + ":"))
        }
    }

    private fun isLocked(file: File): Boolean {
        val probe = try {
            RandomAccessFile(file, "rw")
        } catch (e: Exception) {
            Log.w(TAG, "could not inspect sibling session lock ${file.path}; skipping cleanup", e)
            return true
        }
        return try {
            val lock = probe.channel.tryLock()
            if (lock == null) true else {
                lock.release()
                false
            }
        } catch (_: OverlappingFileLockException) {
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not inspect sibling session lock ${file.path}; skipping cleanup", e)
            true
        } finally {
            try { probe.close() } catch (_: Exception) { }
        }
    }

    private fun ownLockName(context: Context): String =
        if (isExternalDisplay(context)) EXTERNAL_SESSION_LOCK else MAIN_SESSION_LOCK

    private fun siblingLockName(context: Context): String =
        if (isExternalDisplay(context)) MAIN_SESSION_LOCK else EXTERNAL_SESSION_LOCK

    private fun processName(context: Context): String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else readCommandLine(android.os.Process.myPid())

    private fun readCommandLine(pid: Int): String = try {
        File("/proc/$pid/cmdline").readText().substringBefore('\u0000')
    } catch (_: Exception) {
        ""
    }
}
