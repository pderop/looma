# The code that bench-user.sh and bench-servlet.sh share. Sourced by them; not meant to be run
# on its own.
#
# A benchmark script sets BENCH, WARMUP and HEAP_NOTE, and defines:
#   usage                       its --help; common_usage prints the shared part
#   bench_flag <opt>            its options without a value; returns 1 for an unknown one
#   bench_option <opt> <value>  its options with a value; returns 1 for an unknown one
#   bench_args <scheduler> <depth> <mode>
#                               sets main, the class of a run, and adds to args, its JVM options
# and sets BACKEND_MAIN and BACKEND_ARGS before start_backend. Then it calls parse_options "$@"
# and setup, and runs its runs with run.

die() {
	echo "$(basename "$0"): $*" >&2
	exit 1
}

# declare -gA and empty arrays under set -u need bash 4.4. macOS ships bash 3.2: brew install bash.
((BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 4))) ||
	die "needs bash 4.4 or newer, this is $BASH_VERSION (on macOS: brew install bash)"

common_usage() {
	cat <<'USAGE'
Each run is one JVM, with one scheduler. For each scheduler:

  r1 to r<reps>  a measure at --rate: a warm-up, then 20 seconds measured
  c2c, stat      the same, with perf c2c, then with perf stat (on Linux, the script needs perf)
  ramp1 to ramp<ramp-runs>
                 after the warm-up, the rate starts at --rate and goes up by --step at each step,
                 each step 10 seconds measured, until a step is not kept up with

All runs use the same jar and the same JVM flags: only the scheduler changes.

The default CPUs are those of an AMD Ryzen 9 9950X. On another machine, give the three lists,
with commas and ranges only (2-7,10-13), and no CPU in two lists:

  --cpus-carriers <list>   One carrier per CPU, one CPU per core. Default: 2-7,10-13.
  --cpus-client <list>     The load generator: one connection thread per CPU. Default: 0,1,16,17.
  --cpus-backend <list>    The backend. Default: 8,9,14,15,24,25,30,31.
  --reactors <n>           The backend's reactor threads, on those CPUs. Default: one per CPU.
  --connections <n>        The size of a pool of backend connections: one pool per group of the
                           user benchmark, one per container of the servlet benchmark. Default: 8.
  --rate <n>               Requests per second in all. Default: 150000.
  --step <n>               The ramp: requests per second added at each step, 0 for no ramp.
                           Default: 20000.
  --max-rate <n>           The ramp stops after this rate, 0 for no limit. Default: 0.
  --ramp-runs <n>          Ramp runs per scheduler, each in a JVM of its own. Default: 2.
  --reps <n>               Repetitions. Default: 3.
  --no-perf                No c2c, stat, walk or epsilon run: only r1 to r<reps>, and the ramps.
  --schedulers <list>      The schedulers to run, comma-separated, among mpsc, jdk,
                           jdk-pinned, mpsc-unpinned, mpsc-no-affinity and
                           mpsc-unpinned-no-affinity. Default: all six.
  --out <dir>              Default: bench/<bench>-<date>-<time>, in the project.
  --summary-only           Runs nothing: writes again the tables of <out>/summary.md, from the
                           files of its runs. Give --out and the options of the campaign.

USAGE
}

CARRIER_LIST=2-7,10-13
CLIENT_LIST=0,1,16,17
BACKEND_LIST=8,9,14,15,24,25,30,31
RATE=150000
STEP=20000
MAX_RATE=0
RAMP_RUNS=2
PERF=1
REPS=3
OUT=
SUMMARY_ONLY=0
MEASURE=20
STEP_SECONDS=10
# A ramp also gives the last step with a p99 under this, in µs: a rate with an acceptable latency.
P99_LIMIT=1000
SETTLE_SECONDS=3
CONNECTIONS=8
BACKEND_THREADS=
SCHEDULERS="mpsc mpsc-unpinned mpsc-no-affinity mpsc-unpinned-no-affinity jdk jdk-pinned"
KNOWN_SCHEDULERS=$SCHEDULERS

# parse_options "$@": the shared options, then those of the benchmark, through bench_flag and
# bench_option.
parse_options() {
	COMMAND_LINE="$0 $*"
	while [ $# -gt 0 ]; do
		case $1 in
			-h | --help) usage; exit 0 ;;
			--no-perf) PERF=0; shift; continue ;;
			--summary-only) SUMMARY_ONLY=1; shift; continue ;;
		esac
		! bench_flag "$1" || { shift; continue; }
		[ $# -ge 2 ] || die "$1: unknown option, or it needs a value (try --help)"
		case $1 in
			--cpus-carriers) CARRIER_LIST=$2 ;;
			--cpus-client) CLIENT_LIST=$2 ;;
			--cpus-backend) BACKEND_LIST=$2 ;;
			--reactors) BACKEND_THREADS=$2 ;;
			--rate) RATE=$2 ;;
			--step) STEP=$2 ;;
			--max-rate) MAX_RATE=$2 ;;
			--ramp-runs) RAMP_RUNS=$2 ;;
			--reps) REPS=$2 ;;
			--schedulers) SCHEDULERS=${2//,/ } ;;
			--out) OUT=$2 ;;
			--connections) CONNECTIONS=$2 ;;
			*) bench_option "$1" "$2" || die "unknown option '$1' (try --help)" ;;
		esac
		shift 2
	done

	[ -n "${SCHEDULERS// /}" ] || die "--schedulers is empty"
	for s in $SCHEDULERS; do
		[[ " $KNOWN_SCHEDULERS " == *" $s "* ]] || die "unknown scheduler '$s' (try --help)"
	done
	for n in "$RATE" "$STEP" "$MAX_RATE" "$RAMP_RUNS" "$REPS" "$CONNECTIONS"; do
		[[ $n =~ ^[0-9]+$ ]] || die "not a number: '$n'"
	done
	[[ -z $BACKEND_THREADS || $BACKEND_THREADS =~ ^[1-9][0-9]*$ ]] || die "--reactors needs a number above 0, not '$BACKEND_THREADS'"
	((RATE > 0 && REPS > 0 && CONNECTIONS > 0)) || die "--rate, --reps and --connections must be above 0"
	((STEP == 0 || RAMP_RUNS > 0)) || die "--ramp-runs must be above 0, or --step 0 for no ramp"
}

# "2-7,10" -> one CPU per line.
expand_cpus() {
	local part IFS=,
	for part in $1; do
		case $part in
			*-*) seq "${part%-*}" "${part#*-}" ;;
			*) echo "$part" ;;
		esac
	done
}

