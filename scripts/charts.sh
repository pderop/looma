#!/usr/bin/env bash
#
# Draws the ramps published in docs/results/<jdk>/ as charts, in docs/results/charts/:
#
#   user-p99.svg      user benchmark, case small, depth 256: the p99 at each step of each ramp
#   user-cpu.svg      the same ramps: the cpu µs/req at each step
#   servlet-p99.svg   servlet benchmark: the p99 at each step
#   servlet-cpu.svg   the same ramps: the cpu µs/req at each step
#   user.csv, servlet.csv   the numbers of the charts
#
#   user-small-max.svg   user benchmark, case small, depth 256: the max rate of each ramp
#   user-big-max.svg     the same, case big
#   servlet-max.svg      servlet benchmark: the same
#   servlet-skewed-max.svg   the same, servlet benchmark with a skewed load
#   user-max.csv, servlet-max.csv, servlet-skewed-max.csv   the numbers of these charts
#
#   <bench>-p99-measure.svg   the p99 of each measure (r1, r2); user: cases small and big
#   <bench>-cpu-measure.svg   the same runs: the cpu µs/req
#   <bench>-migrations.svg    the same runs: the migrations
#   <bench>-gcs.svg           the same runs: the GC pauses; user: case small
#   user-cpu-depth0.svg       the same at depth 0: the cpu µs/req, user small and big
#   measure.csv               the numbers of these charts
#   user-walk-l3.svg     the walk cycles/req and the loads from another L3, from the summaries
#   servlet-l3.svg       the loads from another L3
#   walk-l3.csv          the numbers of these charts
#   user-stackchunks.svg the live StackChunks of the c2c run of user small
#   stackchunks.csv      the numbers of this chart
#
# The charts by step have one line per scheduler and JDK: at each step, the mean of r1 and r2. A
# line ends at the last step both ramps kept up with. Each bar is one ramp: its max req/s, and its
# max req/s with p99 < 1 ms. Each dot is one scheduler: the mean of its measures. The lines show the schedulers of SERIES, the bars and the dots those of
# BAR_SERIES. scripts/publish.sh runs it after each publish.
#
#   scripts/charts.sh
#   scripts/charts.sh --series   # print the lines of the charts; scripts/publish.sh reads them

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RESULTS=$ROOT/docs/results
OUT=$RESULTS/charts
# cell: reads one cell of a summary.
. "$ROOT/scripts/lib/summary.sh"
# The p99 limit of "max req/s with p99 < 1 ms", the same as in the summaries.
P99_LIMIT=$(sed -n 's/^P99_LIMIT=//p' "$ROOT/scripts/lib/bench-common.sh")
((P99_LIMIT % 1000)) && LIMIT_TEXT="$P99_LIMIT µs" || LIMIT_TEXT="$((P99_LIMIT / 1000)) ms"

# The lines of the charts by step: <jdk directory> <scheduler> <color 1-3> <solid|dashed>.
# One color per scheduler, dashes for the first JDK. A line whose files are missing is left out.
SERIES='
pr227 mpsc-affine-pinned 3 dashed
pr227 mpsc               1 dashed
pr227 jdk                2 dashed
noaff mpsc               1 solid
noaff jdk                2 solid
'
# The groups of the bar charts: <jdk directory> <scheduler> <color 1-6>, top to bottom. The same
# color per scheduler as the lines. This order keeps two neighbours apart for colour-blind readers.
BAR_SERIES='
pr227 mpsc-affine-pinned 3
pr227 mpsc-affine        4
pr227 mpsc-pinned        5
pr227 jdk-pinned         6
pr227 mpsc               1
pr227 jdk                2
noaff mpsc               1
noaff jdk                2
'
if [ "${1:-}" = --series ]; then
	echo "$SERIES"
	exit 0
fi

# steps <console>: one row per step kept up with: rate, cpu µs/req, p99 µs. A step is a block of
# the console from its line step. A block with a *** line was not kept up with.
steps() {
	awk '
		function flush() { if (rate != "" && !bad) print rate "," cpu "," p99; rate = ""; bad = 0 }
		/^step / { flush(); rate = $2; gsub(/,/, "", rate) }
		rate != "" && /^cpu / { cpu = $2 }
		rate != "" && /^latency / { for (i = 2; i < NF; i++) if ($i == "p99") p99 = $(i + 1) }
		rate != "" && /^\*\*\*/ { bad = 1 }
		/^ramp +kept up with/ { flush() }
		END { flush() }' "$1"
}

# csv <bench>: the rows of all the lines of one benchmark:
# jdk,scheduler,color,style,rate,cpu µs/req,p99 µs.
csv() {
	local bench=$1 jdk sch color style file r
	echo "jdk,scheduler,color,style,rate req/s,cpu us/req,p99 us,run"
	while read -r jdk sch color style; do
		[ -n "$jdk" ] || continue
		for ((r = 1; ; r++)); do
			case $bench in
				user) file=$RESULTS/$jdk/user/runs/small-r$r-ramp-$sch-256.out ;;
				servlet) file=$RESULTS/$jdk/servlet/runs/servlet-r$r-ramp-$sch.out ;;
			esac
			[ -f "$file" ] || break
			steps "$file" | sed "s/^/$jdk,$sch,$color,$style,/; s/$/,r$r/"
		done
	done <<<"$SERIES"
}

