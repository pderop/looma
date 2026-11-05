#!/usr/bin/env bash
#
# Runs the user benchmark (docs/benchmark.md), in two cases by default, and writes
# <out>/summary.md: the tables of both cases.
#
#   scripts/bench.sh                                # the CPUs of an AMD Ryzen 9 9950X
#   scripts/bench.sh --users-per-group 8000         # the default case only
#
# Prerequisites: the Loom JDK selected (`sdk env`) and the project built. On Linux, it first runs
# prepare-linux-bench.sh, which needs sudo.

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: scripts/bench.sh [options]

Each run is one JVM, with one scheduler: 5 seconds of warm-up, then 20 seconds measured.
In each case (a size of the groups), for each repetition, it runs every scheduler at --depth and
at depth 0. Then one more run per scheduler with perf c2c, one with perf stat, and one with perf
record for the walk: on Linux, the script needs perf. All runs use the same jar and the same JVM
flags: only -Dbench.scheduler changes, and mpsc-mode3 adds -Djdk.pollerMode=3. The walk run also
keeps UserGroup.walk out of line, so that its cycles have a name of their own.

The default CPUs are those of an AMD Ryzen 9 9950X. On another machine, give the three lists,
with commas and ranges only (2-7,10-15), and no CPU in two lists:

  --cpus-carriers <list>   One carrier per CPU, one CPU per core. Default: 2-7,10-15.
  --cpus-client <list>     The load generator: one connection thread per CPU. Default: 0,1,16,17.
  --cpus-backend <list>    The backend. Default: 8,9,24,25.
  --rate <n>               Requests per second in all. Default: 150000.
  --depth <n>              Users read per request. Default: 256.
  --users-per-group <list> The cases: users per actor group, ~82 bytes each, comma-separated.
                           8000 is named default, 200000 big. Default: 8000,200000.
  --reps <n>               Repetitions. Default: 3.
  --schedulers <list>      The schedulers to run, comma-separated, among mpsc, mpsc-mode3, jdk
                           and jdk-pinned. Default: all four.
  --out <dir>              Default: bench/<date>-<time>, in the project.
USAGE
}

die() {
	echo "$(basename "$0"): $*" >&2
	exit 1
}

CARRIER_LIST=2-7,10-15
CLIENT_LIST=0,1,16,17
BACKEND_LIST=8,9,24,25
RATE=150000
DEPTH=256
USERS_PER_GROUP=8000,200000
REPS=3
OUT=
WARMUP=5
MEASURE=20
SCHEDULERS="mpsc mpsc-mode3 jdk jdk-pinned"
KNOWN_SCHEDULERS=$SCHEDULERS
COMMAND_LINE="$0 $*"

while [ $# -gt 0 ]; do
	case $1 in -h | --help) usage; exit 0 ;; esac
	[ $# -ge 2 ] || die "$1 needs a value"
	case $1 in
		--cpus-carriers) CARRIER_LIST=$2 ;;
		--cpus-client) CLIENT_LIST=$2 ;;
		--cpus-backend) BACKEND_LIST=$2 ;;
		--rate) RATE=$2 ;;
		--depth) DEPTH=$2 ;;
		--users-per-group) USERS_PER_GROUP=$2 ;;
		--reps) REPS=$2 ;;
		--schedulers) SCHEDULERS=${2//,/ } ;;
		--out) OUT=$2 ;;
		*) die "unknown option '$1' (try --help)" ;;
	esac
	shift 2
done

[ -n "${SCHEDULERS// /}" ] || die "--schedulers is empty"
for s in $SCHEDULERS; do
	[[ " $KNOWN_SCHEDULERS " == *" $s "* ]] || die "unknown scheduler '$s' (try --help)"
done
for n in "$RATE" "$DEPTH" "$REPS" ${USERS_PER_GROUP//,/ }; do
	[[ $n =~ ^[0-9]+$ ]] || die "not a number: '$n'"
done
# The cases, as <name>:<users per group>.
CASES=()
for n in ${USERS_PER_GROUP//,/ }; do
	case $n in
		8000) CASES+=(default:$n) ;;
		200000) CASES+=(big:$n) ;;
		*) CASES+=(users-$n:$n) ;;
	esac
