#!/usr/bin/env bash
#
# Copies what can be committed from the runs of scripts/bench-user.sh and scripts/bench-servlet.sh
# to docs/results/<jdk>/<bench>/, and writes the summaries of docs/results/. Nothing is added to
# git: `git status` then shows the copied files.
#
#   scripts/publish.sh bench/user-20261110-061933 bench/servlet-20261110-091502
#   scripts/publish.sh bench/user-* bench/servlet-*     # the runs of both JDKs
#   scripts/publish.sh -n bench/user-20261110-061933    # dry run, one benchmark

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: scripts/publish.sh [-n] <bench-dir>...

Copies, from each <bench-dir>, the output directory of scripts/bench-user.sh or
scripts/bench-servlet.sh:

  summary.md                         its tables
  *-c2c-<scheduler>.txt              the full perf c2c report of each case and scheduler
  *-walk-<scheduler>.txt             the perf report of each walk run: the cycles of each function;
                                     not one whose perf record failed
  runs/*.out, *.gc.log               the console and the GC log of each run
  runs/*-stat-*.csv                  the perf stat counts of each stat run

into docs/results/<jdk>/user/ or docs/results/<jdk>/servlet/. The folder of each benchmark given
is removed first; the other benchmark of the JDK, and the other JDKs, stay.

<jdk> is the name of the JDK build, from the `java` line of the summary: the part before the commit.
"build 28-internal-noaff-<commit>" gives noaff. The runs may come from several JDKs, at
most one user and one servlet run each. All must come from the same CPU as what stays published.

Then it writes:

  docs/results/<jdk>/summary.md      the At a glance of both benchmarks of the JDK, in one table
  docs/results/summary.md            the parts between <!-- begin x --> and <!-- end x -->: the
                                     tables of every JDK, and the notes of the charts
  docs/results/charts/               with scripts/charts.sh

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

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RESULTS=docs/results

# cpu_of <summary>: "cpu model    AMD Ryzen 9 9950X 16-Core Processor" gives the model.
cpu_of() { sed -n 's/^cpu model *//p' "$1" | head -1; }

# jdk_of <summary>: "java   OpenJDK Runtime Environment (build 28-internal-noaff-<commit>)" gives
# noaff. The commit is left out: a new commit of the same build replaces its folder.
jdk_of() {
	sed -n 's/^java *//p' "$1" | head -1 |
		sed -n 's/.*(build [^ ]*-internal-\([A-Za-z0-9._]*\)[-)].*/\1/p'
}

# The benchmark and the JDK of each directory, from its summary.md. Absolute paths: the copies
# below run from the project.
DIRS=() BENCHES=() JDKS=() KEYS=() CPU=
for d in "$@"; do
	dir=$(cd "$d" 2>/dev/null && pwd) || die "no directory $d"
	[ -f "$dir/summary.md" ] || die "no summary.md in $dir: did the run finish?"
	case $(head -1 "$dir/summary.md") in
		"# User benchmark") b=user ;;
		"# Servlet benchmark") b=servlet ;;
		*) die "$dir/summary.md is not the summary of bench-user.sh or bench-servlet.sh" ;;
	esac
	jdk=$(jdk_of "$dir/summary.md")
	[ -n "$jdk" ] || die "no JDK name in the java line of $dir/summary.md"
	cpu=$(cpu_of "$dir/summary.md")
	[ -n "$cpu" ] || die "no cpu model in $dir/summary.md"
	[ -n "$CPU" ] || CPU=$cpu
	[ "$cpu" = "$CPU" ] || die "$dir ran on another CPU than ${DIRS[0]}"
	[[ " ${KEYS[*]-} " != *" $jdk/$b "* ]] || die "two $b runs of $jdk: give one"
	DIRS+=("$dir") BENCHES+=("$b") JDKS+=("$jdk") KEYS+=("$jdk/$b")
done

# The JDKs given, each once.
JDK_LIST=()
for jdk in "${JDKS[@]}"; do
	[[ " ${JDK_LIST[*]-} " == *" $jdk "* ]] || JDK_LIST+=("$jdk")
