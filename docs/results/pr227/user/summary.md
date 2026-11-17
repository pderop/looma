# User benchmark

```
date         2026-11-17 02:25:20 +0000
host         gabuzo-amd, Linux 7.0.0-43-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-pr227-5766f81)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench-user.sh
```

## The runs

What the benchmark is: [benchmark-user.md](../../../benchmark-user.md).

150000 req/s. Each group has 8 backend connections. The cases, by users per group:

- small: 8000 users per group, about 640 KiB per group.
- big: 200000 users per group, about 16 MiB per group.

Each scheduler ran 2 rounds in each case. A round: a measure at 150000 req/s, at depth 256 and at depth 0 (r<n>), then a ramp (r<n>-ramp).
Round 1 also has, between the two: a run with perf c2c, one with perf stat, one with perf record for the walk, and one with the Epsilon GC, at depth 256.
In a round, each of these goes through every scheduler before the next one starts. Each run is
a JVM of its own: 10 seconds of warm-up, then 20 seconds measured.

The ramp, at depth 256: after the warm-up, the rate starts at 150000 req/s and goes up by 20000 req/s at each step,
10 seconds measured, until a step is
not kept up with. Between two steps the clients go on sending, unmeasured: 2
seconds for the late answers, then 3 seconds to go up to the next rate.

- Open loop: the clients send at a fixed rate. A latency is from the planned send time of a
  request to its answer. cpu µs/req is the server's: the process minus the clients.
- Each carrier has a core to itself: the script refuses two carrier CPUs on one core.
- The schedulers: mpsc, mpsc-affine, mpsc-pinned, mpsc-affine-pinned, jdk, jdk-pinned.
- jdk-pinned is jdk with each of its workers pinned to one carrier CPU.
- The clients are pinned to their own CPUs.
- The JDK's poller mode is not set. On Linux, the JDK then uses mode 4 under the MPSC scheduler (one epoll per carrier), and mode 2 under jdk (pollers that run as virtual threads).
- Heap: -Xms2g -Xmx2g -XX:+AlwaysPreTouch, G1, and a GC log per run. The epsilon run:
  -XX:+UseEpsilonGC -Xms12g -Xmx12g -XX:+AlwaysPreTouch. Epsilon frees nothing, so no GC runs:
  each allocation takes new memory.
- The c2c, stat and walk runs count during the measure, minus 3 seconds at each end. The c2c run
  also counts the live StackChunks (GC.class_histogram), during the warm-up and after the measure.
  The walk run keeps UserGroupActor.walk out of line. The full report of each c2c run is in
  <case>-c2c-<scheduler>.txt, of each walk run in <case>-walk-<scheduler>.txt, the files of every
  run in runs/.

## At a glance

| scheduler | small, cpu µs/req | small, max req/s | small, max req/s with p99 < 1 ms | big, cpu µs/req | big, max req/s | big, max req/s with p99 < 1 ms |
|---|---|---|---|---|---|---|
| mpsc | 27.92 | 610k/570k | 530k/550k | 65.06 | 170k/170k | none/none |
| mpsc-affine | 15.09 | 570k/850k | 570k/850k | 53.56 | 190k/190k | none/170k |
| mpsc-pinned | 27.23 | 610k/590k | 550k/550k | 65.23 | 170k/170k | none/none |
| mpsc-affine-pinned | 15.16 | 1,050k/1,070k | 1,030k/1,050k | 54.36 | 190k/190k | 170k/170k |
| jdk | 40.45 | 650k/670k | 470k/450k | 64.14 | 150k/170k | none/none |
| jdk-pinned | 40.28 | 630k/650k | 530k/530k | 65.35 | 190k/190k | none/none |

cpu µs/req at depth 256, the mean of r1 and r2, as in In short.
max req/s: the highest rate kept up with by the ramp of each round at depth 256, r1/r2 ...: the
`ramp kept up with` line of its console, as in The ramp.
max req/s with p99 < 1 ms: a rate with an acceptable latency. In each ramp run, from the first step
up, the last step kept up with before the first step whose p99 is above 1 ms: the `latency`
lines of its console, step by step. none: the p99 of the first step is already above.

