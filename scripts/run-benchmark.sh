#!/usr/bin/env bash
cd "$(dirname "$0")/.."
java -cp out com.mlpipeline.benchmark.CpuBoundBenchmark
