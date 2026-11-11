# The code that jmh-user.sh, jmh-servlet.sh and jmh-pool.sh share. Sourced by them, with their
# arguments; not meant to be run on its own.
#
# A JMH script sets BENCH (user, servlet or pool), CLASS (its JMH class), PROPERTY (the prefix of its
# system properties) and RATES (its default rates), then sources this file.

common_usage() {
	cat <<'USAGE'

Options:
  --schedulers <list>     mpsc, mpsc-unpinned, mpsc-no-affinity, mpsc-unpinned-no-affinity, jdk,
                          jdk-pinned (needs one fork, and two warm-up iterations at least).
                          Default: mpsc-unpinned-no-affinity and jdk.
  --rates <list>          Requests per second, with commas.
  --profilers <list>      none, gc, comp, pauses, safepoints, perf, perfnorm, perfasm, perfc2c,
                          async, jfr. One JMH run each. jfr records the whole fork, warm-up
                          included. Default: none.
  --cpus-carriers <list>  Default: 2-7,10-13.
  --cpus-client <list>    One JMH thread per CPU. Default: 0,1,16,17.
  --connections <n>       The size of a pool of backend connections: one per group (user), one per
                          container (servlet). Default: 8. The pool benchmark has -p connections.
  --warmup-iterations <n> The rate goes up to its value over them. Default: 4.
  --warmup-seconds <s>    Per warm-up iteration. Default: 5.
  --iterations <n>        Iterations measured. Default: 3.
  --seconds <s>           Per measured iteration. Default: 10.
  --ramp <step>           A ramp in each fork, from the rate of --rates, <step> more at each measured
                          iteration. It goes on until a step is not kept up with: the fork ends
                          there, and the next fork starts again. Replaces --iterations.
  --async-lib <path>      Default: ~/work/projects/async-profiler/build/lib/libasyncProfiler.so.
  --hsdis-dir <dir>       The directory of hsdis-amd64.so, for perfasm. Default: ~/tools/hsdis.
  --out <dir>             Default: bench/<bench>-jmh-<date>-<time>, in the project.
  -- <args>               Given to JMH as they are. A JVM option for the fork: -jvmArgsAppend <option>,
                          one -jvmArgsAppend per option.

In <out>: <profiler>-<scheduler>-<rate>.log (the JMH output), .json, the profiler files in a
directory of the same name, console.txt, steps.txt: every STEP line, with the run it comes from,
and timeline.txt: the same for every TIMELINE line.
USAGE
}

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"

SCHEDULERS=mpsc-unpinned-no-affinity,jdk
POOLS=${POOLS:-}
PROFILERS=none
CARRIER_LIST=2-7,10-13
CLIENT_LIST=0,1,16,17
CONNECTIONS=
WARMUP_ITERATIONS=4
WARMUP_SECONDS=5
ITERATIONS=3
SECONDS_PER_ITERATION=10
RAMP=
RAMP_STEP=0
ASYNC_LIB=$HOME/work/projects/async-profiler/build/lib/libasyncProfiler.so
HSDIS_DIR=$HOME/tools/hsdis
OUT=
COMMAND="$0 $*"
while [ $# -gt 0 ]; do
	# Every option but -- and --help takes a value.
	case $1 in
		-- | -h | --help) ;;
		*) [ $# -ge 2 ] || { echo "$1: unknown option, or it needs a value (see --help)" >&2; exit 1; } ;;
	esac
	case $1 in
		--schedulers) SCHEDULERS=$2; shift 2 ;;
		--pools)
			[ "$BENCH" = pool ] || { echo "--pools only goes with jmh-pool.sh" >&2; exit 1; }
			POOLS=$2; shift 2 ;;
		--rates) RATES=$2; shift 2 ;;
		--profilers) PROFILERS=$2; shift 2 ;;
		--cpus-carriers) CARRIER_LIST=$2; shift 2 ;;
		--cpus-client) CLIENT_LIST=$2; shift 2 ;;
		--connections) CONNECTIONS=$2; shift 2 ;;
		--warmup-iterations) WARMUP_ITERATIONS=$2; shift 2 ;;
		--warmup-seconds) WARMUP_SECONDS=$2; shift 2 ;;
		--iterations) ITERATIONS=$2; shift 2 ;;
		--seconds) SECONDS_PER_ITERATION=$2; shift 2 ;;
		--ramp) RAMP=$2; shift 2 ;;
		--async-lib) ASYNC_LIB=$2; shift 2 ;;
		--hsdis-dir) HSDIS_DIR=$2; shift 2 ;;
		--out) OUT=$2; shift 2 ;;
		--) shift; break ;;
		-h | --help) sed -n '3,/^$/s/^# \{0,1\}//p' "$0"; common_usage; exit 0 ;;
		*) echo "unknown option: $1 (see --help)" >&2; exit 1 ;;
	esac
