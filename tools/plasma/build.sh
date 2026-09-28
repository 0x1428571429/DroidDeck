#!/bin/bash
# Builds the KDE Plasma shell package: the closure of seeds.txt over Arch Linux ARM, as a zstd
# tarball that extracts over the rootfs, with the desktop package (LXQt) already in it.
#
# The closure and extraction are winlator-contents' own desktop/build-pkg.sh, pinned below, so this
# package is made exactly like the desktop one. What it leaves out is the runtime's packages (as the
# desktop does) plus the desktop package's - except Qt and the KDE libraries, which are shipped
# again: KWin and plasmashell use Qt's private API and must match the Qt they were built against.
#
#   tools/plasma/build.sh <out dir>
#
# Needs curl, git, tar, zstd, python3.
set -euo pipefail
out=${1:?out dir}
here=$(cd "$(dirname "$0")" && pwd)
. "$here/release.env"
work=$(mktemp -d)
mkdir -p "$out"

git init -q "$work/contents"
git -C "$work/contents" fetch -q --depth 1 https://github.com/The412Banner/winlator-contents.git "$CONTENTS_REF"
git -C "$work/contents" checkout -q FETCH_HEAD

curl -fsSL --retry 6 --retry-delay 5 --retry-all-errors -o "$work/desktop.packages.txt" "$DESKTOP_PACKAGES_URL"

# Package names from the desktop's file list (name-pkgver-pkgrel-arch.pkg.tar.xz); the ones Plasma
# must bring in its own build are not counted as present.
python3 - "$work/desktop.packages.txt" > "$work/desktop-names.txt" <<'PY'
import re, sys
keep = re.compile(r"^(qt6-|layer-shell-qt|kf6-|kwayland|plasma|breeze|kde|libplasma|kirigami|kpipewire|kscreenlocker|kglobalacc)")
for line in open(sys.argv[1]):
    line = line.strip()
    if not line: continue
    name = line.rsplit("-", 3)[0]
    if not keep.match(name): print(name)
PY
cat "$work/contents/desktop/base-packages.txt" "$work/desktop-names.txt" | sort -u > "$work/present.txt"
echo "== plasma: $(wc -l < "$work/present.txt") packages counted as present (runtime + desktop, less Qt/KDE)"

bash "$work/contents/desktop/build-pkg.sh" plasma "$here/seeds.txt" "$work/present.txt" "$out"

# What this package replaces from the desktop's: printed so a Qt bump is never a surprise.
python3 - "$work/desktop.packages.txt" "$out/plasma.packages.txt" <<'PY'
import sys
def names(p): return {l.strip().rsplit("-", 3)[0]: l.strip() for l in open(p) if l.strip()}
d, p = names(sys.argv[1]), names(sys.argv[2])
for n in sorted(set(d) & set(p)):
    if d[n] != p[n]: print("== replaces", d[n], "->", p[n])
PY
rm -rf "$work"
