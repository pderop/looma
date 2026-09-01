# The HTTP cache-locality benchmark — method

Why the benchmark is built the way it is, and how to read a run. The short version, and how
to launch it, is in the [README](../README.md#the-http-cache-locality-benchmark). Measured
results are in [`performance.md`](performance.md).

## What the server does

Every connection is an actor owning a **private** byte array. Nothing is shared — no session
map, no lock, no common counter — so any coherence traffic a run produces was created by the
scheduler, not by the application. Each connection actor is a **root** spawned with an explicit
`Placement.carrier(n % carriers)`, so connection *n* lands deterministically on carrier
*n % carriers*.

```
        ┌──────────────── the actor, on its home carrier ────────────────┐
read ─► │ modify own array ─► copy it ──┐                               │
        └───────────────────────────────┼───────────────────────────────┘
                                        ▼  fork a virtual thread
                         read the snapshot ─► blocking GET to the mock
                                        │
        ┌───────────────────────────────┼───────────────────────────────┐
        │ ◄── message with body+checksum┘                               │
        │ modify own array again ─► fork a virtual thread ─────────────►│ write
        └───────────────────────────────────────────────────────────────┘
```

Four details are load-bearing:

- **The walk is a pointer chase.** Each cache line stores the index of the next line to visit — a
  single random cycle over every line, laid out once per connection. Written the obvious way,
  `array[random.nextInt(n)]`, the CPU knows a dozen addresses in advance and issues those loads in
  parallel, which hides exactly the per-line latency difference being measured. A dependent chain
  cannot be overlapped. The order is random rather than sequential for a second reason: a contiguous
  scan is what the L2 streamer prefetcher exists to hide. And every visit is a read-modify-**write**,
  so each line is left dirty and the next core must take it away rather than merely share it.
- **The pair of modifications straddles a park.** One alone measures nothing; the question is which
  core the *second* one runs on relative to the first.
- **What separates them is a real socket round trip, not a sleep.** A timed park resumes from the
  JDK's timer queue and never exercises the per-carrier sub-poller the locality argument rests on. A
  mock upstream therefore runs in its own process on its own cores; the server probes it at startup
  and refuses to bind without it.
- **The snapshot is what makes this race-free with no synchronisation at all.** The actor keeps
  mutating its own array while the forked thread reads a copy nobody else can see. The buffer is
  allocated once per connection and overwritten — a fresh array per request would turn this into an
  allocation benchmark, and the young collections would sweep the very lines whose residency is
  being measured.

## Why 0 / 64 / 256 KiB — an L2 experiment, not an L1 one

The P-core the defaults are sized for (i9-14900K, one thread per physical core, HT sibling idle):

| Where the line sits | Size on this part | Typical hit | Shared with |
| --- | --- | --- | --- |
| L1d | **48 KiB** | ~5 cycles | nobody |
| L2 | **2 MiB**, private | ~15 cycles | nobody |
| L3 | 36 MiB | ~40-50 cycles | every core on the die |
| HITM (Modified in another core's L2) | — | ~70-90 cycles | the core that dirtied it |

A 64 KiB array is already larger than L1d. And while one connection is parked on the mock, the
other 11 connections of that carrier walk *their* arrays through the same 48 KiB. **No scheduler
can keep this working set in L1d.** What affinity can preserve is L2: 12 connections ×
(64 KiB of `state` + 64 KiB of `snapshot`) = **1.5 MiB**, which still fits in the 2 MiB private L2.
Coming back to the same core is then an L2 hit; landing on another core is a HITM against a line
that never needed to move. That is the operating point, and that is why the chase exists: the
difference being measured is ~15 vs ~80 cycles *per line*, and only a dependent, random, dirtying
walk actually pays it.

**Size against `connsPerCarrier × 2 × stateKiB`**, never `stateKiB` alone: every connection actor
owns its `state` array *and* a same-sized `snapshot` buffer. The defaults leave little headroom
(1.5 MiB of 2 MiB); a looser point (`CONNS_PER_CARRIER=6`, or `STATE_KIB_POINTS="0 32 256"`) would
be expected to show a larger locality term still.

## How the result can be falsified

Three devices exist to attack the answer rather than confirm it.

- **The three-point argument.** If the gap at `256` does not fall back onto the `0` result, cache
  residency was never what separated the schedulers at the operating point.
- **The predicted slope.** A migration costs one HITM per dirty line, so the `jdk`-minus-`carrier`
  **cycles/request delta** should grow roughly linearly in `stateKiB`. Read that criterion in
  cycles, not as a ratio: a ratio collapses mechanically as the denominator grows, whether or not
  the advantage changed.
- **The wait-to-work ratio.** Per-connection concurrency is 1 (HTTP/1.1 answers in order), so a
  request costs `think + cpu` and only `cpu` depends on the scheduler. The largest ratio two
  schedulers can ever show in *throughput* is `(think + cpu_slow) / (think + cpu_fast)`, never
  `cpu_slow / cpu_fast`. At a 1 ms think time against ~50 µs of work, a scheduler costing twice the
  cycles surfaces as ~5 % in rps — a real difference, made invisible by arithmetic alone.

The server binary is identical in all nine runs; only JVM flags differ, and the driver asserts the
scheduler class out of the server log after bind — a missing `--enable-preview` would otherwise
produce a full set of plausible numbers under the wrong label.

### Why `MOCK_THINK_MICROS` defaults to 0

Every microsecond of upstream think time dilutes the only term the scheduler controls. At `0` the
mock answers as soon as it has read the request, the wait collapses to the loopback round trip the
server has to pay anyway, and the difference is as visible in rps as it can be. What `0` does *not*
remove is the reason the mock exists — the server still makes a real blocking socket call, so its
continuation still parks on the per-carrier sub-poller rather than on the JDK's timer queue. Raise
it only to model a genuinely slow backend, and then read cycles/request rather than rps.

Two things `0` changes, worth knowing before reading a run. The mock **stops spinning**: its reactor
only busy-polls inside the last millisecond before a response is due, and with nothing pending it
blocks in `select()` instead. And the mock is **much closer to being the bottleneck**, since the
server now asks for responses as fast as they can be produced — so check the client's rps against
the concurrency ceiling (`connections / upstreamRoundTrip`, which nothing prints) at *every* point,
and raise `MOCK_THREADS` before concluding anything from a run whose rps sits on it.

## Sizing the cpusets for your machine

**The driver's defaults only make sense on an i9-14900K**, and the reasoning matters more than the
numbers. Read the real topology first:

```sh
lscpu -e     # cores, HT siblings, P vs E
lscpu -C     # cache levels, shared vs private
```

On a 14900K, `lscpu -e` shows CPUs 0-15 as 8 P-cores **with Hyper-Threading** (each `CORE` value
appears twice — CPU 0 and CPU 1 are the same physical core), and CPUs 16-31 as 16 E-cores. Two
logical CPUs of one core share the execution units, the 48 KiB L1d **and the 2 MiB L2**.

That is why `SERVER_CPUS` takes **one CPU per physical core** and never both siblings:

```sh
SERVER_CPUS=0,2,4,6,8,10,12,14    # 8 carriers, one per physical P-core, 2 MiB private L2 each
CLIENT_CPUS=16-27                 # 12 E-cores for the load generator
MOCK_CPUS=28-31                   # the 4th E-core L2 cluster, for the mock upstream
```

Two carriers on CPUs 0 and 1 would share one 2 MiB L2, halving the budget the sizing assumes. This
is not hypothetical: the topology maps carrier *i* to the *i*-th CPU **of the affinity mask**, so an
unrestricted run pairs carriers on HT siblings *first*. Always pass an explicit mask.

Three processes, three **disjoint** cpusets. The mock gets its own so that it can never preempt a
carrier — the run would then measure that instead.

Two consequences of this particular CPU, worth knowing before over-reading any ratio:

- 8 carriers × 1.5 MiB is 12 MiB against a **36 MiB L3**, so at the operating point a migration
  never costs a DRAM round trip: the contrast is an L2 hit against an L3/HITM snoop. Expect
  single-digit percent to low multiples, not 10×.
- `lscpu -e` reports L3 id `0` for every CPU — one L3 for the whole chip — so `CLUSTER_LOCAL`
  permits exactly the same steals as `GLOBAL`, and steal *scoping* is not measurable on this part.

**On another CPU, recompute all three cpusets and both state sizes from `lscpu`.** Nothing in the
driver reads the topology. Machine preparation (frequency, `perf` permissions, HT siblings offline)
is a separate checklist: [`machine-prep.md`](machine-prep.md).

## The load generator

`wrk` and `wrk2` through `jbang`. `RATE` unset gives **`wrk`, closed loop** — one request in flight
per connection, the next sent when the response lands, which is what measures a saturation
throughput. `RATE=<req/s>` gives **`wrk2 -R --latency`, open loop** — the client sends at that rate
whatever the server does, and queues client-side when it cannot keep up.

That distinction is not cosmetic. In a closed loop a server stall makes the client *stop sending*
during exactly the bad window, so the requests that would have suffered are never issued and never
appear in the histogram. That is **coordinated omission**. Rule: **publish throughput from the
closed-loop run, and latency only from an open-loop run** at a rate below the throughput the closed
loop found.

## Reading a run

**The headline metric is cycles per request** — `cpu_core/cycles/` from `*.perfstat.txt` divided by
the request count in `*.client.txt` — not raw rps, because it absorbs frequency drift. Then, in
order:

1. **Check saturation before reading any rps at all** (`task-clock` ÷ measured seconds ÷ carriers).
   Below roughly 90 %, the carriers were idling on the backend and that run compares nothing.
2. **The `0` KiB control must be flat.** Whatever gap survives there is dispatch cost, not locality.
3. **The `256` KiB point must fall back onto the `0` KiB point**, in cycles/request delta.
4. **The gap should scale with `stateKiB`** between those two ends.
5. **`perf c2c` should order the three configurations**: `carrier` lowest HITM, `stealing` above it,
   `jdk` at the top. This is the qualitative evidence behind the quantitative one.
6. **Check the concurrency ceiling** against the throughput obtained: throughput cannot exceed
   `connections / upstreamRoundTrip`. Sitting on it means the run measured the client and the mock,
   not the schedulers.

Every knob is an environment variable: `SERVER_CPUS`, `CLIENT_CPUS`, `MOCK_CPUS`,
`MOCK_THINK_MICROS`, `STATE_KIB_POINTS`, `CONNS_PER_CARRIER`, `DURATION`, `WARMUP`, `RATE`, `HEAP`,
`FAST`. An explicit `DURATION`/`WARMUP` wins over `FAST=1`. `WRK=wrk WRK2=wrk2` uses native binaries
instead of jbang.

Everything lands in `target/http-bench/<timestamp>/`: `*.client.txt` (with the exact command in
`*.client.cmd`), `*.perfstat.txt`, `*.c2c.txt`, `env.txt`, and each server's own log.
