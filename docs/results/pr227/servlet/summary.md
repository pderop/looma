# Servlet benchmark

```
date         2026-11-17 04:58:54 +0000
host         gabuzo-amd, Linux 7.0.0-43-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-pr227-5766f81)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
heap         2g, G1; the backend 1g
command      scripts/bench-servlet.sh
```

## The runs

What the benchmark is: [benchmark-servlet.md](../../../benchmark-servlet.md).

150000 req/s. 100000 sessions and 10000 products; a cart keeps up to 20 lines, a profile
up to 20 viewed products. Each request makes two backend calls: its fraud check, then its servlet's.
Each container has 8 backend connections, shared by its two servlets and its fraud detector: at most 80 backend calls at once. The backend is one JVM for all the runs, with 8 reactors,
warmed first by a throwaway JVM at 150000 req/s for 30 seconds (runs/servlet-backend-warmup-*.out).

Each scheduler ran 2 rounds. A round: a measure at 150000 req/s (r<n>), then a ramp (r<n>-ramp).
Round 1 also has, between the two: a run with perf c2c and one with perf stat.
In a round, each of these goes through every scheduler before the next one starts. Each run is
a JVM of its own: 10 seconds of warm-up, the rate going up from a tenth of 150000 req/s, then 20 seconds measured.

The ramp: after the warm-up, the rate starts at 150000 req/s and goes up by 20000 req/s at each step,
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
- Heap: the server -Xms2g -Xmx2g, the backend -Xms1g -Xmx1g, both -XX:+AlwaysPreTouch and G1. A GC
  log per JVM, in runs/.
- The c2c and stat runs count during the measure, minus 3 seconds at each end. The full report
  of each c2c run is in servlet-c2c-<scheduler>.txt, the files of every run in runs/.

## At a glance

| scheduler | servlet, cpu µs/req | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|
| mpsc | 41.11 | 290k/310k | 290k/290k |
| mpsc-affine | 33.03 | 390k/390k | 350k/350k |
| mpsc-pinned | 40.34 | 310k/310k | 310k/310k |
| mpsc-affine-pinned | 32.19 | 430k/430k | 410k/430k |
| jdk | 55.20 | 350k/350k | none/none |
| jdk-pinned | 56.07 | 350k/350k | 290k/290k |

cpu µs/req, the mean of r1 and r2, as in In short.
max req/s: the highest rate kept up with by the ramp of each round, r1/r2 ...: the
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

