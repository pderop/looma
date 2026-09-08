#!/usr/bin/env bash
#
# Runs the user-recommendation demo under three schedulers and prints the three reports side by
# side. THE BINARY IS THE SAME IN ALL OF THEM: which scheduler a run gets is decided entirely by
# the JVM flags below, which is the claim being demonstrated.
#
#   carrier    carrier-affine, carriers pinned one per CPU, no work stealing
#   stealing   same, plus work stealing -- locality traded for balance
#   jdk        JDK builtin scheduler, no affinity at all (the control)
#
#   scripts/run-user-bench.sh                 # the E-cores of a 14900K
#   CORES=pcore scripts/run-user-bench.sh     # its P-cores
#   CPUS=8-15 scripts/run-user-bench.sh       # any other machine: name the CPUs yourself
#   scripts/run-user-bench.sh --help          # the options, and what each run will do
#
# WHAT IT MEASURES AND HOW TO READ THE REPORT is in the javadoc of Main. The two sizes the
# workload is built around -- how deep a request walks, how many users a group owns -- are
# constants of UserGroup with their derivations beside them, deliberately not parameters here.
#
# THE CPUSET is the one thing this script decides, and it decides two things at once. The load
# generator runs in the same JVM as the groups, so a single taskset covers both, and the carriers
# take the FIRST GROUP_COUNT CPUs of the cpuset in ascending order:
#
#   CORES=ecore    16-31           carriers on 16-23, which is two COMPLETE L2 modules (4 Atom
#                                  cores sharing 4 MiB each); clients get 24-31.
#
#   CORES=pcore    0,2,...,14      one carrier per PHYSICAL P-core, each with its private 2 MiB
#                                  L2. Not 0-15: that would land the eight carriers on CPUs 0-7,
#                                  i.e. four physical cores with both hyperthreads of each, two
#                                  groups sharing one L2. Clients share the same eight CPUs here,
#                                  which costs throughput -- identically in all three runs.
#
# STEALING IS GLOBAL, and not by choice: LinuxCarrierTopology groups carriers by last-level
# cache, and this chip has one L3 for everything, so every carrier lands in the same cluster and
# CLUSTER_LOCAL would be GLOBAL under another name. Scoping a steal to the four cores of an Atom
# module would need a topology that descends to L2, which the scheduler does not have.
#
# For a machine that is neither, set CPUS directly.
#
# Prerequisites: the Loom JDK 28 selected (`sdk env` in the project root).

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: scripts/run-user-bench.sh [--help]

Runs the user-recommendation demo under three schedulers on the same cores, with the same load,
and prints the three reports side by side. Configured by environment variables:

  CPUS=<list>          Which CPUs the JVM may use, as taskset writes them: "16-31", "0,2,4,6",
                       "0-3,8". THE FIRST $GROUP_COUNT OF THEM, IN ASCENDING ORDER, GET THE
                       CARRIERS -- one actor group pinned to each. Whatever is left runs the
                       load generator: one client thread per group, in this same JVM.
                       Default: see CORES.

  CORES=ecore|pcore    Shorthand for the two cpusets of a 14900K, when CPUS is not given.
                         ecore  16-31            carriers on 16-23, clients on 24-31
                         pcore  0,2,4,...,14     one carrier per physical P-core (not 0-15,
                                                 which would pack two carriers per core)
                       Default: ecore.

  GROUP_COUNT=<n>      Actor groups, hence carriers, hence how many CPUs of the cpuset are
                       taken -- and also how many client threads, since there is one per group.
                       Same number for every scheduler. Default: 8.

  CONFIGS="..."        Which of carrier, stealing, jdk to run. Default: all three.

  PERF=stat|c2c|both   Attach `perf` and print extra tables at the end. Default: unset (no perf,
                       just the three runs above).
                         stat  perf-stat counters (cycles, instructions, cache-misses) sampled
                               from each of the three runs above, over a steady window inside
                               the measured 20s. Prints cycles/req -- cost per request, immune
                               to clock speed and rate -- and IPC next to the req/s table.
                         c2c   THREE EXTRA ~25s runs, one per scheduler in CONFIGS, each captured
                               with `perf c2c` instead. Prints HITM/load: the share of loads that
                               had to snoop a dirty line out of another core's cache -- the exact
                               mechanism a migration turns into cost. Nothing is shared between
                               actors here, so a HITM can only mean the scheduler resumed one on
                               a core other than the one that dirtied its map.
                         both  stat and c2c both (six runs total instead of three).
                       Needs perf_event_paranoid <= 1 (docs/benchmark.md); higher than that,
                       perf still runs but comes back empty, and the script says so once, up
                       front, rather than leaving blank columns to explain later.

