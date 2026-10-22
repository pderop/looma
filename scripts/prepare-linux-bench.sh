#!/usr/bin/env bash
#
# Puts a Linux bench box into the state the user benchmark wants, and takes it back out again.
# Every knob here is one that otherwise shows up INSIDE the measurement: this benchmark compares
# schedulers by a few percent of throughput and by cache-miss counts, and a core that clocked
# differently, or a PMU that silently degraded to a software timer, moves those numbers by more
# than the effect being measured.
#
#   governor=performance     A core that idles at 800 MHz and ramps takes the ramp-up out on
#                            whichever run happened to touch it first. The carrier-affine run
#                            parks and wakes on the SAME cores by construction, so it is exactly
#                            the run whose cores an ondemand governor treats differently.
#   turbo off                Per-core turbo bins depend on how many neighbours are busy and how
#                            hot the package is, i.e. on the run before this one. It is the
#                            single biggest source of "the same run gave a different answer".
#   perf_event_paranoid=-1   Above 1, PMU sampling is denied: perf stat counts nothing and
#                            async-profiler's cpu engine falls back to a WALL-CLOCK ITIMER
#                            without failing, so you get a flame graph that looks fine and is
#                            measuring something else. perf c2c needs -1. At -1 every user of
#                            the machine can profile the whole system, until --restore.
#   kptr_restrict=0          Kernel addresses readable, so perf resolves kernel symbols instead
#                            of printing hex. The scheduler and I/O-poller frames this benchmark
#                            is about are all kernel-side.
#   nmi_watchdog=0           The watchdog holds one hardware counter open on every CPU. Freeing
#                            it keeps the perf stat event list off multiplexing, which would
#                            otherwise scale every counter by an estimate.
#
#   sudo scripts/prepare-linux-bench.sh          # apply (re-execs itself under sudo if needed)
#   scripts/prepare-linux-bench.sh --check       # print the current state, change nothing
#   sudo scripts/prepare-linux-bench.sh --restore
#
# NOTHING HERE SURVIVES A REBOOT, which is the point -- the box goes back to being a normal
# machine on its own. Apply saves what it found to a state file and --restore puts that back;
# applying twice does not overwrite the saved originals with the values this script just set.

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: scripts/prepare-linux-bench.sh [--check|--restore]

Sets the CPU frequency and perf-permission knobs, so that the benchmark's numbers stay
comparable between runs. Linux only, needs root
(it re-execs itself under sudo, keeping CPUS and STATE).

  --check      Print what is set now and what this script would change. No root, no writes.
  --restore    Put back the values saved when it was last applied, then forget them.

  CPUS=<list>  Narrow the governor change to these CPUs, as taskset writes them (0,2,4-7).
               Default: every CPU, which is what you want -- the load generator and the backend
               run on the CPUs the carriers do NOT get, and a client thread that
               throttles is a client thread that stops offering load.
  STATE=<file> Where the original values are saved. Default: /var/tmp/loom-bench-prep.state

perf_event_paranoid is set to -1, which perf c2c needs: until --restore, every user of the
machine can profile the whole system. Use this on a bench machine, not a shared one.

Turbo is intel_pstate/no_turbo where that exists, else cpufreq/boost; whichever the box has is
the one that gets set, and a box with neither is reported rather than failed.
USAGE
}

MODE=apply
PARANOID=-1

while [ $# -gt 0 ]; do
	case $1 in
		--check) MODE=check ;;
		--restore) MODE=restore ;;
		--help | -h) usage; exit 0 ;;
		*) echo "unknown option '$1'" >&2; usage >&2; exit 1 ;;
	esac
	shift
done

if [ "$(uname -s)" != "Linux" ]; then
	echo "this script is for the Linux bench box; this is $(uname -s)" >&2
	echo "(on macOS there is nothing to set -- and nothing to measure either: no taskset, and" >&2
	echo " the cores share their L2, so the locality signal reads as 0%)" >&2
	exit 1
fi

STATE=${STATE:-/var/tmp/loom-bench-prep.state}

