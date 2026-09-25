# AYN Thor Steam sign-in and Geometry Wars investigation

Date: 2026-09-25  
Device: AYN Thor, Android 13, QCS8550 / Adreno 740  
Repository revision tested: `7e6affe` plus the local `bannerlator-session` change described below.

## Finding

The avoidable second Steam sign-in was caused by DroidDeck restarting Steam after the ARM64 Proton
depot finished installing. The compatibility tool, default mapping, and game launch wrapper are
already set up before Steam starts. The wrapper finds the Proton depot when a game launches, so the
post-download restart is not needed. It made Steam log on again and wait for compatibility state a
second time.

The treatment removed only that post-download restart. It kept the compatibility registrar, its
periodic refresh, the signed-out install retry, and Steam's own client-updater restart handling. On
the treatment session, the app's tool was registered before the client started, sign-in completed
without a second client launch, and Steam's post-logon compatibility wait was 0.194 seconds. The
user observed that this launch reached Steam immediately. A later restored-cache session logged in
in three seconds with a 0.225-second compatibility wait.

Geometry Wars remains a separate unresolved issue. Steam and the compatibility manager selected
and started the intended tool, and DXVK identified `GeometryWars.exe`; the user reported that the
game then hung, on two attempts. Logs show no clear Wine fatal error or game exit before the session
ended. In a later session, Vampire Survivors launched through the same Bannerlator Proton tool and
reached its warning screen at about 62 FPS; the user confirmed it worked. That makes a general Steam
tool-registration or game-launch failure less likely and points more strongly to a
Geometry-Wars-specific Proton/FEX/graphics issue, though it does not prove the exact cause.

## Cause and change

Commit `ebf5da98404350d3956de1e2f191746d4a7ea519` (`First sign-in fetches the ARM64 Proton and
restarts the client once, as Bannerlator does`, 2026-09-23) added a watcher that waited for the
Proton app manifest, reran compatibility setup, and shut down Steam once the depot was installed.
The session loop then relaunched the client. This recreated the sign-in/compatibility wait even
though the tool had been registered before the first client start.

The local change to `tools/linuxfs/overlay/usr/local/bin/bannerlator-session` removes that watcher,
marker file, and special restart branch. It still passes `steam://install/4427310` when the depot
manifest is absent, retries that request after a signed-out user logs in, and retains the regular
Steam updater retry for exit code 42. `bannerlator-steam-compat` still runs before Steam and on its
existing periodic refresh. The matching generated asset in
`app/src/main/assets/linuxfs/usr/local/bin/bannerlator-session` was synchronized for the device
build.

The tool wrapper resolves `Proton Experimental (ARM64)` or `Proton 11.0 (ARM64)` from the Steam
library at game launch. This permits registering the tool before the depot exists and avoids a
client restart merely to expose the downloaded files.

## Device evidence

### Baseline

- Fresh Steam setup in session `session-20260925-104908` requested Proton Experimental ARM64.
- After that install completed, the watcher logged `compatibility layer installed: restarting the
  Steam client once` at 10:57:20. The client exited successfully and relaunched at 10:57:33.
- The second login began at 10:57:41 and completed at 10:58:10. `compat_log.txt` recorded
  `Waiting for compat in post-logon took: 25.541342s`; the user saw the post-login “Loading user
  data…” state.
- Steam's own first-run updater also exited with code 42 once. That is a separate updater restart
  path and remains supported.

### Treatment

- To exercise the no-manifest path without deleting Proton's approximately 2 GB payload, the
  installed app manifest was temporarily moved aside. Session
  `session-20260925-111921` logged the tool registration before Steam started:
  `registered bannerlator-proton-arm64 (depot Proton Experimental (ARM64) present)`.
- The helper also reported `default and 1 installed title(s) set to bannerlator-proton-arm64`.
  Steam was asked to install app 4427310; there was no app-initiated shutdown/restart afterward.
- `steamui_login.txt` records the treatment sign-in at 11:19:31–11:19:33. The compatibility log
  recorded the registration callback and `Waiting for compat in post-logon took: 0.194035s` at
  11:19:33. The first rendered frame was recorded about nine seconds after session creation.
- The user directly reported that this launch was “snappy” and got them in immediately. This
  comparison was made with the Proton payload already present and the Steam account already signed
  in; it proves the no-manifest startup path avoids the added relogin, but is not a full clean-account
  timing comparison.
- The temporarily moved app manifest was restored byte-for-byte from its backup and the backup
  removed. It records Proton Experimental ARM64 as fully installed (`StateFlags=4`).

### Full no-payload attempt and restored game launch

- To test a true missing-depot state, the Proton directory and app manifest were moved aside as
  `.ab-backup` files. In session `session-20260925-112716`, Steam logged in at 11:27:28–11:27:31 and
  waited 0.359 seconds for compatibility state. The client stayed running.
