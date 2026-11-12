#!/usr/bin/env bash
#
# Load test of a backend alone (NioServer), with h2load: how many requests per second it answers,
# and its latency at a given rate. Writes <out>/summary.md, and the output of each step in <out>/.
#
#   scripts/bench-backend.sh                                  # as fast as it answers, 80 connections
#   scripts/bench-backend.sh --rates 200000,600000,1000000    # at fixed rates
#   scripts/bench-backend.sh --backend servlet --bytes 1024
#
# Prerequisites: h2load (nghttp2), the Loom JDK selected (`sdk env`) and the project built. On
# Linux, it first runs scripts/lib/linux-setup.sh, which needs sudo.

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: scripts/bench-backend.sh [options]

Starts one backend JVM, pinned on the backend CPUs. Then h2load, pinned on the client CPUs, sends
HTTP/1.1 requests, one step after the other. Each step: a warm-up, then the measure. The backend
stays up for all the steps.

Two modes:
  - without --rates: as fast as the answers come back. One step per number of connections.
  - with --rates: at a fixed rate. One step per rate, on --connections connections. Each
    connection sends rate / connections requests per second.

Default duration: one step of 20 s.

  --backend <name>       user (GET /user) or servlet (GET /data?bytes=N). Default: user.
  --bytes <n>            The body size asked of the servlet backend. Default: 256.
  --cpus-backend <list>  Default: 8,9,14,15,24,25,30,31.
  --reactors <n>         The backend's reactor threads. Default: one per backend CPU.
  --cpus-client <list>   h2load: one thread per CPU. Default: 0-7,10-13.
  --rates <list>         The steps: requests per second in all, comma-separated. Each one a
                         multiple of --connections.
  --connections <list>   h2load connections in all. Without --rates, a list makes one step per
                         number, comma-separated (16,64,256,1024). With --rates, one number.
                         Each one at least the number of client CPUs. Default: 80.
  --pipeline <n>         Requests sent ahead on a connection, without waiting for the answer.
                         Default: 1.
  --warmup <s>           Per step. Default: 10.
  --seconds <s>          Measured, per step. Default: 10.
  --out <dir>            Default: bench/backend-<date>-<time>, in the project.
USAGE
}

die() {
	echo "$(basename "$0"): $*" >&2
	exit 1
}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

BACKEND=user
BYTES=256
BACKEND_LIST=8,9,14,15,24,25,30,31
REACTORS=
CLIENT_LIST=0-7,10-13
RATES=
CONNECTIONS=80
PIPELINE=1
WARMUP=10
SECONDS_MEASURED=10
OUT=
COMMAND_LINE="$0 $*"
while [ $# -gt 0 ]; do
	case $1 in
		-h | --help) usage; exit 0 ;;
	esac
	[ $# -ge 2 ] || die "$1 needs a value"
	case $1 in
		--backend) BACKEND=$2 ;;
		--bytes) BYTES=$2 ;;
		--cpus-backend) BACKEND_LIST=$2 ;;
		--reactors) REACTORS=$2 ;;
		--cpus-client) CLIENT_LIST=$2 ;;
		--rates) RATES=$2 ;;
		--connections) CONNECTIONS=$2 ;;
		--pipeline) PIPELINE=$2 ;;
		--warmup) WARMUP=$2 ;;
		--seconds) SECONDS_MEASURED=$2 ;;
		--out) OUT=$2 ;;
		*) die "unknown option '$1' (try --help)" ;;
	esac
	shift 2
done

# The number of CPUs in a list like 2-7,10.
count_cpus() {
	tr , '\n' <<<"$1" | awk -F- '{ n += NF == 2 ? $2 - $1 + 1 : 1 } END { print n }'
}

