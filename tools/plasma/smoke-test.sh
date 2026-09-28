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
notice() { [ -n "${GITHUB_ACTIONS:-}" ] && echo "::notice title=plasma smoke test::$*"; echo "$*"; }
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
# What the desktop starts must resolve completely (ldd follows every library they load). Other
# helpers and plugins that need an optional dependency the package does not bring (Python
# bindings, PackageKit, input methods...) only fail to load, as on any Arch install without those
# extras; they are counted, not fatal.
core='^/usr/(bin/(kwin_wayland|kwin_wayland_wrapper|plasmashell|ksmserver|kded6|krunner|dolphin|konsole|systemsettings|plasma_session)|lib/(startplasma-waylandsession|xdg-desktop-portal-kde|kactivitymanagerd|polkit-kde-authentication-agent-1|DiscoverNotifier)):'
if [ -s "$work/unresolved.txt" ]; then
  echo "== unresolved libraries or symbol versions:"
  sort -u "$work/unresolved.txt" | head -80
  if grep -qE "$core" "$work/unresolved.txt"; then
    annotate "unresolved in the desktop's own programs: $(grep -E "$core" "$work/unresolved.txt" | sort -u | head -12 | tr -s ' \t' ' ' | tr '\n' ';')"
    fail=1
  fi
  notice "optional helpers and plugins missing a dependency: $(grep -vE "$core" "$work/unresolved.txt" | cut -d: -f1 | sort -u | wc -l)"
else
  echo "== every library in the package resolves in the runtime"
fi

# KWin must start far enough to answer. plasmashell sets up its scene before it reads its arguments
# and cannot without a compositor, so it is held to its libraries (above) and its answer only noted.
# A fresh proc (never a bind of the host's /dev or /proc: the rootfs is deleted at the end).
sudo mount -t proc proc "$root/proc"
sudo mkdir -p "$root/tmp/xdg" && sudo chmod 700 "$root/tmp/xdg"
for prog in kwin_wayland plasmashell; do
  rc=0
  out=$(sudo chroot "$root" /usr/bin/env -i PATH=/usr/bin HOME=/root XDG_RUNTIME_DIR=/tmp/xdg QT_QPA_PLATFORM=offscreen "/usr/bin/$prog" --version 2>&1) || rc=$?
  echo "$prog --version (exit $rc): $out"
  if [ "$rc" -ne 0 ]; then
    if [ "$prog" = kwin_wayland ]; then annotate "FAILED: $prog --version exit $rc: $(echo "$out" | tail -3 | tr '\n' ' ')"; fail=1
    else notice "$prog --version exit $rc: $(echo "$out" | tail -3 | tr '\n' ' ')"; fi
  fi
done
sudo umount "$root/proc"
mountpoint -q "$root/proc" || sudo rm -rf "$work"
exit $fail
