#!/usr/bin/env bash
# Builds the `gaps` CLI over a patched copy of the pinned crengine tree. Everything it fetches or
# builds lands under $BUILD; the repository and its submodule are only read.
#
#   engine/tools/desktop/build.sh            # into ${TMPDIR:-/tmp}/perigee-gaps-build
#   BUILD=/some/dir engine/tools/desktop/build.sh
#
# Needs a C/C++ compiler, cmake, make, zlib headers and network access to the archives pinned in
# CMakeLists.txt (the ones of engine/src/main/cpp/cmake/deps.cmake). Not root, no system packages.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
BUILD=${BUILD:-${TMPDIR:-/tmp}/perigee-gaps-build}

mkdir -p "$BUILD/tmp" "$BUILD/deps"
# Compiler and cmake scratch files go under $BUILD as well.
export TMPDIR="$BUILD/tmp"

# A fresh copy of the submodule at its pinned commit, without its git link.
SRC="$BUILD/crengine-src"
rm -rf "$SRC"
mkdir -p "$SRC"
tar -C "$ROOT/third_party/crengine" --exclude=./.git -cf - . | tar -C "$SRC" -xf -

# The app's own patches first, so the engine is the one Perigee ships, then the measurement hook.
# Outside any repository git apply works as patch does; $BUILD may be inside one.
for PATCH in "$ROOT"/engine/src/main/cpp/patches/*.patch "$HERE/lvtextfm-gaps.patch"; do
    echo "applying $(basename "$PATCH")"
    GIT_CEILING_DIRECTORIES="$BUILD" git -C "$SRC" apply --whitespace=nowarn "$PATCH"
done

cmake -S "$HERE" -B "$BUILD/cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCRENGINE_DIR="$SRC" \
    -DPERIGEE_DEPS_CACHE="$BUILD/deps"
cmake --build "$BUILD/cmake" --target gaps -j "$(nproc)"
echo "built $BUILD/cmake/gaps"
