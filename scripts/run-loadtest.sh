#!/usr/bin/env bash
cd "$(dirname "$0")/.."
N=${1:-200}
C=${2:-80}
java -cp out com.mlpipeline.loadtest.LoadTester "$N" "$C"
