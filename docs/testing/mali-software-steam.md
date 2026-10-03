# Opt-in software Steam compatibility

This change provides an explicit software fallback for the Linux Steam/Gamescope
session. It does not enable fallback automatically or install a Mali Linux ICD.
The Android presenter still uses Vulkan; a positively identified Mali kbase
GPU uses the system Android driver. Adreno and unknown devices keep the existing
selection, and explicit Android driver choices retain precedence.

## Enable and recover

Set `/sdcard/Download/droiddeck-env` to include:

```sh
BL_STEAM_SOFTWARE=1
```

Remove that entry or set it to `0` to return to normal session rendering. No
preference is migrated or enabled by this branch. Only the exact value `1`
activates the fallback in the scripts and native Gamescope.

The fallback selects Lavapipe/llvmpipe, Gamescope's SDL Wayland swapchain,
software Steam CEF/SDL rendering, and disables mangoapp for that session.
An explicitly requested software session takes precedence over `BL_VK_DRIVER`;
with the mode off, imported Linux drivers retain their existing behavior.
Desktop Steam can also use this mode on a pixman desktop.

The optional present timing, local texture allocation, SHM refresh and ready
fence handling in the new Gamescope patches apply only in this mode. Normal
sessions retain the original dma-buf, buffer-cache and presentation paths.
The Android presenter requests at most its loader's Vulkan API (minimum 1.1)
and advertises dma-buf import only when the complete extension set is available.

## Validation

`tools/tests/test_software_graphics.py` exercises the actual outer session
launcher with a capturing Gamescope executable: default accelerated backend,
custom-driver selection, software-only activation, precedence, overlay, HDR,
refresh rate and argument preservation. The native component is built against
all existing patches and checked against the runtime's shared-library list.
`gamescope-3.16.29-p6` is a versioned component release; the existing release
download/checksum path is unchanged. There is no CI artifact pin or extra build
workflow permission.

Local validation passed all 121 JVM tests and the macOS-applicable Python tests
(62 discovered, 13 Linux-only skipped). The corrected native build passed
[run 37161255955](https://github.com/Droid-Deck/DroidDeck/actions/runs/37161255955).
The installed Gamescope binary matches the component's contents
(`2c92c155f7836a798c2a04eb1db5779e149680fcce888cd9a1ebe3fef7e5a2af`).

Earlier experiment validation on the wired Unisoc T618 / Mali-G52 MC2 device
(Android 12, kbase JM 11.31) reached Steam Big Picture sign-in and downloaded
Geometry Wars: Retro Evolved. Its software-rendered menu, movement, shooting and
pause worked, at roughly 3–5 displayed FPS. The per-game workaround was:

```sh
DISABLE_GAMESCOPE_WSI=1 %command%
```

This option is not applied globally. Software gameplay remains too slow for
normal play. The previous experimental branch `mali-software-steam` retains
the private PanVK build and GPU proof: compute, draw/readback, indexed draw and
visible VKCube worked, but accelerated Geometry Wars lost the GPU device.
That driver lab is intentionally outside this compatibility change.

Before a PR, validate default rendering on supported Adreno devices, explicit
Android/Linux driver choices, overlays and supported HDR/frame-generation
behavior as well as the opted-in wired Mali session. Passing launcher checks
alone is not proof of physical Adreno regression safety.
