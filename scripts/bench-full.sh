#!/usr/bin/env bash
#
# Runs every benchmark of docs/results/summary.md, on both JDK builds, one after the other: the
# user benchmark, the servlet one, then the servlet one with a skewed load. pr227 first, then
# noaff. Each benchmark writes its campaign in bench/<name>-<date>-<time>/, and its console in
# console.log there.
#
#   scripts/bench-full.sh        # a long run: start it in tmux
#   scripts/bench-full.sh -n     # dry run: print the commands, run nothing
#
# Then publish them all: publish.sh keeps the newest run of each benchmark.
#
#   scripts/publish.sh bench/*
#
# The JDKs: the SDKMAN builds 28-loom-pr227 and 28-loom-noaff. PR227_JDK and NOAFF_JDK, the home
# of a JDK, replace them. Prerequisite: the project built. A benchmark that fails does not stop
# the others: the script names it at the end.

set -euo pipefail

cd "$(dirname "$0")/.."

die() { echo "bench-full.sh: $*" >&2; exit 1; }

DRY=0
case ${1:-} in
	"") ;;
	-n) DRY=1 ;;
	-h | --help) sed -n '3,/^$/s/^# \{0,1\}//p' "$0"; exit 0 ;;
	*) die "unknown option $1 (try --help)" ;;
esac

SDK_JAVA=${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java
PR227_JDK=${PR227_JDK:-$SDK_JAVA/28-loom-pr227}
NOAFF_JDK=${NOAFF_JDK:-$SDK_JAVA/28-loom-noaff}
if ((DRY == 0)); then
	for j in "$PR227_JDK" "$NOAFF_JDK"; do
		[ -x "$j/bin/java" ] || die "no JDK at $j"
	done
	mkdir -p bench
fi

STAMP=$(date +%Y%m%d-%H%M%S)
FAILED=()

# bench <jdk home> <name> <command>...: one benchmark, on that JDK, into bench/<name>-$STAMP.
# The console goes to a log first, then into the campaign once it exists.
bench() {
	local jdk=$1 out=bench/$2-$STAMP
	shift 2
	echo "== $out"
	echo "   LOOM_JDK_HOME=$jdk $* --out $out"
	((DRY == 0)) || return 0
	LOOM_JDK_HOME=$jdk "$@" --out "$out" 2>&1 | tee "$out.log" || FAILED+=("$out")
	[ ! -d "$out" ] || mv "$out.log" "$out/console.log"
}

# The commands of docs/results/summary.md. Without --schedulers, each build runs its default.
USER_ARGS=(--users-per-group 8000,200000 --depth 256 --rate 150000 --step 20000)
SERVLET_ARGS=(--containers 10 --rate 150000 --step 20000)
SKEWED_ARGS=(--hot-carrier 50 --containers 20 --rate 150000 --step 20000)

bench "$PR227_JDK" user-pr227 scripts/bench-user.sh "${USER_ARGS[@]}"
bench "$PR227_JDK" servlet-pr227 scripts/bench-servlet.sh "${SERVLET_ARGS[@]}"
bench "$PR227_JDK" servlet-skewed20-pr227 scripts/bench-servlet.sh --schedulers mpsc,mpsc-affine-pinned,jdk "${SKEWED_ARGS[@]}"
bench "$NOAFF_JDK" user-noaff scripts/bench-user.sh "${USER_ARGS[@]}"
bench "$NOAFF_JDK" servlet-noaff scripts/bench-servlet.sh "${SERVLET_ARGS[@]}"
bench "$NOAFF_JDK" servlet-skewed20-noaff scripts/bench-servlet.sh --schedulers mpsc "${SKEWED_ARGS[@]}"

((DRY == 0)) || exit 0
echo
if ((${#FAILED[@]})); then
	echo "failed: ${FAILED[*]} (see console.log in each, or <name>.log in bench/)"
	exit 1
fi
echo "all done. Publish: scripts/publish.sh bench/*"
