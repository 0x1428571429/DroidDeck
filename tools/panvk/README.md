# Experimental Mali kbase/JM driver

This lab builds the Linux Vulkan ICD from
[FristOneRR-Panvk-Source](https://github.com/FristOneRR-Admin/FristOneRR-Panvk-Source)
at `efd07bab401b263a9ac114560ecdb9a2934f5d43`. The fork includes the older Job
Manager submission path needed by Mali-G52. It talks to `/dev/mali0` through
the proprietary kbase kernel interface; upstream DRM-only PanVK cannot use
that device node.

The local patches make Android `liblog` conditional, fix the JM atom ABI,
and withhold placed mapping on kbase.
The original fork padded its v2 layout to 64 bytes, which this kernel interprets
as v3 with an eight-byte sequence-number prefix. Jobs arrived under atom 0 and
timed out after a configuration fault. The patch submits the actual 56-byte v2
structure on JM 11.19 and later (48 bytes on older JM), as specified in
[Arm's kbase UAPI header](https://nest-open-source.googlesource.com/manifest_repos/mali-driver/+/0f8397eced2de6bc649a9cc32d0fae77a1dc34dc/bifrost/r44p0/kernel/include/uapi/gpu/arm/midgard/jm/mali_base_jm_kernel.h).

Build inside an AArch64 glibc environment with Clang/LLVM 22, Meson, Ninja,
libclc, SPIRV-Tools, SPIRV-LLVM-Translator, Vulkan headers, Python Mako/YAML,
and the X11/Wayland development dependencies installed. The tested DroidDeck
runtime already supplied several of these dependencies. Avoid installing an
entire development toolchain into a user's runtime by default.

```sh
git clone https://github.com/FristOneRR-Admin/FristOneRR-Panvk-Source.git panvk-src
git -C panvk-src checkout efd07bab401b263a9ac114560ecdb9a2934f5d43
JOBS=2 tools/panvk/build-in-runtime.sh "$PWD/panvk-src" "$PWD/panvk-build"
```

The build stays under `panvk-build`; it does not install or replace a global
ICD. Its manifest selects the newly built driver explicitly:

```sh
PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1 PANVK_KBASE_DRI3=0 \
VK_DRIVER_FILES="$PWD/panvk-build/panvk-g52-icd.json" \
VK_ICD_FILENAMES="$PWD/panvk-build/panvk-g52-icd.json" \
vulkaninfo --summary
```

To launch the tested GPU cube from the Mac against this device's lab build:

```sh
ADB_SERIAL=RGB05001607258 tools/droiddeckctl run /usr/bin/env -- \
  PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1 PANVK_KBASE_DRI3=0 \
  VK_DRIVER_FILES=/opt/panvk-g52-lab/panvk-g52-icd.json \
  VK_ICD_FILENAMES=/opt/panvk-g52-lab/panvk-g52-icd.json \
  DISABLE_GAMESCOPE_WSI=1 LD_LIBRARY_PATH=/usr/lib \
  /usr/bin/vkcube --wsi xcb --width 640 --height 480
```

Stop the current session before using `droiddeckctl run`.

`PANVK_KBASE_DRI3=0` selects shared-memory presentation. DroidDeck's Xwayland
does not implement the fork's private Termux:X11 raw dma-buf protocol. GPU
rendering and CPU presentation copies are separate: choosing this presentation
path does not select Lavapipe.

Before selecting this driver for a session, validate GPU compute and offscreen
render readback, then X11 presentation, then the desired game. Device detection
alone is insufficient. The wired Unisoc T618 / Mali-G52 MC2 reports kbase UAPI
11.31, while the separate
[G52 reference project](https://github.com/LukeValen/panvk-mali-g52) was tested
on MediaTek with UAPI 11.38. Keep `BL_STEAM_SOFTWARE=1` available for recovery.

The Linux ICD is distinct from the Android Vulkan driver used by DroidDeck's
presenter. Android/Bionic driver packages cannot be loaded by the glibc Steam
runtime.

## Wired G52 results (2026-10-03)

Built natively in DroidDeck's AArch64 glibc runtime with Clang/LLVM 22.1.8.
The app UID can open `/dev/mali0` and submit jobs without root. No DRM render
node or dma-heap was available. This is experimental driver work, with the
Steam UI and outer Gamescope still running in software mode.

| Test | Result |
| --- | --- |
| Vulkan enumeration | Mali-G52 r1 MC2, integrated GPU, Vulkan 1.3.354 |
| Compute shader and host readback | Expected integer 777 returned |
| Vertex/fragment draw and image readback | Expected red pixel 255,0,0,255 returned |
| Indexed triangle and image readback | Expected red pixel returned |
| X11 VKCube inside a DroidDeck Gamescope session | Rotating cube visible; about 15–17 displayed FPS through SHM |
| Geometry Wars, bundled DXVK 3.1.1 | Adapter rejected: `multiDrawIndirect` is unsupported |
| Geometry Wars, isolated DXVK 1.10.3 | Device and 640×480 swapchain created; rendering loses the GPU device |

The fork advertises `VK_EXT_map_memory_placed` although its kbase mapping code
rejects caller-selected addresses for ordinary SAME_VA allocations. Wine's
WoW64 Vulkan path selects that extension and DXVK buffer mapping fails with
`VK_ERROR_MEMORY_MAP_FAILED`. The third patch withholds the extension and
features on kbase, keeping them on DRM. Wine then uses its fallback mapping
path and reaches real game submissions.

The remaining Geometry Wars failure includes terminated jobs (`0x04`) and
`BASE_JD_EVENT_JOB_READ_FAULT` (`0x42`), followed by
`VK_ERROR_DEVICE_LOST`. A diagnostic build that waits after each batch showed
successful uploads and clears, then termination of the first vertex/tiler
chain. Its compute job completed, but its vertex job, tiler job and dependent
fragment job did not complete. Basic indexed rendering also passes with IDVS
disabled, so a generic indexed-draw or separate-vertex-job failure was not
reproduced. The complex vertex workload remains unresolved. Disabling submission overlap and compiler IDVS or
optimizations did not recover rendering. Native draw/readback and VKCube
success therefore do not establish working Proton game acceleration.

Do not advertise missing Vulkan features just to pass DXVK's adapter check.
Keep game overrides and legacy DXVK trials isolated from the Proton depot,
and restore the working software launch option when the trial fails:

```sh
DISABLE_GAMESCOPE_WSI=1 %command%
```

The tested three-patch build has SHA-256
`5fc3f57fe335ebf524871ca24fd38b3c384c31baaebe9a2220e8e482e09e5ad6`.
That identifies the retained evidence binary; rebuilds need not be byte-identical.

The patched driver remains a private lab ICD. It is not bundled in the APK,
selected globally, or production-ready. A compatible DXVK path and reliable
complex rendering must be validated before adding automatic Mali selection.
