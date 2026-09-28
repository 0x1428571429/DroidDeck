# Bionic Steam client probe

This is a research harness for Valve's Android/Bionic ARM64 Steam client
libraries. It does **not** vendor or redistribute Valve binaries. The build
script fetches the current package from Valve's public Steam client-update
endpoint into a temporary working directory.

The question under test is narrow:

> Can Valve's `androidarm64/libsteamclient.so` run directly under Android's
> Bionic linker, and if so, what does it need in order to become a usable
> Steam client?

## Result

Yes, the library itself works directly on stock Android. It is not a complete
Steam desktop client, however. It is a Steamworks IPC client for a separate
full Steam process.

On 2026-09-28, the public-beta ARM64 client manifest contained
`bins_androidarm64_linuxarm64.zip.35688b00e791987ab70b1b6c6ddd006d4c864470`
with SHA-256:

`811859a83c7c5ff220ac9731235e76fd0d5da603a5cdb56fe5f08e145a417cbd`

The archive contains Android ARM64 builds of `libsteamclient.so`,
`libsteamnetworkingsockets.so`, `libtier0_s.so`, `libvstdlib_s.so`, and
`steamservice.so`.
## ABI evidence

`libsteamclient.so` is a genuine Android/NDK build, not a glibc ARM64 binary
with an Android-flavored directory name:

- ELF64 AArch64 shared object.
- Depends on `libandroid.so`, `liblog.so`, `libm.so`, `libdl.so`, and Bionic
  `libc.so`.
- Uses the NDK C++ namespace (`std::__ndk1`).
- Carries `.note.android.ident` and Android LLVM/Clang build strings.
- Contains no `GLIBC_*` symbol-version requirements.

Android's stock linker on an AYN Thor loaded both Valve libraries with no
compatibility shim. `CreateInterface("SteamClient023")` also succeeded.

## What `steamservice.so` proved

`steamservice.so` exports `SteamService_StartThread`,
`SteamService_GetIPCServer`, `SteamService_Stop`, and
`SteamService_Shutdown`. A non-null service name starts its worker thread
successfully on Android and shuts down normally.

That service is **not** the Steamworks host expected by `libsteamclient.so`.
Starting it does not create the TCP IPC listener used by `CreateSteamPipe`.
The service probe is retained because it establishes that the Bionic service
code itself is runnable and documents the calling convention.
## The actual dependency: Steam IPC on TCP 57343

With no Steam host available, both the legacy exported C entry point and the
`SteamClient023` virtual `CreateSteamPipe` method return `0`.

An `LD_PRELOAD` trace of `socket()` and `connect()` showed why:

```text
socket(AF_INET, SOCK_STREAM, IPPROTO_TCP) -> fd
connect(fd, 127.0.0.1:57343) -> ECONNREFUSED
connect(fd, 127.0.0.1:57343) -> ECONNREFUSED
```

A dummy listener captured the client's first 17 bytes:

```text
0d 00 00 00 09 01 00 00 00 00 00 00 00 00 00 00 00
```

Port 57343 is Steam's long-standing local IPC listener. Valve's Lepton
Android compatibility environment uses the same Steam gateway for its
Android `libsteamclient.so`.

## End-to-end proof

Desktop Steam on a Mac was listening on host `127.0.0.1:57343`. We mapped
Android's loopback port to it temporarily with:

```sh
adb reverse tcp:57343 tcp:57343
```
With no local `steamservice.so` involved, the unmodified Bionic client on the
Thor then returned:

```text
SteamClient023=<non-null> rc=0
Steam_CreateSteamPipe=1
Steam_ConnectToGlobalUser=1
Steam_BConnected=1
Steam_BLoggedOn=1
Steam_BReleaseSteamPipe=1
```

This proves the Bionic client is operational on normal Android and can speak
the real cross-process Steam IPC protocol to an ordinary desktop Steam
process.

## Implication for DroidDeck / PRoot

This discovery does **not** by itself remove PRoot. The Android archive does
not contain the full Steam executable or `steamwebhelper`; the Bionic client
expects that full Steam process to exist elsewhere and own port 57343.

The useful architectural split is now proven rather than hypothetical:

```text
Android/Bionic workload
    -> Valve Bionic libsteamclient.so
    -> TCP Steam IPC :57343
    -> full Steam host process
```

For a PRoot-free DroidDeck, the remaining problem is therefore the **host
Steam process**, not Steamworks ABI compatibility. Plausible future backends
are a real Linux environment (rooted chroot/namespaces), a VM/AVF guest with
a host bridge, or a future Valve Bionic build of the full client. Reimplementing
Steam's private IPC server should be treated as a last resort.
## Reproducing

Build and fetch Valve's current Android package into `/tmp`:

```sh
tools/research/bionic-steam-probe/fetch_build.sh
```

Run without a Steam host. This should resolve the Bionic interfaces but
return pipe/user handle `0`:

```sh
tools/research/bionic-steam-probe/run_probe.sh
```

If desktop Steam is running on the ADB host and listening on loopback, test
the real bridge:

```sh
tools/research/bionic-steam-probe/run_probe.sh --reverse-host-steam
```

The runner removes the ADB reverse mapping on exit. No Valve binary is copied
into this repository.

## Security note

Steam binds 57343 to loopback on desktop systems. Keep any DroidDeck bridge
equally constrained; do not expose this private Steam IPC endpoint to the LAN.
On Android all regular applications share the device network namespace, so a
future local host design should also consider how to prevent unrelated apps
from reaching a loopback-only Steam IPC listener.