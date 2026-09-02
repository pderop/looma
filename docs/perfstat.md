# Reading `*.perfstat.txt`

Turning a run of `scripts/run-http-bench.sh` into a conclusion. Design: [`benchmark.md`](benchmark.md).
Results: [`performance.md`](performance.md).

## The two files

`perf stat` runs on the server pid for `DURATION-4` seconds inside the client's window, so the
counters are steady-state totals — and the request count is **not** in them.

```
jdk-64kib.perfstat.txt                          jdk-64kib.client.txt
─────────────────────────────────────────       ──────────────────────────────
     185562.49 msec task-clock  # 7.136 CPUs    3096743 requests in 30.00s
  589624584608 cpu_core/cycles/ # 3.177 GHz     Requests/sec: 103221.11
  554808248302 cpu_core/instructions/
   11172662906 cpu_core/mem_load_retired.l2_hit/
    1642231323 cpu_core/mem_load_retired.l3_hit/
    1644953805 cpu_core/longest_lat_cache.miss/
  26.001912928 seconds time elapsed
```

**The one rule: never compare raw counters.** They are totals, and two runs don't serve the same
number of requests. Divide everything by

```
req = Requests/sec x seconds time elapsed = 103221.11 x 26.0019 = 2 683 946
```

## The metrics

| | formula | what it says |
| --- | --- | --- |
| **cycles/req** | `cycles` ÷ `req` = **219 686** | The headline: cost of one request, immune to rate and frequency. |
| **IPC** | `instructions` ÷ `cycles` = **0.941** | Stalls. Under ~1 the core mostly waits; over ~1.5 it works. Needs no client file. |
| **instructions/req** | `instructions` ÷ `req` = **206 714** | How much *work* the code does. |
| **llc_miss/req** | `longest_lat_cache.miss` ÷ `req` = **613** | Trips to DRAM, ~200-300 cycles each. |
| **l3_hit/req**, **l2_hit/req** | ÷ `req` = **612**, **4163** | *Where* loads are served from. |
| **cpu_us/req** | `task-clock` × 1000 ÷ `req` = **69.1** | Same cost in CPU-time (`= cycles/req ÷ GHz`). |
| **occupancy** | `CPUs utilized` ÷ carriers (`env.txt`) = **89.2 %** | Under ~90 % the run was upstream-bound: its *rps* compares nothing. |

Ignore perf's own `#` rates (`2.990 G/sec`): they divide by CPU-time, not by work.

## Concluding

First: **do the clocks match?** (3.177 vs 3.125 GHz — yes.) If not, you are comparing frequencies.

Then two exact identities do the work:

```
(1)  cycles/req = (instructions/req) / IPC
(2)  rps        = (CPUs utilized x GHz) / (cycles/req)
```

**(2) splits a throughput win into its only three causes.** `stealing` vs `jdk` at 64 KiB:

```
rps x1.281 = cheaper work x1.219  x  occupancy x1.068  x  clock x0.983
```

So +28 % rps is ~22 % cheaper requests and ~7 % more cores used — quoting +28 % as a scheduler
win overstates it.

**(1) says where the cheapness came from**: fewer instructions, or better IPC. There is no third
term. Here instructions/req went *up* 6 % while cycles/req fell 18 % — so the gain is entirely
IPC (0.941 → 1.215): the core stalls less.

**Then locate the stall** in the memory rows:

- `llc_miss/req` flat (613 → 606) → **DRAM is not it**; same bytes touched. This row rules out.
- `l3_hit/req` −21 %, `l2_hit/req` +11 % → loads served one level closer. The locality story.

**Check the magnitude before believing it.** L3−L2 ≈ 30 cycles, so the ~129 loads/req that left
L3 are worth ~3 900 cycles/req — against **39 501** observed. Right direction, **10 %** of the
size. `mem_load_retired.l3_hit` counts *where* a load hit, not that the line was Modified in
another core's L2 (**HITM**, 70-90 cycles). `perf stat` stops here; `c2c-*.c2c.txt` closes it:

```
HITM per load:   carrier 2.96 %    stealing 3.22 %    jdk 11.48 %
```

> jdk moves an actor off the core that dirtied its lines → HITM 11.5 % vs 3.2 % → stalls →
> IPC 0.941 vs 1.215 → 219 686 vs 180 184 cycles/req → 103 221 vs 132 194 rps.

**From `*.perfstat.txt` alone, stop at "the stall is above DRAM."** The coherence link needs `c2c`.

## Reading `c2c-*.c2c.txt`

`perf c2c` **samples** loads slower than 30 cycles (`mem-loads,ldlat=30`) — it is a profile, not a
count. Absolute numbers are sample counts: compare **ratios and orderings**, never totals.

**The one number** is in the first block, `Trace Event Information`:

```
  Load Operations                   :     160635      <- sampled loads
  Load Local HITM                   :      18447      <- line was Modified in another core's cache
  Load Remote HITM                  :          0      <- ...on another socket (0 = single socket)
```

```
HITM/load = (Local + Remote HITM) / Load Operations

  carrier   5 098 / 172 284 =  2.96 %
  stealing  6 006 / 186 270 =  3.22 %
  jdk      18 447 / 160 635 = 11.48 %      <- 3.6x
```

A HITM is a load that had to snoop a dirty line out of another core (70-90 cycles). **Nothing is
shared between actors in this benchmark** — each owns a private array — so a HITM can only mean
the scheduler ran the actor on a core other than the one that dirtied its lines. That is the
whole hypothesis, measured directly. Expected order: `carrier < stealing < jdk`.

**Which lines**, from the `Shared Data Cache Line Table`:

```
# Index             Address  Node  PA cnt     Hitm    Total  LclHitm  RmtHitm  ...
      0      0x78b6a4115d80     0     231    0.33%       60       60        0
      1      0x78b6a4115e00     0     193    0.32%       59       59        0
```

Consecutive 64-byte addresses concentrating the HITM = one actor's array being chased across
cores. Drill in with `perf c2c report -i c2c-jdk.c2c.data -NN -d lcl --stdio` for the offsets,
pids and instruction addresses.

**Traps.** `RmtHitm` is 0 only because this is one socket — on a two-socket box it is the
expensive column. A run with few `Load Operations` samples has a noisy ratio. And `perf c2c` is
captured once, at the middle `stateKiB` point, not per family.

## The script

```bash
scripts/perfstat-report.py [dir] [--baseline=jdk] [--focus=CONFIG] [--lang=en|fr]
```

All of the above, per family, for a whole run directory (defaults to the newest one). Annotations
are derived from the numbers, not hard-coded, so they change when the story does.

## Traps

- **Raw totals, and perf's `#` rates.** Both look like performance and are not.
- **`CPUs utilized` is not a score** — more CPU is good only if throughput follows.
- **Don't mix counter families.** `mem_load_retired.*` = retired demand loads; `longest_lat_cache.miss`
  = core requests to the LLC. An "L3 hit rate" built from both is not a hit rate.
- **`cpu_core/` is P-cores only** on hybrid Intel. Fine here (server pinned to P-cores), silently
  wrong if unpinned.
- **Ratios shrink as the denominator grows** — at 256 KiB read cycles, not percent.
- **One run per cell**, and `req` is an estimate: nothing is meaningful below a few percent.
