#!/usr/bin/env bash
#
# Runs the servlet benchmark (docs/benchmark-servlet.md). Writes <out>/summary.md, and the files of
# each run in <out>/runs/.
#
#   scripts/bench-servlet.sh                        # the CPUs of an AMD Ryzen 9 9950X
#   scripts/bench-servlet.sh --step 10000           # smaller steps in the ramp
#   scripts/bench-servlet.sh --hot-carrier 50       # a skewed load: the actors that start on carrier 0 get half the requests
#
# Prerequisites: the Loom JDK selected (`sdk env`) and the project built. On Linux, it first runs
# scripts/lib/linux-setup.sh, which needs sudo.

set -euo pipefail

source "$(dirname "$0")/lib/bench-common.sh"

BENCH=servlet
WARMUP=10
HEAP_NOTE="2g, G1; the backend 1g"
SESSIONS=100000
PRODUCTS=10000
CART_MAX=20
RECENT_MAX=20
BACKEND_WARMUP=30
CONTAINERS=

usage() {
	echo "Usage: scripts/bench-servlet.sh [options]"
	echo
	common_usage
	cat <<'USAGE'
The servlet benchmark first warms its backend with a throwaway JVM. Its warm-up is 10 seconds, the
rate going up from a tenth of --rate.

  --containers <n>         The containers, a multiple of the carriers: container i starts on carrier
                           i % carriers. Default: one per carrier.
  --sessions <n>           Session ids the requests pick from. Default: 100000.
  --products <n>           Products the requests pick from. Default: 10000.
  --cart-max <n>           Lines a cart keeps. Default: 20.
  --recent-max <n>         Viewed products a profile keeps. Default: 20.
  --backend-warmup <s>     Once, first: a throwaway JVM sends --rate to the backend for that long.
                           Default: 30.
USAGE
}

bench_flag() {
	return 1
}

bench_option() {
	case $1 in
		--containers) CONTAINERS=$2 ;;
		--sessions) SESSIONS=$2 ;;
		--products) PRODUCTS=$2 ;;
		--cart-max) CART_MAX=$2 ;;
		--recent-max) RECENT_MAX=$2 ;;
		--backend-warmup) BACKEND_WARMUP=$2 ;;
		*) return 1 ;;
	esac
}

# The JVM of a run: servlet.Main.
bench_args() {
	local s=$1 mode=$3
	main=io.github.pderop.looma.examples.servlet.Main
	args+=(-Dservlet.scheduler="${s/jdk-pinned/jdk}" -Dservlet.carriers="${#CARRIER_CPUS[@]}"
		-Dservlet.containers="$CONTAINERS"
		-Dservlet.rate="$RATE" -Dservlet.clientCpus="$CLIENT_LIST" -Dservlet.backendPort=8081
		-Dservlet.backendPid="$BACKEND_PID"
		-Dservlet.clkTck="$CLK" -Dservlet.sessions="$SESSIONS" -Dservlet.products="$PRODUCTS"
		-Dservlet.cartMax="$CART_MAX" -Dservlet.recentMax="$RECENT_MAX" -Dservlet.warmupSeconds="$WARMUP"
		-Dservlet.measureSeconds="$MEASURE" -Dservlet.settleSeconds="$SETTLE_SECONDS"
		-Dservlet.hotCarrier="$HOT_CARRIER")
	[ "$mode" != ramp ] ||
		args+=(-Dservlet.step="$STEP" -Dservlet.maxRate="$MAX_RATE" -Dservlet.stepSeconds="$STEP_SECONDS")
	# The warm-up of the backend: a ramp of one step at the rate, as long as asked.
	[ "$mode" != backend-warmup ] ||
		args+=(-Dservlet.step=1 -Dservlet.maxRate="$RATE" -Dservlet.stepSeconds="$BACKEND_WARMUP")
}

parse_options "$@"
for n in "$SESSIONS" "$PRODUCTS" "$CART_MAX" "$RECENT_MAX" "$BACKEND_WARMUP"; do
	[[ $n =~ ^[0-9]+$ ]] || die "not a number: '$n'"
done
((SESSIONS > 0 && PRODUCTS > 0 && CART_MAX > 0 && RECENT_MAX > 0 && BACKEND_WARMUP > 0)) ||
	die "--sessions, --products, --cart-max, --recent-max and --backend-warmup must be above 0"
CASE=servlet
CASES=(servlet:0)
CASE_DEPTH=
[ "$SUMMARY_ONLY" = 0 ] || { resummarize; exit 0; }

