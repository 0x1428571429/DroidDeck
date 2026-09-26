#!/usr/bin/env bash
# Runs INSIDE a debian:trixie container on an x86-64 runner. Cross-builds the ARM64 shadPS4 core
# (tools/shadps4/source.env) with its own scripts - FEXCore first, then shadPS4 linked against it -
# after applying ./patches, and packs the binary as shadps4.AppImage for /opt/appimages.
# Built against trixie's glibc 2.41; the runtime's Arch rootfs is newer, so its own X11, Vulkan
# loader, udev and C++ libraries are used and nothing is bundled.
set -euxo pipefail
WORK=/work
cd "$WORK"
. tools/shadps4/source.env

export DEBIAN_FRONTEND=noninteractive
dpkg --add-architecture arm64
apt-get update
# The Bachata runtime's cross set, plus PulseAudio and ALSA headers so SDL3 builds its audio
# drivers (it loads them at run time; the runtime plays through PulseAudio).
apt-get install -y --no-install-recommends \
  ca-certificates git curl xz-utils zstd file pkg-config dpkg-dev squashfs-tools \
  cmake ninja-build clang llvm lld gcc g++ make \
  gcc-aarch64-linux-gnu g++-aarch64-linux-gnu binutils-aarch64-linux-gnu \
  python3 nodejs \
  libvulkan-dev libc6-dev \
  libx11-dev:arm64 libxext-dev:arm64 libudev-dev:arm64 uuid-dev:arm64 \
  libpulse-dev:arm64 libasound2-dev:arm64 \
  libc6:arm64 libgcc-s1:arm64 libstdc++6:arm64 libvulkan1:arm64 libudev1:arm64 libuuid1:arm64 \
  libx11-6:arm64 libxext6:arm64
git config --global --add safe.directory '*'

SRC=$WORK/build/shadps4-arm64-src
rm -rf "$SRC"
git init -q "$SRC"
git -C "$SRC" remote add origin "$SHADPS4_REPO"
git -C "$SRC" fetch -q --depth 1 origin "$SHADPS4_REV"
git -C "$SRC" checkout -q --detach FETCH_HEAD
test "$(git -C "$SRC" rev-parse HEAD)" = "$SHADPS4_REV"
git -C "$SRC" submodule update --init --recursive --depth 1 --jobs 8
for p in $(ls tools/shadps4/patches/*.patch | sort); do
  echo "applying $(basename "$p")"
  git -C "$SRC" apply --whitespace=nowarn "$WORK/$p"
done

bash "$SRC/runtime/scripts/build-shadps4-arm64.sh"
BIN=$SRC/runtime/build/shadps4-arm64-stage/bin/shadps4-arm64
test -x "$BIN"
cat "$SRC/runtime/build/shadps4-arm64-stage/needed.txt"
# SDL3 found the audio drivers the runtime needs.
grep -E 'SDL_AUDIO_DRIVER_(PULSEAUDIO|ALSA)' "$SRC"/runtime/build/shadps4-arm64/externals/sdl3/include-config-release/build_config/SDL_build_config.h
grep -q '#define SDL_AUDIO_DRIVER_PULSEAUDIO 1' "$SRC"/runtime/build/shadps4-arm64/externals/sdl3/include-config-release/build_config/SDL_build_config.h
# The patch is in.
grep -aq 'BACHATA_MANAGED' "$BIN"

APPDIR=$WORK/build/shadPS4.AppDir
rm -rf "$APPDIR"
install -Dm755 "$BIN" "$APPDIR/usr/bin/shadps4"
cat > "$APPDIR/AppRun" <<'RUN'
#!/bin/sh
# shadPS4 is a command-line emulator; opened from a menu with nothing to run, it shows its own
# Big Picture game list instead of a usage box.
# The first time, the ROMs folder's ps4 folder (any case) is added to its game folders so the
# list is not empty; a folder the player removes later stays removed.
HERE=$(dirname "$(readlink -f "$0")")
if [ $# -eq 0 ]; then
  MARK="${XDG_DATA_HOME:-$HOME/.local/share}/shadPS4/.droiddeck-games-folder"
  if [ ! -e "$MARK" ]; then
    for d in /root/ROMs/*; do
      case "$(basename "$d" | tr 'A-Z' 'a-z')" in
        ps4) [ -d "$d" ] && "$HERE/usr/bin/shadps4" --add-game-folder "$d" && mkdir -p "$(dirname "$MARK")" && : > "$MARK" ;;
      esac
    done
  fi
  set -- --big-picture
fi
exec "$HERE/usr/bin/shadps4" "$@"
RUN
chmod 755 "$APPDIR/AppRun"
cat > "$APPDIR/shadps4.desktop" <<'DESK'
[Desktop Entry]
Type=Application
Name=shadPS4
Exec=shadps4
Icon=shadps4
Categories=Game;Emulator;
DESK
cp "$SRC/src/resources/shadps4.png" "$APPDIR/shadps4.png"
ln -sf shadps4.png "$APPDIR/.DirIcon"

curl -fsSL -o runtime-aarch64 "$APPIMAGE_RUNTIME_URL"
file runtime-aarch64 | grep -q 'ARM aarch64'
mksquashfs "$APPDIR" shadps4.squashfs -root-owned -noappend -comp zstd -Xcompression-level 19
cat runtime-aarch64 shadps4.squashfs > shadps4.AppImage
chmod 755 shadps4.AppImage
rm -f shadps4.squashfs runtime-aarch64
sha256sum shadps4.AppImage | tee shadps4.AppImage.sha256
ls -l shadps4.AppImage
