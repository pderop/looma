# User benchmark

```
date         2026-10-31 16:00:03 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-internal-pr227-731930d)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/bench/20261031-160003
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
| mpsc | 15.31 | 63.20 |
| jdk | 36.16 | not a measurement |
| jdk-pinned | 43.79 | 75.58 |

cpu µs/req at depth 256, the mean of r1 to r3, as in In short.

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | walk µs | p50 µs | p99 µs | p99.9 µs | migrations |
|---|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 256 | 15.31 | 14.94–15.73 | -57.7% | 4.63 | 20 | 30 | 39–279 | 0.0% |
| default | jdk | 256 | 36.16 | 34.89–37.41 |  | 10.64 | 33–35 | 85–88 | 2,501–2,828 | 70.0% |
| default | jdk-pinned | 256 | 43.79 | 43.35–44.11 | +21.1% | 15.01 | 43–44 | 89–93 | 176–189 | 65.1% |
| default | mpsc | 0 | 10.68 | 10.46–10.84 | -58.2% |  | 16–17 | 25–27 | 83–193 | 0.0% |
| default | jdk | 0 | 25.52 | 25.07–25.86 |  |  | 19–23 | 29–32 | 1,033–1,460 | 87.1% |
| default | jdk-pinned | 0 | 28.78 | 28.15–29.71 | +12.7% |  | 21–22 | 29–30 | 42–64 | 86.5% |
| big | mpsc | 256 | 63.20 | 62.26–64.13 |  | 52.43 | 117–126 | 430–476 | 847–992 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 1,016–1,772 | 26,411–50,561 | 54,002–84,083 | 3.8% |
| big | jdk-pinned | 256 | 75.58 | 74.84–76.12 |  | 46.67 | 449–497 | 1,768–1,837 | 2,599–2,888 | 10.3% |
| big | mpsc | 0 | 10.77 | 10.37–11.40 | -61.0% |  | 16–17 | 25–27 | 29–33 | 0.0% |
| big | jdk | 0 | 27.59 | 26.15–28.86 |  |  | 19–22 | 29–31 | 59–1,093 | 86.6% |
| big | jdk-pinned | 0 | 28.91 | 27.92–30.25 | +4.8% |  | 19–21 | 29–30 | 35–55 | 86.0% |

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
| default | mpsc | 256 | 149,999–150,000 | 7 | 0 → 2 |  |
| default | jdk | 256 | 149,999–150,002 | 7 | 26 → 26 |  |
| default | jdk-pinned | 256 | 149,999–150,000 | 7 | 18 → 19 |  |
| default | mpsc | 0 | 149,999 | 7 |  |  |
| default | jdk | 0 | 149,999–150,051 | 7 |  |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 7 |  |  |
| big | mpsc | 256 | 149,998–150,002 | 7 | 7 → 1 |  |
| big | jdk | 256 | 148,718–149,652 | 6–7 | 57 → 27 | not a measurement (r1 to r3, c2c run, stat run) |
| big | jdk-pinned | 256 | 149,987–150,021 | 6–7 | 25 → 25 |  |
| big | mpsc | 0 | 149,999 | 8 |  |  |
| big | jdk | 0 | 149,999 | 8 |  |  |
| big | jdk-pinned | 0 | 149,999–150,000 | 8 |  |  |

From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the line
stackchunks of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 to r3. A run is not a measurement when its console says it could not keep up
with its rate, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 78,494 | 1.19 | 0.84 | 66,012 | 164,837,292,060 | 138,625,332,763 | 2,100,000 |  |
| default | jdk | 125,598 | 0.80 | 1.25 | 156,678 | 263,756,237,284 | 329,022,927,921 | 2,100,000 |  |
| default | jdk-pinned | 123,543 | 0.68 | 1.47 | 181,505 | 259,441,343,860 | 381,159,678,426 | 2,100,000 |  |
| big | mpsc | 64,083 | 0.24 | 4.13 | 264,351 | 134,573,881,550 | 555,138,119,210 | 2,100,000 |  |
| big | jdk | 50,882 | 0.17 | 5.75 | 292,365 | 106,853,035,235 | 613,967,242,744 | 2,100,000 | this run: not a measurement |
| big | jdk-pinned | 57,454 | 0.18 | 5.64 | 324,220 | 120,654,084,121 | 680,861,741,956 | 2,100,000 |  |

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
| default | mpsc | 0.0% | 0.33% | 0.04% | 21,978 | 73 | 9 |  |
| default | jdk | 68.5% | 1.10% | 0.33% | 38,015 | 418 | 124 |  |
| default | jdk-pinned | 65.8% | 1.78% | 0.48% | 53,087 | 945 | 255 |  |
| big | mpsc | 0.0% | 0.48% | 4.73% | 59,083 | 286 | 2,797 |  |
| big | jdk | 2.3% | 0.45% | 5.78% | 57,251 | 258 | 3,308 | this run: not a measurement |
| big | jdk-pinned | 7.9% | 0.55% | 5.20% | 65,846 | 360 | 3,427 |  |

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
| default | mpsc | 15.31 | 16.36 | +1.05 | 6,382 MiB |  |
| default | jdk | 36.16 | 37.25 | +1.09 | 6,618 MiB |  |
| default | jdk-pinned | 43.79 | 44.58 | +0.79 | 6,426 MiB |  |
| big | mpsc | 63.20 | 70.55 | +7.35 | 5,404 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 5,375 MiB | this run: not a measurement |
| big | jdk-pinned | 75.58 | 78.67 | +3.09 | 4,945 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 to r3, as in In short. cpu µs/req Epsilon:
the line cpu of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).
