# The user-recommendation cache-locality benchmark — method

A workload with no I/O and no locks, built to isolate one thing: what it costs when a scheduler
moves an actor off the core that holds its data. Server:
[`examples/…/examples/user`](../examples/src/main/java/io/github/pderop/looma/examples/user);
driver: [`scripts/run-user-bench.sh`](../scripts/run-user-bench.sh). Report format and every
number quoted below: see the javadoc of `Main` in that package.

## The workload

One `UserGroup` actor per core, each owning a private map of ~8 000 users (~640 KiB — fits inside
a core's L2). A request reads a user, follows a friend link, then that friend's friend, 256 times,
scoring each one. Every lookup depends on the previous one, so nothing can be prefetched — the walk
is only as fast as memory answers it. One client per group, one request in flight at a time, so
every reply leaves the actor's mailbox empty. That empty mailbox is the park: the moment the
scheduler picks which core the actor comes back on.

An actor that stays on its carrier keeps its map resident in that core's L2. An actor resumed on a
random core finds its map elsewhere, and every line it dirties has to be fetched again. Nothing is
shared between actors, so any cost measured here comes only from *where* the scheduler ran the
actor — never from contention.

Same binary, three JVM configurations:

| | carriers pinned | stealing | What it answers |
| --- | --- | --- | --- |
| `carrier` | yes | no | Does the locality exist, and what is it worth |
| `stealing` | yes | yes | What stealing costs — a measurement, not a default |
| `jdk` | — | — | The control: same code, same cores, no affinity |

## Running it

Two machine settings drift a core's clock during the 20 s measured window, and that drift shows up
as if it were scheduler signal, not noise. The script checks both, unconditionally, and warns on
stderr if either is wrong — it doesn't fail the run, and doesn't fix them for you:

```sh
cat /sys/devices/system/cpu/intel_pstate/no_turbo   # want 1 (turbo off)
sudo cpupower frequency-set -g performance
echo 1 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo

# revert both after benchmarking:
echo 0 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo
sudo cpupower frequency-set -g powersave
```

Defaults below assume the machine this benchmark was developed and run on: an Intel **Core i9-14900K**
(desktop Raptor Lake). Like every current Intel desktop chip, it is **hybrid**: 8 **P-cores**
(performance, each with a private 2 MiB L2, 2 hardware threads) and 16 **E-cores** (efficiency,
no hyperthreading, sharing a 4 MiB L2 per cluster of 4). `CORES=pcore`/`CORES=ecore` below picks
which set to pin carriers on. On a non-hybrid CPU (most server and laptop parts), or any machine
where the defaults don't fit, ignore `CORES` and set `CPUS` directly.

```sh
scripts/run-user-bench.sh                 # the E-cores of a 14900K (default)
CORES=pcore scripts/run-user-bench.sh     # its P-cores
CPUS=8-15 scripts/run-user-bench.sh       # any other machine: name the CPUs yourself
```

Each of the three configurations runs for ~25 s (5 s warm-up, 20 s measured) and prints:

```
throughput   121,029 req/s (2,421,057 requests in 20.0s)
latency      16.52 us/req (closed loop, 2 clients)
cpu          19.00 us/req (2.30 cores busy)
migrations   55.3% of 37,829 sampled parks resumed on another carrier
```

- **cpu us/req** is the real cost of a request, in CPU time. Read it before req/s — with one
  request in flight per group, nothing here is CPU-bound, so req/s partly reports wake-up latency.
- **migrations** is counted, not inferred: one park in 64 is traced and checked against the
  carrier it resumed on.

At the end the three runs are compared side by side, `jdk` as the baseline:

```
                  req/s     vs jdk  cpu us/req       vs jdk   cores busy    migrations
carrier          152138      25.7%       13.56       -28.6%         2.06          0.0%
jdk              121029       0.0%       19.00         0.0%         2.30         55.3%
```

(a shortened example — 2 groups, not the default 8). `carrier` costs fewer CPU cycles per request
and never migrates; `jdk` resumes an actor on whatever worker is free, on a different core than it
parked on more than half the time.

Nothing in the driver reads the topology, so on another CPU `CPUS` has to be set by hand — one CPU
per carrier, none shared with another carrier's sibling thread. `mvn clean install` must be redone
on the benchmark machine first: a preview class file only runs on the JDK build that produced it.

## Measuring it with `perf`

`perf` is the Linux kernel's built-in profiler: it reads hardware performance counters straight off
the CPU. Two of its subcommands are used here, for two different questions:

- **`perf stat`** just counts. Point it at a running process for a while and it prints totals for a
  fixed list of events — cycles burned, instructions retired, cache accesses. No sampling, no
  guessing: exact counts for exactly the window it ran.
- **`perf c2c`** ("cache-to-cache") is narrower and does something perf stat cannot: it samples
  individual memory loads and, for the slow ones, records *which cache* satisfied it — including
  whether the line had to be pulled out of another core's cache first. That's the one number this
  benchmark's whole hypothesis rests on (see `PERF=c2c` below).

`PERF=stat|c2c|both` attaches one or both to the JVM and prints extra tables at the end. Off by
default — the runs above need nothing but `taskset`.

```sh
PERF=stat scripts/run-user-bench.sh
PERF=c2c  scripts/run-user-bench.sh
PERF=both scripts/run-user-bench.sh
```

`perf` needs `perf_event_paranoid <= 1`:

```sh
cat /proc/sys/kernel/perf_event_paranoid
sudo sysctl kernel.perf_event_paranoid=1        # if it reads higher than 1, until next reboot

# to make it stick across reboots:
echo 'kernel.perf_event_paranoid=1' | sudo tee /etc/sysctl.d/local-perf.conf
```

Above that value the run still completes; the perf tables just come back empty, and the script
says so once, up front, rather than leaving blank columns to explain later.

### `PERF=stat`

For each scheduler, the script runs this against the JVM's pid, for a window inside the 20 s
measured:

```sh
perf stat -p <pid> -e task-clock,cycles,instructions,cache-references,cache-misses -- sleep <n>
```

Raw output, one line per event (on a hybrid P/E chip like the 14900K, perf resolves each bare event
name on both PMUs, and the one this run never touches reports `<not counted>`):

```
     31307.31 msec task-clock                 #  2.236 CPUs utilized
 123241258197      cpu_atom/cycles/           #  3.937 GHz
    <not counted>   cpu_core/cycles/
  57019119132      cpu_atom/instructions/     #  0.46  insn per cycle
    652174549      cpu_atom/cache-references/ # 20.831 M/sec
      2126856      cpu_atom/cache-misses/     #  0.33% of all cache refs
   14.004432693 seconds time elapsed
```

A raw counter is a total over that window, and the three schedulers never serve the same number of
requests in it — nothing is comparable until each counter is divided by requests actually served,
`req = req/s × seconds time elapsed` (`req/s` from `Main`'s own report, `seconds time elapsed` read
back from this `perf stat` run rather than assumed, since it can be a hair under the requested
sleep). The script does that division and prints:

```
               cycles/req     vs jdk       IPC  cache-miss/req   miss rate
carrier             45879     -36.6%     0.759             2.3        1.2%
jdk                 72365       0.0%     0.469             1.3        0.3%
```

Each column is one raw counter turned into a per-request rate, except IPC which relates two
counters to each other:

- **cycles/req** = `cycles / req`. Cost of one request, in cycles. Immune to clock speed and to
  how long the sampling window ran, unlike raw cycles or raw req/s. This is the headline number.
- **vs jdk** — `cycles/req` relative to the `jdk` row, so the control is always 0.0% and the
  others read as the percentage of cost that scheduler removed (negative) or added (positive).
- **IPC** = `instructions / cycles` — instructions retired per cycle, *not* divided by `req`.
  Low IPC means the core is mostly stalled waiting on memory, not computing; it's what explains
  *why* cycles/req differs, not another way of stating the request cost itself.
- **cache-miss/req** = `cache-misses / req` — how many memory accesses left the cache hierarchy
  entirely, per request.
- **miss rate** = `cache-misses / cache-references × 100` — of the memory accesses perf saw, the
  percentage that missed. Independent of `req`: a scheduler that does more total work per request
  (more references) can have a lower miss rate than one that does less, while still losing on
  cache-miss/req.

### `PERF=c2c`

Runs three *extra* ~25 s runs, one per scheduler, each captured instead with:

```sh
perf c2c record -p <pid> -- sleep <n>
perf c2c report --stdio
```

`perf c2c` samples slow loads and tags each one with why it was slow. The number that matters is
**HITM**: a load that found its line dirty in another core's cache and had to wait for that core to
hand it over — 70-90 cycles, the direct cost of a migration. Nothing is shared between actors here,
so a HITM can only mean the scheduler resumed an actor on a core other than the one that dirtied
its map. Raw `perf c2c report` output:

```
  Load Operations                   :     108185      <- sampled loads
  Load Local HITM                   :        132      <- line was dirty in another core's cache
  Load Remote HITM                  :          0      <- ...on another socket (0 = single socket)
```

The script reads those three numbers out of each run and prints:

```
                 load ops       HITM    HITM/load
carrier            104958          6        0.01%
jdk                108185        132        0.12%
```

Compare the **ratio**, not the totals — `perf c2c` samples, it doesn't count. Expect `carrier`
lowest and `jdk` highest: that ordering is the whole hypothesis, measured directly.
