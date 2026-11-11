#!/usr/bin/env bash
#
# Runs the timer benchmark, open loop: driver actors arm delayed messages at a fixed rate, with the
# timer wheel of Looma and with a ScheduledThreadPoolExecutor. A second scenario arms a guard
# timer and cancels it at once. Writes <out>/summary.md.
#
#   scripts/bench-timer.sh                       # 2 rates, wheel and jdk, then the guards
#   scripts/bench-timer.sh --rates 100000 --measure 10
#
# Prerequisites: the Loom JDK selected (`sdk env`) and the project built. Linux, perf, taskset and
# sudo: it first runs scripts/lib/linux-setup.sh, and perf reads the thread counts as root.

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: scripts/bench-timer.sh [options]

Each run is one JVM, pinned with taskset. It warms up, then measures. perf counts the threads of
the JVM during the measure, minus 2 s at each end. The benchmark is open loop: every millisecond
a driver thread tells one driver actor per carrier how many timers to arm, at the rate asked,
whatever happens to them. Each driver actor arms its timers while it handles that message, on
its carrier. The runs, for each rate: the wheel with each tick, then the executor of the JDK.
Then the guard scenario (arm a 30 s timer, cancel it at once): the wheel with the tick of
--sentinel-tick, then the executor, at the rate of --sentinel.

Default duration: 8 runs of about 30 s each, so about 4 minutes.

  --rates <list>    Timers per second, all carriers together, comma-separated.
                    Default: 100000,1000000.
  --delay <ms>      The delay of every timer. Default: 10.
  --ticks <list>    The ticks of the wheel, in ms, comma-separated. Default: 1,10.
  --sentinel <rate> Timers per second of the guard scenario, 0 to skip it. Default: 1000000.
  --sentinel-tick <ms>  The tick of the wheel in the guard scenario. Default: 10.
  --cpus <list>     The CPUs of the JVM, one carrier per CPU. Default: 2-7,10-15.
  --warmup <s>      Default: 10.
  --measure <s>     Default: 15. At least 6.
  --out <dir>       Default: bench/timer-<date>-<time>, in the project.
USAGE
}

die() {
	echo "$(basename "$0"): $*" >&2
	exit 1
}

RATES=100000,1000000
DELAY=10
TICKS=1,10
SENTINEL=1000000
SENTINEL_TICK=10
CPU_LIST=2-7,10-15
WARMUP=10
MEASURE=15
OUT=

while [ $# -gt 0 ]; do
	case $1 in -h | --help) usage; exit 0 ;; esac
	[ $# -ge 2 ] || die "$1 needs a value"
	case $1 in
		--rates) RATES=$2 ;;
		--delay) DELAY=$2 ;;
		--ticks) TICKS=$2 ;;
		--sentinel) SENTINEL=$2 ;;
		--sentinel-tick) SENTINEL_TICK=$2 ;;
		--cpus) CPU_LIST=$2 ;;
		--warmup) WARMUP=$2 ;;
		--measure) MEASURE=$2 ;;
		--out) OUT=$2 ;;
		*) die "unknown option '$1' (try --help)" ;;
	esac
	shift 2
done
[ "$MEASURE" -ge 6 ] || die "--measure must be at least 6"

[ "$(uname -s)" = Linux ] || die "Linux only: it needs perf and taskset"
command -v perf >/dev/null || die "perf is needed"

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
scripts/lib/linux-setup.sh >/dev/null || die "scripts/lib/linux-setup.sh failed: run it by hand to see why"
source scripts/lib/loom-jdk.sh
JAVA=$LOOM_JAVA
CP=${CP:-$(scripts/lib/classpath.sh)}
OUT=${OUT:-$ROOT/bench/timer-$(date +%Y%m%d-%H%M%S)}
[ ! -e "$OUT" ] || die "$OUT already exists"
RUNS=$OUT/runs
mkdir -p "$RUNS"

CARRIERS=$(taskset -c "$CPU_LIST" nproc)
WINDOW=$((MEASURE - 4))
EVENTS=task-clock,context-switches
MAIN=io.github.pderop.looma.examples.timer.TimerBench

trap 'kill $(jobs -p) 2>/dev/null || true' EXIT
trap 'exit 130' INT TERM

# The perf file of a run, summed over all threads, then over the timer threads:
# "context switches, unparker cpu ms, carriers cpu ms, executor thread cpu ms".
# Fields: thread (name-tid), count, unit, event. The unparker threads are the JVM's
# "VirtualThread-unparker" threads, the ones that wake the wheel thread at each tick. perf shows
# them as Virtual..parker-<tid>. The Virtual..locker threads are not counted.
# The carriers are the mpsc-carrier-<id> threads. perf keeps 15 characters of a name and puts ".." in the middle of long ones.
perf_counts() {
	awk -F, '$2 ~ /^[0-9.]+$/ {
			if ($4 == "context-switches") cs += $2
			if ($4 == "task-clock" && $1 ~ /^Virtual\.\.parker-/) unparker += $2
			if ($4 == "task-clock" && $1 ~ /^mpsc-carrier/) carriers += $2
			if ($4 == "task-clock" && $1 ~ /^timer-stpe/) stpe += $2
		}
		END { printf "%d %.0f %.0f %.0f\n", cs, unparker, carriers, stpe }' "$1"
}

