# User benchmark

```
date         2026-11-05 04:42:36 +0000
host         gabuzo-amd, Linux 7.0.0-42-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-internal-pr227-5766f81)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/walk-run/bench/20261105-044236
```

## The runs

150000 req/s. The cases, by users per group:

- default: 8000 users per group, about 640 KiB per group.
- big: 200000 users per group, about 16 MiB per group.

In each case, each scheduler ran 3 times at depth 256 and at depth 0 (r1 to r3), then once
more with perf c2c, once more with perf stat, once more with perf record for the walk, and
once more with the Epsilon GC, at depth 256. Each run: 5 seconds of warm-up, then
20 seconds measured.

- Open loop: the clients send at a fixed rate. A latency is from the planned send time of a
  request to its answer. cpu µs/req is the server's: the process minus the clients.
- Each carrier has a core to itself: the script refuses two carrier CPUs on one core.
- The schedulers: mpsc, jdk, jdk-pinned.
- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to
  their own CPUs.
- mpsc: the MPSC scheduler of the JDK (openjdk/loom pull request 227), behind Carriers.affine.
  Each carrier is pinned to one CPU, and an actor never moves to another carrier.
  The JDK's poller mode is not set: on Linux, it is mode 4, one epoll per carrier.
- Heap: -Xms2g -Xmx2g -XX:+AlwaysPreTouch, G1, and a GC log per run. The epsilon run:
  -XX:+UseEpsilonGC -Xms12g -Xmx12g -XX:+AlwaysPreTouch. Epsilon frees nothing, so no GC runs:
  each allocation takes new memory.
- The c2c, stat and walk runs count during the measure, minus 3 seconds at each end. The c2c run
  also counts the live StackChunks (GC.class_histogram), during the warm-up and after the measure.
  The walk run keeps UserGroup.walk out of line. The full report of each c2c run is in
  <case>-c2c-<scheduler>.txt, of each walk run in <case>-walk-<scheduler>.txt, the files of every
  run in runs/.

## At a glance

| scheduler | default, cpu µs/req | big, cpu µs/req |
|---|---|---|
| mpsc | 15.65 | 63.91 |
| jdk | 37.02 | not a measurement |
| jdk-pinned | 43.81 | 75.97 |

