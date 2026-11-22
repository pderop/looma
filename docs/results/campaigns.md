# The campaigns

`docs/results/` keeps, for each JDK build, the latest run of each benchmark, in a folder named after
the build: its summary, and the files behind every number. [`summary.md`](summary.md) shows the
builds side by side. The machine and the layout: [`../benchmark.md`](../benchmark.md#amd-ryzen-9-9950x).

## What a campaign contains

`scripts/bench-user.sh` writes `bench/user-<date>-<time>/`, `scripts/bench-servlet.sh` writes
`bench/servlet-<date>-<time>/`. [`scripts/publish.sh`](../../scripts/publish.sh) copies them here,
into `<jdk>/user/` and `<jdk>/servlet/`, and writes `<jdk>/summary.md`. `<jdk>` comes from the line
java of the summaries, without the commit: `build 28-internal-noaff-<commit>` gives `noaff`. A new
commit of a build replaces its folder. One call takes the runs of one JDK or of several, and
replaces only the benchmarks it is given: a benchmark can be run and published alone, the others
stay. `scripts/publish.sh bench/*` works: what is not a finished campaign is skipped, and of two
runs of one benchmark on one build, the newer one is kept. When what stays comes from another commit of the same build, or another kernel, it prints a
warning and goes on. Then it fills the notes of the charts of the top
`summary.md`, between its `<!-- begin charts -->` and `<!-- end charts -->` lines, and
[`scripts/charts.sh`](../../scripts/charts.sh) redraws `charts/`.

A campaign with a skewed load (`--hot-carrier`) is `bench/user-skewed-<date>-<time>/` or
`bench/servlet-skewed-<date>-<time>/`, unless `--out` names it; `publish.sh` knows it from the
title of its summary. It goes into `<jdk>/user-skewed/` or `<jdk>/servlet-skewed/`, with the same
files as below. `<jdk>/summary.md` shows it in a second table. The servlet one has its chart,
`charts/servlet-skewed-max.svg`; the other charts leave it out.

```
summary.md                                          the charts of the builds side by side
charts/<bench>-p99.svg, charts/<bench>-cpu.svg      the p99 and the cpu µs/req at each step of each ramp; user: case small
charts/<bench>.csv                                  the numbers of these charts
charts/user-<case>-max.svg, charts/servlet-max.svg  the max rate of each ramp, r1 and r2
charts/servlet-skewed-max.svg                       the same, servlet with a skewed load
charts/<bench>-max.csv                              the numbers of these charts
charts/<bench>-p99-measure.svg, -cpu-measure.svg    the p99 and the cpu µs/req of the measures, the mean of the runs
charts/<bench>-migrations.svg, <bench>-gcs.svg      the migrations and the GC pauses of the same runs
charts/user-cpu-depth0.svg, charts/measure.csv      the cpu µs/req at depth 0; the numbers of these charts
charts/user-walk-l3.svg, charts/servlet-l3.svg      the walk cycles/req and the loads from another L3
charts/walk-l3.csv                                  the numbers of these charts
charts/user-stackchunks.svg, charts/stackchunks.csv the live StackChunks of the c2c run of user small
```

In the folder of each JDK:

```
summary.md                                          the date, build and kernel of each benchmark, then all at a glance
user/summary.md                                     the user benchmark
user/<case>-c2c-<scheduler>.txt                     the perf c2c report
user/<case>-walk-<scheduler>.txt                    the perf report of the walk run: cycles per function;
                                                    not copied when its perf record failed
user/runs/<case>-<run>-<scheduler>-<depth>.out      the console of a run (small-r1-mpsc-256.out,
                                                    big-c2c-jdk-256.out, small-r1-ramp-mpsc-256.out)
user/runs/<case>-<run>-<scheduler>-<depth>.gc.log   its GC log
user/runs/<case>-stat-<scheduler>.csv               the perf stat counts, per thread
servlet/summary.md                                  the servlet benchmark
servlet/servlet-c2c-<scheduler>.txt                 the perf c2c report
servlet/runs/servlet-<run>-<scheduler>.out          the console of a run (servlet-r1-mpsc.out,
                                                    servlet-r1-ramp-jdk.out)
servlet/runs/servlet-<run>-<scheduler>.gc.log       its GC log, and backend.gc.log for the backend
servlet/runs/servlet-stat-<scheduler>.csv           the perf stat counts, per thread
<bench>/runs/*.data                                 the perf data: stays in bench/, not copied
```

Each benchmark's `summary.md` gives numbers, no analysis: a header (date, machine, JDK, CPU
lists, heap, command), then one section per kind of run (At a glance, In short, Controls, perf
stat, perf c2c, the ramp; the user benchmark adds Walk and Epsilon). Each table is followed by its
formulas and its files. The servlet benchmark has no case, no depth, and no StackChunks column.

