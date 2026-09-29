# DroidDeck - Performance findings

Why DroidDeck 0.2.0 ran slower than WinNative and Bannerlator for many people, what was found, what was
changed, what was measured, and what is still open. Written 2026-09-29 from the Discord reports, the GitHub
issues, and a day of measurement on an AYN Thor (SD 8 Gen 2, Adreno 740). Companion to
[progress-log.md](progress-log.md).

## What people reported

- **Discord (#general, 2026-09-26 to 09-29).** The same game at ~90-100 fps in WinNative and ~30-40 in
  DroidDeck 0.1.7, ~25 in 0.2.0 (Adreno 732); "15 fps in a 2D Unity game" on an 8 Elite while Bannerlator's
  Steam client is fine; "20 fps / 7 fps in the Steam client, all renders on CPU" on an Adreno 8xx; Bannerlator
  locked at 120 fps where DroidDeck is not; "less performant, more compatible" as the usual summary.
- **[#65](https://github.com/Droid-Deck/DroidDeck/issues/65)** - the best report: Silksong at 85-90 fps on
  0.1.5 and 37-50 on 0.2.0 on a Fold8, GPU 82% busy vs 16%, proot 3-5x busier, the game's main thread waiting
  on `futex` and the wineserver pipe. History table: 90 (0.1.5), 103 (0.1.6), 58/110/48 (0.1.7), 47-50 (0.2.0).
- **[#64](https://github.com/Droid-Deck/DroidDeck/issues/64)** - Samsung's SSRM clamps CPU/GPU to ~60% in a
  session (Tab S8 Ultra). A platform limit, separate from everything below; no sanctioned way around it found.

## Cause 1: proot's kompat extension traced every hot syscall (fixed, PR #81)

`--kernel-release` (added 2026-09-25 to give the guest the `DroidDeck` hostname, `53ce764`) loads proot's
kompat extension. Its filter list includes `futex`, `epoll_pwait`, `fcntl`, `pselect6`, `pipe2`, `eventfd2`,
`socket`, so each of those from every guest thread stopped in the single tracer, and it drops
`AT_SYSINFO_EHDR` on every `execve`, so glibc ran without the vDSO. With the real kernel release passed, every
one of those handlers is a no-op. Patch `tools/proot/patches/0011-kompat-utsname-only.patch` traces only
`uname`/`sethostname`/`setdomainname` in that case.

Guest micro-benchmarks (same rootfs, proot with vs without the patch):

| | old + `--kernel-release` | patched | no flag at all |
|---|---|---|---|
| futex ping-pong | 465 us | 102 us | 96 us |
| `epoll_pwait(0)` | 60 us | 0.87 us | 0.8 us |
| `fcntl(F_GETFL)` | 40-107 us | 0.39 us | 0.38 us |
| `clock_gettime` | 0.34 us | 0.19 us | 0.11-0.19 us |

A-B-A in Once Upon a KATAMARI (standing still, only `libproot.so` swapped): +2-5% fps, proot CPU ~56% ->
~35%. Small there because Katamari is limited by translated game code; sync-heavy games should gain more.

## Cause 2: a crash-looping mangoapp in Deck mode (fixed, PR #81)

`gamescopereaper --respawn -- mangoapp` restarts the overlay the moment it exits, with no backoff. On a build
whose session preload lacked the System V message queue emulation, mangoapp died right after creating its
swapchain and was restarted 132 times in ~2.5 minutes; each restart is a full exec under proot and the Steam
menu itself lagged. Local builds that skip rebuilding `libblsession.so` hit this; CI builds do not. The wrapper
(`tools/mangoapp/mangoapp`) now parks after more than 5 starts in 60 s.

## What Bannerlator and WinNative do differently

Read from their sources and a device that has Bannerlator installed:

- **Same proot patches** (0001-0009 from WinNative); the Steam client mode uses the same Valve ARM64 Proton, so
  **the same server-side Wine synchronisation** (`wineserver: using server-side synchronization` in every
  session log here). No structural gap remains once kompat is out.
- **Defaults differ.** Bannerlator ships Deck mode off (it notes Steam Input takes the pad and breaks games),
  never passes `-steamos3`, does not set gamescope's realtime queue, runs xalia. WinNative defaults to
  `TU_DEBUG=noconform,sysmem`, `ZINK_DEBUG=compact`, FEX `PERFORMANCE_TSO`, vkd3d shader model 6_6, and never
  turns on sustained performance mode.

## Changes on `perf/steam-client-parity`

- xalia skipped by default (it ran ~10% of a core beside every game under FEX).
- gamescope realtime Vulkan queues: new toggle (Performance -> Client interface), **off** by default.
- Microphone off by default; no permission prompt at start-up.
- FEX default preset `PERFORMANCE_TSO`; `TU_DEBUG=noconform` always (plus `sysmem` for the 710-720 drivers or
  when chosen); `ZINK_DEBUG=compact`; vkd3d shader model 6_6, feature level left to vkd3d-proton.
- Sustained performance mode is opt-in (Performance page); PerfMode logs "sustained mode off".
- The session gets the panel's fastest mode's refresh rate instead of the rate at the moment the surface arrived.
- Session polling without forks: the fill-screen and stop watchers wait on a FIFO read timeout and read files
  with builtins; the redistributable seeder and the compat registrar (Python) start only when a prefix or the
  Steam config actually changed, not every 5-15 s.
- The Steam page shows the saved Deck mode before the Performance page has been opened (it showed "off" while
  the session ran Deck mode with mangoapp).
- `tools/build_local.sh`: caches its pinned downloads, skips an unchanged proot build, and says why it rebuilds
  the Docker image; a warm build is ~30 s (it used to hang on a registry fetch when the daemon briefly reported
  the image missing).
- Turnip **sysmem** was made a default and then reverted: it gained nothing on an Adreno 740.

## How it was measured

- **fps:** `BannerWayland [stats]` lines in logcat (`adb logcat -s BannerWayland:I`), every 10 s. No `su`, no
  process on the device. The "screen" number counts presents and runs ~1.5x the game's own overlay counter in
  Alan Wake (60 vs 38-40): compare like with like, and read the overlay for the game's real rate.
- **Scene:** Alan Wake's American Nightmare, first gameplay spot ("Secure the primary pipes"), standing still.
  Reached without touching the screen: `~/.bl-autolaunch` is ignored once Steam is up, so the game is started with
  `steam steam://rungameid/<id>` from a second proot invocation (the client forwards it), and A is pressed with
  `sendevent` on the device's own gamepad node (`/dev/input/event12`) - the app only forwards events from a real
  controller device, so `adb shell input` presses are dropped.
- **Thermals:** the SoC reaches ~92-95 C in any run, so each run starts only after the CPU zones are under 55 C.
  A one-shot comparison made hot was misleading (the new build read 52-54 fps then, 58-61 cooled).

Alan Wake, cooled starts, fan at max, 60 s each (screen counter, six 10 s samples):

| Build | screen fps | game overlay | proot CPU | wineserver CPU |
|---|---|---|---|---|
| `main` (kompat fix, old defaults) | 56.9-58.6 (avg 57.7) | 39 | ~39% | ~30% |
| new defaults | 58.6-61.5 (avg 59.6) | 38-39 | ~27% | ~34% |
| new defaults, sysmem off | 59.3-62.9 (avg 61.1) | 38 | - | - |

Read: the defaults are worth at most a few percent here and inside the run-to-run noise (about +-1.5 fps). The
game's own frame rate stayed at 38-40 with CPU ~65% and GPU ~56-60%, so this scene is bound by something none
of these settings touch.

## Open leads, biggest first

1. **Wine synchronisation.** Proton 11 dropped esync; fsync needs `futex_waitv`, which Android's app sandbox
   blocks even on a 6.12 kernel; ntsync is not in Android kernels. Every lock and event is a pipe round trip to
   wineserver (30-34% CPU in Alan Wake, 18% of the main thread blocked in `read()` in #65). Winlator-family apps
   run esync, which needs only `eventfd` and `poll`. Restoring esync in the ARM64 Proton is the largest
   likely win and the largest piece of work; emulating `futex_waitv` in proot puts every wait back through the
   tracer and is probably a dead end.
2. **The game's main thread under FEX.** Alan Wake's process uses 139-178% CPU with neither the CPU nor the GPU
   saturated overall. FEX settings beyond the presets (SMC checks, multiblock, TSO variants) are worth a sweep
   on a game whose main thread is the limit.
3. **#65's other 0.1.5 -> 0.2.0 differences, untested:** the fake `power_supply` tree, `BL_HDR`/`DXVK_HDR`, the
   output width (1280 -> the panel's shape), `FEX_TSOENABLED` (1 -> 0), the tracer binary. Their Silksong setup
   is sync-heavy and is the best test of the kompat fix.
4. **gamescope** calls `XQueryPointer` on every vblank in the Steam UI (patch `0100`); query only when a
   gamepad cursor move is pending. Needs a new pinned gamescope release.
5. **Reports not yet reproduced:** software rendering on Adreno 8xx ("all renders on CPU"), Deck mode using
   most of the RAM, Samsung SSRM clamping (#64), two DroidDeck packages both holding a session.
6. **Smaller:** proot tracer pinned to a big core (Max's note: no core wake-up lag), `steamrtarm64` on Proton's
   `LD_LIBRARY_PATH`, the always-on wake and Wi-Fi locks, ADPF hints fed frame intervals instead of work time.