# maxima <console>: the max req/s of a ramp, and its max req/s with p99 < 1 ms, as the summaries
# compute them. max: the line "ramp kept up with". With p99 < 1 ms: from the first step up, the
# last step kept up with before the first step whose p99 is above $P99_LIMIT µs. 0: not even the
# first step.
maxima() {
	awk -v limit="$P99_LIMIT" '
		function num(v) { gsub(/,/, "", v); return v ~ /^[0-9]+$/ ? v : 0 }
		function flush() {
			if (rate != "" && !done) { if (bad || p99 == "" || p99 + 0 > limit) done = 1; else fast = rate }
			rate = ""; bad = 0; p99 = ""
		}
		/^step / { flush(); rate = num($2) }
		rate != "" && /^latency / { for (i = 2; i < NF; i++) if ($i == "p99") p99 = $(i + 1) }
		rate != "" && /^\*\*\*/ { bad = 1 }
		/^ramp +kept up with/ { flush(); max = num($5) }
		END { flush(); print (max == "" ? 0 : max) "," (fast == "" ? 0 : fast) }' "$1"
}

# maxcsv <bench>: one row per ramp, r1, r2 and so on while there is one: jdk,scheduler,color,case,
# run,max req/s,max req/s p99 < 1 ms.
maxcsv() {
	local bench=$1 jdk sch color c r file cases
	echo "jdk,scheduler,color,case,run,max req/s,max req/s p99 < 1 ms"
	case $bench in user) cases='small big' ;; *) cases=servlet ;; esac
	for c in $cases; do
		while read -r jdk sch color; do
			[ -n "$jdk" ] || continue
			for ((r = 1; ; r++)); do
				case $bench in
					user) file=$RESULTS/$jdk/user/runs/$c-r$r-ramp-$sch-256.out ;;
					servlet) file=$RESULTS/$jdk/servlet/runs/servlet-r$r-ramp-$sch.out ;;
					servlet-skewed) file=$RESULTS/$jdk/servlet-skewed/runs/servlet-r$r-ramp-$sch.out ;;
				esac
				[ -f "$file" ] || break
				echo "$jdk,$sch,$color,$c,r$r,$(maxima "$file")"
			done
		done <<<"$BAR_SERIES"
	done
}

# bars <csv> <case> <title> <subtitle>: one bar chart, a group of bars per line of the charts, one
# bar per ramp. The pale bar is the max req/s, the solid one the max req/s with p99 < 1 ms.
bars() {
	awk -F, -v want="$2" -v title="$3" -v subtitle="$4" -v limit="$LIMIT_TEXT" '
		function x(v) { return L + v / x1 * (R - L) }
		function k(v) { v = sprintf("%.0f", v / 1000); return length(v) > 3 ? substr(v, 1, length(v) - 3) "," substr(v, length(v) - 2) "k" : v "k" }
		NR == 1 || $4 != want { next }
		{
			g = $1 SUBSEP $2
			if (!(g in seen)) { seen[g] = 1; ++n; jdk[n] = $1; sch[n] = $2; color[n] = $3 }
			m = ++nb[n]; run[n, m] = $5; max[n, m] = $6; fast[n, m] = $7
			if (m > mb) mb = m
			if ($6 + 0 > top) top = $6 + 0
		}
		END {
			BH = 14; GAP = 4; GROUP = 22
			W = 900; L = 210; R = 690; T = 112
			# Each group: one bar per ramp, as many as the group with the most.
			GH = mb * BH + (mb - 1) * GAP + GROUP
			H = T + n * GH + 50
			xs = top > 600000 ? 100000 : 50000
			x1 = int((top + xs - 1) / xs) * xs
			B = T + n * GH - GROUP + 8
			printf "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"-apple-system, Segoe UI, Helvetica, Arial, sans-serif\">\n", W, H, W, H
			print "<style>"
			print "svg { --surface: #fcfcfb; --text: #0b0b0b; --text2: #52514e; --muted: #898781; --grid: #e1e0d9; --s1: #2a78d6; --s2: #eb6834; --s3: #1baf7a; --s4: #eda100; --s5: #e87ba4; --s6: #008300 }"
			print "@media (prefers-color-scheme: dark) { svg { --surface: #1a1a19; --text: #f0efec; --text2: #c3c2b7; --muted: #898781; --grid: #2c2c2a; --s1: #3987e5; --s2: #d95926; --s3: #199e70; --s4: #c98500; --s5: #d55181; --s6: #0ca30c } }"
			print ".bg { fill: var(--surface) } .t { fill: var(--text) } .t2 { fill: var(--text2) } .m { fill: var(--muted) }"
			print ".g { stroke: var(--grid); stroke-width: 1 }"
			print ".d1 { fill: var(--s1) } .d2 { fill: var(--s2) } .d3 { fill: var(--s3) } .d4 { fill: var(--s4) } .d5 { fill: var(--s5) } .d6 { fill: var(--s6) } .pale { fill-opacity: 0.3 }"
			print "</style>"
			printf "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>\n", W, H
			printf "<text class=\"t\" x=\"%d\" y=\"30\" font-size=\"16\" font-weight=\"600\">%s</text>\n", 12, title
			printf "<text class=\"t2\" x=\"%d\" y=\"52\" font-size=\"12\">%s</text>\n", 12, subtitle
			# The key: a solid and a pale swatch.
			printf "<rect class=\"m\" x=\"%d\" y=\"66\" width=\"18\" height=\"10\" rx=\"2\"/>\n", 12
			printf "<text class=\"t2\" x=\"%d\" y=\"75\" font-size=\"12\">max req/s with p99 &lt; %s</text>\n", 36, limit
			printf "<rect class=\"m pale\" x=\"%d\" y=\"66\" width=\"18\" height=\"10\" rx=\"2\"/>\n", 230
			printf "<text class=\"t2\" x=\"%d\" y=\"75\" font-size=\"12\">max req/s, the last step kept up with</text>\n", 254
			printf "<text class=\"m\" x=\"%d\" y=\"94\" font-size=\"11\">One bar per ramp, r1 then r2.</text>\n", 12
			# Gridlines and x labels.
			for (v = 0; v <= x1; v += xs) {
				printf "<line class=\"g\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%d\" y2=\"%d\"/>\n", x(v), x(v), T - 6, B
				printf "<text class=\"m\" x=\"%.1f\" y=\"%d\" font-size=\"11\" text-anchor=\"middle\">%s</text>\n", x(v), B + 16, (v == 0 ? "0" : k(v))
			}
			printf "<text class=\"t2\" x=\"%d\" y=\"%d\" font-size=\"12\" text-anchor=\"middle\">requests per second</text>\n", (L + R) / 2, B + 38
			# The groups: the scheduler and its build, then a bar per ramp.
			for (i = 1; i <= n; i++) {
				gy = T + (i - 1) * GH
				printf "<text class=\"t\" x=\"%d\" y=\"%d\" font-size=\"12\">%s</text>\n", 12, gy + 12, sch[i]
				printf "<text class=\"m\" x=\"%d\" y=\"%d\" font-size=\"11\">build %s</text>\n", 12, gy + 27, jdk[i]
				for (j = 1; j <= nb[i]; j++) {
					by = gy + (j - 1) * (BH + GAP)
					printf "<text class=\"m\" x=\"%d\" y=\"%d\" font-size=\"10\" text-anchor=\"end\">%s</text>\n", L - 6, by + 11, run[i, j]
					if (max[i, j] > 0)
						printf "<rect class=\"d%s pale\" x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"%d\" rx=\"2\"/>\n", color[i], L, by, x(max[i, j]) - L, BH
					if (fast[i, j] > 0)
						printf "<rect class=\"d%s\" x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"%d\" rx=\"2\"/>\n", color[i], L, by, x(fast[i, j]) - L, BH
					# The two values: max req/s, then max req/s with p99 < the limit.
					printf "<text class=\"t2\" x=\"%.1f\" y=\"%d\" font-size=\"11\">%s · p99 &lt; %s: %s</text>\n", x(max[i, j]) + 6, by + 11,
						(max[i, j] > 0 ? k(max[i, j]) : "none"), limit, (fast[i, j] > 0 ? k(fast[i, j]) : "none")
				}
			}
			print "</svg>"
		}' "$1"
}

