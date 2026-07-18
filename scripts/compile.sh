#!/usr/bin/env bash
# Compiles the whole Java project into ./out - zero external dependencies needed.
set -e
cd "$(dirname "$0")/.."
rm -rf out
mkdir -p out
javac -d out $(find src/main/java -name "*.java")
echo "Compiled successfully -> out/"
