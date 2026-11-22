#!/usr/bin/env bash
#
# Copies what can be committed from the runs of scripts/bench-user.sh and scripts/bench-servlet.sh
# to docs/results/<jdk>/<bench>/, and writes the summaries of docs/results/. Nothing is added to
# git: `git status` then shows the copied files.
#
#   scripts/publish.sh bench/user-20261110-061933 bench/servlet-20261110-091502
#   scripts/publish.sh bench/user-* bench/servlet-*     # the runs of both JDKs
#   scripts/publish.sh bench/*                           # the newest run of each benchmark
#   scripts/publish.sh -n bench/user-20261110-061933    # dry run, one benchmark
#   scripts/publish.sh bench/servlet-skewed-20261117-213738   # a skewed load (--hot-carrier)

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

into docs/results/<jdk>/<bench>/: user or servlet, or user-skewed or servlet-skewed for a run
with a skewed load (--hot-carrier). The folder of each benchmark given is removed first; the other
benchmarks of the JDK, and the other JDKs, stay.

<jdk> is the name of the JDK build, from the `java` line of the summary: the part before the commit.
"build 28-internal-noaff-<commit>" gives noaff. The runs may come from several JDKs. Of two
runs of one benchmark on one JDK, the newer one is kept, by the date line of its summary. What
is not a finished campaign is skipped: bench/* works. All must come from the same CPU as what
stays published.

Then it writes:

  docs/results/<jdk>/summary.md      the date, build and kernel of each benchmark, then the At
                                     a glance of both benchmarks of the JDK, in one table; those
                                     of the skewed load in a second one

A benchmark can be published alone: the others stay. A warning says when what stays comes from
another commit of the same build, or another kernel.
  docs/results/summary.md            the part between <!-- begin charts --> and
                                     <!-- end charts -->: the notes of the charts
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

# build_of <summary>: the build of its java line, with its commit: 28-internal-noaff-<commit>.
build_of() { sed -n 's/^java .*(build \([^ )]*\).*/\1/p' "$1" | head -1; }

# kernel_of <summary>: "host   gabuzo-amd, Linux 7.0.0-43-generic" gives 7.0.0-43-generic.
kernel_of() { sed -n 's/^host .*Linux //p' "$1" | head -1; }

# date_of <summary>: "date         2026-11-17 04:58:54 +0000" gives 2026-11-17 04:58:54 +0000.
date_of() { sed -n 's/^date *//p' "$1" | head -1; }

