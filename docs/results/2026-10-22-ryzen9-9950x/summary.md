# User benchmark

```
date         2026-10-22 15:54:04 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-testing-builds.shipilev.net-openjdk-jdk-loom-b59-20261017-1106)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/bench/20261022-1554
```

## The runs

150000 req/s. The cases, by users per group:

- default: 8000 users per group, about 640 KiB per group.
- big: 200000 users per group, about 16 MiB per group.

In each case, each scheduler ran 3 times at depth 256 and at depth 0 (r1 to r3), then once
more with perf c2c, once more with perf stat, and once more with the Epsilon GC, at depth
256. Each run: 5 seconds of warm-up, then 20 seconds measured.

- Open loop: the clients send at a fixed rate. A latency is from the planned send time of a
  request to its answer. cpu µs/req is the server's: the process minus the clients.
- Each carrier has a core to itself: the script refuses two carrier CPUs on one core.
- The schedulers: stealing-local, stealing-local-home, jdk, jdk-pinned.
- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to
  their own CPUs.
- stealing-local-home: the copied Netty Loom scheduler, stealing within an L3, actors never
  stolen. stealing-local: the same, but an idle carrier could also steal an actor. See
  [the older campaigns](../campaigns.md#older-campaigns).
- Heap: -Xms2g -Xmx2g -XX:+AlwaysPreTouch, G1, and a GC log per run. The epsilon run:
  -XX:+UseEpsilonGC -Xms12g -Xmx12g -XX:+AlwaysPreTouch. Epsilon frees nothing, so no GC runs:
  each allocation takes new memory.
- The c2c run and the stat run count during the measure, minus 3 seconds at each end. The c2c run
  also counts the live StackChunks (GC.class_histogram), during the warm-up and after the measure.
  The full report of each c2c run is in <case>-c2c-<scheduler>.txt, the files of every run in
  runs/.

## At a glance

| scheduler | default, cpu µs/req | big, cpu µs/req |
|---|---|---|
| stealing-local | 23.93 | 65.09 |
| stealing-local-home | 20.71 | 64.02 |
| jdk | 35.64 | not a measurement |
| jdk-pinned | 43.59 | 75.38 |

cpu µs/req at depth 256, the mean of r1 to r3, as in In short.

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | walk µs | p50 µs | p99 µs | p99.9 µs | migrations |
|---|---|---|---|---|---|---|---|---|---|---|
| default | stealing-local | 256 | 23.93 | 23.38–24.28 | -32.9% | 6.81 | 28–29 | 44–47 | 212–354 | 34.5% |
| default | stealing-local-home | 256 | 20.71 | 20.63–20.78 | -41.9% | 4.34 | 24–25 | 34–36 | 60–282 | 0.0% |
| default | jdk | 256 | 35.64 | 35.26–35.92 |  | 9.86 | 32–34 | 86–87 | 2,818–3,144 | 70.9% |
| default | jdk-pinned | 256 | 43.59 | 43.39–43.91 | +22.3% | 14.92 | 41–44 | 85–92 | 147–401 | 65.7% |
| default | stealing-local | 0 | 17.12 | 16.93–17.35 | -33.6% |  | 20–21 | 28–29 | 140–303 | 35.8% |
| default | stealing-local-home | 0 | 16.37 | 16.16–16.69 | -36.5% |  | 20–21 | 28–30 | 137–342 | 0.0% |
| default | jdk | 0 | 25.77 | 24.47–27.61 |  |  | 18–22 | 28–31 | 51–1,890 | 85.8% |
| default | jdk-pinned | 0 | 28.67 | 28.44–28.96 | +11.2% |  | 20–21 | 30 | 41–86 | 85.4% |
| big | stealing-local | 256 | 65.09 | 65.03–65.13 |  | 47.56 | 208–213 | 694–733 | 991–1,615 | 11.7% |
| big | stealing-local-home | 256 | 64.02 | 62.48–65.01 |  | 47.20 | 150–167 | 485–536 | 837–849 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 822–1,083 | 22,495–32,555 | 37,257–51,151 | 5.5% |
| big | jdk-pinned | 256 | 75.38 | 74.67–75.78 |  | 46.52 | 414–469 | 1,571–1,647 | 2,372–2,644 | 11.0% |
| big | stealing-local | 0 | 17.53 | 17.00–17.80 | -34.6% |  | 20–21 | 28–29 | 33–35 | 36.2% |
| big | stealing-local-home | 0 | 16.81 | 16.71–16.94 | -37.3% |  | 20–21 | 28–30 | 34–36 | 0.0% |
| big | jdk | 0 | 26.80 | 26.40–27.52 |  |  | 18–21 | 28–31 | 281–1,066 | 86.4% |
| big | jdk-pinned | 0 | 28.86 | 28.44–29.16 | +7.7% |  | 19–21 | 28–30 | 34–44 | 84.9% |

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1
walk   = cpu µs/req at depth 256 - cpu µs/req at depth 0
```

From the console of each run, runs/<case>-<run>-<scheduler>-<depth>.out: cpu µs/req, the line
cpu; latencies, the line latency; migrations, the line migrations. cpu µs/req and migrations:
the mean of r1 to r3. cpu range and latencies: the lowest and the highest of r1 to r3. vs jdk is empty when jdk is not a measurement.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## Controls

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| default | stealing-local | 256 | 149,999 | 9 | 25 → 27 |  |
| default | stealing-local-home | 256 | 149,999–150,000 | 8–9 | 30 → 27 |  |
| default | jdk | 256 | 149,999–150,000 | 7 | 25 → 27 |  |
| default | jdk-pinned | 256 | 149,999–150,000 | 7 | 17 → 18 |  |
| default | stealing-local | 0 | 149,999 | 9 |  |  |
| default | stealing-local-home | 0 | 149,999–150,005 | 9 |  |  |
| default | jdk | 0 | 149,999 | 7 |  |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 7 |  |  |
| big | stealing-local | 256 | 149,999–150,007 | 7–8 | 272 → 79 |  |
| big | stealing-local-home | 256 | 149,997–150,001 | 8 | 559 → 32 |  |
| big | jdk | 256 | 149,618–149,962 | 6 | 436 → 25 | not a measurement (r1 to r3, c2c run, stat run) |
| big | jdk-pinned | 256 | 149,999–150,010 | 6–7 | 38 → 43 |  |
| big | stealing-local | 0 | 149,999 | 10 |  |  |
| big | stealing-local-home | 0 | 149,996–149,999 | 10 |  |  |
| big | jdk | 0 | 149,999–150,000 | 8–9 |  |  |
| big | jdk-pinned | 0 | 149,999–150,000 | 8–9 |  |  |

From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the line
stackchunks of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 to r3. A run is not a measurement when its console says it could not keep up
with its rate, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| default | stealing-local | 95,745 | 0.95 | 1.05 | 100,276 | 201,064,843,881 | 210,578,556,577 | 2,100,000 |  |
| default | stealing-local-home | 112,527 | 1.28 | 0.78 | 88,027 | 236,306,340,145 | 184,856,852,410 | 2,100,000 |  |
| default | jdk | 136,978 | 0.93 | 1.07 | 146,625 | 287,653,929,416 | 307,912,670,779 | 2,100,000 |  |
| default | jdk-pinned | 125,195 | 0.69 | 1.46 | 182,451 | 262,910,519,371 | 383,148,076,911 | 2,100,000 |  |
| big | stealing-local | 71,055 | 0.25 | 4.08 | 289,662 | 149,214,620,942 | 608,290,265,907 | 2,100,000 |  |
| big | stealing-local-home | 69,921 | 0.24 | 4.10 | 286,445 | 146,833,675,385 | 601,534,826,296 | 2,100,000 |  |
| big | jdk | 51,812 | 0.18 | 5.48 | 283,678 | 108,805,830,498 | 595,723,129,908 | 2,100,000 | this run: not a measurement |
| big | jdk-pinned | 60,108 | 0.18 | 5.42 | 325,977 | 126,226,586,798 | 684,551,538,431 | 2,100,000 |  |

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
| default | stealing-local | 44.9% | 0.29% | 0.03% | 27,864 | 82 | 9 |  |
| default | stealing-local-home | 0.0% | 0.44% | 0.05% | 25,222 | 110 | 13 |  |
| default | jdk | 70.9% | 1.14% | 0.34% | 37,419 | 425 | 127 |  |
| default | jdk-pinned | 64.7% | 1.86% | 0.22% | 50,362 | 938 | 111 |  |
| big | stealing-local | 11.5% | 0.33% | 4.02% | 59,987 | 195 | 2,412 |  |
| big | stealing-local-home | 0.0% | 0.29% | 4.20% | 68,939 | 201 | 2,897 |  |
| big | jdk | 3.3% | 0.42% | 5.45% | 55,463 | 235 | 3,021 | this run: not a measurement |
| big | jdk-pinned | 14.3% | 0.53% | 5.28% | 67,245 | 358 | 3,550 |  |

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

## Epsilon

| case | scheduler | cpu µs/req G1 | cpu µs/req Epsilon | difference | heap used | note |
|---|---|---|---|---|---|---|
| default | stealing-local | 23.93 | 24.96 | +1.03 | 9,688 MiB |  |
| default | stealing-local-home | 20.71 | 21.51 | +0.80 | 9,099 MiB |  |
| default | jdk | 35.64 | 36.54 | +0.90 | 6,728 MiB |  |
| default | jdk-pinned | 43.59 | 44.12 | +0.53 | 6,872 MiB |  |
| big | stealing-local | 65.09 | 71.31 | +6.22 | 5,928 MiB |  |
| big | stealing-local-home | 64.02 | 70.99 | +6.97 | 6,227 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 5,162 MiB | this run: not a measurement |
| big | jdk-pinned | 75.38 | 78.46 | +3.08 | 4,966 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 to r3, as in In short. cpu µs/req Epsilon:
the line cpu of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).
