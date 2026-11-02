# The schedulers

One actor engine, two schedulers. The scheduler picks the carrier of each virtual thread, so of
each actor's [mailbox thread](api.md#2-writing-an-actor).

| In code | What it is | Benchmark name | Carriers pinned | An actor moves | An idle carrier steals |
| --- | --- | --- | --- | --- | --- |
| `Carriers.affine(n)` | the JDK's MPSC scheduler, from [openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227) | `mpsc` | yes, one CPU each (Linux) | never | offloaded threads only |
| `Carriers.builtin(n)` | the JDK's default scheduler, a `ForkJoinPool` | `jdk` | no | may, each time a mailbox thread starts | anything |

Offloaded threads are those an actor starts through `ActorContext.vThreadFactory()`. Neither
scheduler has options.

## Choosing one

### In code

```java
ActorSystem system = ActorSystem.builder()
        .carriers(Carriers.affine(8))       // or Carriers.builtin(8)
        .build();
```

`build()` sets the JDK properties, then checks that they took:

| `Carriers` | Properties it sets |
| --- | --- |
| `affine(n)` | `jdk.virtualThreadScheduler.useMpsc=true`, `jdk.virtualThreadScheduler.mpscSteal=true`, `jdk.virtualThreadScheduler.parallelism=n`, and `java.util.concurrent.ForkJoinPool.common.threadFactory` unless already set: see [Threads started from an actor](../mpsc-scheduler/README.md#threads-started-from-an-actor) |
| `builtin(n)` | `jdk.virtualThreadScheduler.parallelism=n` |

The JDK reads them once. So `build()` must run **before the JVM's first virtual thread**. It
throws `IllegalStateException` when:

- a virtual thread already ran;
- a property it sets already holds another value, from a `-D` flag;
- the command line already chooses a scheduler, with `-Djdk.virtualThreadScheduler.implClass` or
  `-Djdk.virtualThreadScheduler.useMpsc`;
- no provider: no `looma-mpsc-scheduler` on the classpath, or a JDK without the MPSC scheduler;
- `-Djdk.virtualThreadScheduler.maxPoolSize` is below `n`;
- an earlier system in this JVM uses another pool (the same pool is fine);
- an earlier `affine(n)` failed after the JDK started the scheduler: it fails again, with the
  first reason.

Not detected: `builtin(n)` after a virtual thread ran. The JDK keeps the size it already chose.

`affine(n)` pins through native calls, so `--enable-native-access=ALL-UNNAMED` stays on the
command line. Without it, the JVM warns. With `--illegal-native-access=deny`, nothing is pinned,
and Looma says so.

### As JVM flags

Without `carriers(...)`, the flags of the table above choose the scheduler, and `build()` finds
it. With the MPSC flags, actors still never move, but the carriers are not pinned: only
`affine(n)` pins them. Looma reads no system property of its own.

### On the classpath

`affine` needs `looma-mpsc-scheduler` (built locally, like `looma-core`). Without it, discovery
falls back to the builtin, and `carriers(Carriers.affine(n))` throws. A provider that fails to link
(compiled for a newer JDK) is skipped.

```xml
<dependency>
    <groupId>io.github.pderop</groupId>
    <artifactId>looma-mpsc-scheduler</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## `affine`: the JDK's MPSC scheduler

From the JDK of openjdk/loom pull request 227, a draft. Each carrier, `mpsc-carrier-<id>`, has its
own queues.

- An actor's mailbox threads have its carrier's `Thread.Affinity`: never stolen.
- Offloaded threads have none: they start on the actor's carrier, and an idle carrier may take them.
- Each carrier is pinned to one CPU, after the JDK's pollers and timers started.
- On Linux each carrier has its own epoll for reads.

```
carrier-0
  1. run the virtual threads of its queues, for one round
  2. epoll_wait(0): sockets ready?  -> unpark their virtual threads -> back to their queue
  3. nothing to do?  -> try to steal an offloaded thread from another carrier
                        still nothing -> epoll_wait(-1): sleep in its epoll
```

The two queues, the pollers, the threads the JDK starts and the install, step by step:
[`mpsc-scheduler/README.md`](../mpsc-scheduler/README.md).

## `builtin`: the JDK's default scheduler

- Mailbox threads and offloaded threads: plain `Thread.ofVirtual()`.
- Chosen by `carriers(Carriers.builtin(n))`, or when no flag chooses another scheduler. Refused when
  `useMpsc` is set.
- `carrierCount()` is 1: every actor's home carrier is 0, and `Placement.carrier(i)` with `i > 0`
  throws at spawn.

Placement, by default and by key: [`api.md`](api.md#9-placement).

## How a tell runs

The engine is in `io.github.pderop.looma.impl`, once, for both schedulers:

- **`ActorRuntime`** — what one system shares: the path registry, the roots, in-flight accounting,
  shutdown and termination, the queue factory.
- **`ActorCell`** — one actor: mailbox, mailbox threads, hierarchy, lifecycle hooks,
  supervision. It implements `ActorContext`.
- **`ActorRefImpl`** — the reference to a live cell; **`PromiseRef`** — the single-use reply
  channel an `ask` passes as `sender`; **`PoolRef`** — a round-robin fan-out over a pool's actors,
  with no mailbox of its own.
- **`Envelope`** — one mailbox entry, a message or a lifecycle step.
- **`ActorSchedulers`** — discovery, and `carriers(...)`'s installation.

```
tell
  1. mailbox.offer(envelope)
  2. pending.getAndIncrement()   0 -> 1 ?  start a mailbox thread, from the carrier's mailboxThreadFactory
mailbox thread
  runs one envelope per count, then ends
```

```
do {
  if (state != STOPPED) {
    envelope = mailbox.poll();
    if (envelope != null) { runEnvelope; interrupted(); }
  }
} while (pending.decrementAndGet() > 0);
if (TERMINATING && !cascadeStarted && pending == 0) tryFinishDraining();
```

- One mailbox thread at a time: only the `tell` from 0 starts one. An actor's fields need no lock.
- Idle actor: no virtual thread, no stack.
- A mailbox thread never yields. An actor whose mailbox never empties keeps its carrier; the others
  on it wait. Fairness is a non-goal.
- Offer, then count: a counted envelope is always there. A `tell` while the mailbox thread runs
  only counts.
- The count starts at 1, held by `start()`: a `tell` before `start()` waits for it. `preStart` is
  the first envelope, `runFinalStop` the last. A restart keeps the mailbox thread and the queue,
  with a new actor instance.

`shutdown()` and `awaitTermination` are in [`api.md`](api.md#10-stopping). The carriers are
JVM-wide daemon threads: no scheduler's `close()` stops anything.

### The wheel thread

Looma starts one more thread per carrier for its [timers](api.md#5-timers): a virtual thread of
the carrier, named `looma-timer-<carrier>`.

```
a carrier gets its first timer       (delayed or periodic tell)
  -> Looma starts the wheel thread on that carrier, from mailboxThreadFactory(carrier)
     (under `builtin` it is a plain virtual thread, and the system has one wheel)
  -> each round it drains the inbox and the cancelled queue, then fires the timers that are due
  -> it parks when the wheel is empty, and wakes at each tick while the wheel holds a timer
shutdown()
  -> it cancels every pending timer, soon after, then ends; awaitTermination does not wait for it
```

On an MPSC carrier an actor's mailbox thread never leaves its carrier, so it may touch the wheel
of its carrier directly. It marks itself when it starts:

```
the mailbox thread starts on carrier-3
  -> the wheel of carrier-3 runs ?   no  -> one volatile read; its timers go through the queues,
                                            until it ends
                                     yes -> it marks itself local to the wheel of carrier-3
an arm or a cancel on the wheel of carrier-3
  -> the caller is the thread that marked itself ?   yes -> the direct path
                                                     no  -> the queues
     (a thread forked in a StructuredTaskScope inherits the mark, but is not that thread)
```

**A delayed tell to an actor of carrier-3:**

```
ref.tell(msg, 50 ms)
  -> from the marked mailbox thread of carrier-3:
       the timer goes straight into the wheel of carrier-3, no queue
  -> from any other thread:
       the timer goes to the inbox of the wheel of carrier-3, the home carrier of ref
       the wheel thread of carrier-3 moves it into its slot
  -> at the deadline the wheel thread calls ref.tell(msg): local to carrier-3
```

**A cancel of that timer:**

```
future.cancel(false)
  -> from the marked mailbox thread of carrier-3:
       the timer leaves the wheel at once
  -> from any other thread: the message is freed at once, then
       the timer is still in the inbox  -> the wheel thread drops it when it drains the inbox
       the timer is in the wheel        -> it goes to the cancelled queue,
                                           the wheel thread removes it at the next tick
```

A tell to a pool has no single carrier: the marked mailbox thread uses the wheel of its carrier,
any other caller a wheel chosen from its thread id, always a wheel of the same system.

Under `builtin` no thread counts as local: every arm and every cancel goes through the queues. The
switch is `mailboxThreadsStayOnCarrier()` (below).

## The SPI

```java
public interface ActorScheduler extends AutoCloseable {
    int carrierCount();                          // builtin: 1 | affine: one per carrier
    ThreadFactory vThreadFactory(int i);         // the threads an actor offloads
    default ThreadFactory mailboxThreadFactory(int i) { return vThreadFactory(i); }   // its mailbox threads
    default boolean mailboxThreadsStayOnCarrier() { return false; }   // affine: true; they never move
    int currentCarrierId();                      // the calling thread's carrier; builtin: -1
    OptionalInt pinnedCpu();
    OptionalInt clusterOf(int i);                // builtin: empty
    default ThreadFactory platformThreadFactory() { return Thread.ofPlatform().factory(); }
    void close();
    default boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException { ... }
}
```

- `mailboxThreadsStayOnCarrier()`: true only if every thread from `mailboxThreadFactory(i)` runs on
  carrier i alone, one at a time, never stolen. Looma starts its wheel thread from that factory
  too, and then arms and cancels timers on that carrier without a queue.
- `ActorSchedulerProvider`: creates one, `isAvailable()`, can install itself for `Carriers.Affine`.
- A third-party scheduler: implement both, register the provider in
  `META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider`.
- `ActorRuntime` wraps every factory, so each thread counts as in-flight work.
- **The MPSC queues**: `spi.MpscQueueFactory`, set with `ActorSystem.Builder.mpscQueueFactory(...)`.
  Default: `ConcurrentLinkedQueue`. At least MPSC, no limit, no blocking `poll`, and an `offer` and an
  `isEmpty` that order as volatile accesses do.

## Why carrier affinity

An unparked virtual thread may run **anywhere**. An actor is its state, and it starts a new
mailbox thread at every `tell` after an empty mailbox: each one is a chance to move.

```
    ┌── ForkJoinPool worker 0 ─── CPU 0 ───────────────┐
    │  actor A drains its mailbox, runs onReceive      │
    │  A's state is now in CPU 0's L1d / L2,           │
    │  and dirty there                                 │
    └──────────────────────────────────────────────────┘

         mailbox empty → A's mailbox thread ends. Every actor, all the time.
         a `tell` starts a new one → it goes to the pool
                                │
                                ▼   any idle worker may take it

    ┌── ForkJoinPool worker 5 ─── CPU 5 ───────────────┐
    │  actor A resumes here                            │
    │  its lines are not in CPU 5's caches: each one   │
    │  comes back from L3, or from CPU 0 if dirty      │
    └──────────────────────────────────────────────────┘
```

With a home carrier pinned to a CPU, the state stays on one core, and the children too. Offloaded
work may be stolen: it reads none of the actor's state.

Affinity keeps the **L2**, not the L1. The [benchmark](benchmark.md) measures it with two sizes of
state: one that fits a core's L2, one that does not.
