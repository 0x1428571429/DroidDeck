package com.droiddeck.launcher.core

import java.io.File

object ZramSupport {
    class Status(val swapTotalKb: Long, val swapFreeKb: Long, val kernelOk: Boolean) {
        val supported: Boolean get() = kernelOk && swapTotalKb > 0
    }

    fun read(): Status {
        var total = 0L
        var free = 0L
        runCatching {
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("SwapTotal:") -> total = kb(line)
                    line.startsWith("SwapFree:") -> free = kb(line)
                }
            }
        }
        return Status(total, free, kernelAtLeast(System.getProperty("os.version").orEmpty(), 5, 4))
    }

    fun kernelAtLeast(release: String, major: Int, minor: Int): Boolean {
        val parts = Regex("^(\\d+)\\.(\\d+)").find(release)?.groupValues ?: return false
        val ma = parts[1].toInt()
        val mi = parts[2].toInt()
        return ma > major || (ma == major && mi >= minor)
    }

    private fun kb(line: String): Long = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
}
