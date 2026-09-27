# Plasma Mobile 6.7.5 runtime backports

The Thor root installs Plasma Mobile 6.7.5. The app build backports KDE's
[`Use effect caching` change](https://invent.kde.org/plasma/plasma-mobile/-/commit/73c5b4fd2815dc5d845c09cf578f60a9af3e708b)
to fix the mobile task switcher state, and removes Folio's requirement for a
shared KWayland client connection before it queries installed applications.

`tools/build_local.sh` stages the checked-in ARM64 modules under the generated
`app/src/main/assets/linuxfs` tree. To rebuild those modules, run
`tools/plasma-mobile/rebuild.sh`; it builds the affected targets in the Arch
Linux ARM environment described by `Dockerfile` and refreshes `prebuilt/`.
This keeps the changes isolated to the Plasma runtime; the LXQt/Steam root is
not modified. The module sources and patches target tag `v6.7.5`.

The app-catalog patch currently logs whether Folio receives a KWayland
connection and how many KService entries it finds. Those diagnostics are for
Thor dogfooding and can be removed after the empty-drawer cause is confirmed.