done

die() { echo "$*" >&2; exit 1; }

[ -z "$CONNECTIONS" ] || [ "$BENCH" != pool ] || die "jmh-pool.sh has -p connections, not --connections"
[[ -z $CONNECTIONS || $CONNECTIONS =~ ^[1-9][0-9]*$ ]] || die "--connections needs a number, not '$CONNECTIONS'"
OUT=${OUT:-$ROOT/bench/$BENCH-jmh-$(date +%Y%m%d-%H%M%S)}
if [ -n "$RAMP" ]; then
	[[ $RAMP =~ ^[1-9][0-9]*$ ]] || die "--ramp needs <step>, not '$RAMP'"
	RAMP_STEP=$RAMP
	# JMH needs a count. The ramp ends before it, at the first step not kept up with.
	ITERATIONS=1000
fi

# The CPUs of a list, one per line: 2-4,8 gives 2 3 4 8.
expand_cpus() {
	local part
	for part in ${1//,/ }; do
		if [[ $part == *-* ]]; then seq "${part%-*}" "${part#*-}"; else echo "$part"; fi
	done
}

for p in ${PROFILERS//,/ }; do
	case $p in
		none | gc | comp | pauses | safepoints | perf | perfnorm | perfasm | perfc2c | async | jfr) ;;
		*) die "unknown profiler '$p'" ;;
	esac
done
for s in ${SCHEDULERS//,/ }; do
	case $s in
		mpsc | mpsc-unpinned | mpsc-no-affinity | mpsc-unpinned-no-affinity | jdk | jdk-pinned) ;;
		*) die "unknown scheduler '$s'" ;;
	esac
done
if [[ ,$SCHEDULERS, == *,jdk-pinned,* ]]; then
	command -v taskset >/dev/null || die "jdk-pinned needs taskset"
	[ "$WARMUP_ITERATIONS" -ge 2 ] || die "jdk-pinned needs two warm-up iterations at least"
	# The workers are pinned in the first fork only. Two forks come from -f, or from a -p with
	# several values.
	prev=
	for arg in "$@"; do
		case $prev in
			-f) [ "$arg" = 1 ] || die "jdk-pinned only pins one fork: use -f 1" ;;
			-p) [[ $arg != *,* ]] || die "jdk-pinned only pins one fork: give -p one value ($arg)" ;;
		esac
		case $arg in
			-f[0-9]*) [ "$arg" = -f1 ] || die "jdk-pinned only pins one fork: use -f 1" ;;
		esac
		prev=$arg
	done
fi
for p in ${POOLS//,/ }; do
	case $p in
		lbd-lifo | abq | abq-fair | fair-lifo | fair-fifo) ;;
		*) die "unknown pool '$p'" ;;
	esac
done
[[ ,$PROFILERS, != *,async,* ]] || [ -f "$ASYNC_LIB" ] || die "no async-profiler at $ASYNC_LIB (--async-lib)"
[[ ,$PROFILERS, != *,perfasm,* ]] || [ -f "$HSDIS_DIR/hsdis-amd64.so" ] ||
	die "no hsdis-amd64.so in $HSDIS_DIR (--hsdis-dir): perfasm cannot show the assembly"

# backend_status <path>: GETs <path> from the backend on port 8081, and prints the status of the
# answer: 200, 404..., none if nothing listens, or unknown if no status came within two seconds.
backend_status() {
	local status=unknown
	{
		printf 'GET %s HTTP/1.1\r\nHost: localhost\r\n\r\n' "$1" >&3
		IFS=' ' read -r -t 2 _ status _ <&3 || status=unknown
	} 2>/dev/null 3<>/dev/tcp/127.0.0.1/8081 || { echo none; return; }
	echo "${status:-unknown}"
}

# Each backend answers only the path of its benchmark: the other backend gets a 404.
NEEDED=${BENCH/pool/user}
case $NEEDED in
	user) BACKEND_PATH=/user ;;
	servlet) BACKEND_PATH=/data?bytes=1 ;;
