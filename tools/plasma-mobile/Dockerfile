FROM menci/archlinuxarm:base-devel

# Pacman 7's Landlock sandbox cannot start in this Docker kernel. Package builds still run as the
# container user; disable only the unavailable sandbox paths for the dependency-install layer.
RUN sed -i \
      's/^[[:space:]]*#DownloadUser.*/DownloadUser =/; s/^#DisableSandboxFilesystem/DisableSandboxFilesystem/; s/^#DisableSandboxSyscalls/DisableSandboxSyscalls/' \
      /etc/pacman.conf \
    && pacman -Syu --noconfirm --needed \
      base-devel cmake extra-cmake-modules git ninja pkgconf \
      plasma-mobile plasma-wayland-protocols vulkan-headers \
    && pacman -Scc --noconfirm

WORKDIR /src
