# User benchmark

```
date         2026-11-02 15:24:55 +0000
host         gabuzo-amd, Linux 7.0.0-38-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
java         OpenJDK Runtime Environment (build 28-internal-pr227-731930d)
carriers     CPUs 2-7,10-15
clients      CPUs 0,1,16,17
backend      CPUs 8,9,24,25
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench.sh --out bench/20261102-152455
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
- The schedulers: mpsc, mpsc-mode3, jdk, jdk-pinned.
- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to
  their own CPUs.
- mpsc: the MPSC scheduler of the JDK (openjdk/loom pull request 227), behind Carriers.affine.
  Each carrier is pinned to one CPU, and an actor never moves to another carrier.
  The JDK's poller mode is not set: on Linux, it is mode 4, one epoll per carrier.
- mpsc-mode3: mpsc with -Djdk.pollerMode=3. Each carrier has its own Read-Poller, a virtual
  thread. When it has nothing to read, it waits on the Master-Poller, a platform thread.
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
| mpsc | 15.26 | 63.06 |
| mpsc-mode3 | 20.51 | 66.50 |
| jdk | 35.92 | not a measurement |
| jdk-pinned | 43.63 | 75.54 |

cpu µs/req at depth 256, the mean of r1 to r3, as in In short.

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | walk µs | p50 µs | p99 µs | p99.9 µs | migrations |
|---|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 256 | 15.26 | 14.87–15.70 | -57.5% | 4.14 | 20 | 30–31 | 156–211 | 0.0% |
| default | mpsc-mode3 | 256 | 20.51 | 19.94–20.83 | -42.9% | 3.88 | 26–27 | 38–40 | 75–127 | 0.0% |
| default | jdk | 256 | 35.92 | 34.92–37.38 |  | 10.07 | 33–35 | 83–84 | 2,374–2,689 | 71.0% |
| default | jdk-pinned | 256 | 43.63 | 43.53–43.78 | +21.5% | 15.06 | 42–45 | 89–95 | 161–291 | 65.0% |
| default | mpsc | 0 | 11.12 | 10.41–11.57 | -57.0% |  | 16–17 | 24–27 | 48–159 | 0.0% |
| default | mpsc-mode3 | 0 | 16.63 | 16.37–16.92 | -35.7% |  | 22–23 | 32–33 | 79–114 | 0.0% |
| default | jdk | 0 | 25.85 | 24.36–27.08 |  |  | 19–20 | 29–30 | 544–1,558 | 86.3% |
| default | jdk-pinned | 0 | 28.58 | 28.23–29.02 | +10.5% |  | 21–23 | 30–31 | 43–104 | 86.2% |
| big | mpsc | 256 | 63.06 | 62.54–63.73 |  | 51.87 | 120–124 | 460–481 | 931–1,384 | 0.0% |
| big | mpsc-mode3 | 256 | 66.50 | 65.87–67.14 |  | 49.76 | 180–196 | 627–669 | 1,062–1,154 | 0.0% |
| big | jdk | 256 | not a measurement |  |  |  | 865–1,258 | 21,283–36,176 | 32,555–57,147 | 5.0% |
| big | jdk-pinned | 256 | 75.54 | 74.02–76.46 |  | 47.21 | 371–508 | 1,315–1,723 | 2,065–2,507 | 10.9% |
| big | mpsc | 0 | 11.18 | 10.40–11.58 | -57.5% |  | 16–17 | 24–28 | 29–32 | 0.0% |
| big | mpsc-mode3 | 0 | 16.74 | 16.41–17.21 | -36.4% |  | 22–23 | 30–34 | 36–40 | 0.0% |
| big | jdk | 0 | 26.31 | 24.80–28.26 |  |  | 18–22 | 27–31 | 38–1,527 | 86.6% |
| big | jdk-pinned | 0 | 28.32 | 28.12–28.46 | +7.6% |  | 19–21 | 29 | 34–35 | 85.6% |

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
| default | mpsc | 256 | 149,999–150,000 | 6–7 | 2 → 1 |  |
| default | mpsc-mode3 | 256 | 149,999–150,000 | 7 | 31 → 24 |  |
| default | jdk | 256 | 149,999–150,000 | 7 | 26 → 27 |  |
| default | jdk-pinned | 256 | 149,999–150,000 | 7 | 25 → 28 |  |
| default | mpsc | 0 | 149,999–150,000 | 7 |  |  |
| default | mpsc-mode3 | 0 | 149,999–150,000 | 7 |  |  |
| default | jdk | 0 | 149,999–150,000 | 7 |  |  |
| default | jdk-pinned | 0 | 150,000 | 7 |  |  |
| big | mpsc | 256 | 149,994–150,000 | 7 | 10 → 3 |  |
| big | mpsc-mode3 | 256 | 149,997–150,002 | 7 | 64 → 59 |  |
| big | jdk | 256 | 149,044–150,018 | 6 | 60 → 25 | not a measurement (r1 to r3, c2c run, stat run) |
| big | jdk-pinned | 256 | 149,998–150,011 | 6–7 | 188 → 19 |  |
| big | mpsc | 0 | 149,999–150,000 | 8 |  |  |
| big | mpsc-mode3 | 0 | 149,999–150,000 | 8 |  |  |
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
| default | mpsc | 75,212 | 1.20 | 0.83 | 62,678 | 157,944,798,154 | 131,623,071,543 | 2,100,000 |  |
| default | mpsc-mode3 | 89,170 | 1.03 | 0.97 | 86,585 | 187,256,865,135 | 181,828,400,386 | 2,100,000 |  |
| default | jdk | 129,283 | 0.86 | 1.16 | 150,225 | 271,494,009,373 | 315,471,782,241 | 2,100,000 |  |
| default | jdk-pinned | 122,870 | 0.67 | 1.49 | 182,809 | 258,027,949,204 | 383,898,900,981 | 2,100,000 |  |
| big | mpsc | 60,432 | 0.22 | 4.60 | 278,225 | 126,906,500,996 | 584,272,041,546 | 2,100,000 |  |
| big | mpsc-mode3 | 66,776 | 0.23 | 4.27 | 285,039 | 140,230,523,341 | 598,582,017,994 | 2,100,000 |  |
| big | jdk | 53,149 | 0.19 | 5.40 | 286,987 | 111,613,636,604 | 602,672,654,197 | 2,100,000 | this run: not a measurement |
| big | jdk-pinned | 56,712 | 0.17 | 5.75 | 326,183 | 119,095,332,062 | 684,984,100,489 | 2,100,000 |  |

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
| default | mpsc | 0.0% | 0.29% | 0.03% | 21,328 | 61 | 6 |  |
| default | mpsc-mode3 | 0.0% | 0.52% | 0.04% | 24,462 | 126 | 9 |  |
| default | jdk | 71.0% | 1.29% | 0.10% | 38,354 | 493 | 39 |  |
| default | jdk-pinned | 66.2% | 2.43% | 0.14% | 52,062 | 1,263 | 71 |  |
| big | mpsc | 0.0% | 0.47% | 5.12% | 62,364 | 290 | 3,193 |  |
| big | mpsc-mode3 | 0.0% | 0.50% | 4.93% | 62,156 | 312 | 3,066 |  |
| big | jdk | 3.5% | 0.58% | 5.40% | 50,233 | 290 | 2,714 | this run: not a measurement |
| big | jdk-pinned | 8.3% | 0.63% | 5.52% | 65,813 | 413 | 3,633 |  |

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
| default | mpsc | 15.26 | 16.62 | +1.36 | 6,411 MiB |  |
| default | mpsc-mode3 | 20.51 | 21.98 | +1.47 | 6,975 MiB |  |
| default | jdk | 35.92 | 36.40 | +0.48 | 6,465 MiB |  |
| default | jdk-pinned | 43.63 | 43.28 | -0.35 | 6,502 MiB |  |
| big | mpsc | 63.06 | 70.89 | +7.83 | 5,293 MiB |  |
| big | mpsc-mode3 | 66.50 | 72.70 | +6.20 | 5,543 MiB |  |
| big | jdk | not a measurement | not a measurement |  | 5,778 MiB | this run: not a measurement |
| big | jdk-pinned | 75.54 | 78.71 | +3.17 | 5,673 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 to r3, as in In short. cpu µs/req Epsilon:
the line cpu of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).
