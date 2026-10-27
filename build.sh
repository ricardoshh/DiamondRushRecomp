#!/usr/bin/env bash
# Compiles the launcher + javax.microedition layer into build/diamondrush-recomp.jar
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
cd "$root"

if [ -x tools/jdk/bin/javac ]; then bin="$root/tools/jdk/bin/"; else bin=""; fi

mkdir -p lib
libs=()
while IFS= read -r line; do
    line="${line%%#*}"; line="$(echo "$line" | tr -d '[:space:]')"
    [ -z "$line" ] && continue
    dest="lib/$(basename "$line")"
    if [ ! -f "$dest" ]; then
        echo "download $line"
        curl -sSfL -o "$dest" "https://repo1.maven.org/maven2/$line"
    fi
    libs+=("$root/$dest")
done < deps.txt

rm -rf build/classes
mkdir -p build/classes
cp_list="$(IFS=:; echo "${libs[*]}")"
find src -name '*.java' > build/sources.txt
"${bin}javac" --release 21 -encoding UTF-8 -Xlint:-options -cp "$cp_list" -d build/classes @build/sources.txt

# Bundle the libraries (and their native binaries) into a single jar.
(cd build/classes && for l in "${libs[@]}"; do "${bin}jar" --extract --file "$l"; done && rm -rf META-INF)
"${bin}jar" --create --file build/diamondrush-recomp.jar --main-class drpc.Launcher -C build/classes .
echo "OK -> build/diamondrush-recomp.jar"