# svg <csv> <metric> <title> <subtitle>: one chart, one line per scheduler and JDK. A point is the
# mean of the ramps at that step, r1 and r2; the line stops at the last step all the ramps kept up
# with. <metric> is p99 (log scale) or cpu.
svg() {
	awk -F, -v metric="$2" -v title="$3" -v subtitle="$4" '
		function x(v) { return L + (v - x0) / (x1 - x0) * (R - L) }
		function y(v) {
			if (metric == "p99") return B - (log(v) - log(y0)) / (log(y1) - log(y0)) * (B - T)
			return B - (v - y0) / (y1 - y0) * (B - T)
		}
		function ms(v) { return sprintf("%g ms", v / 1000) }
		NR == 1 { next }
		{
			k = $1 SUBSEP $2
			if (!(k in seen)) { seen[k] = ++n; jdk[n] = $1; sch[n] = $2; color[n] = $3; style[n] = $4 }
			i = seen[k]
			if (!((i SUBSEP $8) in runs)) { runs[i SUBSEP $8] = 1; nr[i]++ }
			v = metric == "p99" ? $7 : $6
			if (!((i SUBSEP $5) in cnt)) { rates[i] = rates[i] " " $5 }
			cnt[i SUBSEP $5]++; sum[i SUBSEP $5] += v
		}
		END {
			# The points: the steps all the ramps of a line kept up with, the mean of their values.
			for (i = 1; i <= n; i++) {
				m = split(rates[i], r, " ")
				for (j = 1; j <= m; j++) {
					if (cnt[i SUBSEP r[j]] < nr[i]) continue
					v = sum[i SUBSEP r[j]] / nr[i]
					np[i]++; px[i, np[i]] = r[j]; py[i, np[i]] = v
					if (min == "" || v < min) min = v
					if (max == "" || v > max) max = v
					if (rmin == "" || r[j] + 0 < rmin) rmin = r[j] + 0
					if (rmax == "" || r[j] + 0 > rmax) rmax = r[j] + 0
				}
			}
			W = 900; H = 460; L = 72; R = 600; T = 82; B = 392
			# The x axis, in thousands of req/s: ticks every 100k, or every 50k for short ramps.
			xs = rmax - rmin > 400000 ? 100000 : 50000
			x0 = int(rmin / xs) * xs; x1 = int((rmax + xs - 1) / xs) * xs
			if (metric == "p99") {
				y0 = 10 ^ int(log(min) / log(10)); y1 = 10 ^ int(log(max) / log(10) + 0.999999)
			} else {
				y0 = 0; y1 = int((max + 9.999) / 10) * 10
			}
			printf "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"-apple-system, Segoe UI, Helvetica, Arial, sans-serif\">\n", W, H, W, H
			print "<style>"
			print "svg { --surface: #fcfcfb; --text: #0b0b0b; --text2: #52514e; --muted: #898781; --grid: #e1e0d9; --s1: #2a78d6; --s2: #eb6834; --s3: #1baf7a }"
			print "@media (prefers-color-scheme: dark) { svg { --surface: #1a1a19; --text: #f0efec; --text2: #c3c2b7; --muted: #898781; --grid: #2c2c2a; --s1: #3987e5; --s2: #d95926; --s3: #199e70 } }"
			print ".bg { fill: var(--surface) } .t { fill: var(--text) } .t2 { fill: var(--text2) } .m { fill: var(--muted) }"
			print ".g { stroke: var(--grid); stroke-width: 1 } .g1 { stroke: var(--muted); stroke-width: 1 }"
			print ".l { fill: none; stroke-width: 2.2; stroke-linejoin: round; stroke-linecap: round } .dashed { stroke-dasharray: 7 4 }"
			print ".c1 { stroke: var(--s1) } .c2 { stroke: var(--s2) } .c3 { stroke: var(--s3) }"
			print ".d1 { fill: var(--s1) } .d2 { fill: var(--s2) } .d3 { fill: var(--s3) } .ring { stroke: var(--surface); stroke-width: 2 }"
			print "</style>"
			printf "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>\n", W, H
			printf "<text class=\"t\" x=\"%d\" y=\"30\" font-size=\"16\" font-weight=\"600\">%s</text>\n", 12, title
			printf "<text class=\"t2\" x=\"%d\" y=\"52\" font-size=\"12\">%s</text>\n", 12, subtitle
			# Gridlines and y labels.
			if (metric == "p99") {
				for (v = y0; v <= y1 * 1.0001; v *= 10) {
					printf "<line class=\"%s\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>\n", (v == 1000 ? "g1" : "g"), L, R, y(v), y(v)
					printf "<text class=\"m\" x=\"%d\" y=\"%.1f\" font-size=\"11\" text-anchor=\"end\">%s</text>\n", L - 8, y(v) + 4, ms(v)
				}
			} else {
				for (v = y0; v <= y1; v += 10) {
					printf "<line class=\"g\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>\n", L, R, y(v), y(v)
					printf "<text class=\"m\" x=\"%d\" y=\"%.1f\" font-size=\"11\" text-anchor=\"end\">%d</text>\n", L - 8, y(v) + 4, v
				}
			}
			# The x labels.
			for (v = x0; v <= x1; v += xs)
				printf "<text class=\"m\" x=\"%.1f\" y=\"%d\" font-size=\"11\" text-anchor=\"middle\">%dk</text>\n", x(v), B + 18, v / 1000
			printf "<text class=\"t2\" x=\"%d\" y=\"%d\" font-size=\"12\" text-anchor=\"middle\">requests per second, step of the ramp</text>\n", (L + R) / 2, B + 42
			printf "<text class=\"t2\" x=\"%d\" y=\"%d\" font-size=\"12\">%s</text>\n", 12, T - 14, (metric == "p99" ? "p99, log scale" : "cpu µs per request")
			# The lines, then the end dots on top.
			for (i = 1; i <= n; i++) {
				d = ""
				for (j = 1; j <= np[i]; j++) d = d sprintf("%s%.1f,%.1f", (j == 1 ? "" : " "), x(px[i, j]), y(py[i, j]))
				printf "<polyline class=\"l c%s%s\" points=\"%s\"><title>%s, build %s</title></polyline>\n", color[i], (style[i] == "dashed" ? " dashed" : ""), d, sch[i], jdk[i]
			}
			for (i = 1; i <= n; i++)
				printf "<circle class=\"d%s ring\" cx=\"%.1f\" cy=\"%.1f\" r=\"4\"/>\n", color[i], x(px[i, np[i]]), y(py[i, np[i]])
			# The legend: a line key, the scheduler, its JDK.
			lx = R + 28
			printf "<text class=\"t2\" x=\"%d\" y=\"%d\" font-size=\"12\" font-weight=\"600\">by scheduler and build</text>\n", lx, T + 4
			for (i = 1; i <= n; i++) {
				ly = T + 30 + (i - 1) * 40
				printf "<line class=\"l c%s%s\" x1=\"%d\" x2=\"%d\" y1=\"%d\" y2=\"%d\"/>\n", color[i], (style[i] == "dashed" ? " dashed" : ""), lx, lx + 28, ly - 4, ly - 4
				printf "<text class=\"t\" x=\"%d\" y=\"%d\" font-size=\"12\">%s</text>\n", lx + 36, ly, sch[i]
				printf "<text class=\"m\" x=\"%d\" y=\"%d\" font-size=\"11\">build %s</text>\n", lx + 36, ly + 15, jdk[i]
			}
			ly = T + 30 + n * 40
			printf "<text class=\"m\" x=\"%d\" y=\"%d\" font-size=\"11\">A point: the mean of r1 and r2.</text>\n", lx, ly
			printf "<text class=\"m\" x=\"%d\" y=\"%d\" font-size=\"11\">A line ends at the last step</text>\n", lx, ly + 16
			printf "<text class=\"m\" x=\"%d\" y=\"%d\" font-size=\"11\">both ramps kept up with.</text>\n", lx, ly + 32
			print "</svg>"
		}' "$1"
}

