package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.core.FileUtils
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files

/** Installs the optional Plasma Mobile shell into its isolated Arch Linux ARM package root. */
object PlasmaMobileInstaller {
    @Synchronized
    fun install(context: Context, listener: LinuxRuntimeInstaller.ProgressListener?): String? {
        if (!LinuxRuntime.isInstalled(context)) return "The Linux runtime is not installed"
        if (!DesktopCatalog.desktopInstalled(context)) return "The Linux desktop package is not installed"
        listener?.onProgress("Preparing an isolated Linux runtime for KDE", -1)
        LinuxRuntime.preparePlasmaRoot(context, listener)?.let { return it }
        val root = LinuxRuntime.plasmaRootDir(context)
        if (!File(root, "usr/bin/pacman").isFile) return "This Linux runtime has no pacman package manager"
        val pacmanConfig = File(root, "etc/pacman.conf")
        if (!pacmanConfig.isFile) return "This Linux runtime has no pacman configuration"
        val originalPacmanConfig = try {
            pacmanConfig.readText()
        } catch (e: Exception) {
            return "Could not read the pacman configuration: ${e.message ?: e.javaClass.simpleName}"
        }

        val sessionRoot = File(context.filesDir, "plasma-install-session").apply { mkdirs() }
        val runtimeDir = File(context.filesDir, ".plasma-install-rt").apply { mkdirs() }
        val packageLock = File(root, "var/lib/pacman/db.lck")
        if (packageLock.exists()) {
            val age = System.currentTimeMillis() - packageLock.lastModified()
            if (age < 60_000L) return "The isolated package manager is still active; wait a moment and retry"
            if (!packageLock.delete()) return "Could not clear the stale isolated package-manager lock"
        }
        prepareKeyringPermissions(File(root, "etc/pacman.d/gnupg"))?.let {
            return "Could not make the isolated Arch package keyring writable: $it"
        }
        val guest = listOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root",
            "PATH=/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8", "TERM=xterm",
            // The isolated root inherits sync databases from the runtime image. Force fresh
            // repository databases so the rolling package filenames match the live mirrors.
            // Replace only paths present in incoming packages when the cloned runtime has
            // unowned files from its base image; the Steam root remains untouched.
            "/bin/sh", "-c",
            "/usr/bin/pacman-key --init && /usr/bin/pacman-key --populate archlinuxarm && " +
                "exec /usr/bin/pacman -Syyu --needed --noconfirm --overwrite '*' plasma-mobile",
        )
        val command = LinuxRuntime.commandForRoot(
            context, root, sessionRoot, runtimeDir, null, null, guest, true,
        )
        return try {
            pacmanConfig.writeText(withInstallerDownloadSettings(originalPacmanConfig))
            listener?.onProgress("Preparing KDE Plasma Mobile", -1)
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            builder.directory(root)
            val hostEnv = builder.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let {
                hostEnv["LD_LIBRARY_PATH"] = it
            }

            val process = builder.start()
            val tail = ArrayDeque<String>()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        if (tail.size == 5) tail.removeFirst()
                        tail.addLast(line.take(180))
                        val lower = line.lowercase()
                        listener?.onProgress(
                            when {
                                "synchronizing" in lower || "database" in lower -> "Refreshing KDE package list"
                                "downloading" in lower || "retrieving" in lower -> "Downloading KDE Plasma Mobile packages"
                                "installing" in lower || "checking" in lower -> "Installing KDE Plasma Mobile"
                                else -> "Installing KDE Plasma Mobile"
                            },
                            -1,
                        )
                    }
                }
            }
            val status = process.waitFor()
            if (status != 0) {
                val detail = tail.joinToString(" · ")
                return "pacman exited with status $status" + if (detail.isNotEmpty()) ": $detail" else ""
            }
            val packageMarker = DesktopCatalog.plasmaMobileMarker(context)
            FileUtils.writeString(packageMarker, "archlinuxarm\n")
            if (!packageMarker.isFile) return "Plasma Mobile packages installed, but DroidDeck could not save the installation marker"
            if (!DesktopCatalog.plasmaMobileInstalled(context)) {
                packageMarker.delete()
                return "pacman finished, but the Plasma Mobile shell was not found in the runtime"
            }
            null
        } catch (e: Exception) {
            "Could not install KDE Plasma Mobile: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try {
                pacmanConfig.writeText(originalPacmanConfig)
            } catch (e: Exception) {
                android.util.Log.e("PlasmaMobileInstaller", "could not restore pacman config", e)
            }
        }
    }

    /** Pacman downloads as its caller, without Landlock, inside the confined guest root. */
    private fun withInstallerDownloadSettings(config: String): String {
        val lines = config.lines().toMutableList()
        val optionsIndex = lines.indexOfFirst { it.trim().equals("[options]", ignoreCase = true) }
        if (optionsIndex < 0) {
            lines.add(0, "[options]")
            lines.add(1, "#DownloadUser =")
            lines.add(2, "DisableSandbox = true")
            return lines.joinToString("\n")
        }
        var sectionEnd = optionsIndex + 1
        while (sectionEnd < lines.size) {
            val line = lines[sectionEnd].trim()
            if (line.startsWith("[") && line.endsWith("]")) break
            sectionEnd++
        }
        val overrides = listOf("DownloadUser" to "#DownloadUser =", "DisableSandbox" to "DisableSandbox = true")
        for ((name, setting) in overrides) {
            val matcher = Regex("^\\s*#?\\s*${name}\\s*=.*$", RegexOption.IGNORE_CASE)
            var replaced = false
            for (index in optionsIndex + 1 until sectionEnd) {
                if (matcher.matches(lines[index])) {
                    lines[index] = setting
                    replaced = true
                    break
                }
            }
            if (!replaced) lines.add(sectionEnd++, setting)
        }
        return lines.joinToString("\n")
    }

    /** Keep GnuPG's private keyring writable by this app-private PRoot root. */
    private fun prepareKeyringPermissions(directory: File): String? {
        if (!directory.exists() && !directory.mkdirs()) return directory.path
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) return directory.path

        fun makeOwnerWritable(file: File): String? {
            if (Files.isSymbolicLink(file.toPath())) return file.path
            val ok = if (file.isDirectory) {
                file.setReadable(true, true) && file.setWritable(true, true) &&
                    file.setExecutable(true, true)
            } else {
                file.setReadable(true, true) && file.setWritable(true, true)
            }
            if (!ok) return file.path
            if (file.isDirectory) {
                val children = file.listFiles() ?: return file.path
                for (child in children) makeOwnerWritable(child)?.let { return it }
            }
            return null
        }

        return makeOwnerWritable(directory)
    }
}
