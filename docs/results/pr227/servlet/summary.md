# Servlet benchmark

```
date         2026-11-22 03:30:52 +0000
host         gabuzo-amd, Linux 7.0.0-43-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-pr227-5766f81)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
heap         2g, G1; the backend 1g
command      scripts/bench-servlet.sh --containers 10 --rate 150000 --step 20000 --out bench/servlet-pr227-20261122-013751
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
- The schedulers: mpsc, mpsc-affine, mpsc-affine-pinned, jdk.
- The clients are pinned to their own CPUs.
- The JDK's poller mode is not set. On Linux, the JDK then uses mode 4 under the MPSC scheduler (one epoll per carrier), and mode 2 under jdk (pollers that run as virtual threads).
- Heap: the server -Xms2g -Xmx2g, the backend -Xms1g -Xmx1g, both -XX:+AlwaysPreTouch and G1. A GC
  log per JVM, in runs/.
- The c2c and stat runs count during the measure, minus 3 seconds at each end. The full report
  of each c2c run is in servlet-c2c-<scheduler>.txt, the files of every run in runs/.

## At a glance

| scheduler | servlet, cpu µs/req | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|
| mpsc | 40.12 | 310k/310k | 290k/310k |
| mpsc-affine | 31.81 | 390k/370k | 350k/350k |
| mpsc-affine-pinned | 31.70 | 430k/450k | 430k/410k |
| jdk | 54.61 | 350k/330k | none/150k |

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
- mpsc-affine-pinned: both, **each actor stays on its carrier** and **each carrier is pinned to one CPU** (`Carriers.mpsc(n).affine(true).pinned(true)`).
- jdk: the JDK's default scheduler, **a ForkJoinPool** (`Carriers.builtin(n)`).

## In short

| scheduler | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations | backend CPUs |
|---|---|---|---|---|---|---|---|---|
| mpsc | 40.12 | 39.91–40.34 | -26.5% | 52 | 78–80 | 5,398–5,468 | 79.7% | 1.89–1.90 |
| mpsc-affine | 31.81 | 31.65–31.98 | -41.7% | 46 | 74 | 4,661–4,911 | 0.0% | 1.84–1.87 |
| mpsc-affine-pinned | 31.70 | 31.20–32.20 | -41.9% | 46–47 | 72–76 | 4,465–4,633 | 0.0% | 1.82–1.83 |
| jdk | 54.61 | 54.37–54.84 |  | 62–66 | 1,252–1,338 | 4,936–5,280 | 70.3% | 1.80–1.81 |

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
| mpsc | 149,952–149,974 | 16 |  |
| mpsc-affine | 149,971–149,975 | 16 |  |
| mpsc-affine-pinned | 149,968–149,969 | 16 |  |
| jdk | 149,976–149,978 | 17 |  |

From the files of each run, in runs/. req/s: the `throughput` line of its console. GCs: the Pause
lines of its GC log. req/s and GCs: the lowest and the highest of r1 and r2. A run is not a measurement when its console
says it could not keep up with its rate, some requests got no 200, its clients sent too late,
or a pinning failed.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## perf stat

| scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|
| mpsc | 258,288 | 1.57 | 0.64 | 164,802 | 542,405,426,132 | 346,084,778,281 | 2,100,000 |  |
| mpsc-affine | 257,702 | 1.93 | 0.52 | 133,674 | 541,173,655,343 | 280,715,462,047 | 2,100,000 |  |
| mpsc-affine-pinned | 262,745 | 1.98 | 0.50 | 132,530 | 551,765,015,113 | 278,312,647,307 | 2,100,000 |  |
| jdk | 288,068 | 1.25 | 0.80 | 229,721 | 604,943,258,558 | 482,414,241,038 | 2,100,000 |  |

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
| mpsc | 1.90% | 0.17% | 35,175 | 667 | 60 |  |
| mpsc-affine | 1.04% | 0.08% | 31,852 | 332 | 26 |  |
| mpsc-affine-pinned | 1.09% | 0.09% | 30,956 | 337 | 29 |  |
| jdk | 1.94% | 0.18% | 43,176 | 839 | 78 |  |

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
| mpsc | r1 | 310,000 | 32.16 | 1058.8 | 330,000 | 312,922 | 54558.7 |
| mpsc | r2 | 310,000 | 32.16 | 791.0 | 330,000 | 316,214 | 49152.0 |
| mpsc-affine | r1 | 390,000 | 25.36 | 15671.3 | 410,000 | 409,876 | 4362.2 |
| mpsc-affine | r2 | 370,000 | 26.77 | 4370.4 | 390,000 | 389,271 | 27672.6 |
| mpsc-affine-pinned | r1 | 430,000 | 23.23 | 671.2 | 450,000 | 437,213 | 89260.0 |
| mpsc-affine-pinned | r2 | 450,000 | 22.20 | 1743.9 | 470,000 | 440,915 | 90112.0 |
| jdk | r1 | 350,000 | 28.52 | 5566.5 | 370,000 | 369,938 | 9592.8 |
| jdk | r2 | 330,000 | 30.23 | 4159.5 | 350,000 | 349,660 | 9764.9 |

From the console of each ramp run, runs/servlet-r<n>-ramp-<scheduler>.out: from 150000 req/s,
20000 req/s more at each step, 10s measured, until a step is not kept up with.
Each step is one block of the console, from its `step` line. The console has every step.

- kept up with req/s: the last step kept up with, the `ramp kept up with` line. none: not even the first.
- cpu µs/req, p99 µs: at that step, the `cpu` and `latency` lines of its block.
- missed req/s: the next step, the first not kept up with: its block says it did not keep up.
  -: the ramp stopped at its last rate, with no step missed.
- achieved req/s, p99 µs: at that missed step, the `throughput` and `latency` lines of its block.
