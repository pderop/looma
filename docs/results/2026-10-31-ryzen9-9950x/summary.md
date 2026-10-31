# User benchmark

```
date         2026-10-31 04:35:59 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-internal-pr227-731930d)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/bench/20261031-043559
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
- The schedulers: affine, mpsc, jdk, jdk-pinned.
- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to
  their own CPUs.
- affine: Looma's copy of the Netty-VirtualThread-Scheduler. mpsc: the MPSC scheduler of the JDK
  (openjdk/loom pull request 227). In both, each carrier is pinned to one CPU, and an actor never
  moves to another carrier.
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
| affine | 20.99 | 64.98 |
| mpsc | 15.48 | 63.24 |
| jdk | 36.25 | not a measurement |
| jdk-pinned | 43.83 | 75.13 |

cpu µs/req at depth 256, the mean of r1 to r3, as in In short.

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | walk µs | p50 µs | p99 µs | p99.9 µs | migrations |
|---|---|---|---|---|---|---|---|---|---|---|
| default | affine | 256 | 20.99 | 20.72–21.28 | -42.1% | 4.59 | 25 | 35–36 | 189–571 | 0.0% |
| default | mpsc | 256 | 15.48 | 14.81–15.83 | -57.3% | 4.01 | 20–21 | 29–32 | 70–96 | 0.0% |
| default | jdk | 256 | 36.25 | 35.74–36.60 |  | 9.16 | 33–35 | 86–90 | 2,445–3,131 | 70.9% |
| default | jdk-pinned | 256 | 43.83 | 43.57–44.34 | +20.9% | 15.03 | 43–45 | 87–94 | 166–433 | 65.4% |
| default | affine | 0 | 16.40 | 15.90–16.81 | -39.5% |  | 20–21 | 28–30 | 61–334 | 0.0% |
| default | mpsc | 0 | 11.48 | 11.08–11.82 | -57.6% |  | 16 | 24–26 | 34–123 | 0.0% |
| default | jdk | 0 | 27.09 | 26.61–27.42 |  |  | 21–23 | 31–32 | 782–1,947 | 86.5% |
| default | jdk-pinned | 0 | 28.80 | 28.50–29.21 | +6.3% |  | 19–20 | 28–30 | 38–50 | 85.1% |
| big | affine | 256 | 64.98 | 64.57–65.79 |  | 48.59 | 164–179 | 566–591 | 817–1,078 | 0.0% |
| big | mpsc | 256 | 63.24 | 62.12–63.81 |  | 51.70 | 117–125 | 455–498 | 907–1,022 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 828–1,146 | 22,790–37,847 | 38,470–57,934 | 5.2% |
| big | jdk-pinned | 256 | 75.13 | 73.40–76.75 |  | 46.02 | 380–561 | 1,528–2,136 | 2,370–3,226 | 11.1% |
| big | affine | 0 | 16.40 | 15.96–16.62 | -38.0% |  | 20–21 | 28–29 | 34–35 | 0.0% |
| big | mpsc | 0 | 11.54 | 11.43–11.65 | -56.4% |  | 15–16 | 24–26 | 30–53 | 0.0% |
| big | jdk | 0 | 26.46 | 25.87–27.16 |  |  | 20–21 | 30–31 | 490–1,044 | 86.7% |
| big | jdk-pinned | 0 | 29.11 | 28.61–30.01 | +10.0% |  | 18–21 | 27–30 | 34–45 | 85.2% |

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1
walk   = cpu µs/req at depth 256 - cpu µs/req at depth 0
```