# setup: checks the CPU lists, prepares the machine, finds the JDK, makes the output directory
# $OUT and its runs/ directory $RUNS.
setup() {
	# The three CPU lists: commas and ranges only, and no CPU in two lists.
	declare -gA LIST_OF=()
	local opt name list part cpus cpu topo core
	for opt in carriers:$CARRIER_LIST client:$CLIENT_LIST backend:$BACKEND_LIST; do
		name=${opt%%:*} list=${opt#*:}
		[[ $list =~ ^[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$ ]] ||
			die "--cpus-$name '$list': give CPUs with commas and ranges only, as in 2-7,10-13"
		for part in ${list//,/ }; do
			[[ $part != *-* ]] || ((${part%-*} <= ${part#*-})) || die "--cpus-$name '$list': the range $part goes down"
		done
		cpus=$(expand_cpus "$list")
		for cpu in $cpus; do
			[ -z "${LIST_OF[$cpu]:-}" ] || die "CPU $cpu is twice in the lists: --cpus-${LIST_OF[$cpu]} and --cpus-$name"
			LIST_OF[$cpu]=$name
		done
	done
	[ -n "$BACKEND_THREADS" ] || BACKEND_THREADS=$(expand_cpus "$BACKEND_LIST" | wc -l | tr -d ' ')

	ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
	cd "$ROOT"
	# Turbo off, a fixed frequency, perf allowed. Applying it again keeps the saved originals.
	[ "$(uname -s)" != Linux ] || scripts/lib/linux-setup.sh >/dev/null ||
		die "scripts/lib/linux-setup.sh failed: run it by hand to see why"
	source scripts/lib/loom-jdk.sh
	JAVA=$LOOM_JAVA
	JCMD=$(dirname "$JAVA")/jcmd
	CP=${CP:-$(scripts/lib/classpath.sh)}
	OUT=${OUT:-$ROOT/bench/$BENCH-$(date +%Y%m%d-%H%M%S)}
	[ ! -e "$OUT" ] || die "$OUT already exists"
	RUNS=$OUT/runs
	mkdir -p "$RUNS"

	CARRIER_CPUS=($(expand_cpus "$CARRIER_LIST"))

	# Two carriers on the two CPUs of one core would share its L2.
	declare -gA CORE_OF=()
	for cpu in "${CARRIER_CPUS[@]}"; do
		topo=/sys/devices/system/cpu/cpu$cpu/topology
		[ -r "$topo/core_id" ] || continue
		core=$(cat "$topo/physical_package_id"):$(cat "$topo/core_id")
		[ -z "${CORE_OF[$core]:-}" ] || die "carrier CPUs ${CORE_OF[$core]} and $cpu share a core"
		CORE_OF[$core]=$cpu
	done

	if command -v taskset >/dev/null; then
		PIN=(taskset -c "$CARRIER_LIST")
		BACKEND_PIN=(taskset -c "$BACKEND_LIST")
	else
		echo "no taskset: nothing is pinned, a smoke test and not a measurement" >&2
		PIN=()
		BACKEND_PIN=()
	fi
	C2C=0
	if [ "$PERF" = 1 ]; then
		command -v perf >/dev/null && C2C=1
		# On Linux, the c2c, stat and walk runs are part of every measurement: no perf, no run.
		[ "$C2C" = 1 ] || [ "$(uname -s)" != Linux ] || die "perf is needed on Linux (the c2c, stat and walk runs), or --no-perf"
		# Elsewhere, there is no perf: a smoke test, without the c2c, stat and walk runs.
		[ "$C2C" = 1 ] || echo "no perf: no c2c, stat or walk run, a smoke test and not a measurement" >&2
	fi

	# Whatever this script started -- the backend, a benchmark JVM, perf -- dies with it.
	trap 'kill $(jobs -p) 2>/dev/null || true' EXIT
	trap 'exit 130' INT TERM

	CLK=$(getconf CLK_TCK 2>/dev/null || echo 100)
	S=$(wc -w <<<"$SCHEDULERS")
}

turbo() {
	local f
	for f in /sys/devices/system/cpu/intel_pstate/no_turbo /sys/devices/system/cpu/cpufreq/boost; do
		[ -r "$f" ] || continue
		case $f:$(cat "$f") in */no_turbo:1 | */boost:0) echo off ;; *) echo ON ;; esac
		return
	done
	echo unknown
}

# ITMT, the "preferred cores" of the kernel: it may put two busy threads on one CPU. On AMD it is
# amd_pstate's prefcore, on Intel sched_itmt_enabled.
itmt() {
	local f=/sys/devices/system/cpu/amd_pstate/prefcore
	if [ -r "$f" ]; then
		case $(cat "$f") in enabled) echo "yes (amd_pstate prefcore: enabled)" ;; *) echo "no (amd_pstate prefcore: $(cat "$f"))" ;; esac
	elif [ -r /proc/sys/kernel/sched_itmt_enabled ]; then
		case $(cat /proc/sys/kernel/sched_itmt_enabled) in 1) echo "yes (sched_itmt_enabled: 1)" ;; *) echo "no (sched_itmt_enabled: 0)" ;; esac
	else
		echo "unknown: no amd_pstate prefcore, no sched_itmt_enabled"
	fi
}

# header <title>: the title and the machine, at the top of a summary.
header() {
	echo "# $1"
	echo
	echo '```'
	printf 'date         %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
	printf 'host         %s, %s %s\n' "$(hostname)" "$(uname -s)" "$(uname -r)"
	printf 'cpu model    %s\n' "$(lscpu 2>/dev/null | sed -n 's/^Model name:[[:space:]]*//p' | head -1)"
	printf 'turbo        %s\n' "$(turbo)"
	printf 'itmt on      %s\n' "$(itmt)"
	printf 'java         %s\n' "$("$JAVA" -version 2>&1 | sed -n 2p)"
	printf 'carriers     CPUs %s\n' "$CARRIER_LIST"
	printf 'clients      CPUs %s\n' "$CLIENT_LIST"
	printf 'backend      CPUs %s, %s reactors, %s connections per pool\n' "$BACKEND_LIST" "$BACKEND_THREADS" "$CONNECTIONS"
	printf 'heap         %s\n' "$HEAP_NOTE"
	printf 'command      %s\n' "$COMMAND_LINE"
	echo '```'
}

# start_backend: the backend of the benchmark, $BACKEND_MAIN with $BACKEND_ARGS, in a process of its
# own, for all its runs, on port 8081. Its console is $OUT/backend.out.
start_backend() {
	local out=$OUT/backend.out
	(exec 3<>/dev/tcp/127.0.0.1/8081) 2>/dev/null && die "port 8081 is in use: is another backend running?"
	${BACKEND_PIN[@]+"${BACKEND_PIN[@]}"} "$JAVA" "${BACKEND_ARGS[@]}" -cp "$CP" "$BACKEND_MAIN" >"$out" 2>&1 &
	BACKEND_PID=$!
	for _ in $(seq 100); do
		grep -qs 'listening' "$out" && return 0
		kill -0 "$BACKEND_PID" 2>/dev/null || die "the backend did not start: $(cat "$out")"
		sleep 0.2
	done
	die "the backend never said it was listening: see $out"
}

stop_backend() {
	kill "$BACKEND_PID" 2>/dev/null || true
	wait "$BACKEND_PID" 2>/dev/null || true
}

