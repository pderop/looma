#!/usr/bin/env bash
#
# Copies what can be committed from the runs of scripts/bench-user.sh and scripts/bench-servlet.sh
# to docs/results/, and writes there one table of both. Only the latest campaign is kept there.
# Nothing is added to git: `git status` then shows the copied files.
#
#   scripts/publish.sh bench/user-20261110-061933 bench/servlet-20261110-091502
#   scripts/publish.sh -n bench/user-20261110-061933    # dry run, one benchmark

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: scripts/publish.sh [-n] <bench-dir>...

Copies, from each <bench-dir>, the output directory of scripts/bench-user.sh or
scripts/bench-servlet.sh:

  summary.md                         its tables
  *-c2c-<scheduler>.txt              the full perf c2c report of each case and scheduler
  *-walk-<scheduler>.txt             the perf report of each walk run: the cycles of each function
  runs/*.out, *.gc.log               the console and the GC log of each run
  runs/*-stat-*.csv                  the perf stat counts of each stat run

into docs/results/user/ or docs/results/servlet/. Then it writes docs/results/summary.md: the At a
glance of each benchmark, in one table. The runs must come from the same CPU.

Only the latest campaign is kept: docs/results/user/, servlet/ and summary.md are removed first.
campaigns.md stays. So give the user and the servlet runs together.
The perf data files are not copied: they are big, and only perf on the machine that made them can
read them.

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
[ $# -ge 1 ] || { usage >&2; exit 1; }

# The benchmark of each directory, from the title of its summary.md. Absolute paths: the copies
# below run from the project.
DIRS=() BENCHES=()
for d in "$@"; do
	dir=$(cd "$d" 2>/dev/null && pwd) || die "no directory $d"
	[ -f "$dir/summary.md" ] || die "no summary.md in $dir: did the run finish?"
	case $(head -1 "$dir/summary.md") in
		"# User benchmark") b=user ;;
		"# Servlet benchmark") b=servlet ;;
		*) die "$dir/summary.md is not the summary of bench-user.sh or bench-servlet.sh" ;;
	esac
	[[ " ${BENCHES[*]-} " != *" $b "* ]] || die "two $b runs: give one of each"
	DIRS+=("$dir") BENCHES+=("$b")
done

# "cpu model    AMD Ryzen 9 9950X 16-Core Processor": all the runs must come from the same CPU.
SUMMARY=${DIRS[0]}/summary.md
CPU=$(sed -n 's/^cpu model *//p' "$SUMMARY" | head -1)
[ -n "$CPU" ] || die "no cpu model in $SUMMARY"
for dir in "${DIRS[@]}"; do
	[ "$(sed -n 's/^cpu model *//p' "$dir/summary.md" | head -1)" = "$CPU" ] ||
		die "$dir ran on another CPU than ${DIRS[0]}"
done

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST=docs/results

# copy <from> <to>: <from> a file of the run, <to> a name in DEST.
copy() {
	echo "  $1 -> $DEST/$2"
	[ "$DRY" = 1 ] || cp "$1" "$ROOT/$DEST/$2"
}