## Where the numbers come from

The formulas of each column. Each number is read from a file of `runs/` or a c2c report, or
computed from the same line. In the order of `summary.md`. The paths are in the folder of a JDK.

### In short and Controls

From the console of each run, `user/runs/<case>-<run>-<scheduler>-<depth>.out`, or
`servlet/runs/servlet-<run>-<scheduler>.out`:

```
req/s             line throughput
cpu µs/req        line cpu: mean, lowest and highest of r1 and r2
latencies         line latency (p50, p99, p99.9): lowest and highest of r1 and r2
migrations        line migrations: mean of r1 and r2 (the user benchmark)
backend CPUs      line backend cpu: lowest and highest of r1 and r2
live StackChunks  line stackchunks, of the c2c run: the stacks of parked virtual threads on the heap
note              "not a measurement (r1)": the console of r1 says the run did not keep up, its
                  clients sent too late, a GET failed, or a pinning failed (jdk-pinned: the last
                  line, pinning, has fewer workers than carriers). That run counts in req/s and
                  GCs only: the other numbers are those of the other runs
GCs               lines Pause of runs/<...>.gc.log, without the StackChunk counts' full GCs
                  (Heap Inspection)

vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth (servlet: same rate), - 1   (empty when jdk is not a measurement)
```

### perf stat

The table perf stat of `user/summary.md` has one row per case and scheduler; that of
`servlet/summary.md`, one row per scheduler, from `servlet/runs/servlet-stat-<scheduler>.csv`.
Its columns instructions, cycles and requests are the inputs; the other columns are computed from
them, on the same row.

From `user/runs/<case>-stat-<scheduler>.csv`, one line per thread and counter:

```
<thread>,<count>,,cycles,<time>,<percent>,,
<thread>,<count>,,instructions,<time>,<percent>,,
field 1: the thread   field 2: the count   field 4: what it counts   (the file's comment lists all)
```

```
instructions = sum of field 2 where field 4 = instructions, every thread but client-*
               (<not counted>: the thread did not run)
cycles       = the same, where field 4 = cycles
requests     = rate × 14 s (the perf stat window), not counted;
               the throughput line of user/runs/<case>-stat-<scheduler>-256.out shows the rate was kept

instructions/req       = instructions / requests
cycles/req             = cycles / requests
IPC                    = instructions / cycles
cycles per instruction = cycles / instructions
```

### perf c2c

From the c2c report `<case>-c2c-<scheduler>.txt`, block `Trace Event Information`: `Load Operations`,
`Load Remote HITM`, and Load DRAM = `Load Local DRAM` + `Load Remote DRAM`. migrations: the line
`migrations` of the c2c run's console.

```
other L3 = Load Remote HITM / Load Operations
DRAM     = Load DRAM / Load Operations
```

Compare these shares, not the counts: the runs sample different numbers of loads.

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

The line of the walk, in `user/<case>-walk-<scheduler>.txt`:

```
    <percent>%   <Period>  [.] long io.github.pderop.looma.examples.user.UserGroupActor.walk(java.lang.Integer)
    % of all     Period    function
```

The scheduler, the kernel and the GC are on other lines of the same report.

### Epsilon

```
cpu µs/req G1       the same number as In short, depth 256
cpu µs/req Epsilon  line cpu of runs/<case>-epsilon-<scheduler>-256.out
heap used           top - start, in the GC log's last heap line, runs/<case>-epsilon-<scheduler>-256.gc.log
difference          cpu µs/req Epsilon - cpu µs/req G1
```

The last heap line of `user/runs/<case>-epsilon-<scheduler>-256.gc.log`:

```
space <size>K, <percent>% used [<start>, <top>, <end>)
                                start    top (next object)    end
heap used = <top> - <start>, in bytes
```

### The ramp

From the console of each ramp, `user/runs/<case>-r<n>-ramp-<scheduler>-256.out` or
`servlet/runs/servlet-r<n>-ramp-<scheduler>.out`, one block per step, from its line `step`. The
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
