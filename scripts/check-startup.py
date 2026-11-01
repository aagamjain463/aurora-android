#!/usr/bin/env python3
"""Fails when the median cold start in a Macrobenchmark result passes the limit."""
import glob
import json
import sys

LIMIT_MS = 2500  # The target. Real phones and CI emulators differ, so retune if CI proves unrepresentative.

files = glob.glob("benchmark/build/**/*benchmarkData.json", recursive=True)
if not files:
    sys.exit("No benchmark result found.")
medians = [
    b["metrics"]["timeToInitialDisplayMs"]["median"]
    for f in files
    for b in json.load(open(f))["benchmarks"]
    if "timeToInitialDisplayMs" in b["metrics"]
]
if not medians:
    sys.exit("No startup timing in the benchmark result.")
median = max(medians)
print(f"Median cold start: {median:.0f} ms (limit {LIMIT_MS} ms)")
sys.exit(1 if median > LIMIT_MS else 0)