# follow <pid> <file>: prints the lines of <file> as they come, until the process <pid> exits.
# The same on Linux and macOS, where tail has no --pid.
follow() {
	local pid=$1 line part=
	# The process creates the file: it may not be there yet.
	until [ -e "$2" ]; do
		kill -0 "$pid" 2>/dev/null || return 0
		sleep 0.1
	done
	{
		while kill -0 "$pid" 2>/dev/null; do
			if IFS= read -r line; then
				printf '%s\n' "$part$line"
				part=
			else
				# The end of the file for now: keep a line not yet finished, and look again.
				part+=$line
				sleep 0.2
			fi
		done
		# The process is gone: what it wrote last.
		while IFS= read -r line || [ -n "$line" ]; do
			printf '%s\n' "$part$line"
			part=
		done
	} <"$2"
}

# show_steps <pid> <out> <name>: as a ramp goes, one line per step, once its report is out, and the
# last rate kept up with.
show_steps() {
	follow "$1" "$2" | awk -v name="$3" '
		/^step /                { rate = $2; late = 0 }
		/did not keep up|no backend response/ { late = 1 }
		/^$/ && rate != ""      { printf "  %s  %s req/s: %s\n", name, rate, late ? "did not keep up" : "kept up"; rate = ""; fflush() }
		/^ramp +kept up with /  { sub(/^ramp +/, ""); printf "  %s  %s\n", name, $0; fflush() }' || true
}

