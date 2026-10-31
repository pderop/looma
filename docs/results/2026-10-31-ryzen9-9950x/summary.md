# User benchmark

```
date         2026-10-31 14:56:17 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-internal-pr227-731930d)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/bench/20261031-145617
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
| mpsc | 15.68 | 63.30 |
| jdk | 36.50 | not a measurement |
| jdk-pinned | 44.31 | 75.70 |

cpu µs/req at depth 256, the mean of r1 to r3, as in In short.

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | walk µs | p50 µs | p99 µs | p99.9 µs | migrations |
|---|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 256 | 15.68 | 15.66–15.72 | -57.0% | 4.64 | 20 | 30 | 54–436 | 0.0% |
| default | jdk | 256 | 36.50 | 35.84–37.33 |  | 9.69 | 33–37 | 87–88 | 2,355–3,389 | 69.7% |
| default | jdk-pinned | 256 | 44.31 | 44.10–44.48 | +21.4% | 14.84 | 43–44 | 88–92 | 153–296 | 66.0% |
| default | mpsc | 0 | 11.05 | 10.63–11.52 | -58.8% |  | 16–17 | 25–27 | 53–344 | 0.0% |
| default | jdk | 0 | 26.81 | 25.60–28.13 |  |  | 18–21 | 29–31 | 1,288–1,859 | 86.7% |
| default | jdk-pinned | 0 | 29.48 | 28.28–30.78 | +10.0% |  | 20–22 | 29–32 | 47–48 | 85.8% |
| big | mpsc | 256 | 63.30 | 63.01–63.55 |  | 51.88 | 120–123 | 458–500 | 903–1,140 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 1,033–1,799 | 28,082–48,923 | 45,548–77,464 | 4.0% |
| big | jdk-pinned | 256 | 75.70 | 75.12–76.15 |  | 47.12 | 426–489 | 1,686–1,708 | 2,666–2,943 | 10.5% |
| big | mpsc | 0 | 11.42 | 11.01–11.69 | -56.2% |  | 16 | 24–26 | 29–31 | 0.0% |
| big | jdk | 0 | 26.07 | 25.78–26.21 |  |  | 18–19 | 28–29 | 57–1,196 | 85.1% |
| big | jdk-pinned | 0 | 28.58 | 28.28–29.03 | +9.7% |  | 19–20 | 28–30 | 35–37 | 84.6% |

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
| default | mpsc | 256 | 149,999 | 7 | 3 → 2 |  |
| default | jdk | 256 | 149,999 | 7 | 30 → 26 |  |
| default | jdk-pinned | 256 | 149,999 | 7 | 32 → 27 |  |
| default | mpsc | 0 | 149,999–150,000 | 7 |  |  |
| default | jdk | 0 | 149,999–150,000 | 7 |  |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 7 |  |  |
| big | mpsc | 256 | 149,997–150,052 | 7 | 5 → 11 |  |
| big | jdk | 256 | 149,094–149,835 | 6 | 116 → 73 | not a measurement (r1 to r3, c2c run, stat run) |
| big | jdk-pinned | 256 | 150,001–150,009 | 6–7 | 161 → 50 |  |
| big | mpsc | 0 | 149,999–150,000 | 8 |  |  |
| big | jdk | 0 | 149,999 | 8–9 |  |  |
| big | jdk-pinned | 0 | 150,000 | 8 |  |  |

From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the line
stackchunks of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 to r3. A run is not a measurement when its console says it could not keep up
with its rate, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 72,191 | 1.09 | 0.92 | 66,165 | 151,600,539,312 | 138,947,122,045 | 2,100,000 |  |
| default | jdk | 127,715 | 0.82 | 1.22 | 156,122 | 268,201,008,636 | 327,855,764,954 | 2,100,000 |  |
| default | jdk-pinned | 130,455 | 0.72 | 1.39 | 180,696 | 273,955,513,749 | 379,461,009,066 | 2,100,000 |  |
| big | mpsc | 62,936 | 0.24 | 4.18 | 263,020 | 132,165,207,895 | 552,342,274,513 | 2,100,000 |  |
| big | jdk | 50,977 | 0.17 | 5.73 | 292,203 | 107,052,008,388 | 613,626,368,803 | 2,100,000 | this run: not a measurement |
| big | jdk-pinned | 57,141 | 0.18 | 5.68 | 324,511 | 119,995,851,905 | 681,472,226,288 | 2,100,000 |  |

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
| default | mpsc | 0.0% | 0.32% | 0.02% | 21,856 | 69 | 5 |  |
| default | jdk | 70.0% | 1.62% | 0.34% | 39,562 | 641 | 133 |  |
| default | jdk-pinned | 64.5% | 1.92% | 0.23% | 50,560 | 969 | 118 |  |
| big | mpsc | 0.0% | 0.43% | 4.66% | 61,532 | 267 | 2,867 |  |
| big | jdk | 2.3% | 0.46% | 6.44% | 46,046 | 214 | 2,964 | this run: not a measurement |
| big | jdk-pinned | 10.5% | 0.59% | 4.01% | 59,657 | 352 | 2,393 |  |

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
| default | mpsc | 15.68 | 16.80 | +1.12 | 6,810 MiB |  |
| default | jdk | 36.50 | 37.48 | +0.98 | 6,874 MiB |  |
| default | jdk-pinned | 44.31 | 43.91 | -0.40 | 6,706 MiB |  |
| big | mpsc | 63.30 | 70.68 | +7.38 | 5,424 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 5,142 MiB | this run: not a measurement |
| big | jdk-pinned | 75.70 | 78.68 | +2.98 | 5,336 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 to r3, as in In short. cpu µs/req Epsilon:
the line cpu of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).
