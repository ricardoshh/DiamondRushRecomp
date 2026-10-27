#!/usr/bin/env bash
# Builds a portable Linux release: dist/DiamondRushRecomp/bin/DiamondRushRecomp (+ bundled Java runtime)
# and archives it to dist/DiamondRushRecomp-linux-x64.tar.gz
set -euo pipefail
version="${1:-0.1.0}"
root="$(cd "$(dirname "$0")" && pwd)"
cd "$root"
./build.sh

if [ -x tools/jdk/bin/jpackage ]; then jpackage="tools/jdk/bin/jpackage"; else jpackage="jpackage"; fi

rm -rf dist build/pkg
mkdir -p build/pkg
cp build/diamondrush-recomp.jar build/pkg/

"$jpackage" --type app-image \
    --name DiamondRushRecomp \
    --app-version "$version" \
    --vendor DiamondRushRecomp \
    --input build/pkg \
    --main-jar diamondrush-recomp.jar \
    --main-class drpc.Launcher \
    --add-modules java.base,java.desktop,jdk.unsupported \
    --jlink-options '--strip-debug --no-header-files --no-man-pages --compress=zip-6' \
    --dest dist

cp LEIA-ME.txt dist/DiamondRushRecomp/
# No version in the name: README links to releases/latest/download/<name> keep working.
tar -C dist -czf "dist/DiamondRushRecomp-linux-x64.tar.gz" DiamondRushRecomp
echo "OK -> dist/DiamondRushRecomp-linux-x64.tar.gz"
