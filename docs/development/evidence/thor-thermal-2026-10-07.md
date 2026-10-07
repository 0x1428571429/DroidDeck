# AYN Thor thermal report — switching in the Steam Big Picture library

Session 2026-10-07-24-steam. DroidDeck 0.3.1 (11), Steam Deck mode, upscaler Linear, screen effects off.

## device.txt

DroidDeck session report
========================
Written                 2026-10-07 13:57:25 GMT+04:00
Session mode            Steam client (gamescope)

App
---
Version                 0.3.1 (11)
Package                 com.droiddeck.launcher.latency
targetSdk               28
Native lib dir          /data/app/~~El6UORyYBRREMeGnPM4KsQ==/com.droiddeck.launcher.latency-FClnUhVS7_d8HTk7vfv-zg==/lib/arm64

Device
------
Model                   AYN AYN Thor
Device / product        kalama / kalama
Board / hardware        kalama / qcom
SoC                     QTI QCS8550
Android                 13 (API 33)
Security patch          2024-01-01
Build                   Thor_V1.0.0.377_20260206_165408_user
Fingerprint             qti/kalama/kalama:13/TKQ1.231222.001/eng.Thor.20260206.163241:user/release-keys
Kernel                  5.15.123-android13-8-gafd857749d1f
ABIs                    arm64-v8a, armeabi-v7a, armeabi

CPU and memory
--------------
Cores                   8
Core ceilings          cpu0 2.02 GHz, cpu1 2.02 GHz, cpu2 2.02 GHz, cpu3 2.80 GHz, cpu4 2.80 GHz, cpu5 2.80 GHz, cpu6 2.80 GHz, cpu7 3.19 GHz
RAM total               14.9 GB
RAM available           9.5 GB
App storage free        20.5 GB
Shared storage free     20.5 GB

GPU
---
KGSL gpu_model          Adreno740v2
KGSL chip id            unknown
System Vulkan ICD       /vendor/lib64/hw/vulkan.adreno.so

Display
-------
Session output          1920x1080
Session refresh         120.00 Hz
Resolution (Steam)      screen (1920x1080)
Resolution (Desktop)    720p (1280x720)
Foldable                false

Drivers
-------
GPU                     Adreno 740 · Adreno 7xx · Supported
Driver mode             auto (recommended pair: banner)
Display driver (chosen) Mesa_Turnip_v26.3.0-20261007
  name / version        Mesa Turnip v26.3.0-20261007 Vulkan 1.4.363
  imported available    Mesa_Turnip_v26.3.0-20261007
Linux driver            Mesa Turnip v26.3.0-20261007-Linux Vulkan 1.4.363 (glibc 2.38+)
Runtime's own ICD       /data/user/0/com.droiddeck.launcher.latency/files/linuxfs/usr/share/vulkan/icd.d/freedreno_icd.json

Runtime
-------
Installed version       r9
Runtime ready           true
Desktop installed       true
Runtime root            /data/user/0/com.droiddeck.launcher.latency/files/linuxfs

Settings in effect
------------------
Client core override    false
  client cores          0,1,2,3,4,5,6,7
  game cores            every core (nothing sent)
Turnip sysmem           true
Zink lazy descriptors   true
Threaded GL (glthread)  true
No GL error checks      true
Steam Deck mode         true
Steam controller        deck
Upscaler                Linear (sharpness 75%)
FEX preset              PERFORMANCE_TSO
Skip xalia              true
Wine sync               esync
droiddeck-esync packs   5 installed, 0 wanted
Linux x86 (FEX)         not set up
gamescope realtime      false
proot without seccomp   false
proot fast path         true
Guest host name         DroidDeck
DirectAudio for games   true
Stretch 16:9 to panel   false
Client audio            DirectAudio
Microphone              false
On-screen controls      auto
Touch mode              auto
Performance HUD         true
Game storage            automatic (/storage/6ABE-0C62/Android/data/com.droiddeck.launcher.latency/files/steam)

