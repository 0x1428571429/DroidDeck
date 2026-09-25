#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf .frames
mkdir -p .frames
node render.mjs 0 84
node render.mjs 84 168
node render.mjs 168 252
ffmpeg -y -hide_banner -loglevel error -framerate 60 -i .frames/%04d.png \
  -c:v libx264 -preset slow -crf 17 -pix_fmt yuv420p -movflags +faststart \
  -t 4.2 droiddeck-boot.mp4
ffmpeg -y -hide_banner -loglevel error -framerate 60 -i .frames/%04d.png \
  -c:v libvpx-vp9 -row-mt 1 -crf 24 -b:v 0 -pix_fmt yuv420p -an \
  -t 4.2 droiddeck-boot.webm
rm -rf .frames
