# DroidDeck boot animation

A 4.2 second, 1920×1080, 60 fps startup animation built directly from the geometry and colors of `../droiddeck-mark.svg`.

The mark is split into its left bridge-like half, blue orb, and right crescent. The left half drops in and lands rotated as a bridge, the orb bounces onto it, the crescent lands as a cap, the stack teeters, then all three spring back into the exact source-logo geometry. A restrained blue floor glow and final pulse provide the only added visual treatment.

Outputs:

- `droiddeck-boot.webm` — VP9/YUV420p, intended boot-movie asset.
- `droiddeck-boot.mp4` — H.264/YUV420p preview / general playback copy.

To rebuild on macOS with Google Chrome, Node 24+, and ffmpeg installed:

```sh
./build.sh
```

`boot.html` is the animation source. `render.mjs` drives Chrome through the DevTools protocol and captures deterministic frames; no generated imagery is involved.
