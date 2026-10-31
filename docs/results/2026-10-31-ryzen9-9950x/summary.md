# User benchmark

```
date         2026-10-31 02:15:51 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-internal-pr227-731930d)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/bench/20261031-021551
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
- The schedulers: stealing-local-home, mpsc-home, jdk, jdk-pinned.
- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to
  their own CPUs.
- mpsc-home and mpsc-home-poller3: the MPSC scheduler of the JDK (openjdk/loom pull request 227).
  mpsc-home-poller3 runs it with jdk.pollerMode=3, the poller mode of the stealing-* schedulers.
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
| default | stealing-local-home | 230,190,123,645 | 181,366,101,430 | 2,100,000 | 109,614 | 1.27 | 0.79 | 86,365 |  |
| default | mpsc-home | 171,697,438,761 | 142,564,640,728 | 2,100,000 | 81,761 | 1.20 | 0.83 | 67,888 |  |
| default | jdk | 276,548,008,030 | 316,519,295,579 | 2,100,000 | 131,690 | 0.87 | 1.14 | 150,723 |  |
| default | jdk-pinned | 263,010,957,974 | 385,560,943,943 | 2,100,000 | 125,243 | 0.68 | 1.47 | 183,600 |  |
| big | stealing-local-home | 145,758,100,211 | 591,596,496,734 | 2,100,000 | 69,409 | 0.25 | 4.06 | 281,713 |  |
| big | mpsc-home | 135,124,026,831 | 574,688,110,091 | 2,100,000 | 64,345 | 0.24 | 4.25 | 273,661 |  |
| big | jdk | 110,004,626,985 | 609,450,179,260 | 2,100,000 | 52,383 | 0.18 | 5.54 | 290,214 | this run: not a measurement |
| big | jdk-pinned | 125,381,964,192 | 686,827,381,384 | 2,100,000 | 59,706 | 0.18 | 5.48 | 327,061 |  |

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
| default | stealing-local-home | 0.0% | 25,729 | 91 | 11 | 0.35% | 0.04% |  |
| default | mpsc-home | 0.0% | 22,444 | 60 | 7 | 0.27% | 0.03% |  |
| default | jdk | 72.9% | 35,758 | 361 | 33 | 1.01% | 0.09% |  |
| default | jdk-pinned | 66.4% | 53,932 | 1,199 | 245 | 2.22% | 0.45% |  |
| big | stealing-local-home | 0.0% | 65,079 | 195 | 2,984 | 0.30% | 4.59% |  |
| big | mpsc-home | 0.0% | 65,527 | 259 | 3,278 | 0.40% | 5.00% |  |
| big | jdk | 5.7% | 55,179 | 296 | 2,772 | 0.54% | 5.02% | this run: not a measurement |
| big | jdk-pinned | 9.6% | 56,422 | 359 | 3,205 | 0.64% | 5.68% |  |

## Controls

From the files of each run, in runs/: req/s, the line throughput of its console
(<case>-<run>-<scheduler>-<depth>.out); GCs, the Pause lines of its GC log (.gc.log), less
the full GCs of the StackChunk counts (Heap Inspection); live StackChunks, the line
stackchunks of the console of the c2c run, during the warm-up → after the measure; note,
"not a measurement" when a console says the run could not keep up with its rate, a GET
failed, or a pinning failed (the line pinning). req/s and GCs: the lowest and the highest of r1 to r3. Step by step: [Where the numbers come from](../../benchmark.md#where-the-numbers-come-from).

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| default | stealing-local-home | 256 | 149,999–150,000 | 9 | 28 → 27 |  |
| default | stealing-local-home | 0 | 149,994–149,999 | 9 |  |  |
| default | mpsc-home | 256 | 149,999–150,001 | 7 | 2 → 0 |  |
| default | mpsc-home | 0 | 149,998–149,999 | 7 |  |  |
| default | jdk | 256 | 149,999 | 7 | 27 → 27 |  |
| default | jdk | 0 | 149,999 | 7–8 |  |  |
| default | jdk-pinned | 256 | 149,999–150,000 | 7 | 26 → 27 |  |
| default | jdk-pinned | 0 | 149,999–150,001 | 7 |  |  |
| big | stealing-local-home | 256 | 149,996–149,999 | 8 | 68 → 46 |  |
| big | stealing-local-home | 0 | 149,999–150,001 | 10–11 |  |  |
| big | mpsc-home | 256 | 149,999–150,001 | 7–8 | 8 → 3 |  |
| big | mpsc-home | 0 | 149,999–150,000 | 8 |  |  |
| big | jdk | 256 | 148,941–150,053 | 6 | 72 → 107 | not a measurement, c2c run: not a measurement, stat run: not a measurement |
| big | jdk | 0 | 149,999–150,000 | 8–9 |  |  |
| big | jdk-pinned | 256 | 149,969–150,000 | 7 | 26 → 29 |  |
| big | jdk-pinned | 0 | 149,998–149,999 | 8–9 |  |  |

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
| default | stealing-local-home | 256 | 20.96 | 20.76–21.16 | -41.6% | 4.44 | 24–26 | 34–36 | 213–941 | 0.0% |
| default | stealing-local-home | 0 | 16.52 | 16.28–16.89 | -39.0% |  | 20–21 | 28–29 | 159–346 | 0.0% |
| default | mpsc-home | 256 | 15.98 | 15.50–16.37 | -55.5% | 4.28 | 20 | 29–30 | 48–111 | 0.0% |
| default | mpsc-home | 0 | 11.70 | 11.05–12.26 | -56.8% |  | 16 | 24–26 | 79–96 | 0.0% |
| default | jdk | 256 | 35.89 | 35.34–36.21 |  | 8.79 | 33–35 | 83–88 | 2,277–2,765 | 69.4% |
| default | jdk | 0 | 27.10 | 26.03–27.78 |  |  | 20–21 | 28–30 | 1,056–1,733 | 85.7% |
| default | jdk-pinned | 256 | 43.93 | 43.29–44.26 | +22.4% | 15.05 | 43–44 | 89–92 | 148–186 | 65.4% |
| default | jdk-pinned | 0 | 28.88 | 28.51–29.35 | +6.6% |  | 20–22 | 29–30 | 38–48 | 85.6% |
| big | stealing-local-home | 256 | 63.08 | 62.36–64.48 |  | 46.49 | 147–166 | 470–552 | 739–1,024 | 0.0% |
| big | stealing-local-home | 0 | 16.59 | 16.08–16.90 | -36.5% |  | 20–21 | 28–30 | 34–90 | 0.0% |
| big | mpsc-home | 256 | 62.17 | 60.54–63.33 |  | 50.78 | 109–122 | 456–489 | 898–960 | 0.0% |
| big | mpsc-home | 0 | 11.39 | 10.91–12.15 | -56.4% |  | 16 | 25–26 | 29–30 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 826–2,175 | 31,179–60,522 | 57,082–103,154 | 4.3% |
| big | jdk | 0 | 26.12 | 24.49–29.22 |  |  | 20–22 | 30–31 | 102–1,816 | 87.5% |
| big | jdk-pinned | 256 | 74.51 | 74.39–74.64 |  | 45.06 | 394–413 | 1,413–1,576 | 2,148–2,402 | 12.6% |
| big | jdk-pinned | 0 | 29.45 | 28.24–30.59 | +12.7% |  | 19–22 | 29–30 | 35–48 | 85.7% |

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
| default | stealing-local-home | 20.96 | 21.16 | +0.20 | 9,595 MiB |  |
| default | mpsc-home | 15.98 | 17.07 | +1.09 | 6,675 MiB |  |
| default | jdk | 35.89 | 37.27 | +1.38 | 6,963 MiB |  |
| default | jdk-pinned | 43.93 | 44.22 | +0.29 | 6,792 MiB |  |
| big | stealing-local-home | 63.08 | 70.51 | +7.43 | 6,698 MiB |  |
| big | mpsc-home | 62.17 | 70.71 | +8.54 | 5,700 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 5,042 MiB | this run: not a measurement |
| big | jdk-pinned | 74.51 | 78.56 | +4.05 | 4,986 MiB |  |
