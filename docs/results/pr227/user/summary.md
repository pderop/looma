# User benchmark

```
date         2026-11-22 01:37:52 +0000
host         gabuzo-amd, Linux 7.0.0-43-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-pr227-5766f81)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
heap         2g, G1; the epsilon runs 12g, Epsilon
command      scripts/bench-user.sh --users-per-group 8000,200000 --depth 256 --rate 150000 --step 20000 --out bench/user-pr227-20261122-013751
```

## The runs

What the benchmark is: [benchmark-user.md](../../../benchmark-user.md).

150000 req/s. Each group has 8 backend connections.
The cases, by users per group:

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
- The schedulers: mpsc, mpsc-affine, mpsc-affine-pinned, jdk.
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
| mpsc | 27.84 | 630k/610k | 550k/550k | 64.11 | 190k/170k | none/none |
| mpsc-affine | 15.32 | 890k/870k | 830k/870k | 54.06 | 190k/190k | 170k/170k |
| mpsc-affine-pinned | 14.57 | 1,070k/1,090k | 1,070k/1,090k | 54.38 | 190k/190k | 170k/170k |
| jdk | 40.20 | 670k/670k | 530k/510k | 64.36 | 190k/150k | none/none |

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
- mpsc-affine-pinned: both, **each actor stays on its carrier** and **each carrier is pinned to one CPU** (`Carriers.mpsc(n).affine(true).pinned(true)`).
- jdk: the JDK's default scheduler, **a ForkJoinPool** (`Carriers.builtin(n)`).

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations | backend CPUs |
|---|---|---|---|---|---|---|---|---|---|---|
| small | mpsc | 256 | 27.84 | 27.16–28.53 | -30.7% | 36 | 80–84 | 118–133 | 58.0% | 0.87–0.92 |
| small | mpsc-affine | 256 | 15.32 | 15.21–15.42 | -61.9% | 20 | 29–30 | 35–36 | 0.0% | 0.85 |
| small | mpsc-affine-pinned | 256 | 14.57 | 14.07–15.08 | -63.7% | 20 | 29–30 | 35–37 | 0.0% | 0.84–0.87 |
| small | jdk | 256 | 40.20 | 40.04–40.35 |  | 42–43 | 90–94 | 670–907 | 60.7% | 0.83 |
| small | mpsc | 0 | 12.38 | 12.37–12.40 | -56.0% | 15–16 | 23 | 28 | 73.0% | 0.77–0.85 |
| small | mpsc-affine | 0 | 11.62 | 11.40–11.84 | -58.7% | 16 | 25 | 29–30 | 0.0% | 0.85–0.86 |
| small | mpsc-affine-pinned | 0 | 10.92 | 10.59–11.25 | -61.2% | 16 | 25–26 | 31–73 | 0.0% | 0.85–0.87 |
| small | jdk | 0 | 28.14 | 27.91–28.38 |  | 20–23 | 30 | 35–469 | 84.3% | 0.80–0.83 |
| big | mpsc | 256 | 64.11 | 63.47–64.75 | -0.4% | 235–287 | 1,195–1,218 | 3,428–6,480 | 8.8% | 0.79–0.81 |
| big | mpsc-affine | 256 | 54.06 | 54.00–54.13 | -16.0% | 106–107 | 488–569 | 1,615–2,759 | 0.0% | 0.79–0.80 |
| big | mpsc-affine-pinned | 256 | 54.38 | 54.36–54.41 | -15.5% | 108 | 470–485 | 1,799–1,800 | 0.0% | 0.79 |
| big | jdk | 256 | 64.36 | 64.27–64.46 |  | 818–872 | 8,872–16,704 | 24,576–47,415 | 4.2% | 0.75 |
| big | mpsc | 0 | 12.73 | 12.43–13.03 | -56.2% | 15–17 | 22–24 | 25–27 | 74.5% | 0.86–0.90 |
| big | mpsc-affine | 0 | 11.71 | 11.47–11.95 | -59.7% | 16 | 24–25 | 28–29 | 0.0% | 0.84 |
| big | mpsc-affine-pinned | 0 | 10.77 | 10.70–10.85 | -62.9% | 16 | 24–25 | 28–29 | 0.0% | 0.85–0.87 |
| big | jdk | 0 | 29.04 | 29.02–29.05 |  | 19 | 28–29 | 36–47 | 84.2% | 0.81–0.82 |

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
| small | mpsc | 256 | 149,999 | 7 | 2 → 1 |  |
| small | mpsc-affine | 256 | 149,999 | 7 | 4 → 3 |  |
| small | mpsc-affine-pinned | 256 | 149,999–150,000 | 7–8 | 0 → 0 |  |
| small | jdk | 256 | 149,999–150,000 | 7–8 | 31 → 100 |  |
| small | mpsc | 0 | 149,999 | 7–8 |  |  |
| small | mpsc-affine | 0 | 149,999 | 7–8 |  |  |
| small | mpsc-affine-pinned | 0 | 149,999 | 8 |  |  |
| small | jdk | 0 | 149,999 | 8 |  |  |
| big | mpsc | 256 | 149,972–149,997 | 7 | 36 → 33 |  |
| big | mpsc-affine | 256 | 149,999 | 8 | 9 → 6 |  |
| big | mpsc-affine-pinned | 256 | 149,999–150,000 | 7–8 | 11 → 1 |  |
| big | jdk | 256 | 149,988–150,004 | 7–8 | 20 → 37 |  |
| big | mpsc | 0 | 149,999 | 8–9 |  |  |
| big | mpsc-affine | 0 | 150,000 | 8–9 |  |  |
| big | mpsc-affine-pinned | 0 | 149,999–150,000 | 9 |  |  |
| big | jdk | 0 | 149,999 | 9 |  |  |