# measurecsv: one row per measure, r1, r2 and so on while there is one, for each group
# of BAR_SERIES and each case: jdk,scheduler,color,case,run,p99 us,cpu us/req,migrations %,GCs.
# The cases: small, servlet, big at depth 256; small0, big0 at depth 0. A run that is not a
# measurement, as the summaries say it, has empty values. So has "migrations unknown". GCs: the
# Pause lines of the GC log of the run, less the full GCs of the StackChunk counts, as in Controls.
measurecsv() {
	local jdk sch color c r file
	echo "jdk,scheduler,color,case,run,p99 us,cpu us/req,migrations %,GCs"
	while read -r jdk sch color; do
		[ -n "$jdk" ] || continue
		for c in small servlet big small0 big0; do
			for ((r = 1; ; r++)); do
				case $c in
					servlet) file=$RESULTS/$jdk/servlet/runs/servlet-r$r-$sch ;;
					*0) file=$RESULTS/$jdk/user/runs/${c%0}-r$r-$sch-0 ;;
					*) file=$RESULTS/$jdk/user/runs/$c-r$r-$sch-256 ;;
				esac
				[ -f "$file.out" ] || break
				if grep -q 'did not keep up\|no backend response\|pinning failed' "$file.out"; then
					echo "$jdk,$sch,$color,$c,r$r,,,,"
					continue
				fi
				awk -v pre="$jdk,$sch,$color,$c,r$r" -v gcs="$(grep Pause "$file.gc.log" | grep -vc 'Heap Inspection' || true)" '
					/^latency / { for (i = 2; i < NF; i++) if ($i == "p99") p99 = $(i + 1) }
					/^cpu / { cpu = $2 }
					/^migrations / && $2 ~ /%$/ { mig = $2; sub(/%$/, "", mig) }
					END { print pre "," p99 "," cpu "," mig "," gcs }' "$file.out"
			done
		done
	done <<<"$BAR_SERIES"
}

