# Settings layout

DroidDeck configuration now lives in a searchable Settings hub. Updates remains a
separate launcher tab. The hub links to the existing managers and uses the existing
preference stores and callbacks; there is no preference migration.

## Where controls moved

| Previous location | New home | Reason |
| --- | --- | --- |
| Setup → Launcher | General | Appearance and Android Home behavior are launcher settings. |
| Steam/Desktop settings → Display and HDR | Display & graphics | Resolution, frame limits, scaling and real HDR output belong together. Resolution, HDR and frame limit keep their mode-specific scope; aspect ratio and scaling remain shared. |
| Setup → Session → Frame generation | Display & graphics | Frame generation is a graphics choice rather than a startup behavior. Existing import and availability checks remain. |
| Session menu → Effects and texture filtering | Display & graphics, with the session controls retained | Saved defaults are discoverable before a session starts. The session menu still applies its live effects using the same controls. Simulated HDR is labeled separately from HDR10 output. |
| Setup → Controller; Steam/Desktop settings → Touch controls | Controls & input | Controller appearance, mapping, rumble, touch behavior, Steam controller identity and Back action order have one configuration home. |
| Steam settings → Audio | Audio & microphone | Game audio and Steam-menu audio have different backends; naming the target prevents a toggle from appearing to affect both. |
| Steam settings → Storage and Added games; Setup tools → ROMs and Files | Library & storage | An additional Steam library differs from importing an existing game directory. Folder and executable selectors remain available alongside artwork controls. |
| Steam/Desktop settings → Session, Startup, Client, Network, Decky and Desktop renderer; Setup → Offline | Sessions | These options determine how sessions start, suspend and integrate with Android. Steam-only controls remain Steam-only. |
| Launcher rail → Components; Setup tools → Protons and Performance; Steam settings → Game settings | Compatibility & performance | GPU drivers, Proton builds, component swaps and execution tuning are related troubleshooting tools. Specialist pages keep their own operations and progress indicators. |
| Setup → Overview and logs; Steam settings → Storage diagnostics | Support & diagnostics | Runtime readiness, child-process repair, logging and storage diagnostics are actionable troubleshooting controls. |
| Launcher rail → Updates | Updates, unchanged | Releases, test builds, update channels and build information remain directly accessible. Support links to this tab. |

Advanced FEX, synchronization, environment, effects, texture and storage diagnostic
controls stay in expandable groups. Searching can open these groups automatically;
users can still collapse them.

## Control and workflow inventory against `main`

This inventory compares the six pre-hub screens on `main` with the category
content and specialist pages. It includes controls that appear only for a
supported device, Steam mode, an installed runtime, or an active session.

| Previous source | Controls and workflows in the old screen | Current home and retained workflow |
| --- | --- | --- |
| `FrontEndSetup.kt` | Launcher theme; Android home-app enablement and conditional default-home picker; launcher fullscreen and animation; Flathub visibility; controller layout/mapping controls; Back-button action order; offline-account toggle; frame-generation mode and Lossless Scaling import; session-log enable/share/clear; Files and ROM-folder tools; Proton and Performance shortcuts; system checks for GPU support, runtime readiness/update/removal, phantom-process limit and sign-in. | General keeps launcher appearance/home/store controls; Controls keeps controller settings and Back order; Sessions keeps offline mode; Display & graphics keeps frame generation/import; Library & storage keeps Files and ROM folders; Compatibility & performance opens Components, Proton and Performance managers; Support & diagnostics keeps GPU/runtime/process-limit checks, developer-options and wireless-ADB recovery, account status and log controls. The Updates action links to the existing Updates tab. The process-limit repair/detail buttons, runtime progress and enabled/disabled states remain conditional. |
| `ModeSettingsDialog.kt` | Per-mode resolution presets plus custom width/height entry, aspect ratio, FPS cap, scaler and sharpness, HDR10, GPU-driver shortcut, touch mode, Steam on-screen-controller mode and controller identity, Back order, suspend policy, automatic PiP and PiP shortcut, Steam startup, Decky check/install/update/uninstall/enable/plugin-ZIP workflows, Steam Deck mode and branch, MangoHud, Wi-Fi discovery with permission explanation/system-settings route, imported game folders and forget action, artwork, per-folder executable selection/import, game-storage location/import, storage diagnostics, FEX, synchronization backend, per-game environment, fill/16:9 and Desktop renderer. | Display & graphics owns resolution/custom dialog, ratio, FPS, scaling/sharpness, HDR10, fill/16:9 and MangoHud; Steam/Desktop selection still scopes mode-specific values while ratio/scaler defaults remain shared. Controls owns touch and Steam controller identity, with Back order alongside controller setup. Audio & microphone owns direct game audio, separately labeled Steam-menu audio and microphone. Library & storage owns game imports/executable choices/artwork and second-library selection/folder import; storage diagnostics is under Support. Sessions owns suspend, PiP auto-entry, startup, Decky management, Steam Deck mode/branch, Wi-Fi discovery and Desktop renderer. Compatibility & performance owns the GPU-driver shortcut plus FEX, sync backend and game environment in an expandable group. Steam-only, Desktop-only, permission-dependent and Decky/runtime/session-running gates remain in place. |
| `ComponentsPage.kt` | GPU-driver tabs and Android/Linux driver management; release refresh, download/import, selection/removal and bundled Android-driver restore; Proton/component selectors; per-Proton FEX, DXVK and VKD3D original/package status; next-game queued swaps and cancellation; catalog refresh/download; WCP import; restore-original and delete confirmations; in-use/game-running status and About dialog. | Compatibility & performance opens the existing Components manager. GPU search opens its GPU view directly. The manager retains its tabs, progress, confirmation dialogs, queued-operation state, per-Proton view and driver import/restore actions. |
| `ProtonPage.kt` | Available Proton build list; install, remove and cancel-queued operations; runtime-not-ready explanation; install/remove/session-running availability gates and progress. | Compatibility & performance → Proton versions opens the same manager; runtime requirements, queue cancellation and operation gates remain there. |
| `PerformanceDialog.kt` | Client CPU-core override and client-core selection; game-core selection; GL threading, Zink, GL-error handling and Gamescope real-time priority; GPU-clock pinning; Turnip system-memory behavior; Xalia; esync fallback, fsync-first and fast sync; proot seccomp and dependent fast path; guest-hostname validation/edit; phantom-process warning. | Compatibility & performance → Performance opens the existing specialist page. Core multi-selects, conditional controls, hostname validation and warning remain on that page. FEX and the game synchronization selector are also discoverable directly in the expandable Compatibility group; their specialist workflows remain intact. |
| `SessionOverlay.kt` | Live HUD/fill/scaling/sharpness/frame-generation controls; effects preset, CAS/strength, fake HDR, debanding/strength, brightness, contrast, gamma, saturation, FXAA, toon, CRT and NTSC; texture anisotropy and LOD bias; live component selection/queued-swap cancellation; touch/OSC and virtual-controller rumble/Steam/QAM/keyboard buttons; Back order; physical/Android keyboard launch; second-screen mode/display; Android app launch; suspend policy, ratio, FEX/game environment, log sharing, PiP entry/auto-entry, background and stop-session confirmation; Steam-menu and QAM shortcuts. | SessionOverlay retains live HUD, launch-image controls, menus, component swaps, device routing, app launch and session actions. Display & graphics now also edits saved screen-effects and texture-filtering defaults. Those preference-backed values are used at session/game launch; the drawer continues applying screen effects live and keeps texture choices in its own controls. Look presets that select a scaler update the shared scaler state. Keyboard display, second-screen routing, QAM/Steam actions, app/session actions and stop confirmation remain exclusive to the live drawer; only the virtual-controller keyboard-button preference is in Controls. |