Each scheduler runs for about 25s (5s warm-up, 20s measured), both fixed in the demo. What the
numbers mean is in the javadoc of Main.
USAGE
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
	usage
	exit 0
fi

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR/.."

source "$SCRIPT_DIR/loom-jdk.sh"
JAVA="$LOOM_JAVA"

case "${CORES:-ecore}" in
	ecore) DEFAULT_CPUS=16-31 ;;
	pcore) DEFAULT_CPUS=0,2,4,6,8,10,12,14 ;;
	*)
		echo "unknown CORES '${CORES}' (want: ecore, pcore)" >&2
		exit 1
		;;
esac

CPUS=${CPUS:-$DEFAULT_CPUS}

# Without this property the scheduler installs no topology at all: no pinning, no clusters.
TOPOLOGY=io.netty.loom.topology.LinuxCarrierTopology

# Actor groups, one per carrier. Not named GROUPS: bash owns that name -- it holds the user's
# gids -- and silently ignores an assignment to it.
GROUP_COUNT=${GROUP_COUNT:-8}
CONFIGS=${CONFIGS:-"carrier stealing jdk"}

PERF=${PERF:-}
case "$PERF" in
	"" | stat | c2c | both) ;;
	*)
		echo "unknown PERF '$PERF' (want: stat, c2c, both)" >&2
		exit 1
		;;
esac

# Main's own warm-up/measure split -- perf has to sample strictly inside the measured window, so
# these two have to track Main.WARMUP_SECONDS / Main.MEASURE_SECONDS by hand.
MAIN_WARMUP_SECONDS=5
MAIN_MEASURE_SECONDS=20

# perf stat/c2c degrade to empty output rather than failing outright (that's what lets a machine
# without perf run this script at all), so a paranoid setting that blocks them would otherwise
# show up only as blank columns far downstream, with nothing here to explain why. One line up
# front instead.
warn_if_paranoid() {
	[ -n "$PERF" ] || return 0
	if ! command -v perf >/dev/null 2>&1; then
		echo "PERF=$PERF requested but perf is not installed -- ignoring" >&2
		PERF=""
		return 0
	fi
	local paranoid
	paranoid=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null || true)
	if [[ $paranoid =~ ^-?[0-9]+$ ]] && [ "$paranoid" -gt 1 ]; then
		echo "WARNING: perf_event_paranoid is $paranoid -- perf stat/c2c will most likely come back" >&2
		echo "empty. Fix it for this boot, then re-run:" >&2
		echo "    sudo sysctl kernel.perf_event_paranoid=1" >&2
		echo "(see docs/benchmark.md to make that permanent across reboots)" >&2
	fi
}
warn_if_paranoid

MAIN=io.github.pderop.looma.examples.user.Main
OUT=${TMPDIR:-/tmp}/user-bench
CP=$(scripts/examples-classpath.sh)

mkdir -p "$OUT"

# "16-31", "0,2,4", "0-3,8" -> one CPU per line, ascending, which is the order the carriers are
# handed out in. Printing the split below is the whole point: the cpuset alone does not show it,
# and getting it wrong is what puts two groups on one core.
expand_cpus() {
	local part from to cpu
	local IFS=,
	for part in $1; do
		case $part in
			*-*)
				from=${part%-*}
				to=${part#*-}
				for ((cpu = from; cpu <= to; cpu++)); do
					echo "$cpu"
				done
				;;
			*) echo "$part" ;;
		esac
	done
}