# jdk-pinned: the builtin's workers, ForkJoinPool-<n>-worker-<k>, each pinned to the k-th carrier
# CPU. Found by name in a thread dump, during the warm-up only: a thread dump stops the JVM. Writes
# how many workers it pinned to <file>. A worker can exit before taskset runs: it is not counted.
# Only workers 1 to the number of carrier CPUs are pinned and counted.
pin_jdk_workers() {
	local pid=$1 file=$2 out=$3 t line pinned=()
	wait_line "$pid" "$out" window || return 0
	for ((t = 1; t < WARMUP; t++)); do
		sleep 1
		while read -r line; do
			# '"ForkJoinPool-1-worker-3" #42 [30559] ...': the OS thread id is in the brackets.
			[[ $line =~ ^\"ForkJoinPool-[0-9]+-worker-([0-9]+)\"\ \#[0-9]+\ \[([0-9]+)\] ]] || continue
			# A worker numbered above the carriers replaces or helps another: left alone.
			((BASH_REMATCH[1] <= ${#CARRIER_CPUS[@]})) || continue
			taskset -p -c "${CARRIER_CPUS[BASH_REMATCH[1] - 1]}" \
				"${BASH_REMATCH[2]}" >/dev/null 2>&1 && pinned[BASH_REMATCH[1]]=1
		done < <("$JCMD" "$pid" Thread.print 2>/dev/null || true)
	done
	echo "pinning      ${#pinned[@]} of ${#CARRIER_CPUS[@]} workers pinned to a carrier CPU" >"$file"
}

# wait_line <pid> <out> <word>: waits for the line of the console <out> that starts with <word>.
# Main writes "window" just before the warm-up, and "measure" when the measure starts. Gives up
# when the JVM <pid> exits first.
wait_line() {
	local pid=$1 out=$2 word=$3
	until grep -qs "^$word " "$out"; do
		kill -0 "$pid" 2>/dev/null || return 1
		sleep 0.1
	done
}

# perf c2c over the measure, minus 3 seconds at each end. Its report waits for the JVM to exit,
# which writes the map of the JIT-compiled code.
c2c() {
	local s=$1 pid=$2 out=$3
	wait_line "$pid" "$out" measure || return 0
	sleep 3
	perf c2c record -F 1000 -p "$pid" -o "$RUNS/$CASE-c2c-$s.data" -- sleep $((MEASURE - 6)) \
		>"$RUNS/$CASE-c2c-$s.record.log" 2>&1 || true
	while kill -0 "$pid" 2>/dev/null; do sleep 1; done
	DEBUGINFOD_URLS= perf c2c report -i "$RUNS/$CASE-c2c-$s.data" --stdio --full-symbols \
		>"$OUT/$CASE-c2c-$s.txt" 2>&1 || true
}

# perf stat over the measure, minus 3 seconds at each end, per thread.
stat() {
	local s=$1 pid=$2 out=$3
	wait_line "$pid" "$out" measure || return 0
	sleep 3
	perf stat --per-thread -x, -p "$pid" -e cycles -e instructions -- sleep $((MEASURE - 6)) \
		2>"$RUNS/$CASE-stat-$s.counts" || true
	{
		echo "# perf stat --per-thread -x, -e cycles -e instructions: the counts of each thread of the JVM,"
		echo "# during the measure, minus 3 s at each end. One line per thread and counter. The fields:"
		echo "#   1. the thread: its name, then its id"
		echo "#   2. the count: a number, of cycles or of instructions as field 4 says"
		echo "#   3. the unit: empty"
		echo "#   4. the name of the counter: the word cycles or the word instructions"
		echo "#   5. how long the thread was counted, in ns"
		echo "#   6. the share of that time the counter really ran, in %"
		echo "#   7. and 8. a metric and its unit: empty here"
		echo "# <not counted>: the thread did not run during the window."
		echo "# summary.md: instructions is the sum of field 2 on the lines where field 4 is instructions,"
		echo "# for every thread but client-* (the load); cycles is the same on the lines where it is cycles."
		cat "$RUNS/$CASE-stat-$s.counts"
	} >"$RUNS/$CASE-stat-$s.csv"
	rm -f "$RUNS/$CASE-stat-$s.counts"
}

# perf record of the cycles over the measure, minus 3 seconds at each end. Its report waits for the
# JVM to exit, which writes the map of the JIT-compiled code. One line per function, with its cycles.
walk_record() {
	local s=$1 pid=$2 out=$3
	wait_line "$pid" "$out" measure || return 0
	sleep 3
	perf record -e cycles -F 1000 -p "$pid" -o "$RUNS/$CASE-walk-$s.data" -- sleep $((MEASURE - 6)) \
		>"$RUNS/$CASE-walk-$s.record.log" 2>&1 || true
	while kill -0 "$pid" 2>/dev/null; do sleep 1; done
	DEBUGINFOD_URLS= perf report -i "$RUNS/$CASE-walk-$s.data" --stdio --sort sym --show-total-period \
		>"$OUT/$CASE-walk-$s.txt" 2>&1 || true
}

# The stat columns of a run, from its perf stat file: "instructions | cycles | requests". The
# counts are summed over the server's threads: the client-* threads are the load, not the server.
# The requests are the rate times the window of perf stat.
stat_counts() {
	awk -F, -v req=$((RATE * (MEASURE - 6))) '$1 !~ /^client-/ && $2 ~ /^[0-9]+$/ { sum[$4] += $2 }
		END {
			if (sum["cycles"] > 0) printf "%.0f | %.0f | %d", sum["instructions"], sum["cycles"], req
			else printf "no count | |"
		}' "$1"
}

# The c2c columns of a run, copied from its report: "Load Operations | Load Remote HITM | Load
# DRAM", the last one being Load Local DRAM plus Load Remote DRAM.
c2c_counts() {
	awk -F: '
		{ gsub(/[ ,]/, "", $2) }
		/^ *Load Operations/ { loads = $2 }
		/^ *Load Remote HITM/ { hitm = $2 }
		/^ *Load Local DRAM|^ *Load Remote DRAM/ { dram += $2 }
		END {
			if (loads > 0) printf "%d | %d | %d", loads, hitm, dram
			else printf "no sample | |"
		}' "$1"
}

# The walk columns of a run, from its perf report: "walk cycles | requests". walk cycles is the
# sum of the column Period on the lines of UserGroupActor.walk. The requests are the rate times the
# window of perf record.
walk_counts() {
	awk -v req=$((RATE * (MEASURE - 6))) '
		/UserGroupActor\.walk\(/ { gsub(/,/, "", $2); if ($2 ~ /^[0-9]+$/) { cycles += $2; found = 1 } }
		END {
			if (found) printf "%.0f | %d", cycles, req
			else printf "no sample of the walk |"
		}' "$1"
}

# The heap used at exit of an epsilon run, in MiB, from its GC log: Epsilon writes the line
# "space 12582912K,  33% used [<start>, <top>, <end>)", and the heap used is top - start.
epsilon_heap() {
	local line start top
	line=$(grep -E 'space [0-9]+K, +[0-9]+% used \[' "$1" | tail -1)
	[ -n "$line" ] || return 0
	start=$(echo "$line" | sed -E 's/.*\[(0x[0-9a-f]+), (0x[0-9a-f]+),.*/\1/')
	top=$(echo "$line" | sed -E 's/.*\[(0x[0-9a-f]+), (0x[0-9a-f]+),.*/\2/')
	echo $(((top - start) / 1048576))
}

# row <case> <label> <scheduler> <depth> [c2c|stat|walk|epsilon|ramp]: the line of one run in rows.md,
# from its files: the console, the GC log, and the perf stat file, the c2c or the walk report. The
# servlet runs have no depth: an empty <depth>.
row() {
	local c=$1 label=$2 s=$3 depth=$4 mode=${5:-} name=$1-$2-$3${4:+-$4} gcs late stats=" | | " c2cs=" | | " heap= walks=" | " fast=
	# The full GCs of the StackChunk counts are not the run's.
	gcs=$(grep Pause "$RUNS/$name.gc.log" | grep -vc 'Heap Inspection' || true)
	late=$(grep -q 'did not keep up\|no backend response\|pinning failed' "$RUNS/$name.out" && echo "not a measurement" || echo)
	[ "$mode" != c2c ] || c2cs=$(c2c_counts "$OUT/$c-c2c-$s.txt")
	[ "$mode" != stat ] || stats=$(stat_counts "$RUNS/$c-stat-$s.csv")
	[ "$mode" != epsilon ] || heap=$(epsilon_heap "$RUNS/$name.gc.log")
	[ "$mode" != walk ] || walks=$(walk_counts "$OUT/$c-walk-$s.txt")
	[ "$mode" != ramp ] || fast=$(fast_rate "$RUNS/$name.out")
	awk -v c="$c" -v s="$s" -v d="$depth" -v r="$label" -v gcs="$gcs" -v late="$late" -v stats="$stats" -v c2cs="$c2cs" \
		-v heap="$heap" -v walks="$walks" -v fast="$fast" '
		/^ramp +kept up with /      { held = $5; gsub(/,/, "", held) }
		/^throughput/  { gsub(/,/, "", $2); rps = $2 }
		/^cpu /        { cpu = $2 }
		/^migrations/  { mig = $2 }
		/^latency/     { p50 = $3; p99 = $7; p999 = $9 }
		/^stackchunks/ { chunks = $2 " → " $7 }
		/^backend cpu/ { bcpu = $3 }
		END { printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n",
			c, s, d, r, rps, cpu, mig, p50, p99, p999, gcs, stats, c2cs, chunks, late, heap, walks, held, bcpu, fast }' "$RUNS/$name.out"
}

# resummarize: --summary-only. Writes again the tables of $OUT/summary.md, from the files of its
# runs: one line per run in rows.md, as run does, then the tables. The header and The runs stay as
# they are. The options must be those of the campaign: the tables use them.
resummarize() {
	local f name rest c s d label mode
	[ -n "$OUT" ] && [ -f "$OUT/summary.md" ] || die "--summary-only needs --out <dir>, the directory of a campaign"
	RUNS=$OUT/runs
	: >"$OUT/rows.md"
	for f in "$RUNS"/*.out; do
		name=$(basename "$f" .out)
		for c in "${CASES[@]}"; do
			c=${c%%:*}
			[[ $name == "$c"-* ]] || continue
			rest=${name#"$c"-} d=
			if [ -n "$CASE_DEPTH" ]; then
				[[ $rest =~ -([0-9]+)$ ]] || continue
				d=${BASH_REMATCH[1]} rest=${rest%-"$d"}
			fi
			for s in $SCHEDULERS; do
				label=${rest%-"$s"}
				[ "$label" != "$rest" ] && [[ $label =~ ^(r[0-9]+|c2c|stat|walk|epsilon|ramp[0-9]+)$ ]] || continue
				case $label in
					r[0-9]*) mode= ;;
					ramp*) mode=ramp ;;
					*) mode=$label ;;
				esac
				row "$c" "$label" "$s" "$d" "$mode" >>"$OUT/rows.md"
			done
		done
	done
	{
		awk '/^## At a glance/ { exit } { print }' "$OUT/summary.md"
		summary_tables
		[ "$STEP" = 0 ] || ramp_table
	} >"$OUT/summary.md.new"
	mv "$OUT/summary.md.new" "$OUT/summary.md"
	rm -f "$OUT/rows.md"
	echo "$BENCH benchmark: $OUT/summary.md"
}

# The sections of the summary after "The runs", from rows.md: perf stat, perf c2c, the walk, the controls,
# and in short the CPU and the latency. One line per case and scheduler. Each section says where
# its numbers come from, and shows the counts that the other columns are computed from.
summary_tables() {
	local names=() c
	for c in "${CASES[@]}"; do names+=("${c%%:*}"); done
	SCHEDULER_LINES=$(scheduler_lines) awk -F'|' -v cases="${names[*]}" -v order="$SCHEDULERS" -v depth="$CASE_DEPTH" -v reps="$REPS" \
		-v rate="$RATE" -v window=$((MEASURE - 6)) -v ramp="$STEP" -v ramps="$RAMP_RUNS" -v perf="$PERF" -v limit="$(p99_limit)" -v user="$([ "$BENCH" = user ] && echo 1 || echo 0)" '
		function trim(v) { gsub(/^ +| +$/, "", v); return v }
		function keep(k, v) {
			if (!(k in lo) || v + 0 < lo[k]) lo[k] = v + 0
			if (!(k in hi) || v + 0 > hi[k]) hi[k] = v + 0
		}
		function range(k, fmt,   a, b) {
			if (fmt == "") fmt = "%.0f"
			a = sprintf(fmt, lo[k]); b = sprintf(fmt, hi[k])
			return a == b ? a : a "–" b
		}
		function share(a, b) { return b > 0 ? sprintf("%.2f%%", a / b * 100) : "" }
		# 193826125512 -> 193,826,125,512
		function group(v,   out) {
			v = sprintf("%.0f", v); out = ""
			while (length(v) > 3) { out = "," substr(v, length(v) - 2) out; v = substr(v, 1, length(v) - 3) }
			return v out
		}
		function grange(k,   a, b) { a = group(lo[k]); b = group(hi[k]); return a == b ? a : a "–" b }
		function mean(k) { return cpu[k] / n[k] }
		function measured(k) { return n[k] > 0 && !(k in late) }
		# The note of a perf run: its own, or that of the runs at the same depth.
		function pnote(cs, own) {
			if (own != "") return "this run: " own
			return measured(cs SUBSEP depth) ? "" : runs ": not a measurement"
		}
		# "| <case> | <scheduler> | <depth> |" for the user benchmark, "| <scheduler> |" for the servlet one.
		function lead(c, s, d) { return user ? sprintf("| %s | %s | %s |", c, s, d) : sprintf("| %s |", s) }
		function plead(c, s) { return user ? sprintf("| %s | %s |", c, s) : sprintf("| %s |", s) }
		{ for (i = 2; i <= 26; i++) f[i] = trim($i); c = f[2]; s = f[3]; d = f[4]; r = f[5]; cs = c SUBSEP s }
		r ~ /^r[0-9]+$/ {
			k = cs SUBSEP d; n[k]++; cpu[k] += f[7]; mig[k] += f[8]
			keep(k SUBSEP "rps", f[6]); keep(k SUBSEP "cpu", f[7]); keep(k SUBSEP "p50", f[9])
			keep(k SUBSEP "p99", f[10]); keep(k SUBSEP "p999", f[11]); keep(k SUBSEP "gcs", f[12])
			if (f[25] != "") keep(k SUBSEP "bcpu", f[25])
			if (f[20] != "") late[k] = 1
		}
		r == "stat" { stat[cs] = 1; ins[cs] = f[13]; cyc[cs] = f[14]; req[cs] = f[15]; snote[cs] = f[20] }
		r == "walk" { wrun[cs] = 1; wcyc[cs] = f[22]; wreq[cs] = f[23]; wnote[cs] = f[20] }
		r == "epsilon" { eps[cs] = 1; ecpu[cs] = f[7]; enote[cs] = f[20]; eheap[cs] = f[21] }
		r ~ /^ramp[0-9]+$/ { held[cs, substr(r, 5) + 0] = f[24] == "" ? "none" : f[24]; fast[cs, substr(r, 5) + 0] = f[26] }
		r == "c2c" {
			c2c[cs] = 1; cmig[cs] = f[8]; loads[cs] = f[16]; hitm[cs] = f[17]; dram[cs] = f[18]
			chunks[cs] = f[19]; cnote[cs] = f[20]
		}
		END {
			nc = split(cases, cas, " "); ns = split(order, sch, " ")
			# With one repetition, a range or a mean is just the value of r1.
			runs = reps == 1 ? "r1" : "r1 to r" reps
			span = reps == 1 ? "the value of r1" : "the lowest and the highest of r1 to r" reps
			avg = reps == 1 ? "the value of r1" : "the mean of r1 to r" reps
			at = user ? " at depth " depth : ""
			# The files of a run: runs/<case>-<run>-<scheduler>-<depth>, or runs/servlet-<run>-<scheduler>.
			file = user ? "runs/<case>-<run>-<scheduler>-<depth>" : "runs/servlet-<run>-<scheduler>"
			pfile = user ? "<case>-%s-<scheduler>" : "servlet-%s-<scheduler>"

			step = "Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from)."

			print "## At a glance"
			print ""
			printf "| scheduler |"
			for (a = 1; a <= nc; a++) printf " %s, cpu µs/req |", user ? cas[a] : "servlet"
			if (ramp > 0) for (a = 1; a <= nc; a++) printf " %s, max req/s |", user ? cas[a] : "servlet"
			if (ramp > 0) for (a = 1; a <= nc; a++) printf " %s, max req/s with p99 < %s |", user ? cas[a] : "servlet", limit
			print ""
			printf "|---|"
			for (a = 1; a <= nc; a++) printf "---|"
			if (ramp > 0) for (a = 1; a <= 2 * nc; a++) printf "---|"
			print ""
			for (i = 1; i <= ns; i++) {
				printf "| %s |", sch[i]
				for (a = 1; a <= nc; a++) {
					k = cas[a] SUBSEP sch[i] SUBSEP depth
					printf " %s |", !n[k] ? "" : measured(k) ? sprintf("%.2f", mean(k)) : "not a measurement"
				}
				# One value per ramp run, in order: ramp1 / ramp2 ...
				if (ramp > 0) for (a = 1; a <= nc; a++) {
					cs = cas[a] SUBSEP sch[i]; v = ""
					for (k = 1; k <= ramps; k++) {
						if (!((cs, k) in held)) continue
						v = v (v == "" ? "" : " / ") (held[cs, k] == "none" ? "none" : group(held[cs, k]))
					}
					printf " %s |", v
				}
				if (ramp > 0) for (a = 1; a <= nc; a++) {
					cs = cas[a] SUBSEP sch[i]; v = ""
					for (k = 1; k <= ramps; k++) {
						if (!((cs, k) in fast)) continue
						v = v (v == "" ? "" : " / ") (fast[cs, k] == "none" ? "none" : group(fast[cs, k]))
					}
					printf " %s |", v
				}
				print ""
			}
			print ""
			print "cpu µs/req" at ", " avg ", as in In short."
			if (ramp > 0) {
				print "max req/s: the highest rate kept up with by each ramp run" at ", ramp1 / ramp2 ...: the line"
				print "ramp kept up with of its console, as in The ramp."
				print "max req/s with p99 < " limit ": a rate with an acceptable latency. In each ramp run, from the first step"
				print "up, the last step kept up with before the first step whose p99 is above " limit ": the lines latency"
				print "of its console, step by step. none: the p99 of the first step is already above."
			}
			print ""
			print ENVIRON["SCHEDULER_LINES"]

			print ""
			print "## In short"
			print ""
			if (user) {
				print "| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations | backend CPUs |"
				print "|---|---|---|---|---|---|---|---|---|---|---|"
			} else {
				print "| scheduler | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | backend CPUs |"
				print "|---|---|---|---|---|---|---|---|"
			}
			for (a = 1; a <= nc; a++) for (j = 0; j < 1 + user; j++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]; d = j == 0 ? depth : 0; k = cs SUBSEP d
				if (!n[k]) continue
				cv = measured(k) ? sprintf("%.2f", mean(k)) : "not a measurement"
				jk = cas[a] SUBSEP "jdk" SUBSEP d
				vs = sch[i] != "jdk" && measured(k) && measured(jk) ? sprintf("%+.1f%%", (mean(k) / mean(jk) - 1) * 100) : ""
				printf "%s %s | %s | %s | %s | %s | %s |", lead(cas[a], sch[i], d), cv,
					(measured(k) ? range(k SUBSEP "cpu", "%.2f") : ""), vs, grange(k SUBSEP "p50"), grange(k SUBSEP "p99"),
					grange(k SUBSEP "p999")
				if (user) printf " %.1f%% |", mig[k] / n[k]
				printf " %s |\n", ((k SUBSEP "bcpu") in lo ? range(k SUBSEP "bcpu", "%.2f") : "")
			}
			print ""
			print "```"
			print "vs jdk = cpu µs/req / cpu µs/req of jdk, same " (user ? "case and depth" : "rate") ", - 1"
			print "```"
			print ""
			print "From the console of each run, " file ".out: cpu µs/req, the line cpu; latencies, the line"
			if (user) {
				print "latency; migrations, the line migrations; backend CPUs, the line backend cpu: the CPU time of the"
				print "backend process, read from /proc, divided by the length of the measure. cpu µs/req and"
				print "migrations: " avg ". cpu range, backend CPUs and"
			} else {
				print "latency; backend CPUs, the line backend cpu: the CPU time of the backend process, read from"
				print "/proc, divided by the length of the measure. cpu µs/req: " avg ". cpu range, backend CPUs and"
			}
			print "latencies: " span ". vs jdk is empty when jdk is not a measurement."
			print step

			print ""
			print "## Controls"
			print ""
			if (user) {
				print "| case | scheduler | depth | req/s | GCs | live StackChunks | note |"
				print "|---|---|---|---|---|---|---|"
			} else {
				print "| scheduler | req/s | GCs | note |"
				print "|---|---|---|---|"
			}
			for (a = 1; a <= nc; a++) for (j = 0; j < 1 + user; j++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]; d = j == 0 ? depth : 0; k = cs SUBSEP d
				if (!n[k]) continue
				which = k in late ? runs : ""
				if (d == depth && cnote[cs] != "") which = which (which == "" ? "" : ", ") "c2c run"
				if (d == depth && snote[cs] != "") which = which (which == "" ? "" : ", ") "stat run"
				note = which == "" ? "" : "not a measurement (" which ")"
				if (user)
					printf "%s %s | %s | %s | %s |\n", lead(cas[a], sch[i], d), grange(k SUBSEP "rps"),
						range(k SUBSEP "gcs"), (d == depth ? chunks[cs] : ""), note
				else
					printf "%s %s | %s | %s |\n", lead(cas[a], sch[i], d), grange(k SUBSEP "rps"), range(k SUBSEP "gcs"), note
			}
			print ""
			print "From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause"
			if (user) {
				print "lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the line"
				print "stackchunks of the console of the c2c run, during the warm-up → after the measure. req/s and"
				print "GCs: " span ". A run is not a measurement when its console says it could not keep up"
				print "with its rate, its clients sent too late, a GET failed, or a pinning failed."
			} else {
				print "lines of its GC log. req/s and GCs: " span ". A run is not a measurement when its console"
				print "says it could not keep up with its rate, some requests got no 200, its clients sent too late,"
				print "or a pinning failed."
			}
			print step

			# --no-perf: no stat, c2c, walk or epsilon run, so none of their sections.
			if (!perf) exit

			print ""
			print "## perf stat"
			print ""
			print (user ? "| case " : "") "| scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |"
			print (user ? "|---" : "") "|---|---|---|---|---|---|---|---|---|"
			for (a = 1; a <= nc; a++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]
				if (!stat[cs]) continue
				if (cyc[cs] + 0 > 0 && req[cs] + 0 > 0)
					printf "%s %s | %.2f | %.2f | %s | %s | %s | %s | %s |\n", plead(cas[a], sch[i]), group(ins[cs] / req[cs]),
						ins[cs] / cyc[cs], cyc[cs] / ins[cs], group(cyc[cs] / req[cs]), group(ins[cs]), group(cyc[cs]),
						group(req[cs]), pnote(cs, snote[cs])
				else
					printf "%s | | | | %s | | | %s |\n", plead(cas[a], sch[i]), ins[cs], pnote(cs, snote[cs])
			}
			print ""
			print "```"
			print "instructions/req       = instructions / requests"
			print "cycles/req             = cycles / requests"
			print "IPC                    = instructions / cycles"
			print "cycles per instruction = cycles / instructions = 1 / IPC"
			print "```"
			print ""
			print "The stat run of each scheduler" at ". instructions and cycles: the counts of perf"
			print "stat, summed over the server'"'"'s threads (every thread but client-*), in"
			print "runs/" sprintf(pfile, "stat") ".csv. requests: " group(rate) " req/s × " window " s, the window of perf stat."
			print step

			print ""
			print "## perf c2c"
			print ""
			if (user) {
				print "| case | scheduler | migrations | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |"
				print "|---|---|---|---|---|---|---|---|---|"
			} else {
				print "| scheduler | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |"
				print "|---|---|---|---|---|---|---|"
			}
			for (a = 1; a <= nc; a++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]
				if (!c2c[cs]) continue
				printf "%s", plead(cas[a], sch[i])
				if (user) printf " %s |", cmig[cs]
				if (loads[cs] ~ /^[0-9]+$/)
					printf " %s | %s | %s | %s | %s | %s |\n", share(hitm[cs], loads[cs]), share(dram[cs], loads[cs]),
						group(loads[cs]), group(hitm[cs]), group(dram[cs]), pnote(cs, cnote[cs])
				else
					printf " | | %s | | | %s |\n", loads[cs], pnote(cs, cnote[cs])
			}
			print ""
			print "```"
			print "other L3 = Load Remote HITM / Load Operations"
			print "DRAM     = Load DRAM / Load Operations"
			print "```"
			print ""
			print "The c2c run of each scheduler" at ". Load Operations, Load Remote HITM and Load DRAM"
			print "are copied from its report, " sprintf(pfile, "c2c") ".txt, block Trace Event Information:"
			print ""
			print "- Load Operations: the memory loads that perf sampled."
			print "- Load Remote HITM: among them, the loads that took their line from behind another L3,"
			print "  because a core there had modified it."
			print "- Load DRAM: among them, the loads served by the memory (Load Local DRAM + Load Remote DRAM)."
			if (user) {
				print ""
				print "migrations: the line migrations of the console of the c2c run, runs/<case>-c2c-<scheduler>-" depth ".out."
			}
			print step

			if (!user) exit

			print ""
			print "## Walk"
			print ""
			print "| case | scheduler | walk cycles/req | walk cycles | requests | note |"
			print "|---|---|---|---|---|---|"
			for (a = 1; a <= nc; a++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]
				if (!wrun[cs]) continue
				if (wcyc[cs] ~ /^[0-9]+$/ && wreq[cs] + 0 > 0)
					printf "| %s | %s | %s | %s | %s | %s |\n", cas[a], sch[i], group(wcyc[cs] / wreq[cs]),
						group(wcyc[cs]), group(wreq[cs]), pnote(cs, wnote[cs])
				else
					printf "| %s | %s | | %s | | %s |\n", cas[a], sch[i], wcyc[cs], pnote(cs, wnote[cs])
			}
			print ""
			print "```"
			print "walk cycles/req = walk cycles / requests"
			print "```"
			print ""
			print "The walk run of each scheduler, at depth " depth ": the same run, with UserGroupActor.walk kept out"
			print "of line (-XX:CompileCommand=dontinline), so that it has a name of its own. walk cycles: the"
			print "cycles that perf record gives to UserGroupActor.walk, the column Period of its report,"
			print "<case>-walk-<scheduler>.txt. It is only the walk: the scheduler and the kernel are not in it."
			print "requests: " group(rate) " req/s × " window " s, the window of perf record."
			print step

			print ""
			print "## Epsilon"
			print ""
			print "| case | scheduler | cpu µs/req G1 | cpu µs/req Epsilon | difference | heap used | note |"
			print "|---|---|---|---|---|---|---|"
			for (a = 1; a <= nc; a++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]; k = cs SUBSEP depth
				if (!eps[cs]) continue
				g = measured(k) ? sprintf("%.2f", mean(k)) : "not a measurement"
				e = enote[cs] == "" ? ecpu[cs] : "not a measurement"
				df = measured(k) && enote[cs] == "" ? sprintf("%+.2f", ecpu[cs] - mean(k)) : ""
				printf "| %s | %s | %s | %s | %s | %s | %s |\n", cas[a], sch[i], g, e, df,
					(eheap[cs] == "" ? "" : group(eheap[cs]) " MiB"), (enote[cs] == "" ? "" : "this run: " enote[cs])
			}
			print ""
			print "```"
			print "difference = cpu µs/req Epsilon - cpu µs/req G1"
			print "```"
			print ""
			print "The epsilon run of each scheduler, at depth " depth ": the same run with the Epsilon GC, which"
			print "frees nothing, so no GC runs. cpu µs/req G1: " avg ", as in In short. cpu µs/req Epsilon:"
			print "the line cpu of its console, runs/<case>-epsilon-<scheduler>-" depth ".out. heap used: from its GC log"
			print "(.gc.log), the line of the heap at exit \"space ... used [start, top, end)\", top - start."
			print step
		}' "$OUT/rows.md"
}

# ramp_steps <out>: each step of a ramp's console, one line each, tab-separated: asked req/s,
# achieved req/s, cpu µs/req, server cores, p50, p99, p99.9, max, backend CPUs, kept up.
ramp_steps() {
	awk -v OFS='\t' '
		function row() {
			if (asked != "") print asked, rps, us, cores, p50, p99, p999, max, backend, late ? "no" : "yes"
			asked = ""
		}
		function start(a) { row(); asked = a; rps = us = cores = p50 = p99 = p999 = max = backend = "-"; late = 0 }
		/^step / { start($2) }
		/^ramp +kept up with/ { row() }
		/^throughput / { rps = $2 }
		/^cpu / { us = $2; cores = $4; sub(/\(/, "", cores) }
		/^latency / { p50 = $3; p99 = $7; p999 = $9; max = $11 }
		/^backend cpu / { backend = $3 }
		/did not keep up|no backend response/ { late = 1 }
		END { row() }' "$1"
}

# fast_rate <out>: in a ramp's console, the last step kept up with before the first step whose p99
# is above $P99_LIMIT µs, or before the first step not kept up with. none: not even the first step.
fast_rate() {
	ramp_steps "$1" | awk -F'\t' -v limit="$P99_LIMIT" '
		$10 != "yes" || $6 == "-" || $6 + 0 > limit { exit }
		{ v = $1; gsub(/,/, "", v) }
		END { print (v == "" ? "none" : v) }'
}

# p99_limit: $P99_LIMIT for a reader: "1 ms", or "500 µs".
p99_limit() {
	((P99_LIMIT % 1000)) && echo "$P99_LIMIT µs" || echo "$((P99_LIMIT / 1000)) ms"
}

# The ramp section of the summary: one row per ramp run, per case and scheduler, with the last step
# kept up with and the first step missed. Every step is in the console of the run. The servlet
# benchmark has no case column.
ramp_table() {
	local c s out user=0 file
	[ "$BENCH" != user ] || user=1
	file="runs/<case>-ramp<n>-<scheduler>-$CASE_DEPTH.out"
	[ "$user" = 1 ] || file="runs/servlet-ramp<n>-<scheduler>.out"
	echo
	echo "## The ramp"
	echo
	[ "$user" = 0 ] || printf '| case '
	echo "| scheduler | run | kept up with req/s | cpu µs/req | p99 µs | missed req/s | achieved req/s | p99 µs |"
	[ "$user" = 0 ] || printf '|---'
	echo "|---|---|---|---|---|---|---|---|"
	for c in "${CASES[@]}"; do
		for s in $SCHEDULERS; do
			for ((r = 1; r <= RAMP_RUNS; r++)); do
				out=$RUNS/${c%%:*}-ramp$r-$s${CASE_DEPTH:+-$CASE_DEPTH}.out
				[ -f "$out" ] || continue
				# ramp_steps fields: 1 asked, 2 achieved, 3 cpu µs/req, 6 p99, 10 kept up.
				ramp_steps "$out" | awk -F'\t' -v c="${c%%:*}" -v s="$s" -v run="ramp$r" -v user="$user" '
					$10 == "yes" { held = $1; us = $3; p99 = $6 }
					$10 == "no" && miss == "" { miss = $1; got = $2; mp99 = $6 }
					END {
						printf (user ? "| " c " | " s " | " run : "| " s " | " run)
						if (held == "") printf " | none | | "
						else printf " | %s | %s | %s", held, us, p99
						if (miss == "") print " | - | | |"
						else printf " | %s | %s | %s |\n", miss, got, mp99
					}'
			done
		done
	done
	echo
	echo "From the console of each ramp run$([ "$user" = 0 ] || echo ", at depth $CASE_DEPTH"), $file: from $RATE req/s,"
	echo "$STEP req/s more at each step, ${STEP_SECONDS}s measured, until a step is not kept up with$([ "$MAX_RATE" = 0 ] || echo " or above $MAX_RATE req/s")."
	echo "Each step is one block of the console, from its line step. The console has every step."
	echo
	echo "- kept up with req/s: the last step kept up with, the line ramp kept up with. none: not even the first."
	echo "- cpu µs/req, p99 µs: at that step, the lines cpu and latency of its block."
	echo "- missed req/s: the next step, the first not kept up with: its block says it did not keep up$([ "$user" = 0 ] || echo ", or that a GET got no answer")."
	echo "  -: the ramp stopped at its last rate, with no step missed."
	echo "- achieved req/s, p99 µs: at that missed step, the lines throughput and latency of its block."
}

# run <label> <scheduler> <depth> [c2c|stat|walk|epsilon|ramp]: one JVM of the case $CASE, its console in
# <case>-<label>-<scheduler>-<depth>.out, one line in rows.md. The servlet runs: no depth, and the
# case is servlet.
run() {
	local label=$1 s=$2 depth=$3 mode=${4:-} name=$CASE-$1-$2${3:+-$3} pid helpers=() main
	# The epsilon run: a GC that frees nothing, so no GC runs. Its log says the heap used at exit.
	local heap=(-Xms2g -Xmx2g -Xlog:gc:file="$RUNS/$name.gc.log")
	[ "$mode" != epsilon ] ||
		heap=(-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms12g -Xmx12g -Xlog:gc*:file="$RUNS/$name.gc.log")
	local args=(--enable-native-access=ALL-UNNAMED "${heap[@]}" -XX:+AlwaysPreTouch -Dbench.connections="$CONNECTIONS")
	bench_args "$s" "$depth" "$mode"
	[ "$mode" != c2c ] || args+=(-XX:+UnlockDiagnosticVMOptions -XX:+DumpPerfMapAtExit)
	# The walk run: UserGroupActor.walk is never inlined into its caller, so perf sees it as one function.
	[ "$mode" != walk ] || args+=(-XX:+UnlockDiagnosticVMOptions -XX:+DumpPerfMapAtExit -XX:CompileCommand=quiet
		-XX:CompileCommand=dontinline,io.github.pderop.looma.examples.user.UserGroupActor::walk)
	${PIN[@]+"${PIN[@]}"} "$JAVA" "${args[@]}" -cp "$CP" "$main" >"$RUNS/$name.out" 2>&1 &
	pid=$!
	rm -f "$RUNS/$name.pinning"
	if [ "${#PIN[@]}" -gt 0 ] && [ "$s" = jdk-pinned ]; then
		pin_jdk_workers "$pid" "$RUNS/$name.pinning" "$RUNS/$name.out" &
		helpers+=($!)
	fi
	[ "$mode" != c2c ] || { c2c "$s" "$pid" "$RUNS/$name.out" & helpers+=($!); }
	[ "$mode" != stat ] || { stat "$s" "$pid" "$RUNS/$name.out" & helpers+=($!); }
	[ "$mode" != walk ] || { walk_record "$s" "$pid" "$RUNS/$name.out" & helpers+=($!); }
	[ "$mode" != ramp ] || { show_steps "$pid" "$RUNS/$name.out" "$name" & helpers+=($!); }
	wait "$pid" || die "$name failed: see $RUNS/$name.out"
	# A helper that fails must not end the campaign: what it did is in its file.
	[ "${#helpers[@]}" = 0 ] || wait "${helpers[@]}" || true
	# What the helpers did, at the end of the console. A worker pinned short is a failed pinning.
	if [ -f "$RUNS/$name.pinning" ]; then
		read -r _ n _ <"$RUNS/$name.pinning"
		if [ "$n" != "${#CARRIER_CPUS[@]}" ]; then
			local pinning
			pinning=$(<"$RUNS/$name.pinning")
			echo "${pinning/#pinning      /pinning      pinning failed: }" >"$RUNS/$name.pinning"
		fi
	fi
	cat "$RUNS/$name.pinning" >>"$RUNS/$name.out" 2>/dev/null || true
	rm -f "$RUNS/$name.pinning"
	[ "$mode" = backend-warmup ] || row "$CASE" "$label" "$s" "$depth" "$mode" >>"$OUT/rows.md"
	echo "  $name done"
}


# c2c_runs <depth>: the c2c run of each scheduler. perf c2c record sometimes stops before it writes
# any sample: the run is made again, up to 3 times.
c2c_runs() {
	local depth=$1 s try
	for s in $SCHEDULERS; do
		for try in 1 2 3; do
			run c2c "$s" "$depth" c2c
			grep -q 'Load Operations' "$OUT/$CASE-c2c-$s.txt" && break
			[ "$try" = 3 ] || {
				grep -v "^| $CASE | $s | $depth | c2c |" "$OUT/rows.md" >"$OUT/kept.md" || true
				mv "$OUT/kept.md" "$OUT/rows.md"
			}
		done
	done
}

# The lines of "The runs" that both benchmarks share: the load, the CPUs, the schedulers.
shared_lines() {
	echo "- Open loop: the clients send at a fixed rate. A latency is from the planned send time of a"
	echo "  request to its answer. cpu µs/req is the server's: the process minus the clients."
	echo "- Each carrier has a core to itself: the script refuses two carrier CPUs on one core."
	echo "- The schedulers: ${SCHEDULERS// /, }."
	echo "- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to"
	echo "  their own CPUs."
	echo "- mpsc: the MPSC scheduler of the JDK (openjdk/loom pull request 227), behind Carriers.mpsc."
	echo "  Each carrier is pinned to one CPU, and an actor never moves to another carrier."
	echo "  The JDK's poller mode is not set: on Linux, it is mode 4, one epoll per carrier."
	[[ " $SCHEDULERS " != *" mpsc-unpinned "* ]] ||
		echo "- mpsc-unpinned is mpsc with no carrier pinned (Carriers.mpsc(n).pinned(false)): the OS places them."
	[[ " $SCHEDULERS " != *"-no-affinity "* ]] || {
		echo "- mpsc-no-affinity is mpsc where an actor can run on any carrier: its thread starts on the carrier"
		echo "  of its sender, and an idle carrier may take it (Carriers.mpsc(n).affinity(false))."
		echo "  mpsc-unpinned-no-affinity: the same, no carrier pinned."
	}
}

# scheduler_lines: what each scheduler is, in one line, at the end of each At a glance.
scheduler_lines() {
	local s
	echo "#### The schedulers"
	echo
	for s in $SCHEDULERS; do
		case $s in
		mpsc)
			echo "- mpsc: the MPSC scheduler of openjdk/loom pull request 227, with"
			echo "  \`-Djdk.virtualThreadScheduler.useMpsc=true -Djdk.virtualThreadScheduler.mpscSteal=true\`."
			echo "  Looma **pins each carrier to one CPU**. An actor's threads have the \`Thread.Affinity\` of its"
			echo "  carrier: **never stolen**. The threads an actor starts have none: **an idle carrier can steal them**." ;;
		mpsc-unpinned) echo "- mpsc-unpinned: mpsc, **no carrier pinned**." ;;
		mpsc-no-affinity) echo "- mpsc-no-affinity: mpsc, **carriers pinned**, but an actor's threads have no affinity: **they start on the carrier of the sender, and an idle carrier can steal them**." ;;
		mpsc-unpinned-no-affinity) echo "- mpsc-unpinned-no-affinity: both, **no carrier pinned** and **no affinity**." ;;
		jdk) echo "- jdk: the JDK's default scheduler, **a ForkJoinPool**." ;;
		jdk-pinned) echo "- jdk-pinned: jdk, and the script **pins each worker to one carrier CPU**." ;;
		esac
	done
}

# The ramp, in "The runs" of both benchmarks.
ramp_lines() {
	[ "$STEP" = 0 ] && return
	echo
	echo "Last, $RAMP_RUNS ramp runs per scheduler (ramp1 to ramp$RAMP_RUNS), each in a JVM of its own$([ -z "$CASE_DEPTH" ] || echo ", at depth $CASE_DEPTH")."
	echo "After the warm-up of each, the rate starts at $RATE req/s and goes up by $STEP req/s at each step,"
	echo "${STEP_SECONDS} seconds measured, until a step is"
	echo "not kept up with$([ "$MAX_RATE" = 0 ] || echo " or above $MAX_RATE req/s"). Between two steps the clients go on sending, unmeasured: 2"
	echo "seconds for the late answers, then ${SETTLE_SECONDS} seconds to go up to the next rate."
}
