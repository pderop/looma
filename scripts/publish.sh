#!/usr/bin/env bash
#
# Copies what can be committed from a run of scripts/bench.sh to docs/results/.
# Nothing is added to git: `git status` then shows the copied files.
#
#   scripts/publish.sh bench/20261021-030301
#   scripts/publish.sh -n bench/20261021-031812    # dry run

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: scripts/publish.sh [-n] <bench-dir>

Copies, from <bench-dir>, the output directory of scripts/bench.sh:
  summary.md                  the tables of all the cases
  <case>-c2c-<scheduler>.txt  the full perf c2c report of each case and scheduler
  runs/*.out, runs/*.gc.log   the console and the GC log of each run
  runs/*-stat-*.csv           the perf stat counts of each stat run
into docs/results/<date>-<cpu>/, with the date and the CPU read from summary.md. That folder is
emptied first, if it exists. The perf data files are not copied: they are big, and only perf on
the machine that made them can read them.

  -n        Dry run: print what would be copied, copy nothing.
USAGE
}

die() {
	echo "$(basename "$0"): $*" >&2
	exit 1
}

DRY=0
case ${1:-} in
	-h | --help) usage; exit 0 ;;
	-n) DRY=1; shift ;;
esac
[ $# = 1 ] || { usage >&2; exit 1; }
# An absolute path: the copies below run from the project.
DIR=$(cd "$1" 2>/dev/null && pwd) || die "no directory $1"
SUMMARY=$DIR/summary.md
[ -f "$SUMMARY" ] || die "no summary.md in $DIR: did the run finish?"

# "date         2026-10-20 04:32:54 +0000" and "cpu model    AMD Ryzen 9 9950X 16-Core Processor".
DATE=$(awk '/^date / { print $2; exit }' "$SUMMARY")
CPU=$(sed -n 's/^cpu model *//p' "$SUMMARY" | head -1)
[ -n "$DATE" ] && [ -n "$CPU" ] || die "no date or cpu model in $SUMMARY"
# The CPU as a short name: "AMD Ryzen 9 9950X 16-Core Processor" gives ryzen9-9950x, and
# "Intel(R) Core(TM) i9-14900K" gives i9-14900k.
SHORT=$(echo "$CPU" | tr 'A-Z' 'a-z' | sed -E 's/\((r|tm)\)//g' | awk '{
	out = ""
	for (i = 1; i <= NF; i++) {
		w = $i
		if (w ~ /^(amd|intel|core|processor|[0-9]+-core)$/) continue
		# A word and the single digit after it go together: ryzen 9 -> ryzen9.
		if (out != "" && w ~ /^[0-9]$/) { out = out w; continue }
		out = out (out == "" ? "" : "-") w
	}
	print out
}')

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST=docs/results/$DATE-$SHORT

# copy <from> <to>: <from> a file of the run, <to> a name in DEST.
copy() {
	echo "  $1 -> $DEST/$2"
	[ "$DRY" = 1 ] || cp "$1" "$ROOT/$DEST/$2"
}

cd "$ROOT"
# A campaign of the same date and CPU replaces the folder: no file of the older one stays.
if [ -d "$DEST" ]; then
	echo "$([ "$DRY" = 1 ] && echo "would replace" || echo "replaced") $DEST"
	[ "$DRY" = 1 ] || rm -rf "$DEST"
fi
[ "$DRY" = 1 ] || mkdir -p "$DEST/runs"
echo "$([ "$DRY" = 1 ] && echo "would copy" || echo "copied"):"
copy "$SUMMARY" summary.md
for f in "$DIR"/*-c2c-*.txt; do
	[ -e "$f" ] || continue
	copy "$f" "$(basename "$f")"
done
for f in "$DIR"/runs/*.out "$DIR"/runs/*.gc.log "$DIR"/runs/*-stat-*.csv; do
	[ -e "$f" ] || continue
	copy "$f" "runs/$(basename "$f")"
done