- This did **not** complete the intended download test: the session log's Steam argument line did
  not contain `steam://install/4427310`, and the client resumed a pending Vampire Survivors update.
  Why the missing-manifest branch did not request the Proton app was not isolated. Do not use this
  session as proof that a depot downloads successfully without a restart.
- The Vampire Survivors launch refusal happened during this depot-isolation interval. The
  compatibility log briefly assigned app 1794680 to Valve's `proton-experimental-arm64`, whose
  command prefix requires Steam Linux Runtime 4 ARM64 (app 4185400). That path is unsuitable here;
  this failure is not evidence against the normal restored-depot launch path.
- The Proton directory and its `StateFlags=4` manifest were restored. In session
  `session-20260925-114136`, the login completed at 11:41:48 and the compatibility wait was 0.225
  seconds. The log mapped app 1794680 to `bannerlator-proton-arm64` and invoked
  `VampireSurvivors.exe`. Wine 11 started, Gamescope created the 1794680 surface, and the game
  reached its photosensitivity warning at approximately 62 FPS. The user confirmed the game works.
  The one-shot `.bl-autolaunch` test marker was removed after launch.

### Clean missing-depot download and relaunch check

- Session `session-20260925-115405` was a real no-depot test: the 1.9 GB Proton directory and its
  manifest were moved out of Steam's library while preserving a local backup. The modified helper
  logged `registered bannerlator-proton-arm64 (no ARM64 Proton depot yet)` and mapped both installed
  titles to that tool before starting Steam. The Steam argument line contained
  `steam://install/4427310`.
- Steam showed the install dialog for Proton Experimental (ARM64), and the user confirmed it with
  the controller. Steam downloaded 474.4 MB and staged the full 2,078,890,587-byte payload. Its
  manifest reached `StateFlags=4`, build `25406135`. The same Steam process remained running after
  install; the session log has no app-triggered exit/restart. The signed-in session logged one
  successful login at 11:54:16. Its first rendered frame arrived 10.1 seconds after session start;
  post-logon compatibility wait was 0.186978 seconds.
- After a deliberate Steam/session restart, session `session-20260925-120029` registered the custom
  tool with the depot present and again mapped both installed games. The one-shot launch hook ran
  Vampire Survivors (1794680); logs show `bannerlator-proton: DirectAudio ready (Wine 11)`,
  `VampireSurvivors.exe`, and the game reached its photosensitivity warning at 62 FPS. The user
  confirmed that it launched. This verifies a working game launch after the depot download and a
  subsequent client/session restart.
- The fresh app install contains no outside Proton archive, so a third-party Proton's visibility,
  config mapping, and launch still need a separate dogfood check. The full download test used an
  already signed-in Steam account; it does not measure first account sign-in on a clean account.

### Fresh install, QR sign-in, and Katamari

- On the freshly installed 0.1.7 build, the user started Steam from DroidDeck, downloaded the
  runtime/Steam files, signed in using QR, and accepted the Proton Experimental (ARM64) install to
  internal storage. The user reports Proton completed without Steam automatically closing, asking
  them to sign in again, or showing another loading-user-data cycle.
- Session `session-20260925-120849` shows the app initially asked Steam to install app 4427310. Steam
  performed its own first-run client update and exited with `rc=42` at 12:14:04; the session script
  restarted it through the existing updater-retry path. After sign-in, the helper asked for Proton
  again at 12:15:04, consistent with Steam dropping the install URL while signed out. Proton
  Experimental (ARM64) reached `Fully Installed` at 12:17:39 (build 25406135). There is no further
  Steam exit in the log after that completion, so this is distinct from the removed post-Proton
  restart.