ALL_CPUS=($(expand_cpus "$CPUS" | sort -n))
CARRIER_CPUS=("${ALL_CPUS[@]:0:$GROUP_COUNT}")
CLIENT_CPUS=("${ALL_CPUS[@]:$GROUP_COUNT}")

echo "=== what this will run ==="
printf 'cpuset       %s (%d CPUs)\n' "$CPUS" "${#ALL_CPUS[@]}"
printf 'carrier CPUs %s\n' "${CARRIER_CPUS[*]}"
printf 'client CPUs  %s\n' "${CLIENT_CPUS[*]:-<none: they share the carrier CPUs>}"
printf 'groups       %d actors, one pinned per carrier, same number for every scheduler\n' \
	"$GROUP_COUNT"
printf 'load         %d client threads, one per group, one request in flight each\n' "$GROUP_COUNT"
printf 'schedulers   %s (~25s each)\n' "$CONFIGS"

if [ "${#ALL_CPUS[@]}" -lt "$GROUP_COUNT" ]; then
	echo "WARNING: ${#ALL_CPUS[@]} CPUs for $GROUP_COUNT carriers -- the extra carriers will float" >&2
fi
if [ "${#CLIENT_CPUS[@]}" -eq 0 ]; then
	echo "NOTE: no CPU left for the load generator, so clients preempt carriers -- identically in" >&2
	echo "      all three runs, but it costs throughput. Widen CPUS to separate them." >&2
fi

# Turbo and a non-"performance" governor both let a core's clock drift during a run -- on a
# 14900K, cores 6-7 boost higher than the rest -- and that drift shows up as if it were scheduler
# signal, indistinguishable from the locality effect the three runs exist to isolate.
warn_if_frequency() {
	local no_turbo=/sys/devices/system/cpu/intel_pstate/no_turbo cpu path governors
	if [ -r "$no_turbo" ] && [ "$(cat "$no_turbo")" != "1" ]; then
		echo "WARNING: turbo is enabled -- per-core frequency drift will show up as if it were" >&2
		echo "scheduler signal. For an A/B run:" >&2
		echo "    sudo cpupower frequency-set -g performance" >&2
		echo "    echo 1 | sudo tee $no_turbo" >&2
		echo "(revert after: echo 0 | sudo tee $no_turbo; sudo cpupower frequency-set -g powersave)" >&2
	fi
	governors=$(
		for cpu in "${ALL_CPUS[@]}"; do
			path=/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor
			[ -r "$path" ] && cat "$path"
		done | sort -u | tr '\n' ' '
	# No cpufreq sysfs at all -- a Mac, a container -- leaves the loop ending on a failed test,
	# which pipefail hands back as the status of the whole substitution and `set -e` then turns
	# into a silent exit right here, before the first run. Nothing to warn about in that case.
	) || true
	if [ -n "$governors" ] && [ "$governors" != "performance " ]; then
		echo "WARNING: governor on the cpuset is [$governors] -- want \"performance\", or the clock" >&2
		echo "will ramp up and down mid-run instead of holding still. Fix:" >&2
		echo "    sudo cpupower -c $CPUS frequency-set -g performance" >&2
	fi
}
warn_if_frequency

# taskset only where there is one: this script still runs on a Mac, minus the pinning.
if command -v taskset >/dev/null 2>&1; then
	PIN=(taskset -c "$CPUS")
else
	echo "no taskset here -- running unpinned, which is a smoke test and not a measurement" >&2
	PIN=()
fi

# THE ONLY DIFFERENCE BETWEEN THE RUNS. The carrier side needs the preview flag and native access,
# because that is what the scheduler module is built with and what its topology probe calls.
CARRIER_ARGS=(
	--enable-preview
	--enable-native-access=ALL-UNNAMED
	-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler
	-Dio.netty.loom.schedulers="$GROUP_COUNT"
	-Dio.netty.loom.topology="$TOPOLOGY"
)

# Work stealing lets an idle carrier run another carrier's actor: it recovers the CPU an affine
# run leaves idle, and pays for it with exactly the locality this demo measures.
STEALING_ARGS=(
	"${CARRIER_ARGS[@]}"
	-Dio.netty.loom.workstealing.enabled=true
	-Dio.netty.loom.workstealing.scope=GLOBAL
)

