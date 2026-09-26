package com.droiddeck.launcher.runtime;

import android.content.Context;
import android.os.Process;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.system.StructUtsname;

import com.droiddeck.launcher.core.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * The glibc arm64 rootfs at {@code files/linuxfs} and the proot invocation that runs a program in
 * it as this app's own uid. It is a second runtime beside the Wine imagefs, not a container: no
 * Wine, no box64, no FEX. proot is packaged as {@code libproot.so} so the installer places it, with
 * its loader, in the native library directory - the only place an app on targetSdk 28 may execute
 * a file from.
 *
 * <p>Ported from WinNative's gamescope runtime (GPL-3.0).
 */
public final class LinuxRuntime {
    public static final String DIR = "linuxfs";
    public static final String SESSION_SCRIPT = "/usr/local/bin/bannerlator-session";
    public static final String MODE_DESKTOP = "desktop";
    public static final String MODE_STEAM = "steam";
    public static final String MODE_RUN = "run";
    /** Shortcut extra naming which of the modes above a Linux entry launches. */
    public static final String EXTRA_LINUX_MODE = "linux_mode";
    private static final String KGSL_DEVICE = "/dev/kgsl-3d0";
    private static final String GUEST_HOSTNAME = "DroidDeck";
    /** Where every Linux session's debug log lands: public, so a user can just hand the folder over. */
    public static final String DEBUG_LOG_DIR = "DroidDeck";

