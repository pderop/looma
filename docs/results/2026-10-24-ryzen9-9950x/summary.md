# User benchmark

```
date         2026-10-24 04:22:13 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-testing-builds.shipilev.net-openjdk-jdk-loom-b59-20261017-1106)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/bench/20261024-0422
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
  their own CPUs.
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
| default | stealing-local | 204,578,068,679 | 209,959,482,625 | 2,100,000 | 97,418 | 0.97 | 1.03 | 99,981 |  |
| default | stealing-local-home | 197,230,413,510 | 178,071,527,097 | 2,100,000 | 93,919 | 1.11 | 0.90 | 84,796 |  |
| default | jdk | 281,233,515,444 | 336,591,377,456 | 2,100,000 | 133,921 | 0.84 | 1.20 | 160,282 |  |
| default | jdk-pinned | 265,960,143,983 | 388,112,013,990 | 2,100,000 | 126,648 | 0.69 | 1.46 | 184,815 |  |
| big | stealing-local | 148,100,323,132 | 575,295,379,371 | 2,100,000 | 70,524 | 0.26 | 3.88 | 273,950 |  |
| big | stealing-local-home | 146,816,768,266 | 593,801,017,845 | 2,100,000 | 69,913 | 0.25 | 4.04 | 282,762 |  |
| big | jdk | 110,963,815,394 | 614,608,784,910 | 2,100,000 | 52,840 | 0.18 | 5.54 | 292,671 | this run: not a measurement |
| big | jdk-pinned | 127,979,682,617 | 680,217,040,958 | 2,100,000 | 60,943 | 0.19 | 5.32 | 323,913 |  |

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
| default | stealing-local | 42.2% | 28,089 | 99 | 3 | 0.35% | 0.01% |  |
| default | stealing-local-home | 0.0% | 25,175 | 85 | 12 | 0.34% | 0.05% |  |
| default | jdk | 70.1% | 37,709 | 423 | 53 | 1.12% | 0.14% |  |
| default | jdk-pinned | 66.3% | 49,377 | 707 | 38 | 1.43% | 0.08% |  |
| big | stealing-local | 10.8% | 63,912 | 172 | 3,015 | 0.27% | 4.72% |  |
| big | stealing-local-home | 0.0% | 67,057 | 220 | 3,211 | 0.33% | 4.79% |  |
| big | jdk | 2.5% | 48,767 | 201 | 2,467 | 0.41% | 5.06% | this run: not a measurement |
| big | jdk-pinned | 8.1% | 65,664 | 356 | 3,846 | 0.54% | 5.86% |  |

## Controls

From the files of each run, in runs/: req/s, the line throughput of its console
(<case>-<run>-<scheduler>-<depth>.out); GCs, the Pause lines of its GC log (.gc.log), less
the full GCs of the StackChunk counts (Heap Inspection); live StackChunks, the line
stackchunks of the console of the c2c run, during the warm-up → after the measure; note,
"not a measurement" when a console says the run could not keep up with its rate, a GET
failed, or a pinning failed (the line pinning). req/s and GCs: the lowest and the highest of r1 to r3. Step by step: [Where the numbers come from](../../benchmark.md#where-the-numbers-come-from).

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| default | stealing-local | 256 | 149,999 | 8–9 | 30 → 25 |  |
| default | stealing-local | 0 | 149,998–149,999 | 9 |  |  |
| default | stealing-local-home | 256 | 149,999 | 8–9 | 26 → 26 |  |
| default | stealing-local-home | 0 | 149,999 | 9 |  |  |
| default | jdk | 256 | 149,999 | 7 | 20 → 21 |  |
| default | jdk | 0 | 149,999–150,000 | 7 |  |  |
| default | jdk-pinned | 256 | 149,999 | 7 | 19 → 21 |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 7–8 |  |  |
| big | stealing-local | 256 | 149,997–150,012 | 7 | 40 → 104 |  |
| big | stealing-local | 0 | 149,999–150,000 | 10 |  |  |
| big | stealing-local-home | 256 | 149,980–150,000 | 8 | 2101 → 32 |  |
| big | stealing-local-home | 0 | 149,993–149,994 | 10 |  |  |
| big | jdk | 256 | 148,264–150,093 | 6 | 123 → 25 | not a measurement, c2c run: not a measurement, stat run: not a measurement |
| big | jdk | 0 | 149,999 | 8–9 |  |  |
| big | jdk-pinned | 256 | 150,000–150,005 | 7 | 26 → 29 |  |
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
| default | stealing-local | 256 | 23.94 | 23.73–24.07 | -33.4% | 6.46 | 27–29 | 42–44 | 107–350 | 36.3% |
| default | stealing-local | 0 | 17.48 | 16.97–17.93 | -36.8% |  | 20–21 | 28–30 | 100–154 | 34.4% |
| default | stealing-local-home | 256 | 20.54 | 20.44–20.70 | -42.8% | 4.08 | 24–25 | 35 | 144–272 | 0.0% |
| default | stealing-local-home | 0 | 16.46 | 16.45–16.47 | -40.5% |  | 20–21 | 28–29 | 129–358 | 0.0% |
| default | jdk | 256 | 35.93 | 35.32–36.27 |  | 8.27 | 32–37 | 84–88 | 2,464–3,025 | 69.9% |
| default | jdk | 0 | 27.66 | 27.16–28.21 |  |  | 20–21 | 29–31 | 589–818 | 86.4% |
| default | jdk-pinned | 256 | 43.69 | 43.47–43.89 | +21.6% | 14.95 | 42–44 | 86–93 | 136–188 | 65.7% |
| default | jdk-pinned | 0 | 28.74 | 27.52–29.58 | +3.9% |  | 20–21 | 28–29 | 46–48 | 84.8% |
| big | stealing-local | 256 | 64.97 | 64.60–65.27 |  | 47.72 | 204–216 | 647–700 | 924–1,074 | 11.4% |
| big | stealing-local | 0 | 17.25 | 16.92–17.54 | -35.9% |  | 20–21 | 28 | 32–34 | 35.0% |
| big | stealing-local-home | 256 | 63.24 | 62.82–63.59 |  | 46.46 | 152–160 | 483–512 | 699–972 | 0.0% |
| big | stealing-local-home | 0 | 16.78 | 16.68–16.90 | -37.7% |  | 20–21 | 28 | 34–35 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 903–1,202 | 25,903–56,328 | 50,004–96,338 | 4.7% |
| big | jdk | 0 | 26.92 | 26.26–27.40 |  |  | 20–22 | 29–31 | 50–131 | 86.7% |
| big | jdk-pinned | 256 | 74.63 | 74.08–74.99 |  | 45.62 | 387–429 | 1,458–1,653 | 2,234–2,609 | 12.4% |
| big | jdk-pinned | 0 | 29.01 | 28.25–30.16 | +7.8% |  | 20–23 | 29–31 | 34–39 | 86.0% |

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
| default | stealing-local | 23.94 | 24.17 | +0.23 | 8,957 MiB |  |
| default | stealing-local-home | 20.54 | 21.37 | +0.83 | 9,313 MiB |  |
| default | jdk | 35.93 | 38.36 | +2.43 | 6,666 MiB |  |
| default | jdk-pinned | 43.69 | 44.85 | +1.16 | 7,064 MiB |  |
| big | stealing-local | 64.97 | 70.25 | +5.28 | 5,909 MiB |  |
| big | stealing-local-home | 63.24 | 70.15 | +6.91 | 6,567 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 4,995 MiB | this run: not a measurement |
| big | jdk-pinned | 74.63 | 78.63 | +4.00 | 5,382 MiB |  |
