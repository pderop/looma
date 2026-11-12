# The code that jmh-user-start-backend.sh and jmh-servlet-start-backend.sh share. Sourced by them,
# with their arguments; not meant to be run on its own.
#
# A start script sets BENCH (user or servlet), MAIN (the backend class), PROPERTY (the prefix of its
# system properties) and HEAP (its heap options, maybe none), then sources this file.

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

backend_usage() {
	cat <<'USAGE'

Options:
  --cpus-backend <list>  Default: 8,9,14,15,24,25,30,31.
  --reactors <n>         The backend's reactor threads, on those CPUs. Default: one per CPU.
  --warmup <s>           A throwaway JMH fork sends load for that long. 0 for none. Default: 20.
  --out <dir>            Default: bench/<bench>-jmh-backend-<date>-<time>, in the project.
  -- <args>              Given to the JMH script for the warm-up, as --rates or --cpus-client.

In <out>: backend.out, gc.log (the backend's collections), warmup.log (the output of the warm-up),
and its files in warmup/.
USAGE
}

BACKEND_LIST=8,9,14,15,24,25,30,31
REACTORS=
WARMUP=20
OUT=
while [ $# -gt 0 ]; do
	# Every option but -- and --help takes a value.
	case $1 in
		-- | -h | --help) ;;
		*) [ $# -ge 2 ] || { echo "$1: unknown option, or it needs a value (see --help)" >&2; exit 1; } ;;
	esac
	case $1 in
		--cpus-backend) BACKEND_LIST=$2; shift 2 ;;
		--reactors)
			[[ $2 =~ ^[1-9][0-9]*$ ]] || { echo "--reactors needs a number, not '$2'" >&2; exit 1; }
			REACTORS=$2; shift 2 ;;
		--warmup) WARMUP=$2; shift 2 ;;
		--out) OUT=$2; shift 2 ;;
		--) shift; break ;;
		-h | --help) sed -n '3,/^$/s/^# \{0,1\}//p' "$0"; backend_usage; exit 0 ;;
		*) echo "unknown option: $1 (see --help)" >&2; exit 1 ;;
	esac
done

die() { echo "$*" >&2; exit 1; }

# One reactor per backend CPU, unless --reactors says otherwise.
REACTORS=${REACTORS:-$(tr , '\n' <<<"$BACKEND_LIST" | awk -F- '{ n += NF == 2 ? $2 - $1 + 1 : 1 } END { print n }')}
ARGS=(${HEAP[@]+"${HEAP[@]}"} -D"$PROPERTY".backendPort=8081 -D"$PROPERTY".backendThreads="$REACTORS")
OUT=${OUT:-$ROOT/bench/$BENCH-jmh-backend-$(date +%Y%m%d-%H%M%S)}

source "$ROOT/scripts/lib/loom-jdk.sh"
CP_FILE=$ROOT/examples/target/test-classpath.txt
[ -f "$CP_FILE" ] || die "no $CP_FILE: build the project first (mvn install)"
CP=$ROOT/examples/target/test-classes:$ROOT/examples/target/classes:$(cat "$CP_FILE")

PIN=()
if command -v taskset >/dev/null; then
	PIN=(taskset -c "$BACKEND_LIST")
fi
mkdir -p "$OUT"

(exec 3<>/dev/tcp/127.0.0.1/8081) 2>/dev/null && die "port 8081 is in use: is another backend running?"
${PIN[@]+"${PIN[@]}"} "$LOOM_JAVA" "${ARGS[@]}" -Xlog:gc:file="$OUT/gc.log":uptime \
	-cp "$CP" "$MAIN" >"$OUT/backend.out" 2>&1 &
BACKEND_PID=$!

# The backend dies with this script.
trap 'kill "$BACKEND_PID" 2>/dev/null || true' EXIT
trap 'exit 130' INT TERM HUP

for _ in $(seq 100); do
	grep -qs listening "$OUT/backend.out" && break
	kill -0 "$BACKEND_PID" 2>/dev/null || die "the backend did not start: $(cat "$OUT/backend.out")"
	sleep 0.2
done
grep -qs listening "$OUT/backend.out" || die "the backend never said it was listening: see $OUT/backend.out"
echo "$BENCH backend started, pid $BACKEND_PID, $REACTORS reactors on CPUs $BACKEND_LIST"

if [ "$WARMUP" -gt 0 ]; then
	echo "backend warm-up: ${WARMUP}s, please wait"
	"scripts/jmh-$BENCH.sh" --schedulers mpsc-unpinned-no-affinity --profilers none \
		--warmup-iterations "$(((WARMUP + 4) / 5))" --warmup-seconds 5 --iterations 1 --seconds 1 \
		--out "$OUT/warmup" "$@" >"$OUT/warmup.log" 2>&1 || true
	grep -qs 'phase=measure' "$OUT/warmup/steps.txt" || die "the backend warm-up failed: see $OUT/warmup.log"
fi

echo "backend ready ($(date +%T)): run the JMH script. Ctrl-C stops the backend."
wait "$BACKEND_PID"
