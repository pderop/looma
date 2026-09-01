# Machine prep for the HTTP cache-locality bench

Checklist to run once per machine (or per reboot) before `scripts/run-http-bench.sh`. None of
this is done by the script itself — see the "what the script does vs. doesn't" note at the
bottom. Skipping it doesn't make the run fail; it makes the numbers noise instead of signal.

Defaults below assume an i9-14900K (8 P-cores with HT on CPU 0-15, 16 E-cores on CPU 16-31,
2 MiB private L2 per P-core, 36 MiB shared L3). On another CPU, redo step 1 and recompute
`SERVER_CPUS`/`CLIENT_CPUS` from it — and re-derive `STATE_KIB_POINTS` / `CONNS_PER_CARRIER` against
the new private-L2 size, remembering that the per-carrier footprint is
`connsPerCarrier x 2 x stateKiB` (each connection owns a state array *and* a snapshot buffer).

## 1. Identify the real topology

```sh
lscpu -e     # cores, HT siblings, socket/core id
lscpu -C     # cache levels and what's shared vs. private
```

Pick `SERVER_CPUS` as **one thread per physical core**, never both HT siblings of the same
core — sharing them defeats the whole point (48K L1d and 2 MiB L2 shared instead of private).
Pick `CLIENT_CPUS` from cores outside that set, so the load generator never contends with the server for a
core.

## 2. Pin frequency (turbo off, governor performance)

```sh
sudo cpupower frequency-set -g performance
echo 1 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo
```

Without this, per-core frequency drift (on a 14900K, cores 6-7 boost to 6.0 GHz against 5.7 for
the rest) shows up as if it were locality signal. The script's `warn_if_turbo()` only **detects**
`no_turbo != 1` and prints these two commands — it never runs them. It does, however,
`record_environment()` the values of `no_turbo` and `scaling_governor` into `<output dir>/env.txt`
before the first run, so a campaign can be audited for this afterwards. **Check that file before
quoting any number from a run you did not personally prepare.**

Revert after benchmarking:
```sh
sudo cpupower frequency-set -g powersave
echo 0 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo
```

## 3. Allow `perf stat` / `perf c2c` without full root

```sh
cat /proc/sys/kernel/perf_event_paranoid
sudo sysctl kernel.perf_event_paranoid=1        # if it reads higher than 1

perf list | grep -i mem_load     # cpu_core/mem_load_retired.l2_hit|l3_hit/ must exist
perf list | grep -i xsnp         # snoop events perf c2c needs
```

If this isn't set, the run doesn't fail — `perf stat`/`perf c2c` just silently produce nothing
and the script prints `(... unavailable -- check perf_event_paranoid ...)`, so you lose the
headline metric (cycles/request) without an obvious error.

## 4. JDK

The bench needs the Loom JDK (Franz's scheduler). How to obtain it, and that a stock JDK 25 is
enough to *use* Looma without that scheduler: [README Prerequisites](../README.md#prerequisites).

```sh
sdk env                                                      # .sdkmanrc names 28-loom
# or, without SDKMAN:
export JAVA_HOME=$HOME/.sdkman/candidates/java/28-loom        # or wherever the Loom JDK lives
```

One JDK for everything: Maven builds on it and the scripts launch the measured JVMs with it.
`LOOM_JDK_HOME` still takes precedence in `scripts/loom-jdk.sh` if you want a run against a
different Loom build than the one you built with. The scripts exit with an explicit error if there
is no `java` where they look, or if what is there turns out to be a stock JDK 28 rather than a Loom
build — that last one otherwise only surfaces deep inside a run, as
`cannot find symbol: Thread.VirtualThreadScheduler`.

## 5. Tools on `PATH`

```sh
command -v jbang     # launches wrk/wrk2 (wrk2 only needed when RATE is set)
command -v taskset
command -v perf       # optional: c2c/stat steps degrade gracefully without it
```

`jbang`/`taskset` missing is fatal (script checks and exits); `perf` missing is not.

## 6. Isolate the benchmark cores (optional but recommended)

Nothing here is done or checked by the script.

- Keep IRQs off `SERVER_CPUS`/`CLIENT_CPUS` (`irqbalance` off, or steer `/proc/irq/*/smp_affinity`
  manually).
- Consider `isolcpus=` at boot for those CPUs if the machine runs other load.
- If you see sawtooth latency, disable deep C-states for the run
  (`cpupower idle-set -D 0`, or via BIOS) — optional, revert after.

## Quick pre-flight

```sh
lscpu -e; lscpu -C
cat /sys/devices/system/cpu/intel_pstate/no_turbo
cat /proc/sys/kernel/perf_event_paranoid
cpupower frequency-info | grep governor
command -v jbang taskset perf
```

## What `run-http-bench.sh` actually does for you

| Step | Script behavior |
|---|---|
| 1. Topology | Not read at all — `SERVER_CPUS`/`CLIENT_CPUS` are env vars with 14900K defaults. |
| 2. Turbo/governor | **Records** `no_turbo` and the governors into `<out>/env.txt`, and warns on stderr if turbo is on; never applies `cpupower`/`no_turbo` itself. |
| 3. `perf_event_paranoid` | **Records** it into `<out>/env.txt`, and warns on stderr if it reads higher than 1; never sets the sysctl itself. Failure still degrades to a printed warning at the point of use, run continues without those counters. |
| 4. JDK | Takes `LOOM_JDK_HOME` if set, else `JAVA_HOME`, else `java` on `PATH`; **verifies** it is a Loom JDK image and exits if not. |
| 5. `jbang`/`taskset` | **Verified**, exits if missing (`wrk2` only when `RATE` is set). `perf` only checked at point of use, absence is non-fatal. |
| 6. Core isolation | Not handled at all. |
| 7. The run itself | Fully automated: builds the jar, starts each server config pinned via `taskset`, waits for `"listening on port"`, runs a warm-up then the measured `wrk` (or `wrk2 -R`) run, captures `perf stat`/`perf c2c`, runs the nine measured configurations (carrier/stealing/jdk x 0/64/256 KiB of state per connection, uniform `<configuration>-<size>kib` labels) plus three `perf c2c` captures at the operating point, writes everything under `target/http-bench/<timestamp>/`. |

What each of the nine runs compares against, and how to read the output, is in the
[README's benchmark chapter](../README.md#the-http-cache-locality-benchmark); the most recent
measured campaign is in [`performance.md`](performance.md).