done
((DEPTH > 0)) || die "--depth must be above 0: every run is also made at depth 0"

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

# The three CPU lists: commas and ranges only, and no CPU in two lists.
declare -A LIST_OF=()
for opt in carriers:$CARRIER_LIST client:$CLIENT_LIST backend:$BACKEND_LIST; do
	name=${opt%%:*} list=${opt#*:}
	[[ $list =~ ^[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$ ]] ||
		die "--cpus-$name '$list': give CPUs with commas and ranges only, as in 2-7,10-15"
	for part in ${list//,/ }; do
		[[ $part != *-* ]] || ((${part%-*} <= ${part#*-})) || die "--cpus-$name '$list': the range $part goes down"
	done
	cpus=$(expand_cpus "$list")
	for cpu in $cpus; do
		[ -z "${LIST_OF[$cpu]:-}" ] || die "CPU $cpu is twice in the lists: --cpus-${LIST_OF[$cpu]} and --cpus-$name"
		LIST_OF[$cpu]=$name
	done
done

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
# Turbo off, a fixed frequency, perf allowed. Applying it again keeps the saved originals.
[ "$(uname -s)" != Linux ] || scripts/prepare-linux-bench.sh >/dev/null ||
	die "prepare-linux-bench.sh failed: run it by hand to see why"
source scripts/loom-jdk.sh
JAVA=$LOOM_JAVA
JCMD=$(dirname "$JAVA")/jcmd
CP=${CP:-$(scripts/classpath.sh)}
OUT=${OUT:-$ROOT/bench/$(date +%Y%m%d-%H%M%S)}
[ ! -e "$OUT" ] || die "$OUT already exists"
# The files of every run go to runs/; summary.md and the c2c reports stay at the top.
RUNS=$OUT/runs
mkdir -p "$RUNS"

CARRIER_CPUS=($(expand_cpus "$CARRIER_LIST"))

# Two carriers on the two CPUs of one core would share its L2.
declare -A CORE_OF=()
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
command -v perf >/dev/null && C2C=1
# On Linux, the c2c, stat and walk runs are part of every measurement: no perf, no run.
[ "$C2C" = 1 ] || [ "$(uname -s)" != Linux ] || die "perf is needed on Linux (the c2c, stat and walk runs)"
# Elsewhere, there is no perf: a smoke test, without the c2c, stat and walk runs.
[ "$C2C" = 1 ] || echo "no perf: no c2c, stat or walk run, a smoke test and not a measurement" >&2

turbo() {
	local f
	for f in /sys/devices/system/cpu/intel_pstate/no_turbo /sys/devices/system/cpu/cpufreq/boost; do
		[ -r "$f" ] || continue
		case $f:$(cat "$f") in */no_turbo:1 | */boost:0) echo off ;; *) echo ON ;; esac
		return
	done
	echo unknown
}

{
	echo "# User benchmark"
	echo
	echo '```'
	printf 'date         %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
	printf 'host         %s, %s %s\n' "$(hostname)" "$(uname -s)" "$(uname -r)"
	printf 'cpu model    %s\n' "$(lscpu 2>/dev/null | sed -n 's/^Model name:[[:space:]]*//p' | head -1)"
	printf 'turbo        %s\n' "$(turbo)"
	printf 'java         %s\n' "$("$JAVA" -version 2>&1 | sed -n 2p)"
	printf 'carriers     CPUs %s\n' "$CARRIER_LIST"
	printf 'clients      CPUs %s\n' "$CLIENT_LIST"
	printf 'backend      CPUs %s\n' "$BACKEND_LIST"
	printf 'heap         2g, G1; the epsilon runs 12g, Epsilon\n'
	printf 'command      %s\n' "$COMMAND_LINE"
	echo '```'
} >"$OUT/summary.md"
cat "$OUT/summary.md"

# Whatever this script started -- the backend, a benchmark JVM, perf -- dies with it.
trap 'kill $(jobs -p) 2>/dev/null || true' EXIT
trap 'exit 130' INT TERM

# The backend, in a process of its own, for all the runs.
(exec 3<>/dev/tcp/127.0.0.1/8081) 2>/dev/null && die "port 8081 is in use: is another backend running?"
"${BACKEND_PIN[@]}" "$JAVA" -Dbench.backendPort=8081 -Dbench.backendThreads="$(expand_cpus "$BACKEND_LIST" | wc -l | tr -d " ")" \
	-cp "$CP" io.github.pderop.looma.examples.user.BackendServer >"$OUT/backend.out" 2>&1 &
BACKEND_PID=$!
for _ in $(seq 50); do
	grep -qs 'listening' "$OUT/backend.out" && break
	kill -0 "$BACKEND_PID" 2>/dev/null || die "the backend did not start: $(cat "$OUT/backend.out")"
	sleep 0.2
done
grep -qs 'listening' "$OUT/backend.out" || die "the backend never said it was listening"

# jdk-pinned: the builtin's workers, ForkJoinPool-<n>-worker-<k>, each pinned to the k-th carrier
# CPU. Found by name in a thread dump, during the warm-up only: a thread dump stops the JVM. Writes
# how many workers it pinned to <file>. A worker can exit before taskset runs: it is not counted.
pin_jdk_workers() {
	local pid=$1 file=$2 out=$3 t line pinned=()
	wait_line "$pid" "$out" window || return 0
	for ((t = 1; t < WARMUP; t++)); do
		sleep 1
		while read -r line; do
			# '"ForkJoinPool-1-worker-3" #42 [30559] ...': the OS thread id is in the brackets.
			[[ $line =~ ^\"ForkJoinPool-[0-9]+-worker-([0-9]+)\"\ \#[0-9]+\ \[([0-9]+)\] ]] || continue
			taskset -p -c "${CARRIER_CPUS[$(((BASH_REMATCH[1] - 1) % ${#CARRIER_CPUS[@]}))]}" \
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
# sum of the column Period on the lines of UserGroup.walk. The requests are the rate times the
# window of perf record.
walk_counts() {
	awk -v req=$((RATE * (MEASURE - 6))) '
		/UserGroup\.walk\(/ { gsub(/,/, "", $2); if ($2 ~ /^[0-9]+$/) { cycles += $2; found = 1 } }
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

# row <case> <label> <scheduler> <depth> [c2c|stat|walk|epsilon]: the line of one run in rows.md,
# from its files: the console, the GC log, and the perf stat file, the c2c or the walk report.
row() {
	local c=$1 label=$2 s=$3 depth=$4 mode=${5:-} name=$1-$2-$3-$4 gcs late stats=" | | " c2cs=" | | " heap= walks=" | "
	# The full GCs of the StackChunk counts are not the run's.
	gcs=$(grep Pause "$RUNS/$name.gc.log" | grep -vc 'Heap Inspection' || true)
	late=$(grep -q 'did not keep up\|no backend response\|pinning failed' "$RUNS/$name.out" && echo "not a measurement" || echo)
	[ "$mode" != c2c ] || c2cs=$(c2c_counts "$OUT/$c-c2c-$s.txt")
	[ "$mode" != stat ] || stats=$(stat_counts "$RUNS/$c-stat-$s.csv")
	[ "$mode" != epsilon ] || heap=$(epsilon_heap "$RUNS/$name.gc.log")
	[ "$mode" != walk ] || walks=$(walk_counts "$OUT/$c-walk-$s.txt")
	awk -v c="$c" -v s="$s" -v d="$depth" -v r="$label" -v gcs="$gcs" -v late="$late" -v stats="$stats" -v c2cs="$c2cs" \
		-v heap="$heap" -v walks="$walks" '
		/^throughput/  { gsub(/,/, "", $2); rps = $2 }
		/^cpu /        { cpu = $2 }
		/^migrations/  { mig = $2 }
		/^latency/     { p50 = $3; p99 = $7; p999 = $9 }
		/^stackchunks/ { chunks = $2 " → " $7 }
		END { printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n",
			c, s, d, r, rps, cpu, mig, p50, p99, p999, gcs, stats, c2cs, chunks, late, heap, walks }' "$RUNS/$name.out"
}

# The sections of the summary after "The runs", from rows.md: perf stat, perf c2c, the walk, the controls,
# and in short the CPU and the latency. One line per case and scheduler. Each section says where
# its numbers come from, and shows the counts that the other columns are computed from.
summary_tables() {
	local names=() c
	for c in "${CASES[@]}"; do names+=("${c%%:*}"); done
	awk -F'|' -v cases="${names[*]}" -v order="$SCHEDULERS" -v depth="$DEPTH" -v reps="$REPS" \
		-v rate="$RATE" -v window=$((MEASURE - 6)) '
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
		{ for (i = 2; i <= 23; i++) f[i] = trim($i); c = f[2]; s = f[3]; d = f[4]; r = f[5]; cs = c SUBSEP s }
		r ~ /^r[0-9]+$/ {
			k = cs SUBSEP d; n[k]++; cpu[k] += f[7]; mig[k] += f[8]
			keep(k SUBSEP "rps", f[6]); keep(k SUBSEP "cpu", f[7]); keep(k SUBSEP "p50", f[9])
			keep(k SUBSEP "p99", f[10]); keep(k SUBSEP "p999", f[11]); keep(k SUBSEP "gcs", f[12])
			if (f[20] != "") late[k] = 1
		}
		r == "stat" { stat[cs] = 1; ins[cs] = f[13]; cyc[cs] = f[14]; req[cs] = f[15]; snote[cs] = f[20] }
		r == "walk" { wrun[cs] = 1; wcyc[cs] = f[22]; wreq[cs] = f[23]; wnote[cs] = f[20] }
		r == "epsilon" { eps[cs] = 1; ecpu[cs] = f[7]; enote[cs] = f[20]; eheap[cs] = f[21] }
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

			step = "Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from)."

			print "## At a glance"
			print ""
			printf "| scheduler |"
			for (a = 1; a <= nc; a++) printf " %s, cpu µs/req |", cas[a]
			print ""
			printf "|---|"
			for (a = 1; a <= nc; a++) printf "---|"
			print ""
			for (i = 1; i <= ns; i++) {
				printf "| %s |", sch[i]
				for (a = 1; a <= nc; a++) {
					k = cas[a] SUBSEP sch[i] SUBSEP depth
					printf " %s |", !n[k] ? "" : measured(k) ? sprintf("%.2f", mean(k)) : "not a measurement"
				}
				print ""
			}
			print ""
			print "cpu µs/req at depth " depth ", " avg ", as in In short."

			print ""
			print "## In short"
			print ""
			print "| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations |"
			print "|---|---|---|---|---|---|---|---|---|---|"
			for (a = 1; a <= nc; a++) for (j = 0; j < 2; j++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]; d = j == 0 ? depth : 0; k = cs SUBSEP d
				if (!n[k]) continue
				cv = measured(k) ? sprintf("%.2f", mean(k)) : "not a measurement"
				jk = cas[a] SUBSEP "jdk" SUBSEP d
				vs = sch[i] != "jdk" && measured(k) && measured(jk) ? sprintf("%+.1f%%", (mean(k) / mean(jk) - 1) * 100) : ""
				printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %.1f%% |\n", cas[a], sch[i], d, cv,
					(measured(k) ? range(k SUBSEP "cpu", "%.2f") : ""), vs, grange(k SUBSEP "p50"), grange(k SUBSEP "p99"),
					grange(k SUBSEP "p999"), mig[k] / n[k]
			}
			print ""
			print "```"
			print "vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1"
			print "```"
			print ""
			print "From the console of each run, runs/<case>-<run>-<scheduler>-<depth>.out: cpu µs/req, the line"
			print "cpu; latencies, the line latency; migrations, the line migrations. cpu µs/req and migrations:"
			print avg ". cpu range and latencies: " span ". vs jdk is empty when jdk is not a measurement."
			print step

			print ""
			print "## Controls"
			print ""
			print "| case | scheduler | depth | req/s | GCs | live StackChunks | note |"
			print "|---|---|---|---|---|---|---|"
			for (a = 1; a <= nc; a++) for (j = 0; j < 2; j++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]; d = j == 0 ? depth : 0; k = cs SUBSEP d
				if (!n[k]) continue
				which = k in late ? runs : ""
				if (d == depth && cnote[cs] != "") which = which (which == "" ? "" : ", ") "c2c run"
				if (d == depth && snote[cs] != "") which = which (which == "" ? "" : ", ") "stat run"
				note = which == "" ? "" : "not a measurement (" which ")"
				printf "| %s | %s | %s | %s | %s | %s | %s |\n", cas[a], sch[i], d, grange(k SUBSEP "rps"),
					range(k SUBSEP "gcs"), (d == depth ? chunks[cs] : ""), note
			}
			print ""
			print "From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause"
			print "lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the line"
			print "stackchunks of the console of the c2c run, during the warm-up → after the measure. req/s and"
			print "GCs: " span ". A run is not a measurement when its console says it could not keep up"
			print "with its rate, a GET failed, or a pinning failed."
			print step

			print ""
			print "## perf stat"
			print ""
			print "| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |"
			print "|---|---|---|---|---|---|---|---|---|---|"
			for (a = 1; a <= nc; a++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]
				if (!stat[cs]) continue
				if (cyc[cs] + 0 > 0 && req[cs] + 0 > 0)
					printf "| %s | %s | %s | %.2f | %.2f | %s | %s | %s | %s | %s |\n", cas[a], sch[i], group(ins[cs] / req[cs]),
						ins[cs] / cyc[cs], cyc[cs] / ins[cs], group(cyc[cs] / req[cs]), group(ins[cs]), group(cyc[cs]),
						group(req[cs]), pnote(cs, snote[cs])
				else
					printf "| %s | %s | | | | | %s | | | %s |\n", cas[a], sch[i], ins[cs], pnote(cs, snote[cs])
			}
			print ""
			print "```"
			print "instructions/req       = instructions / requests"
			print "cycles/req             = cycles / requests"
			print "IPC                    = instructions / cycles"
			print "cycles per instruction = cycles / instructions = 1 / IPC"
			print "```"
			print ""
			print "The stat run of each scheduler, at depth " depth ". instructions and cycles: the counts of perf"
			print "stat, summed over the server'"'"'s threads (every thread but client-*), in"
			print "runs/<case>-stat-<scheduler>.csv. requests: " group(rate) " req/s × " window " s, the window of perf stat."
			print step

			print ""
			print "## perf c2c"
			print ""
			print "| case | scheduler | migrations | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |"
			print "|---|---|---|---|---|---|---|---|---|"
			for (a = 1; a <= nc; a++) for (i = 1; i <= ns; i++) {
				cs = cas[a] SUBSEP sch[i]
				if (!c2c[cs]) continue
				if (loads[cs] ~ /^[0-9]+$/)
					printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s |\n", cas[a], sch[i], cmig[cs],
						share(hitm[cs], loads[cs]), share(dram[cs], loads[cs]), group(loads[cs]), group(hitm[cs]),
						group(dram[cs]), pnote(cs, cnote[cs])
				else
					printf "| %s | %s | %s | | | %s | | | %s |\n", cas[a], sch[i], cmig[cs], loads[cs], pnote(cs, cnote[cs])
			}
			print ""
			print "```"
			print "other L3 = Load Remote HITM / Load Operations"
			print "DRAM     = Load DRAM / Load Operations"
			print "```"
			print ""
			print "The c2c run of each scheduler, at depth " depth ". Load Operations, Load Remote HITM and Load DRAM"
			print "are copied from its report, <case>-c2c-<scheduler>.txt, block Trace Event Information:"
			print ""
			print "- Load Operations: the memory loads that perf sampled."
			print "- Load Remote HITM: among them, the loads that took their line from behind another L3,"
			print "  because a core there had modified it."
			print "- Load DRAM: among them, the loads served by the memory (Load Local DRAM + Load Remote DRAM)."
			print ""
			print "migrations: the line migrations of the console of the c2c run, runs/<case>-c2c-<scheduler>-" depth ".out."
			print step

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
			print "The walk run of each scheduler, at depth " depth ": the same run, with UserGroup.walk kept out"
			print "of line (-XX:CompileCommand=dontinline), so that its cycles have a name of their own. walk"
			print "cycles: the cycles that perf record gives to UserGroup.walk, the column Period of its report,"
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

# run <label> <scheduler> <depth> [c2c|stat|walk|epsilon]: one JVM of the case $CASE, its console in
# <case>-<label>-<scheduler>-<depth>.out, one line in rows.md.
run() {
	local label=$1 s=$2 depth=$3 mode=${4:-} name=$CASE-$1-$2-$3 pid helpers=()
	# The epsilon run: a GC that frees nothing, so no GC runs. Its log says the heap used at exit.
	local heap=(-Xms2g -Xmx2g -Xlog:gc:file="$RUNS/$name.gc.log")
	[ "$mode" != epsilon ] ||
		heap=(-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms12g -Xmx12g -Xlog:gc*:file="$RUNS/$name.gc.log")
	# mpsc-mode3 is mpsc with the JDK's poller mode 3. Without the flag, mpsc gets mode 4 on Linux.
	local scheduler=${s%-pinned} poller=()
	[ "$s" != mpsc-mode3 ] || { scheduler=mpsc poller=(-Djdk.pollerMode=3); }
	local args=(--enable-native-access=ALL-UNNAMED "${heap[@]}" -XX:+AlwaysPreTouch "${poller[@]}"
		-Dbench.scheduler="$scheduler" -Dbench.carriers="${#CARRIER_CPUS[@]}"
		-Dbench.rate="$RATE" -Dbench.clientCpus="$CLIENT_LIST" -Dbench.depth="$depth"
		-Dbench.usersPerGroup="$CASE_USERS" -Dbench.warmupSeconds="$WARMUP" -Dbench.measureSeconds="$MEASURE")
	[ "$mode" != c2c ] || args+=(-XX:+UnlockDiagnosticVMOptions -XX:+DumpPerfMapAtExit -Dbench.stackChunks=true)
	# The walk run: UserGroup.walk is never inlined into its caller, so perf sees it as one function.
	[ "$mode" != walk ] || args+=(-XX:+UnlockDiagnosticVMOptions -XX:+DumpPerfMapAtExit -XX:CompileCommand=quiet
		-XX:CompileCommand=dontinline,io.github.pderop.looma.examples.user.UserGroup::walk)
	"${PIN[@]}" "$JAVA" "${args[@]}" -cp "$CP" io.github.pderop.looma.examples.user.Main >"$RUNS/$name.out" 2>&1 &
	pid=$!
	rm -f "$RUNS/$name.pinning"
	if [ "${#PIN[@]}" -gt 0 ] && [ "$s" = jdk-pinned ]; then
		pin_jdk_workers "$pid" "$RUNS/$name.pinning" "$RUNS/$name.out" &
		helpers+=($!)
	fi
	[ "$mode" != c2c ] || { c2c "$s" "$pid" "$RUNS/$name.out" & helpers+=($!); }
	[ "$mode" != stat ] || { stat "$s" "$pid" "$RUNS/$name.out" & helpers+=($!); }
	[ "$mode" != walk ] || { walk_record "$s" "$pid" "$RUNS/$name.out" & helpers+=($!); }
	wait "$pid" || die "$name failed: see $RUNS/$name.out"
	# A helper that fails must not end the campaign: what it did is in its file.
	[ "${#helpers[@]}" = 0 ] || wait "${helpers[@]}" || true
	# What the helpers did, at the end of the console. A worker pinned short is a failed pinning.
	if [ -f "$RUNS/$name.pinning" ]; then
		read -r _ n _ <"$RUNS/$name.pinning"
		[ "$n" = "${#CARRIER_CPUS[@]}" ] || sed -i 's/^pinning      /pinning      pinning failed: /' "$RUNS/$name.pinning"
	fi
	cat "$RUNS/$name.pinning" >>"$RUNS/$name.out" 2>/dev/null || true
	rm -f "$RUNS/$name.pinning"
	row "$CASE" "$label" "$s" "$depth" "$mode" >>"$OUT/rows.md"
	echo "  $name done"
}

S=$(wc -w <<<"$SCHEDULERS")
N=$(((REPS * 2 + C2C * 3 + 1) * S * ${#CASES[@]}))
echo "about $((N * (WARMUP + MEASURE + 5) / 60 + 1)) minutes: $N runs"
: >"$OUT/rows.md"
for c in "${CASES[@]}"; do
	CASE=${c%%:*}
	CASE_USERS=${c#*:}
	for ((r = 1; r <= REPS; r++)); do
		for s in $SCHEDULERS; do
			run "r$r" "$s" "$DEPTH"
			run "r$r" "$s" 0
		done
	done
	if [ "$C2C" = 1 ]; then
		for s in $SCHEDULERS; do
			for try in 1 2 3; do
				run c2c "$s" "$DEPTH" c2c
				# perf c2c record sometimes stops before it writes any sample: run it again.
				grep -q 'Load Operations' "$OUT/$CASE-c2c-$s.txt" && break
				[ "$try" = 3 ] || {
					grep -v "^| $CASE | $s | $DEPTH | c2c |" "$OUT/rows.md" >"$OUT/kept.md" || true
					mv "$OUT/kept.md" "$OUT/rows.md"
				}
			done
		done
		for s in $SCHEDULERS; do
			run stat "$s" "$DEPTH" stat
		done
		for s in $SCHEDULERS; do
			run walk "$s" "$DEPTH" walk
		done
	fi
	for s in $SCHEDULERS; do
		run epsilon "$s" "$DEPTH" epsilon
	done
done

{
	echo
	echo "## The runs"
	echo
	echo "$RATE req/s. The cases, by users per group:"
	echo
	for c in "${CASES[@]}"; do
		kib=$((${c#*:} * 82 / 1024))
		size="$kib KiB"
		((kib < 10240)) || size="$(((kib + 512) / 1024)) MiB"
		echo "- ${c%%:*}: ${c#*:} users per group, about $size per group."
	done
	echo
	times="$REPS times"
	reps="r1 to r$REPS"
	[ "$REPS" != 1 ] || { times=once; reps=r1; }
	echo "In each case, each scheduler ran $times at depth $DEPTH and at depth 0 ($reps), then once"
	echo "more with perf c2c, once more with perf stat, once more with perf record for the walk, and"
	echo "once more with the Epsilon GC, at depth $DEPTH. Each run: $WARMUP seconds of warm-up, then"
	echo "$MEASURE seconds measured."
	echo
	echo "- Open loop: the clients send at a fixed rate. A latency is from the planned send time of a"
	echo "  request to its answer. cpu µs/req is the server's: the process minus the clients."
	echo "- Each carrier has a core to itself: the script refuses two carrier CPUs on one core."
	echo "- The schedulers: ${SCHEDULERS// /, }."
	echo "- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to"
	echo "  their own CPUs."
	echo "- mpsc: the MPSC scheduler of the JDK (openjdk/loom pull request 227), behind Carriers.affine."
	echo "  Each carrier is pinned to one CPU, and an actor never moves to another carrier."
	echo "  The JDK's poller mode is not set: on Linux, it is mode 4, one epoll per carrier."
	echo "- mpsc-mode3: mpsc with -Djdk.pollerMode=3. Each carrier has its own Read-Poller, a virtual"
	echo "  thread. When it has nothing to read, it waits on the Master-Poller, a platform thread."
	echo "- Heap: -Xms2g -Xmx2g -XX:+AlwaysPreTouch, G1, and a GC log per run. The epsilon run:"
	echo "  -XX:+UseEpsilonGC -Xms12g -Xmx12g -XX:+AlwaysPreTouch. Epsilon frees nothing, so no GC runs:"
	echo "  each allocation takes new memory."
	echo "- The c2c, stat and walk runs count during the measure, minus 3 seconds at each end. The c2c run"
	echo "  also counts the live StackChunks (GC.class_histogram), during the warm-up and after the measure."
	echo "  The walk run keeps UserGroup.walk out of line. The full report of each c2c run is in"
	echo "  <case>-c2c-<scheduler>.txt, of each walk run in <case>-walk-<scheduler>.txt, the files of every"
	echo "  run in runs/."
	echo
	summary_tables
} >>"$OUT/summary.md"
rm -f "$OUT/rows.md"

echo
echo "summary: $OUT/summary.md"