# Re-exec under sudo rather than making the caller remember it. CPUS and STATE are passed by
# hand: some sudo versions (sudo-rs on Ubuntu) ignore -E.
if [ "$MODE" != check ] && [ "$(id -u)" -ne 0 ]; then
	command -v sudo >/dev/null 2>&1 || { echo "not root and no sudo here" >&2; exit 1; }
	echo "not root -- re-running under sudo"
	exec sudo env CPUS="${CPUS:-}" STATE="$STATE" "$0" "$@"
fi

NO_TURBO=/sys/devices/system/cpu/intel_pstate/no_turbo
BOOST=/sys/devices/system/cpu/cpufreq/boost
PARANOID_PATH=/proc/sys/kernel/perf_event_paranoid
KPTR_PATH=/proc/sys/kernel/kptr_restrict
WATCHDOG_PATH=/proc/sys/kernel/nmi_watchdog

expand_cpus() {
	local part from to cpu
	local IFS=,
	for part in $1; do
		case $part in
			*-*)
				from=${part%-*}
				to=${part#*-}
				for ((cpu = from; cpu <= to; cpu++)); do echo "$cpu"; done
				;;
			*) echo "$part" ;;
		esac
	done
}

cpu_list() {
	local dir
	if [ -n "${CPUS:-}" ]; then
		expand_cpus "$CPUS"
	else
		for dir in /sys/devices/system/cpu/cpu[0-9]*; do echo "${dir##*/cpu}"; done | sort -n
	fi
}

CPU_IDS=($(cpu_list))

read_value() { [ -r "$1" ] && cat "$1" || echo "<absent>"; }

governor_of() { read_value "/sys/devices/system/cpu/cpu$1/cpufreq/scaling_governor"; }

governors_now() {
	local cpu
	for cpu in "${CPU_IDS[@]}"; do governor_of "$cpu"; done | sort | uniq -c |
		awk '{ printf "%s%s x%s", (NR > 1 ? ", " : ""), $2, $1 } END { print "" }'
}

# The two spellings of the same setting, reported the way a human means it rather than the way
# the file spells it: no_turbo=1 and boost=0 both mean turbo is OFF.
turbo_now() {
	if [ -r "$NO_TURBO" ]; then
		[ "$(cat "$NO_TURBO")" = "1" ] && echo "off (no_turbo=1)" || echo "ON (no_turbo=0)"
	elif [ -r "$BOOST" ]; then
		[ "$(cat "$BOOST")" = "0" ] && echo "off (boost=0)" || echo "ON (boost=1)"
	else
		echo "<no intel_pstate/no_turbo or cpufreq/boost on this box>"
	fi
}

show_state() {
	printf '  %-22s %s\n' "governor" "$(governors_now)  (${#CPU_IDS[@]} CPUs)"
	printf '  %-22s %s\n' "turbo" "$(turbo_now)"
	printf '  %-22s %s\n' "perf_event_paranoid" "$(read_value "$PARANOID_PATH")"
	printf '  %-22s %s\n' "kptr_restrict" "$(read_value "$KPTR_PATH")"
	printf '  %-22s %s\n' "nmi_watchdog" "$(read_value "$WATCHDOG_PATH")"
}

write_value() {
	local path=$1 value=$2
	if [ ! -e "$path" ]; then
		echo "  skip    $path (not on this box)"
		return 0
	fi
	if ! echo "$value" > "$path" 2>/dev/null; then
		echo "  FAILED  $path <- $value" >&2
		return 0
	fi
	printf '  set     %-52s %s\n' "$path" "$value"
}

set_governor() {
	local want=$1 cpu
	# cpupower where it exists (it also nudges the driver's own limits), plain sysfs where the
	# distro did not install linux-tools. Both end at the same file.
	if command -v cpupower >/dev/null 2>&1; then
		if [ -n "${CPUS:-}" ]; then
			cpupower -c "$CPUS" frequency-set -g "$want" >/dev/null 2>&1 && {
				echo "  set     governor=$want on CPUS=$CPUS (cpupower)"; return 0; }
		else
			cpupower frequency-set -g "$want" >/dev/null 2>&1 && {
				echo "  set     governor=$want on all CPUs (cpupower)"; return 0; }
		fi
		echo "  cpupower failed -- falling back to sysfs" >&2
	fi
	local done=0
	for cpu in "${CPU_IDS[@]}"; do
		local path=/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor
		[ -w "$path" ] || continue
		echo "$want" > "$path" 2>/dev/null && done=$((done + 1))
	done
	if [ "$done" -eq 0 ]; then
		echo "  skip    governor (no writable scaling_governor -- no cpufreq driver?)"
	else
		echo "  set     governor=$want on $done CPUs (sysfs)"
	fi
}

