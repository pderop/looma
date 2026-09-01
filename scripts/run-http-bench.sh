#!/usr/bin/env bash
#
# Drives the HTTP/1.1 cache-locality example through nine runs: three configurations x three
# per-connection state sizes.
#
#   carrier   carrier-affine scheduler, carriers pinned one per CPU of the cpuset
#   stealing  same, with work stealing enabled -- shows locality being traded for balance
#   jdk       JDK builtin scheduler (1 VT per actor, no locality)
#
#   0 KiB     dispatch-only control -- no memory touched, so any gap that survives is scheduler
#             overhead, not locality
#   64 KiB    the intended operating point: 12 connections x 2 x 64 KiB = 1.5 MiB per carrier,
#             which is 75% of a P-core's 2 MiB private L2 -- tighter than it looks, see below
#   256 KiB   12 x 2 x 256 KiB = 6 MiB per carrier, well past the private L2 -- the carrier
#             advantage MUST collapse back towards the 0 KiB result here, or the run was not
#             measuring residency
#
# The server binary is IDENTICAL in all nine: which scheduler a run gets is decided by JVM
# flags (CARRIER_ARGS / JDK_ARGS below). A missing --enable-preview on a carrier run is caught
# after bind by assert_scheduler.
#
# WHAT IS MEASURED. Every connection is an actor owning a private byte array -- nothing is
# shared, so nothing here needs a lock. Each request walks that array along a pointer chase, hands
# a COPY of it to a forked virtual thread that reads it and blocks on the upstream call, then
# modifies it again when the answer comes back. The affine scheduler brings the actor's loop back
# to the core that already holds those lines; the JDK builtin resumes it anywhere, so lines this
# actor dirtied on one core must be snooped away from it by the next. Cost of a migration = number
# of dirty lines it drags across, which is why the gap should grow roughly LINEARLY with stateKiB
# until a carrier's connections no longer fit in its L2.
#
# Three points, not a sweep of many, carry the whole falsification argument: 0 KiB gives the
# scheduling-only baseline, 64 KiB is the operating point where the locality gap must appear, and
# 256 KiB is where that gap must collapse back onto the 0 KiB result -- if it does not collapse,
# the gap seen at 64 was not caused by the state fitting in cache.
#
# SIZE AGAINST connsPerCarrier x 2 x stateKiB, NOT connsPerCarrier x stateKiB: every ConnectionActor
# owns `state` AND a same-sized `snapshot` buffer, and both are resident. At the defaults that is
# 1.5 MiB of a 2 MiB private L2 at 64 KiB -- and the 11 other connections of a carrier run between
# two touches of any one array, so the reuse distance is already ~1.4 MiB. The 2026-08-30 campaign
# measured no locality term at this point and that under-sizing is the prime suspect
# (docs/performance.md). CONNS_PER_CARRIER=6, or STATE_KIB_POINTS="0 32 256", puts the operating
# point back at 768 KiB. Re-derive all of it if CONNS_PER_CARRIER changes.
#
# THE RULE THAT KEEPS THIS HONEST: every run gets the same cpuset and the same parallelism.
# Under taskset that mostly happens by itself -- availableProcessors() already reports the
# restricted count -- but the JDK side still bounds the virtual-thread scheduler pool so the
# two runs are strictly comparable.
#
# Defaults are sized for an i9-14900K: 8 P-cores with HT (CPU 0-15, siblings adjacent), 16
# E-cores (CPU 16-31), L2 2 MiB PRIVATE per P-core, a single 36 MiB L3. SERVER_CPUS therefore
# takes one thread per physical P-core, and the client gets the E-cores. On another machine,
# check `lscpu -e` and `lscpu -C` and re-derive both.
#
# THREE processes, three disjoint cpusets: the load generator, the server under test, and the
# mock upstream every request blocks on. The mock gets its own so that it can never preempt a
# carrier -- the run would then measure that instead. (At a non-zero MOCK_THINK_MICROS it also
# busy-polls inside the last millisecond before each response is due, see MockUpstream, which makes
# the separation not merely advisable but mandatory.) The mock is started ONCE and outlives every
# configuration below: it is not part of what is being compared.
#
# THE LOAD GENERATOR IS wrk/wrk2, the same pair the upstream project's own harness uses
# (benchmark-runner/scripts/run-benchmark.sh), reached the same way through jbang. Leave RATE
# empty and each connection runs a closed loop -- one request in flight, the next sent when the
# response lands -- which is what measures a saturation throughput. Set RATE=<req/s> and wrk2
# sends at that fixed rate whatever the server does, queueing client-side when it cannot keep up
# and reporting that queueing in the latency: the only way to get percentiles that are not
# flattered by coordinated omission. Pick a rate BELOW the closed-loop throughput, or the queue
# diverges and the run measures nothing.
#
# Prerequisites: jbang (or a native wrk/wrk2 via $WRK/$WRK2), perf, and the Loom JDK 28 selected
# -- `sdk env` in the project root puts it in JAVA_HOME, which is the same JDK Maven itself runs
# on (see scripts/loom-jdk.sh).

