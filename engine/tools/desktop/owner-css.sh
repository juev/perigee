#!/usr/bin/env bash
# Runs the owner-css check of a build.sh build: writes its books and runs it, with the layout cache in
# $BUILD/owner-css. The exit code is the check's, the number of FAILs. The directories have a space and an
# apostrophe in their names, so the child process the check starts has to get its paths intact.
#
#   BUILD=/some/dir engine/tools/desktop/owner-css.sh
#   BUILD=/some/dir LEGACY_BUILD=/other/dir engine/tools/desktop/owner-css.sh
#
# LEGACY_BUILD is a build.sh build without patches/0003 (with the owner-css target): its owner-css first writes
# a layout cache of large.epub with an owner's @font-face, the way the app did before issue #150, and the check
# then opens the book over that cache.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
BUILD=${BUILD:-${TMPDIR:-/tmp}/perigee-gaps-build}
OUT="$BUILD/owner-css"

BOOKS="$OUT/the owner's books"
SCRATCH="$OUT/the owner's scratch"

mkdir -p "$BOOKS" "$SCRATCH"
python3 -I "$HERE/owner-css-books.py" "$BOOKS"
ARGS=(--data "$ROOT/third_party/crengine/cr3gui/data" --fonts "$ROOT/engine/src/main/assets/crengine/fonts" --books "$BOOKS")
LEGACY=()
if [ -n "${LEGACY_BUILD:-}" ]; then
    OLD="$OUT/the old engine's scratch"
    rm -rf "$OLD"
    mkdir -p "$OLD"
    "$LEGACY_BUILD/cmake/owner-css" "${ARGS[@]}" --scratch "$OLD" \
        --child "$OLD/written.tsv" --child-owner alias --child-hit no
    LEGACY=(--legacy-cache "$OLD/cache")
fi
"$BUILD/cmake/owner-css" "${ARGS[@]}" --scratch "$SCRATCH" ${LEGACY[@]+"${LEGACY[@]}"}
