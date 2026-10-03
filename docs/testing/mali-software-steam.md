# Mali software Steam experiment

This branch adds an opt-in CPU rendering path for Steam and Gamescope. Android's compositor selects system Vulkan automatically on non-Adreno hardware; explicit custom driver choices are retained. Linux uses Lavapipe for Gamescope and llvmpipe/software SDL for Steam. This is a UI compatibility fallback, not proof of accelerated game support.

Set `/sdcard/Download/droiddeck-env` to:

```sh
BL_STEAM_SOFTWARE=1
```

Launch **Steam → Play Steam**. The session selects Gamescope's SDL Wayland Vulkan swapchain, which can present Lavapipe frames through `wl_shm`. Gamescope's native Wayland backend requires exportable dma-bufs even for its blank texture. The new patch keeps locally sampled textures, including cursors and shared-memory client frames, private on swapchain backends and makes `VK_KHR_present_id`/`VK_KHR_present_wait` optional, using the existing timer pacing when they are unavailable. Drivers that support both features retain the present-wait thread. Shared-memory pixels are uploaded on every commit because clients reuse their buffers; the private textures are marked ready after the synchronous copy, avoiding an invalid dma-buf fence lookup.

Steam's embedded browser disables GPU rendering, and the native client uses the SDL software renderer and X11. The performance overlay is disabled in this mode.

## Building this branch

This experimental branch pins native CI run [37146942306](https://github.com/Droid-Deck/DroidDeck/actions/runs/37146942306) and its bundle checksum in `tools/gamescope/release.env`. Both the APK workflow and ordinary local build fetch and verify that artifact. No component release is published.

```sh
DROIDDECK_BUILD_VARIANT=debug tools/build_local.sh
```

To test a different successful native build, download its **gamescope-patched** artifact and verify the accompanying checksum:

```sh
gh run download <run-id> -R Droid-Deck/DroidDeck -n gamescope-patched -D /tmp/mali-gamescope
(cd /tmp/mali-gamescope && shasum -a 256 -c gamescope.tzst.sha256)
DROIDDECK_GAMESCOPE_BUNDLE=/tmp/mali-gamescope/gamescope.tzst \
DROIDDECK_BUILD_VARIANT=debug tools/build_local.sh
```

`DROIDDECK_GAMESCOPE_BUNDLE` overrides the pin for that local build. Before shipping, publish the native component and replace the temporary artifact pin with its release tag and checksum. CI artifacts have limited retention; this branch is an experiment, not a permanent release source.

The desktop path can also use the software Steam settings. On the tested G52 device, its labwc renderer requires `/sdcard/Download/droiddeck-wlr-renderer` containing `pixman`. Add `BL_DESKTOP_STEAM=1` only to start Steam automatically on the desktop.

## Device validation

Validated 2026-10-03 on the wired Unisoc T618 / Mali-G52 MC2 device (`RGB05001607258`, Android 12, kernel 5.4, 4 KiB pages). **Steam → Play Steam reaches the Big Picture QR sign-in screen inside Gamescope with only `BL_STEAM_SOFTWARE=1`.** The selected Deck mode was retained: the client command includes `-gamepadui -steamdeck -steamos3`. A test string was entered and cleared in the account field without submitting it; no account was signed in during that initial test. The desktop and desktop Steam QR screen also worked in the earlier test.

- Native bundle SHA-256: `22df550d60fcd62e351790eb775ee857e037fcd49fcc23a5bfa4638377e6b4b7`; the installed Gamescope binary matches the bundle (`41a067202e94af16b6f09b819208450e667dcc709480a2b97405d59a0e93cb69`).
- Signed debug APK SHA-256: `7a19928dc93e72c81b553c62515f3881a249eb037a81fbb6296e05bb9edd23b3`, built from commit `e427c08` using the ordinary artifact-pin path.
- Device logs: `Download/DroidDeck/2026-10-03-10-steam/`. They identify Lavapipe, SDL swapchain creation, timer pacing, and the outer window `Steam Big Picture Mode` drawing through the system Mali-G52 presenter.
- The native ARM build, full local Android build, bundle checksum, installed binary identity, shell syntax, workflow YAML, and diff checks passed. App data and the primary checkout were preserved.

The shared-memory commit fixes are both needed: without a ready fence, commits never display; with the old buffer memoization, a reused buffer keeps displaying its first copied pixels (the boot logo or black) despite ongoing frame delivery. Refreshing the copy also makes the account-field edits visible.

## Geometry Wars gameplay follow-up

After the user signed in, Geometry Wars: Retro Evolved (`8400`) was downloaded through the native Steam client on the same device and APK. Steam verified all seven files (66,019,943 bytes, build `251921`, depot `8401`). The first install confirmation crashed Steam with exit 139 while it queued additional compatibility runtimes. Restarting the session retained the install request; the download then completed successfully. The cause of that client crash is unverified.

The game launches through `bannerlator-proton-arm64` using the installed Proton Experimental (ARM64), Wine and DXVK, with the guest still using Lavapipe. It reached the animated menu and an active round; the on-screen A button starts a round, the left stick moves the ship, the right stick fires in different directions, and Start pauses it.

The first run was cropped and repeatedly recreated its swapchain. Turning off **Stretch games to fill** did not resolve the cropping. A game-specific Steam launch option did:

```sh
DISABLE_GAMESCOPE_WSI=1 %command%
```

That disables the Vulkan Xwayland-bypass layer for the game while keeping the outer Gamescope session. The subsequent menu and gameplay show the whole 4:3 image, including the high-score column and all menu entries. Fullscreen stretching remains off on the test device, and on-screen controls are set to **Always**. These are runtime settings; the branch does not automatically apply this per-game workaround.

The Android presenter reported roughly 3–5 displayed frames per second in representative gameplay samples. Movement and shooting work, but CPU-rendered performance is insufficient for normal play. Audio was not verified. The game was left paused with scrcpy running. Follow-up logs are in `Download/DroidDeck/2026-10-03-11-steam/`; screenshots and raw logs are retained locally, outside Git.

A guest glibc Mali ICD and compatible image transport remain necessary for hardware acceleration. This patch does not install Steam-ARM's feature layers or validate other Mali generations; the gameplay result establishes one Windows D3D9 title through software rendering, not general Proton/DXVK compatibility. The Android presenter also caps its requested Vulkan API to the loader's supported version and withholds dma-buf advertisement when the complete import extension set is missing; that missing-extension branch has not been exercised on this G52 driver.

## GPU acceleration follow-up

The [kbase/JM driver lab](../../tools/panvk/README.md) now includes a pinned
source build and three fixes validated on this wired G52. GPU compute,
offscreen draw/readback, indexed draw and visible VKCube presentation work.
Geometry Wars remains blocked: the bundled DXVK requires an unsupported
feature, while an isolated older DXVK reaches rendering and loses the GPU
device. The software Steam/Gamescope settings remain the recovery path.