# Deliberately NO --enable-preview: without it the scheduler jar on the classpath cannot link,
# ServiceLoader discovery skips it, and the builtin scheduler is what is left -- which is exactly
# the control this run wants.
JDK_ARGS=(
	-Djdk.virtualThreadScheduler.parallelism="$GROUP_COUNT"
	-Djdk.virtualThreadScheduler.maxPoolSize="$GROUP_COUNT"
)

# capture_perf_stat <label> <pid>
# Waits out Main's own warm-up plus a margin for JVM startup, then samples counters for a window
# inside the measured 20s, margined at both ends so perf never outlives the JVM it is attached to.
# Deliberately generic (unqualified) event names: CORES=ecore, the default, targets the E-cores,
# whose PMU is cpu_atom and does not carry the mem_load_retired.l2_hit/l3_hit split that a
# P-core (cpu_core) run could give -- these five work on either, which is the point.
capture_perf_stat() {
	case "$PERF" in stat | both) ;; *) return 0 ;; esac
	local label=$1 pid=$2 window=$((MAIN_MEASURE_SECONDS - 6))
	sleep $((MAIN_WARMUP_SECONDS + 2))
	[ "$window" -ge 1 ] || return 0
	perf stat -p "$pid" -e task-clock,cycles,instructions,cache-references,cache-misses \
		-- sleep "$window" >"$OUT/$label.perfstat.txt" 2>&1 ||
		echo "  ($label: perf stat produced nothing -- check perf_event_paranoid)" >&2
}

# One run of one scheduler, appending its four numbers to $OUT/results.tsv so that the summary
# below never has to parse a report twice. With PERF unset this is exactly the original pipeline
# (tee streams the report live); with PERF set the JVM is backgrounded instead so capture_perf_stat
# can get an exact pid to attach to, and the report is only shown once the run is over.
run() {
	local label=$1
	shift
	local out="$OUT/$label.out"
	echo
	echo "=== $label ==="
	if [ -n "$PERF" ]; then
		"${PIN[@]}" "$JAVA" "$@" -Dcache.groups="$GROUP_COUNT" -cp "$CP" "$MAIN" >"$out" 2>&1 &
		local pid=$!
		capture_perf_stat "$label" "$pid"
		local status=0
		wait "$pid" || status=$?
		cat "$out"
		[ "$status" -eq 0 ] || exit "$status"
	else
		"${PIN[@]}" "$JAVA" "$@" -Dcache.groups="$GROUP_COUNT" -cp "$CP" "$MAIN" | tee "$out"
	fi
	awk -v label="$label" '
		/^throughput/ { gsub(/,/, "", $2); rps = $2 }
		/^cpu /       { cpu = $2; gsub(/[()]/, "", $4); busy = $4 }
		/^migrations/ { gsub(/%/, "", $2); mig = $2 }
		END { printf "%s\t%s\t%s\t%s\t%s\n", label, rps, cpu, busy, mig }
	' "$out" >> "$OUT/results.tsv"
}

# run_c2c <label> <flags...>
# A dedicated extra run per scheduler, not folded into run() above: c2c needs sustained PEBS
# sampling of its own and writes its own large *.c2c.data, and multiplexing it against the
# perf-stat counters would just make both noisier for nothing.
run_c2c() {
	local label=$1
	shift
	echo
	echo "=== $label ==="
	local out="$OUT/$label.out"
	"${PIN[@]}" "$JAVA" "$@" -Dcache.groups="$GROUP_COUNT" -cp "$CP" "$MAIN" >"$out" 2>&1 &
	local pid=$! window=$((MAIN_MEASURE_SECONDS - 6))
	sleep $((MAIN_WARMUP_SECONDS + 2))
	if [ "$window" -ge 1 ]; then
		perf c2c record -p "$pid" -o "$OUT/$label.c2c.data" -- sleep "$window" >/dev/null 2>&1 &&
			perf c2c report -i "$OUT/$label.c2c.data" --stdio >"$OUT/$label.c2c.txt" 2>&1 ||
			echo "  ($label: perf c2c produced nothing -- check 'perf list | grep -i xsnp')" >&2
	fi
	local status=0
	wait "$pid" || status=$?
	tail -3 "$out"
	[ "$status" -eq 0 ] || exit "$status"
}

