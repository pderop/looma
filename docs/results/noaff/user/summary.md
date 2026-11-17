# User benchmark

```
date         2026-11-17 05:50:20 +0000
host         gabuzo-amd, Linux 7.0.0-43-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-noaff-33d7cd3)
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
- The schedulers: mpsc, jdk.
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
| mpsc | 29.86 | 830k/850k | 690k/690k | 64.97 | 150k/190k | none/none |
| jdk | 40.58 | 650k/690k | 510k/510k | 64.41 | 150k/150k | none/none |

cpu µs/req at depth 256, the mean of r1 and r2, as in In short.
max req/s: the highest rate kept up with by the ramp of each round at depth 256, r1/r2 ...: the
`ramp kept up with` line of its console, as in The ramp.
max req/s with p99 < 1 ms: a rate with an acceptable latency. In each ramp run, from the first step
up, the last step kept up with before the first step whose p99 is above 1 ms: the `latency`
lines of its console, step by step. none: the p99 of the first step is already above.

### The schedulers

- mpsc: the JDK's MPSC scheduler, **no carrier pinned** and **no affinity**: an actor can run
  on any carrier (`Carriers.mpsc(n)`).
- jdk: the JDK's default scheduler, **a ForkJoinPool** (`Carriers.builtin(n)`).

## In short

| case | scheduler | depth | cpu µs/req | cpu range | vs jdk | p50 µs | p99 µs | p99.9 µs | migrations | backend CPUs |
|---|---|---|---|---|---|---|---|---|---|---|
| small | mpsc | 256 | 29.86 | 29.77–29.94 | -26.4% | 37–38 | 85–87 | 139–149 | 43.9% | 0.86 |
| small | jdk | 256 | 40.58 | 40.49–40.67 |  | 39 | 87–91 | 968–2,482 | 63.5% | 0.82–0.83 |
| small | mpsc | 0 | 14.37 | 14.27–14.47 | -49.5% | 17 | 24–25 | 29–30 | 34.4% | 0.88–0.91 |
| small | jdk | 0 | 28.44 | 28.36–28.51 |  | 21–23 | 30 | 722–1,053 | 83.9% | 0.81 |
| big | mpsc | 256 | 64.97 | 64.70–65.23 | +0.9% | 299–366 | 2,018–3,426 | 10,977–59,015 | 4.2% | 0.79–0.80 |
| big | jdk | 256 | 64.41 | 64.20–64.63 |  | 797–1,029 | 20,890–33,587 | 66,847–100,139 | 3.8% | 0.72–0.76 |
| big | mpsc | 0 | 14.50 | 14.43–14.56 | -51.3% | 16–17 | 24 | 29 | 42.8% | 0.87–0.89 |
| big | jdk | 0 | 29.74 | 28.67–30.81 |  | 22 | 30–31 | 50–52 | 84.5% | 0.81–0.83 |

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
| small | mpsc | 256 | 150,002 | 5 | 21 → 21 |  |
| small | jdk | 256 | 150,003–150,004 | 8 | 27 → 26 |  |
| small | mpsc | 0 | 150,001 | 5 |  |  |
| small | jdk | 0 | 149,999–150,002 | 8–9 |  |  |
| big | mpsc | 256 | 149,996–150,015 | 7 | 47 → 52 |  |
| big | jdk | 256 | 149,935–150,043 | 7–8 | 149 → 38 |  |
| big | mpsc | 0 | 150,000–150,002 | 6–7 |  |  |
| big | jdk | 0 | 150,002 | 9–10 |  |  |

From the files of each run, in runs/. req/s: the `throughput` line of its console. GCs: the Pause
lines of its GC log, less the full GCs of the StackChunk counts. live StackChunks: the
`stackchunks` line of the console of the c2c run, during the warm-up → after the measure. req/s and
GCs: the lowest and the highest of r1 and r2. A run is not a measurement when its console says it could not keep up
with its rate, its clients sent too late, a GET failed, or a pinning failed.
Step by step: [Where the numbers come from](../../campaigns.md#where-the-numbers-come-from).

## perf stat

| case | scheduler | instructions/req | IPC | cycles per instruction | cycles/req | instructions | cycles | requests | note |
|---|---|---|---|---|---|---|---|---|---|
| small | mpsc | 71,274 | 0.58 | 1.72 | 122,368 | 149,675,485,545 | 256,971,901,472 | 2,100,000 |  |
| small | jdk | 125,190 | 0.74 | 1.35 | 168,410 | 262,899,678,278 | 353,660,865,449 | 2,100,000 |  |
| big | mpsc | 53,348 | 0.19 | 5.25 | 280,268 | 112,030,640,445 | 588,563,442,840 | 2,100,000 |  |
| big | jdk | 56,715 | 0.21 | 4.81 | 273,076 | 119,102,023,444 | 573,460,035,811 | 2,100,000 |  |

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
| small | mpsc | 43.8% | 2.85% | 0.14% | 37,421 | 1,065 | 52 |  |
| small | jdk | 62.7% | 2.42% | 0.33% | 43,142 | 1,046 | 142 |  |
| big | mpsc | 3.4% | 0.48% | 6.01% | 59,719 | 286 | 3,590 |  |
| big | jdk | 3.8% | 0.74% | 7.07% | 51,257 | 380 | 3,626 |  |

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
| small | mpsc | 68,788 | 144,454,574,608 | 2,100,000 |  |
| small | jdk | 67,458 | 141,661,659,509 | 2,100,000 |  |
| big | mpsc | 243,295 | 510,918,452,973 | 2,100,000 |  |
| big | jdk | 239,402 | 502,744,242,882 | 2,100,000 |  |

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
| small | mpsc | 29.86 | 29.93 | +0.07 | 5,417 MiB |  |
| small | jdk | 40.58 | 40.67 | +0.09 | 8,119 MiB |  |
| big | mpsc | 64.97 | 66.02 | +1.05 | 5,231 MiB |  |
| big | jdk | 64.41 | 65.46 | +1.05 | 6,705 MiB |  |

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
| small | mpsc | r1 | 830,000 | 12.01 | 1891.3 | 850,000 | 849,890 | 6189.1 |
| small | mpsc | r2 | 850,000 | 11.75 | 1810.4 | 870,000 | 869,757 | 7503.9 |
| small | jdk | r1 | 650,000 | 15.22 | 9093.1 | 670,000 | 669,927 | 15786.0 |
| small | jdk | r2 | 690,000 | 14.45 | 7852.0 | 710,000 | 708,863 | 15278.1 |
| big | mpsc | r1 | 150,000 | 65.08 | 4689.9 | 170,000 | 169,951 | 14802.9 |
| big | mpsc | r2 | 190,000 | 52.47 | 5472.3 | 210,000 | 195,004 | 204210.2 |
| big | jdk | r1 | 150,000 | 64.00 | 10420.2 | 170,000 | 169,624 | 90505.2 |
| big | jdk | r2 | 150,000 | 64.58 | 25100.3 | 170,000 | 169,866 | 35782.7 |

From the console of each ramp run, at depth 256, runs/<case>-r<n>-ramp-<scheduler>-256.out: from 150000 req/s,
20000 req/s more at each step, 10s measured, until a step is not kept up with.
Each step is one block of the console, from its `step` line. The console has every step.

- kept up with req/s: the last step kept up with, the `ramp kept up with` line. none: not even the first.
- cpu µs/req, p99 µs: at that step, the `cpu` and `latency` lines of its block.
- missed req/s: the next step, the first not kept up with: its block says it did not keep up, or that a GET got no answer.
  -: the ramp stopped at its last rate, with no step missed.
- achieved req/s, p99 µs: at that missed step, the `throughput` and `latency` lines of its block.
