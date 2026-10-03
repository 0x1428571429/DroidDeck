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

Testing is in progress on the wired Unisoc T618 / Mali-G52 MC2 device (`RGB05001607258`, Android 12, kernel 5.4, 4 KiB pages). The desktop and desktop Steam QR screen have been observed. Gamescope acceptance and the native artifact identity will be recorded after the patched build is tested.

A guest glibc Mali ICD and compatible image transport remain necessary for hardware acceleration. This patch does not install Steam-ARM's feature layers, prove Proton/DXVK support, or validate other Mali generations. The Android presenter also caps its requested Vulkan API to the loader's supported version and withholds dma-buf advertisement when the complete import extension set is missing; that missing-extension branch has not been exercised on this G52 driver.