{
	echo "# Timer benchmark"
	echo
	echo '```'
	printf 'date         %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
	printf 'host         %s, %s %s\n' "$(hostname)" "$(uname -s)" "$(uname -r)"
	printf 'cpu model    %s\n' "$(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1)"
	printf 'java         %s\n' "$("$JAVA" -version 2>&1 | sed -n 2p)"
	printf 'jvm          %d carriers, on CPUs %s\n' "$CARRIERS" "$CPU_LIST"
	printf 'timers       delay %s ms\n' "$DELAY"
	printf 'window       %ds warm-up, %ds measured; perf counts %ds of it\n' "$WARMUP" "$MEASURE" "$WINDOW"
	scripts/lib/linux-setup.sh --check 2>&1 | sed -n '2,6p'
	echo '```'
	echo
} >"$OUT/summary.md"

ROWS=()
RAW=()
GUARD_RUNS=()
declare -A CPU_PER_TIMER

# run_case <scenario> <mode> <tick, or - for jdk> <rate>: one JVM under perf. Adds a row to ROWS.
# Keeps its cpu us/timer in CPU_PER_TIMER, and for the guard scenario its name in GUARD_RUNS.
run_case() {
	local scenario=$1 mode=$2 tick=$3 rate=$4
	local name=$scenario-$mode-tick$tick-$rate
	echo "== $name"
	taskset -c "$CPU_LIST" "$JAVA" --enable-native-access=ALL-UNNAMED -Xms2g -Xmx2g -XX:+AlwaysPreTouch \
		-Dtimer.mode="$mode" -Dtimer.scenario="$scenario" -Dtimer.rate="$rate" -Dtimer.delayMillis="$DELAY" \
		-Dtimer.tickMillis="$([ "$tick" = - ] && echo 1 || echo "$tick")" -Dtimer.carriers="$CARRIERS" \
		-Dtimer.warmupSeconds="$WARMUP" -Dtimer.measureSeconds="$MEASURE" \
		-cp "$CP" $MAIN >"$RUNS/$name.out" 2>&1 &
	local pid=$!
	until grep -qs '^measure ' "$RUNS/$name.out"; do
		kill -0 "$pid" 2>/dev/null || die "$name: the JVM stopped: $(cat "$RUNS/$name.out")"
		sleep 0.1
	done
	sleep 2
	sudo perf stat --per-thread -x, -e "$EVENTS" -p "$pid" -- sleep $WINDOW 2>"$RUNS/$name-stat.csv" || true
	wait "$pid" || die "$name: the JVM failed: $(cat "$RUNS/$name.out")"

	local cs unparker_ms carriers_ms stpe_ms
	read -r cs unparker_ms carriers_ms stpe_ms < <(perf_counts "$RUNS/$name-stat.csv")
	# The console lines: "asked N timers/s (T in S s)", "armed ...", "received ...", "missed N of M
	# driver slots (P %)", "cpu C us/timer", "late p50 ...". Numbers have thousands commas.
	ROWS+=("$(awk -v scenario="$scenario" -v mode="$mode" -v tick="$tick" -v cs="$cs" \
		-v u="$unparker_ms" -v c="$carriers_ms" -v s="$stpe_ms" \
		'{ gsub(/,/, "") }
		/^asked/ { asked = $2 } /^armed/ { armed = $2 } /^received/ { got = $2 }
		/^missed/ { missed = $2; slots = $4 }
		/^cpu +[0-9]/ { cpu = $2 } /^late/ { p50 = $3; p99 = $6; p999 = $9 }
		/^\*\*\* did not keep up/ { flag = "NO" }
		END { if (got == "") got = "-"; if (p50 == "") { p50 = "-"; p99 = "-"; p999 = "-" }
			printf "| %s | %s | %s | %d | %d | %s | %s of %s | %s | %s | %s | %s | %s | %d | %d | %d | %d |", scenario, mode, tick, asked, armed, got, missed, slots, cpu, p50, p99, p999, (flag == "NO" ? "NO" : "yes"), cs, u, c, s }' \
		"$RUNS/$name.out")")
	RAW+=("| $name | $(awk '/^armed/ { gsub(/[(,]/, "", $4); print $4 }' "$RUNS/$name.out") | $(awk '/^received/ { gsub(/[(,]/, "", $4); print $4 }' "$RUNS/$name.out") | $cs | $unparker_ms | $carriers_ms | $stpe_ms |")
	CPU_PER_TIMER[$name]=$(awk '/^cpu +[0-9]/ { print $2 }' "$RUNS/$name.out")
	if [ "$scenario" = sentinel ]; then
		GUARD_RUNS+=("$mode $tick $rate")
	fi
	cat "$RUNS/$name.out"
}

for rate in ${RATES//,/ }; do
	for tick in ${TICKS//,/ }; do run_case expire wheel "$tick" "$rate"; done
	run_case expire jdk - "$rate"
done
if [ "$SENTINEL" -gt 0 ]; then
	run_case sentinel wheel "$SENTINEL_TICK" "$SENTINEL"
	run_case sentinel jdk - "$SENTINEL"
fi

# One row per guard run. cpu us per guard: cpu us/timer of the guard run.
GUARD_ROWS=()
for entry in "${GUARD_RUNS[@]}"; do
	read -r mode tick rate <<<"$entry"
	name=sentinel-$mode-tick$tick-$rate
	cpu=${CPU_PER_TIMER[$name]:--}
	GUARD_ROWS+=("$(awk -v mode="$mode" -v tick="$tick" -v rate="$rate" -v cpu="$cpu" \
		'{ gsub(/,/, "") } /^armed/ { g = $2; n = $4; gsub(/\(/, "", n) } /^heap/ { h = $2 } /^timeouts/ { t = $2 }
		END { printf "| %s | %s | %d | %s | %s | %s | %s | %s |", mode, tick, rate, g, n, cpu, h, t }' \
		"$RUNS/$name.out")")
done

{
	echo "## Results"
	echo
	echo "The benchmark is open loop. A driver thread works on a fixed schedule, one slot every millisecond:"
	echo "it tells one driver actor per carrier how many timers to arm, at the rate asked. Each driver actor"
	echo "arms its timers while it handles that message, on its carrier. A late timer does not slow the next"
	echo "ones, so the rate asked is the same in every mode. rate asked, armed/s, received/s, missed driver"
	echo "slots, cpu and lateness: the console of the run, runs/<run>.out. armed/s: timers armed per second"
	echo "in the measure. received/s: timers that arrived (expire scenario only; 0 for the guards, which are"
	echo "cancelled). missed driver slots: slots that started a whole slot late or more, out of all slots."
	echo "kept up: NO when received is under 99% of the rate asked (armed for the guards) or missed slots"
	echo "are over 1%. cpu: the CPU time of the whole process over the measure, divided by the timers armed"
	echo "in it. This is the main number. Lateness: from the time a timer was due to the time its target got"
	echo "the message, in us. Context switches and CPU ms: runs/<run>-stat.csv, perf stat --per-thread over"
	echo "$WINDOW s of the measure, summed over the threads named in the column."
	echo
	echo "- unparker ms: the VirtualThread-unparker threads of the JVM. They wake the wheel thread at each"
	echo "  tick. About 0 in mode jdk: it is a control."
	echo "- carriers ms: the mpsc-carrier threads. The wheel runs on them, so its own CPU is inside this"
	echo "  number and cannot be split out."
	echo "- executor ms: the timer-stpe thread of mode jdk."
	echo "- tick: - in mode jdk, there is no wheel."
	echo
	echo "| scenario | mode | tick ms | rate asked | armed/s | received/s | missed driver slots | cpu us/timer | p50 us | p99 us | p99.9 us | kept up | context switches | unparker ms | carriers ms | executor ms |"
	echo "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
	printf '%s\n' "${ROWS[@]}"
	echo
	if [ ${#GUARD_ROWS[@]} -gt 0 ]; then
		echo "## Guards armed and cancelled at once"
		echo
		echo "Each timer is a guard due in 30 s, cancelled at once. From the"
		echo "console of the run. cpu us per guard: the cpu us/timer of this guard run, so the wheel and jdk"
		echo "rows compare side by side. heap MiB: used heap (total minus free memory) after System.gc(), at the end of"
		echo "the measure. timeouts: guards that arrived, must be 0. In mode jdk the executor removes a"
		echo "cancelled task at once (setRemoveOnCancelPolicy(true))."
		echo
		echo "| mode | tick ms | rate asked | guards/s | guards in the measure | cpu us per guard | heap MiB after GC | timeouts |"
		echo "|---|---|---|---|---|---|---|---|"
		printf '%s\n' "${GUARD_ROWS[@]}"
		echo
	fi
	echo "## Raw counts"
	echo
	echo "| run | armed in the measure | received in the measure | context switches | unparker ms | carriers ms | executor ms |"
	echo "|---|---|---|---|---|---|---|"
	printf '%s\n' "${RAW[@]}"
} >>"$OUT/summary.md"

echo
cat "$OUT/summary.md"
