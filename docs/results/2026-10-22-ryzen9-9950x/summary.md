# User benchmark

```
date         2026-10-22 12:56:45 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-testing-builds.shipilev.net-openjdk-jdk-loom-b59-20261017-1106)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out /home/pderop/work/projects/looma-test/bench/20261022-1256
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
| default | stealing-local | 206,586,407,927 | 210,892,374,142 | 2,100,000 | 98,374 | 0.98 | 1.02 | 100,425 |  |
| default | hybrid | 192,845,105,696 | 192,047,860,579 | 2,100,000 | 91,831 | 1.00 | 1.00 | 91,451 |  |
| default | jdk | 275,266,910,425 | 320,310,885,643 | 2,100,000 | 131,079 | 0.86 | 1.16 | 152,529 |  |
| default | jdk-pinned | 267,871,084,770 | 394,911,851,258 | 2,100,000 | 127,558 | 0.68 | 1.47 | 188,053 |  |
| big | stealing-local | 140,415,362,296 | 605,913,417,514 | 2,100,000 | 66,864 | 0.23 | 4.32 | 288,530 |  |
| big | hybrid | 148,809,197,070 | 602,921,500,405 | 2,100,000 | 70,862 | 0.25 | 4.05 | 287,105 |  |
| big | jdk | 110,645,350,346 | 594,548,721,490 | 2,100,000 | 52,688 | 0.19 | 5.37 | 283,118 | this run: not a measurement |
| big | jdk-pinned | 132,964,588,640 | 669,419,906,678 | 2,100,000 | 63,316 | 0.20 | 5.03 | 318,771 |  |

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
| default | stealing-local | 44.1% | 28,105 | 99 | 13 | 0.35% | 0.05% |  |
| default | hybrid | 0.0% | 25,987 | 155 | 7 | 0.60% | 0.03% |  |
| default | jdk | 71.6% | 37,865 | 439 | 128 | 1.16% | 0.34% |  |
| default | jdk-pinned | 66.5% | 53,335 | 958 | 90 | 1.80% | 0.17% |  |
| big | stealing-local | 10.7% | 60,491 | 154 | 2,778 | 0.25% | 4.59% |  |
| big | hybrid | 0.0% | 59,945 | 406 | 2,427 | 0.68% | 4.05% |  |
| big | jdk | 5.2% | 50,323 | 241 | 3,097 | 0.48% | 6.15% | this run: not a measurement |
| big | jdk-pinned | 8.2% | 70,642 | 335 | 3,117 | 0.47% | 4.41% |  |

## Controls

From the files of each run, in runs/: req/s, the line throughput of its console
(<case>-<run>-<scheduler>-<depth>.out); GCs, the Pause lines of its GC log (.gc.log), less
the full GCs of the StackChunk counts (Heap Inspection); live StackChunks, the line
stackchunks of the console of the c2c run, during the warm-up → after the measure; note,
"not a measurement" when a console says the run could not keep up with its rate, a GET
failed, or a pinning failed (the line pinning). req/s and GCs: the lowest and the highest of r1 to r3. Step by step: [Where the numbers come from](../../benchmark.md#where-the-numbers-come-from).

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| default | stealing-local | 256 | 149,999–150,000 | 8–9 | 28 → 25 |  |
| default | stealing-local | 0 | 149,999 | 9 |  |  |
| default | hybrid | 256 | 149,999–150,000 | 9 | 25 → 25 |  |
| default | hybrid | 0 | 149,995–149,999 | 9 |  |  |
| default | jdk | 256 | 149,999–150,000 | 7 | 22 → 21 |  |
| default | jdk | 0 | 149,999–150,000 | 7 |  |  |
| default | jdk-pinned | 256 | 149,999 | 7 | 37 → 26 |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 7 |  |  |
| big | stealing-local | 256 | 150,000–150,002 | 7–8 | 139 → 57 |  |
| big | stealing-local | 0 | 149,999 | 10 |  |  |
| big | hybrid | 256 | 149,996–150,002 | 7–8 | 108 → 26 |  |
| big | hybrid | 0 | 149,999 | 9–11 |  |  |
| big | jdk | 256 | 148,659–150,397 | 6–7 | 49 → 46 | not a measurement, c2c run: not a measurement, stat run: not a measurement |
| big | jdk | 0 | 149,999–150,000 | 8 |  |  |
| big | jdk-pinned | 256 | 149,996–150,007 | 6–7 | 27 → 31 |  |
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
| default | stealing-local | 256 | 24.27 | 23.35–24.94 | -32.4% | 7.04 | 28–29 | 41–48 | 199–367 | 36.1% |
| default | stealing-local | 0 | 17.23 | 16.91–17.47 | -34.7% |  | 20–21 | 28–30 | 90–172 | 35.2% |
| default | hybrid | 256 | 21.86 | 21.36–22.46 | -39.1% | 4.69 | 26 | 38 | 1,786–2,210 | 0.0% |
| default | hybrid | 0 | 17.18 | 16.81–17.59 | -34.9% |  | 21–22 | 32 | 693–2,263 | 0.0% |
| default | jdk | 256 | 35.89 | 35.56–36.44 |  | 9.50 | 32–34 | 83–87 | 2,501–3,017 | 71.2% |
| default | jdk | 0 | 26.39 | 24.78–27.82 |  |  | 18–22 | 29–31 | 1,425–1,460 | 86.2% |
| default | jdk-pinned | 256 | 43.54 | 43.41–43.69 | +21.3% | 14.70 | 42 | 88–89 | 134–584 | 65.6% |
| default | jdk-pinned | 0 | 28.85 | 28.31–29.13 | +9.3% |  | 20–22 | 28–30 | 40–84 | 86.0% |
| big | stealing-local | 256 | 65.02 | 64.86–65.15 |  | 47.53 | 205–218 | 676–724 | 967–1,059 | 11.1% |
| big | stealing-local | 0 | 17.49 | 17.36–17.65 | -34.0% |  | 20–21 | 28–29 | 33–34 | 37.7% |
| big | hybrid | 256 | 67.23 | 66.72–68.00 |  | 49.62 | 173–182 | 593–622 | 1,612–2,523 | 0.0% |
| big | hybrid | 0 | 17.60 | 17.24–18.08 | -33.5% |  | 21 | 31 | 1,422–1,852 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 1,118–1,970 | 34,701–57,115 | 55,312–91,357 | 3.5% |
| big | jdk | 0 | 26.48 | 25.02–27.34 |  |  | 18–22 | 28–31 | 332–1,375 | 86.2% |
| big | jdk-pinned | 256 | 74.69 | 73.71–75.81 |  | 45.82 | 360–460 | 1,255–1,654 | 1,828–2,468 | 12.4% |
| big | jdk-pinned | 0 | 28.87 | 28.43–29.53 | +9.1% |  | 19–22 | 29–30 | 36 | 85.8% |

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
| default | stealing-local | 24.27 | 25.40 | +1.13 | 8,645 MiB |  |
| default | hybrid | 21.86 | 22.54 | +0.68 | 8,637 MiB |  |
| default | jdk | 35.89 | 38.10 | +2.21 | 6,659 MiB |  |
| default | jdk-pinned | 43.54 | 45.28 | +1.74 | 6,896 MiB |  |
| big | stealing-local | 65.02 | 71.09 | +6.07 | 5,884 MiB |  |
| big | hybrid | 67.23 | 73.22 | +5.99 | 6,274 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 5,258 MiB | this run: not a measurement |
| big | jdk-pinned | 74.69 | 78.49 | +3.80 | 4,978 MiB |  |
