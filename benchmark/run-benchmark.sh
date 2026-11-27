#!/usr/bin/env bash
# Frame rate benchmark of Figura avatars with the modpack's Epic Fight / Oculus setup.
# Needs benchmark/mods (Epic Fight, figura_extra_fight, Oculus, Embeddium), benchmark/libs (jcpp from the Oculus jar),
# benchmark/avatars/*.nbt (built avatars, eg. from figura/cache/avatars) and a shader pack in benchmark/run/shaderpacks.
# Each pass starts the game, creates or opens a flat world, waits WARMUP seconds, records DURATION seconds and quits.
# Results are appended to benchmark/results/results.txt, screenshots go to benchmark/run/screenshots.
#
# usage: benchmark/run-benchmark.sh label:avatar[:rate[:budget]] ...
#   avatar: file name in benchmark/avatars, or "none"; rate 0 = render scripts every frame
#   CROWD=<folder in benchmark/avatars>: one fake player per .nbt avatar in it (avatar should be none)
#   example: benchmark/run-benchmark.sh baseline:none jiang-unlimited:jiang_linyuan.nbt:0 jiang-default:jiang_linyuan.nbt
set -u
cd "$(dirname "$0")/.."
WARMUP=${WARMUP:-20}
DURATION=${DURATION:-60}
mkdir -p benchmark/results

for pass in "$@"; do
    IFS=: read -r label avatar rate budget <<< "$pass"
    args="-Dfigura.benchmark.label=$label -Dfigura.benchmark.warmup=$WARMUP -Dfigura.benchmark.duration=$DURATION"
    args="$args -Dfigura.benchmark.results=../results/results.txt"
    if [ "$avatar" != "none" ]; then
        args="$args -Dfigura.benchmark.avatar=../avatars/$avatar"
    fi
    [ -n "${rate:-}" ] && args="$args -Dfigura.benchmark.rate=$rate"
    [ -n "${budget:-}" ] && args="$args -Dfigura.benchmark.budget=$budget"
    [ -n "${SPARK:-}" ] && args="$args -Dfigura.benchmark.spark=true"
    [ -n "${CROWD:-}" ] && args="$args -Dfigura.benchmark.crowd=../avatars/$CROWD"
    [ -n "${EXTRA_ARGS:-}" ] && args="$args $EXTRA_ARGS"
    [ -n "${CROWD_NOAVATARS:-}" ] && args="$args -Dfigura.benchmark.crowdAvatars=false"
    [ -n "${JIT:-}" ] && args="$args -Dfigura.luaJit=$([ "$JIT" = 0 ] && echo false || echo true)"
    echo "=== $label ($args)"
    extra=""; [ -n "${BHOPT:-}" ] && extra="-Pbhopt=$BHOPT"
    [ -n "${ACCEL:-}" ] && extra="$extra -Paccel=$ACCEL"
    ./gradlew :forge:runClient -Pbenchmark $extra "-PbenchmarkArgs=$args" --console=plain > "benchmark/results/$label-gradle.log" 2>&1
    echo "=== $label exit $?"
    grep -a "\[benchmark\] $label |" "benchmark/results/$label-gradle.log" || echo "=== $label produced no result, see benchmark/results/$label-gradle.log"
done
