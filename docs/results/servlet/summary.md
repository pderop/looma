# Servlet benchmark

```
date         2026-11-11 22:48:11 +0000
host         gabuzo-amd, Linux 7.0.0-42-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-pr227-5766f81)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
heap         2g, G1; the backend 1g
command      ./scripts/bench-servlet.sh 
```

## The runs

150000 req/s. 100000 sessions and 10000 products; a cart keeps up to 20 lines, a profile
up to 20 viewed products. Each request makes two backend calls: its fraud check, then its servlet's.
Each container has 8 backend connections, shared by its two servlets and its fraud detector: at most 80 backend calls at once. The backend is one JVM for all the runs, with 8 reactors,
warmed first by a throwaway JVM at 150000 req/s for 30 seconds (runs/servlet-backend-warmup-*.out).

Each scheduler ran 3 times (r1 to r3), then once more with perf c2c and once more with perf stat.
Each run: 10 seconds of warm-up, the rate going up from a tenth of 150000 req/s, then
20 seconds measured.

Last, 2 ramp runs per scheduler (ramp1 to ramp2), each in a JVM of its own.
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
- Heap: the server -Xms2g -Xmx2g, the backend -Xms1g -Xmx1g, both -XX:+AlwaysPreTouch and G1. A GC
  log per JVM, in runs/.
- The c2c and stat runs count during the measure, minus 3 seconds at each end. The full report
  of each c2c run is in servlet-c2c-<scheduler>.txt, the files of every run in runs/.

## At a glance

| scheduler | servlet, cpu µs/req | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|
| mpsc | 31.88 | 430,000 / 430,000 | 430,000 / 410,000 |
| mpsc-unpinned | 31.91 | 430,000 / 390,000 | 390,000 / 350,000 |
| mpsc-no-affinity | 39.58 | 310,000 / 310,000 | 310,000 / 310,000 |
| mpsc-unpinned-no-affinity | 40.23 | 310,000 / 310,000 | 310,000 / 290,000 |
| jdk | 54.48 | 350,000 / 330,000 | none / none |
| jdk-pinned | 54.15 | 370,000 / 330,000 | 290,000 / 290,000 |

cpu µs/req, the mean of r1 to r3, as in In short.
max req/s: the highest rate kept up with by each ramp run, ramp1 / ramp2 ...: the line
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