set -euo pipefail

# Resolved before the cd, so it survives being invoked by a relative path.
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

cd "$SCRIPT_DIR/.."

SERVER_CPUS=${SERVER_CPUS:-0,2,4,6,8,10,12,14}
CLIENT_CPUS=${CLIENT_CPUS:-16-27}
PORT=${PORT:-8080}
# FAST=1 collapses the campaign into a smoke run that fits in about two minutes. It still starts
# every one of the nine runs, so it proves the whole path works end to end and shows roughly where
# each configuration lands -- but the three perf c2c captures are skipped, and a 5s closed loop is
# far too short to compare schedulers with: run-to-run variance at that window is the same size as
# the effect being measured. NOTHING measured under FAST=1 belongs in a report. The defaults
# (30s windows plus three c2c captures) are what any quoted number must come from.
FAST=${FAST:-0}
if [ "$FAST" = 1 ]; then
	DURATION=${DURATION:-5}
	WARMUP=${WARMUP:-2}
else
	DURATION=${DURATION:-30}
	WARMUP=${WARMUP:-5}
fi
CLIENT_THREADS=${CLIENT_THREADS:-2}
# Empty: wrk, closed loop, max throughput. Set to a req/s figure: wrk2 -R, open loop, latencies.
RATE=${RATE:-}
# The generators. A native binary on PATH wins; otherwise jbang, exactly as upstream launches
# them. Both are overridable (WRK=/path/to/wrk).
#
# They are NOT interchangeable: wrk2 requires -R and refuses to start without it, so pointing
# $WRK at a wrk2 binary makes every closed-loop run fail instantly. The check after the warm-up
# in run() is what catches that -- and anything else that leaves the client with no result.
if [ -z "${WRK:-}" ]; then
	if command -v wrk >/dev/null; then WRK=wrk; else WRK="jbang wrk@hyperfoil"; fi
fi
if [ -z "${WRK2:-}" ]; then
	if command -v wrk2 >/dev/null; then WRK2=wrk2; else WRK2="jbang wrk2@hyperfoil"; fi