**Loss found:** none in this source-to-destination inventory. The old Setup tool cards
and Updates entry have become category/search destinations, while the Components,
Proton and Performance operations remain in their specialist screens. Session-only
actions remain in the drawer rather than becoming persistent settings.

## Navigation and scope

- Back returns from a specialist manager to its category, then to the hub or the
  contextual Steam/Desktop/game page that opened settings. Selecting another rail
  item closes the settings history.
- Category and hub scroll state is retained when a manager opens over them. Search
  targets have stable IDs and request focus on the corresponding actionable control.
- Steam/Desktop selectors expose existing mode-specific defaults. Shared values do
  not become separate preferences when the selected mode changes.
- GPU shortcuts open the GPU view directly. Automatic/manual driver selection,
  effective selections, imports, downloads, deletion and bundled restore are retained.
- **Default Proton** changes Steam's synchronized default through `ProtonDefault`.
  **Editing components for** selects the build whose components are displayed and
  does not change the default. Proton installations remain a separate manager;
  per-game Proton overrides remain in Steam's Properties → Compatibility.
- Keyboard display, second-screen routing, Steam menu/QAM actions, session restart
  and stop stay exclusively in the session menu. Controller settings can still
  configure the presence of a keyboard button without opening the live keyboard.
- Runtime prerequisites, active-session restrictions, Android permissions,
  confirmations, downloads, queued operations and legacy synchronization switches
  retain their existing behavior.

## Feedback behind the changes

These are qualitative reports, some from earlier builds, rather than a claim that
every reported runtime issue remains in the current version:

- [Driver navigation](https://discord.com/channels/1552707793247539220/1552707794430459916/1555132174364315729)
  exposed confusion about being sent to Components and changing its view with RB.
  The driver shortcut now chooses the GPU view directly.
- [Proton selection](https://discord.com/channels/1552707793247539220/1552707794430459916/1555965564621562008)
  highlighted several selection concepts. Separate default, installation and editing
  controls make each operation explicit while retaining Steam synchronization.
- [Audio targets](https://discord.com/channels/1552707793247539220/1556195514322780160/1556272336393478195)
  motivated separating game audio from Steam-menu audio labels.
- [Storage paths](https://discord.com/channels/1552707793247539220/1556195514322780160/1556224196395270225)
  motivated putting library paths beside game imports and file access.

## Verification boundaries

Navigation history and catalog routing have JVM tests, alongside the existing
preference and workflow regression tests. The complete APK build uses
`DROIDDECK_BUILD_VARIANT=debug tools/build_local.sh` to include native assets.

Device acceptance still requires checking controller focus and Back restoration,
search scrolling, narrow layouts, permission dialogs and the session-menu boundary
on an Android handheld. No Android device was connected during this implementation;
successful compilation and unit tests do not establish on-device rendering or input.