# dots <csv> <column> <scale> <cases> <titles> <title> <subtitle> <axis> <note>: one dot per run, a
# row per run of each group, one panel per case, on one shared axis. <cases>: the cases of the csv,
# as "small servlet"; <titles>: theirs, as "user small|servlet". <scale>: log, with a line at 1 ms
# and the value of each dot above it, or lin:<step>, from 0 with a gridline every <step>.
dots() {
	awk -F, -v col="$2" -v scale="$3" -v cases="$4" -v titles="$5" -v title="$6" -v subtitle="$7" -v axis="$8" -v note="$9" '
		function lg(v) { return log(v) / log(10) }
		function pos(v) { return log_ ? (lg(v) - y0) / (y1 - y0) : v / y1 }
		function x(p, v) { return P[p] + pos(v) * PW }
		function ms(v) { return v >= 1000 ? sprintf("%.1f ms", v / 1000) : sprintf("%.0f µs", v) }
		function lab(v) { return log_ ? ms(v) : v unit }
		BEGIN { split(cases, want, " "); for (k in want) shown[want[k]] = 1 }
		NR == 1 { next }
		# Only the cases of the chart: they also set the axis.
		!($4 in shown) { next }
		{
			g = $1 SUBSEP $2
			if (!(g in seen)) { seen[g] = 1; ++n; jdk[n] = $1; sch[n] = $2; color[n] = $3; gi[g] = n }
			# One dot per group and case: the mean of its runs. A run with no value is left out;
			# the runs kept are named when some are.
			k = gi[g] SUBSEP $4; runs[k]++
			if ($col != "") { sum[k] += $col; cnt[k]++; good[k] = good[k] (good[k] == "" ? "" : ", ") $5 }
		}
		END {
			for (k in cnt) {
				mean[k] = sum[k] / cnt[k]
				if (min == "" || mean[k] < min) min = mean[k]
				if (max == "" || mean[k] > max) max = mean[k]
			}
			log_ = scale == "log"
			unit = col == 8 ? "%" : ""
			if (log_) { y0 = int(lg(min)); y1 = int(lg(max) + 0.999999) }
			else { step = substr(scale, 5) + 0; y0 = 0; y1 = int((max + step - 0.001) / step) * step }
			BH = 14; GAP = 4; GROUP = 22
			W = 900; L = 210; T = 128; PG = 30
			np = split(cases, cs, " "); split(titles, ct, "|")
			# 40 px on the right: room for the text of a dot near the end of the last panel.
			PW = int((W - L - 40 - (np - 1) * PG) / np)
			for (p = 1; p <= np; p++) P[p] = L + (p - 1) * (PW + PG)
			GH = 42
			H = T + n * GH + 50
			B = T + n * GH - GROUP + 8
			printf "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"-apple-system, Segoe UI, Helvetica, Arial, sans-serif\">\n", W, H, W, H
			print "<style>"
			print "svg { --surface: #fcfcfb; --text: #0b0b0b; --text2: #52514e; --muted: #898781; --grid: #e1e0d9; --s1: #2a78d6; --s2: #eb6834; --s3: #1baf7a; --s4: #eda100; --s5: #e87ba4; --s6: #008300 }"
			print "@media (prefers-color-scheme: dark) { svg { --surface: #1a1a19; --text: #f0efec; --text2: #c3c2b7; --muted: #898781; --grid: #2c2c2a; --s1: #3987e5; --s2: #d95926; --s3: #199e70; --s4: #c98500; --s5: #d55181; --s6: #0ca30c } }"
			print ".bg { fill: var(--surface) } .t { fill: var(--text) } .t2 { fill: var(--text2) } .m { fill: var(--muted) }"
			print ".g { stroke: var(--grid); stroke-width: 1 } .g1 { stroke: var(--muted); stroke-width: 1 }"
			print ".d1 { fill: var(--s1) } .d2 { fill: var(--s2) } .d3 { fill: var(--s3) } .d4 { fill: var(--s4) } .d5 { fill: var(--s5) } .d6 { fill: var(--s6) }"
			print ".ring { stroke: var(--surface); stroke-width: 2 }"
			print "</style>"
			printf "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>\n", W, H
			printf "<text class=\"t\" x=\"12\" y=\"30\" font-size=\"16\" font-weight=\"600\">%s</text>\n", title
			printf "<text class=\"t2\" x=\"12\" y=\"52\" font-size=\"12\">%s</text>\n", subtitle
			printf "<text class=\"m\" x=\"12\" y=\"74\" font-size=\"11\">%s</text>\n", note
			# Each panel: its title, its gridlines, the labels of the inner ones: those at the edges
			# would touch the next panel.
			for (p = 1; p <= np; p++) {
				printf "<text class=\"t\" x=\"%d\" y=\"%d\" font-size=\"12\" font-weight=\"600\">%s</text>\n", P[p], T - 20, ct[p]
				for (e = y0; e <= y1; e += (log_ ? 1 : step)) {
					vv = log_ ? 10 ^ e : e
					printf "<line class=\"%s\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%d\" y2=\"%d\"/>\n", (log_ && vv == 1000 ? "g1" : "g"), x(p, vv), x(p, vv), T - 6, B
					if (e > y0 && e < y1)
						printf "<text class=\"m\" x=\"%.1f\" y=\"%d\" font-size=\"10\" text-anchor=\"middle\">%s</text>\n", x(p, vv), B + 16, (log_ ? (vv >= 1000 ? (vv / 1000) " ms" : vv " µs") : vv unit)
				}
			}
			printf "<text class=\"t2\" x=\"%d\" y=\"%d\" font-size=\"12\" text-anchor=\"middle\">%s</text>\n", L + (np * PW + (np - 1) * PG) / 2, B + 38, axis
			# The groups: the scheduler and its build, then a dot per case.
			for (i = 1; i <= n; i++) {
				gy = T + (i - 1) * GH
				cy = gy + 9
				printf "<text class=\"t\" x=\"12\" y=\"%d\" font-size=\"12\">%s</text>\n", gy + 12, sch[i]
				printf "<text class=\"m\" x=\"12\" y=\"%d\" font-size=\"11\">build %s</text>\n", gy + 27, jdk[i]
				for (p = 1; p <= np; p++) {
					k = i SUBSEP cs[p]
					if (!(k in runs)) continue
					if (!(k in mean)) {
						printf "<text class=\"m\" x=\"%d\" y=\"%d\" font-size=\"10\">not a measurement</text>\n", P[p], cy + 4
						continue
					}
					val = mean[k]; cx = x(p, val)
					part = cnt[k] < runs[k] ? "(" good[k] ")" : ""
					printf "<circle class=\"d%s ring\" cx=\"%.1f\" cy=\"%.1f\" r=\"5\"><title>%s, %s: %s %s</title></circle>\n", color[i], cx, cy, sch[i], ct[p], lab(val), part
					# Right of the dot: its value above 1 ms, and the runs kept when some were left out.
					# The gap before the next panel leaves it room.
					t = (log_ && val >= 1000 ? lab(val) : "") (part != "" ? " " part : "")
					if (t != "")
						printf "<text class=\"t2\" x=\"%.1f\" y=\"%.1f\" font-size=\"10\">%s</text>\n", cx + 9, cy + 4, t
				}
			}
			print "</svg>"
		}' "$1"
}