done

# What stays published goes into the same tables and charts: it must come from the same CPU. That is
# the other benchmark of a JDK given, and every benchmark of the other JDKs.
for f in "$ROOT/$RESULTS"/*/*/summary.md; do
	[ -f "$f" ] || continue
	b=$(basename "$(dirname "$f")") jdk=$(basename "$(dirname "$(dirname "$f")")")
	[[ " ${KEYS[*]} " != *" $jdk/$b "* ]] || continue
	[ "$(cpu_of "$f")" = "$CPU" ] ||
		die "$RESULTS/$jdk/$b ran on another CPU: publish a new $b run of $jdk too, or remove it"
done

# copy <from> <to>: <from> a file of the run, <to> a name in DEST.
copy() {
	echo "  $1 -> $DEST/$2"
	[ "$DRY" = 1 ] || cp "$1" "$ROOT/$DEST/$2"
}

# copy_summary <from> <to>: copy, with each relative link one directory deeper: the links of the
# summary of a run are written for one directory below docs/results, its copy is two.
copy_summary() {
	echo "  $1 -> $DEST/$2"
	[ "$DRY" = 1 ] || sed 's#](\.\./#](../../#g' "$1" >"$ROOT/$DEST/$2"
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


# jdk_summary <jdk>: the summary.md of a JDK: the machine, from the header of its first summary
# without its date, heap and command lines, then one table of both benchmarks, then the
# schedulers.
jdk_summary() {
	local dest=$RESULTS/$1 benches=() files=() links= b
	for b in user servlet; do
		[ -f "$dest/$b/summary.md" ] || continue
		benches+=("$b") files+=("$dest/$b/summary.md")
		links+="${links:+ and }[$b/summary.md]($b/summary.md)"
	done
	echo "# Benchmarks, $1"
	echo
	awk '/^```/ { n++; print; if (n == 2) exit; next } n == 1 && !/^(date|heap|command) /' "${files[0]}"
	echo
	echo "## At a glance"
	echo
	glance "${files[@]}"
	echo
	echo "Each column is copied from the At a glance of $links, whose notes say how it is"
	echo "computed. The date, the heap and the command of each benchmark are in the header of its summary, the rate in its section The runs."
	[[ " ${benches[*]} " != *" servlet "* ]] ||
		echo "servlet, p99 µs: the column p99 µs of the In short of servlet/summary.md."
	echo
	# The schedulers, as the At a glance of the first summary describes them.
	awk '/^## / { p = 0 } /^#+ The schedulers/ { p = 1 }
		# A blank line only before a line that is not blank: none at the end.
		p && /^$/ { blank++; next }
		p { for (; blank > 0; blank--) print ""; print }' "${files[0]}"
}

# The lines of the charts, from scripts/charts.sh: <jdk> <scheduler>.
series() { "$ROOT/scripts/charts.sh" --series | awk 'NF { print $1, $2 }'; }

# chart_notes: one line per line of the charts left out because its ramp kept up with no step.
chart_notes() {
	local b jdk sch f r
	for b in user servlet; do
		series | while read -r jdk sch; do
			for ((r = 1; ; r++)); do
				case $b in
					user) f=$jdk/user/runs/small-r$r-ramp-$sch-256.out ;;
					servlet) f=$jdk/servlet/runs/servlet-r$r-ramp-$sch.out ;;
				esac
				[ -f "$RESULTS/$f" ] || break
				grep -q '^ramp *kept up with none' "$RESULTS/$f" || continue
				echo "The $b charts have no line for the ramp r$r of \`$sch\` on \`$jdk\`: it did not keep up"
				echo "with the first step (\`$f\`)."
			done
		done
	done
}

# The p99 limit of "max req/s with p99 < 1 ms", as in the summaries.
P99_LIMIT=$(sed -n 's/^P99_LIMIT=//p' "$ROOT/scripts/lib/bench-common.sh")
((P99_LIMIT % 1000)) && LIMIT_TEXT="$P99_LIMIT µs" || LIMIT_TEXT="$((P99_LIMIT / 1000)) ms"