Android process limits
----------------------
Phantom proc monitor    disabled (good - the OS will not kill the session's children)

Device switch files in Download
-------------------------------

Nothing identifying is collected here: no serial number, no device or advertising
id, no account name, no network names. Safe to attach to a bug report as it is.

## compositor stats/perf (Steam home, during use)

10-07 13:57:33.614 10155 10310 I DroidDeckWayland: [vulkan] "Steam 大屏幕模式" (gamescope) is presenting GPU frames through Wayland: 1920x1080, format AR24, qcom_compressed (dma-buf: copied into the screen swapchain)
10-07 13:57:33.614 10155 10310 I DroidDeckWayland: [vulkan] "Steam 大屏幕模式" (gamescope) is presenting GPU frames through Wayland: 1x1, format AR24, qcom_compressed (dma-buf: copied into the screen swapchain)
10-07 13:57:33.614 10155 10310 I DroidDeckWayland: [window] opened "Steam 大屏幕模式" (gamescope) 1920x1080 at 0,0 (no desktop position yet)
10-07 13:57:35.763 10155 10310 I DroidDeckWayland: [stats] last 10 s: 109 frames on screen (10.9 fps) | 226 GPU frames from games | 2 window redraws | 1 windows open
10-07 13:57:35.763 10155 10310 I DroidDeckWayland: [perf] last 10 s: 586 ticks, 109 scenes, 109 on screen (copy 109, zero-copy 0, layer copy 0) | render_scene 10.21/902.35 ms | base 0 black kept, 0 presented | acquire 0.04/0.20 ms | present 0.40/8.33 ms (109) | fence wait 1.24/3.86 ms (109, 0 GPU release waits) | release 9.07/18.10 ms (107, 76 held) | 0 pool drops
10-07 13:57:45.763 10155 10310 I DroidDeckWayland: [stats] last 10 s: 384 frames on screen (38.4 fps) | 866 GPU frames from games | 0 window redraws | 1 windows open
10-07 13:57:45.763 10155 10310 I DroidDeckWayland: [perf] last 10 s: 599 ticks, 384 scenes, 384 on screen (copy 384, zero-copy 0, layer copy 0) | render_scene 7.56/28.65 ms | base 0 black kept, 0 presented | acquire 0.04/0.83 ms | present 6.23/28.43 ms (384) | fence wait 1.04/5.71 ms (384, 0 GPU release waits) | release 4.03/33.73 ms (432, 224 held) | 0 pool drops
10-07 13:57:55.763 10155 10310 I DroidDeckWayland: [stats] last 10 s: 505 frames on screen (50.5 fps) | 1196 GPU frames from games | 0 window redraws | 1 windows open
10-07 13:57:55.764 10155 10310 I DroidDeckWayland: [perf] last 10 s: 600 ticks, 505 scenes, 505 on screen (copy 505, zero-copy 0, layer copy 0) | render_scene 1.93/11.98 ms | base 0 black kept, 0 presented | acquire 0.03/0.11 ms | present 0.28/11.77 ms (505) | fence wait 1.44/6.26 ms (505, 0 GPU release waits) | release 4.82/21.37 ms (597, 539 held) | 0 pool drops
10-07 13:58:05.764 10155 10310 I DroidDeckWayland: [stats] last 10 s: 463 frames on screen (46.3 fps) | 1194 GPU frames from games | 0 window redraws | 1 windows open
10-07 13:58:05.764 10155 10310 I DroidDeckWayland: [perf] last 10 s: 599 ticks, 463 scenes, 463 on screen (copy 463, zero-copy 0, layer copy 0) | render_scene 1.71/6.01 ms | base 0 black kept, 0 presented | acquire 0.04/2.86 ms | present 0.30/3.23 ms (463) | fence wait 1.15/5.60 ms (463, 0 GPU release waits) | release 3.85/22.34 ms (596, 554 held) | 0 pool drops
10-07 13:58:15.764 10155 10310 I DroidDeckWayland: [stats] last 10 s: 460 frames on screen (46.0 fps) | 1196 GPU frames from games | 0 window redraws | 1 windows open

## OEM fan controller temperature (FanBase) around the session

10-07 14:04:15.928  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 57.738887786865234 speedPercentage = 35 smartSpeed = 17500
10-07 14:04:17.742  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 65.40555572509766 speedPercentage = 45 smartSpeed = 22500
10-07 14:04:20.539  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 58.650001525878906 speedPercentage = 37 smartSpeed = 18500
10-07 14:04:24.427  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 56.14444351196289 speedPercentage = 33 smartSpeed = 16500
10-07 14:04:28.465  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 52.43888854980469 speedPercentage = 29 smartSpeed = 14500
10-07 14:04:32.480  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 51.77777862548828 speedPercentage = 28 smartSpeed = 14000
10-07 14:04:35.094  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 57.10555648803711 speedPercentage = 35 smartSpeed = 17500
10-07 14:04:39.150  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 51.488887786865234 speedPercentage = 28 smartSpeed = 14000
10-07 14:04:43.199  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 50.11111068725586 speedPercentage = 26 smartSpeed = 13000
10-07 14:04:47.251  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 49.57777786254883 speedPercentage = 25 smartSpeed = 12500
10-07 14:04:51.286  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 49.18333435058594 speedPercentage = 25 smartSpeed = 12500
10-07 14:04:55.343  2721  2924 D FanBase : mSmartAction temp control curve; result /sys/devices/virtual/thermal/   null result.temperature = 49.31111145019531 speedPercentage = 25 smartSpeed = 12500
