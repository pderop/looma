# User benchmark

```
date         2026-11-22 04:25:35 +0000
host         gabuzo-amd, Linux 7.0.0-43-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-noaff-33d7cd3)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench-user.sh --users-per-group 8000,200000 --depth 256 --rate 150000 --step 20000 --out bench/user-noaff-20261122-013751
```

## The runs

What the benchmark is: [benchmark-user.md](../../../benchmark-user.md).

150000 req/s. Each group has 8 backend connections.
The cases, by users per group:

- small: 8000 users per group, about 640 KiB per group.
- big: 200000 users per group, about 16 MiB per group.

Each scheduler ran 2 rounds in each case. A round: a measure at 150000 req/s, at depth 256 and at depth 0 (r<n>), then a ramp (r<n>-ramp).
Round 1 also has, between the two: a run with perf c2c, one with perf stat, one with perf record for the walk, and one with the Epsilon GC, at depth 256.
In a round, each of these goes through every scheduler before the next one starts. Each run is
a JVM of its own: 10 seconds of warm-up, then 20 seconds measured.

The ramp, at depth 256: after the warm-up, the rate starts at 150000 req/s and goes up by 20000 req/s at each step,
10 seconds measured, until a step is
not kept up with. Between two steps the clients go on sending, unmeasured: 2
seconds for the late answers, then 3 seconds to go up to the next rate.

- Open loop: the clients send at a fixed rate. A latency is from the planned send time of a
  request to its answer. cpu µs/req is the server's: the process minus the clients.
- Each carrier has a core to itself: the script refuses two carrier CPUs on one core.
- The schedulers: mpsc.
- The clients are pinned to their own CPUs.
- The JDK's poller mode is not set. On Linux, the JDK then uses mode 4 under the MPSC scheduler (one epoll per carrier), and mode 2 under jdk (pollers that run as virtual threads).
- Heap: -Xms2g -Xmx2g -XX:+AlwaysPreTouch, G1, and a GC log per run. The epsilon run:
  -XX:+UseEpsilonGC -Xms12g -Xmx12g -XX:+AlwaysPreTouch. Epsilon frees nothing, so no GC runs:
  each allocation takes new memory.
- The c2c, stat and walk runs count during the measure, minus 3 seconds at each end. The c2c run
  also counts the live StackChunks (GC.class_histogram), during the warm-up and after the measure.
  The walk run keeps UserGroupActor.walk out of line. The full report of each c2c run is in
  <case>-c2c-<scheduler>.txt, of each walk run in <case>-walk-<scheduler>.txt, the files of every
  run in runs/.

## At a glance

| scheduler | small, cpu µs/req | small, max req/s | small, max req/s with p99 < 1 ms | big, cpu µs/req | big, max req/s | big, max req/s with p99 < 1 ms |
|---|---|---|---|---|---|---|
| mpsc | 29.29 | 870k/830k | 770k/750k | 64.41 | 190k/190k | none/none |

cpu µs/req at depth 256, the mean of r1 and r2, as in In short.
max req/s: the highest rate kept up with by the ramp of each round at depth 256, r1/r2 ...: the
`ramp kept up with` line of its console, as in The ramp.
max req/s with p99 < 1 ms: a rate with an acceptable latency. In each ramp run, from the first step
up, the last step kept up with before the first step whose p99 is above 1 ms: the `latency`
lines of its console, step by step. none: the p99 of the first step is already above.

### The schedulers

