#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
source_dir="${repo_root}/tools/plasma-mobile/prebuilt"
asset_root="${repo_root}/app/src/main/assets/linuxfs"

install -Dm644 "${source_dir}/org.kde.plasma.mobile.homescreen.folio.so" \
    "${asset_root}/usr/lib/qt6/plugins/plasma/applets/org.kde.plasma.mobile.homescreen.folio.so"
install -Dm644 "${source_dir}/libmobiletaskswitcherplugin.so" \
    "${asset_root}/usr/lib/qt6/qml/org/kde/plasma/private/mobileshell/taskswitcherplugin/libmobiletaskswitcherplugin.so"
install -Dm644 "${source_dir}/FlickContainer.qml" \
    "${asset_root}/usr/share/kwin/effects/mobiletaskswitcher/contents/ui/FlickContainer.qml"
install -Dm644 "${source_dir}/TaskSwitcher.qml" \
    "${asset_root}/usr/share/kwin/effects/mobiletaskswitcher/contents/ui/TaskSwitcher.qml"

echo "Staged the Plasma Mobile 6.7.5 ARM64 backports. Run tools/plasma-mobile/rebuild.sh to rebuild them."
