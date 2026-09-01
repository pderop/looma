# Open question: should the actor loop yield on a count or on a duration?

**Status: analysis only, nothing changed.** `YIELD_EVERY = 64` is what ships
(`core/src/main/java/io/github/pderop/looma/impl/ActorCell.java`). This page records the arithmetic
and what would have to be measured before touching it.

The question came up because the vendored Netty scheduler bounds a drain batch by *time*
(`YIELD_DURATION_NS`, i.e. `io.netty.loom.yield.us`, default **50 microseconds**) while our actor
loop counts envelopes. Should ours become time-based too?

## The two mechanisms are not at the same layer

This is the main point, and it is why "do what the Netty scheduler does" cannot be taken literally.

**Netty's budget is in the carrier loop.** `EventLoopScheduler.drainContinuations` is called from
`virtualThreadSchedulerLoop`. The carrier drains continuations belonging to *many different* virtual
threads out of its run queue, and the budget bounds how long one batch lasts before the outer loop
gets to its other duties: `runPinnedContinuation()`, work stealing, park/unpark bookkeeping.
Crucially, **the budget preempts nothing**: `runContinuation(task)` runs to completion, and the
`elapsedNs >= deadlineNs` test happens only *between* continuations. Netty cannot interrupt a virtual
thread that is computing.

**Our counter is inside the virtual thread.** `ActorCell.runLoop` calls `Thread.yield()` from the
actor's own loop. That call is precisely what *creates* the continuation boundary Netty merely
observes. It is the only lever that exists against a CPU-bound actor, because virtual threads are not
preemptible.

So the two are **complementary, not alternatives**. Replacing our counter with Netty's model would
delete the only mechanism we have.

## The real question underneath

Restated without Netty: *should our yield trigger be a duration rather than a count?* Here the
instinct is right, and the arithmetic shows why. With `c` the cost per message:

| Actor | `c` | `YIELD_EVERY=64` → carrier hold | a 50 µs budget → messages per yield |
|---|---|---|---|
| trivial (routing) | ~200 ns | ~13 µs | ~250 |
| medium | ~5 µs | ~320 µs | ~10 |
| heavy (parse, crypto) | ~50 µs | **~3.2 ms** | 1 |

A count is a proxy for "some amount of CPU", and the variance of that proxy is enormous. The 3.2 ms
on the last row is a real latency problem for actors co-located on one carrier — and it is exactly
the backlog case where fairness matters most, since the loop only keeps draining while the mailbox
stays non-empty. At the other end we yield far too often for nothing: on the affine scheduler an
uncontended yield is an unmount plus a near-immediate re-enqueue, so pure overhead. A time budget
corrects both ends *in the right direction*.

## Why not to copy it verbatim

`System.nanoTime()` runs on the order of 20-25 ns depending on platform. Netty pays it once **per
continuation** — a unit that already costs several hundred ns in mount/unmount alone, so the relative
overhead is negligible. We would pay it once **per envelope**, a unit 10 to 50 times finer. On a
trivial actor that is ~10 % of the cost of the message itself.

That is where the analogy breaks: same mechanism, an order of magnitude difference in grain. The
current counter, by contrast, is a register increment and a perfectly predicted branch — free.

## The shape worth trying

A hybrid: keep the counter as a *sampler*, make time the *criterion*.

```java
if (++rounds >= CHECK_EVERY) {          // 8 or 16
    rounds = 0;
    long now = System.nanoTime();
    if (now - sliceStart >= YIELD_BUDGET_NS) {
        sliceStart = now;
        Thread.yield();
    }
}
```

This amortises the clock to ~1.5 ns per message and bounds the hold to
`budget + CHECK_EVERY × c`. At `CHECK_EVERY = 8` the heavy row's worst case falls from 3.2 ms to
~400 µs — not perfect, but a factor of 8 for almost nothing. `sliceStart` would reset on park,
exactly as `rounds` does today.

## Two reservations

1. **The worst case is reduced, not eliminated.** Removing it properly means reading the clock on
   every envelope, i.e. paying the 20 ns everywhere. That is a trade to settle with a number, not
   with an argument.
2. **Nothing currently indicates that 64 is a measured problem.** `scripts/run-http-bench.sh` is the
   harness: run the hybrid against the current build and watch **p99** (where the gain should show)
   as much as throughput (where the regression would). Latency percentiles need the open-loop form,
   `RATE=<req/s>` — see [`performance.md`](performance.md) for how a campaign is reported and
   [`machine-prep.md`](machine-prep.md) for getting signal instead of noise out of it.

## Scoping

All of this only bites when **several actors share a carrier**. Under the
[JDK builtin scheduler](jdk.md) the pool is shared, so it always applies. Under the
[carrier-affine scheduler](carrier.md) it depends on the actor-to-carrier ratio: with few actors per
carrier the expected gain is small and `YIELD_EVERY = 64` should be left alone.