- mpsc: the JDK's MPSC scheduler, **no carrier pinned** and **no affinity**: an actor can run
  on any carrier (`Carriers.mpsc(n)`).

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations | backend CPUs |
|---|---|---|---|---|---|---|---|---|---|---|
| small | mpsc | 256 | 29.29 | 29.03–29.55 |  | 37 | 85–86 | 120–131 | 37.0% | 0.85–0.88 |
| small | mpsc | 0 | 14.10 | 14.05–14.15 |  | 16 | 23 | 27 | 42.6% | 0.88–0.89 |
| big | mpsc | 256 | 64.41 | 64.33–64.49 |  | 275–288 | 1,258–1,489 | 3,258–7,037 | 5.7% | 0.78–0.79 |
| big | mpsc | 0 | 13.60 | 13.48–13.72 |  | 16–17 | 23–25 | 27–28 | 22.2% | 0.89–0.90 |

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1
```

From the console of each run, runs/<case>-<run>-<scheduler>-<depth>.out: cpu µs/req, the `cpu` line; latencies, the
`latency` line; migrations, the `migrations` line; backend CPUs, the `backend cpu` line: the CPU time of the
backend process, read from /proc, divided by the length of the measure. cpu µs/req and
migrations: the mean of r1 and r2. cpu range, backend CPUs and
latencies: the lowest and the highest of r1 and r2. vs jdk is empty when jdk is not a measurement.
When a run is not a measurement, its numbers are not in this table: the mean is that of the
other runs, named in parentheses after it, as (r2). The column note of Controls says which run
was left out.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## Controls

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| small | mpsc | 256 | 149,999 | 5–6 | 23 → 21 |  |
| small | mpsc | 0 | 149,999–150,000 | 6 |  |  |
| big | mpsc | 256 | 149,997–150,009 | 7 | 30 → 38 |  |
| big | mpsc | 0 | 149,999–150,000 | 6–7 |  |  |

From the files of each run, in runs/. req/s: the `throughput` line of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the
`stackchunks` line of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 and r2. A run is not a measurement when its console says it could not keep up
with its rate, its clients sent too late, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| small | mpsc | 67,977 | 0.55 | 1.81 | 122,987 | 142,750,982,865 | 258,272,138,324 | 2,100,000 |  |
| big | mpsc | 50,845 | 0.18 | 5.48 | 278,469 | 106,773,767,836 | 584,785,305,844 | 2,100,000 |  |

```
instructions/req       = instructions / requests
cycles/req             = cycles / requests
IPC                    = instructions / cycles
cycles per instruction = cycles / instructions = 1 / IPC
```

The stat run of each scheduler at depth 256. instructions and cycles: the counts of perf
stat, summed over the server's threads (every thread but client-*), in
runs/<case>-stat-<scheduler>.csv. requests: 150,000 req/s × 14 s, the window of perf stat.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## perf c2c

| case | scheduler | migrations | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |
|---|---|---|---|---|---|---|---|---|
| small | mpsc | 29.5% | 3.00% | 0.15% | 38,376 | 1,153 | 59 |  |
| big | mpsc | 5.2% | 0.60% | 6.03% | 60,279 | 359 | 3,633 |  |

```
other L3 = Load Remote HITM / Load Operations
DRAM     = Load DRAM / Load Operations
```

The c2c run of each scheduler at depth 256. Load Operations, Load Remote HITM and Load DRAM
are copied from its report, <case>-c2c-<scheduler>.txt, block Trace Event Information:

- Load Operations: the memory loads that perf sampled.
- Load Remote HITM: among them, the loads that took their line from behind another L3,
  because a core there had modified it.
- Load DRAM: among them, the loads served by the memory (Load Local DRAM + Load Remote DRAM).

migrations: the `migrations` line of the console of the c2c run, runs/<case>-c2c-<scheduler>-256.out.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## Walk

| case | scheduler | walk cycles/req | walk cycles | requests | note |
|---|---|---|---|---|---|
| small | mpsc | 67,816 | 142,413,928,417 | 2,100,000 |  |
| big | mpsc | 246,469 | 517,585,799,821 | 2,100,000 |  |

```
walk cycles/req = walk cycles / requests
```

The walk run of each scheduler, at depth 256: the same run, with UserGroupActor.walk kept out
of line (-XX:CompileCommand=dontinline), so that it has a name of its own. walk cycles: the
cycles that perf record gives to UserGroupActor.walk, the column Period of its report,
<case>-walk-<scheduler>.txt. It is only the walk: the scheduler and the kernel are not in it.
requests: 150,000 req/s × 14 s, the window of perf record.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## Epsilon

| case | scheduler | cpu µs/req G1 | cpu µs/req Epsilon | difference | heap used | note |
|---|---|---|---|---|---|---|
| small | mpsc | 29.29 | 28.93 | -0.36 | 5,065 MiB |  |
| big | mpsc | 64.41 | 65.79 | +1.38 | 5,582 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 and r2, as in In short. cpu µs/req Epsilon:
the `cpu` line of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## The ramp

| case | scheduler | run | kept up with req/s | cpu µs/req | p99 µs | missed req/s | achieved req/s | p99 µs |
|---|---|---|---|---|---|---|---|---|
| small | mpsc | r1 | 870,000 | 11.47 | 3467.3 | 890,000 | 888,631 | 12083.2 |
| small | mpsc | r2 | 830,000 | 12.00 | 2678.8 | 850,000 | 849,945 | 7163.9 |
| big | mpsc | r1 | 190,000 | 52.55 | 18382.8 | 210,000 | 201,636 | 134021.1 |
| big | mpsc | r2 | 190,000 | 52.66 | 47251.5 | 210,000 | 185,780 | 207749.1 |

From the console of each ramp run, at depth 256, runs/<case>-r<n>-ramp-<scheduler>-256.out: from 150000 req/s,
20000 req/s more at each step, 10s measured, until a step is not kept up with.
Each step is one block of the console, from its `step` line. The console has every step.

- kept up with req/s: the last step kept up with, the `ramp kept up with` line. none: not even the first.
- cpu µs/req, p99 µs: at that step, the `cpu` and `latency` lines of its block.
- missed req/s: the next step, the first not kept up with: its block says it did not keep up, or that a GET got no answer.
  -: the ramp stopped at its last rate, with no step missed.
- achieved req/s, p99 µs: at that missed step, the `throughput` and `latency` lines of its block.