# cell: in scripts/lib/summary.sh.
. "$ROOT/scripts/lib/summary.sh"

# builds: the published JDKs, those of the charts first.
builds() {
	{
		series | awk '{ print $1 }'
		for f in "$RESULTS"/*/summary.md; do basename "$(dirname "$f")"; done
	} | awk '!seen[$0]++' | while read -r jdk; do
		[ -f "$RESULTS/$jdk/user/summary.md" ] || [ -f "$RESULTS/$jdk/servlet/summary.md" ] && echo "$jdk"
	done
}

# schedulers <jdk>: the schedulers of a JDK, in the order of its At a glance.
schedulers() {
	local b
	for b in user servlet; do
		[ -f "$RESULTS/$1/$b/summary.md" ] || continue
		awk '
			/^## / { s = $0 == "## At a glance"; h = 0; next }
			s && /^\|/ { v = $2; gsub(/^ +| +$/, "", v); if (!h) { h = 1; next } if (v !~ /^-+$/) print v }
		' FS='|' "$RESULTS/$1/$b/summary.md"
	done | awk '!seen[$0]++'
}

# specs <aspect>: where each column of an aspect comes from, one line each: <bench>|<section>|
# <column>|<filters>. The columns of peak, fast, p99, cpu and migrations: user small, servlet, user
# big, at depth 256.
specs() {
	case $1 in
		peak | fast | cpu)
			local c
			case $1 in
				peak) c="max req/s" ;;
				fast) c="max req/s with p99 < $LIMIT_TEXT" ;;
				cpu) c="cpu µs/req" ;;
			esac
			echo "user|At a glance|small, $c|"
			echo "servlet|At a glance|servlet, $c|"
			echo "user|At a glance|big, $c|"
			;;
		p99 | migrations)
			local c=$1
			[ "$1" = p99 ] && c="p99 µs"
			echo "user|In short|$c|case=small depth=256"
			echo "servlet|In short|$c|"
			echo "user|In short|$c|case=big depth=256"
			;;
		*) return 1 ;;
	esac
}

# header: the column titles of an aspect: the three cases.
header() { echo "user \`small\` | servlet | user \`big\`"; }

# value <jdk> <scheduler> <spec>: the cell a spec gives for one scheduler of one JDK.
value() {
	local b sec col filt
	IFS='|' read -r b sec col filt <<<"$3"
	cell "$RESULTS/$1/$b/summary.md" "$sec" "$col" "scheduler=$2 $filt"
}

# aspect_table <aspect>: one row per scheduler of each JDK, one column per spec. A row with no
# value is left out.
aspect_table() {
	local jdk sch spec row v any n
	n=$(specs "$1" | wc -l)
	echo "| scheduler | build | $(header "$1") |"
	printf '| --- | --- |'
	for ((i = 0; i < n; i++)); do printf ' --- |'; done
	echo
	for jdk in $(builds); do
		for sch in $(schedulers "$jdk"); do
			row="| \`$sch\` | \`$jdk\` |" any=0
			while IFS= read -r spec; do
				v=$(value "$jdk" "$sch" "$spec")
				[ -z "$v" ] || any=1
				row+=" $v |"
			done < <(specs "$1")
			[ "$any" = 0 ] || echo "$row"
		done
	done
}

# vs_table <jdk> <scheduler> <jdk> <scheduler>: the first against the second, aspect by aspect.
vs_table() {
	local a label spec row
	echo "| aspect | $(header peak) |"
	echo "| --- | --- | --- | --- |"
	for a in peak fast p99 cpu migrations; do
		case $a in
			peak) label="peak rate" ;;
			fast) label="highest rate with p99 < $LIMIT_TEXT" ;;
			p99) label="p99 µs at 150k req/s" ;;
			cpu) label="cpu µs/req at 150k req/s" ;;
			migrations) label="migrations" ;;
		esac
		row="| $label |"
		while IFS= read -r spec; do
			row+=" $(value "$1" "$2" "$spec") vs $(value "$3" "$4" "$spec") |"
		done < <(specs "$a")
		echo "$row"
	done
}

# block <name> [<args>]: the content of a block of docs/results/summary.md.
block() {
	case $1 in
		charts) chart_notes ;;
		vs) [ $# = 5 ] || die "block vs needs <jdk> <scheduler> <jdk> <scheduler>: $*"; vs_table "$2" "$3" "$4" "$5" ;;
		*) specs "$1" >/dev/null || die "unknown block in $RESULTS/summary.md: $1"; aspect_table "$1" ;;
	esac
}

# results_summary: fills each block <!-- begin <name> [<args>] --> ... <!-- end <name> --> of
# docs/results/summary.md. The text around them is written by hand.
results_summary() {
	local tmp id
	tmp=$(mktemp -d)
	sed -n 's/^<!-- begin \(.*\) -->$/\1/p' "$RESULTS/summary.md" | while read -r id; do
		# shellcheck disable=SC2086
		block $id >"$tmp/$(echo "$id" | tr ' /' '__')"
	done
	awk -v dir="$tmp" '
		/^<!-- end .+ -->$/ { skip = 0 }
		!skip { print }
		/^<!-- begin .+ -->$/ {
			id = substr($0, 12, length($0) - 15); gsub(/[ \/]/, "_", id)
			f = dir "/" id
			if ((r = getline line < f) < 0) { print "no block " id > "/dev/stderr"; bad = 1; exit }
			print ""
			for (; r > 0; r = getline line < f) { print line; n = 1 }
			close(f)
			if (n) print ""
			n = 0
			skip = 1
		}
		END { if (skip && !bad) { print "no end of a block" > "/dev/stderr"; bad = 1 } exit bad }' \
		"$RESULTS/summary.md" >"$tmp/summary.md" || die "$RESULTS/summary.md: see above"
	mv "$tmp/summary.md" "$RESULTS/summary.md"
	rm -r "$tmp"
}

cd "$ROOT"
# A benchmark given replaces its published folder: the old folder is removed first, so that none of
# its files stays. The other benchmark of the JDK, and the other JDKs, stay.
for i in "${!DIRS[@]}"; do
	dir=${DIRS[$i]} b=${BENCHES[$i]} DEST=$RESULTS/${JDKS[$i]}
	if [ -e "$DEST/$b" ]; then
		echo "$([ "$DRY" = 1 ] && echo "would remove" || echo "removed") $DEST/$b"
		[ "$DRY" = 1 ] || rm -rf "${DEST:?}/$b"
	fi
	echo "$([ "$DRY" = 1 ] && echo "would copy" || echo "copied"):"
	[ "$DRY" = 1 ] || mkdir -p "$DEST/$b/runs"
	copy_summary "$dir/summary.md" "$b/summary.md"
	for f in "$dir"/*-c2c-*.txt "$dir"/*-walk-*.txt; do
		[ -e "$f" ] || continue
		# A walk report whose perf record failed holds no data: the summary says so, and it is not
		# copied.
		if [[ $f == *-walk-* ]] && { [ ! -s "$f" ] || grep -q 'failed to process sample\|data size field is 0' "$f"; }; then
			echo "  $f: perf record failed, not copied"
			continue
		fi
		copy "$f" "$b/$(basename "$f")"
	done
	for f in "$dir"/runs/*.out "$dir"/runs/*.gc.log "$dir"/runs/*-stat-*.csv; do
		[ -e "$f" ] || continue
		copy "$f" "$b/runs/$(basename "$f")"
	done
done
if [ "$DRY" = 1 ]; then
	for jdk in "${JDK_LIST[@]}"; do echo "would write $RESULTS/$jdk/summary.md"; done
	echo "would write the blocks of $RESULTS/summary.md, and the charts"
	exit 0
fi
for jdk in "${JDK_LIST[@]}"; do
	jdk_summary "$jdk" >"$RESULTS/$jdk/summary.md"
	echo "wrote $RESULTS/$jdk/summary.md"
done
results_summary
echo "wrote the blocks of $RESULTS/summary.md"
# The charts draw the ramps of every JDK: redraw them.
"$ROOT/scripts/charts.sh"