cpu µs/req at depth 256, the mean of r1 to r3, as in In short.

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations |
|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 256 | 15.65 | 15.14–16.00 | -57.7% | 20 | 29–30 | 38–189 | 0.0% |
| default | jdk | 256 | 37.02 | 35.32–38.35 |  | 34–38 | 85–90 | 2,265–2,591 | 69.0% |
| default | jdk-pinned | 256 | 43.81 | 43.56–44.08 | +18.4% | 43–44 | 91–92 | 192–468 | 65.1% |
| default | mpsc | 0 | 11.38 | 11.02–11.93 | -58.3% | 16 | 24–26 | 35–47 | 0.0% |
| default | jdk | 0 | 27.32 | 26.71–28.23 |  | 20–22 | 30–31 | 1,575–1,638 | 86.8% |
| default | jdk-pinned | 0 | 29.02 | 28.59–29.82 | +6.2% | 19–22 | 29–30 | 48–60 | 85.8% |
| big | mpsc | 256 | 63.91 | 62.87–64.50 |  | 121–127 | 459–497 | 969–1,016 | 0.0% |
| big | jdk | 256 | not a measurement |  |  | 676–1,381 | 27,247–37,126 | 46,760–55,574 | 5.4% |
| big | jdk-pinned | 256 | 75.97 | 75.56–76.52 |  | 480–548 | 1,755–2,020 | 2,642–3,084 | 9.5% |
| big | mpsc | 0 | 11.19 | 10.99–11.32 | -58.2% | 16 | 24–26 | 32–149 | 0.0% |
| big | jdk | 0 | 26.74 | 26.50–27.15 |  | 20–23 | 29–32 | 349–1,005 | 86.4% |
| big | jdk-pinned | 0 | 28.71 | 28.17–29.26 | +7.4% | 20–24 | 30–31 | 36–42 | 85.9% |

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1
```

From the console of each run, runs/<case>-<run>-<scheduler>-<depth>.out: cpu µs/req, the line
cpu; latencies, the line latency; migrations, the line migrations. cpu µs/req and migrations:
the mean of r1 to r3. cpu range and latencies: the lowest and the highest of r1 to r3. vs jdk is empty when jdk is not a measurement.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## Controls

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| default | mpsc | 256 | 149,999–150,000 | 6–7 | 2 → 3 |  |
| default | jdk | 256 | 149,999–150,000 | 7 | 32 → 29 |  |
| default | jdk-pinned | 256 | 149,999–150,000 | 7 | 25 → 26 |  |
| default | mpsc | 0 | 149,999–150,000 | 6–7 |  |  |
| default | jdk | 0 | 149,999 | 7 |  |  |
| default | jdk-pinned | 0 | 149,999 | 7 |  |  |
| big | mpsc | 256 | 149,999–150,002 | 7 | 6 → 4 |  |
| big | jdk | 256 | 149,352–149,938 | 6 | 25 → 36 | not a measurement (r1 to r3, c2c run, stat run) |
| big | jdk-pinned | 256 | 149,998–150,005 | 6–7 | 32 → 19 |  |
| big | mpsc | 0 | 149,998–150,004 | 8 |  |  |
| big | jdk | 0 | 149,999–150,000 | 8 |  |  |
| big | jdk-pinned | 0 | 149,999 | 8 |  |  |

From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the line
stackchunks of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 to r3. A run is not a measurement when its console says it could not keep up
with its rate, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 68,498 | 1.09 | 0.92 | 62,996 | 143,845,254,788 | 132,291,611,696 | 2,100,000 |  |
| default | jdk | 132,744 | 0.92 | 1.09 | 144,584 | 278,762,708,510 | 303,625,404,646 | 2,100,000 |  |
| default | jdk-pinned | 121,070 | 0.66 | 1.51 | 182,796 | 254,246,427,865 | 383,872,205,562 | 2,100,000 |  |
| big | mpsc | 63,913 | 0.24 | 4.16 | 266,007 | 134,218,122,715 | 558,615,491,590 | 2,100,000 |  |
| big | jdk | 50,459 | 0.17 | 5.88 | 296,533 | 105,962,959,735 | 622,718,456,733 | 2,100,000 | this run: not a measurement |
| big | jdk-pinned | 62,522 | 0.20 | 5.04 | 315,223 | 131,296,814,836 | 661,967,738,894 | 2,100,000 |  |

```
instructions/req       = instructions / requests
cycles/req             = cycles / requests
IPC                    = instructions / cycles
cycles per instruction = cycles / instructions = 1 / IPC
```

The stat run of each scheduler, at depth 256. instructions and cycles: the counts of perf
stat, summed over the server's threads (every thread but client-*), in
runs/<case>-stat-<scheduler>.csv. requests: 150,000 req/s × 14 s, the window of perf stat.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf c2c

| case | scheduler | migrations | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |
|---|---|---|---|---|---|---|---|---|
| default | mpsc | 0.0% | 0.34% | 0.04% | 21,488 | 73 | 9 |  |
| default | jdk | 66.6% | 1.37% | 0.28% | 37,546 | 514 | 104 |  |
| default | jdk-pinned | 64.6% | 1.98% | 0.13% | 52,204 | 1,034 | 69 |  |
| big | mpsc | 0.0% | 0.37% | 5.73% | 61,432 | 229 | 3,519 |  |
| big | jdk | 3.4% | 0.56% | 5.98% | 56,500 | 315 | 3,380 | this run: not a measurement |
| big | jdk-pinned | 10.5% | 0.67% | 4.53% | 64,405 | 431 | 2,917 |  |

```
other L3 = Load Remote HITM / Load Operations
DRAM     = Load DRAM / Load Operations
```

The c2c run of each scheduler, at depth 256. Load Operations, Load Remote HITM and Load DRAM
are copied from its report, <case>-c2c-<scheduler>.txt, block Trace Event Information:

- Load Operations: the memory loads that perf sampled.
- Load Remote HITM: among them, the loads that took their line from behind another L3,
  because a core there had modified it.
- Load DRAM: among them, the loads served by the memory (Load Local DRAM + Load Remote DRAM).

migrations: the line migrations of the console of the c2c run, runs/<case>-c2c-<scheduler>-256.out.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## Walk

| case | scheduler | walk cycles/req | walk cycles | requests | note |
|---|---|---|---|---|---|
| default | mpsc | 18,336 | 38,506,283,054 | 2,100,000 |  |
| default | jdk | 57,055 | 119,815,810,533 | 2,100,000 |  |
| default | jdk-pinned | 77,678 | 163,123,462,318 | 2,100,000 |  |
| big | mpsc | 240,537 | 505,127,965,208 | 2,100,000 |  |
| big | jdk | 261,871 | 549,928,658,827 | 2,100,000 | this run: not a measurement |
| big | jdk-pinned | 282,340 | 592,914,183,357 | 2,100,000 |  |

```
walk cycles/req = walk cycles / requests
```

The walk run of each scheduler, at depth 256: the same run, with UserGroup.walk kept out
of line (-XX:CompileCommand=dontinline), so that it has a name of its own. walk cycles: the
cycles that perf record gives to UserGroup.walk, the column Period of its report,
<case>-walk-<scheduler>.txt. It is only the walk: the scheduler and the kernel are not in it.
requests: 150,000 req/s × 14 s, the window of perf record.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## Epsilon

| case | scheduler | cpu µs/req G1 | cpu µs/req Epsilon | difference | heap used | note |
|---|---|---|---|---|---|---|
| default | mpsc | 15.65 | 15.85 | +0.20 | 6,375 MiB |  |
| default | jdk | 37.02 | 37.14 | +0.12 | 6,550 MiB |  |
| default | jdk-pinned | 43.81 | 44.01 | +0.20 | 6,556 MiB |  |
| big | mpsc | 63.91 | 70.39 | +6.48 | 5,309 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 5,115 MiB | this run: not a measurement |
| big | jdk-pinned | 75.97 | 78.60 | +2.63 | 5,308 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 to r3, as in In short. cpu µs/req Epsilon:
the line cpu of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).
