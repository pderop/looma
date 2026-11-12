# The results

`docs/results/` keeps one campaign, the latest: its summaries, and the files behind every number.
Each publish replaces it, in the same place: `summary.md`, `user/` and `servlet/`. [`../benchmark-user.md`](../benchmark-user.md) explains the user benchmark,
[`../benchmark-servlet.md`](../benchmark-servlet.md) the servlet one.

## The latest campaign

| Campaign | Machine | Schedulers | JDK |
| --- | --- | --- | --- |
| [2026-11-11](summary.md) | AMD Ryzen 9 9950X | `mpsc`, `mpsc-unpinned`, `mpsc-no-affinity`, `mpsc-unpinned-no-affinity`, `jdk`, `jdk-pinned` | openjdk/loom pull request 227, commit 5766f81 |

The machine and the layout: [`../benchmark.md`](../benchmark.md#amd-ryzen-9-9950x).

## What a campaign contains

`scripts/bench-user.sh` writes `bench/user-<date>-<time>/`, `scripts/bench-servlet.sh` writes
`bench/servlet-<date>-<time>/`. [`scripts/publish.sh`](../../scripts/publish.sh) copies them here,
into `user/` and `servlet/`, and writes the top `summary.md`:

```
summary.md                                          both benchmarks at a glance
user/summary.md                                     the user benchmark
user/<case>-c2c-<scheduler>.txt                     the perf c2c report
user/<case>-walk-<scheduler>.txt                    the perf report of the walk run: cycles per function
user/runs/<case>-<run>-<scheduler>-<depth>.out      the console of a run (default-r1-mpsc-256.out,
                                                    big-c2c-jdk-256.out, default-ramp1-mpsc-256.out)
user/runs/<case>-<run>-<scheduler>-<depth>.gc.log   its GC log
user/runs/<case>-stat-<scheduler>.csv               the perf stat counts, per thread
servlet/summary.md                                  the servlet benchmark
servlet/servlet-c2c-<scheduler>.txt                 the perf c2c report
servlet/runs/servlet-<run>-<scheduler>.out          the console of a run (servlet-r1-mpsc.out,
                                                    servlet-ramp1-jdk.out)
servlet/runs/servlet-<run>-<scheduler>.gc.log       its GC log, and backend.gc.log for the backend
servlet/runs/servlet-stat-<scheduler>.csv           the perf stat counts, per thread
<bench>/runs/*.data                                 the perf data: stays in bench/, not copied
```

The top `summary.md`: a header (date, machine, CPU, turbo, ITMT, JDK, CPU lists), then
one table, one row per scheduler: the columns of the At a glance of each benchmark, and the p99 of
the servlet one. Then The schedulers: one line per scheduler, what it is.

Each benchmark's `summary.md`: the same header, with the heap and the command, then these
sections. They give numbers, no analysis. Each one gives its table, then its formulas and its files:

| Section | Per | Gives |
| --- | --- | --- |
| The runs | | how the runs were made |
| At a glance | scheduler, case | cpu µs/req (the user benchmark: at depth 256), and max req/s; then The schedulers, one line per scheduler |
| In short | case, depth, scheduler | CPU and latency of r1 to r3 |
| Controls | case, depth, scheduler | rate reached, GC pauses, live StackChunks |
| perf stat | case, scheduler | from the stat run |
| perf c2c | case, scheduler | from the c2c run |
| Walk | case, scheduler | the user benchmark only: the cycles of the walk alone, from the walk run |
| Epsilon | case, scheduler | the user benchmark only: CPU with G1 and with Epsilon, heap used |
| The ramp | case, scheduler, ramp run | from the ramp runs: the last step kept up with and the first step missed |

The servlet benchmark has no case and no depth: its tables have no such column. Its tables have no
migrations and no StackChunks column either.

What each column means:

| Column | What it is |
| --- | --- |
| **cpu µs/req** | CPU per request, mean of r1 to r3. The main number |
| **cpu range** | lowest and highest of r1 to r3 |
| **max req/s** | the highest rate each ramp run kept up with: its line `ramp kept up with`. One value per ramp run |
| **max req/s with p99 < 1 ms** | in each ramp run, the last step before the p99 first goes above 1 ms: a rate with an acceptable latency. One value per ramp run |
| **backend CPUs** | the CPU time of the backend process during the measure, read from `/proc`, divided by its length |
| **vs jdk** | against `jdk`, same case and depth (servlet: same rate) |
| **walk cycles/req** | the cycles of `UserGroupActor.walk` alone, per request of the measure |
| **p50, p99, p99.9 µs** | latency from the planned send time; lowest and highest of r1 to r3 |
| **migrations** | how often an actor ran a message on another carrier than the one before; one message in 64 checked |
| **GCs** | GC pauses, lowest and highest of r1 to r3. About the same for every scheduler |
| **instructions/req, cycles/req** | of the server's threads, per request of the measure |
| **IPC, cycles per instruction** | instructions per cycle, and its inverse |
| **Load Operations, Load Remote HITM** | sampled loads, and those that took a line modified behind another L3 (on the 9950X: the other CCD) |
| **other L3** | the share of Load Remote HITM. Compare shares, not counts: runs sample different numbers of loads |
| **DRAM** | the share of the sampled loads served by memory |
| **live StackChunks** | during the warm-up → after the measure. A `StackChunk` holds a parked virtual thread's stack |
| **cpu µs/req G1, Epsilon** | the same scheduler with each GC |
| **heap used** | what the epsilon run allocated: Epsilon frees nothing |
| **note** | "not a measurement": a run did not keep up, its clients sent too late, a GET failed, or a pinning failed. In perf stat and perf c2c: "this run: …" (the stat or c2c run) or "r1 to r3: …" (that case and scheduler); the counts are then not comparable. In Controls: the runs that were not, as in "(r1 to r3, c2c run, stat run)" |

## Where the numbers come from

The formulas of each column. Each number is read from a file of `runs/` or a c2c report, or
computed from the same line. In the order of `summary.md`.

### In short and Controls

From the console of each run, `user/runs/<case>-<run>-<scheduler>-<depth>.out`, or
`servlet/runs/servlet-<run>-<scheduler>.out`:

```
req/s             line throughput
cpu µs/req        line cpu: mean, lowest and highest of r1 to r3
latencies         line latency (p50, p99, p99.9): lowest and highest of r1 to r3
migrations        line migrations: mean of r1 to r3 (the user benchmark)
backend CPUs      line backend cpu: lowest and highest of r1 to r3
live StackChunks  line stackchunks, of the c2c run
note              "not a measurement": the console says the run did not keep up, its clients
                  sent too late, a GET failed, or a pinning failed (jdk-pinned: the last line,
                  pinning, has fewer workers than carriers)
GCs               lines Pause of runs/<...>.gc.log, without the StackChunk counts' full GCs
                  (Heap Inspection)

vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth (servlet: same rate), - 1   (empty when jdk is not a measurement)
```

### perf stat

The line, from [`user/summary.md`](user/summary.md):

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| default | mpsc | 67,076 | 1.13 | 0.88 | 59,280 | 140,858,969,364 | 124,488,884,253 | 2,100,000 | |

From `user/runs/default-stat-mpsc.csv`, one line per thread and counter:

```
mpsc-carrier-0-284820,12791933037,,cycles,3293619417,100.00,,
mpsc-carrier-0-284820,14013156366,,instructions,3293650877,100.00,,
field 1: the thread   field 2: the count   field 4: what it counts   (the file's comment lists all)
```

```
instructions = sum of field 2 where field 4 = instructions, every thread but client-*
               (<not counted>: the thread did not run)
cycles       = the same, where field 4 = cycles
requests     = 150,000 req/s × 14 s (the perf stat window), not counted;
               the line throughput of user/runs/default-stat-mpsc-256.out shows the rate was kept

instructions/req       = instructions / requests  = 140,858,969,364 / 2,100,000       = 67,076
cycles/req             = cycles / requests        = 124,488,884,253 / 2,100,000       = 59,280
IPC                    = instructions / cycles    = 140,858,969,364 / 124,488,884,253 = 1.13
cycles per instruction = cycles / instructions    = 124,488,884,253 / 140,858,969,364 = 0.88
```

### perf c2c

From the c2c report `<case>-c2c-<scheduler>.txt`, block `Trace Event Information`: `Load Operations`,
`Load Remote HITM`, and Load DRAM = `Load Local DRAM` + `Load Remote DRAM`. migrations: the line
`migrations` of the c2c run's console.

```
other L3 = Load Remote HITM / Load Operations
DRAM     = Load DRAM / Load Operations
```

### Walk

The walk run is the run at depth 256, with two more JVM flags:

- `-XX:CompileCommand=dontinline` on `UserGroupActor.walk`: the JIT keeps the walk as a function
  of its own.
- `-XX:+DumpPerfMapAtExit`: at exit, the JVM writes the perf map, `/tmp/perf-<pid>.map`. It gives
  the name of each JIT-compiled function. `perf report` reads it, so it can name the walk.

From its perf report, `<case>-walk-<scheduler>.txt`: `perf record -e cycles` over the measure,
minus 3 s at each end, then `perf report --sort sym --show-total-period`. One line per function;
the column Period is in cycles.

```
walk cycles      Period on the line UserGroupActor.walk(...)
requests         rate × 14 s (the perf record window), not counted
walk cycles/req  walk cycles / requests
```

Example, big, mpsc, from `user/big-walk-mpsc.txt`:

```
    55.06%   403798944850  [.] long io.github.pderop.looma.examples.user.UserGroupActor.walk(java.lang.Integer)
    % of all Period        function

walk cycles/req = 403,798,944,850 / 2,100,000 = 192,285
```

The scheduler, the kernel and the GC are on other lines of the same report.

### Epsilon

```
cpu µs/req G1       the same number as In short, depth 256
cpu µs/req Epsilon  line cpu of runs/<case>-epsilon-<scheduler>-256.out
heap used           top - start, in the GC log's last heap line, runs/<case>-epsilon-<scheduler>-256.gc.log
difference          cpu µs/req Epsilon - cpu µs/req G1
```

Example, `user/runs/default-epsilon-mpsc-256.gc.log`:

```
space 12582912K,  60% used [0x0000000500000000, 0x00000006d31e1518, 0x0000000800000000)
                             start               top (next object)    end
heap used = 0x6d31e1518 - 0x500000000 = 7,836,931,352 bytes = 7,473 MiB
```

### The ramp

From the console of each ramp run, `user/runs/<case>-ramp<n>-<scheduler>-256.out` or
`servlet/runs/servlet-ramp<n>-<scheduler>.out`, one block per step, from its line `step`. The
console has every step; the table keeps two of them:

```
kept up with req/s           line ramp kept up with: the last step kept up with
cpu µs/req, p99 µs           at that step: lines cpu and latency of its block
missed req/s                 the next step: its block says the run or its clients did not keep up,
                             or that a GET got no answer
achieved req/s, p99 µs       at that missed step: lines throughput and latency of its block
max req/s (At a glance)      the same as kept up with req/s
max req/s with p99 < 1 ms    (At a glance) from the first step up, the last step kept up with before
                             the first step whose p99 is above 1 ms, line latency of each block.
                             none: the p99 of the first step is already above
```
