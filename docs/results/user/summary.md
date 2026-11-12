# User benchmark

```
date         2026-11-11 23:43:08 +0000
host         gabuzo-amd, Linux 7.0.0-42-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-pr227-5766f81)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
heap         2g, G1; the epsilon runs 12g, Epsilon
command      ./scripts/bench-user.sh 
```

## The runs

150000 req/s. Each group has 8 backend connections. The cases, by users per group:

- default: 8000 users per group, about 640 KiB per group.
- big: 200000 users per group, about 16 MiB per group.

In each case, each scheduler ran 3 times at depth 256 and at depth 0 (r1 to r3), then once
more with perf c2c, once more with perf stat, once more with perf record for the walk, and
once more with the Epsilon GC, at depth 256. Each run: 10 seconds of warm-up, then
20 seconds measured.

Last, 2 ramp runs per scheduler (ramp1 to ramp2), each in a JVM of its own, at depth 256.
After the warm-up of each, the rate starts at 150000 req/s and goes up by 20000 req/s at each step,
10 seconds measured, until a step is
not kept up with. Between two steps the clients go on sending, unmeasured: 2
seconds for the late answers, then 3 seconds to go up to the next rate.

- Open loop: the clients send at a fixed rate. A latency is from the planned send time of a
  request to its answer. cpu µs/req is the server's: the process minus the clients.
- Each carrier has a core to itself: the script refuses two carrier CPUs on one core.
- The schedulers: mpsc, mpsc-unpinned, mpsc-no-affinity, mpsc-unpinned-no-affinity, jdk, jdk-pinned.
- jdk-pinned is jdk with each of its workers pinned to one carrier CPU. The clients are pinned to
  their own CPUs.
- mpsc: the MPSC scheduler of the JDK (openjdk/loom pull request 227), behind Carriers.mpsc.
  Each carrier is pinned to one CPU, and an actor never moves to another carrier.
  The JDK's poller mode is not set: on Linux, it is mode 4, one epoll per carrier.
- mpsc-unpinned is mpsc with no carrier pinned (Carriers.mpsc(n).pinned(false)): the OS places them.
- mpsc-no-affinity is mpsc where an actor can run on any carrier: its thread starts on the carrier
  of its sender, and an idle carrier may take it (Carriers.mpsc(n).affinity(false)).
  mpsc-unpinned-no-affinity: the same, no carrier pinned.
- Heap: -Xms2g -Xmx2g -XX:+AlwaysPreTouch, G1, and a GC log per run. The epsilon run:
  -XX:+UseEpsilonGC -Xms12g -Xmx12g -XX:+AlwaysPreTouch. Epsilon frees nothing, so no GC runs:
  each allocation takes new memory.
- The c2c, stat and walk runs count during the measure, minus 3 seconds at each end. The c2c run
  also counts the live StackChunks (GC.class_histogram), during the warm-up and after the measure.
  The walk run keeps UserGroupActor.walk out of line. The full report of each c2c run is in
  <case>-c2c-<scheduler>.txt, of each walk run in <case>-walk-<scheduler>.txt, the files of every
  run in runs/.

## At a glance

| scheduler | default, cpu µs/req | big, cpu µs/req | default, max req/s | big, max req/s | default, max req/s with p99 < 1 ms | big, max req/s with p99 < 1 ms |
|---|---|---|---|---|---|---|
| mpsc | 14.60 | 53.85 | 1,070,000 / 1,090,000 | 190,000 / 190,000 | 1,070,000 / 1,070,000 | 170,000 / 190,000 |
| mpsc-unpinned | 15.37 | 53.54 | 890,000 / 930,000 | 190,000 / 190,000 | 830,000 / 850,000 | 170,000 / 170,000 |
| mpsc-no-affinity | 26.76 | 65.19 | 630,000 / 630,000 | 190,000 / 170,000 | 570,000 / 570,000 | none / none |
| mpsc-unpinned-no-affinity | 27.57 | 64.77 | 610,000 / 630,000 | 170,000 / 170,000 | 550,000 / 550,000 | none / none |
| jdk | 40.34 | 64.54 | 670,000 / 690,000 | 170,000 / 170,000 | 530,000 / 510,000 | none / none |
| jdk-pinned | 39.81 | 65.03 | 670,000 / 690,000 | 190,000 / 190,000 | 530,000 / 530,000 | none / none |