| scheduler | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations | backend CPUs |
|---|---|---|---|---|---|---|---|---|
| mpsc | 41.11 | 40.81–41.40 | -25.5% | 53–54 | 96–122 | 7,168–7,504 | 78.7% | 1.89–1.90 |
| mpsc-affine | 33.03 | 32.53–33.52 | -40.2% | 47–49 | 79–85 | 6,066–7,217 | 0.0% | 1.87–1.89 |
| mpsc-pinned | 40.34 | 40.23–40.46 | -26.9% | 52 | 78–79 | 5,886–6,124 | 79.5% | 1.90–1.92 |
| mpsc-affine-pinned | 32.19 | 32.05–32.32 | -41.7% | 47 | 77–78 | 5,419–5,480 | 0.0% | 1.85–1.87 |
| jdk | 55.20 | 54.44–55.96 |  | 66 | 1,997–2,148 | 6,701–7,020 | 69.6% | 1.81–1.84 |
| jdk-pinned | 56.07 | 54.61–57.53 | +1.6% | 64–66 | 115–117 | 5,878–6,660 | 72.6% | 1.82–1.83 |

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same rate, - 1
```

From the console of each run, runs/servlet-<run>-<scheduler>.out: cpu µs/req, the `cpu` line; latencies, the
`latency` line; migrations, the `migrations` line, the fraud detectors only; backend CPUs, the
`backend cpu` line: the CPU time of the backend process, read from /proc, divided by the length of
the measure. cpu µs/req and migrations: the mean of r1 and r2. cpu range, backend CPUs and
latencies: the lowest and the highest of r1 and r2. vs jdk is empty when jdk is not a measurement.
When a run is not a measurement, its numbers are not in this table: the mean is that of the
other runs, named in parentheses after it, as (r2). The column note of Controls says which run
was left out.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## Controls

| scheduler | req/s | GCs | note |
|---|---|---|---|
| mpsc | 149,951–149,960 | 16 |  |
| mpsc-affine | 149,946–149,973 | 16–17 |  |
| mpsc-pinned | 149,967–149,968 | 16 |  |
| mpsc-affine-pinned | 149,957–149,964 | 16–17 |  |
| jdk | 149,946–149,974 | 17 |  |
| jdk-pinned | 149,938–149,951 | 17 |  |

From the files of each run, in runs/. req/s: the `throughput` line of its console. GCs: the Pause
lines of its GC log. req/s and GCs: the lowest and the highest of r1 and r2. A run is not a measurement when its console
says it could not keep up with its rate, some requests got no 200, its clients sent too late,
or a pinning failed.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## perf stat

| scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|
| mpsc | 249,493 | 1.45 | 0.69 | 171,493 | 523,934,324,551 | 360,136,258,137 | 2,100,000 |  |
| mpsc-affine | 259,215 | 1.87 | 0.54 | 138,760 | 544,351,418,705 | 291,396,552,252 | 2,100,000 |  |
| mpsc-pinned | 262,865 | 1.62 | 0.62 | 162,699 | 552,017,446,518 | 341,668,002,454 | 2,100,000 |  |
| mpsc-affine-pinned | 250,949 | 1.89 | 0.53 | 132,841 | 526,993,407,528 | 278,966,670,985 | 2,100,000 |  |
| jdk | 288,664 | 1.25 | 0.80 | 230,955 | 606,193,668,384 | 485,005,478,041 | 2,100,000 |  |
| jdk-pinned | 274,520 | 1.22 | 0.82 | 224,154 | 576,491,234,758 | 470,722,391,627 | 2,100,000 |  |

```
instructions/req       = instructions / requests
cycles/req             = cycles / requests
IPC                    = instructions / cycles
cycles per instruction = cycles / instructions = 1 / IPC
```

The stat run of each scheduler. instructions and cycles: the counts of perf
stat, summed over the server's threads (every thread but client-*), in
runs/servlet-stat-<scheduler>.csv. requests: 150,000 req/s × 14 s, the window of perf stat.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## perf c2c

| scheduler | other L3 | DRAM | Load Operations | Load Remote HITM | Load DRAM | note |
|---|---|---|---|---|---|---|
| mpsc | 2.02% | 0.28% | 35,876 | 723 | 99 |  |
| mpsc-affine | 1.05% | 0.10% | 30,826 | 324 | 31 |  |
| mpsc-pinned | 1.88% | 0.21% | 34,941 | 658 | 74 |  |
| mpsc-affine-pinned | 1.13% | 0.11% | 31,668 | 359 | 34 |  |
| jdk | 1.83% | 0.18% | 43,342 | 792 | 76 |  |
| jdk-pinned | 2.06% | 0.23% | 44,184 | 912 | 102 |  |

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
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## The ramp

| scheduler | run | kept up with req/s | cpu µs/req | p99 µs | missed req/s | achieved req/s | p99 µs |
|---|---|---|---|---|---|---|---|
| mpsc | r1 | 290,000 | 34.37 | 262.1 | 310,000 | 308,046 | 43974.7 |
| mpsc | r2 | 310,000 | 32.13 | 22118.4 | 330,000 | 311,482 | 50298.9 |
| mpsc-affine | r1 | 390,000 | 25.48 | 9527.3 | 410,000 | 409,052 | 33308.7 |
| mpsc-affine | r2 | 390,000 | 25.56 | 423.2 | 410,000 | 409,790 | 18726.9 |
| mpsc-pinned | r1 | 310,000 | 32.17 | 590.3 | 330,000 | 313,907 | 48791.6 |
| mpsc-pinned | r2 | 310,000 | 32.17 | 945.7 | 330,000 | 313,831 | 56623.1 |
| mpsc-affine-pinned | r1 | 430,000 | 23.24 | 1387.5 | 450,000 | 423,764 | 95027.2 |
| mpsc-affine-pinned | r2 | 430,000 | 23.22 | 766.5 | 450,000 | 435,529 | 90112.0 |
| jdk | r1 | 350,000 | 28.55 | 10911.7 | 370,000 | 364,198 | 25264.1 |
| jdk | r2 | 350,000 | 28.55 | 8740.9 | 370,000 | 362,332 | 18726.9 |
| jdk-pinned | r1 | 350,000 | 28.54 | 4935.7 | 370,000 | 369,934 | 10682.4 |
| jdk-pinned | r2 | 350,000 | 28.55 | 4673.5 | 370,000 | 369,936 | 13156.4 |

From the console of each ramp run, runs/servlet-r<n>-ramp-<scheduler>.out: from 150000 req/s,
20000 req/s more at each step, 10s measured, until a step is not kept up with.
Each step is one block of the console, from its `step` line. The console has every step.

- kept up with req/s: the last step kept up with, the `ramp kept up with` line. none: not even the first.
- cpu µs/req, p99 µs: at that step, the `cpu` and `latency` lines of its block.
- missed req/s: the next step, the first not kept up with: its block says it did not keep up.
  -: the ramp stopped at its last rate, with no step missed.
- achieved req/s, p99 µs: at that missed step, the `throughput` and `latency` lines of its block.
