#!/bin/bash
# Installs the Plasma package the way the app does - runtime, then the desktop package, then this
# one, each extracted over the last - and checks that its programs load in that rootfs: every
# library they link resolves (with the runtime's glibc), and KWin and plasmashell start far enough
# to print their versions. Runs on an arm64 host; needs sudo for chroot.
#
#   tools/plasma/smoke-test.sh <plasma.tar.zst>
set -euo pipefail
# On Actions, failures also go out as annotations, which can be read back without the log.
annotate() { [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error title=plasma smoke test::$*"; echo "$*"; }
trap 'annotate "stopped at line $LINENO: $BASH_COMMAND"' ERR
pkg=$(realpath "${1:?plasma.tar.zst}")
here=$(cd "$(dirname "$0")" && pwd)
. "$here/release.env"
work=$(mktemp -d)
root=$work/root
mkdir -p "$root"

fetch() { # url sha256 out
  curl -fsSL --retry 6 --retry-delay 5 --retry-all-errors -o "$3" "$1"
  echo "$2  $3" | sha256sum -c -
}
fetch "$RUNTIME_URL" "$RUNTIME_SHA256" "$work/runtime.tar.zst"
fetch "$DESKTOP_URL" "$DESKTOP_SHA256" "$work/desktop.tar.zst"
for t in "$work/runtime.tar.zst" "$work/desktop.tar.zst" "$pkg"; do
  sudo tar --zstd -xf "$t" -C "$root"
done
rm -f "$work/runtime.tar.zst" "$work/desktop.tar.zst"

fail=0
for bin in usr/bin/kwin_wayland usr/bin/plasmashell usr/bin/dolphin usr/bin/konsole usr/bin/systemsettings; do
  [ -e "$root/$bin" ] || { annotate "MISSING /$bin"; fail=1; }
done
ls "$root"/usr/lib/startplasma-waylandsession "$root"/usr/lib*/libexec/startplasma-waylandsession 2>/dev/null \
  || echo "note: no startplasma-waylandsession; the launcher starts plasmashell itself"

# Every ELF the package added, checked with the rootfs's own loader.
tar --zstd -tf "$pkg" | sed -n 's#^\./##; /^usr\/\(bin\|lib\)\/.*[^/]$/p' > "$work/files.txt"
: > "$work/unresolved.txt"
while read -r f; do
  [ -f "$root/$f" ] && [ ! -L "$root/$f" ] || continue
  head -c 4 "$root/$f" | grep -q $'\x7fELF' || continue
  sudo chroot "$root" /usr/bin/ldd "/$f" 2>&1 | grep -E "not found" | sed "s#^#/$f: #" >> "$work/unresolved.txt" || true
done < "$work/files.txt"
if [ -s "$work/unresolved.txt" ]; then
  echo "== unresolved libraries or symbol versions:"
  sort -u "$work/unresolved.txt" | head -80
  annotate "unresolved: $(sed 's#^.*: *##' "$work/unresolved.txt" | sort | uniq -c | sort -rn | head -12 | tr -s ' ' | tr '\n' ';')"
  fail=1
else
  echo "== every library in the package resolves in the runtime"
fi

for prog in kwin_wayland plasmashell; do
  if out=$(sudo chroot "$root" /usr/bin/env -i PATH=/usr/bin HOME=/root QT_QPA_PLATFORM=offscreen "/usr/bin/$prog" --version 2>&1); then
    echo "$out"
  else
    annotate "FAILED: $prog --version: $(echo "$out" | tail -3 | tr '\n' ' ')"; fail=1
  fi
done
sudo rm -rf "$work"
exit $fail