esac
case $(backend_status "$BACKEND_PATH") in
	200) ;;
	none) die "no backend on port 8081: start scripts/jmh-$NEEDED-start-backend.sh first" ;;
	404) die "the backend on port 8081 is not the $NEEDED one: stop it, then start scripts/jmh-$NEEDED-start-backend.sh" ;;
	*) die "the backend on port 8081 gave an unexpected answer: start scripts/jmh-$NEEDED-start-backend.sh" ;;
esac

[ "$(uname -s)" != Linux ] || scripts/lib/linux-setup.sh >/dev/null ||
	die "scripts/lib/linux-setup.sh failed: run it by hand to see why"

source "$ROOT/scripts/lib/loom-jdk.sh"
JAVA=$LOOM_JAVA
CP_FILE=$ROOT/examples/target/test-classpath.txt
[ -f "$CP_FILE" ] || die "no $CP_FILE: build the project first (mvn install)"
CP=$ROOT/examples/target/test-classes:$ROOT/examples/target/classes:$(cat "$CP_FILE")

PIN=()
if command -v taskset >/dev/null; then
	PIN=(taskset -c "$CARRIER_LIST")
fi
CARRIERS=$(expand_cpus "$CARRIER_LIST" | wc -l | tr -d " ")
CLIENTS=$(expand_cpus "$CLIENT_LIST" | wc -l | tr -d " ")
CARRIER_CPUS=($(expand_cpus "$CARRIER_LIST"))
JCMD=$(dirname "$JAVA")/jcmd
mkdir -p "$OUT"
# The console is also kept in the output directory.
exec > >(tee -a "$OUT/console.txt") 2>&1

# The forks run on the carrier CPUs (taskset), like Main in scripts/bench-user.sh. The JMH threads pin
# themselves to the client CPUs.
JVM_ARGS=(--enable-native-access=ALL-UNNAMED -Xms2g -Xmx2g -XX:+AlwaysPreTouch
	-D"$PROPERTY".carriers="$CARRIERS" -D"$PROPERTY".clientCpus="$CLIENT_LIST" -D"$PROPERTY".backendPort=8081)
[ -z "$CONNECTIONS" ] || JVM_ARGS+=(-Dbench.connections="$CONNECTIONS")

# prof_args <profiler> <dir>: the JMH options of a profiler, its files in <dir>.
prof_args() {
	case $1 in
		none | jfr) ;; # jfr: not the JMH profiler, see jmh().
		perfasm) echo "-prof perfasm:saveLog=true;saveLogTo=$2" ;;
		async) echo "-prof async:libPath=$ASYNC_LIB;output=flamegraph,text;dir=$2" ;;
		*) echo "-prof $1" ;;
	esac
}

