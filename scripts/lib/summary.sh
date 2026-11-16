# Reads the tables of the summaries written by scripts/bench-user.sh and scripts/bench-servlet.sh.
# Sourced by scripts/publish.sh and scripts/charts.sh.

# cell <summary> <section> <column> <filters>: one cell of the first table under "## <section>",
# in the first row whose columns match <filters>, as "scheduler=jdk case=small". Empty if none.
cell() {
	[ -f "$1" ] || return 0
	awk -v sect="## $2" -v col="$3" -v filt="$4" '
		function trim(v) { gsub(/^ +| +$/, "", v); return v }
		/^## / { in_s = $0 == sect; state = 0; next }
		!in_s { next }
		/^\|/ {
			if (state == 2) next
			n = split($0, f, "|")
			if (state == 0) { state = 1; for (i = 2; i < n; i++) h[trim(f[i])] = i; next }
			if (trim(f[2]) ~ /^-+$/) next
			ok = col in h
			m = split(filt, F, " ")
			for (j = 1; j <= m; j++) { split(F[j], kv, "="); if (!(kv[1] in h) || trim(f[h[kv[1]]]) != kv[2]) ok = 0 }
			if (ok) { print trim(f[h[col]]); exit }
			next
		}
		state == 1 { state = 2 }' "$1"
}
