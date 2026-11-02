# The results

One folder per campaign, `<date>-<cpu>`: its `summary.md`, and the files behind every number.
[`../benchmark.md`](../benchmark.md) explains the benchmark.

## The campaigns

### AMD Ryzen 9 9950X

| Campaign | Schedulers | JDK | Note |
| --- | --- | --- | --- |
| [`2026-11-02-ryzen9-9950x`](2026-11-02-ryzen9-9950x/summary.md) | `mpsc`, `mpsc-mode3`, `jdk`, `jdk-pinned` | openjdk/loom pull request 227 | the latest; the client threads count their own answers |
| [`2026-10-24-ryzen9-9950x`](2026-10-24-ryzen9-9950x/summary.md) | `stealing-local`, `stealing-local-home` ([older names](#older-campaigns)), `jdk`, `jdk-pinned` | builds.shipilev.net | after [#9](https://github.com/pderop/looma/pull/9) |
| [`2026-10-22-ryzen9-9950x`](2026-10-22-ryzen9-9950x/summary.md) | the same four | builds.shipilev.net | before #9: the scan for an idle carrier started at a random one, not the first; #9 removed it |

The machine and the layout: [`../benchmark.md`](../benchmark.md#amd-ryzen-9-9950x).

## What a campaign contains

`scripts/bench.sh` writes it to `bench/<date>-<time>/`, [`scripts/publish.sh`](../../scripts/publish.sh)
copies it here:

```
summary.md                                     the results
<case>-c2c-<scheduler>.txt                     the perf c2c report
runs/<case>-<run>-<scheduler>-<depth>.out      the console of a run (default-r1-mpsc-256.out,
                                               big-c2c-jdk-256.out, default-epsilon-mpsc-256.out)
runs/<case>-<run>-<scheduler>-<depth>.gc.log   its GC log
runs/<case>-stat-<scheduler>.csv               the perf stat counts, per thread
runs/<case>-c2c-<scheduler>.data               the perf c2c data: stays in bench/, not copied
```

`summary.md`: a header (date, machine, CPU, turbo, JDK, CPU lists, heap, command), then these
sections. They give numbers, no analysis. Each one gives its table, then its formulas and its
files:

| Section | Per | Gives |
| --- | --- | --- |
| The runs | | how the runs were made |
| At a glance | scheduler, case | cpu µs/req at depth 256 |
| In short | case, depth, scheduler | CPU and latency of r1 to r3 |
| Controls | case, depth, scheduler | rate reached, GC pauses, live StackChunks |
| perf stat | case, scheduler | from the stat run |
| perf c2c | case, scheduler | from the c2c run |
| Epsilon | case, scheduler | CPU with G1 and with Epsilon, heap used |

What each column means:

| Column | What it is |
| --- | --- |
| **cpu µs/req** | CPU per request, mean of r1 to r3. The main number |
| **cpu range** | lowest and highest of r1 to r3 |
| **vs jdk** | against `jdk`, same case and depth |
| **walk µs** | the cost of reading the users |
| **p50, p99, p99.9 µs** | latency from the planned send time; lowest and highest of r1 to r3 |
| **migrations** | how often an actor ran a message on another carrier than the one before; one message in 64 checked |
| **GCs** | GC pauses, lowest and highest of r1 to r3. About the same for every scheduler |
| **instructions/req, cycles/req** | of the server's threads, per request of the measure |
| **IPC, cycles per instruction** | instructions per cycle, and its inverse. A low IPC: the core waits, most often for memory |
| **Load Operations, Load Remote HITM** | sampled loads, and those that took a line modified behind another L3 (on the 9950X: the other CCD) |
| **other L3** | the share of Load Remote HITM. Compare shares, not counts: runs sample different numbers of loads |
| **DRAM** | the share of the sampled loads served by memory |
| **live StackChunks** | during the warm-up → after the measure. A `StackChunk` holds a parked virtual thread's stack |
| **cpu µs/req G1, Epsilon** | the same scheduler with each GC. The difference is the GC's CPU, roughly: Epsilon never reuses memory |
| **heap used** | what the epsilon run allocated: Epsilon frees nothing |
| **note** | "not a measurement": a run did not keep up, a GET failed, or a pinning failed. In perf stat and perf c2c: "this run: …" (the stat or c2c run) or "r1 to r3: …" (that case and scheduler); the counts are then not comparable. In Controls: the runs that were not, as in "(r1 to r3, c2c run, stat run)" |

## Where the numbers come from

The formulas of each column. Each number is read from a file of `runs/` or a c2c report, or
computed from the same line. In the order of `summary.md`.

### In short and Controls

From the console of each run, `runs/<case>-<run>-<scheduler>-<depth>.out`:

```
req/s             line throughput
cpu µs/req        line cpu: mean, lowest and highest of r1 to r3
latencies         line latency (p50, p99, p99.9): lowest and highest of r1 to r3
migrations        line migrations: mean of r1 to r3
live StackChunks  line stackchunks, of the c2c run
note              "not a measurement": the console says the run did not keep up, a GET failed,
                  or a pinning failed (jdk-pinned: the last line, pinning, has fewer workers
                  than carriers)
GCs               lines Pause of runs/<...>.gc.log, without the StackChunk counts' full GCs
                  (Heap Inspection)

vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1   (empty when jdk is not a measurement)
walk   = cpu µs/req at depth 256 - cpu µs/req at depth 0
```

### perf stat

The line, from [`2026-11-02-ryzen9-9950x/summary.md`](2026-11-02-ryzen9-9950x/summary.md):

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| default | mpsc | 75,212 | 1.20 | 0.83 | 62,678 | 157,944,798,154 | 131,623,071,543 | 2,100,000 | |

From `2026-11-02-ryzen9-9950x/runs/default-stat-mpsc.csv`, one line per thread and counter:

```
mpsc-carrier-0-8436,11459908717,,cycles,2966130044,100.00,,
mpsc-carrier-0-8436,13118375273,,instructions,2966098824,100.00,,
field 1: the thread   field 2: the count   field 4: what it counts   (the file's comment lists all)
```

```
instructions = sum of field 2 where field 4 = instructions, every thread but client-*
               (<not counted>: the thread did not run)
cycles       = the same, where field 4 = cycles
requests     = 150,000 req/s × 14 s (the perf stat window), not counted;
               the line throughput of runs/default-stat-mpsc-256.out shows the rate was kept

instructions/req       = instructions / requests  = 157,944,798,154 / 2,100,000       = 75,212
cycles/req             = cycles / requests        = 131,623,071,543 / 2,100,000       = 62,678
IPC                    = instructions / cycles    = 157,944,798,154 / 131,623,071,543 = 1.20
cycles per instruction = cycles / instructions    = 131,623,071,543 / 157,944,798,154 = 0.83
```

### perf c2c

From the c2c report `<case>-c2c-<scheduler>.txt`, block `Trace Event Information`: `Load Operations`,
`Load Remote HITM`, and Load DRAM = `Load Local DRAM` + `Load Remote DRAM`. migrations: the line
`migrations` of the c2c run's console.

```
other L3 = Load Remote HITM / Load Operations
DRAM     = Load DRAM / Load Operations
```

### Epsilon

```
cpu µs/req G1       the same number as In short, depth 256
cpu µs/req Epsilon  line cpu of runs/<case>-epsilon-<scheduler>-256.out
heap used           top - start, in the GC log's last heap line, runs/<case>-epsilon-<scheduler>-256.gc.log
difference          cpu µs/req Epsilon - cpu µs/req G1
```

Example, `2026-11-02-ryzen9-9950x/runs/default-epsilon-mpsc-256.gc.log`:

```
space 12582912K,  52% used [0x0000000500000000, 0x0000000690b3cd58, 0x0000000800000000)
                             start               top (next object)    end
heap used = 0x690b3cd58 - 0x500000000 = 6,722,669,912 bytes = 6,411 MiB
```

## Older campaigns

2026-10-22 and 2026-10-24 ran two setups of Looma's copy of the Netty-VirtualThread-Scheduler,
then behind `Carriers.affine(n)`, on the Loom JDK 28 of builds.shipilev.net (b59):

| Name | What it was | Now |
| --- | --- | --- |
| `stealing-local-home` | stealing within an L3, actors never stolen | removed |
| `stealing-local` | the same, but an idle carrier could also steal an actor | removed |

Since 2026-10-31, the copy is no longer built, and `Carriers.affine(n)` runs the MPSC scheduler:
its runs are named `mpsc`.
