#!/usr/bin/env bash
#
# Runs the WakeBench JMH (netty-loom-scheduler/src/test) twice, once per way of picking the idle
# carrier to wake:
#
#   first    the scheduler as it is in the tree: the lowest-numbered idle carrier first (upstream).
#   random   the scan starts at a random carrier: IdleCarrierTracker from commit 12af517 (#8),
#            compiled on top of the same classes.
#
#   scripts/run-wake-jmh.sh                       # the CPUs of an AMD Ryzen 9 9950X
#   scripts/run-wake-jmh.sh --reps 3              # first, random, first, random, ...
#   scripts/run-wake-jmh.sh -- -p tasks=4         # what follows -- goes to JMH
#
# Prerequisites: Linux, the Loom JDK selected (`sdk env`), the project built (`mvn install`), and
# the git history (for commit 12af517).
#
# Options:
#   --cpus <list>        The CPUs of the JVM, as taskset writes them. The carriers take the first
#                        ones, one each. Default: 0-15.
#   --carriers <n>       Default: 8. With the default CPUs, they are on 0-7: one L3.
#   --jmh-cpu <n>        The CPU of the JMH thread, which asks for each burst. It must be in
#                        --cpus, after the carriers. Default: 8.
#   --reps <n>           Repetitions of the pair. Default: 1 (about 10 minutes).
#   --out <dir>          Default: bench/wake-jmh-<date>-<time>, in the project.
#
# In <out>: <variant>-<rep>.log (the JMH output, with the SHARE and CONCURRENCY lines) and
# <variant>-<rep>.json.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RANDOM_COMMIT=12af517
TRACKER=netty-loom-scheduler/src/main/java/io/netty/loom/scheduler/IdleCarrierTracker.java

CPUS=0-15
CARRIERS=8
JMH_CPU=8
REPS=1
OUT=$ROOT/bench/wake-jmh-$(date +%Y%m%d-%H%M%S)
while [ $# -gt 0 ]; do
	case $1 in
		--cpus) CPUS=$2; shift 2 ;;
		--carriers) CARRIERS=$2; shift 2 ;;
		--jmh-cpu) JMH_CPU=$2; shift 2 ;;
		--reps) REPS=$2; shift 2 ;;
		--out) OUT=$2; shift 2 ;;
		--) shift; break ;;
		-h | --help) sed -n '3,/^$/s/^# \{0,1\}//p' "$0"; exit 0 ;;
		*) echo "unknown option: $1 (see --help)" >&2; exit 1 ;;
	esac
done

source "$ROOT/scripts/loom-jdk.sh"
JAVAC=$(dirname "$LOOM_JAVA")/javac

MODULE=$ROOT/netty-loom-scheduler/target
CP_FILE=$MODULE/test-classpath.txt
[ -f "$CP_FILE" ] || { echo "no $CP_FILE: build the project first (mvn install)" >&2; exit 1; }
CP=$MODULE/test-classes:$MODULE/classes:$(cat "$CP_FILE")

# The random variant: the old IdleCarrierTracker, put before the scheduler classes on the classpath.
RANDOM_DIR=$MODULE/wake-random
rm -rf "$RANDOM_DIR"
mkdir -p "$RANDOM_DIR/src" "$RANDOM_DIR/classes"
git -C "$ROOT" show "$RANDOM_COMMIT:$TRACKER" > "$RANDOM_DIR/src/IdleCarrierTracker.java"
"$JAVAC" --release 28 --enable-preview -nowarn -cp "$CP" -d "$RANDOM_DIR/classes" \
	"$RANDOM_DIR/src/IdleCarrierTracker.java"

JVM_ARGS="--enable-preview --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED
 -Djdk.traceVirtualThreadLocals=false -Xms1g -Xmx1g
 -Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler
 -Dio.netty.loom.schedulers=$CARRIERS -Dio.netty.loom.workstealing.enabled=true
 -Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology"

mkdir -p "$OUT"
for rep in $(seq "$REPS"); do
	for variant in first random; do
		case $variant in
			first) VCP=$CP ;;
			random) VCP=$RANDOM_DIR/classes:$CP ;;
		esac
		echo "=== $variant, rep $rep ($(date +%T))"
		# The forked JVMs keep the CPUs of this one.
		taskset -c "$CPUS" "$LOOM_JAVA" --enable-preview -cp "$VCP" org.openjdk.jmh.Main WakeBench \
			-jvmArgsAppend "$(echo $JVM_ARGS)" -p jmhCpu="$JMH_CPU" \
			-rf json -rff "$OUT/$variant-$rep.json" "$@" > "$OUT/$variant-$rep.log" 2>&1
		grep -E '^(Benchmark +\(|SHARE|WakeBench)' "$OUT/$variant-$rep.log"
	done
done
echo "results in $OUT"
