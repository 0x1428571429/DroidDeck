#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
image_name=${DROIDDECK_PLASMA_BUILD_IMAGE:-droiddeck-plasma-mobile-builder:6.7.5}

if ! docker image inspect "${image_name}" >/dev/null 2>&1; then
    docker build --platform linux/arm64 -t "${image_name}" \
        -f "${repo_root}/tools/plasma-mobile/Dockerfile" "${repo_root}/tools/plasma-mobile"
fi

docker run --rm --platform linux/arm64 \
    --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -v "${repo_root}:/src" -w /src "${image_name}" \
    bash /src/tools/plasma-mobile/build-in-container.sh

prebuilt="${repo_root}/tools/plasma-mobile/prebuilt"
asset_root="${repo_root}/app/src/main/assets/linuxfs"
cp "${asset_root}/usr/lib/qt6/plugins/plasma/applets/org.kde.plasma.mobile.homescreen.folio.so" \
    "${prebuilt}/org.kde.plasma.mobile.homescreen.folio.so"
cp "${asset_root}/usr/lib/qt6/qml/org/kde/plasma/private/mobileshell/taskswitcherplugin/libmobiletaskswitcherplugin.so" \
    "${prebuilt}/libmobiletaskswitcherplugin.so"
cp "${asset_root}/usr/share/kwin/effects/mobiletaskswitcher/contents/ui/FlickContainer.qml" \
    "${prebuilt}/FlickContainer.qml"
cp "${asset_root}/usr/share/kwin/effects/mobiletaskswitcher/contents/ui/TaskSwitcher.qml" \
    "${prebuilt}/TaskSwitcher.qml"
echo "Refreshed the Plasma Mobile backports from ${image_name##*:}."
