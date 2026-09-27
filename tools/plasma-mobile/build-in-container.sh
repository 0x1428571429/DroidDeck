#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
plasma_version=6.7.5
source_dir="${TMPDIR:-/tmp}/plasma-mobile-${plasma_version}"
build_dir="${TMPDIR:-/tmp}/plasma-mobile-build-${plasma_version}"
asset_root="${repo_root}/app/src/main/assets/linuxfs"

installed_version=$(pacman -Q plasma-mobile | awk '{print $2}')
case "${installed_version}" in
    "${plasma_version}"-*) ;;
    *)
        echo "Expected plasma-mobile ${plasma_version}, found ${installed_version}." >&2
        exit 1
        ;;
esac

rm -rf -- "${source_dir}" "${build_dir}"
git clone --quiet --depth 1 --branch "v${plasma_version}" \
    https://invent.kde.org/plasma/plasma-mobile.git "${source_dir}"
git -C "${source_dir}" apply --check \
    "${repo_root}/tools/plasma-mobile/patches/0001-mobile-task-switcher.patch" \
    "${repo_root}/tools/plasma-mobile/patches/0002-folio-app-model.patch"
git -C "${source_dir}" apply \
    "${repo_root}/tools/plasma-mobile/patches/0001-mobile-task-switcher.patch" \
    "${repo_root}/tools/plasma-mobile/patches/0002-folio-app-model.patch"

cmake -S "${source_dir}" -B "${build_dir}" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
cmake --build "${build_dir}" --parallel "${DROIDDECK_PLASMA_BUILD_JOBS:-4}" \
    --target mobiletaskswitcherplugin org.kde.plasma.mobile.homescreen.folio

install -Dm644 "${build_dir}/bin/plasma/applets/org.kde.plasma.mobile.homescreen.folio.so" \
    "${asset_root}/usr/lib/qt6/plugins/plasma/applets/org.kde.plasma.mobile.homescreen.folio.so"
install -Dm644 "${build_dir}/kwin/mobiletaskswitcher/plugin/libmobiletaskswitcherplugin.so" \
    "${asset_root}/usr/lib/qt6/qml/org/kde/plasma/private/mobileshell/taskswitcherplugin/libmobiletaskswitcherplugin.so"
install -Dm644 "${source_dir}/kwin/mobiletaskswitcher/package/contents/ui/FlickContainer.qml" \
    "${asset_root}/usr/share/kwin/effects/mobiletaskswitcher/contents/ui/FlickContainer.qml"
install -Dm644 "${source_dir}/kwin/mobiletaskswitcher/package/contents/ui/TaskSwitcher.qml" \
    "${asset_root}/usr/share/kwin/effects/mobiletaskswitcher/contents/ui/TaskSwitcher.qml"
strip --strip-unneeded \
    "${asset_root}/usr/lib/qt6/plugins/plasma/applets/org.kde.plasma.mobile.homescreen.folio.so" \
    "${asset_root}/usr/lib/qt6/qml/org/kde/plasma/private/mobileshell/taskswitcherplugin/libmobiletaskswitcherplugin.so"

echo "Built Plasma Mobile ${plasma_version} app-catalog and task-switcher backports."