# walkcsv: one row per group of BAR_SERIES and panel: jdk,scheduler,color,panel,value. The values
# are copied from the summaries: the walk cycles/req of the Walk table, the other L3 of the perf
# c2c table. A missing value is left out.
walkcsv() {
	local jdk sch color u s v
	echo "jdk,scheduler,color,panel,value"
	while read -r jdk sch color; do
		[ -n "$jdk" ] || continue
		u=$RESULTS/$jdk/user/summary.md s=$RESULTS/$jdk/servlet/summary.md
		v=$(cell "$u" Walk "walk cycles/req" "scheduler=$sch case=small"); [ -z "$v" ] || echo "$jdk,$sch,$color,walk-small,${v//,/}"
		v=$(cell "$u" Walk "walk cycles/req" "scheduler=$sch case=big"); [ -z "$v" ] || echo "$jdk,$sch,$color,walk-big,${v//,/}"
		v=$(cell "$u" "perf c2c" "other L3" "scheduler=$sch case=small"); [ -z "$v" ] || echo "$jdk,$sch,$color,l3-small,${v%\%}"
		v=$(cell "$s" "perf c2c" "other L3" "scheduler=$sch"); [ -z "$v" ] || echo "$jdk,$sch,$color,l3-servlet,${v%\%}"
	done <<<"$BAR_SERIES"
}

# chunkscsv: one row per group of BAR_SERIES and panel: jdk,scheduler,color,panel,value. The live
# StackChunks of the c2c run of user small, from the `stackchunks` line of its console: during the
# warm-up (chunks-warm) and after the measure (chunks-after).
chunkscsv() {
	local jdk sch color file
	echo "jdk,scheduler,color,panel,value"
	while read -r jdk sch color; do
		[ -n "$jdk" ] || continue
		file=$RESULTS/$jdk/user/runs/small-c2c-$sch-256.out
		[ -f "$file" ] || continue
		awk -v pre="$jdk,$sch,$color" '/^stackchunks/ { print pre ",chunks-warm," $2; print pre ",chunks-after," $7 }' "$file"
	done <<<"$BAR_SERIES"
}