# glance <summary>...: one table, one row per scheduler: the columns of the At a glance of each
# benchmark, and after the CPU of the servlet one, its p99 from its In short. Each <summary> is
# <bench>/summary.md.
glance() {
	awk '
		function trim(v) { gsub(/^ +| +$/, "", v); return v }
		# A column of the table: its label, where its values come from, and the column whose
		# "not a measurement" empties it.
		function add(label, b, sect, col, guard) { cols[++nc] = label; from[nc] = b SUBSEP sect SUBSEP col; by[nc] = guard }
		FNR == 1 { b = FILENAME; sub(/\/summary\.md$/, "", b); sub(/.*\//, "", b); benches[++nb] = b; sect = "" }
		/^## / { sect = $0; head = 0; next }
		/^\|/ && (sect == "## At a glance" || (b == "servlet" && sect == "## In short")) {
			n = split($0, f, "|")
			if (trim(f[2]) ~ /^-+$/) next
			if (!head) {
				head = 1
				for (i = 3; i < n; i++) name[b, sect, i - 2] = trim(f[i])
				count[b, sect] = n - 3
				next
			}
			# The schedulers, in the order of the first table.
			if (sect == "## At a glance" && !(trim(f[2]) in seen)) { seen[trim(f[2])] = 1; sch[++ns] = trim(f[2]) }
			for (i = 3; i < n; i++) val[b, sect, name[b, sect, i - 2], trim(f[2])] = trim(f[i])
		}
		END {
			g = "## At a glance"
			for (k = 1; k <= nb; k++) {
				b = benches[k]
				for (i = 1; i <= count[b, g]; i++) {
					c = name[b, g, i]
					add(b == "user" ? "user " c : c, b, g, c)
					# The p99 of the servlet benchmark, after its CPU.
					if (b == "servlet" && c ~ /cpu/) add("servlet, p99 µs", b, "## In short", "p99 µs", c)
				}
			}
			printf "| scheduler |"; for (i = 1; i <= nc; i++) printf " %s |", cols[i]; print ""
			printf "|---|"; for (i = 1; i <= nc; i++) printf "---|"; print ""
			for (j = 1; j <= ns; j++) {
				printf "| %s |", sch[j]
				for (i = 1; i <= nc; i++) {
					split(from[i], w, SUBSEP)
					v = val[w[1], w[2], w[3], sch[j]]
					if (by[i] != "" && val[w[1], g, by[i], sch[j]] == "not a measurement") v = ""
					printf " %s |", v
				}
				print ""
			}
		}' "$@"
}

# The top summary.md: the machine, from the header of the first summary without its heap and
# command lines, then one table of both benchmarks, then the schedulers.
top_summary() {
	local files=() links= b
	for b in "${BENCHES[@]}"; do
		files+=("$DEST/$b/summary.md")
		links+="${links:+ and }[$b/summary.md]($b/summary.md)"
	done
	echo "# Benchmarks"
	echo
	awk '/^```/ { n++; print; if (n == 2) exit; next } n == 1 && !/^(heap|command) /' "$DEST/${BENCHES[0]}/summary.md"
	echo
	echo "## At a glance"
	echo
	glance "${files[@]}"
	echo
	echo "Each column is copied from the At a glance of $links, whose notes say how it is"
	echo "computed. The rate, the heap and the command of each benchmark are in the header of its summary."
	[[ " ${BENCHES[*]} " != *" servlet "* ]] ||
		echo "servlet, p99 µs: the column p99 µs of the In short of servlet/summary.md."
	echo
	# The schedulers, as the At a glance of the first summary describes them.
	awk '/^## / { p = 0 } /^#### The schedulers/ { p = 1 }
		# A blank line only before a line that is not blank: none at the end.
		p && /^$/ { blank++; next }
		p { for (; blank > 0; blank--) print ""; print }' "$DEST/${BENCHES[0]}/summary.md"
}

cd "$ROOT"
# Only the latest campaign is kept: the files of the last one go first, so that none of them stays.
# campaigns.md stays.
for old in "$DEST/user" "$DEST/servlet" "$DEST/summary.md"; do
	[ -e "$old" ] || continue
	echo "$([ "$DRY" = 1 ] && echo "would remove" || echo "removed") $old"
	[ "$DRY" = 1 ] || rm -rf "$old"
done
echo "$([ "$DRY" = 1 ] && echo "would copy" || echo "copied"):"
for i in "${!DIRS[@]}"; do
	dir=${DIRS[$i]} b=${BENCHES[$i]}
	[ "$DRY" = 1 ] || mkdir -p "$DEST/$b/runs"
	copy "$dir/summary.md" "$b/summary.md"
	for f in "$dir"/*-c2c-*.txt "$dir"/*-walk-*.txt; do
		[ -e "$f" ] || continue
		copy "$f" "$b/$(basename "$f")"
	done
	for f in "$dir"/runs/*.out "$dir"/runs/*.gc.log "$dir"/runs/*-stat-*.csv; do
		[ -e "$f" ] || continue
		copy "$f" "$b/runs/$(basename "$f")"
	done
done
if [ "$DRY" = 1 ]; then
	echo "would write $DEST/summary.md"
else
	top_summary >"$DEST/summary.md"
	echo "wrote $DEST/summary.md"
fi
