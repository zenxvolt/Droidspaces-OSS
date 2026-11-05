#!/bin/bash
# build-zstd.sh - Build the static zstd the Android app bundles next to busybox
# SPDX-License-Identifier: GPL-3.0-or-later
#
# BusyBox has no zstd applet, so .tar.zst export and import run this binary. It is built
# from a pinned upstream release with the same musl toolchains as the backend and written
# to the app's binaries folder as zstd-<arch>. Run it again to rebuild, or bump the two
# variables below to move to a newer release.
#
# Usage: scripts/build-zstd.sh [arch...]     (default: aarch64 armhf x86_64 x86)

set -euo pipefail

ZSTD_VERSION=1.5.7
ZSTD_SHA256=eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3
ZSTD_URL="https://github.com/facebook/zstd/releases/download/v${ZSTD_VERSION}/zstd-${ZSTD_VERSION}.tar.gz"

# The four arches the APK ships a backend for, and the toolchain that builds each
declare -A TRIPLETS=(
    [aarch64]=aarch64-linux-musl
    [armhf]=arm-linux-musleabihf
    [x86_64]=x86_64-linux-musl
    [x86]=i686-linux-musl
)

die() { echo "[-] $*" >&2; exit 1; }

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DEST="$REPO/Android/app/src/main/assets/binaries"

ARCHES=("$@")
[ ${#ARCHES[@]} -gt 0 ] || ARCHES=(aarch64 armhf x86_64 x86)

# Same lookup order as the Makefile: MUSL_CROSS, then ~/toolchains, then PATH
find_cc() {
    local t=$1
    if [ -n "${MUSL_CROSS:-}" ] && [ -f "$MUSL_CROSS/$t-gcc" ]; then
        echo "$MUSL_CROSS/$t-gcc"
    elif [ -f "$HOME/toolchains/$t-cross/bin/$t-gcc" ]; then
        echo "$HOME/toolchains/$t-cross/bin/$t-gcc"
    elif command -v "$t-gcc" >/dev/null 2>&1; then
        command -v "$t-gcc"
    else
        return 1
    fi
}

for tool in curl make sha256sum tar; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "[*] Downloading zstd $ZSTD_VERSION..."
curl -fsSL -o "$WORK/zstd.tar.gz" "$ZSTD_URL"
echo "$ZSTD_SHA256  $WORK/zstd.tar.gz" | sha256sum -c - >/dev/null \
    || die "zstd $ZSTD_VERSION source does not match the pinned SHA-256"

mkdir -p "$DEST"
for arch in "${ARCHES[@]}"; do
    triplet=${TRIPLETS[$arch]:-}
    [ -n "$triplet" ] || die "unknown arch '$arch' (use: ${!TRIPLETS[*]})"
    cc=$(find_cc "$triplet") || die "$triplet-gcc not found. Run scripts/install-musl.sh $arch or set MUSL_CROSS"

    # A fresh tree per arch: zstd-small is one compile and link step, and make would
    # call the first arch's binary up to date.
    build="$WORK/$arch"
    mkdir "$build"
    tar -xzf "$WORK/zstd.tar.gz" -C "$build" --strip-components=1

    echo "[*] Building zstd-$arch with $cc"
    # zstd-small is upstream's minimal target (zstd format only, no benchmark, no
    # dictionary builder, no legacy decoders). It builds single-threaded unless told
    # otherwise, and export wants -T0. The x86 assembly decoder is off so all four
    # arches take the same C path. The prefix map and build-id keep the output
    # identical no matter where the script runs.
    make -C "$build/programs" zstd-small CC="$cc" ZSTD_NO_ASM=1 \
        CPPFLAGS="-DZSTD_MULTITHREAD -DZSTD_DISABLE_ASM -fstack-protector-strong -ffile-prefix-map=$build=." \
        LDFLAGS="-static -pthread -Wl,-z,relro,-z,now -Wl,--build-id=none" >/dev/null

    install -m 755 "$build/programs/zstd-small" "$DEST/zstd-$arch"
    echo "[+] $DEST/zstd-$arch ($(stat -c %s "$DEST/zstd-$arch") bytes)"
    echo "    sha256 $(sha256sum "$DEST/zstd-$arch" | cut -d' ' -f1)"
done
