#!/usr/bin/env bash
set -euo pipefail

WORKDIR="${WORKDIR:-${TMPDIR:-/tmp}/droiddeck-bionic-steam-probe}"
ADB="${ADB:-adb}"
REMOTE="/data/local/tmp/droiddeck-bionic"
REVERSE=0

if [[ "${1:-}" == "--reverse-host-steam" ]]; then
    REVERSE=1
elif [[ -n "${1:-}" ]]; then
    echo "usage: $0 [--reverse-host-steam]" >&2
    exit 2
fi

[[ -f "$WORKDIR/bin/steamclient-probe" ]] || {
    echo "Run fetch_build.sh first" >&2
    exit 1
}
[[ -f "$WORKDIR/android/libsteamclient.so" ]] || {
    echo "Valve libsteamclient.so is missing from $WORKDIR" >&2
    exit 1
}

"$ADB" shell "mkdir -p $REMOTE"
"$ADB" push "$WORKDIR/bin/steamclient-probe" "$REMOTE/steamclient-probe" >/dev/null
"$ADB" push "$WORKDIR/android/libsteamclient.so" "$REMOTE/libsteamclient.so" >/dev/null
"$ADB" shell "chmod 755 $REMOTE/steamclient-probe"
cleanup() {
    if (( REVERSE )); then
        "$ADB" reverse --remove tcp:57343 >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

if (( REVERSE )); then
    "$ADB" reverse --remove tcp:57343 >/dev/null 2>&1 || true
    "$ADB" reverse tcp:57343 tcp:57343 >/dev/null
    echo "Mapped Android 127.0.0.1:57343 to host 127.0.0.1:57343."
    echo "A desktop Steam process must already be listening on the host."
else
    echo "No bridge configured; CreateSteamPipe should be 0 unless Steam already"
    echo "exists in the Android network namespace."
fi

set +e
"$ADB" shell "$REMOTE/steamclient-probe $REMOTE/libsteamclient.so"
rc=$?
set -e

if (( REVERSE )); then
    if (( rc == 0 )); then
        echo "PASS: Bionic libsteamclient connected to host Steam."
    else
        echo "FAIL: Bionic libsteamclient did not obtain a Steam pipe." >&2
    fi
fi
exit "$rc"