From the console of each run, runs/<case>-<run>-<scheduler>-<depth>.out: cpu µs/req, the line
cpu; latencies, the line latency; migrations, the line migrations. cpu µs/req and migrations:
the mean of r1 to r3. cpu range and latencies: the lowest and the highest of r1 to r3. vs jdk is empty when jdk is not a measurement.
Step by step: [Where the numbers come from](../README.md#where-the-numbers-come-from).

## Controls

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| default | affine | 256 | 149,999 | 9 | 54 → 25 |  |
| default | mpsc | 256 | 149,999–150,000 | 7 | 3 → 1 |  |
| default | jdk | 256 | 149,999 | 7 | 25 → 25 |  |
| default | jdk-pinned | 256 | 149,999 | 7 | 28 → 28 |  |
| default | affine | 0 | 149,999 | 9–10 |  |  |
| default | mpsc | 0 | 149,999–150,000 | 7 |  |  |
| default | jdk | 0 | 149,999 | 7 |  |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 7 |  |  |
| big | affine | 256 | 150,001–150,002 | 8 | 138 → 39 |  |
| big | mpsc | 256 | 149,998–150,001 | 7 | 9 → 4 |  |
| big | jdk | 256 | 149,458–149,986 | 6–7 | 47 → 25 | not a measurement (r1 to r3, c2c run, stat run) |
| big | jdk-pinned | 256 | 150,001–150,008 | 6–7 | 26 → 54 |  |
| big | affine | 0 | 149,999–150,000 | 10 |  |  |
| big | mpsc | 0 | 149,999–150,000 | 8 |  |  |
| big | jdk | 0 | 149,999–150,000 | 8–9 |  |  |
| big | jdk-pinned | 0 | 149,999–150,000 | 8 |  |  |

From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the line
stackchunks of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 to r3. A run is not a measurement when its console says it could not keep up
with its rate, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../README.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| default | affine | 91,723 | 1.09 | 0.92 | 84,167 | 192,618,575,784 | 176,750,716,430 | 2,100,000 |  |
| default | mpsc | 71,391 | 1.13 | 0.88 | 62,954 | 149,922,066,427 | 132,203,014,918 | 2,100,000 |  |
| default | jdk | 133,352 | 0.88 | 1.14 | 151,753 | 280,039,390,067 | 318,681,134,553 | 2,100,000 |  |
| default | jdk-pinned | 124,822 | 0.69 | 1.46 | 181,767 | 262,126,958,721 | 381,710,110,860 | 2,100,000 |  |
| big | affine | 75,851 | 0.28 | 3.62 | 274,719 | 159,286,682,312 | 576,909,931,678 | 2,100,000 |  |
| big | mpsc | 64,727 | 0.24 | 4.18 | 270,518 | 135,925,784,002 | 568,088,564,425 | 2,100,000 |  |
| big | jdk | 53,174 | 0.18 | 5.50 | 292,445 | 111,666,280,094 | 614,134,366,750 | 2,100,000 | this run: not a measurement |
| big | jdk-pinned | 63,829 | 0.20 | 4.95 | 315,923 | 134,041,617,921 | 663,438,976,837 | 2,100,000 |  |

```
instructions/req       = instructions / requests
cycles/req             = cycles / requests
IPC                    = instructions / cycles
cycles per instruction = cycles / instructions = 1 / IPC
```

The stat run of each scheduler, at depth 256. instructions and cycles: the counts of perf
stat, summed over the server's threads (every thread but client-*), in
runs/<case>-stat-<scheduler>.csv. requests: 150,000 req/s × 14 s, the window of perf stat.
Step by step: [Where the numbers come from](../README.md#where-the-numbers-come-from).

## perf c2c

| case | scheduler | migrations | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |
|---|---|---|---|---|---|---|---|---|
| default | affine | 0.0% | 0.46% | 0.06% | 25,934 | 118 | 15 |  |
| default | mpsc | 0.0% | 0.34% | 0.03% | 22,607 | 76 | 6 |  |
| default | jdk | 69.8% | 1.28% | 0.25% | 38,870 | 497 | 96 |  |
| default | jdk-pinned | 65.3% | 1.54% | 0.26% | 54,545 | 840 | 140 |  |
| big | affine | 0.0% | 0.32% | 4.43% | 64,572 | 208 | 2,861 |  |
| big | mpsc | 0.0% | 0.36% | 4.55% | 61,849 | 221 | 2,812 |  |
| big | jdk | 3.1% | 0.44% | 5.73% | 57,911 | 257 | 3,317 | this run: not a measurement |
| big | jdk-pinned | 10.5% | 0.67% | 5.58% | 67,127 | 449 | 3,743 |  |

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
Step by step: [Where the numbers come from](../README.md#where-the-numbers-come-from).

## Epsilon

| case | scheduler | cpu µs/req G1 | cpu µs/req Epsilon | difference | heap used | note |
|---|---|---|---|---|---|---|
| default | affine | 20.99 | 21.14 | +0.15 | 9,122 MiB |  |
| default | mpsc | 15.48 | 16.32 | +0.84 | 7,271 MiB |  |
| default | jdk | 36.25 | 35.42 | -0.83 | 6,815 MiB |  |
| default | jdk-pinned | 43.83 | 44.79 | +0.96 | 6,748 MiB |  |
| big | affine | 64.98 | 70.84 | +5.86 | 6,602 MiB |  |
| big | mpsc | 63.24 | 70.64 | +7.40 | 5,653 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 5,190 MiB | this run: not a measurement |
| big | jdk-pinned | 75.13 | 78.65 | +3.52 | 5,053 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 to r3, as in In short. cpu µs/req Epsilon:
the line cpu of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../README.md#where-the-numbers-come-from).
