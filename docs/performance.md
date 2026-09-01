# HTTP cache-locality campaign — 2026-09-01

Numbers from one full (non-`FAST`) run of the HTTP cache-locality benchmark
(`scripts/run-http-bench.sh`). Raw output in `target/http-bench/20260901-130357/`. Nine
measured runs (3 schedulers × 3 per-connection state sizes) plus three `perf c2c` captures.
The protocol and the three-point argument are in the
[README](../README.md#the-http-cache-locality-benchmark); this page is what that run
produced. The rest of this section is the minimum needed to read the tables without
flipping over.

## What was on the machine

A real HTTP/1.1 server (`examples/…/http`), driven by `wrk` — 96 connections, closed loop,
one request in flight per connection. Every connection is one `ConnectionActor`, spawned as
a root on carrier `n % 8`. Its only mutable state is private: a `state` `int[]` sized by
the knob **`stateKiB`**, plus a same-sized `snapshot` buffer allocated once and overwritten
every request. Nothing is shared between actors — no session map, no lock — so any
coherence traffic the run produces was created by the scheduler moving the actor off the
core that dirtied those lines.

One request:

1. the actor walks `state` (`ConnectionActor.modifyState`);
2. copies it into `snapshot` and forks a virtual thread from
   `context.vThreadFactory()`;
3. that thread walks the copy and makes a **blocking GET** to a mock upstream on its own
   cores — a real socket park, not a sleep;
4. the answer comes back as a message; the actor walks `state` again, then writes the
   HTTP response.

The walk is a **pointer chase**: each 64-byte line of the array stores the index of the
next line; the loop cannot issue the next load until the current one returns; every visit
is a read-modify-write. That is the opposite of a sequential scan (the L2 streamer would
prefetch it) and of independent random indexing (the CPU would keep a dozen loads in
flight). Either would hide the latency this bench exists to measure — ~15 cycles for an L2
hit vs ~80 for a HITM. More in
[Pointer chase, L1d, L2](#pointer-chase-l1d-l2--what-the-three-sizes-mean).

The pair of modifications straddles the park. That is the whole measurement: which core
holds those lines when the actor resumes.

**`64 KiB` in the tables is that per-connection `state` array**, not a request body and not
the heap. At 12 connections per carrier both `state` and `snapshot` count, so the carrier's
footprint is `12 × 2 × stateKiB`:

| `stateKiB` | one `ConnectionActor` | per carrier (12 conns) | against this P-core |
| --- | --- | --- | --- |
| 0 | empty arrays — the chase is skipped | nothing walked | dispatch cost only |
| 64 | 64 KiB `state` + 64 KiB `snapshot` | **1.5 MiB** | past 48 KiB L1d, inside 2 MiB private L2 |
| 256 | 256 + 256 | 6 MiB | past L2 |

Three JVM-flag sets, identical binary: `carrier` (affine, no steal), `stealing` (affine +
steal), `jdk` (builtin — the control).

**The mock had no think delay.** `MOCK_THINK_MICROS=0` — recorded in `env.txt` as
`think 0us` and in `mock.log` as `thinkMicros=0`. The mock answers as soon as it has read
the request; the wait in the middle of each request is only the loopback socket round trip.
That is the campaign that makes the locality term visible: a non-zero think time dilutes
the only term the scheduler controls, and flushes the private L2 before the actor comes
back to its own lines. See
[why this campaign found what 2026-08-30 could not](#why-this-campaign-found-what-2026-08-30-could-not).

**The result in one paragraph.** At `stateKiB=64` — a working set that **does not fit in
L1d** (48 KiB) and **does fit in the private L2** (1.5 MiB of 2 MiB) — the affine scheduler
spends **24 % fewer cycles per request** than the JDK builtin (176 951 vs 219 686). That
extra cost of the control is only 16 387 cycles when nothing is touched (`stateKiB=0`:
dispatch, not locality) and is gone at 256 KiB (past L2: nobody is resident). The growth of
the gap, 26 348 cycles/request, is the locality term. A second derivation — cheaper cycles
per cache line walked — gives 26 419, 0.3 % apart. `perf c2c` orders the three
configurations the same way.

## What was run

| | |
| --- | --- |
| Machine | i9-14900K (`gabuzo2`, kernel 6.8.0-138), 8 P-cores + 16 E-cores, **48 KiB L1d** / 2 MiB **private** L2 per P-core, one 36 MiB L3 |
| Server cpuset | `0,2,4,6,8,10,12,14` — one thread per physical P-core, 8 carriers |
| Client cpuset | `16-27` (E-cores), `wrk -t 2 -c 96 -d 30s`, closed loop |
| Mock upstream | cpuset `28-31`, **`thinkMicros=0`**, 2 threads, 671-byte response, started once for the whole campaign |
| Workload | 8 carriers × 12 connections = 96 actors, one upstream call per request, per-connection private array of `stateKiB` |
| JVM | `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`, identical on all three sides |
| Counters | `perf stat` over a 26.0 s steady-state window inside each 30 s run |
| Machine state | turbo **off** (`no_turbo=1`), governor `performance`, `perf_event_paranoid=1` — recorded in `env.txt` |

Only JVM flags differ between the three configurations. The driver's scheduler assertion held on
every run: 8 carrier-affine servers and 4 JDK-builtin ones, per the `scheduler=` lines.

## Pointer chase, L1d, L2 — what the three sizes mean

The walk is not `array[rng()]` and not a sequential scan. Each 64-byte line of the connection's
`int[]` stores the index of the next line; the loop cannot issue the next load until the current one
returns; the permutation is random; every visit is a read-modify-write. That is a **pointer chase**.
Independent random indexing would let the CPU keep a dozen loads in flight and hide the latency
being measured. A contiguous scan is what the L2 streamer prefetcher exists to hide. Either would
turn ~15 vs ~80 cycles per line into a rounding error. The loop is `ConnectionActor.modifyState`;
the layout is `layOutChase`.

On this P-core the caches are:

| | this part | typical hit |
| --- | --- | --- |
| L1d | **48 KiB**, private | ~5 cycles |
| L2 | **2 MiB**, private (HT sibling idle) | ~15 cycles |
| L3 | 36 MiB, shared by the whole die | ~40-50 cycles |
| HITM | Modified in another core's L2 | ~70-90 cycles |

**64 KiB does not fit in L1d.** One connection's array is already larger than 48 KiB, and while it
is parked the other 11 connections of that carrier walk theirs through the same L1d. No scheduler
can keep this working set in L1d. What affinity *can* preserve is L2: 12 connections × (64 KiB of
`state` + 64 KiB of `snapshot`) = **1.5 MiB**, which still fits in the 2 MiB private L2. Coming back
to the same core is then an L2 hit; landing on another core is a HITM. That is the operating point.

| `stateKiB` | per-carrier footprint | what it isolates |
| --- | --- | --- |
| 0 | nothing walked | dispatch cost only |
| 64 | 1.5 MiB — past L1d, inside L2 | the locality term |
| 256 | 6 MiB — past L2 | the gap must collapse |

A request walks *n* lines three times (`modifyState`, `readSnapshot`, `modifyState`): 3 072 line
visits at 64 KiB, 12 288 at 256 KiB. The per-line costs derived below (27.5 vs 36.1 cycles at 64 KiB;
~65–70 at 256 KiB) sit where that picture says they should: L2-ish when the working set fits, a
deeper miss when it does not.

## Headline table

`cyc/req` = `cpu_core/cycles/` ÷ (rps × 26.0 s). This is the metric to read — see
[why the rps column compares less than it looks](#why-the-rps-column-compares-less-than-it-looks),
which applies to this campaign more than to the previous one.

| run | rps | avg lat | cyc/req | ins/req | IPC | GHz | occupancy | L2 hit/req | L3 hit/req | LLC miss/req |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| carrier-0kib | 195 590 | 0.50 ms | **92 388** | 170 387 | 1.84 | 3.07 | 73.5 % | 1 199 | 45 | 536 |
| stealing-0kib | 202 837 | 0.42 ms | 102 160 | 172 481 | 1.69 | 3.05 | 84.8 % | 1 188 | 73 | 567 |
| jdk-0kib | 198 258 | 0.36 ms | 108 775 | 176 724 | 1.62 | 3.07 | 87.8 % | 1 059 | 136 | 580 |
| carrier-64kib | 123 697 | 0.88 ms | **176 951** | 219 845 | 1.24 | 3.13 | 87.4 % | 4 712 | 470 | 613 |
| stealing-64kib | **132 194** | 0.78 ms | 180 184 | 218 993 | 1.22 | 3.13 | 95.3 % | 4 620 | 483 | 606 |
| jdk-64kib | 103 221 | 0.94 ms | 219 686 | 206 714 | 0.94 | 3.18 | 89.2 % | 4 163 | 612 | 613 |
| carrier-256kib | 22 972 | 4.20 ms | 949 440 | 360 431 | 0.38 | 3.15 | 86.6 % | 13 565 | 3 023 | 7 245 |
| stealing-256kib | **28 003** | 3.46 ms | 884 086 | 371 401 | 0.42 | 3.16 | 98.0 % | 13 695 | 3 110 | 6 435 |
| jdk-256kib | 26 355 | 3.65 ms | 940 686 | 347 456 | 0.37 | 3.19 | 97.3 % | 12 953 | 2 719 | 6 462 |

Occupancy is `task-clock` ÷ (26.0 s × 8 carriers): how much of the eight-core budget the process
actually used. Latencies come from a closed loop and are optimistic by construction (coordinated
omission) — listed for shape only, **never to be published as percentiles**.

Ratios against the `jdk` control:

| stateKiB | carrier/jdk rps | stealing/jdk rps | jdk/carrier cyc/req | jdk/stealing cyc/req |
|---:|---:|---:|---:|---:|
| 0 | 0.987 | 1.023 | 1.177 | 1.065 |
| 64 | **1.198** | **1.281** | **1.242** | 1.219 |
| 256 | 0.872 | 1.063 | 0.991 | 1.064 |

## The result: a locality term, isolated two independent ways

The criterion is the **absolute cycles/request delta**, not the ratio — a ratio collapses
mechanically as the denominator grows, whether or not the advantage changed.

| stateKiB | jdk − carrier | jdk − stealing |
|---:|---:|---:|
| 0 | 16 387 cyc/req | 6 616 |
| 64 | **42 735 cyc/req** | 39 501 |
| 256 | −8 753 cyc/req | 56 600 |

Read against what the README requires:

1. **The `0` KiB control is a small constant.** Nothing is touched, so the 16 387 cycles/request the
   affine scheduler saves there is pure dispatch cost — cheaper wakeup, no cross-carrier handoff.
2. **The gap grows at the operating point.** 16 387 → 42 735, i.e. **+26 348 cycles/request** that
   exist only when there is state to keep resident. That state does not fit in L1d (48 KiB); it does
   fit in the private L2 (1.5 MiB of 2 MiB). That growth is the locality term, and it is what the
   previous campaign failed to find.
3. **The gap collapses past the private L2.** At 256 KiB it is −8 753 — zero within the noise of a
   949 k-cycle request. Nothing is resident for anybody, so no scheduler can separate itself. Had it
   *not* collapsed, the gap at 64 KiB would not have been caused by cache residency and the whole
   result would be void.

Now the independent check. Decompose cycles/request into the fixed cost measured at 0 KiB plus a cost
per cache line touched — a request walks *n* lines three times (`modifyState`, `readSnapshot`,
`modifyState`), so 3 072 lines at 64 KiB and 12 288 at 256 KiB:

| | fixed (0 KiB) | 64 KiB memory work | **cyc/line** | 256 KiB memory work | **cyc/line** |
|---|---:|---:|---:|---:|---:|
| carrier | 92 388 | 84 563 | **27.5** | 857 052 | 69.7 |
| stealing | 102 160 | 78 025 | **25.4** | 781 927 | 63.6 |
| jdk | 108 775 | 110 910 | **36.1** | 831 911 | 67.7 |

At the operating point the affine scheduler genuinely buys a **cheaper cache line**: 27.5 cycles
against 36.1, a difference of 8.6 cycles per line. Over 3 072 lines that is **26 419
cycles/request** — against the 26 348 measured as the growth of the delta between 0 and 64 KiB.
**Two derivations from different columns of the same table, agreeing to 0.3 %.**

Past the L2 the per-line cost converges on all three (69.7 / 63.6 / 67.7, a 9 % spread) at roughly
2.5× the operating-point figure. That is the L2 crossing: everyone pays the level below at the same
tariff.

## `perf c2c`: the qualitative half, and it orders correctly

Captured at the middle point, `stateKiB=64`, 10 s each. Raw counts, then normalised per million
requests since the three runs served different amounts of traffic:

| | carrier | stealing | jdk |
|---|---:|---:|---:|
| Total shared cache lines | 3 130 | 3 835 | **16 047** |
| Load Local HITM | 5 098 | 6 006 | **18 447** |
| Load HITs on shared lines | 17 374 | 20 753 | 34 133 |
| rps during the capture | 110 031 | 118 065 | 94 187 |
| **shared lines / Mreq** | **2 845** | 3 248 | **17 037** |
| **HITM / Mreq** | **4 633** | 5 087 | **19 585** |

The three-way ordering the README predicts — `carrier` lowest, `stealing` just above it, `jdk` far
above — holds exactly. Normalised, the JDK builtin touches **6.0× the shared cache lines** and takes
**4.2× the HITM events** of the affine scheduler. Remote HITM is 0 throughout: single socket, as
expected.

This is the same qualitative signal the 2026-08-30 campaign showed. What is new is that it now
**converts into cycles**.

## Why the rps column compares less than it looks

No configuration saturates the server, and one row is worse than the others:

- **At `stateKiB=0` the three land within 2 % of each other** (195.6 k / 202.8 k / 198.3 k) *despite
  differing by 18 % in cycles per request*. `carrier-0kib` sits at **73.5 % occupancy** — a quarter
  of the machine idle — and still cannot go faster than the control. That is the signature of a
  limit outside the server under test: the mock (2 reactor threads) or the load generator (2 `wrk`
  threads). **The 0 KiB rps figures therefore compare the harness, not the schedulers.** Their
  cycles/request figures remain valid, a per-request cost not depending on the rate.
- **At 64 KiB, occupancy is 87.4 % (carrier) and 89.2 % (jdk)** — just under the harness's own 90 %
  rule of thumb. The 1.198× rps advantage is indicative; the 1.242× cycles/request advantage is the
  result.
- **At 256 KiB** the occupancy gap is the whole story: carrier 86.6 % against jdk 97.3 %.

Raising `MOCK_THREADS` is the first thing to try before the next campaign: at `thinkMicros=0` the
server asks for responses as fast as the mock can produce them, and the 0 KiB row says the mock or
the client ran out of headroom first.

## The 256 KiB inversion, and what is left of it

At 256 KiB the control beats `carrier` on throughput — 26 355 vs 22 972 rps, +14.7 %. It is again the
only cell where the affine scheduler loses, but the reason has changed since the previous campaign:
there, `carrier` was still 5.4 % cheaper per request and lost purely on utilization. Here the two are
**level on efficiency** (949 440 vs 940 686 cycles, a 0.9 % edge to the control) and `carrier` loses
on utilization on top of that.

That is exactly what the decomposition predicts. Past the private L2 there is no locality left to
buy, so the affine scheduler's constant dispatch advantage is all it has — and against a 949 k-cycle
request, 16 k cycles is 1.7 %, less than the 11 points of idle CPU that strict affinity costs.

Turning on work stealing removes the handicap without giving back the efficiency:
`stealing-256kib` runs the machine at 98.0 % and wins the size outright, +6.3 % over the control and
+21.9 % over `carrier`. **The inversion is a property of strict affinity without a release valve, not
of the affine scheduler as such.**

## What work stealing costs here

`stealing` wins rps at every size: +2.3 % at 0 KiB, +28.1 % at 64 KiB, +6.3 % at 256 KiB over the
control — and at 64 KiB it beats strict affinity as well, by 6.9 %.

It is not free in efficiency. At the operating point it costs **180 184** cycles/request against
`carrier`'s **176 951** — 1.8 % more — while running the machine at 95.3 % instead of 87.4 %. Its
`perf c2c` numbers sit between the other two, exactly where the locality-for-balance trade predicts.
Stealing buys occupancy with a small, measurable amount of locality, and on this workload the trade
pays at every size.

The one figure not to over-read is stealing's 25.4 cyc/line at 64 KiB, lower than `carrier`'s 27.5.
The decomposition subtracts each configuration's own 0 KiB baseline, and stealing's baseline comes
from the harness-limited row above — so that column carries the 0 KiB uncertainty. Nothing should be
concluded from stealing beating strict affinity on per-line cost.

## Why this campaign found what 2026-08-30 could not

The previous campaign ran at `MOCK_THINK_MICROS=100` and concluded there was **no measurable locality
term**: the `jdk − carrier` delta *shrank* between 0 and 64 KiB (64 307 → 54 466) instead of growing,
and the JDK builtin was cheaper per touched line than the affine scheduler (25.1 vs 28.3). Here the
same two measurements move the other way — the delta grows (16 387 → 42 735) and the affine scheduler
is cheaper per line (27.5 vs 36.1).

The mechanism is **reuse distance**, which is what the think time really controls. Between two
touches of one connection's array, the other 11 connections of that carrier run. At a 100 µs think
time each of those requests also waits, so far more traffic passes through the 2 MiB private L2
before the actor comes back to its own lines: they have already been evicted to the shared L3, and
the migration then costs an L3 hit for **both** schedulers. There is nothing left for affinity to
preserve. At `thinkMicros=0` the actor returns to its array almost immediately, the lines are still
resident, and only then does *which core* holds them matter.

Two caveats on that explanation. It compares two campaigns that differ in more than one respect, so
it is a strong reading rather than a controlled experiment — a run at `MOCK_THINK_MICROS=100` against
today's build would settle it. And the earlier campaign reported the JDK control executing 2.1× the
instructions per request at 0 KiB (355 785 vs 172 362); here the three configurations are within 4 %
of each other on instructions, and the whole difference is in **IPC** (0.94 vs 1.24 at the operating
point) — i.e. in stalls, which is what a locality argument predicts and what an instruction-count
difference would have confounded.

## Known harness limitations

Both of the standing ones reproduce in this campaign.

1. **The startup affinity line is unreliable under work stealing — measurement artifact, not
   mispinning.** All three `carrier` runs print a clean `0->cpu0 1->cpu2 … 7->cpu14`. All three
   `stealing` runs print a collision:

   ```
   stealing-0kib     ... 0->cpu12 1->cpu12   (cpu0 missing)
   stealing-64kib    ... 0->cpu4  2->cpu4    (cpu0 missing)
   stealing-256kib   ... 4->cpu10 5->cpu10   (cpu8 missing)
   ```

   `LinuxCarrierTopology.carrierToCore` is a fixed table, so the pinning itself cannot collide.
   `HttpBenchMain.reportCarrierPinning` probes by starting one virtual thread per carrier factory and
   calling `pinnedCpu()` from inside it; at startup every carrier is idle, so those probe tasks are
   prime steal targets and a stolen probe reports the **thief's** CPU. That the artifact appears in
   exactly the three work-stealing runs and in no others confirms the diagnosis. Fixing it means
   reading the affinity from the carrier thread itself rather than from a stealable task.

2. **The per-carrier footprint is `connsPerCarrier × 2 × stateKiB`**, not `connsPerCarrier ×
   stateKiB` — every `ConnectionActor` owns `state` **and** a same-sized `snapshot` buffer. At the
   defaults the 64 KiB operating point is therefore **1.5 MiB of a 2 MiB private L2**, not 768 KiB.

   This campaign found the locality term *despite* that tight sizing, which strengthens the result
   rather than weakening it: a properly sized operating point should show a larger term still. Try
   `CONNS_PER_CARRIER=6`, or `STATE_KIB_POINTS="0 32 256"` — 768 KiB per carrier either way.

3. **`perf c2c` still records `cpu_atom/mem-loads` alongside `cpu_core/mem-loads`.** The server is
   `taskset`-ed to P-cores, so the atom rows should be empty and the totals above are effectively
   `cpu_core` — but the event set should be restricted explicitly
   (`-e cpu_core/mem-loads,ldlat=30/P`) before these HITM figures are published again.

## Conclusions

1. **The locality term is real and worth about 26 000 cycles/request** at the 64 KiB operating point
   — 8.6 cycles per cache line touched — measured twice, independently, agreeing to 0.3 %. That point
   is past L1d and inside L2; it is not an L1 result.
2. **The constant dispatch term is about 16 000 cycles/request**, measured with no state touched at
   all.
3. **At the operating point the affine scheduler costs 24 % fewer cycles per request** than the JDK
   builtin (176 951 vs 219 686) and delivers 20 % more throughput, at 87 % occupancy against 89 %.
4. **`perf c2c` corroborates it and orders the three configurations as predicted**: normalised per
   request, the JDK builtin touches 6.0× the shared cache lines and takes 4.2× the HITM events.
5. **The effect vanishes past the private L2**, as it must for the 64 KiB result to mean what it
   claims. At 256 KiB the three configurations are within 1 % on cycles/request.
6. **Work stealing wins throughput at every size** and costs 1.8 % in cycles/request at the operating
   point. It is the configuration to lead with; strict affinity is the one that isolates the effect.
7. **The mock had no think delay.** The 0 KiB row is harness-limited and its rps must not be compared.
   Raise `MOCK_THREADS` and re-run before quoting any throughput figure.

## Caveats

- Single campaign, one run per cell — no run-to-run variance estimate. Nothing above should be quoted
  at a precision finer than a few percent without a repeat. The 0.3 % agreement between the two
  derivations of the locality term is an internal consistency check, not a precision claim.
- Closed loop: the latency column is optimistic by construction. Re-run with `RATE=` below the
  measured throughput for percentiles that mean anything.
- The operating point is under-sized (limitation 2) and the harness is the limit at 0 KiB. Both push
  in the direction of *understating* the locality term.