# The benchmark and the JDK of each directory, from its summary.md. Absolute paths: the copies
# below run from the project. What is not a finished campaign is skipped, so bench/* works. Of two
# runs of one benchmark on one JDK, the newer one is kept.
DIRS=() BENCHES=() JDKS=() KEYS=() CPU=
for d in "$@"; do
	[ -d "$d" ] || { echo "skipped $d: not a directory" >&2; continue; }
	dir=$(cd "$d" && pwd)
	[ -f "$dir/summary.md" ] || { echo "skipped $d: no summary.md, the run did not finish" >&2; continue; }
	case $(head -1 "$dir/summary.md") in
		"# User benchmark") b=user ;;
		"# Servlet benchmark") b=servlet ;;
		"# User benchmark, skewed load") b=user-skewed ;;
		"# Servlet benchmark, skewed load") b=servlet-skewed ;;
		*) echo "skipped $d: not a summary of bench-user.sh or bench-servlet.sh" >&2; continue ;;
	esac
	jdk=$(jdk_of "$dir/summary.md")
	[ -n "$jdk" ] || die "no JDK name in the java line of $dir/summary.md"
	k=-1
	for ((i = 0; i < ${#KEYS[@]}; i++)); do [ "${KEYS[$i]}" != "$jdk/$b" ] || k=$i; done
	if ((k >= 0)); then
		old=${DIRS[$k]}
		if [[ $(date_of "$dir/summary.md") > $(date_of "$old/summary.md") ]]; then
			echo "skipped $old: $dir is a newer $b run of $jdk" >&2
			DIRS[$k]=$dir
		else
			echo "skipped $dir: $old is a newer $b run of $jdk" >&2
		fi
		continue
	fi
	DIRS+=("$dir") BENCHES+=("$b") JDKS+=("$jdk") KEYS+=("$jdk/$b")
done
((${#DIRS[@]})) || die "no finished campaign given"
# All the runs kept come from one CPU.
for dir in "${DIRS[@]}"; do
	cpu=$(cpu_of "$dir/summary.md")
	[ -n "$cpu" ] || die "no cpu model in $dir/summary.md"
	[ -n "$CPU" ] || CPU=$cpu
	[ "$cpu" = "$CPU" ] || die "$dir ran on another CPU than ${DIRS[0]}"
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
	# A benchmark can be run again alone: what stays may come from another commit or kernel. Say so,
	# and go on.
	for i in "${!DIRS[@]}"; do
		g=${DIRS[$i]}/summary.md
		[ "${JDKS[$i]}" != "$jdk" ] || [ "$(build_of "$g")" = "$(build_of "$f")" ] ||
			echo "warning: ${KEYS[$i]} is $(build_of "$g"), $RESULTS/$jdk/$b stays $(build_of "$f")" >&2
		[ "$(kernel_of "$g")" = "$(kernel_of "$f")" ] ||
			echo "warning: ${KEYS[$i]} ran on Linux $(kernel_of "$g"), $RESULTS/$jdk/$b stays on $(kernel_of "$f")" >&2
	done
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
		# The benchmark, from the folder: user, servlet, user-skewed, servlet-skewed. Its base: user
		# or servlet.
		FNR == 1 { b = FILENAME; sub(/\/summary\.md$/, "", b); sub(/.*\//, "", b); benches[++nb] = b; sect = "" }
		FNR == 1 { base[b] = b; sub(/-skewed$/, "", base[b]) }
		/^## / { sect = $0; head = 0; next }
		/^\|/ && (sect == "## At a glance" || (base[b] == "servlet" && sect == "## In short")) {
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
					add(base[b] == "user" ? "user " c : c, b, g, c)
					# The p99 of the servlet benchmark, after its CPU.
					if (base[b] == "servlet" && c ~ /cpu/) add("servlet, p99 µs", b, "## In short", "p99 µs", c)
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


# glance_section <title> <jdk> <bench>...: the table of glance, of the benchmarks of the JDK that
# are published, and its notes. Nothing if none is.
glance_section() {
	local title=$1 dest=$RESULTS/$2 files=() links= servlet= b
	shift 2
	for b in "$@"; do
		[ -f "$dest/$b/summary.md" ] || continue
		files+=("$dest/$b/summary.md")
		links+="${links:+ and }[$b/summary.md]($b/summary.md)"
		[[ $b != servlet* ]] || servlet=$b
	done
	((${#files[@]} > 0)) || return 0
	echo "## $title"
	echo
	glance "${files[@]}"
	echo
	echo "Each column is copied from the At a glance of $links, whose notes say how it is"
	echo "computed. The date, the heap and the command of each benchmark are in the header of its summary, the rate in its section The runs."
	[ -z "$servlet" ] ||
		echo "servlet, p99 µs: the column p99 µs of the In short of $servlet/summary.md."
	echo
}

# jdk_summary <jdk>: the summary.md of a JDK: the machine, from the header of its first summary
# without the lines that can differ from one benchmark to the next, then the date, build and kernel
# of each benchmark, one table of both benchmarks, one of both under a skewed load, then the
# schedulers.
jdk_summary() {
	local dest=$RESULTS/$1 files=() b f
	for b in user servlet user-skewed servlet-skewed; do
		[ -f "$dest/$b/summary.md" ] && files+=("$dest/$b/summary.md")
	done
	echo "# Benchmarks, $1"
	echo
	awk '/^```/ { n++; print; if (n == 2) exit; next } n == 1 && !/^(date|host|java|heap|command) /' "${files[0]}"
	echo
	echo "## The runs"
	echo
	echo "| benchmark | date | java build | Linux kernel |"
	echo "|---|---|---|---|"
	for f in "${files[@]}"; do
		b=$(basename "$(dirname "$f")")
		echo "| [$b]($b/summary.md) | $(sed -n 's/^date *//p' "$f" | head -1) | $(build_of "$f") | $(kernel_of "$f") |"
	done
	echo
	echo "Each benchmark can be run alone: each row comes from the header of its summary, lines date, java"
	echo "and host. The host: $(sed -n 's/^host *\([^,]*\),.*/\1/p' "${files[0]}" | head -1)."
	echo
	glance_section "At a glance" "$1" user servlet
	glance_section "At a glance, skewed load" "$1" user-skewed servlet-skewed
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

# block <name>: the content of a block of docs/results/summary.md.
block() {
	case $1 in
		charts) chart_notes ;;
		*) die "unknown block in $RESULTS/summary.md: $1" ;;
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