: > "$OUT/results.tsv"

for config in $CONFIGS; do
	case $config in
		carrier) run carrier "${CARRIER_ARGS[@]}" ;;
		stealing) run stealing "${STEALING_ARGS[@]}" ;;
		jdk) run jdk "${JDK_ARGS[@]}" ;;
		*) echo "unknown configuration '$config' (want: carrier, stealing, jdk)" >&2; exit 1 ;;
	esac
done

case "$PERF" in
	c2c | both)
		for config in $CONFIGS; do
			case $config in
				carrier) run_c2c "c2c-carrier" "${CARRIER_ARGS[@]}" ;;
				stealing) run_c2c "c2c-stealing" "${STEALING_ARGS[@]}" ;;
				jdk) run_c2c "c2c-jdk" "${JDK_ARGS[@]}" ;;
			esac
		done
		;;
esac

echo
echo "=== summary (cores=${CORES:-ecore} cpus=$CPUS groups=$GROUP_COUNT) ==="

# One row per scheduler, jdk as the baseline every delta is taken against. Read cpu us/req and
# "cores busy" before req/s: with one client per group nothing here is CPU-limited, so req/s
# largely reports wake-up latency, and a run that merely stayed awake longer will lead on it --
# the javadoc of Main says why.
awk -F'\t' '
	{
		label = $1
		if (!(label in seen)) { seen[label] = ++labels; order[labels] = label }
		rps[label] = $2; cpu[label] = $3; busy[label] = $4; mig[label] = $5
	}
	END {
		printf "%-10s %12s %10s %11s %12s %12s %13s\n", "", "req/s", "vs jdk", "cpu us/req",
			"vs jdk", "cores busy", "migrations"
		for (i = 1; i <= labels; i++) {
			l = order[i]
			printf "%-10s %12.0f %9.1f%% %11.2f %11.1f%% %12.2f %12.1f%%\n", l, rps[l],
				("jdk" in rps ? (rps[l] / rps["jdk"] - 1) * 100 : 0), cpu[l],
				("jdk" in cpu ? (cpu[l] / cpu["jdk"] - 1) * 100 : 0), busy[l], mig[l]
		}
	}
' "$OUT/results.tsv"