    public static File debugLogDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), DEBUG_LOG_DIR);
    }

    private LinuxRuntime() {}

    public static File rootDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    /** Isolated Arch root used by Plasma Mobile, so its rolling package update cannot change Steam's rootfs. */
    public static File plasmaRootDir(Context context) {
        return new File(context.getFilesDir(), DIR + "-plasma-mobile");
    }

    /**
     * Creates an independent package root from the installed runtime. This must be a real copy:
     * package hooks can edit installed files in place, which would modify the Steam root through
     * hard links. Volatile state and the user's home are kept separate.
     */
    public static String preparePlasmaRoot(Context context, LinuxRuntimeInstaller.ProgressListener listener) {
        File source = rootDir(context);
        File destination = plasmaRootDir(context);
        if (!isInstalled(context)) return "The Linux runtime is not installed";
        if (!new File(source, "usr/bin/labwc").isFile()) return "The Linux desktop package is not installed";
        String sourceStamp = plasmaSourceStamp(context);
        File stamp = new File(destination, ".droiddeck-source");
        if (new File(destination, "usr/bin/gamescope").isFile()
                && stamp.isFile() && sourceStamp.equals(FileUtils.readString(stamp))) return null;

        File staging = new File(context.getFilesDir(), DIR + "-plasma-mobile.new");
        FileUtils.delete(staging);
        FileUtils.delete(destination);
        long sourceBytes;
        try {
            sourceBytes = measurePlasmaTree(context, source, source);
        } catch (Exception e) {
            android.util.Log.e("LinuxRuntime", "could not measure runtime for Plasma Mobile", e);
            return "Could not read the Linux runtime files for KDE";
        }
        long reserve = Math.max(64L * 1024L * 1024L, sourceBytes / 20L);
        long available = new StatFs(context.getFilesDir().getPath()).getAvailableBytes();
        if (available < sourceBytes + reserve) {
            return "KDE needs about " + FileUtils.sizeToString(sourceBytes + reserve)
                    + " of free storage to make an isolated Linux runtime";
        }
        if (!staging.mkdirs()) return "Could not create the isolated KDE runtime directory";
        listenerProgress(listener, "Copying an isolated Linux runtime for KDE", -1);
        try {
            copyPlasmaTree(context, source, staging, source);
            FileUtils.writeString(new File(staging, ".droiddeck-source"), sourceStamp);
            if (!staging.renameTo(destination)) {
                FileUtils.delete(staging);
                return "Could not finish preparing the isolated KDE runtime";
            }
            return null;
        } catch (Exception e) {
            android.util.Log.e("LinuxRuntime", "could not prepare Plasma Mobile root", e);
            FileUtils.delete(staging);
            return "Could not prepare the isolated KDE runtime: " + e.getMessage();
        }
    }

    /** Measures without following links and skips app-private bind targets before opening them. */
    private static long measurePlasmaTree(Context context, File root, File current) throws IOException {
        String name = root.toPath().relativize(current.toPath()).toString().replace(File.separatorChar, '/');
        if (isPlasmaExcludedDirectory(context, name)) return 0L;
        BasicFileAttributes attrs = Files.readAttributes(current.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || !attrs.isDirectory()) return attrs.size();
        File[] children = current.listFiles();
        if (children == null) throw new IOException("could not list " + current);
        long bytes = 0L;
        for (File child : children) bytes += measurePlasmaTree(context, root, child);
        return bytes;
    }

    /** Copies without following links and checks bind targets before listing their source dirs. */
    private static void copyPlasmaTree(Context context, File root, File destinationRoot, File current) throws IOException {
        String name = root.toPath().relativize(current.toPath()).toString().replace(File.separatorChar, '/');
        Path sourcePath = current.toPath();
        Path targetPath = destinationRoot.toPath().resolve(root.toPath().relativize(sourcePath));
        if (isPlasmaExcludedDirectory(context, name)) {
            Files.createDirectories(targetPath);
            return;
        }
        BasicFileAttributes attrs = Files.readAttributes(sourcePath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink()) {
            Files.createDirectories(targetPath.getParent());
            Files.createSymbolicLink(targetPath, Files.readSymbolicLink(sourcePath));
            return;
        }
        if (!attrs.isDirectory()) {
            Files.createDirectories(targetPath.getParent());
            Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }

        Files.createDirectories(targetPath);
        File[] children = current.listFiles();
        if (children == null) throw new IOException("could not list " + current);
        for (File child : children) copyPlasmaTree(context, root, destinationRoot, child);
        try {
            Files.setPosixFilePermissions(targetPath, Files.getPosixFilePermissions(sourcePath));
        } catch (UnsupportedOperationException ignored) {
            // App-private Linux rootfs storage normally supports POSIX modes; keep the copy usable
            // on filesystems that do not expose them.
        }
    }

    private static String plasmaSourceStamp(Context context) {
        String version = FileUtils.readString(new File(rootDir(context), ".version"));
        String desktop = FileUtils.readString(new File(rootDir(context), ".droiddeck-pkg-desktop"));
        return (version == null ? "" : version.trim()) + "\n" + (desktop == null ? "" : desktop.trim());
    }

    private static boolean isPlasmaExcludedDirectory(Context context, String name) {
        if (name.equals("dev") || name.equals("proc") || name.equals("sys") ||
                name.equals("root") || name.equals("run") || name.equals("tmp") ||
                name.equals("storage") || name.startsWith("storage/") ||
                name.equals("mnt/bannerlator-sd") || name.startsWith("mnt/bannerlator-sd/") ||
                name.equals("var/cache") || name.startsWith("var/cache/") ||
                name.equals("var/tmp") || name.startsWith("var/tmp/")) return true;
        // PRoot binds these host directories into the guest at their same absolute paths. Earlier
        // sessions can leave those mount-point directories inside the source root; copying them
        // would walk back into filesDir and recursively encounter linuxfs itself.
        List<String> guestPaths = new ArrayList<>();
        for (File bind : new File[]{context.getFilesDir(), context.getCacheDir()}) {
            guestPaths.add(bind.getAbsolutePath());
        }
        // Android may return /data/data/<package> from Context while the guest bind target is
        // /data/user/<id>/<package>, or vice versa. Include both spellings plus dataDir's actual
        // files/cache locations so the runtime copy never descends into an app-private bind.
        String dataDir = context.getApplicationInfo().dataDir;
        if (dataDir != null) {
            guestPaths.add(new File(dataDir, "files").getAbsolutePath());
            guestPaths.add(new File(dataDir, "cache").getAbsolutePath());
        }
        String packageName = context.getPackageName();
        int userId = Process.myUid() / 100000;
        guestPaths.add("/data/user/" + userId + "/" + packageName + "/files");
        guestPaths.add("/data/user/" + userId + "/" + packageName + "/cache");
        guestPaths.add("/data/data/" + packageName + "/files");
        guestPaths.add("/data/data/" + packageName + "/cache");
        for (String path : guestPaths) {
            if (!path.startsWith(File.separator)) continue;
            String guestPath = path.substring(1).replace(File.separatorChar, '/');
            if (name.equals(guestPath) || name.startsWith(guestPath + "/")) return true;
        }
        // The host path can be exposed through an alias that differs from both Context and
        // ApplicationInfo. In the guest root, DroidDeck's bind targets are still identifiable by
        // the package directory followed by files/ or cache/.
        String packageMarker = "/" + context.getPackageName() + "/";
        int packageIndex = name.indexOf(packageMarker);
        if (packageIndex >= 0) {
            String bindTarget = name.substring(packageIndex + packageMarker.length());
            if (bindTarget.equals("files") || bindTarget.startsWith("files/") ||
                    bindTarget.equals("cache") || bindTarget.startsWith("cache/")) return true;
        }
        return false;
    }

    private static void listenerProgress(LinuxRuntimeInstaller.ProgressListener listener, String stage, int percent) {
        if (listener != null) listener.onProgress(stage, percent);
    }

    /** Whether the copy still matches the installed runtime and desktop package. */
    public static boolean isPlasmaRootCurrent(Context context) {
        File stamp = new File(plasmaRootDir(context), ".droiddeck-source");
        return stamp.isFile() && plasmaSourceStamp(context).equals(FileUtils.readString(stamp));
    }

    /**
     * The session's own small tree beside the rootfs: the fake evdev nodes and their rings. In
     * Bannerlator these lived in the Wine imagefs; here there is no Wine, so the session owns them.
     * Bound into the guest at its own host path, so nothing needs translating.
     */
    public static File sessionRoot(Context context) {
        return new File(context.getFilesDir(), "session");
    }

    /** Per-session input and PTY files for the compositor hosted on an external display. */
    public static File sessionRoot(Context context, boolean externalDisplay) {
        return new File(context.getFilesDir(), externalDisplay ? "session-external-display" : "session");
    }

    /** Where the runtime carries the host-side proot; see tools/linuxfs/prebuilt/proot/README.md. */
    private static final String HOST_DIR = "opt/android-host";

    public static File prootBinary(Context context) {
        File packaged = new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
        if (packaged.isFile()) return packaged;
        return new File(rootDir(context), HOST_DIR + "/proot");
    }

    public static File prootLoader(Context context) {
        File packaged = new File(context.getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
        if (packaged.isFile()) return packaged;
        return new File(rootDir(context), HOST_DIR + "/loader");
    }

    /** proot links against libtalloc, which sits beside it; empty when the apk copy is in use. */
    public static String prootLibraryPath(Context context) {
        File dir = new File(rootDir(context), HOST_DIR);
        return dir.equals(prootBinary(context).getParentFile()) ? dir.getPath() : "";
    }

    /** The rootfs is present with gamescope and the session script the launcher hands control to. */
    public static boolean isInstalled(Context context) {
        File root = rootDir(context);
        return new File(root, "usr/bin/gamescope").isFile()
                && new File(root, SESSION_SCRIPT.substring(1)).isFile()
                && prootBinary(context).isFile()
                && prootLoader(context).isFile();
    }

    /** The Vulkan ICD manifest the rootfs ships for the device GPU, or null when it has none. */
    public static File vulkanIcd(Context context) {
        return vulkanIcd(rootDir(context));
    }

    public static File vulkanIcd(File root) {
        File icdDir = new File(root, "usr/share/vulkan/icd.d");
        File[] manifests = icdDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (manifests == null) return null;
        for (File manifest : manifests) {
            if (manifest.getName().contains("freedreno")) return manifest;
        }
        return manifests.length > 0 ? manifests[0] : null;
    }

    /**
     * The proot command line running {@code guestCommand} inside the rootfs. Host paths the session
     * needs - the app's files directory for the compositor and audio sockets, external storage for
     * the user's games - are bound at their own paths, so nothing on either side needs translating
     * and proot never touches the fds a dma-buf travels in. Android has no /dev/shm; a directory
     * under the cache stands in, which glibc's shm_open and Chromium's shared memory accept.
     */
    public static List<String> command(Context context, File sessionRoot, File runtimeDir,
                                       File externalStorage, List<String> guestCommand) {
        return command(context, sessionRoot, runtimeDir, externalStorage, null, guestCommand);
    }

    /** As above, plus {@code host:guest} bind specs - the installed games handed to Steam. */
    public static List<String> command(Context context, File sessionRoot, File runtimeDir,
                                       File externalStorage, List<String> extraBinds,
                                       List<String> guestCommand) {
        return commandForRoot(context, rootDir(context), sessionRoot, runtimeDir, externalStorage, extraBinds, guestCommand);
    }

    public static List<String> commandForRoot(Context context, File root, File sessionRoot, File runtimeDir,
                                              File externalStorage, List<String> extraBinds,
                                              List<String> guestCommand) {
        return commandForRoot(context, root, sessionRoot, runtimeDir, externalStorage, extraBinds, guestCommand, false);
    }

    /** As above, optionally giving package installers PRoot's fake-root identity inside the guest. */
    public static List<String> commandForRoot(Context context, File root, File sessionRoot, File runtimeDir,
                                              File externalStorage, List<String> extraBinds,
                                              List<String> guestCommand, boolean fakeRoot) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBinary(context).getPath());
        cmd.add("--kill-on-exit");
        // Preserve the host kernel identity while giving the guest the app's branded host name.
        cmd.add("--kernel-release=" + guestUtsname());
        // Android's app seccomp policy traps the whole set*id family. Xwayland's Popen() calls
        // setgid()/setuid() before it execs xkbcomp and _exit(127)s when they fail, so without
        // this the keymap never compiles and Xwayland dies. -i makes proot answer those calls
        // itself while still reporting our real ids, so nothing inside sees a different user.
        if (fakeRoot) {
            cmd.add("-0");
        } else {
            int uid = Process.myUid();
            cmd.add("-i");
            cmd.add(uid + ":" + uid);
        }
        cmd.add("-r");
        cmd.add(root.getPath());
        cmd.add("-w");
        cmd.add("/root");
        bind(cmd, "/dev");
        bind(cmd, "/proc");
        bind(cmd, "/sys");
        bind(cmd, "/dev/urandom:/dev/random");
        bind(cmd, "/proc/self/fd:/dev/fd");
        bind(cmd, "/proc/self/fd/0:/dev/stdin");
        bind(cmd, "/proc/self/fd/1:/dev/stdout");
        bind(cmd, "/proc/self/fd/2:/dev/stderr");
        bind(cmd, new File(root, "etc/bannerlator/empty").getPath() + ":/sys/fs/selinux");
        bind(cmd, context.getFilesDir().getPath());
        bind(cmd, context.getCacheDir().getPath());
        bind(cmd, runtimeDir.getPath());
        if (sessionRoot != null) bind(cmd, sessionRoot.getPath());
        if (externalStorage != null && externalStorage.isDirectory()) {
            bind(cmd, externalStorage.getPath());
        }
        File shm = new File(context.getCacheDir(), "shm");
        shm.mkdirs();
        bind(cmd, shm.getPath() + ":/dev/shm");

        // Android denies apps these; glibc, Steam and libcap read them at startup.
        File fakeProc = new File(root, "etc/bannerlator/proc");
        // libpci picks its procfs backend on whether it can read the /proc/bus/pci directory, which
        // the app can, then die()s - exit(1) on the calling process - on the devices file inside
        // it, which the app cannot. Chromium loads libpci in its GPU process to name the video
        // card, so that exit kills the process; after a few tries CEF gives up on hardware and
        // draws the rest of the session on SwiftShader: the client's interface rendered on the
        // CPU. An empty list is the truthful answer from in here - nothing the app can see is on
        // a PCI bus. Created at session start rather than shipped in the rootfs so an installed
        // runtime is fixed too, and the table's guard below binds it only when the real file
        // cannot be read, so it can never stand in front of real data. (WinNative, maxjivi05,
        // deff1ac6, via Bannerlator 8fb668d1: 44 -> 85 fps scrolling the Big Picture library on a
        // OnePlus 15, GPU-process crashes 12 -> 0.)
        File pciDevices = new File(fakeProc, "pci_devices");
        if (!pciDevices.isFile()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                pciDevices.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                pciDevices.createNewFile();
            } catch (java.io.IOException e) {
                // It then fails the isFile() test below and the session runs as it did before.
            }
        }
        String[][] procFiles = {
                {"pci_devices", "/proc/bus/pci/devices"},
                {"stat", "/proc/stat"},
                {"version", "/proc/version"},
                {"loadavg", "/proc/loadavg"},
                {"uptime", "/proc/uptime"},
                {"vmstat", "/proc/vmstat"},
                {"cap_last_cap", "/proc/sys/kernel/cap_last_cap"},
                {"overflowuid", "/proc/sys/kernel/overflowuid"},
                {"overflowgid", "/proc/sys/kernel/overflowgid"},
        };
        for (String[] entry : procFiles) {
            File fake = new File(fakeProc, entry[0]);
            if (fake.isFile() && !new File(entry[1]).canRead()) {
                bind(cmd, fake.getPath() + ":" + entry[1]);
            }
        }
        bindGpuNode(context, cmd);
        if (extraBinds != null) {
            for (String spec : extraBinds) bind(cmd, spec);
        }
        cmd.addAll(guestCommand);
        return cmd;
    }

    /**
     * An app process may not open {@code /dev/dri} - the nodes exist but are labelled
     * {@code graphics_device}, which stock policy grants surfaceflinger and not us - yet libdrm and
     * everything built on it identify a GPU by its render node, and gamescope refuses to offer
     * linux-dmabuf without one. The KGSL device Turnip actually drives ({@code gpu_device}, which we
     * may open) stands in: it appears as a render node with the sysfs entries libdrm reads, and our
     * Turnip build reports the same device numbers for it.
     */
    private static void bindGpuNode(Context context, List<String> cmd) {
        StructStat st;
        try {
            st = Os.stat(KGSL_DEVICE);
        } catch (ErrnoException e) {
            return;
        }
        long dev = st.st_rdev;
        long major = ((dev >> 8) & 0xfff) | ((dev >> 32) & ~0xfffL);
        long minor = (dev & 0xff) | ((dev >> 12) & ~0xffL);
        String node = "renderD" + minor;
        File base = new File(context.getCacheDir(), "drm");
        File dri = new File(base, "dri");
        File device = new File(base, "sys/" + major + ":" + minor + "/device");
        File drm = new File(device, "drm/" + node);
        try {
            if ((!dri.isDirectory() && !dri.mkdirs()) || (!drm.isDirectory() && !drm.mkdirs())) {
                return;
            }
            new File(dri, node).createNewFile();
            Files.write(new File(drm, "dev").toPath(),
                    (major + ":" + minor + "\n").getBytes(StandardCharsets.UTF_8));
            Files.write(new File(device, "uevent").toPath(),
                    "DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\n".getBytes(StandardCharsets.UTF_8));
            File subsystem = new File(device, "subsystem");
            if (!Files.isSymbolicLink(subsystem.toPath())) {
                Os.symlink("/sys/bus/platform", subsystem.getPath());
            }
        } catch (IOException | ErrnoException e) {
            return;
        }
        bind(cmd, new File(base, "sys").getPath() + ":/sys/dev/char");
        bind(cmd, dri.getPath() + ":/dev/dri");
        bind(cmd, KGSL_DEVICE + ":/dev/dri/" + node);
    }

    private static void bind(List<String> cmd, String spec) {
        cmd.add("-b");
        cmd.add(spec);
    }

    /** PRoot's complex -k format: sysname, nodename, release, version, machine, domain, HWCAP. */
    private static String guestUtsname() {
        StructUtsname host = Os.uname();
        return "\\" + host.sysname + "\\" + GUEST_HOSTNAME + "\\" + host.release
                + "\\" + host.version + "\\" + host.machine + "\\localdomain\\-1\\";
    }

    /** X access control and Steam look the session user up by uid: the app uid is root inside. */
    public static void writeAccounts(Context context) throws IOException {
        writeAccounts(context, rootDir(context));
    }

    public static void writeAccounts(Context context, File root) throws IOException {
        int uid = Process.myUid();
        Files.write(new File(root, "etc/passwd").toPath(),
                ("root:x:" + uid + ":" + uid + ":root:/root:/bin/bash\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "etc/group").toPath(),
                ("root:x:" + uid + ":\n").getBytes(StandardCharsets.UTF_8));
    }
}