cpu µs/req at depth 256, the mean of r1 to r3, as in In short.
max req/s: the highest rate kept up with by each ramp run at depth 256, ramp1 / ramp2 ...: the line
ramp kept up with of its console, as in The ramp.
max req/s with p99 < 1 ms: a rate with an acceptable latency. In each ramp run, from the first step
up, the last step kept up with before the first step whose p99 is above 1 ms: the lines latency
of its console, step by step. none: the p99 of the first step is already above.

#### The schedulers

- mpsc: the MPSC scheduler of openjdk/loom pull request 227, with
  `-Djdk.virtualThreadScheduler.useMpsc=true -Djdk.virtualThreadScheduler.mpscSteal=true`.
  Looma **pins each carrier to one CPU**. An actor's threads have the `Thread.Affinity` of its
  carrier: **never stolen**. The threads an actor starts have none: **an idle carrier can steal them**.
- mpsc-unpinned: mpsc, **no carrier pinned**.
- mpsc-no-affinity: mpsc, **carriers pinned**, but an actor's threads have no affinity: **they start on the carrier of the sender, and an idle carrier can steal them**.
- mpsc-unpinned-no-affinity: both, **no carrier pinned** and **no affinity**.
- jdk: the JDK's default scheduler, **a ForkJoinPool**.
- jdk-pinned: jdk, and the script **pins each worker to one carrier CPU**.

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations | backend CPUs |
|---|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 256 | 14.60 | 14.04–15.23 | -63.8% | 19–20 | 28–30 | 36–291 | 0.0% | 0.82–0.86 |
| default | mpsc-unpinned | 256 | 15.37 | 14.90–15.72 | -61.9% | 20 | 29–30 | 35–37 | 0.0% | 0.84–0.85 |
| default | mpsc-no-affinity | 256 | 26.76 | 26.45–27.34 | -33.7% | 34–35 | 80–84 | 117–121 | 58.1% | 0.89–0.92 |
| default | mpsc-unpinned-no-affinity | 256 | 27.57 | 26.11–28.51 | -31.7% | 33–36 | 79–81 | 120–133 | 60.4% | 0.84–0.93 |
| default | jdk | 256 | 40.34 | 39.83–40.90 |  | 40–42 | 86–90 | 679–2,036 | 62.0% | 0.82–0.83 |
| default | jdk-pinned | 256 | 39.81 | 39.51–40.17 | -1.3% | 42–43 | 89–93 | 129–132 | 61.7% | 0.82–0.83 |
| default | mpsc | 0 | 11.19 | 10.91–11.40 | -62.8% | 16 | 25 | 30–31 | 0.0% | 0.83–0.85 |
| default | mpsc-unpinned | 0 | 11.54 | 10.94–11.86 | -61.6% | 16 | 24–26 | 30 | 0.0% | 0.83–0.88 |
| default | mpsc-no-affinity | 0 | 11.82 | 11.80–11.85 | -60.6% | 16–17 | 22–26 | 27–31 | 72.0% | 0.98–1.01 |
| default | mpsc-unpinned-no-affinity | 0 | 12.73 | 12.47–12.94 | -57.6% | 16–17 | 22–24 | 26–28 | 74.1% | 0.82–0.94 |
| default | jdk | 0 | 30.04 | 29.74–30.29 |  | 20–22 | 30 | 37–142 | 84.3% | 0.81–0.82 |
| default | jdk-pinned | 0 | 29.77 | 29.71–29.82 | -0.9% | 21–22 | 29–30 | 36–41 | 85.0% | 0.81–0.82 |
| big | mpsc | 256 | 53.85 | 52.79–54.40 | -16.6% | 99–108 | 450–532 | 977–11,321 | 0.0% | 0.79–0.80 |
| big | mpsc-unpinned | 256 | 53.54 | 53.00–54.13 | -17.0% | 102–107 | 439–508 | 1,172–1,597 | 0.0% | 0.80 |
| big | mpsc-no-affinity | 256 | 65.19 | 65.16–65.23 | +1.0% | 319–322 | 1,347–1,365 | 3,549–3,650 | 6.3% | 0.76–0.78 |
| big | mpsc-unpinned-no-affinity | 256 | 64.77 | 63.92–65.39 | +0.3% | 253–349 | 1,626–1,802 | 14,909–62,915 | 7.1% | 0.77–0.79 |
| big | jdk | 256 | 64.54 | 64.31–64.84 |  | 795–1,026 | 7,446–14,623 | 27,378–40,665 | 4.2% | 0.73–0.75 |
| big | jdk-pinned | 256 | 65.03 | 64.66–65.56 | +0.7% | 592–895 | 1,792–2,314 | 2,339–3,258 | 5.0% | 0.75–0.78 |
| big | mpsc | 0 | 11.49 | 11.24–11.64 | -61.9% | 16 | 24–25 | 28–29 | 0.0% | 0.84 |
| big | mpsc-unpinned | 0 | 11.59 | 10.87–12.03 | -61.5% | 16 | 24–26 | 28–29 | 0.0% | 0.84–0.87 |
| big | mpsc-no-affinity | 0 | 12.10 | 11.79–12.45 | -59.8% | 15–17 | 21–25 | 24–29 | 70.9% | 0.95–1.01 |
| big | mpsc-unpinned-no-affinity | 0 | 12.58 | 12.10–12.85 | -58.2% | 17 | 24 | 27–28 | 73.7% | 0.86–0.91 |
| big | jdk | 0 | 30.13 | 29.50–30.84 |  | 19–21 | 29–30 | 38–60 | 84.9% | 0.81–0.82 |
| big | jdk-pinned | 0 | 28.93 | 28.30–30.02 | -4.0% | 19–20 | 28–29 | 33–35 | 83.8% | 0.81 |

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1
```

From the console of each run, runs/<case>-<run>-<scheduler>-<depth>.out: cpu µs/req, the line cpu; latencies, the line
latency; migrations, the line migrations; backend CPUs, the line backend cpu: the CPU time of the
backend process, read from /proc, divided by the length of the measure. cpu µs/req and
migrations: the mean of r1 to r3. cpu range, backend CPUs and
latencies: the lowest and the highest of r1 to r3. vs jdk is empty when jdk is not a measurement.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## Controls

| case | scheduler | depth | req/s | GCs | live StackChunks | note |
|---|---|---|---|---|---|---|
| default | mpsc | 256 | 150,000 | 8 | 2 → 0 |  |
| default | mpsc-unpinned | 256 | 149,999–150,000 | 7–8 | 3 → 2 |  |
| default | mpsc-no-affinity | 256 | 149,999–150,000 | 7 | 1 → 3 |  |
| default | mpsc-unpinned-no-affinity | 256 | 149,999–150,000 | 7 | 4 → 1 |  |
| default | jdk | 256 | 150,000 | 7 | 27 → 29 |  |
| default | jdk-pinned | 256 | 149,999–150,000 | 7–8 | 28 → 29 |  |
| default | mpsc | 0 | 149,999–150,000 | 7–8 |  |  |
| default | mpsc-unpinned | 0 | 149,999–150,001 | 7–8 |  |  |
| default | mpsc-no-affinity | 0 | 149,999–150,000 | 7–8 |  |  |
| default | mpsc-unpinned-no-affinity | 0 | 149,999 | 7 |  |  |
| default | jdk | 0 | 149,999–150,000 | 8 |  |  |
| default | jdk-pinned | 0 | 149,999–150,000 | 8–9 |  |  |
| big | mpsc | 256 | 149,999–150,000 | 7–8 | 37 → 4 |  |
| big | mpsc-unpinned | 256 | 149,995–150,000 | 7–8 | 115 → 4 |  |
| big | mpsc-no-affinity | 256 | 149,992–150,006 | 6–8 | 6 → 8 |  |
| big | mpsc-unpinned-no-affinity | 256 | 149,998–150,009 | 7 | 44 → 15 |  |
| big | jdk | 256 | 149,985–149,996 | 7–8 | 85 → 37 |  |
| big | jdk-pinned | 256 | 149,996–150,003 | 7–8 | 421 → 151 |  |
| big | mpsc | 0 | 149,999 | 9 |  |  |
| big | mpsc-unpinned | 0 | 149,999 | 8–9 |  |  |
| big | mpsc-no-affinity | 0 | 149,999 | 8–9 |  |  |
| big | mpsc-unpinned-no-affinity | 0 | 149,999–150,000 | 8–9 |  |  |
| big | jdk | 0 | 149,999 | 9 |  |  |
| big | jdk-pinned | 0 | 149,999–150,001 | 9 |  |  |

From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the line
stackchunks of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 to r3. A run is not a measurement when its console says it could not keep up
with its rate, its clients sent too late, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| default | mpsc | 67,076 | 1.13 | 0.88 | 59,280 | 140,858,969,364 | 124,488,884,253 | 2,100,000 |  |
| default | mpsc-unpinned | 74,494 | 1.14 | 0.88 | 65,314 | 156,437,264,392 | 137,159,717,943 | 2,100,000 |  |
| default | mpsc-no-affinity | 67,691 | 0.59 | 1.70 | 114,773 | 142,151,189,834 | 241,022,858,335 | 2,100,000 |  |
| default | mpsc-unpinned-no-affinity | 67,378 | 0.56 | 1.79 | 120,717 | 141,494,383,364 | 253,506,175,017 | 2,100,000 |  |
| default | jdk | 120,152 | 0.72 | 1.40 | 167,747 | 252,318,550,150 | 352,267,997,607 | 2,100,000 |  |
| default | jdk-pinned | 113,809 | 0.70 | 1.43 | 163,213 | 238,998,735,403 | 342,747,079,146 | 2,100,000 |  |
| big | mpsc | 63,269 | 0.27 | 3.66 | 231,753 | 132,865,927,317 | 486,680,870,158 | 2,100,000 |  |
| big | mpsc-unpinned | 59,718 | 0.25 | 3.94 | 235,509 | 125,408,835,659 | 494,568,228,153 | 2,100,000 |  |
| big | mpsc-no-affinity | 48,316 | 0.17 | 5.82 | 281,022 | 101,463,073,127 | 590,147,006,789 | 2,100,000 |  |
| big | mpsc-unpinned-no-affinity | 50,188 | 0.18 | 5.60 | 281,267 | 105,395,382,158 | 590,660,254,442 | 2,100,000 |  |
| big | jdk | 56,289 | 0.21 | 4.80 | 270,274 | 118,207,909,894 | 567,576,307,038 | 2,100,000 |  |
| big | jdk-pinned | 54,950 | 0.20 | 5.03 | 276,152 | 115,395,049,238 | 579,919,133,625 | 2,100,000 |  |

```
instructions/req       = instructions / requests
cycles/req             = cycles / requests
IPC                    = instructions / cycles
cycles per instruction = cycles / instructions = 1 / IPC
```

The stat run of each scheduler at depth 256. instructions and cycles: the counts of perf
stat, summed over the server's threads (every thread but client-*), in
runs/<case>-stat-<scheduler>.csv. requests: 150,000 req/s × 14 s, the window of perf stat.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf c2c

| case | scheduler | migrations | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |
|---|---|---|---|---|---|---|---|---|
| default | mpsc | 0.0% | 0.37% | 0.03% | 21,095 | 78 | 6 |  |
| default | mpsc-unpinned | 0.0% | 0.37% | 0.01% | 21,583 | 79 | 3 |  |
| default | mpsc-no-affinity | 61.4% | 3.02% | 0.16% | 33,490 | 1,010 | 55 |  |
| default | mpsc-unpinned-no-affinity | 59.3% | 2.32% | 0.23% | 36,870 | 854 | 85 |  |
| default | jdk | 61.0% | 2.64% | 0.18% | 42,228 | 1,114 | 75 |  |
| default | jdk-pinned | 60.5% | 1.64% | 0.25% | 44,746 | 735 | 110 |  |
| big | mpsc | 0.0% | 0.47% | 5.68% | 49,885 | 233 | 2,832 |  |
| big | mpsc-unpinned | 0.0% | 0.48% | 5.38% | 49,882 | 239 | 2,683 |  |
| big | mpsc-no-affinity | 6.1% | 0.61% | 6.01% | 53,155 | 323 | 3,192 |  |
| big | mpsc-unpinned-no-affinity | 6.2% | 0.69% | 7.35% | 50,293 | 347 | 3,695 |  |
| big | jdk | 3.5% | 0.64% | 7.16% | 51,637 | 329 | 3,699 |  |
| big | jdk-pinned | 4.7% | 0.74% | 5.30% | 61,498 | 453 | 3,257 |  |

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

migrations: the line migrations of the console of the c2c run, runs/<case>-c2c-<scheduler>-256.out.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## Walk

| case | scheduler | walk cycles/req | walk cycles | requests | note |
|---|---|---|---|---|---|
| default | mpsc | 15,210 | 31,940,841,157 | 2,100,000 |  |
| default | mpsc-unpinned | 15,678 | 32,924,452,547 | 2,100,000 |  |
| default | mpsc-no-affinity | 64,113 | 134,637,239,815 | 2,100,000 |  |
| default | mpsc-unpinned-no-affinity | 68,960 | 144,816,411,347 | 2,100,000 |  |
| default | jdk | 68,647 | 144,158,673,589 | 2,100,000 |  |
| default | jdk-pinned | 69,287 | 145,502,868,513 | 2,100,000 |  |
| big | mpsc | 192,285 | 403,798,944,850 | 2,100,000 |  |
| big | mpsc-unpinned | 181,597 | 381,353,672,756 | 2,100,000 |  |
| big | mpsc-no-affinity | 249,050 | 523,005,805,048 | 2,100,000 |  |
| big | mpsc-unpinned-no-affinity | 247,873 | 520,532,353,176 | 2,100,000 |  |
| big | jdk | 237,779 | 499,336,893,240 | 2,100,000 |  |
| big | jdk-pinned | 243,888 | 512,164,098,830 | 2,100,000 |  |

```
walk cycles/req = walk cycles / requests
```

The walk run of each scheduler, at depth 256: the same run, with UserGroupActor.walk kept out
of line (-XX:CompileCommand=dontinline), so that it has a name of its own. walk cycles: the
cycles that perf record gives to UserGroupActor.walk, the column Period of its report,
<case>-walk-<scheduler>.txt. It is only the walk: the scheduler and the kernel are not in it.
requests: 150,000 req/s × 14 s, the window of perf record.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## Epsilon

| case | scheduler | cpu µs/req G1 | cpu µs/req Epsilon | difference | heap used | note |
|---|---|---|---|---|---|---|
| default | mpsc | 14.60 | 14.99 | +0.39 | 7,473 MiB |  |
| default | mpsc-unpinned | 15.37 | 16.01 | +0.64 | 7,408 MiB |  |
| default | mpsc-no-affinity | 26.76 | 26.26 | -0.50 | 6,685 MiB |  |
| default | mpsc-unpinned-no-affinity | 27.57 | 27.39 | -0.18 | 6,721 MiB |  |
| default | jdk | 40.34 | 39.80 | -0.54 | 7,495 MiB |  |
| default | jdk-pinned | 39.81 | 39.88 | +0.07 | 8,059 MiB |  |
| big | mpsc | 53.85 | 57.85 | +4.00 | 6,028 MiB |  |
| big | mpsc-unpinned | 53.54 | 58.10 | +4.56 | 6,483 MiB |  |
| big | mpsc-no-affinity | 65.19 | 66.30 | +1.11 | 5,488 MiB |  |
| big | mpsc-unpinned-no-affinity | 64.77 | 66.26 | +1.49 | 5,459 MiB |  |
| big | jdk | 64.54 | not a measurement |  | 7,001 MiB | this run: not a measurement |
| big | jdk-pinned | 65.03 | 65.87 | +0.84 | 6,285 MiB |  |

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

The epsilon run of each scheduler, at depth 256: the same run with the Epsilon GC, which
frees nothing, so no GC runs. cpu µs/req G1: the mean of r1 to r3, as in In short. cpu µs/req Epsilon:
the line cpu of its console, runs/<case>-epsilon-<scheduler>-256.out. heap used: from its GC log
(.gc.log), the line of the heap at exit "space ... used [start, top, end)", top - start.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## The ramp

| case | scheduler | run | kept up with req/s | cpu µs/req | p99 µs | missed req/s | achieved req/s | p99 µs |
|---|---|---|---|---|---|---|---|---|
| default | mpsc | ramp1 | 1,070,000 | 9.33 | 699.9 | 1,090,000 | 1,077,875 | 35651.6 |
| default | mpsc | ramp2 | 1,090,000 | 9.16 | 1602.6 | 1,110,000 | 1,080,516 | 35782.7 |
| default | mpsc-unpinned | ramp1 | 890,000 | 11.11 | 243.6 | 910,000 | 908,961 | 14450.7 |
| default | mpsc-unpinned | ramp2 | 930,000 | 10.69 | 684.0 | 950,000 | 948,998 | 2965.5 |
| default | mpsc-no-affinity | ramp1 | 630,000 | 15.85 | 6705.2 | 650,000 | 640,827 | 27197.4 |
| default | mpsc-no-affinity | ramp2 | 630,000 | 15.85 | 4567.0 | 650,000 | 648,738 | 23117.8 |
| default | mpsc-unpinned-no-affinity | ramp1 | 610,000 | 16.36 | 4775.9 | 630,000 | 629,946 | 18087.9 |
| default | mpsc-unpinned-no-affinity | ramp2 | 630,000 | 15.85 | 12099.6 | 650,000 | 635,000 | 24543.2 |
| default | jdk | ramp1 | 670,000 | 14.88 | 5267.5 | 690,000 | 689,941 | 9412.6 |
| default | jdk | ramp2 | 690,000 | 14.47 | 7950.3 | 710,000 | 709,976 | 9740.3 |
| default | jdk-pinned | ramp1 | 670,000 | 14.91 | 7446.5 | 690,000 | 690,070 | 10182.7 |
| default | jdk-pinned | ramp2 | 690,000 | 14.48 | 7348.2 | 710,000 | 709,728 | 8208.4 |
| big | mpsc | ramp1 | 190,000 | 51.33 | 1702.9 | 210,000 | 200,351 | 144310.3 |
| big | mpsc | ramp2 | 190,000 | 49.79 | 911.4 | 210,000 | 205,539 | 197001.2 |
| big | mpsc-unpinned | ramp1 | 190,000 | 48.96 | 4767.7 | 210,000 | 193,220 | 210108.4 |
| big | mpsc-unpinned | ramp2 | 190,000 | 51.18 | 6369.3 | 210,000 | 198,896 | 168951.8 |
| big | mpsc-no-affinity | ramp1 | 190,000 | 52.16 | 38764.5 | 210,000 | 188,679 | 187170.8 |
| big | mpsc-no-affinity | ramp2 | 170,000 | 58.54 | 11255.8 | 190,000 | 188,112 | 116981.8 |
| big | mpsc-unpinned-no-affinity | ramp1 | 170,000 | 58.40 | 13983.7 | 190,000 | 189,200 | 105906.2 |
| big | mpsc-unpinned-no-affinity | ramp2 | 170,000 | 58.40 | 15605.8 | 190,000 | 189,664 | 66388.0 |
| big | jdk | ramp1 | 170,000 | 58.33 | 15818.8 | 190,000 | 190,089 | 88342.5 |
| big | jdk | ramp2 | 170,000 | 58.07 | 38961.2 | 190,000 | 190,328 | 50135.0 |
| big | jdk-pinned | ramp1 | 190,000 | 52.56 | 16629.8 | 210,000 | 198,376 | 149291.0 |
| big | jdk-pinned | ramp2 | 190,000 | 52.48 | 15269.9 | 210,000 | 193,983 | 171704.3 |

From the console of each ramp run, at depth 256, runs/<case>-ramp<n>-<scheduler>-256.out: from 150000 req/s,
20000 req/s more at each step, 10s measured, until a step is not kept up with.
Each step is one block of the console, from its line step. The console has every step.

- kept up with req/s: the last step kept up with, the line ramp kept up with. none: not even the first.
- cpu µs/req, p99 µs: at that step, the lines cpu and latency of its block.
- missed req/s: the next step, the first not kept up with: its block says it did not keep up, or that a GET got no answer.
  -: the ramp stopped at its last rate, with no step missed.
- achieved req/s, p99 µs: at that missed step, the lines throughput and latency of its block.