# Reads the output of JMH, and prints one line per measured iteration as soon as it ends.
show_steps() {
	local line
	local fields=' rate=([0-9]+) .* dropped=([0-9]+).* achieved=([0-9]+).* server_us_per_req=([0-9.]+).* p50_us=([0-9.]+).* p99_us=([0-9.]+).* p999_us=([0-9.]+).* kept_up=(.*)'
	local params last=
	while IFS= read -r line; do
		if [[ $line == STEP*phase=measure* && $line =~ $fields ]]; then
			# The parameters of the run, before rate=: printed again when they change.
			params=${line#STEP }
			params=${params%% rate=*}
			[ "$params" = "$last" ] || echo "  $params"
			last=$params
			local text="  rate ${BASH_REMATCH[1]}: achieved ${BASH_REMATCH[3]}, dropped ${BASH_REMATCH[2]},"
			text+=" cpu ${BASH_REMATCH[4]} us/req, p50 ${BASH_REMATCH[5]}, p99 ${BASH_REMATCH[6]},"
			text+=" p99.9 ${BASH_REMATCH[7]} us, kept up ${BASH_REMATCH[8]}"
			# How late the clients sent: kept_up is no when it is above half the time between two
			# sends of a client.
			if [[ $line =~ lag_p99_us=([0-9.]+) ]]; then
				text+=", send lag p99 ${BASH_REMATCH[1]} us"
			fi
			# The share of sampled messages that ran on another carrier than the one before. The servlet
			# benchmark counts the messages of its fraud detectors.
			# -1: a fraud detector did not answer in time.
			if [[ $line =~ migrations_pct=-1 ]]; then
				text+=", migrations unknown"
			elif [[ $line =~ migrations_pct=([0-9.]+) ]]; then
				text+=", migrations ${BASH_REMATCH[1]}%"
			fi
			# Only the pool benchmark prints the wait for a connection.
			if [[ $line =~ take_p99_us=([0-9.]+).*take_max_us=([0-9.]+) ]]; then
				text+=", take p99 ${BASH_REMATCH[1]}, max ${BASH_REMATCH[2]} us"
			fi
			echo "$text"
		fi
	done
}

# jdk-pinned: each worker of the builtin scheduler, ForkJoinPool-<n>-worker-<k>, is pinned to the
# k-th carrier CPU. The workers are found in a thread dump. A thread dump stops the JVM, so this
# only runs during the warm-up. It starts when the first warm-up iteration ends.
# pin_jdk_workers <log> <seconds of warm-up left>
pin_jdk_workers() {
	local log=$1 left=$2 pid end line pinned=()
	until grep -qs '^STEP.*phase=warmup' "$log"; do sleep 0.1; done
	pid=$(pgrep -nf org.openjdk.jmh.runner.ForkedMain) || return 0
	end=$(($(date +%s) + left - 1))
	while sleep 1 && [ "$(date +%s)" -lt "$end" ]; do
		while read -r line; do
			# '"ForkJoinPool-1-worker-3" #42 [30559] ...': the OS thread id is in the brackets.
			[[ $line =~ ^\"ForkJoinPool-[0-9]+-worker-([0-9]+)\"\ \#[0-9]+\ \[([0-9]+)\] ]] || continue
			# A worker numbered above the carriers replaces or helps another: left alone.
			((BASH_REMATCH[1] <= ${#CARRIER_CPUS[@]})) || continue
			taskset -p -c "${CARRIER_CPUS[BASH_REMATCH[1] - 1]}" \
				"${BASH_REMATCH[2]}" >/dev/null 2>&1 && pinned[BASH_REMATCH[1]]=1
		done < <("$JCMD" "$pid" Thread.print 2>/dev/null || true)
	done
	echo "  pinned ${#pinned[@]} of ${#CARRIER_CPUS[@]} workers"
}

# jmh <name> <profiler> <scheduler> <rate> <warm-up iterations> <warm-up s> <iterations> <s>: one
# JMH run, its output in <name>.log.
jmh() {
	local name=$1 prof=$2 scheduler=$3 rate=$4 wi=$5 w=$6 i=$7 r=$8
	shift 8
	mkdir -p "$OUT/$name"
	local jvm=("${JVM_ARGS[@]}" -Xlog:gc:file="$OUT/$name/gc.log")
	# The profilers that sample need the debug info between safepoints.
	case $prof in jfr | async | perfasm) jvm+=(-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints) ;; esac
	# async-profiler loaded late, by JMH, crashes this JDK. Loaded at start, it is ready when JMH asks
	# for it. JMH loads it in both JVMs: its own, and the fork.
	local host=()
	if [ "$prof" = async ]; then
		jvm+=(-agentpath:"$ASYNC_LIB")
		host=(-agentpath:"$ASYNC_LIB")
	fi
	# JFR starts with the fork, and not at the first measured iteration: turning its events on throws
	# away the hot compiled code.
	[ "$prof" != jfr ] || jvm+=(-XX:StartFlightRecording=filename="$OUT/$name/recording.jfr",settings=profile)
	# comp only gives the JIT time. jit.log also says what compiles and what deopts, with the uptime
	# that the STEP lines also give.
	[ "$prof" != comp ] || jvm+=(-Xlog:jit+compilation=debug,deoptimization=debug:file="$OUT/$name/jit.log":uptime)
	# One -jvmArgsAppend per option. With one only, JMH splits it at the spaces. With several, JMH
	# keeps each as it is: a -jvmArgsAppend given after -- then adds an option.
	local append=() arg
	for arg in "${jvm[@]}"; do
		append+=(-jvmArgsAppend "$arg")
	done
	local env=()
	[ "$prof" != perfasm ] || env=(env "LD_LIBRARY_PATH=$HSDIS_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}")
	local pinner=
	if [ "$scheduler" = jdk-pinned ]; then
		rm -f "$OUT/$name.log"
		pin_jdk_workers "$OUT/$name.log" $(((wi - 1) * w)) &
		pinner=$!
	fi
	# shellcheck disable=SC2046 # the profiler options are words on purpose
	${env[@]+"${env[@]}"} ${PIN[@]+"${PIN[@]}"} "$JAVA" ${host[@]+"${host[@]}"} -cp "$CP" org.openjdk.jmh.Main "$CLASS" \
		-p scheduler="${scheduler/jdk-pinned/jdk}" -p rate="$rate" -p rampStep="$RAMP_STEP" -t "$CLIENTS" -wi "$wi" -w "${w}s" -i "$i" -r "${r}s" \
		"${append[@]}" \
		$(prof_args "$prof" "$OUT/$name") \
		-rf json -rff "$OUT/$name.json" "$@" 2>&1 | tee "$OUT/$name.log" | show_steps || [ "$RAMP_STEP" != 0 ] ||
		echo "  $name: JMH failed, see $name.log"
	# A ramp ends with a failed fork: only a run with no step measured says that JMH failed.
	[ "$RAMP_STEP" = 0 ] || grep -qs '^STEP.*phase=measure' "$OUT/$name.log" ||
		echo "  $name: no step measured, see $name.log"
	# A fork that dies early leaves the pinning waiting.
	if [ -n "$pinner" ]; then
		kill "$pinner" 2>/dev/null || true
		wait "$pinner" 2>/dev/null || true
	fi
	grep '^STEP' "$OUT/$name.log" | sed "s/^STEP/STEP run=$name/" >>"$OUT/steps.txt" || true
	grep '^TIMELINE' "$OUT/$name.log" | sed "s/^TIMELINE/TIMELINE run=$name/" >>"$OUT/timeline.txt" || true
}

{
	echo "date      $(date '+%Y-%m-%d %H:%M:%S %z')"
	echo "host      $(hostname), $(uname -sr)"
	echo "java      $("$JAVA" -version 2>&1 | sed -n 2p)"
	echo "cpus      carriers $CARRIER_LIST, clients $CLIENT_LIST"
	echo "runs      $BENCH, schedulers $SCHEDULERS, rates $RATES, profilers $PROFILERS${POOLS:+, pools $POOLS}${CONNECTIONS:+, connections $CONNECTIONS}"
	if [ "$RAMP_STEP" != 0 ]; then
		echo "fork      $WARMUP_ITERATIONS x ${WARMUP_SECONDS}s warm-up, ${SECONDS_PER_ITERATION}s per step, $RAMP_STEP more req/s at each one, until a step is not kept up with"
	else
		echo "fork      $WARMUP_ITERATIONS x ${WARMUP_SECONDS}s warm-up, $ITERATIONS x ${SECONDS_PER_ITERATION}s measured"
	fi
	echo "command   $COMMAND"
} | tee "$OUT/header.txt"

POOL_LIST=${POOLS:-none}
POOL_LIST=${POOL_LIST//,/ }
for prof in ${PROFILERS//,/ }; do
	for scheduler in ${SCHEDULERS//,/ }; do
		for rate in ${RATES//,/ }; do
			# Without --pools, one pass with no pool.
			for pool in $POOL_LIST; do
				name=$prof-$scheduler-$rate
				pool_args=()
				if [ "$pool" != none ]; then
					name=$prof-$scheduler-$pool-$rate
					pool_args=(-p pool="$pool")
				fi
				echo "$name ($(date +%T))"
				jmh "$name" "$prof" "$scheduler" "$rate" "$WARMUP_ITERATIONS" "$WARMUP_SECONDS" "$ITERATIONS" "$SECONDS_PER_ITERATION" \
					${pool_args[@]+"${pool_args[@]}"} "$@"
				if [ "$RAMP_STEP" != 0 ]; then
					grep "run=$name " "$OUT/steps.txt" 2>/dev/null | grep 'phase=measure' |
						sed -E 's/^STEP run=[^ ]+ (.*) rate=([0-9]+) .* kept_up=(.*)/\2 \3 \1/' |
						awk '
							# The parameters of the run, after the rate and kept_up.
							{ params = $0; sub(/^[^ ]+ [^ ]+ /, "", params) }
							# A new fork starts again from the first rate, or with other parameters.
							NR > 1 && ($1 <= prev || params != current) { report() }
							{ prev = $1; current = params }
							stopped { next }
							$2 == "yes" { last = $1; next }
							{ stopped = 1 }
							END { if (NR) report() }
							function report() {
								print "  fork " ++fork " (" current "): highest rate kept up, from the first step: " (last ? last : "none")
								last = 0; stopped = 0
							}' || true
				fi
			done
		done
	done
done
echo "results in $OUT"