# perf stat: cycles/req and IPC, computed the same way docs/benchmark.md does it -- a raw counter
# is a total over the sampling window, and two schedulers never serve the same number of requests
# in it, so nothing here is comparable until divided by req = rps x window. rps comes from Main's
# own report (already in results.tsv), window from perf's "seconds time elapsed", which can be a
# hair under the requested sleep and has to be read back rather than assumed.
if [[ $PERF == stat || $PERF == both ]]; then
	: > "$OUT/perfstat.tsv"
	for config in $CONFIGS; do
		pf="$OUT/$config.perfstat.txt"
		[ -f "$pf" ] || continue
		# perf's thousands separators make these numbers unusable in arithmetic until stripped.
		# pipefail turns "no match" (an expected outcome when perf produced nothing) into a
		# failing assignment, so each extraction is allowed to come back empty rather than abort.
		# On a hybrid P/E chip perf silently expands a bare event into one per PMU
		# (cpu_atom/cycles/ and cpu_core/cycles/), and whichever PMU this run's cpuset never
		# touches reports "<not counted>" instead of a number -- so match the qualified name too,
		# and the digit requirement already skips the "<not counted>" line on its own.
		cycles=$(grep -oP '[\d,]+(?=\s+(cpu_\w+/)?cycles/?\b)' "$pf" | tr -d , | head -1) || true
		instructions=$(grep -oP '[\d,]+(?=\s+(cpu_\w+/)?instructions/?\b)' "$pf" | tr -d , | head -1) || true
		misses=$(grep -oP '[\d,]+(?=\s+(cpu_\w+/)?cache-misses/?\b)' "$pf" | tr -d , | head -1) || true
		refs=$(grep -oP '[\d,]+(?=\s+(cpu_\w+/)?cache-references/?\b)' "$pf" | tr -d , | head -1) || true
		window=$(grep -oP '[\d.]+(?=\s+seconds time elapsed)' "$pf" | head -1) || true
		if [ -n "$cycles" ] && [ -n "$window" ]; then
			printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$config" "$cycles" "${instructions:-0}" \
				"${misses:-0}" "${refs:-0}" "$window" >> "$OUT/perfstat.tsv"
		fi
	done
	if [ -s "$OUT/perfstat.tsv" ]; then
		echo
		echo "=== perf stat (steady window inside the measured 20s) ==="
		awk -F'\t' '
			NR == FNR { rps[$1] = $2; next }
			{
				label = $1; req = rps[label] * $6
				if (req <= 0) next
				if (!(label in seen)) { seen[label] = ++n; order[n] = label }
				cpr[label] = $2 / req
				ipc[label] = ($2 > 0 ? $3 / $2 : 0)
				missreq[label] = $4 / req
				missrate[label] = ($5 > 0 ? $4 / $5 * 100 : 0)
			}
			END {
				printf "%-10s %14s %10s %9s %15s %11s\n", "", "cycles/req", "vs jdk", "IPC",
					"cache-miss/req", "miss rate"
				for (i = 1; i <= n; i++) {
					l = order[i]
					printf "%-10s %14.0f %9.1f%% %9.3f %15.1f %10.1f%%\n", l, cpr[l],
						("jdk" in cpr ? (cpr[l] / cpr["jdk"] - 1) * 100 : 0), ipc[l], missreq[l],
						missrate[l]
				}
			}
		' "$OUT/results.tsv" "$OUT/perfstat.tsv"
		echo
		echo "cycles/req is the headline: total cycles perf counted, divided by the requests served"
		echo "in that same window (rps x window) -- unlike raw cycles or raw req/s, it is immune to"
		echo "both clock speed and how long the sample ran."
	else
		echo
		echo "=== perf stat: no counters captured (perf_event_paranoid? see docs/benchmark.md) ==="
	fi
fi

# perf c2c: HITM/load, the coherence evidence perf stat cannot give. Absolute counts are sample
# counts, not events -- compare the ratio and the ordering across schedulers, never the totals.
if [[ $PERF == c2c || $PERF == both ]]; then
	echo
	echo "=== perf c2c (cache-to-cache: a dirty line snooped out of another core) ==="
	found=0
	for config in $CONFIGS; do
		cf="$OUT/c2c-$config.c2c.txt"
		[ -f "$cf" ] || continue
		loads=$(grep -oP 'Load Operations\s*:\s*\K[\d,]+' "$cf" | tr -d , | head -1) || true
		[ -n "$loads" ] && [ "$loads" -gt 0 ] || continue
		local_hitm=$(grep -oP 'Load Local HITM\s*:\s*\K[\d,]+' "$cf" | tr -d , | head -1) || true
		remote_hitm=$(grep -oP 'Load Remote HITM\s*:\s*\K[\d,]+' "$cf" | tr -d , | head -1) || true
		hitm=$(( ${local_hitm:-0} + ${remote_hitm:-0} ))
		if [ "$found" -eq 0 ]; then
			printf '%-10s %14s %10s %12s\n' "" "load ops" "HITM" "HITM/load"
			found=1
		fi
		awk -v c="$config" -v l="$loads" -v h="$hitm" \
			'BEGIN { printf "%-10s %14d %10d %11.2f%%\n", c, l, h, h / l * 100 }'
	done
	if [ "$found" -eq 1 ]; then
		echo
		echo "Nothing is shared between actors here, so a HITM can only mean the scheduler resumed"
		echo "one on a core other than the one that dirtied its map. Expect carrier lowest, jdk"
		echo "highest -- that ordering is the mechanism this whole benchmark is about."
	else
		echo "no c2c counters captured (perf_event_paranoid? 'perf list | grep -i xsnp' to check the"
		echo "snoop events)"
	fi
fi
