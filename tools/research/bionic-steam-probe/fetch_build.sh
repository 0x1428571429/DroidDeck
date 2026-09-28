#!/usr/bin/env bash
set -euo pipefail

MANIFEST_URL="${MANIFEST_URL:-https://client-update.steamstatic.com/steam_client_publicbeta_linuxarm64}"
WORKDIR="${WORKDIR:-${TMPDIR:-/tmp}/droiddeck-bionic-steam-probe}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$WORKDIR/android" "$WORKDIR/bin"

find_ndk() {
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
        printf '%s\n' "$ANDROID_NDK_HOME"
        return
    fi
    if [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
        printf '%s\n' "$ANDROID_NDK_ROOT"
        return
    fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
    local ndk
    ndk="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
    [[ -n "$ndk" ]] || return 1
    printf '%s\n' "$ndk"
}
NDK="$(find_ndk)" || {
    echo "Android NDK not found; set ANDROID_NDK_HOME" >&2
    exit 1
}
HOST_TAG="linux-x86_64"
[[ "$(uname -s)" == Darwin ]] && HOST_TAG="darwin-x86_64"
CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android26-clang"

curl -fsSL "$MANIFEST_URL" -o "$WORKDIR/manifest.vdf"
PACKAGE="$(python3 - "$WORKDIR/manifest.vdf" <<'PY'
import re
import sys
text = open(sys.argv[1], encoding="utf-8").read()
m = re.search(
    r'"bins_androidarm64_linuxarm64"\s*\{.*?"file"\s*"([^"]+)"',
    text,
    re.S,
)
if not m:
    raise SystemExit("bins_androidarm64_linuxarm64 not found")
print(m.group(1))
PY
)"
PACKAGE_SHA="$(python3 - "$WORKDIR/manifest.vdf" <<'PY'
import re
import sys
text = open(sys.argv[1], encoding="utf-8").read()
block = re.search(r'"bins_androidarm64_linuxarm64"\s*\{(.*?)\n\s*\}', text, re.S)
if not block:
    raise SystemExit("bins_androidarm64_linuxarm64 block not found")
m = re.search(r'"sha2"\s*"([0-9a-fA-F]{64})"', block.group(1))
if not m:
    raise SystemExit("sha2 missing from Android ARM64 package")
print(m.group(1).lower())
PY
)"
echo "Valve package: $PACKAGE"
curl -fsSL "https://client-update.steamstatic.com/$PACKAGE" -o "$WORKDIR/android.zip"
ACTUAL_SHA="$(python3 - "$WORKDIR/android.zip" <<'PY'
import hashlib
import sys
print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
PY
)"
[[ "$ACTUAL_SHA" == "$PACKAGE_SHA" ]] || {
    echo "SHA-256 mismatch: manifest=$PACKAGE_SHA download=$ACTUAL_SHA" >&2
    exit 1
}
python3 - "$WORKDIR/android.zip" "$WORKDIR/android" <<'PY'
import os
import sys
import zipfile

archive, out = sys.argv[1:]
wanted = {
    "libsteamclient.so",
    "steamservice.so",
    "libtier0_s.so",
    "libvstdlib_s.so",
}
with zipfile.ZipFile(archive) as zf:
    for name in zf.namelist():
        base = os.path.basename(name)
        if base in wanted:
            target = os.path.join(out, base)
            with open(target, "wb") as f:
                f.write(zf.read(name))
            print(f"extracted {name} -> {target}")
PY

"$CC" -O0 -g -Wall -Wextra "$SCRIPT_DIR/steamclient_probe.c"     -ldl -o "$WORKDIR/bin/steamclient-probe"
"$CC" -O0 -g -Wall -Wextra "$SCRIPT_DIR/steamservice_probe.c"     -ldl -o "$WORKDIR/bin/steamservice-probe"
"$CC" -O0 -g -Wall -Wextra "$SCRIPT_DIR/steamclient_service_probe.c"     -ldl -o "$WORKDIR/bin/steamclient-service-probe"
"$CC" -shared -fPIC -O0 -g -Wall -Wextra "$SCRIPT_DIR/trace_hooks.c"     -o "$WORKDIR/bin/trace-hooks.so"
echo "Built probes in $WORKDIR/bin"