From the files of each run, in runs/. req/s: the `throughput` line of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the
`stackchunks` line of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 and r2. A run is not a measurement when its console says it could not keep up
with its rate, its clients sent too late, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| small | mpsc | 67,055 | 0.57 | 1.77 | 118,649 | 140,815,578,800 | 249,163,482,623 | 2,100,000 |  |
| small | mpsc-affine | 69,563 | 1.16 | 0.87 | 60,195 | 146,081,841,318 | 126,410,290,435 | 2,100,000 |  |
| small | mpsc-affine-pinned | 74,651 | 1.28 | 0.78 | 58,229 | 156,766,692,286 | 122,280,776,924 | 2,100,000 |  |
| small | jdk | 120,633 | 0.71 | 1.40 | 168,838 | 253,329,669,250 | 354,558,762,420 | 2,100,000 |  |
| big | mpsc | 49,546 | 0.18 | 5.62 | 278,339 | 104,047,629,087 | 584,512,208,576 | 2,100,000 |  |
| big | mpsc-affine | 62,955 | 0.28 | 3.58 | 225,379 | 132,204,856,461 | 473,295,711,862 | 2,100,000 |  |
| big | mpsc-affine-pinned | 62,820 | 0.27 | 3.66 | 229,811 | 131,922,852,412 | 482,603,704,632 | 2,100,000 |  |
| big | jdk | 53,992 | 0.20 | 5.06 | 273,031 | 113,383,619,965 | 573,365,972,301 | 2,100,000 |  |

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
| small | mpsc | 57.9% | 3.09% | 0.24% | 34,369 | 1,062 | 82 |  |
| small | mpsc-affine | 0.0% | 0.34% | 0.02% | 20,600 | 71 | 4 |  |
| small | mpsc-affine-pinned | 0.0% | 0.36% | 0.02% | 20,986 | 76 | 5 |  |
| small | jdk | 61.4% | 2.62% | 0.25% | 41,942 | 1,097 | 105 |  |
| big | mpsc | 6.7% | 0.63% | 6.10% | 54,133 | 341 | 3,304 |  |
| big | mpsc-affine | 0.0% | 0.48% | 5.39% | 48,099 | 231 | 2,593 |  |
| big | mpsc-affine-pinned | 0.0% | 0.41% | 5.50% | 54,340 | 223 | 2,990 |  |
| big | jdk | 5.1% | 0.63% | 5.93% | 49,654 | 311 | 2,943 |  |

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
| small | mpsc | 69,076 | 145,059,856,272 | 2,100,000 |  |
| small | mpsc-affine | 14,931 | 31,355,880,469 | 2,100,000 |  |
| small | mpsc-affine-pinned | 15,002 | 31,503,305,641 | 2,100,000 |  |
| small | jdk | 67,479 | 141,706,625,136 | 2,100,000 |  |
| big | mpsc | 253,610 | 532,580,073,982 | 2,100,000 |  |
| big | mpsc-affine | 190,516 | 400,083,510,557 | 2,100,000 |  |
| big | mpsc-affine-pinned | 188,546 | 395,947,446,905 | 2,100,000 |  |
| big | jdk | 240,761 | 505,597,753,446 | 2,100,000 |  |

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
| small | mpsc | 27.84 | 27.34 | -0.50 | 6,789 MiB |  |
| small | mpsc-affine | 15.32 | 15.95 | +0.63 | 7,161 MiB |  |
| small | mpsc-affine-pinned | 14.57 | 14.70 | +0.12 | 7,694 MiB |  |
| small | jdk | 40.20 | 40.77 | +0.58 | 7,591 MiB |  |
| big | mpsc | 64.11 | 66.30 | +2.19 | 5,343 MiB |  |
| big | mpsc-affine | 54.06 | 57.43 | +3.37 | 6,379 MiB |  |
| big | mpsc-affine-pinned | 54.38 | 57.43 | +3.05 | 6,568 MiB |  |
| big | jdk | 64.36 | 65.40 | +1.04 | 7,053 MiB |  |

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
| small | mpsc | r1 | 630,000 | 15.84 | 6578.2 | 650,000 | 644,223 | 28770.3 |
| small | mpsc | r2 | 610,000 | 16.37 | 4370.4 | 630,000 | 629,632 | 19316.7 |
| small | mpsc-affine | r1 | 890,000 | 11.14 | 82.9 | 910,000 | 909,904 | 456.4 |
| small | mpsc-affine | r2 | 870,000 | 11.30 | 134.8 | 890,000 | 889,736 | 100.3 |
| small | mpsc-affine-pinned | r1 | 1,070,000 | 9.33 | 670.2 | 1,090,000 | 1,085,154 | 35029.0 |
| small | mpsc-affine-pinned | r2 | 1,090,000 | 9.16 | 657.9 | 1,110,000 | 1,097,916 | 20594.7 |
| small | jdk | r1 | 670,000 | 14.91 | 5644.3 | 690,000 | 689,946 | 6848.5 |
| small | jdk | r2 | 670,000 | 14.89 | 5349.4 | 690,000 | 690,040 | 6258.7 |
| big | mpsc | r1 | 190,000 | 52.30 | 58228.7 | 210,000 | 197,068 | 159776.8 |
| big | mpsc | r2 | 170,000 | 58.51 | 6815.7 | 190,000 | 189,517 | 107544.6 |
| big | mpsc-affine | r1 | 190,000 | 50.45 | 1968.1 | 210,000 | 206,309 | 181927.9 |
| big | mpsc-affine | r2 | 190,000 | 48.57 | 1740.8 | 210,000 | 209,713 | 32964.6 |
| big | mpsc-affine-pinned | r1 | 190,000 | 49.50 | 1091.6 | 210,000 | 208,897 | 124387.3 |
| big | mpsc-affine-pinned | r2 | 190,000 | 50.42 | 1598.5 | 210,000 | 203,799 | 199884.8 |
| big | jdk | r1 | 190,000 | 52.54 | 23740.4 | 210,000 | 199,209 | 79364.1 |
| big | jdk | r2 | 150,000 | 64.61 | 15392.8 | 170,000 | 169,889 | 54460.4 |

From the console of each ramp run, at depth 256, runs/<case>-r<n>-ramp-<scheduler>-256.out: from 150000 req/s,
20000 req/s more at each step, 10s measured, until a step is not kept up with.
Each step is one block of the console, from its `step` line. The console has every step.

- kept up with req/s: the last step kept up with, the `ramp kept up with` line. none: not even the first.
- cpu µs/req, p99 µs: at that step, the `cpu` and `latency` lines of its block.
- missed req/s: the next step, the first not kept up with: its block says it did not keep up, or that a GET got no answer.
  -: the ramp stopped at its last rate, with no step missed.
- achieved req/s, p99 µs: at that missed step, the `throughput` and `latency` lines of its block.
