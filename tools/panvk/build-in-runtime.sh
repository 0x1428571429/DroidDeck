#!/bin/bash
# Experimental kbase/JM PanVK build. Run inside an AArch64 glibc environment.
# Output stays private to the supplied build directory; no system ICD is installed.
set -euo pipefail

source_dir=${1:?Usage: build-in-runtime.sh SOURCE_DIR BUILD_DIR}
build_dir=${2:?Usage: build-in-runtime.sh SOURCE_DIR BUILD_DIR}
revision=efd07bab401b263a9ac114560ecdb9a2934f5d43
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
source_dir=$(cd -- "$source_dir" && pwd)
mkdir -p "$build_dir"
build_dir=$(cd -- "$build_dir" && pwd)

if [ "$(uname -m)" != aarch64 ]; then
  echo 'This script needs a native AArch64 Linux build environment.' >&2
  exit 1
fi
if [ "$(git -C "$source_dir" rev-parse HEAD)" != "$revision" ]; then
  echo "Expected FristOneRR-Panvk-Source revision $revision." >&2
  exit 1
fi
for patch_file in "$script_dir"/patches/*.patch; do
  if git -C "$source_dir" apply --reverse --check "$patch_file" 2>/dev/null; then
    continue
  fi
  git -C "$source_dir" apply --check "$patch_file"
  git -C "$source_dir" apply "$patch_file"
done

# The fork's SPIRV-Tools probe contains Clang-only warning options.
export CC=clang CXX=clang++
meson setup "$build_dir" "$source_dir" --buildtype=release \
  --prefix="$build_dir/install" --libdir=lib \
  -Dbuild-tests=false -Dgallium-drivers= -Dvulkan-drivers=panfrost \
  -Dpanfrost-kmds=kbase -Dplatforms=x11,wayland \
  -Dglx=disabled -Degl=disabled -Dgbm=disabled \
  -Dgles1=disabled -Dgles2=disabled -Dopengl=false \
  -Dllvm=enabled -Dmesa-clc=enabled -Dprecomp-compiler=enabled \
  -Dpanfrost-rust=false -Dvalgrind=disabled -Dzstd=enabled
ninja -C "$build_dir" -j"${JOBS:-2}" src/panfrost/vulkan/libvulkan_panfrost.so

python3 - "$build_dir" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
driver = root / 'src/panfrost/vulkan/libvulkan_panfrost.so'
manifest = {'file_format_version': '1.0.0', 'ICD': {
    'library_path': str(driver), 'api_version': '1.4.0'}}
(root / 'panvk-g52-icd.json').write_text(json.dumps(manifest, indent=2) + '\n')
PY
sha256sum "$build_dir/src/panfrost/vulkan/libvulkan_panfrost.so"
echo "Private ICD: $build_dir/panvk-g52-icd.json"
