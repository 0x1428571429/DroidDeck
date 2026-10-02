#!/usr/bin/bash
# Runs INSIDE an Arch Linux ARM container (menci/archlinuxarm:base-devel) on an arm64 runner.
# Builds FEX for the runtime, with tools/fex/patches, (Arch Linux ARM ships no package and FEX publishes no Linux
# binaries), plus the runtime's own unsquashfs to unpack FEX's x86 rootfs image, and packs both
# as fex.tzst (usr/local/...) for bannerlator-steam-x64 to stage. No thunks: they need an x86
# cross toolchain, and the x86-64 Steam client only needs FEX to run, not host GPU drivers.
set -euxo pipefail
VERSION=${FEX_VERSION:-FEX-2609}
WORK=/work
cd "$WORK"
# Same pacman workarounds as tools/gamescope/build-in-arch.sh.
grep -q '^DisableSandbox' /etc/pacman.conf || sed -i 's/^\[options\]/[options]\nDisableSandbox/' /etc/pacman.conf
{ for m in https://ca.us.mirror.archlinuxarm.org https://fl.us.mirror.archlinuxarm.org https://de3.mirror.archlinuxarm.org https://nl.mirror.archlinuxarm.org; do echo "Server = $m/\$arch/\$repo"; done; cat /etc/pacman.d/mirrorlist; } > /etc/pacman.d/mirrorlist.new
mv /etc/pacman.d/mirrorlist.new /etc/pacman.d/mirrorlist
pacman -Syu --noconfirm --needed git zstd binutils cmake ninja clang lld llvm python squashfs-tools file

rm -rf fex-src out && git clone -q --depth 1 --branch "$VERSION" --recurse-submodules --shallow-submodules https://github.com/FEX-Emu/FEX.git fex-src
# Our proot answers openat2 with ENOSYS (tools/proot/PATCHES.md, 0008); FEX opened every rootfs
# path with openat2 and fell through to the arm64 guest's files on anything but EXDEV.
for p in tools/fex/patches/*.patch; do patch -d fex-src -p1 --no-backup-if-mismatch < "$p"; done
# TUNE_CPU none: the default (native) would tune for the runner's Neoverse cores, not the device.
cmake -S fex-src -B fex-build -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr/local \
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DUSE_LINKER=lld \
  -DTUNE_CPU=none -DTUNE_ARCH=armv8-a \
  -DBUILD_TESTING=OFF -DBUILD_THUNKS=OFF -DBUILD_FEXCONFIG=OFF -DENABLE_ASSERTIONS=OFF \
  -DENABLE_CCACHE=OFF -DENABLE_OFFLINE_TELEMETRY=OFF
ninja -C fex-build
DESTDIR="$WORK/out" ninja -C fex-build install
install -Dm755 /usr/bin/unsquashfs out/usr/local/bin/unsquashfs
# Nothing outside the runtime's own libraries: every needed soname must resolve in a stock guest.
for f in out/usr/local/bin/*; do
  file "$f" | grep -q ELF || continue
  readelf -d "$f" | awk '/NEEDED/ { gsub(/[\[\]]/, "", $5); print $5 }'
done | sort -u | tee fex-needed.txt
(cd out && find . -type f -o -type l | sort) | tee fex-files.txt
tar -C out -c . | zstd -19 -T0 -o fex.tzst
sha256sum fex.tzst | tee fex.tzst.sha256
