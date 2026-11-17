#!/usr/bin/env bash
#
# Runs the user benchmark (docs/benchmark-user.md). Writes <out>/summary.md, and the files of each
# run in <out>/runs/.
#
#   scripts/bench-user.sh                           # the CPUs of an AMD Ryzen 9 9950X
#   scripts/bench-user.sh --users-per-group 8000    # the small case only
#
# Prerequisites: the Loom JDK selected (`sdk env`) and the project built. On Linux, it first runs
# scripts/lib/linux-setup.sh, which needs sudo.

set -euo pipefail

source "$(dirname "$0")/lib/bench-common.sh"

BENCH=user
WARMUP=10
HEAP_NOTE="2g, G1"
DEPTH=256
DEPTH0=1
USERS_PER_GROUP=8000,200000

usage() {
	echo "Usage: scripts/bench-user.sh [options]"
	echo
	common_usage
	cat <<'USAGE'
The user benchmark has cases (a size of the groups), and each measure also runs at depth 0,
then makes a walk run (perf record, UserGroupActor.walk out of line) and an epsilon run (no GC). Its
warm-up is 10 seconds.

  --depth <n>              Users read per request. Default: 256.
  --no-depth0              No run at depth 0: each measure at --depth only.
  --users-per-group <list> The cases: users per actor group, ~82 bytes each, comma-separated.
                           8000 is named small, 200000 big. Default: 8000,200000.

One case only, with the options above:

  --users-per-group 8000                     only small
  --users-per-group 200000                   only big
  --users-per-group 200000 --ramps-only      only the ramps of big
  --users-per-group 8000 --step 0            only the measures of small, with the perf runs

Publishing one case replaces both cases of the published user benchmark.
USAGE
}

bench_flag() {
	case $1 in
		--no-depth0) DEPTH0=0 ;;
		*) return 1 ;;
	esac
}

bench_option() {
	case $1 in
		--depth) DEPTH=$2 ;;
		--users-per-group) USERS_PER_GROUP=$2 ;;
		*) return 1 ;;
	esac
}

# The JVM of a run: user.Main, at depth <depth>.
bench_args() {
	local s=$1 depth=$2 mode=$3
	main=io.github.pderop.looma.examples.user.Main
	args+=(-Dbench.scheduler="${s/jdk-pinned/jdk}" -Dbench.carriers="${#CARRIER_CPUS[@]}"
		-Dbench.rate="$RATE" -Dbench.clientCpus="$CLIENT_LIST" -Dbench.depth="$depth"
		-Dbench.usersPerGroup="$CASE_USERS" -Dbench.warmupSeconds="$WARMUP" -Dbench.measureSeconds="$MEASURE"
		-Dbench.backendPid="$BACKEND_PID" -Dbench.clkTck="$CLK")
	[ "$mode" != c2c ] || args+=(-Dbench.stackChunks=true)
	[ "$mode" != ramp ] || args+=(-Dbench.step="$STEP" -Dbench.maxRate="$MAX_RATE" -Dbench.stepSeconds="$STEP_SECONDS")
}

parse_options "$@"
for n in "$DEPTH" ${USERS_PER_GROUP//,/ }; do
	[[ $n =~ ^[0-9]+$ ]] || die "not a number: '$n'"
done
((DEPTH > 0)) || die "--depth must be above 0"
# The cases, as <name>:<users per group>.
CASES=()
for n in ${USERS_PER_GROUP//,/ }; do
	case $n in
		8000) CASES+=(small:$n) ;;
		200000) CASES+=(big:$n) ;;
		*) CASES+=(users-$n:$n) ;;
	esac
done
CASE_DEPTH=$DEPTH
[ "$SUMMARY_ONLY" = 0 ] || { resummarize; exit 0; }

