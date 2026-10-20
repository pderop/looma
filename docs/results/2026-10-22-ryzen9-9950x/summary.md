# User benchmark

```
date         2026-10-22 04:31:42 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-testing-builds.shipilev.net-openjdk-jdk-loom-b59-20261017-1106)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25, and the Master-Poller
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/bench/20261022-0431
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
| default | stealing-local | 196,366,461,916 | 207,605,570,528 | 2,100,000 | 93,508 | 0.95 | 1.06 | 98,860 |  |
| default | hybrid | 181,248,846,938 | 182,632,063,583 | 2,100,000 | 86,309 | 0.99 | 1.01 | 86,968 |  |
| default | jdk | 266,210,890,074 | 371,851,075,125 | 2,100,000 | 126,767 | 0.72 | 1.40 | 177,072 |  |
| default | jdk-pinned | 264,042,630,451 | 381,375,338,692 | 2,100,000 | 125,735 | 0.69 | 1.44 | 181,607 |  |
| big | stealing-local | 162,548,522,151 | 615,652,235,783 | 2,100,000 | 77,404 | 0.26 | 3.79 | 293,168 |  |
| big | hybrid | 163,197,581,956 | 598,879,668,085 | 2,100,000 | 77,713 | 0.27 | 3.67 | 285,181 |  |
| big | jdk | 109,911,858,593 | 637,880,405,787 | 2,100,000 | 52,339 | 0.17 | 5.80 | 303,753 | this run: not a measurement |
| big | jdk-pinned | 140,698,243,920 | 708,025,936,206 | 2,100,000 | 66,999 | 0.20 | 5.03 | 337,155 |  |

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
| default | stealing-local | 43.9% | 26,390 | 79 | 14 | 0.30% | 0.05% |  |
| default | hybrid | 0.0% | 24,773 | 140 | 10 | 0.57% | 0.04% |  |
| default | jdk | 66.3% | 48,065 | 926 | 277 | 1.93% | 0.58% |  |
| default | jdk-pinned | 66.3% | 50,365 | 884 | 163 | 1.76% | 0.32% |  |
| big | stealing-local | 19.0% | 58,811 | 207 | 2,947 | 0.35% | 5.01% |  |
| big | hybrid | 0.0% | 61,732 | 419 | 3,931 | 0.68% | 6.37% |  |
| big | jdk | 5.5% | 56,130 | 232 | 2,960 | 0.41% | 5.27% | this run: not a measurement |
| big | jdk-pinned | 13.1% | 68,458 | 340 | 3,561 | 0.50% | 5.20% |  |

## Controls

From the files of each run, in runs/: req/s, the line throughput of its console
(<case>-<run>-<scheduler>-<depth>.out); GCs, the Pause lines of its GC log (.gc.log), less
the full GCs of the StackChunk counts (Heap Inspection); live StackChunks, the line
stackchunks of the console of the c2c run, during the warm-up → after the measure; note,
"not a measurement" when a console says the run could not keep up with its rate, a GET
failed, or a pinning failed (the lines pinning and poller). req/s and GCs: the lowest and the highest of r1 to r3. Step by step: [Where the numbers come from](../../benchmark.md#where-the-numbers-come-from).

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| default | stealing-local | 256 | 149,999–150,000 | 6 | 51 → 50 |  |
| default | stealing-local | 0 | 149,999–150,000 | 6 |  |  |
| default | hybrid | 256 | 149,999–150,000 | 6 | 54 → 54 |  |
| default | hybrid | 0 | 149,996–149,999 | 6 |  |  |
| default | jdk | 256 | 149,999–150,051 | 5–6 | 49 → 50 |  |
| default | jdk | 0 | 149,999–150,000 | 5 |  |  |
| default | jdk-pinned | 256 | 149,999–150,000 | 5 | 50 → 50 |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 5–6 |  |  |
| big | stealing-local | 256 | 149,998–150,001 | 6–7 | 110 → 61 |  |
| big | stealing-local | 0 | 149,999 | 7 |  |  |
| big | hybrid | 256 | 149,999–150,053 | 7 | 127 → 61 |  |
| big | hybrid | 0 | 149,999–150,000 | 7 |  |  |
| big | jdk | 256 | 149,945–150,003 | 6–7 | 75 → 42 | not a measurement, c2c run: not a measurement, stat run: not a measurement |
| big | jdk | 0 | 149,999–150,000 | 6–7 |  |  |
| big | jdk-pinned | 256 | 149,997–150,003 | 6 | 52 → 54 |  |
| big | jdk-pinned | 0 | 149,999–150,001 | 6 |  |  |

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
| default | stealing-local | 256 | 23.91 | 23.75–24.05 | -43.5% | 6.86 | 27–28 | 40–44 | 98–355 | 36.1% |
| default | stealing-local | 0 | 17.05 | 17.00–17.10 | -40.9% |  | 18–20 | 25–28 | 34–52 | 43.6% |
| default | hybrid | 256 | 21.49 | 21.17–21.80 | -49.2% | 4.95 | 24–25 | 34–35 | 44–137 | 0.0% |
| default | hybrid | 0 | 16.54 | 16.05–17.01 | -42.7% |  | 20–22 | 29–32 | 39–162 | 0.0% |
| default | jdk | 256 | 42.33 | 41.69–42.82 |  | 13.48 | 38–40 | 77–82 | 874–1,970 | 66.9% |
| default | jdk | 0 | 28.85 | 28.03–29.65 |  |  | 19–20 | 26–27 | 32–36 | 84.4% |
| default | jdk-pinned | 256 | 43.96 | 43.85–44.15 | +3.9% | 15.00 | 39–43 | 77–86 | 112–122 | 65.4% |
| default | jdk-pinned | 0 | 28.96 | 28.14–30.14 | +0.4% |  | 21–22 | 28–29 | 35–37 | 85.5% |
| big | stealing-local | 256 | 67.49 | 66.25–68.60 |  | 50.71 | 143–166 | 527–581 | 769–849 | 16.6% |
| big | stealing-local | 0 | 16.78 | 15.92–17.34 | -40.2% |  | 19–22 | 25–30 | 31–35 | 39.3% |
| big | hybrid | 256 | 68.42 | 67.68–69.28 |  | 51.20 | 130–139 | 472–504 | 782–1,151 | 0.0% |
| big | hybrid | 0 | 17.22 | 17.11–17.30 | -38.6% |  | 20 | 28–29 | 33–34 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 671–839 | 22,970–46,432 | 50,856–114,098 | 5.9% |
| big | jdk | 0 | 28.05 | 26.95–29.01 |  |  | 18–22 | 29–30 | 35–36 | 85.3% |
| big | jdk-pinned | 256 | 78.38 | 78.20–78.57 |  | 50.07 | 281–293 | 982–1,117 | 1,386–3,154 | 13.9% |
| big | jdk-pinned | 0 | 28.31 | 26.93–30.13 | +1.0% |  | 18–21 | 26–30 | 31–34 | 85.2% |

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
| default | stealing-local | 23.91 | 25.06 | +1.15 | 5,470 MiB |  |
| default | hybrid | 21.49 | 21.35 | -0.14 | 5,375 MiB |  |
| default | jdk | 42.33 | 42.66 | +0.33 | 4,758 MiB |  |
| default | jdk-pinned | 43.96 | 44.36 | +0.40 | 4,812 MiB |  |
| big | stealing-local | 67.49 | 73.32 | +5.83 | 5,652 MiB |  |
| big | hybrid | 68.42 | 74.81 | +6.39 | 5,590 MiB |  |
| big | jdk | not a measurement | 75.94 |  | 4,661 MiB |  |
| big | jdk-pinned | 78.38 | 80.35 | +1.97 | 4,855 MiB |  |
