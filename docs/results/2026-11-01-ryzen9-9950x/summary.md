# User benchmark

```
date         2026-11-01 21:46:10 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-internal-pr227-731930d)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out bench/20261101-214610
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
- The schedulers: mpsc, jdk, jdk-pinned.
- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to
  their own CPUs.
- mpsc: the MPSC scheduler of the JDK (openjdk/loom pull request 227), behind Carriers.affine.
  Each carrier is pinned to one CPU, and an actor never moves to another carrier.
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
| mpsc | 14.81 | 62.82 |
| jdk | 36.13 | not a measurement |
| jdk-pinned | 43.71 | 75.10 |

cpu µs/req at depth 256, the mean of r1 to r3, as in In short.

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | walk µs | p50 µs | p99 µs | p99.9 µs | migrations |
|---|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 256 | 14.81 | 14.31–15.77 | -59.0% | 3.90 | 20–21 | 30–32 | 49–494 | 0.0% |
| default | jdk | 256 | 36.13 | 34.90–36.94 |  | 9.15 | 32–35 | 83–92 | 2,494–3,488 | 70.9% |
| default | jdk-pinned | 256 | 43.71 | 43.45–44.11 | +21.0% | 14.84 | 44 | 89–92 | 138–246 | 65.2% |
| default | mpsc | 0 | 10.90 | 10.56–11.46 | -59.6% |  | 16 | 24–27 | 64–235 | 0.0% |
| default | jdk | 0 | 26.98 | 25.83–27.63 |  |  | 20–21 | 29–30 | 406–1,272 | 86.5% |
| default | jdk-pinned | 0 | 28.86 | 28.34–29.77 | +7.0% |  | 21–23 | 30–31 | 39–48 | 86.6% |
| big | mpsc | 256 | 62.82 | 61.74–63.61 |  | 51.63 | 116–125 | 450–510 | 885–1,122 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 1,118–1,974 | 19,317–42,762 | 38,601–67,830 | 3.1% |
| big | jdk-pinned | 256 | 75.10 | 74.37–76.07 |  | 46.35 | 408–491 | 1,488–1,808 | 2,240–2,679 | 11.5% |
| big | mpsc | 0 | 11.19 | 10.97–11.47 | -55.7% |  | 16 | 25–26 | 30–31 | 0.0% |
| big | jdk | 0 | 25.26 | 24.77–25.68 |  |  | 19 | 29–30 | 44–832 | 87.0% |
| big | jdk-pinned | 0 | 28.76 | 28.31–29.40 | +13.9% |  | 20–23 | 28–31 | 36–37 | 86.1% |

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
| default | mpsc | 256 | 149,999–150,051 | 7 | 0 → 7 |  |
| default | jdk | 256 | 149,999–150,000 | 7 | 30 → 25 |  |
| default | jdk-pinned | 256 | 150,000 | 7 | 27 → 29 |  |
| default | mpsc | 0 | 149,998–149,999 | 7 |  |  |
| default | jdk | 0 | 149,999–150,000 | 7 |  |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 7 |  |  |
| big | mpsc | 256 | 149,999–150,003 | 6–7 | 26 → 3 |  |
| big | jdk | 256 | 149,240–150,074 | 6–7 | 139 → 89 | not a measurement (r1 to r3, c2c run, stat run) |
| big | jdk-pinned | 256 | 150,003–150,016 | 6–7 | 65 → 25 |  |
| big | mpsc | 0 | 149,999 | 7–8 |  |  |
| big | jdk | 0 | 149,999–150,000 | 8 |  |  |
| big | jdk-pinned | 0 | 149,999–150,051 | 8–9 |  |  |

From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the line
stackchunks of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 to r3. A run is not a measurement when its console says it could not keep up
with its rate, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 69,045 | 1.11 | 0.90 | 62,116 | 144,993,497,176 | 130,444,552,351 | 2,100,000 |  |
| default | jdk | 127,377 | 0.86 | 1.16 | 147,751 | 267,491,627,124 | 310,277,945,714 | 2,100,000 |  |
| default | jdk-pinned | 121,493 | 0.67 | 1.49 | 180,851 | 255,134,588,594 | 379,786,156,097 | 2,100,000 |  |
| big | mpsc | 62,995 | 0.24 | 4.26 | 268,063 | 132,290,224,778 | 562,933,045,025 | 2,100,000 |  |
| big | jdk | 50,621 | 0.18 | 5.61 | 284,185 | 106,303,866,030 | 596,788,138,791 | 2,100,000 | this run: not a measurement |
| big | jdk-pinned | 62,598 | 0.20 | 5.04 | 315,726 | 131,456,698,973 | 663,025,293,005 | 2,100,000 |  |

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
| default | mpsc | 0.0% | 0.32% | 0.01% | 21,525 | 69 | 2 |  |
| default | jdk | 67.5% | 1.40% | 0.25% | 37,016 | 519 | 91 |  |
| default | jdk-pinned | 65.2% | 1.81% | 0.26% | 48,635 | 879 | 125 |  |
| big | mpsc | 0.0% | 0.44% | 5.19% | 65,511 | 290 | 3,400 |  |
| big | jdk | 2.3% | 0.50% | 5.40% | 58,540 | 290 | 3,161 | this run: not a measurement |
| big | jdk-pinned | 11.3% | 0.68% | 4.48% | 63,701 | 434 | 2,856 |  |

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
| default | mpsc | 14.81 | 16.10 | +1.29 | 6,323 MiB |  |
| default | jdk | 36.13 | 36.58 | +0.45 | 6,827 MiB |  |
| default | jdk-pinned | 43.71 | 44.10 | +0.39 | 6,528 MiB |  |
| big | mpsc | 62.82 | 70.68 | +7.86 | 5,629 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 5,108 MiB | this run: not a measurement |
| big | jdk-pinned | 75.10 | 78.56 | +3.46 | 4,845 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 to r3, as in In short. cpu µs/req Epsilon:
the line cpu of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).