setup
BACKEND_MAIN=io.github.pderop.looma.examples.user.BackendServer
BACKEND_ARGS=(-Dbench.backendPort=8081 -Dbench.backendThreads="$BACKEND_THREADS")
header "User benchmark" >"$OUT/summary.md"
cat "$OUT/summary.md"

n=$(((REPS * (1 + DEPTH0) + C2C * 3 + PERF + (STEP > 0) * RAMP_RUNS) * S * ${#CASES[@]}))
echo
echo "user benchmark: about $((n * (WARMUP + MEASURE + 5) / 60 + 1)) minutes, $n runs$([ "$STEP" = 0 ] || echo ", plus the steps of the ramps")"
start_backend
: >"$OUT/rows.md"
for c in "${CASES[@]}"; do
	CASE=${c%%:*}
	CASE_USERS=${c#*:}
	# Round r: the measure of every scheduler, then (round 1 only) the perf runs, then the ramps.
	for ((r = 1; r <= ROUNDS; r++)); do
		((REPS == 0)) || for s in $SCHEDULERS; do
			run "r$r" "$s" "$DEPTH"
			[ "$DEPTH0" = 0 ] || run "r$r" "$s" 0
		done
		if ((r == 1)) && [ "$C2C" = 1 ]; then
			c2c_runs "$DEPTH"
			for s in $SCHEDULERS; do
				run stat "$s" "$DEPTH" stat
			done
			walk_runs "$DEPTH"
		fi
		if ((r == 1)) && [ "$PERF" = 1 ]; then
			for s in $SCHEDULERS; do
				run epsilon "$s" "$DEPTH" epsilon
			done
		fi
		if [ "$STEP" != 0 ]; then
			for s in $SCHEDULERS; do
				run "r$r-ramp" "$s" "$DEPTH" ramp
			done
		fi
	done
done
stop_backend

{
	echo
	echo "## The runs"
	echo
	echo "What the benchmark is: [benchmark-user.md](../../benchmark-user.md)."
	echo
	echo "$RATE req/s. Each group has $CONNECTIONS backend connections. The cases, by users per group:"
	echo
	for c in "${CASES[@]}"; do
		kib=$((${c#*:} * 82 / 1024))
		size="$kib KiB"
		((kib < 10240)) || size="$(((kib + 512) / 1024)) MiB"
		echo "- ${c%%:*}: ${c#*:} users per group, about $size per group."
	done
	echo
	depths="at depth $DEPTH and at depth 0"
	[ "$DEPTH0" = 1 ] || depths="at depth $DEPTH only"
	rounds_lines "$depths" "a run with perf c2c, one with perf stat, one with perf record for the walk, and one with the Epsilon GC, at depth $DEPTH"
	ramp_lines
	echo
	shared_lines
	if [ "$PERF" = 1 ]; then
		echo "- Heap: -Xms2g -Xmx2g -XX:+AlwaysPreTouch, G1, and a GC log per run. The epsilon run:"
		echo "  -XX:+UseEpsilonGC -Xms12g -Xmx12g -XX:+AlwaysPreTouch. Epsilon frees nothing, so no GC runs:"
		echo "  each allocation takes new memory."
		echo "- The c2c, stat and walk runs count during the measure, minus 3 seconds at each end. The c2c run"
		echo "  also counts the live StackChunks (GC.class_histogram), during the warm-up and after the measure."
		echo "  The walk run keeps UserGroupActor.walk out of line. The full report of each c2c run is in"
		echo "  <case>-c2c-<scheduler>.txt, of each walk run in <case>-walk-<scheduler>.txt, the files of every"
		echo "  run in runs/."
	else
		echo "- Heap: -Xms2g -Xmx2g -XX:+AlwaysPreTouch, G1, and a GC log per run. The files of every run"
		echo "  are in runs/."
	fi
	echo
	summary_tables
	[ "$STEP" = 0 ] || ramp_table
} >>"$OUT/summary.md"
rm -f "$OUT/rows.md"
echo "user benchmark: $OUT/summary.md"
