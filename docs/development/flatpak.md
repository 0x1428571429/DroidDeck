# Flatpak and the Store

The Store rail section installs apps from Flathub into the Linux runtime with Flatpak. Only
ARM64 builds are listed. Everything lives in one per-user installation at
`/root/.local/share/flatpak`, so nothing needs Flatpak's system helper or polkit.

## Putting Flatpak in the runtime

`bannerlator-flatpak-setup` runs under proot's fake root (`-0`), because pacman and pacman-key
refuse any other uid. The runtime's pacman database lists only the base image. The desktop
and emulator packages were unpacked over it without registering. A plain `pacman -S flatpak`
would therefore reinstall about a hundred packages, Mesa among them, over the runtime's KGSL
Turnip build, and run every hook in the image, mkinitcpio included. So pacman, with a
database of its own under `/var/cache/droiddeck-flatpak`, only downloads the nine packages
Flatpak adds to this image and checks their signatures against the Arch Linux ARM keyring.
The script then unpacks them without hooks and checks what the binaries link. Libraries that
the Desktop package normally brings (PyGObject, json-glib, fuse3 and others) are fetched too,
but only when their files are missing. Flathub is added as a per-user remote from a copy of
its `.flatpakrepo` carried in the script, so that step needs no network. Every store command
writes its output to `Download/DroidDeck/flatpak-<verb>.log`, next to the session logs. The
setup script also puts Flatpak's own error text in the failure message the app shows.

## bubblewrap without namespaces

Android gives apps no user namespaces, so `bwrap` cannot work. Flatpak is pointed at
`bannerlator-bwrap` instead (`FLATPAK_BWRAP`, exported by `bannerlator-session` and by the
store's commands). It reads bwrap's command line, including `--args` fds and the `--file` and
`--bind-data` payloads, and then does one of two things:

- A sandbox that remaps nothing runs directly. Flatpak's install triggers (`--ro-bind / /`) and
  the D-Bus proxy (every top-level directory bound onto itself) are the cases, and they keep the
  file descriptors Flatpak handed them.
- Anything else is described to the app over the abstract socket
  `com.droiddeck.launcher.bwrap`, which serves only the app's own uid. `BwrapSpawner` builds the
  tree under `cache/bwrap/<n>`: a root directory for top-level links, directories and files,
  scratch directories for `--tmpfs` inside a bind, and every `--bind` translated from guest
  to host paths through the session's own binds. It then starts it as a proot of its own
  beside the session's. Output, the pid (for `--info-fd`) and the exit status flow back, and
  the stand-in exiting kills the sandbox.

A proot nested in the session's proot would also work, but every system call then goes
through two tracers, about thirty times slower at a program's start.

The spawner also adds what the rootfs gives its own programs:

- **GPU.** Flathub's Mesa has Turnip only for DRM, and Adreno on Android is KGSL. The runtime's
  own `libvulkan_freedreno.so` is bound in with the three libraries the Freedesktop runtime
  lacks (`libdisplay-info`, SPIRV-Tools). Vulkan uses it, and Mesa's GL runs on it through Zink
  (`MESA_LOADER_DRIVER_OVERRIDE=zink`). This is skipped for an app drawing into a desktop
  composited by pixman, where Zink cannot present.
- **Controllers.** The libraries in the rootfs's `/etc/ld.so.preload` (the session shim and the
  fake evdev reader) go in through `LD_PRELOAD`, with the session's `dev` directory.
- **Browsers.** Firefox's child sandboxes are switched off (`MOZ_DISABLE_*_SANDBOX`), and
  Chromium and Electron apps use zypak's mimic strategy (`ZYPAK_ZYGOTE_STRATEGY_SPAWN=0`). The
  Flatpak portal's Spawn refuses a caller whose `/proc/<pid>/root` holds no `.flatpak-info`,
  and a proot sandbox's root is the host's.
- **CPU.** `/proc/cpuinfo` without the cores' part numbers. Snapdragon's ARMv9 cores imply SVE2
  to LLVM, Qualcomm leaves SVE off, and llvmpipe's first shader died with SIGILL.

## Running apps

The front end starts an app as a run-mode session of `bannerlator-flatpak-run <app-id>`, full
screen under gamescope. Under gamescope it passes `--nosocket=wayland --socket=x11` and sets
`XDG_SESSION_TYPE=x11`. Otherwise Flatpak finds the app compositor's `wayland-0` and the window
opens behind gamescope. gamescope's own Wayland socket is no alternative: Chromium on Wayland
asks the render node it names for a DRM version, which KGSL cannot give, and aborts. The
launcher also starts a session bus when there is none. On the Linux desktop, the apps' exported
menu entries appear in the LXQt menu. While the desktop is composited by pixman, games are
wrapped through `droiddeck-gpu` like the rootfs's own.

## The store

`FlathubApi` reads flathub.org's public API: collections, search filtered to `aarch64`, and
AppStream details. `bannerlator-flatpak` drives libflatpak through PyGObject and prints one
JSON object per line (`op`, `progress`, `error`, `done`). `FlatpakManager` turns those lines
into the store's progress bar. Store commands run in a proot of their own, and
`OrphanReaper` spares them when a session starts.
