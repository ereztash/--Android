#!/usr/bin/env bash
# E2 — the smallest heap each shipped structure survives in.
#
# One JVM per (stage, heap) pair, launched directly rather than through Gradle: Gradle would add
# its own heap to every measurement, which is the thing being measured.
#
# A heap floor OVER-states the structure's retained size by whatever headroom the collector
# needs. That is the useful direction: it is a lower bound on what a device must supply.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP="${CP:-}"
[ -n "$CP" ] || { echo "set CP to the :core test runtime classpath (./gradlew -q :core:printTestClasspath)"; exit 2; }
ASSETS="$ROOT/lexicon/assets"

printf '%-12s %10s   %s\n' stage floor note
for stage in empty lexicon trie trie-copy frequency bigrams; do
  lo=1; hi=512; note=""
  # binary search the smallest -Xmx that completes
  while [ $((hi - lo)) -gt 1 ]; do
    mid=$(( (lo + hi) / 2 ))
    out=$(java -Xmx${mid}m -XX:+UseSerialGC -cp "$CP" \
          com.hebrewime.core.scratch.MemoryFloor "$stage" "$ASSETS" 2>/dev/null)
    case "$out" in
      OK*) hi=$mid; note="${out#OK $stage }"; note="${note%% max=*}" ;;
      *)   lo=$mid ;;
    esac
  done
  printf '%-12s %9sm   %s\n' "$stage" "$hi" "$note"
done