fi
# The falsification axis, in KiB of state PER CONNECTION. Size it against the per-carrier
# footprint, connsPerCarrier x 2 x stateKiB (state + snapshot): 0 is the dispatch-only control;
# 64 is the intended operating point (1.5 MiB per carrier, 75% of a P-core's 2 MiB private L2);
# 256 is 6 MiB per carrier, well past it, where the carrier advantage must collapse back onto the
# 0 KiB result. See the header comment for the falsification argument and for why 64 may be too
# tight already.
STATE_KIB_POINTS=${STATE_KIB_POINTS:-"0 64 256"}
# The three perf c2c captures need ONE fixed operating point, not a set of points, so they derive
# it from the middle of STATE_KIB_POINTS rather than carrying a separate knob that could drift
# out of step with it.
read -ra STATE_KIB_POINTS_ARRAY <<<"$STATE_KIB_POINTS"
if [ ${#STATE_KIB_POINTS_ARRAY[@]} -eq 0 ]; then
	# Indexing an empty array below aborts under `set -u` with nothing but a line number.
	echo "STATE_KIB_POINTS is empty -- it needs at least one size, e.g. STATE_KIB_POINTS=\"0 64 256\"" >&2
	exit 1
fi
C2C_STATE_KIB=${STATE_KIB_POINTS_ARRAY[$((${#STATE_KIB_POINTS_ARRAY[@]} / 2))]}
# 8 carriers x 12 connections = 96 connections, in the neighbourhood of the 100 the upstream
# harness uses. Connections are placed round-robin over the carriers and each owns its own state
# array plus a same-sized snapshot buffer, so a carrier keeps connsPerCarrier x 2 x stateKiB
# resident; raising this raises the per-carrier footprint just as much as raising stateKiB does.
CONNS_PER_CARRIER=${CONNS_PER_CARRIER:-12}
MOCK_CPUS=${MOCK_CPUS:-28-31}
MOCK_PORT=${MOCK_PORT:-8081}
MOCK_THREADS=${MOCK_THREADS:-2}
# Fixed and equal on both sides; see start_server. Large enough that a young collection is rare
# rather than continuous -- the connection actors reuse their snapshot buffers, so what is left to
# allocate per request is Jackson's and the codec's, not the state arrays.
HEAP=${HEAP:-2g}
# The upstream think time, in microseconds, and with it the single most important sizing decision in
# this harness: it sets how much of a request is spent WAITING rather than working, and a scheduler
# can only be seen in the working half. Per-connection concurrency is 1, so a request costs
# (think + cpu), and the largest ratio two schedulers can ever show IN THROUGHPUT is
# (think + cpu_slow) / (think + cpu_fast), never cpu_slow/cpu_fast. Every microsecond of think time
# is dilution of the thing being measured.
#
# 0 is therefore the default: the mock answers as soon as it has read the request, the wait collapses
# to the loopback round trip the server has to pay anyway, and the scheduler difference is as visible
# in rps as it can be made to be.
#
# What 0 does NOT remove is the reason the mock exists at all. The server still makes a REAL blocking
# socket call, so its continuation still parks on the per-carrier sub-poller rather than on the JDK's
# timer queue -- that path is what the whole locality argument rests on, and a Thread.sleep would not
# exercise it. See MockUpstream's javadoc.
#
# Two consequences of 0 worth knowing before reading a run:
#   - THE MOCK STOPS SPINNING. Its reactor only busy-polls inside the last millisecond before a
#     response is due (MockUpstream.SPIN_THRESHOLD_NANOS); with no deadline to wait for, it blocks in
#     select() instead. It still gets a cpuset of its own -- it must not preempt a carrier -- but it
#     no longer burns a core to do nothing.
#   - THE MOCK IS NOW MUCH CLOSER TO BEING THE BOTTLENECK, because the server asks for responses as
#     fast as it can produce them. Nothing prints the concurrency ceiling -- work it out by hand,
#     connections / upstreamRoundTrip, taking the round trip from the mock's own latency (the
#     client's avg latency at stateKiB=0 is a usable upper bound for it). Do that at EVERY point,
#     not only at stateKiB=0, and raise MOCK_THREADS before concluding anything from a run whose
#     rps sits on it.
#
# Raise it to model a genuinely slow backend -- and then read cycles/request rather than rps: at
# 1000us, ~40-70us of carrier work sits behind ~1.07ms of idle waiting, so a scheduler twice as
# expensive in cycles shows up as ~5% in rps. That is exactly how a real difference hides.
MOCK_THINK_MICROS=${MOCK_THINK_MICROS:-0}
OUT=${OUT:-target/http-bench/$(date +%Y%m%d-%H%M%S)}

source "$SCRIPT_DIR/loom-jdk.sh"
JAVA="$LOOM_JAVA"
SCHEDULER=io.netty.loom.scheduler.NettyScheduler
TOPOLOGY=io.netty.loom.topology.LinuxCarrierTopology

# The number of CPUs in a cpuset spec ("0,2,4", "16-27", or a mix of both).
#
# Pure bash on purpose: this runs before the `command -v` guards below, and every prerequisite
# reached before those guards is one the harness cannot report cleanly. An interpreter missing
# here would leave CARRIERS empty and feed it to -Dio.netty.loom.schedulers=, which is a broken run
# rather than a refused one.
count_cpus() {
	local spec=$1 total=0 part lo hi
	local IFS=,
	for part in $spec; do
		if [[ $part == *-* ]]; then
			lo=${part%-*}
			hi=${part#*-}
			total=$((total + hi - lo + 1))
		else
			total=$((total + 1))
		fi
	done
	echo "$total"
}

# Carriers and virtual-thread scheduler parallelism are all set to it, so the three runs are
# strictly comparable.
CARRIERS=$(count_cpus "$SERVER_CPUS")
if [ "$CARRIERS" -lt 1 ]; then
	echo "SERVER_CPUS=\"$SERVER_CPUS\" names no CPU" >&2
	exit 1
fi

# The ONLY difference between the runs. Both sides bound the same number of threads to the same
# cpuset; the carrier side additionally needs the preview flag and native access, because that is
# what the scheduler module is built with and what its topology probe calls.
CARRIER_ARGS=(
	--enable-preview
	--enable-native-access=ALL-UNNAMED
	-Djdk.virtualThreadScheduler.implClass="$SCHEDULER"
	-Dio.netty.loom.schedulers="$CARRIERS"
	-Dio.netty.loom.topology="$TOPOLOGY"
)

# No --enable-preview: without it the scheduler jar on the classpath fails to link, ServiceLoader
# discovery skips it (see ActorSchedulers) and the JDK builtin is what is left -- which is exactly
# the configuration this run wants. One pool: the JDK virtual-thread scheduler carries every
# actor loop and every forked VT.
JDK_ARGS=(
	-Djdk.virtualThreadScheduler.parallelism="$CARRIERS"
	-Djdk.virtualThreadScheduler.maxPoolSize="$CARRIERS"
)

command -v "${WRK%% *}" >/dev/null || { echo "${WRK%% *} not found (install jbang, or set \$WRK)" >&2; exit 1; }
if [ -n "$RATE" ]; then
	command -v "${WRK2%% *}" >/dev/null || { echo "${WRK2%% *} not found (install jbang, or set \$WRK2)" >&2; exit 1; }
fi
command -v taskset >/dev/null || { echo "taskset not found" >&2; exit 1; }

mkdir -p "$OUT"

# Asked of Maven rather than assembled by hand: since httpclient5 arrived the classpath is no
# longer just the two project jars, and a hand-written target/*.jar wildcard would also pick
# up the shaded benchmarks jar, which carries its own copy of the scheduler. The helper builds
# the project if needed and prints nothing but the classpath.
CP=$("$SCRIPT_DIR/examples-classpath.sh")

echo "output dir : $OUT"
echo "server cpus: $SERVER_CPUS ($CARRIERS carriers)"
echo "client cpus: $CLIENT_CPUS"
echo "mock cpus  : $MOCK_CPUS (port $MOCK_PORT, ${MOCK_THINK_MICROS}us)"
# Stated up front and left in the log next to the numbers: a FAST directory holds short windows
# and no c2c capture, and must not be read months later as if it were a full campaign.
echo "window     : ${WARMUP}s warm-up + ${DURATION}s measured$([ "$FAST" = 1 ] && echo " (FAST=1: smoke run, not a result)")"
echo

# The mock upstream: started once, before any measured run, and killed on exit however this
# script ends. Every server run probes it at startup and refuses to bind without it, so a
# silently-dead mock cannot produce a full set of plausible-looking results.
start_mock() {
	taskset -c "$MOCK_CPUS" "$JAVA" \
		-Dmock.port="$MOCK_PORT" -Dmock.threads="$MOCK_THREADS" -Dmock.thinkMicros="$MOCK_THINK_MICROS" \
		-cp "$CP" io.github.pderop.looma.examples.http.MockUpstream >"$OUT/mock.log" 2>&1 &
	MOCK_PID=$!

	for _ in $(seq 1 100); do
		if grep -q "mock upstream listening" "$OUT/mock.log" 2>/dev/null; then
			return 0
		fi
		if ! kill -0 "$MOCK_PID" 2>/dev/null; then
			echo "mock upstream failed to start; see $OUT/mock.log" >&2
			tail -20 "$OUT/mock.log" >&2
			exit 1
		fi
		sleep 0.2
	done
	echo "mock upstream did not report listening within 20s" >&2
	exit 1
}

cleanup() {
	kill "${SERVER_PID:-}" 2>/dev/null || true
	kill "${MOCK_PID:-}" 2>/dev/null || true
}
trap cleanup EXIT

# Everything about the machine that can quietly turn a campaign into noise, written INTO the output
# directory rather than printed. warn_if_turbo() and warn_if_paranoid() below only warn, and they
# warn on a stderr the run directory does not capture -- so a directory of results could never
# afterwards be audited for the settings most able to fake a result, or to erase one. Written before the first run, so it describes the machine
# the numbers next to it came from.
record_environment() {
	local env_file="$OUT/env.txt"
	{
		echo "date        : $(date '+%Y-%m-%dT%H:%M:%S%z')"
		echo "host        : $(uname -n)  kernel $(uname -r)"
		echo "jdk         : $("$JAVA" -version 2>&1 | head -1 || true)"
		echo
		echo "server cpus : $SERVER_CPUS ($CARRIERS carriers)"
		echo "client cpus : $CLIENT_CPUS"
		echo "mock cpus   : $MOCK_CPUS (think ${MOCK_THINK_MICROS}us, $MOCK_THREADS threads)"
		echo "window      : ${WARMUP}s warm-up + ${DURATION}s measured${RATE:+, rate $RATE}  (FAST=$FAST)"
		echo "workload    : stateKiB \"$STATE_KIB_POINTS\", connsPerCarrier $CONNS_PER_CARRIER, heap $HEAP"
		echo
		# The two settings machine-prep.md step 2 exists for. "unreadable" is a real answer, not an
		# error: a kernel without intel_pstate has no no_turbo file at all.
		if [ -r /sys/devices/system/cpu/intel_pstate/no_turbo ]; then
			echo "no_turbo    : $(cat /sys/devices/system/cpu/intel_pstate/no_turbo)  (1 = turbo off, which is what a campaign wants)"
		else
			echo "no_turbo    : unreadable (no intel_pstate)"
		fi
		local governors
		governors=$(cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null | sort -u | tr '\n' ' ' || true)
		echo "governors   : ${governors:-unreadable}"
		echo "paranoid    : $(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null || echo unreadable)"
		echo
		echo "== lscpu -C =="
		lscpu -C 2>/dev/null || echo "(lscpu unavailable)"
		echo
		echo "== lscpu -e =="
		lscpu -e 2>/dev/null || echo "(lscpu unavailable)"
	} >"$env_file" 2>&1
	echo "environment: $env_file"
}

warn_if_turbo() {
	local no_turbo=/sys/devices/system/cpu/intel_pstate/no_turbo
	if [ -r "$no_turbo" ] && [ "$(cat $no_turbo)" != "1" ]; then
		cat >&2 <<-MSG
		WARNING: turbo is enabled. Run-to-run variance and per-core frequency asymmetry (on a
		14900K, cores 6-7 boost to 6.0 GHz against 5.7 for the rest) will show up as if it were
		signal. For the A/B comparison:
		    sudo cpupower frequency-set -g performance
		    echo 1 | sudo tee $no_turbo
		Then do ONE confirmation run with turbo back on.
		MSG
	fi
}

# Warns rather than sets it: the run is still valid without perf, it just loses cycles/request --
# and this script never touches sysctls, for the same reason it never runs cpupower.
warn_if_paranoid() {
	local paranoid_file=/proc/sys/kernel/perf_event_paranoid paranoid
	paranoid=$(cat "$paranoid_file" 2>/dev/null || true)
	# A non-numeric read means the file is absent or unreadable (a container, a non-Linux kernel),
	# which is not something to warn about -- only a value that is really too high is.
	[[ $paranoid =~ ^-?[0-9]+$ ]] || return 0
	if [ "$paranoid" -gt 1 ]; then
		cat >&2 <<-MSG
		WARNING: perf_event_paranoid is $paranoid. perf stat and perf c2c will produce nothing, so
		the run loses cycles/request -- the headline metric -- and the HITM tables, without failing:
		    sudo sysctl kernel.perf_event_paranoid=1
		    perf list | grep -i mem_load    # cpu_core/mem_load_retired.l2_hit|l3_hit/ must exist
		    perf list | grep -i xsnp        # the snoop events perf c2c needs
		MSG
	fi
}

# start_server <label> <stateKiB> <flags...>
start_server() {
	local label=$1 state_kib=$2
	shift 2

	local args=(
		# Identical on both sides, so the "only the scheduler differs" rule below still holds.
		# A fixed heap keeps G1 from resizing mid-run, and pre-touching keeps first-touch page
		# faults out of the measured window -- both would otherwise land wherever the run happened
		# to warm up and be read as scheduler noise.
		-Xms"$HEAP" -Xmx"$HEAP" -XX:+AlwaysPreTouch
		-Dhttp.port="$PORT"
		-Dhttp.stateKiB="$state_kib"
		-Dhttp.mockUrl="http://127.0.0.1:$MOCK_PORT/fruits"
	)

	args+=("$@")

	taskset -c "$SERVER_CPUS" "$JAVA" "${args[@]}" -cp "$CP" \
		io.github.pderop.looma.examples.http.HttpBenchMain >"$OUT/$label.server.log" 2>&1 &
	SERVER_PID=$!

	for _ in $(seq 1 100); do
		if grep -q "listening on port" "$OUT/$label.server.log" 2>/dev/null; then
			return 0
		fi
		if ! kill -0 "$SERVER_PID" 2>/dev/null; then
			echo "server failed to start; see $OUT/$label.server.log" >&2
			tail -20 "$OUT/$label.server.log" >&2
			exit 1
		fi
		sleep 0.2
	done
	echo "server did not report listening within 20s" >&2
	exit 1
}

stop_server() {
	kill "$SERVER_PID" 2>/dev/null || true
	wait "$SERVER_PID" 2>/dev/null || true
}

connections() {
	echo $((CARRIERS * CONNS_PER_CARRIER))
}

# Prints the load-generator command for the measured run: wrk when RATE is empty, wrk2 -R when it
# is not. Printed rather than run so the exact line lands in the output directory next to what it
# produced.
client_command() {
	local conns=$1
	if [ -n "$RATE" ]; then
		echo "$WRK2 -t $CLIENT_THREADS -c $conns -d ${DURATION}s -R $RATE --latency"
	else
		echo "$WRK -t $CLIENT_THREADS -c $conns -d ${DURATION}s"
	fi
}

# assert_scheduler <label> <expected class simple name>
# A missing --enable-preview on a carrier run, or a classpath that lets Franz's provider load
# when the jdk control is intended, silently produces a full set of plausible numbers under the
# wrong label. The expected class is a positional parameter, not an appended one: run() and c2c()
# forward their residual "$@" as JVM flags, so a value appended at the end would land on the java
# command line and the server would refuse to start.
assert_scheduler() {
	local label=$1 expected=$2 line
	line=$(grep -m1 '^scheduler=' "$OUT/$label.server.log" || true)
	case $line in
		*"$expected"*) return 0 ;;
	esac
	echo "$label was configured for $expected but the server reports: ${line:-<no scheduler line>}" >&2
	stop_server
	exit 1
}

# run <label> <stateKiB> <expected scheduler simple name> <flags...>
run() {
	local label=$1 state_kib=$2 expected=$3
	shift 3
	local conns
	conns=$(connections)

	echo "== $label (stateKiB=$state_kib conns=$conns${RATE:+ rate=$RATE})"
	start_server "$label" "$state_kib" "$@"
	assert_scheduler "$label" "$expected"

	# A separate warm-up run, not a client flag: neither wrk nor wrk2 has one, and the JIT has to
	# have compiled the request path before any counter is read.
	taskset -c "$CLIENT_CPUS" $WRK -t "$CLIENT_THREADS" -c "$conns" -d "${WARMUP}s" \
		"http://127.0.0.1:$PORT/hot" >"$OUT/$label.warmup.txt" 2>&1 || true

	# The warm-up doubles as the check that the client works at all. Without it a client that
	# refuses to start -- a wrong binary, a bad flag, a closed port -- leaves every run of the
	# campaign with an empty result file and a perf capture of an idle server, which looks exactly
	# like a completed run. Fail here, loudly, rather than produce a directory of plausible
	# nothing.
	if ! grep -q 'Requests/sec' "$OUT/$label.warmup.txt"; then
		echo "load generator produced no result -- see $OUT/$label.warmup.txt:" >&2
		sed -n '1,8p' "$OUT/$label.warmup.txt" >&2
		echo "  command was: $WRK -t $CLIENT_THREADS -c $conns -d ${WARMUP}s" >&2
		stop_server
		exit 1
	fi

	client_command "$conns" >"$OUT/$label.client.cmd"
	# shellcheck disable=SC2046
	taskset -c "$CLIENT_CPUS" $(client_command "$conns") "http://127.0.0.1:$PORT/hot" \
		>"$OUT/$label.client.txt" 2>&1 &
	local client_pid=$!

	# task-clock is what makes this readable without trusting the machine's frequency: CPU-time
	# divided by (window x carriers) is the occupancy, and cycles/task-clock is the average clock
	# the run ACTUALLY got. Without it, comparing cycles/s between runs silently assumes every run
	# ran at the same frequency -- true only if turbo is off, which is exactly what a result cannot
	# afford to assume. longest_lat_cache.miss separates "L2 miss caught by L3" from "descent to
	# DRAM", which mem_load_retired.l3_hit alone cannot.
	# Counters over the steady state only: the warm-up already ran, so two seconds in is enough,
	# and a second of margin is left at the end so perf never outlives the client it is counting.
	# Computed rather than inlined because a short DURATION (FAST=1, or an explicit override) can
	# leave no steady state at all, and perf must not be asked to sleep a negative number of
	# seconds -- it would fail the run rather than skip the counters.
	local steady_window=$((DURATION - 4))
	sleep 2
	if [ "$steady_window" -ge 1 ]; then
		perf stat -p "$SERVER_PID" \
			-e task-clock,cpu_core/cycles/,cpu_core/instructions/,cpu_core/mem_load_retired.l2_hit/,cpu_core/mem_load_retired.l3_hit/,cpu_core/longest_lat_cache.miss/ \
			-- sleep "$steady_window" >"$OUT/$label.perfstat.txt" 2>&1 || \
			echo "  (perf stat unavailable -- check perf_event_paranoid and 'perf list | grep -i mem_load')" >&2
	else
		echo "  (no steady-state window at DURATION=$DURATION -- skipping counters, so this run has no cycles/request)" >&2
	fi

	wait "$client_pid" || true
	grep -E "requests in|Requests/sec|Transfer/sec|Latency|Socket errors|Non-2xx" "$OUT/$label.client.txt" || true
	stop_server
	echo
}

# c2c <label> <expected scheduler simple name> <flags...>
# The decisive evidence: HITM should climb carrier < stealing < jdk.
c2c() {
	local label=$1 expected=$2
	shift 2
	command -v perf >/dev/null || return 0
	echo "== $label perf c2c"
	start_server "$label" "$C2C_STATE_KIB" "$@"
	assert_scheduler "$label" "$expected"
	# Always the closed loop here: this run is about which cache lines bounce, and a rate limit
	# would only change how many samples perf gets.
	taskset -c "$CLIENT_CPUS" $WRK -t "$CLIENT_THREADS" -c "$(connections)" -d 20s \
		"http://127.0.0.1:$PORT/hot" >"$OUT/$label.client.txt" 2>&1 &
	local client_pid=$!
	sleep 5
	perf c2c record -p "$SERVER_PID" -o "$OUT/$label.c2c.data" -- sleep 10 >/dev/null 2>&1 && \
		perf c2c report -i "$OUT/$label.c2c.data" --stdio >"$OUT/$label.c2c.txt" 2>&1 || \
		echo "  (perf c2c unavailable -- 'perf list | grep -i xsnp' to check the snoop events)" >&2
	wait "$client_pid" || true
	stop_server
	echo
}

record_environment
warn_if_turbo
warn_if_paranoid
start_mock

# The nine runs: three configurations x three sizes, one uniform label per run
# (<configuration>-<size>kib). 0 KiB is the dispatch-only control, 64 KiB the operating point,
# 256 KiB the L2-defeating point -- see the header comment for what each compares against.
for kib in $STATE_KIB_POINTS; do
	run "carrier-${kib}kib"  "$kib" "NettyActorScheduler" "${CARRIER_ARGS[@]}"
	run "stealing-${kib}kib" "$kib" "NettyActorScheduler" "${CARRIER_ARGS[@]}" -Dio.netty.loom.workstealing.enabled=true
	run "jdk-${kib}kib"      "$kib" "JdkActorScheduler"   "${JDK_ARGS[@]}"
done

# The c2c captures are 35s of wall clock each and are pure diagnosis -- they explain a gap the
# nine runs above have already shown. A smoke run has no gap worth explaining, so FAST=1 drops
# them; that is most of what buys the two-minute budget.
if [ "$FAST" = 1 ]; then
	echo "== skipping all three perf c2c captures (FAST=1)"
	echo
else
	c2c "c2c-carrier"  "NettyActorScheduler" "${CARRIER_ARGS[@]}"
	c2c "c2c-stealing" "NettyActorScheduler" "${CARRIER_ARGS[@]}" -Dio.netty.loom.workstealing.enabled=true
	c2c "c2c-jdk"      "JdkActorScheduler"   "${JDK_ARGS[@]}"
fi

echo "done -- results in $OUT"
if [ "$FAST" = 1 ]; then
	echo "FAST=1: ${DURATION}s measurement windows and no c2c captures. This run says the harness"
	echo "works and roughly where each configuration lands; it does NOT compare schedulers. Re-run"
	echo "without FAST for anything quoted."
fi
echo "headline metric is cycles/request (cpu_core/cycles/ from *.perfstat.txt divided by the"
echo "request count in *.client.txt), not raw rps: it absorbs frequency drift."
if [ -z "$RATE" ]; then
	echo "closed loop: the latency figures above are optimistic by construction (coordinated"
	echo "omission). Re-run with RATE=<req/s> below this throughput for percentiles that mean something."
fi