# panels <csv> <names> <titles> <subtitles> <title> <subtitle> <note>: one bar per group in each
# panel, each panel on its own axis from 0, the value at the end of each bar. <names>: the panels
# of the csv, as "walk-small l3-small"; <titles>, <subtitles>: theirs, separated by |. A panel
# whose name starts with l3 is a percentage.
panels() {
	awk -F, -v names="$2" -v titles="$3" -v subtitles="$4" -v title="$5" -v subtitle="$6" -v note="$7" '
		# 71178 -> 71,178: the awk of macOS has no thousands separator.
		function commas(v) { v = sprintf("%d", v); while (v ~ /[0-9]{4}/) sub(/[0-9]{3}($|,)/, ",&", v); return v }
		function fmt(p, v) { return p ~ /^l3/ ? sprintf("%.2f%%", v) : commas(v) }
		NR == 1 { next }
		{
			g = $1 SUBSEP $2
			if (!(g in seen)) { seen[g] = 1; ++n; jdk[n] = $1; sch[n] = $2; color[n] = $3; gi[g] = n }
			v[gi[g], $4] = $5
			if ($5 + 0 > top[$4]) top[$4] = $5 + 0
		}
		END {
			np = split(names, pn, " "); split(titles, t1, "|"); split(subtitles, t2, "|")
			BH = 14; GROUP = 22
			W = 900; L = 210; T = 128; PG = 20
			PW = int((W - L - 10 - (np - 1) * PG) / np)
			# Each bar ends at most 50 px before its panel ends: room for its value.
			for (p = 1; p <= np; p++) P[p] = L + (p - 1) * (PW + PG)
			GH = BH + GROUP
			H = T + n * GH + 24
			B = T + n * GH - GROUP + 8
			printf "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" font-family=\"-apple-system, Segoe UI, Helvetica, Arial, sans-serif\">\n", W, H, W, H
			print "<style>"
			print "svg { --surface: #fcfcfb; --text: #0b0b0b; --text2: #52514e; --muted: #898781; --grid: #e1e0d9; --s1: #2a78d6; --s2: #eb6834; --s3: #1baf7a; --s4: #eda100; --s5: #e87ba4; --s6: #008300 }"
			print "@media (prefers-color-scheme: dark) { svg { --surface: #1a1a19; --text: #f0efec; --text2: #c3c2b7; --muted: #898781; --grid: #2c2c2a; --s1: #3987e5; --s2: #d95926; --s3: #199e70; --s4: #c98500; --s5: #d55181; --s6: #0ca30c } }"
			print ".bg { fill: var(--surface) } .t { fill: var(--text) } .t2 { fill: var(--text2) } .m { fill: var(--muted) }"
			print ".g1 { stroke: var(--muted); stroke-width: 1 }"
			print ".d1 { fill: var(--s1) } .d2 { fill: var(--s2) } .d3 { fill: var(--s3) } .d4 { fill: var(--s4) } .d5 { fill: var(--s5) } .d6 { fill: var(--s6) }"
			print "</style>"
			printf "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>\n", W, H
			printf "<text class=\"t\" x=\"12\" y=\"30\" font-size=\"16\" font-weight=\"600\">%s</text>\n", title
			printf "<text class=\"t2\" x=\"12\" y=\"52\" font-size=\"12\">%s</text>\n", subtitle
			printf "<text class=\"m\" x=\"12\" y=\"74\" font-size=\"11\">%s</text>\n", note
			for (p = 1; p <= np; p++) {
				printf "<text class=\"t\" x=\"%d\" y=\"%d\" font-size=\"12\" font-weight=\"600\">%s</text>\n", P[p], T - 30, t1[p]
				printf "<text class=\"t2\" x=\"%d\" y=\"%d\" font-size=\"11\">%s</text>\n", P[p], T - 14, t2[p]
				printf "<line class=\"g1\" x1=\"%d\" x2=\"%d\" y1=\"%d\" y2=\"%d\"/>\n", P[p], P[p], T - 6, B
			}
			for (i = 1; i <= n; i++) {
				gy = T + (i - 1) * GH
				printf "<text class=\"t\" x=\"12\" y=\"%d\" font-size=\"12\">%s</text>\n", gy + 6, sch[i]
				printf "<text class=\"m\" x=\"12\" y=\"%d\" font-size=\"11\">build %s</text>\n", gy + 20, jdk[i]
				for (p = 1; p <= np; p++) {
					if (!((i, pn[p]) in v)) continue
					val = v[i, pn[p]]; w = top[pn[p]] > 0 ? val / top[pn[p]] * (PW - 50) : 0
					printf "<rect class=\"d%s\" x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"%d\" rx=\"2\"><title>%s, %s, %s: %s</title></rect>\n", color[i], P[p], gy, w, BH, sch[i], t1[p], t2[p], fmt(pn[p], val)
					printf "<text class=\"t2\" x=\"%.1f\" y=\"%d\" font-size=\"10\">%s</text>\n", P[p] + w + 4, gy + 11, fmt(pn[p], val)
				}
			}
			print "</svg>"
		}' "$1"
}