| scheduler | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | backend CPUs |
|---|---|---|---|---|---|---|---|
| mpsc | 31.88 | 31.70–32.20 | -41.5% | 46–47 | 75–79 | 4,735–5,648 | 1.82–1.83 |
| mpsc-unpinned | 31.91 | 31.79–32.01 | -41.4% | 46–47 | 74 | 4,678–5,095 | 1.84–1.85 |
| mpsc-no-affinity | 39.58 | 39.25–39.84 | -27.3% | 51–52 | 74–77 | 4,074–4,948 | 1.88–1.90 |
| mpsc-unpinned-no-affinity | 40.23 | 39.87–40.48 | -26.2% | 52 | 78–84 | 5,530–7,234 | 1.88 |
| jdk | 54.48 | 54.23–54.66 |  | 66–68 | 1,218–1,701 | 4,854–5,222 | 1.78–1.80 |
| jdk-pinned | 54.15 | 53.95–54.25 | -0.6% | 67–68 | 104–107 | 4,706–5,452 | 1.79–1.80 |

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same rate, - 1
```

From the console of each run, runs/servlet-<run>-<scheduler>.out: cpu µs/req, the line cpu; latencies, the line
latency; backend CPUs, the line backend cpu: the CPU time of the backend process, read from
/proc, divided by the length of the measure. cpu µs/req: the mean of r1 to r3. cpu range, backend CPUs and
latencies: the lowest and the highest of r1 to r3. vs jdk is empty when jdk is not a measurement.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## Controls

| scheduler | req/s | GCs | note |
|---|---|---|---|
| mpsc | 149,972–149,973 | 16 |  |
| mpsc-unpinned | 149,955–149,978 | 16 |  |
| mpsc-no-affinity | 149,969–149,977 | 16–17 |  |
| mpsc-unpinned-no-affinity | 149,974–149,978 | 16–17 | not a measurement (c2c run) |
| jdk | 149,973–149,978 | 17 |  |
| jdk-pinned | 149,961–149,974 | 17 |  |

From the files of each run, in runs/. req/s: the line throughput of its console. GCs: the Pause
lines of its GC log. req/s and GCs: the lowest and the highest of r1 to r3. A run is not a measurement when its console
says it could not keep up with its rate, some requests got no 200, its clients sent too late,
or a pinning failed.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf stat

| scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|
| mpsc | 246,143 | 1.89 | 0.53 | 130,089 | 516,900,686,796 | 273,186,198,845 | 2,100,000 |  |
| mpsc-unpinned | 247,954 | 1.85 | 0.54 | 133,948 | 520,703,401,598 | 281,290,242,633 | 2,100,000 |  |
| mpsc-no-affinity | 241,473 | 1.51 | 0.66 | 159,957 | 507,093,132,894 | 335,910,195,407 | 2,100,000 |  |
| mpsc-unpinned-no-affinity | 247,310 | 1.51 | 0.66 | 163,944 | 519,351,704,215 | 344,283,210,821 | 2,100,000 |  |
| jdk | 275,280 | 1.23 | 0.81 | 223,619 | 578,087,322,966 | 469,599,555,644 | 2,100,000 |  |
| jdk-pinned | 283,172 | 1.24 | 0.81 | 228,043 | 594,661,442,845 | 478,891,113,308 | 2,100,000 |  |

```
instructions/req       = instructions / requests
cycles/req             = cycles / requests
IPC                    = instructions / cycles
cycles per instruction = cycles / instructions = 1 / IPC
```

The stat run of each scheduler. instructions and cycles: the counts of perf
stat, summed over the server's threads (every thread but client-*), in
runs/servlet-stat-<scheduler>.csv. requests: 150,000 req/s × 14 s, the window of perf stat.
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## perf c2c

| scheduler | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |
|---|---|---|---|---|---|---|
| mpsc | 1.11% | 0.09% | 32,553 | 360 | 29 |  |
| mpsc-unpinned | 1.18% | 0.09% | 30,677 | 361 | 27 |  |
| mpsc-no-affinity | 2.05% | 0.28% | 35,378 | 724 | 98 |  |
| mpsc-unpinned-no-affinity | 1.89% | 0.19% | 33,701 | 638 | 63 | this run: not a measurement |
| jdk | 2.00% | 0.17% | 43,303 | 865 | 75 |  |
| jdk-pinned | 2.11% | 0.15% | 45,268 | 955 | 68 |  |

```
other L3 = Load Remote HITM / Load Operations
DRAM     = Load DRAM / Load Operations
```

The c2c run of each scheduler. Load Operations, Load Remote HITM and Load DRAM
are copied from its report, servlet-c2c-<scheduler>.txt, block Trace Event Information:

- Load Operations: the memory loads that perf sampled.
- Load Remote HITM: among them, the loads that took their line from behind another L3,
  because a core there had modified it.
- Load DRAM: among them, the loads served by the memory (Load Local DRAM + Load Remote DRAM).
Step by step: [Where the numbers come from](../campaigns.md#where-the-numbers-come-from).

## The ramp

| scheduler | run | kept up with req/s | cpu µs/req | p99 µs | missed req/s | achieved req/s | p99 µs |
|---|---|---|---|---|---|---|---|
| mpsc | ramp1 | 430,000 | 23.23 | 839.7 | 450,000 | 432,476 | 89194.5 |
| mpsc | ramp2 | 430,000 | 23.23 | 1269.8 | 450,000 | 428,853 | 90767.4 |
| mpsc-unpinned | ramp1 | 430,000 | 23.20 | 5578.8 | 450,000 | 424,187 | 91095.0 |
| mpsc-unpinned | ramp2 | 390,000 | 25.46 | 10829.8 | 410,000 | 408,624 | 33046.5 |
| mpsc-no-affinity | ramp1 | 310,000 | 32.18 | 999.9 | 330,000 | 312,632 | 48037.9 |
| mpsc-no-affinity | ramp2 | 310,000 | 32.17 | 518.4 | 330,000 | 315,042 | 47022.1 |
| mpsc-unpinned-no-affinity | ramp1 | 310,000 | 32.16 | 434.7 | 330,000 | 318,112 | 45449.2 |
| mpsc-unpinned-no-affinity | ramp2 | 310,000 | 32.17 | 1151.0 | 330,000 | 314,802 | 49741.8 |
| jdk | ramp1 | 350,000 | 28.55 | 5865.5 | 370,000 | 369,839 | 13402.1 |
| jdk | ramp2 | 330,000 | 30.18 | 4546.6 | 350,000 | 349,909 | 10928.1 |
| jdk-pinned | ramp1 | 370,000 | 27.00 | 8978.4 | 390,000 | 386,264 | 18956.3 |
| jdk-pinned | ramp2 | 330,000 | 30.27 | 2420.7 | 350,000 | 348,420 | 21135.4 |

From the console of each ramp run, runs/servlet-ramp<n>-<scheduler>.out: from 150000 req/s,
20000 req/s more at each step, 10s measured, until a step is not kept up with.
Each step is one block of the console, from its line step. The console has every step.

- kept up with req/s: the last step kept up with, the line ramp kept up with. none: not even the first.
- cpu µs/req, p99 µs: at that step, the lines cpu and latency of its block.
- missed req/s: the next step, the first not kept up with: its block says it did not keep up.
  -: the ramp stopped at its last rate, with no step missed.
- achieved req/s, p99 µs: at that missed step, the lines throughput and latency of its block.