# Called by setup, before the output folder exists.
bench_checks() {
	((${#CARRIER_CPUS[@]} >= 2)) || die "the servlet benchmark needs at least 2 carrier CPUs: its container pool has a selector"
	CONTAINERS=${CONTAINERS:-${#CARRIER_CPUS[@]}}
	[[ $CONTAINERS =~ ^[0-9]+$ ]] && ((10#$CONTAINERS > 0 && 10#$CONTAINERS % ${#CARRIER_CPUS[@]} == 0)) ||
		die "--containers must be a multiple of the carriers (${#CARRIER_CPUS[@]}), got '$CONTAINERS'"
	CONTAINERS=$((10#$CONTAINERS))
	((HOT_CARRIER == 0 || SESSIONS >= CONTAINERS)) || die "--hot-carrier needs at least one session per container: --sessions"
}
setup
BACKEND_MAIN=io.github.pderop.looma.examples.servlet.ServletBackend
BACKEND_ARGS=(-Xms1g -Xmx1g -XX:+AlwaysPreTouch -Xlog:gc:file="$RUNS/backend.gc.log"
	-Dservlet.backendPort=8081 -Dservlet.backendThreads="$BACKEND_THREADS")
header "Servlet benchmark" >"$OUT/summary.md"
cat "$OUT/summary.md"

n=$(((REPS + C2C * 2 + (STEP > 0) * RAMP_RUNS) * S + 1))
echo
echo "servlet benchmark: about $(((n * (WARMUP + MEASURE + 5) + BACKEND_WARMUP) / 60 + 1)) minutes, $n runs$([ "$STEP" = 0 ] || echo ", plus the steps of the ramps")"
start_backend
: >"$OUT/rows.md"
# A throwaway JVM warms the backend first. Its results are thrown away.
run backend-warmup "${SCHEDULERS%% *}" "" backend-warmup
# Round r: the measure of every scheduler, then (round 1 only) the perf runs, then the ramps.
for ((r = 1; r <= ROUNDS; r++)); do
	((REPS == 0)) || for s in $SCHEDULERS; do
		run "r$r" "$s" ""
	done
	if ((r == 1)) && [ "$C2C" = 1 ]; then
		c2c_runs ""
		for s in $SCHEDULERS; do
			run stat "$s" "" stat
		done
	fi
	if [ "$STEP" != 0 ]; then
		for s in $SCHEDULERS; do
			run "r$r-ramp" "$s" "" ramp
		done
	fi
done
stop_backend

{
	echo
	echo "## The runs"
	echo
	echo "What the benchmark is: [benchmark-servlet.md](../../benchmark-servlet.md)."
	echo
	echo "$RATE req/s. $SESSIONS sessions and $PRODUCTS products; a cart keeps up to $CART_MAX lines, a profile"
	echo "up to $RECENT_MAX viewed products. Each request makes two backend calls: its fraud check, then its servlet's."
	((CONTAINERS == ${#CARRIER_CPUS[@]})) ||
		echo "$CONTAINERS containers, $((CONTAINERS / ${#CARRIER_CPUS[@]})) per carrier: container i starts on carrier i % ${#CARRIER_CPUS[@]}."
	skew_lines containers "$CONTAINERS"
	echo "Each container has $CONNECTIONS backend connections, shared by its two servlets and its fraud detector: at most $((CONTAINERS * CONNECTIONS)) backend calls at once. The backend is one JVM for all the runs, with $BACKEND_THREADS reactors,"
	echo "warmed first by a throwaway JVM at $RATE req/s for ${BACKEND_WARMUP} seconds (runs/servlet-backend-warmup-*.out)."
	echo
	rounds_lines "" "a run with perf c2c and one with perf stat" "the rate going up from a tenth of $RATE req/s"
	ramp_lines
	echo
	shared_lines
	echo "- Heap: the server -Xms2g -Xmx2g, the backend -Xms1g -Xmx1g, both -XX:+AlwaysPreTouch and G1. A GC"
	echo "  log per JVM, in runs/."
	if [ "$PERF" = 1 ]; then
		echo "- The c2c and stat runs count during the measure, minus 3 seconds at each end. The full report"
		echo "  of each c2c run is in servlet-c2c-<scheduler>.txt, the files of every run in runs/."
	fi
	echo
	summary_tables
	[ "$STEP" = 0 ] || ramp_table
} >>"$OUT/summary.md"
rm -f "$OUT/rows.md"
echo "servlet benchmark: $OUT/summary.md"