CLIENT_THREADS=$(count_cpus "$CLIENT_LIST")
REACTORS=${REACTORS:-$(count_cpus "$BACKEND_LIST")}
if [ -n "$RATES" ]; then
	[[ $CONNECTIONS =~ ^[0-9]+$ ]] || die "--connections: one number with --rates, not '$CONNECTIONS'"
	STEPS=${RATES//,/ }
else
	STEPS=${CONNECTIONS//,/ }
fi
for n in "$BYTES" "$REACTORS" "$PIPELINE" "$WARMUP" "$SECONDS_MEASURED" ${CONNECTIONS//,/ } ${RATES//,/ }; do
	[[ $n =~ ^[0-9]+$ ]] || die "not a number: '$n'"
done
((BYTES > 0 && REACTORS > 0 && PIPELINE > 0 && SECONDS_MEASURED > 0)) ||
	die "--bytes, --reactors, --pipeline and --seconds must be above 0"
for c in ${CONNECTIONS//,/ }; do
	((c >= CLIENT_THREADS)) || die "--connections: $c is less than the $CLIENT_THREADS client CPUs"
done
for r in ${RATES//,/ }; do
	((r > 0 && r % CONNECTIONS == 0)) || die "--rates: $r is not a multiple of the $CONNECTIONS connections"
done

case $BACKEND in
	user)
		MAIN=io.github.pderop.looma.examples.user.BackendServer
		ARGS=(-Dbench.backendPort=8081 -Dbench.backendThreads="$REACTORS")
		URL=http://127.0.0.1:8081/user
		;;
	servlet)
		MAIN=io.github.pderop.looma.examples.servlet.ServletBackend
		ARGS=(-Xms1g -Xmx1g -XX:+AlwaysPreTouch -Dservlet.backendPort=8081 -Dservlet.backendThreads="$REACTORS")
		URL="http://127.0.0.1:8081/data?bytes=$BYTES"
		;;
	*) die "--backend: user or servlet, not '$BACKEND'" ;;
esac

command -v h2load >/dev/null || die "no h2load: install nghttp2 (apt install nghttp2-client, brew install nghttp2)"
[ "$(uname -s)" != Linux ] || scripts/lib/linux-setup.sh >/dev/null ||
	die "scripts/lib/linux-setup.sh failed: run it by hand to see why"
source "$ROOT/scripts/lib/loom-jdk.sh"
CP=${CP:-$(scripts/lib/classpath.sh)}

BACKEND_PIN=()
CLIENT_PIN=()
if command -v taskset >/dev/null; then
	BACKEND_PIN=(taskset -c "$BACKEND_LIST")
	CLIENT_PIN=(taskset -c "$CLIENT_LIST")
else
	echo "no taskset: nothing is pinned, a smoke test and not a measurement" >&2
fi

# Many connections: the default limit of open files can be too low, for h2load and the backend.
ulimit -n 65536 2>/dev/null || true

OUT=${OUT:-$ROOT/bench/backend-$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT"

# The CPU seconds a process has used so far: from /proc on Linux, from ps elsewhere.
cpu_seconds() {
	if [ -r "/proc/$1/stat" ]; then
		# utime and stime, in ticks, after the name in parentheses.
		sed 's/.*) //' "/proc/$1/stat" | awk -v clk="$(getconf CLK_TCK)" '{ printf "%.2f", ($12 + $13) / clk }'
	else
		# [hh:]mm:ss.cc
		ps -o time= -p "$1" | awk -F: '{ s = 0; for (i = 1; i <= NF; i++) s = s * 60 + $i; printf "%.2f", s }'
	fi
}

# log_stats <log>: the h2load log of one step, one request per line (start in microseconds since
# the epoch, status, microseconds until the end of the answer). Prints
# "mean p50 p99 p999 max with/span busiest": the latencies in microseconds, then how the starts
# spread: the milliseconds with at least one start, out of those from the first start to the last,
# and the most starts in one millisecond.
log_stats() {
	awk '
		{
			d = $3 + 0; count[d]++; sum += d; n++; if (d > max) max = d
			starts[int($1 / 1000)]++
		}
		END {
			if (n == 0) { print "- - - - - - -"; exit }
			split("0.50 0.99 0.999", q, " ")
			k = 1
			seen = 0
			for (d = 0; d <= max && k <= 3; d++) {
				seen += count[d]
				while (k <= 3 && seen >= q[k] * n) { p[k] = d; k++ }
			}
			first = -1
			for (ms in starts) {
				with++
				if (starts[ms] > busiest) busiest = starts[ms]
				if (first < 0 || ms + 0 < first) first = ms + 0
				if (ms + 0 > last) last = ms + 0
			}
			printf "%.0f %d %d %d %d %d/%d %d\n", sum / n, p[1], p[2], p[3], max, with, last - first + 1, busiest
		}' "$1"
}

# aligned_table: reads a header row then data rows, "| a | b |", and prints them as a Markdown table
# whose columns line up, numbers to the right. It reads well in a terminal and in a viewer.
aligned_table() {
	awk -F'|' '
		{
			for (i = 2; i < NF; i++) {
				v = $i
				gsub(/^ +| +$/, "", v)
				cell[NR, i] = v
				if (length(v) > width[i]) width[i] = length(v)
			}
			cols = NF - 1
			rows = NR
		}
		END {
			for (r = 1; r <= rows; r++) {
				line = "|"
				for (i = 2; i <= cols; i++) line = line sprintf(" %" width[i] "s |", cell[r, i])
				print line
				if (r > 1) continue
				line = "|"
				for (i = 2; i <= cols; i++) {
					dashes = ""
					for (k = 0; k <= width[i]; k++) dashes = dashes "-"
					line = line dashes ":|"
				}
				print line
			}
		}'
}

(exec 3<>/dev/tcp/127.0.0.1/8081) 2>/dev/null && die "port 8081 is in use: is another backend running?"
${BACKEND_PIN[@]+"${BACKEND_PIN[@]}"} "$LOOM_JAVA" "${ARGS[@]}" -Xlog:gc:file="$OUT/backend.gc.log":uptime \
	-cp "$CP" "$MAIN" >"$OUT/backend.out" 2>&1 &
BACKEND_PID=$!
trap 'kill "$BACKEND_PID" 2>/dev/null || true' EXIT
for _ in $(seq 100); do
	grep -qs listening "$OUT/backend.out" && break
	kill -0 "$BACKEND_PID" 2>/dev/null || die "the backend did not start: $(cat "$OUT/backend.out")"
	sleep 0.2
done
grep -qs listening "$OUT/backend.out" || die "the backend never said it was listening: see $OUT/backend.out"

steps=$(wc -w <<<"$STEPS" | tr -d ' ')
echo "backend benchmark: $BACKEND backend, $REACTORS reactors on CPUs $BACKEND_LIST; h2load on $CLIENT_LIST"
echo "about $(((steps * (WARMUP + SECONDS_MEASURED + 2) + 59) / 60)) minute(s), $steps step(s)"

ROWS=$OUT/rows.md
: >"$ROWS"
for step in $STEPS; do
	if [ -n "$RATES" ]; then
		c=$CONNECTIONS
		target=$step
		name=r$step
		rate_args=(--rps "$((step / c))")
		echo
		echo "== step: $step req/s, on $c connections ($((step / c)) req/s each), pipeline $PIPELINE"
	else
		c=$step
		target=max
		name=c$step
		rate_args=()
		echo
		echo "== step: $c connections, as fast as it answers, pipeline $PIPELINE"
	fi
	file=$OUT/h2load-$name.txt
	log=$OUT/h2load-$name.log
	before=$(cpu_seconds "$BACKEND_PID")
	# bash's time gives the wall, user and system seconds of h2load.
	TIMEFORMAT='%R %U %S'
	{ time ${CLIENT_PIN[@]+"${CLIENT_PIN[@]}"} h2load --h1 -t "$CLIENT_THREADS" -c "$c" -m "$PIPELINE" \
		${rate_args[@]+"${rate_args[@]}"} --warm-up-time="${WARMUP}s" -D "$SECONDS_MEASURED" \
		--log-file="$log" "$URL" >"$file" 2>&1; } 2>"$OUT/time-$name.txt" || die "h2load failed: see $file"
	after=$(cpu_seconds "$BACKEND_PID")
	kill -0 "$BACKEND_PID" 2>/dev/null || die "the backend died: see $OUT/backend.out"
	read -r wall user sys <"$OUT/time-$name.txt"
	client_cpu=$(awk -v u="$user" -v s="$sys" 'BEGIN { print u + s }')
	backend_cpu=$(awk -v a="$after" -v b="$before" 'BEGIN { print a - b }')
	# The log holds every request of the measure: one line each, so it is large. Only the
	# latencies are kept.
	read -r mean p50 p99 p999 max with busiest <<<"$(log_stats "$log")"
	rm -f "$log"
	if [ -n "$RATES" ]; then
		per_ms=$((step / 1000))
	else
		per_ms=-
	fi
	# What h2load says of the measure, then what its log says, then the cores.
	grep -E '^(finished in|requests:|status codes:)' "$file"
	echo "latency (us): mean $mean, p50 $p50, p99 $p99, p99.9 $p999, max $max"
	echo "starts: ${with%/*} of the ${with#*/} ms from the first start to the last had one or more; most in one ms: $busiest (expected per ms: $per_ms)"
	awk -v w="$wall" -v b="$backend_cpu" -v c="$client_cpu" \
		'BEGIN { printf "cores: backend %.2f, h2load %.2f (CPU seconds / %.1f s of wall time)\n", b / w, c / w, w }'
	# The lines of h2load used here:
	#   finished in 10.00s, 123456.70 req/s, 12.34MB/s
	#   requests: 100 total, 100 started, 100 done, 100 succeeded, 0 failed, 0 errored, 0 timeout
	#   status codes: 100 2xx, 0 3xx, 0 4xx, 0 5xx
	awk -v c="$c" -v target="$target" -v wall="$wall" -v client="$client_cpu" -v backend="$backend_cpu" \
		-v lat="$mean | $p50 | $p99 | $p999 | $max | $with | $busiest | $per_ms" '
		/^finished in/ { rate = $4 }
		/^requests:/ { total = $2 }
		/^status codes:/ { s2xx = $3 }
		END {
			printf "| %d | %s | %.0f | %d | %d | %s | %.2f | %.2f |\n", c, target, rate, total, total - s2xx, lat,
				backend / wall, client / wall
		}' "$file" >>"$ROWS"
done
kill "$BACKEND_PID" 2>/dev/null || true
wait "$BACKEND_PID" 2>/dev/null || true

{
	echo "# Backend benchmark"
	echo
	echo "\`\`\`"
	echo "date      $(date '+%Y-%m-%d %H:%M:%S %z')"
	echo "host      $(hostname -s), $(uname -sr)"
	echo "java      $("$LOOM_JAVA" -version 2>&1 | head -1)"
	echo "h2load    $(h2load --version | head -1)"
	echo "command   $COMMAND_LINE"
	echo "\`\`\`"
	echo
	echo "The $BACKEND backend ($MAIN), alone: $URL, $REACTORS reactors on CPUs $BACKEND_LIST."
	echo "h2load on CPUs $CLIENT_LIST, $CLIENT_THREADS threads, HTTP/1.1, $PIPELINE request(s) ahead per connection."
	if [ -n "$RATES" ]; then
		echo "Each step sends a fixed rate (target req/s) on $CONNECTIONS connections."
	else
		echo "Each step sends the next request when an answer comes back (target: max)."
	fi
	echo "Each step: $WARMUP s of warm-up, then $SECONDS_MEASURED s measured. The backend JVM stays up for all the steps."
	echo
	echo "- connections: the h2load connections of the step."
	echo "- target req/s: the rate asked, or max for as fast as it answers."
	echo "- req/s, requests, not 2xx (requests minus 2xx answers): from h2load, measure only. The"
	echo "  output of each step is in h2load-<step>.txt."
	echo "- mean, p50, p99, p99.9, max: the time of one request, from sending it to the end of its"
	echo "  answer, in microseconds, over every request of the measure (the h2load log, then deleted)."
	echo "- ms with starts: the milliseconds in which one request or more started, out of those from the"
	echo "  first start to the last (from the h2load log). busiest ms: the most requests started in one"
	echo "  millisecond."
	echo "  per ms: the rate asked divided by 1000, what a regular sender starts each millisecond."
	echo "  h2load sends in bursts: the further from regular, the more the latencies include waiting"
	echo "  that h2load itself caused."
	echo "- backend cores and h2load cores: their CPU seconds over the step, warm-up included,"
	echo "  divided by its wall time."
	echo
	{
		echo "| connections | target req/s | req/s | requests | not 2xx | mean us | p50 us | p99 us | p99.9 us | max us | ms with starts | busiest ms | per ms | backend cores | h2load cores |"
		cat "$ROWS"
	} | aligned_table
} >"$OUT/summary.md"
rm -f "$ROWS"
echo
# The table, with its header.
sed -n '/^| connections/,$p' "$OUT/summary.md"
echo
echo "backend benchmark: $OUT/summary.md"