### The schedulers

- mpsc: the JDK's MPSC scheduler, **no carrier pinned** and **no affinity**: an actor can run
  on any carrier (`Carriers.mpsc(n)`).
- mpsc-affine: mpsc where **each actor stays on its carrier**, no carrier pinned (`Carriers.mpsc(n).affine(true)`).
- mpsc-pinned: mpsc where **each carrier is pinned to one CPU**, no affinity (`Carriers.mpsc(n).pinned(true)`).
- mpsc-affine-pinned: both, **each actor stays on its carrier** and **each carrier is pinned to one CPU** (`Carriers.mpsc(n).affine(true).pinned(true)`).
- jdk: the JDK's default scheduler, **a ForkJoinPool** (`Carriers.builtin(n)`).
- jdk-pinned: jdk, and the script **pins each worker to one carrier CPU**.

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations | backend CPUs |
|---|---|---|---|---|---|---|---|---|---|---|
| small | mpsc | 256 | 27.92 | 27.31–28.54 | -31.0% | 36–37 | 84 | 135–376 | 56.7% | 0.80–0.93 |
| small | mpsc-affine | 256 | 15.09 | 14.93–15.24 | -62.7% | 20 | 31–32 | 246–263 | 0.0% | 0.86–0.87 |
| small | mpsc-pinned | 256 | 27.23 | 26.63–27.83 | -32.7% | 35–36 | 84 | 246–448 | 57.2% | 0.91–0.93 |
| small | mpsc-affine-pinned | 256 | 15.16 | 14.83–15.48 | -62.5% | 20 | 30 | 198–453 | 0.0% | 0.84–0.85 |
| small | jdk | 256 | 40.45 | 40.28–40.61 |  | 40 | 88–92 | 1,192–1,324 | 62.8% | 0.83 |
| small | jdk-pinned | 256 | 40.28 | 39.88–40.68 | -0.4% | 43 | 90–97 | 367–679 | 61.4% | 0.83–0.85 |
| small | mpsc | 0 | 11.82 | 10.96–12.67 | -58.8% | 15–16 | 23 | 29–69 | 70.8% | 0.78–1.05 |
| small | mpsc-affine | 0 | 12.12 | 11.98–12.26 | -57.8% | 17 | 26–27 | 203–216 | 0.0% | 0.86–0.87 |
| small | mpsc-pinned | 0 | 12.28 | 12.03–12.53 | -57.2% | 17 | 24–26 | 35–48 | 74.2% | 1.00–1.02 |
| small | mpsc-affine-pinned | 0 | 11.26 | 10.91–11.61 | -60.8% | 17 | 26–27 | 38–76 | 0.0% | 0.87 |
| small | jdk | 0 | 28.69 | 28.44–28.94 |  | 20–24 | 30–31 | 1,166–1,272 | 83.5% | 0.81–0.82 |
| small | jdk-pinned | 0 | 29.66 | 29.34–29.99 | +3.4% | 20–22 | 30–31 | 48–59 | 84.2% | 0.83 |
| big | mpsc | 256 | 65.06 | 64.75–65.36 | +1.4% | 291–386 | 1,553–8,684 | 7,770–36,176 | 6.1% | 0.81 |
| big | mpsc-affine | 256 | 53.56 | 53.31–53.82 | -16.5% | 103 | 541–547 | 6,025–6,885 | 0.0% | 0.79–0.81 |
| big | mpsc-pinned | 256 | 65.23 | 65.08–65.38 | +1.7% | 308–331 | 1,493–1,865 | 7,316–8,962 | 6.3% | 0.79–0.81 |
| big | mpsc-affine-pinned | 256 | 54.36 | 54.22–54.49 | -15.3% | 105–109 | 534–541 | 2,580–3,410 | 0.0% | 0.79–0.80 |
| big | jdk | 256 | 64.14 | 64.09–64.19 |  | 751–770 | 9,994–15,057 | 25,756–64,979 | 4.9% | 0.73–0.77 |
| big | jdk-pinned | 256 | 65.35 | 64.96–65.74 | +1.9% | 606–949 | 1,875–2,869 | 6,103–8,245 | 4.5% | 0.75–0.76 |
| big | mpsc | 0 | 13.19 | 13.03–13.35 | -55.1% | 16 | 23 | 28–29 | 75.7% | 0.79–0.88 |
| big | mpsc-affine | 0 | 11.89 | 11.23–12.55 | -59.6% | 17 | 26 | 32 | 0.0% | 0.85–0.88 |
| big | mpsc-pinned | 0 | 12.18 | 12.13–12.22 | -58.6% | 17 | 23–25 | 27–29 | 73.8% | 1.00–1.02 |
| big | mpsc-affine-pinned | 0 | 11.29 | 11.18–11.41 | -61.6% | 16 | 25 | 30–31 | 0.0% | 0.85–0.86 |
| big | jdk | 0 | 29.40 | 28.55–30.25 |  | 18–21 | 28–31 | 45–184 | 83.8% | 0.81–0.82 |
| big | jdk-pinned | 0 | 29.34 | 28.20–30.47 | -0.2% | 21–24 | 30–31 | 36–38 | 83.8% | 0.82–0.83 |

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1
```

From the console of each run, runs/<case>-<run>-<scheduler>-<depth>.out: cpu µs/req, the `cpu` line; latencies, the
`latency` line; migrations, the `migrations` line; backend CPUs, the `backend cpu` line: the CPU time of the
backend process, read from /proc, divided by the length of the measure. cpu µs/req and
migrations: the mean of r1 and r2. cpu range, backend CPUs and
latencies: the lowest and the highest of r1 and r2. vs jdk is empty when jdk is not a measurement.
When a run is not a measurement, its numbers are not in this table: the mean is that of the
other runs, named in parentheses after it, as (r2). The column note of Controls says which run
was left out.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## Controls

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| small | mpsc | 256 | 150,000–150,001 | 7 | 6 → 2 |  |
| small | mpsc-affine | 256 | 150,000–150,003 | 7–8 | 3 → 0 |  |
| small | mpsc-pinned | 256 | 150,000 | 7 | 3 → 1 |  |
| small | mpsc-affine-pinned | 256 | 150,001–150,002 | 8 | 3 → 2 |  |
| small | jdk | 256 | 150,000–150,004 | 7–8 | 21 → 18 |  |
| small | jdk-pinned | 256 | 150,000–150,003 | 7–8 | 28 → 26 |  |
| small | mpsc | 0 | 150,000–150,003 | 7–8 |  |  |
| small | mpsc-affine | 0 | 150,000–150,003 | 8 |  |  |
| small | mpsc-pinned | 0 | 149,999–150,000 | 7 |  |  |
| small | mpsc-affine-pinned | 0 | 149,990–150,003 | 7–8 |  |  |
| small | jdk | 0 | 149,998–150,003 | 8 |  |  |
| small | jdk-pinned | 0 | 149,999–150,000 | 8 |  |  |
| big | mpsc | 256 | 150,005–150,006 | 7 | 108 → 6 |  |
| big | mpsc-affine | 256 | 149,999–150,002 | 8 | 4 → 4 |  |
| big | mpsc-pinned | 256 | 150,008–150,061 | 7 | 26 → 4 |  |
| big | mpsc-affine-pinned | 256 | 150,002–150,003 | 7–8 | 6 → 5 |  |
| big | jdk | 256 | 149,983–150,027 | 8 | 1052 → 37 |  |
| big | jdk-pinned | 256 | 150,004–150,011 | 7–8 | 155 → 27 |  |
| big | mpsc | 0 | 150,000 | 8–9 |  |  |
| big | mpsc-affine | 0 | 150,000–150,003 | 9 |  |  |
| big | mpsc-pinned | 0 | 150,002 | 8–9 |  |  |
| big | mpsc-affine-pinned | 0 | 150,002–150,003 | 8–9 |  |  |
| big | jdk | 0 | 150,001–150,052 | 9–10 |  |  |
| big | jdk-pinned | 0 | 150,000 | 9 |  |  |

From the files of each run, in runs/. req/s: the `throughput` line of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the
`stackchunks` line of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 and r2. A run is not a measurement when its console says it could not keep up
with its rate, its clients sent too late, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| small | mpsc | 72,981 | 0.63 | 1.59 | 115,754 | 153,260,430,379 | 243,084,192,157 | 2,100,000 |  |
| small | mpsc-affine | 73,562 | 1.15 | 0.87 | 63,832 | 154,479,184,078 | 134,046,777,157 | 2,100,000 |  |
| small | mpsc-pinned | 67,528 | 0.61 | 1.63 | 110,054 | 141,808,722,329 | 231,112,620,307 | 2,100,000 |  |
| small | mpsc-affine-pinned | 73,350 | 1.16 | 0.86 | 63,282 | 154,034,286,420 | 132,891,552,479 | 2,100,000 |  |
| small | jdk | 115,845 | 0.70 | 1.43 | 165,688 | 243,275,426,436 | 347,944,659,755 | 2,100,000 |  |
| small | jdk-pinned | 114,888 | 0.68 | 1.46 | 167,934 | 241,264,898,411 | 352,661,288,885 | 2,100,000 |  |
| big | mpsc | 51,191 | 0.18 | 5.48 | 280,344 | 107,501,319,271 | 588,722,340,121 | 2,100,000 |  |
| big | mpsc-affine | 61,664 | 0.26 | 3.83 | 236,421 | 129,495,348,016 | 496,484,319,152 | 2,100,000 |  |
| big | mpsc-pinned | 53,425 | 0.20 | 5.05 | 269,794 | 112,193,211,195 | 566,567,754,227 | 2,100,000 |  |
| big | mpsc-affine-pinned | 59,479 | 0.26 | 3.86 | 229,433 | 124,905,250,328 | 481,808,702,461 | 2,100,000 |  |
| big | jdk | 54,995 | 0.20 | 5.01 | 275,518 | 115,489,137,053 | 578,588,833,155 | 2,100,000 |  |
| big | jdk-pinned | 55,282 | 0.20 | 5.01 | 277,087 | 116,093,029,030 | 581,882,090,739 | 2,100,000 |  |

```
instructions/req       = instructions / requests
cycles/req             = cycles / requests
IPC                    = instructions / cycles
cycles per instruction = cycles / instructions = 1 / IPC
```

The stat run of each scheduler at depth 256. instructions and cycles: the counts of perf
stat, summed over the server's threads (every thread but client-*), in
runs/<case>-stat-<scheduler>.csv. requests: 150,000 req/s × 14 s, the window of perf stat.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## perf c2c

| case | scheduler | migrations | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |
|---|---|---|---|---|---|---|---|---|
| small | mpsc | 59.2% | 4.00% | 0.49% | 36,643 | 1,464 | 180 |  |
| small | mpsc-affine | 0.0% | 0.28% | 0.07% | 21,086 | 58 | 14 |  |
| small | mpsc-pinned | 61.4% | 2.58% | 0.18% | 35,373 | 911 | 64 |  |
| small | mpsc-affine-pinned | 0.0% | 0.29% | 0.03% | 21,347 | 62 | 7 |  |
| small | jdk | 63.4% | 2.16% | 0.22% | 43,261 | 935 | 97 |  |
| small | jdk-pinned | 60.9% | 2.21% | 0.16% | 50,883 | 1,127 | 83 |  |
| big | mpsc | 6.0% | 0.53% | 6.04% | 58,699 | 309 | 3,547 |  |
| big | mpsc-affine | 0.0% | 0.43% | 4.76% | 51,823 | 221 | 2,467 |  |
| big | mpsc-pinned | 6.1% | 0.61% | 5.67% | 53,522 | 324 | 3,034 |  |
| big | mpsc-affine-pinned | 0.0% | 0.36% | 4.86% | 53,039 | 189 | 2,580 |  |
| big | jdk | 3.3% | 0.48% | 4.99% | 54,302 | 258 | 2,707 |  |
| big | jdk-pinned | 4.4% | 0.58% | 5.70% | 62,171 | 360 | 3,546 |  |

```
other L3 = Load Remote HITM / Load Operations
DRAM     = Load DRAM / Load Operations
```

The c2c run of each scheduler at depth 256. Load Operations, Load Remote HITM and Load DRAM
are copied from its report, <case>-c2c-<scheduler>.txt, block Trace Event Information:

- Load Operations: the memory loads that perf sampled.
- Load Remote HITM: among them, the loads that took their line from behind another L3,
  because a core there had modified it.
- Load DRAM: among them, the loads served by the memory (Load Local DRAM + Load Remote DRAM).

migrations: the `migrations` line of the console of the c2c run, runs/<case>-c2c-<scheduler>-256.out.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## Walk

| case | scheduler | walk cycles/req | walk cycles | requests | note |
|---|---|---|---|---|---|
| small | mpsc | 66,734 | 140,141,526,150 | 2,100,000 |  |
| small | mpsc-affine | 16,158 | 33,931,976,395 | 2,100,000 |  |
| small | mpsc-pinned | 63,309 | 132,949,745,767 | 2,100,000 |  |
| small | mpsc-affine-pinned | 15,230 | 31,983,168,862 | 2,100,000 |  |
| small | jdk | 66,815 | 140,310,704,524 | 2,100,000 |  |
| small | jdk-pinned | 68,965 | 144,826,404,603 | 2,100,000 |  |
| big | mpsc | 247,076 | 518,860,114,002 | 2,100,000 |  |
| big | mpsc-affine | 191,852 | 402,888,605,572 | 2,100,000 |  |
| big | mpsc-pinned | 253,078 | 531,463,027,165 | 2,100,000 |  |
| big | mpsc-affine-pinned | 194,539 | 408,531,667,029 | 2,100,000 |  |
| big | jdk | 238,571 | 500,999,482,700 | 2,100,000 |  |
| big | jdk-pinned | 242,168 | 508,552,195,132 | 2,100,000 |  |

```
walk cycles/req = walk cycles / requests
```

The walk run of each scheduler, at depth 256: the same run, with UserGroupActor.walk kept out
of line (-XX:CompileCommand=dontinline), so that it has a name of its own. walk cycles: the
cycles that perf record gives to UserGroupActor.walk, the column Period of its report,
<case>-walk-<scheduler>.txt. It is only the walk: the scheduler and the kernel are not in it.
requests: 150,000 req/s × 14 s, the window of perf record.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## Epsilon

| case | scheduler | cpu µs/req G1 | cpu µs/req Epsilon | difference | heap used | note |
|---|---|---|---|---|---|---|
| small | mpsc | 27.92 | 28.48 | +0.56 | 7,193 MiB |  |
| small | mpsc-affine | 15.09 | 17.82 | +2.73 | 7,785 MiB |  |
| small | mpsc-pinned | 27.23 | 26.65 | -0.58 | 6,993 MiB |  |
| small | mpsc-affine-pinned | 15.16 | 15.27 | +0.11 | 7,317 MiB |  |
| small | jdk | 40.45 | 41.16 | +0.71 | 7,571 MiB |  |
| small | jdk-pinned | 40.28 | 40.45 | +0.17 | 7,633 MiB |  |
| big | mpsc | 65.06 | 66.36 | +1.30 | 5,603 MiB |  |
| big | mpsc-affine | 53.56 | 58.49 | +4.93 | 6,371 MiB |  |
| big | mpsc-pinned | 65.23 | 66.33 | +1.10 | 5,639 MiB |  |
| big | mpsc-affine-pinned | 54.36 | 58.61 | +4.25 | 6,444 MiB |  |
| big | jdk | 64.14 | 64.31 | +0.17 | 6,340 MiB |  |
| big | jdk-pinned | 65.35 | 66.13 | +0.78 | 6,209 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 and r2, as in In short. cpu µs/req Epsilon:
the `cpu` line of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## The ramp

| case | scheduler | run | kept up with req/s | cpu µs/req | p99 µs | missed req/s | achieved req/s | p99 µs |
|---|---|---|---|---|---|---|---|---|
| small | mpsc | r1 | 610,000 | 16.37 | 6127.6 | 630,000 | 622,175 | 26509.3 |
| small | mpsc | r2 | 570,000 | 17.52 | 1455.1 | 590,000 | 589,901 | 5730.3 |
| small | mpsc-affine | r1 | 570,000 | 13.70 | 37.9 | 590,000 | 589,886 | 38.7 |
| small | mpsc-affine | r2 | 850,000 | 11.52 | 613.9 | 870,000 | 869,348 | 4866.0 |
| small | mpsc-pinned | r1 | 610,000 | 16.37 | 3239.9 | 630,000 | 629,929 | 16859.1 |
| small | mpsc-pinned | r2 | 590,000 | 16.93 | 1640.4 | 610,000 | 610,016 | 4032.5 |
| small | mpsc-affine-pinned | r1 | 1,050,000 | 9.51 | 1649.7 | 1,070,000 | 1,053,693 | 36143.1 |
| small | mpsc-affine-pinned | r2 | 1,070,000 | 9.33 | 4161.5 | 1,090,000 | 1,068,114 | 35192.8 |
| small | jdk | r1 | 650,000 | 15.30 | 4118.5 | 670,000 | 669,841 | 5222.4 |
| small | jdk | r2 | 670,000 | 14.82 | 6000.6 | 690,000 | 690,156 | 8437.8 |
| small | jdk-pinned | r1 | 630,000 | 15.84 | 6234.1 | 650,000 | 650,047 | 7671.8 |
| small | jdk-pinned | r2 | 650,000 | 15.36 | 8069.1 | 670,000 | 670,051 | 10534.9 |
| big | mpsc | r1 | 170,000 | 58.25 | 15392.8 | 190,000 | 189,521 | 83296.3 |
| big | mpsc | r2 | 170,000 | 58.29 | 39551.0 | 190,000 | 187,283 | 97386.5 |
| big | mpsc-affine | r1 | 190,000 | 50.98 | 58589.2 | 210,000 | 194,592 | 207093.8 |
| big | mpsc-affine | r2 | 190,000 | 49.96 | 2038.8 | 210,000 | 207,320 | 187170.8 |
| big | mpsc-pinned | r1 | 170,000 | 58.54 | 11141.1 | 190,000 | 189,938 | 90308.6 |
| big | mpsc-pinned | r2 | 170,000 | 58.55 | 10469.4 | 190,000 | 190,176 | 101187.6 |
| big | mpsc-affine-pinned | r1 | 190,000 | 50.15 | 1047.0 | 210,000 | 205,128 | 189530.1 |
| big | mpsc-affine-pinned | r2 | 190,000 | 49.85 | 1044.5 | 210,000 | 209,965 | 72941.6 |
| big | jdk | r1 | 150,000 | 64.88 | 17006.6 | 170,000 | 169,945 | 60784.6 |
| big | jdk | r2 | 170,000 | 57.69 | 26132.5 | 190,000 | 189,533 | 95354.9 |
| big | jdk-pinned | r1 | 190,000 | 52.70 | 44826.6 | 210,000 | 196,259 | 125173.8 |
| big | jdk-pinned | r2 | 190,000 | 52.52 | 21889.0 | 210,000 | 196,766 | 129105.9 |

From the console of each ramp run, at depth 256, runs/<case>-r<n>-ramp-<scheduler>-256.out: from 150000 req/s,
20000 req/s more at each step, 10s measured, until a step is not kept up with.
Each step is one block of the console, from its `step` line. The console has every step.

- kept up with req/s: the last step kept up with, the `ramp kept up with` line. none: not even the first.
- cpu µs/req, p99 µs: at that step, the `cpu` and `latency` lines of its block.
- missed req/s: the next step, the first not kept up with: its block says it did not keep up, or that a GET got no answer.
  -: the ramp stopped at its last rate, with no step missed.
- achieved req/s, p99 µs: at that missed step, the `throughput` and `latency` lines of its block.