mkdir -p "$OUT"
csv user >"$OUT/user.csv"
csv servlet >"$OUT/servlet.csv"
svg "$OUT/user.csv" p99 "User benchmark: p99 by rate" "case small, depth 256; latency from the planned send time" >"$OUT/user-p99.svg"
svg "$OUT/user.csv" cpu "User benchmark: CPU per request by rate" "case small, depth 256; the server process minus the clients" >"$OUT/user-cpu.svg"
svg "$OUT/servlet.csv" p99 "Servlet benchmark: p99 by rate" "latency from the planned send time" >"$OUT/servlet-p99.svg"
svg "$OUT/servlet.csv" cpu "Servlet benchmark: CPU per request by rate" "the server process minus the clients" >"$OUT/servlet-cpu.svg"
maxcsv user >"$OUT/user-max.csv"
maxcsv servlet >"$OUT/servlet-max.csv"
maxcsv servlet-skewed >"$OUT/servlet-skewed-max.csv"
bars "$OUT/user-max.csv" small "User benchmark: max rate" "case small, depth 256" >"$OUT/user-small-max.svg"
bars "$OUT/user-max.csv" big "User benchmark: max rate" "case big, depth 256" >"$OUT/user-big-max.svg"
bars "$OUT/servlet-max.csv" servlet "Servlet benchmark: max rate" "10 containers, one per carrier" >"$OUT/servlet-max.svg"
bars "$OUT/servlet-skewed-max.csv" servlet "Servlet benchmark, skewed load: max rate" "20 containers; the 2 that start on carrier 0 get half the requests" >"$OUT/servlet-skewed-max.svg"
echo "wrote $OUT: user-p99.svg user-cpu.svg servlet-p99.svg servlet-cpu.svg user.csv servlet.csv"
echo "wrote $OUT: user-small-max.svg user-big-max.svg servlet-max.svg servlet-skewed-max.svg user-max.csv servlet-max.csv servlet-skewed-max.csv"
measurecsv >"$OUT/measure.csv"
KEPT="A run that did not keep up is left out."
# The charts of the measures, one set per benchmark: <bench>;<cases>;<titles>;<name>.
while IFS=';' read -r b cases titles name; do
	dots "$OUT/measure.csv" 6 log "$cases" "$titles" "$name benchmark: p99 at 150k" "latency from the planned send time" "p99" \
		"One dot per scheduler: the mean of its runs. Log scale; the darker line is 1 ms. $KEPT" >"$OUT/$b-p99-measure.svg"
	dots "$OUT/measure.csv" 7 lin:10 "$cases" "$titles" "$name benchmark: CPU per request at 150k" "the server process minus the clients" "cpu µs per request" \
		"One dot per scheduler: the mean of its runs. $KEPT" >"$OUT/$b-cpu-measure.svg"
	dots "$OUT/measure.csv" 8 lin:25 "$cases" "$titles" "$name benchmark: migrations at 150k" \
		"$([ "$b" = servlet ] && echo "the fraud detectors" || echo "every actor")" "messages run on another carrier than the message before" \
		"One dot per scheduler: the mean of its runs. One message in 64 of each actor is checked. $KEPT" >"$OUT/$b-migrations.svg"
done <<'SETS'
user;small big;user small|user big;User
servlet;servlet;servlet;Servlet
SETS
dots "$OUT/measure.csv" 7 lin:10 "small0 big0" "user small, depth 0|user big, depth 0" "User benchmark: CPU per request with no work per actor" \
	"depth 0: the actor does not walk its objects; at 150k" "cpu µs per request" \
	"One dot per scheduler: the mean of its runs. $KEPT" >"$OUT/user-cpu-depth0.svg"
dots "$OUT/measure.csv" 9 lin:5 small "user small" "User benchmark: GCs at 150k" \
	"the GC pauses of each run, the full GCs of the StackChunk counts left out" "GC pauses per run" \
	"One dot per scheduler: the mean of its runs. $KEPT" >"$OUT/user-gcs.svg"
dots "$OUT/measure.csv" 9 lin:5 servlet servlet "Servlet benchmark: GCs at 150k" \
	"the GC pauses of each run" "GC pauses per run" \
	"One dot per scheduler: the mean of its runs. $KEPT" >"$OUT/servlet-gcs.svg"
echo "wrote $OUT: <bench>-p99-measure.svg <bench>-cpu-measure.svg <bench>-migrations.svg user-cpu-depth0.svg <bench>-gcs.svg measure.csv"
walkcsv >"$OUT/walk-l3.csv"
panels "$OUT/walk-l3.csv" "walk-small walk-big l3-small" "walk cycles/req|walk cycles/req|other L3" \
	"user small|user big|user small" "User benchmark: walk cycles and loads from another L3" \
	"the walk: depth 256; other L3: the share of the sampled loads" \
	"One bar per scheduler, each panel on its own axis from 0. Walk: the walk run. Other L3: the c2c run." >"$OUT/user-walk-l3.svg"
panels "$OUT/walk-l3.csv" "l3-servlet" "other L3" "servlet" "Servlet benchmark: loads from another L3" \
	"the share of the sampled loads" "One bar per scheduler. The c2c run." >"$OUT/servlet-l3.svg"
chunkscsv >"$OUT/stackchunks.csv"
panels "$OUT/stackchunks.csv" "chunks-after" "live StackChunks" "after the measure" \
	"User benchmark: live stacks of virtual threads" "user small, depth 256: the c2c run" \
	"One bar per scheduler. The count during the warm-up is in stackchunks.csv." >"$OUT/user-stackchunks.svg"
echo "wrote $OUT: user-walk-l3.svg servlet-l3.svg walk-l3.csv user-stackchunks.svg stackchunks.csv"
