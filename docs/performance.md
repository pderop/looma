# HTTP cache-locality benchmark — results

One full run of `scripts/run-http-bench.sh` (2026-09-01). The protocol is described in the
[README](../README.md#the-http-cache-locality-benchmark); re-running the script reproduces
these tables from scratch.

**In one sentence:** when each connection's working set fits in the private L2 but not in
L1d, the carrier-affine scheduler serves **20 % more requests** than the JDK builtin and
spends **19.5 % fewer CPU cycles per request**.

## What is measured

A real HTTP/1.1 server, 96 connections driven by `wrk`. Each connection is one actor
pinned to a carrier, owning a private `int[]` of `stateKiB`. Every request walks that array,
makes a blocking call to a mock upstream, then walks the array again.

Nothing is shared between actors — no session map, no lock. So any cache-coherence traffic
the run produces was created by **the scheduler moving an actor off the core that dirtied
its lines**. That is the whole point of the benchmark.

`stateKiB` is swept over three values to place the working set on either side of the caches:

| `stateKiB` | per-carrier footprint | position | what it shows |
| --- | --- | --- | --- |
| 0 | nothing walked | — | dispatch cost alone |
| **64** | **1.5 MiB** | past 48 KiB L1d, inside 2 MiB L2 | **the locality effect** |
| 256 | 6 MiB | past L2 | effect must disappear |

Three configurations of the same binary: `carrier` (affine, no stealing), `stealing`
(affine + work stealing), `jdk` (builtin scheduler — the control).

## Setup

| | |
| --- | --- |
| Machine | i9-14900K, 8 P-cores, 48 KiB L1d + 2 MiB private L2 per core, 36 MiB shared L3 |
| Server | 8 carriers pinned to P-cores `0,2,…,14` |
| Client | `wrk -t 2 -c 96 -d 30s`, closed loop, on E-cores |
| Mock upstream | separate cores, **no think delay**, 671-byte response |
| JVM | `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`, identical everywhere |
| Counters | `perf stat` over a 26 s steady-state window |
| Machine state | turbo **off**, governor `performance` |

Only JVM flags differ between the three configurations.

## Results

`cyc/req` is the metric to read: it is a per-request cost, independent of the achieved rate.
Occupancy is how much of the 8-core budget the process actually used.

| run | rps | cyc/req | IPC | occupancy |
|---|---:|---:|---:|---:|
| carrier-0kib | 195 590 | **92 388** | 1.84 | 73.5 % |
| stealing-0kib | 202 837 | 102 160 | 1.69 | 84.8 % |
| jdk-0kib | 198 258 | 108 775 | 1.62 | 87.8 % |
| carrier-64kib | 123 697 | **176 951** | 1.24 | 87.4 % |
| stealing-64kib | **132 194** | 180 184 | 1.22 | 95.3 % |
| jdk-64kib | 103 221 | 219 686 | 0.94 | 89.2 % |
| carrier-256kib | 22 972 | 949 440 | 0.38 | 86.6 % |
| stealing-256kib | **28 003** | 884 086 | 0.42 | 98.0 % |
| jdk-256kib | 26 355 | 940 686 | 0.37 | 97.3 % |

**At the operating point (64 KiB)**, `carrier` serves 123 697 rps against the control's
103 221 (**+19.8 %**) while spending 176 951 cycles per request against 219 686
(**−19.5 %**, i.e. the control costs 24 % more).

## Work stealing

`stealing` wins throughput at **every** size: +2.3 % at 0 KiB, +28.1 % at 64 KiB, +6.3 % at
256 KiB over the control — and beats strict affinity by 6.9 % at the operating point.

It costs 1.8 % in cycles per request (180 184 vs 176 951) and buys 8 points of occupancy
(95.3 % vs 87.4 %). That trade pays on this workload at every size.

It also removes the single cell where strict affinity loses: at 256 KiB `carrier` drops
14.7 % below the control on throughput, purely because strict affinity leaves 11 points of
CPU idle with no locality left to buy. **That inversion is a property of affinity without a
release valve, not of the affine scheduler as such.**

**`stealing` is the configuration to run in production; `carrier` is the one that isolates
the effect for measurement.**

## What not to over-read

- **The 0 KiB throughput figures compare the harness, not the schedulers.** The three land
  within 2 % of each other despite an 18 % spread in cycles per request, and `carrier-0kib`
  sits at 73.5 % occupancy — the mock or the load generator ran out of headroom first. Their
  cycles/request figures remain valid. Raise `MOCK_THREADS` before quoting any throughput.
- **Latency is closed-loop** and therefore optimistic by construction. Never publish these
  as percentiles; re-run with a fixed `RATE=` for that.
- **One run per cell**, no variance estimate. Nothing here should be quoted finer than a few
  percent.
- **The operating point is under-sized**: each actor owns `state` *and* a same-sized
  `snapshot`, so 64 KiB means 1.5 MiB of a 2 MiB L2. The effect was found *despite* that,
  which understates it. Try `CONNS_PER_CARRIER=6` for a properly sized point.
- **The result holds for a fast upstream, and only there.** The mock answers immediately, so
  an actor returns to its array while its lines are still resident. The slower the upstream,
  the more traffic from the other connections flushes the private L2 in between — until both
  schedulers pay an L3 hit on resume and there is nothing left for affinity to preserve.
  Sweep `MOCK_THINK_MICROS` to find where the effect fades on your workload.