- The user installed *Once Upon A KATAMARI* (appid 1880620). Steam's manifest reached `Fully
  Installed` at 12:23:59. Its first launch used Valve's generic `Proton Experimental` tool (AppID
  1493710), whose logged command chain runs through FEX and Steam Linux Runtime 4. Steam released
  that launch session by 12:25:14; the user reports it failed immediately. The logs also show the
  generic tool's dependency chain includes FEX AppID 3127680 and runtime AppID 4183110.
- After the user selected `Bannerlator Proton (ARM64)`, Steam mapped app 1880620 to
  `bannerlator-proton-arm64` at priority 250. The process command line used
  `compatibilitytools.d/bannerlator-proton-arm64/bannerlator-proton waitforexitandrun`; DXVK then
  created a surface for `OnceUponaKATAMARI.exe`. The user reports that launch worked.
- This confirms the custom ARM64 tool is present and usable during fresh onboarding, while also
  exposing a first-launch/default selection gap: the first attempt used Valve's generic Proton
  before the successful manual switch. The log alone does not establish whether that was due to
  Steam's default/per-game preference or a delay before the helper's per-app mapping took effect.
  The user has not yet restarted Steam after this successful launch; confirming the tool selection
  and relaunch after that restart remains pending. No device input or state change was made by the
  agent while recording this update.

### Compatibility-tool and Geometry Wars check

- Geometry Wars (appid 8400) was present in the Steam library. `compat_log.txt` recorded it mapped
  to `bannerlator-proton-arm64` at priority 250, then logged the `waitforexitandrun` command prefix.
- Session output included `bannerlator-proton: DirectAudio ready (Wine 11)` and DXVK application
  info for `GeometryWars.exe`, with a Gamescope surface and Vulkan swapchain created. Steam's
  `gameprocess_log.txt` tracked the launch processes. The user reported that Geometry Wars hung
  again after launch; no clean exit or decisive fatal Proton error was captured before the session
  ended.
- In the treatment run, Steam restored Geometry Wars from the preceding forced exit automatically.
  Therefore the app/tool registration and launch path were exercised, but this was not a controlled
  playability test. Gameplay was not confirmed. Vampire Survivors later launched successfully on
  the same tool, so the Geometry Wars hang now appears title-specific; a second title alone does not
  identify the exact incompatibility.
- A full DroidDeck uninstall removed third-party Proton archives. The current fresh install has no
  GE-Proton or other external Proton available for an end-to-end test. This change does not bypass
  the extra-Proton adoption/registration helper; that functionality is retained structurally but
  was not device-tested with a third-party archive in this pass.
- A previous A/B attempt hid the registration directory and skipped the whole helper, which also
  skipped external Proton wrapping and config updates. That attempt was invalid for judging
  registration safety. This treatment does not repeat that mistake. :codex-annotation{index="1"}

### Follow-up Steam startup spinner

- In session `session-20260925-123244`, DroidDeck created a normal Steam session at 12:32:44 and
  launched Steam at 12:32:46. The session reached its first frame at 12:33:01 (about 17 seconds
  after session creation). The session log does not show the Proton helper shutting down or
  relaunching the Steam client. Steam's own short-lived updater verification child exited at
  12:32:57, while the parent client continued.
- `steamui_login.txt` records server logon success at 12:33:03, but Steam did not set login state to
  `Success` until 12:33:28. At 12:33:26, `compat_log.txt` reports `Waiting for compat in post-logon
  took: 22.587135s`; that is the closest direct log correlate for the “Loading user data” spinner.
  The log shows Steam queued registration callbacks for Bannerlator Proton ARM64, FEX, Steam Linux
  Runtime 4, and Proton Experimental at the end of that wait. The network logon itself took about
  two seconds; most of the remaining delay was in Steam's post-logon compatibility readiness.
- This reproduces a long post-logon wait even without the removed post-Proton restart. It is a
  separate startup delay from the extra sign-in caused by `ebf5da9`; removing that restart avoids
  one unnecessary login but does not guarantee every Steam login will skip its compatibility
  wait. These logs do not identify which compat callback or underlying Steam operation consumed the
  22.6 seconds, so attributing it specifically to Proton registration would be speculation.
- The session contains one Steam startup/login sequence, not evidence of a second in-session client
  restart. If the user triggered a separate Steam restart after these timestamps, its result is not
  present in the captured log window. No device controls or input were used while collecting this
  evidence.

## Interpretation and next check

Avoiding the helper-forced post-download restart addresses one source of a repeated login, but the
follow-up startup still had a 22.59-second Steam post-logon compatibility wait. The full missing-depot
run verifies that Steam received and completed the Proton install request without the helper forcing
a restart, and that Vampire Survivors launches through the custom tool afterward. It does not
establish that all fresh accounts or later logins will be fast. The specific callback or operation
behind the latest wait remains unidentified. Third-party Proton registration and launch remain
unverified after the full app uninstall, and Geometry Wars still hangs after launch.

For Geometry Wars, tool selection and launch handoff succeeded, which rules out the earlier
“compatibility tool not found” registration failure in this treatment. The hang occurs later. To
attribute it confidently, compare another known-working Windows title on the same tool/driver, or
test Geometry Wars with a different rendering path while capturing the screen and per-game Proton
output during the hang. Neither comparison was available in this run, so no Geometry Wars-specific
fix is proposed from this evidence.

Validation completed: debug APK build and install succeeded before the treatment session; live
session logs confirmed pre-start registration, fast post-logon compat callback, and the Geometry
Wars launch prefix. Source and packaged session script are synchronized. Shell syntax and whitespace
checks are recorded in the progress log for this investigation. After the clean-depot test, Steam
and the game were shut down cleanly, DroidDeck was fully uninstalled, and `:app:assembleDebug`
completed successfully. The APK's embedded session script matched the edited source byte-for-byte;
version 0.1.7 (code 8) was installed on the Thor and initially left unopened. The user subsequently
completed first onboarding and QR sign-in in session `session-20260925-120849`. The full uninstall
also removed the app-specific SD-card Steam library at
`/storage/FF7F-F56A/Android/data/com.droiddeck.launcher/files/steam`; reinstall a test game before
using game launch as an acceptance check. Katamari was reinstalled and launched with the custom tool;
the post-launch manual Steam restart check remains open.
