# User benchmark

```
date         2026-10-22 05:38:56 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-testing-builds.shipilev.net-openjdk-jdk-loom-b59-20261017-1106)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25, and the Master-Poller
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/bench/20261022-0538
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
- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to
  their own CPUs. The JDK's Master-Poller thread is moved to the backend's CPUs.
- Heap: -Xms2g -Xmx2g -XX:+AlwaysPreTouch, G1, and a GC log per run. The epsilon run:
  -XX:+UseEpsilonGC -Xms12g -Xmx12g -XX:+AlwaysPreTouch. Epsilon frees nothing, so no GC runs:
  each allocation takes new memory.
- The c2c run and the stat run count during the measure, minus 3 seconds at each end. The c2c run
  also counts the live StackChunks (GC.class_histogram), during the warm-up and after the measure.
  The full report of each c2c run is in <case>-c2c-<scheduler>.txt, the files of every run in
  runs/.

## perf stat

The stat run of each scheduler, at depth 256. instructions and cycles: the counts of perf
stat, summed over the server's threads (every thread but client-*), in
runs/<case>-stat-<scheduler>.csv. requests: 150,000 req/s × 14 s, the window of perf stat.
Step by step, on one line: [Where the numbers come from](../../benchmark.md#where-the-numbers-come-from).

```
instructions/req       = instructions / requests
cycles/req             = cycles / requests
IPC                    = instructions / cycles
cycles per instruction = cycles / instructions = 1 / IPC
```

| case | scheduler | instructions | cycles | requests | instructions/req | IPC | cycles per instruction | cycles/req | note |
|---|---|---|---|---|---|---|---|---|---|
| default | stealing-local | 199,915,228,329 | 208,909,029,353 | 2,100,000 | 95,198 | 0.96 | 1.04 | 99,480 |  |
| default | hybrid | 185,913,901,469 | 184,049,493,092 | 2,100,000 | 88,530 | 1.01 | 0.99 | 87,643 |  |
| default | jdk | 275,882,088,488 | 381,132,028,203 | 2,100,000 | 131,372 | 0.72 | 1.38 | 181,491 |  |
| default | jdk-pinned | 273,461,701,441 | 388,992,625,409 | 2,100,000 | 130,220 | 0.70 | 1.42 | 185,235 |  |
| big | stealing-local | 158,170,135,286 | 617,642,277,437 | 2,100,000 | 75,319 | 0.26 | 3.90 | 294,115 |  |
| big | hybrid | 165,721,219,000 | 598,276,420,151 | 2,100,000 | 78,915 | 0.28 | 3.61 | 284,894 |  |
| big | jdk | 111,725,879,852 | 606,540,368,103 | 2,100,000 | 53,203 | 0.18 | 5.43 | 288,829 | this run: not a measurement |
| big | jdk-pinned | 143,111,923,213 | 706,646,634,619 | 2,100,000 | 68,149 | 0.20 | 4.94 | 336,498 |  |

## perf c2c

The c2c run of each scheduler, at depth 256. Load Operations, Load Remote HITM and Load DRAM
are copied from its report, <case>-c2c-<scheduler>.txt, block Trace Event Information:

- Load Operations: the memory loads that perf sampled.
- Load Remote HITM: among them, the loads that took their line from behind another L3,
  because a core there had modified it.
- Load DRAM: among them, the loads served by the memory (Load Local DRAM + Load Remote DRAM).

migrations: the line migrations of the console of the c2c run, runs/<case>-c2c-<scheduler>-256.out.
Step by step: [Where the numbers come from](../../benchmark.md#where-the-numbers-come-from).

```
other L3 = Load Remote HITM / Load Operations
DRAM     = Load DRAM / Load Operations
```

| case | scheduler | migrations | Load Operations | Load Remote HITM | Load DRAM | other L3 | DRAM | note |
|---|---|---|---|---|---|---|---|---|
| default | stealing-local | 45.8% | 27,575 | 106 | 13 | 0.38% | 0.05% |  |
| default | hybrid | 0.0% | 25,243 | 162 | 7 | 0.64% | 0.03% |  |
| default | jdk | 66.2% | 47,062 | 837 | 99 | 1.78% | 0.21% |  |
| default | jdk-pinned | 65.6% | 51,988 | 898 | 57 | 1.73% | 0.11% |  |
| big | stealing-local | 15.2% | 60,520 | 181 | 2,451 | 0.30% | 4.05% |  |
| big | hybrid | 0.0% | 67,323 | 460 | 2,754 | 0.68% | 4.09% |  |
| big | jdk | 4.1% | 54,807 | 222 | 3,085 | 0.41% | 5.63% | r1 to r3: not a measurement |
| big | jdk-pinned | 12.9% | 73,141 | 392 | 3,574 | 0.54% | 4.89% |  |

## Controls

From the files of each run, in runs/: req/s, the line throughput of its console
(<case>-<run>-<scheduler>-<depth>.out); GCs, the Pause lines of its GC log (.gc.log), less
the full GCs of the StackChunk counts (Heap Inspection); live StackChunks, the line
stackchunks of the console of the c2c run, during the warm-up → after the measure; note,
"not a measurement" when a console says the run could not keep up with its rate, a GET
failed, or a pinning failed (the lines pinning and poller). req/s and GCs: the lowest and the highest of r1 to r3. Step by step: [Where the numbers come from](../../benchmark.md#where-the-numbers-come-from).

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| default | stealing-local | 256 | 149,999 | 9 | 27 → 24 |  |
| default | stealing-local | 0 | 149,999 | 9 |  |  |
| default | hybrid | 256 | 149,999 | 9 | 25 → 28 |  |
| default | hybrid | 0 | 149,992–149,999 | 9–10 |  |  |
| default | jdk | 256 | 149,999 | 7 | 26 → 28 |  |
| default | jdk | 0 | 149,999 | 7 |  |  |
| default | jdk-pinned | 256 | 149,999–150,000 | 7 | 25 → 28 |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 7 |  |  |
| big | stealing-local | 256 | 149,996–149,998 | 8 | 170 → 24 |  |
| big | stealing-local | 0 | 149,999 | 10 |  |  |
| big | hybrid | 256 | 149,998–149,999 | 8 | 26 → 32 |  |
| big | hybrid | 0 | 149,999 | 10 |  |  |
| big | jdk | 256 | 149,339–150,104 | 6–7 | 31 → 66 | not a measurement, stat run: not a measurement |
| big | jdk | 0 | 149,999 | 8 |  |  |
| big | jdk-pinned | 256 | 149,988–150,002 | 7 | 71 → 28 |  |
| big | jdk-pinned | 0 | 149,999–150,000 | 8 |  |  |

## In short

From the console of each run, runs/<case>-<run>-<scheduler>-<depth>.out, r1 to r3: cpu µs/req,
the line cpu; latencies, the line latency; migrations, the line migrations.
cpu µs/req and migrations: the mean of r1 to r3. cpu range and latencies: the lowest and the highest of r1 to r3.
Step by step: [Where the numbers come from](../../benchmark.md#where-the-numbers-come-from).

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1
walk   = cpu µs/req at depth 256 - cpu µs/req at depth 0
```

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | walk µs | p50 µs | p99 µs | p99.9 µs | migrations |
|---|---|---|---|---|---|---|---|---|---|---|
| default | stealing-local | 256 | 23.60 | 22.86–24.08 | -44.9% | 6.92 | 26–29 | 39–47 | 143–415 | 33.9% |
| default | stealing-local | 0 | 16.67 | 16.10–17.10 | -42.8% |  | 19–21 | 27–30 | 110–177 | 38.7% |
| default | hybrid | 256 | 21.37 | 21.35–21.40 | -50.1% | 4.28 | 25 | 35–36 | 254–495 | 0.0% |
| default | hybrid | 0 | 17.09 | 17.03–17.17 | -41.4% |  | 20–21 | 29–30 | 198–412 | 0.0% |
| default | jdk | 256 | 42.85 | 42.49–43.32 |  | 13.68 | 40–42 | 80–87 | 806–1,861 | 66.1% |
| default | jdk | 0 | 29.17 | 27.77–30.33 |  |  | 19–22 | 27–32 | 41–401 | 85.3% |
| default | jdk-pinned | 256 | 44.34 | 43.99–44.53 | +3.5% | 15.50 | 40–43 | 78–86 | 138–260 | 65.9% |
| default | jdk-pinned | 0 | 28.84 | 28.50–29.50 | -1.1% |  | 19–23 | 27–31 | 35–42 | 85.3% |
| big | stealing-local | 256 | 67.27 | 65.61–68.24 |  | 50.20 | 137–164 | 466–576 | 686–882 | 16.7% |
| big | stealing-local | 0 | 17.07 | 16.91–17.31 | -39.0% |  | 19–20 | 27 | 32 | 35.4% |
| big | hybrid | 256 | 69.13 | 68.83–69.44 |  | 51.99 | 135–141 | 499–518 | 788–840 | 0.0% |
| big | hybrid | 0 | 17.14 | 17.04–17.31 | -38.8% |  | 20–21 | 27–30 | 33–36 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 832–1,101 | 28,394–51,085 | 58,819–93,913 | 4.5% |
| big | jdk | 0 | 28.00 | 27.51–28.26 |  |  | 18–22 | 27–32 | 37–374 | 85.2% |
| big | jdk-pinned | 256 | 77.75 | 76.91–78.69 |  | 48.85 | 233–308 | 860–1,071 | 1,205–3,330 | 15.7% |
| big | jdk-pinned | 0 | 28.90 | 28.24–29.64 | +3.2% |  | 18–20 | 26–31 | 30–36 | 84.7% |

## Epsilon

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 to r3, as in In short. cpu µs/req Epsilon:
the line cpu of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../../benchmark.md#where-the-numbers-come-from).

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

| case | scheduler | cpu µs/req G1 | cpu µs/req Epsilon | difference | heap used | note |
|---|---|---|---|---|---|---|
| default | stealing-local | 23.60 | 25.03 | +1.43 | 8,447 MiB |  |
| default | hybrid | 21.37 | 21.61 | +0.24 | 8,979 MiB |  |
| default | jdk | 42.85 | 43.23 | +0.38 | 7,491 MiB |  |
| default | jdk-pinned | 44.34 | 44.76 | +0.42 | 6,639 MiB |  |
| big | stealing-local | 67.27 | 73.55 | +6.28 | 6,503 MiB |  |
| big | hybrid | 69.13 | 74.86 | +5.73 | 6,648 MiB |  |
| big | jdk | not a measurement | 74.45 |  | 4,979 MiB |  |
| big | jdk-pinned | 77.75 | 80.32 | +2.57 | 5,148 MiB |  |
