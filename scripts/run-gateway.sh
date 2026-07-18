#!/usr/bin/env bash
cd "$(dirname "$0")/.."
if [ ! -d out ]; then
  echo "Compiling first..."
  ./scripts/compile.sh
fi
java -cp out com.mlpipeline.Main