set_turbo() {
	local off=$1 # 1 = turbo off
	if [ -e "$NO_TURBO" ]; then
		write_value "$NO_TURBO" "$off"
	elif [ -e "$BOOST" ]; then
		write_value "$BOOST" "$((1 - off))"
	else
		echo "  skip    turbo (neither intel_pstate/no_turbo nor cpufreq/boost)"
	fi
}

save_state() {
	local cpu
	# Only the FIRST apply records anything. A second one would otherwise save "performance"
	# and "turbo off" as the originals, and --restore would then restore the bench settings.
	if [ -e "$STATE" ]; then
		echo "state file $STATE already exists -- keeping the originals it holds"
		return 0
	fi
	{
		echo "# written by scripts/prepare-linux-bench.sh on $(date "+%Y-%m-%dT%H:%M:%S%z")"
		echo "PARANOID=$(read_value "$PARANOID_PATH")"
		echo "KPTR=$(read_value "$KPTR_PATH")"
		echo "WATCHDOG=$(read_value "$WATCHDOG_PATH")"
		[ -r "$NO_TURBO" ] && echo "NO_TURBO=$(cat "$NO_TURBO")"
		[ -r "$BOOST" ] && echo "BOOST=$(cat "$BOOST")"
		for cpu in "${CPU_IDS[@]}"; do
			local gov
			gov=$(governor_of "$cpu")
			[ "$gov" = "<absent>" ] || echo "GOV_$cpu=$gov"
		done
	} > "$STATE"
	echo "originals saved to $STATE"
}

case $MODE in
	check)
		echo "=== now ==="
		show_state
		echo
		echo "=== what apply would set ==="
		printf '  %-22s %s\n' "governor" "performance"
		printf '  %-22s %s\n' "turbo" "off"
		printf '  %-22s %s\n' "perf_event_paranoid" "$PARANOID"
		printf '  %-22s %s\n' "kptr_restrict" "0"
		printf '  %-22s %s\n' "nmi_watchdog" "0"
		[ -e "$STATE" ] && echo && echo "state file present: $STATE (--restore has something to put back)"
		;;

	apply)
		echo "=== before ==="
		show_state
		echo
		save_state
		echo
		echo "=== applying ==="
		set_governor performance
		set_turbo 1
		write_value "$PARANOID_PATH" "$PARANOID"
		write_value "$KPTR_PATH" 0
		write_value "$WATCHDOG_PATH" 0
		echo
		echo "=== after ==="
		show_state
		echo
		echo "Ready. Undo with: sudo $0 --restore   (a reboot undoes it too)"
		;;

	restore)
		if [ ! -e "$STATE" ]; then
			echo "no state file at $STATE -- nothing was saved, so there is nothing to put back." >&2
			echo "A reboot returns the box to its defaults." >&2
			exit 1
		fi
		echo "=== before ==="
		show_state
		echo
		echo "=== restoring from $STATE ==="
		# Read as data, not as shell: the file holds values this script wrote from sysfs, and
		# sourcing it would run whatever a later editor put there.
		while IFS='=' read -r key value; do
			case $key in
				'#'* | '') continue ;;
				PARANOID) [ "$value" = "<absent>" ] || write_value "$PARANOID_PATH" "$value" ;;
				KPTR) [ "$value" = "<absent>" ] || write_value "$KPTR_PATH" "$value" ;;
				WATCHDOG) [ "$value" = "<absent>" ] || write_value "$WATCHDOG_PATH" "$value" ;;
				NO_TURBO) write_value "$NO_TURBO" "$value" ;;
				BOOST) write_value "$BOOST" "$value" ;;
				GOV_*) write_value "/sys/devices/system/cpu/cpu${key#GOV_}/cpufreq/scaling_governor" "$value" ;;
			esac
		done < "$STATE"
		rm -f "$STATE"
		echo
		echo "=== after ==="
		show_state
		;;
esac
