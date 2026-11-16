# The schedulers

One actor engine, two JDK schedulers: the MPSC scheduler, in four configs, and the JDK's default
one. The scheduler picks the carrier of each virtual thread, so of each actor's
[mailbox thread](api.md#2-writing-an-actor).

| In code | Benchmark name | Carriers pinned | An actor moves | An idle carrier steals |
| --- | --- | --- | --- | --- |
| `Carriers.mpsc(n)` | `mpsc` | no | may, see below | anything |
| `Carriers.mpsc(n).affine(true)` | `mpsc-affine` | no | never | offloaded threads only |
| `Carriers.mpsc(n).pinned(true)` | `mpsc-pinned` | yes, one CPU each (Linux) | may | anything |
| `Carriers.mpsc(n).affine(true).pinned(true)` | `mpsc-affine-pinned` | yes, one CPU each (Linux) | never | offloaded threads only |
| `Carriers.builtin(n)` | `jdk` | no | may, each time a mailbox thread starts | anything |

The benchmarks call each config a scheduler, and add a sixth, `jdk-pinned`
([the benchmarks](benchmark.md#the-schedulers)).

- `mpsc`: the MPSC scheduler of [openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227).
  `affine(true)` and `pinned(true)` need its `Thread.Affinity`: on a JDK without it, only
  `Carriers.mpsc(n)` is accepted.
- `builtin`: the JDK's default scheduler, a `ForkJoinPool`.
- Offloaded threads: the threads an actor starts through `ActorContext.vThreadFactory()`.
- How an actor moves, under `mpsc` (and `mpsc-pinned`, the same way):
  - on `pr227`, each new mailbox thread starts on the carrier of its sender;
  - on `noaff`, the same thread is woken on the carrier it last ran on, or on its waker's
    ([keep-alive](#the-keep-alive));
  - either way, an idle carrier may take it.

## Choosing one

### In code

```java
ActorSystem system = ActorSystem.builder()
        .carriers(Carriers.mpsc(8))         // or Carriers.builtin(8)
        .build();
```

`build()` sets the JDK properties, then checks that they took:

| `Carriers` | Properties it sets |
| --- | --- |
| `mpsc(n)`, with any `pinned` and `affine` | `jdk.virtualThreadScheduler.useMpsc=true`, `jdk.virtualThreadScheduler.mpscSteal=true`, `jdk.virtualThreadScheduler.parallelism=n`, and `java.util.concurrent.ForkJoinPool.common.threadFactory` unless already set: see [Installing the MPSC scheduler](mpsc-install.md) |
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
- an earlier `mpsc(n)` failed after the JDK started the scheduler: it fails again, with the
  first reason.

Not detected: `builtin(n)` after a virtual thread ran. The JDK keeps the size it already chose.

Keep `--enable-native-access=ALL-UNNAMED` on the command line with `mpsc(n)`: the install reads the
CPUs of the process through a native call, and `pinned(true)` pins through another. Without the
flag, the JVM warns. With `--illegal-native-access=deny`, nothing is pinned, and Looma says so.

### As JVM flags

Without `carriers(...)`, the flags of the table above choose the scheduler, and `build()` finds it.
With the MPSC flags, actors stay on their carrier, as with `mpsc(n).affine(true)`, on a JDK with
`Thread.Affinity`; on a JDK without it, they have no affinity. The carriers are not pinned: only
`pinned(true)` pins them. Looma reads no system property of its own.

### On the classpath

`mpsc` needs `looma-mpsc-scheduler` (built locally, like `looma-core`). Without it, discovery
falls back to the builtin, and `carriers(Carriers.mpsc(n))` throws. A provider that fails to link
(compiled for a newer JDK) is skipped.

```xml
<dependency>
    <groupId>io.github.pderop</groupId>
    <artifactId>looma-mpsc-scheduler</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## `mpsc`: the JDK's MPSC scheduler

Each carrier, `mpsc-carrier-<id>`, has its own queues and, on Linux, its own epoll for reads:
[how it runs](../mpsc-scheduler/README.md#how-the-mpsc-scheduler-runs).

- Offloaded threads have no affinity: they start on the carrier that starts them, and an idle
  carrier may take them.
- Mailbox threads have no affinity by default. With `affine(true)`, they have their carrier's
  `Thread.Affinity`: never stolen.
- `pinned(true)` pins each carrier to one CPU, after the JDK's pollers and timers started:
  [the install](mpsc-install.md).

### The keep-alive

On a JDK without `Thread.Affinity`, the mailbox thread of an idle actor does not end. It yields
once, then parks until the next message. A message that comes during the yield finds it still on
its carrier. A later message wakes it, and the JDK sends it back to the carrier it last ran on,
unless a thread of another carrier wakes it (it then moves there) or an idle carrier steals it.

| JDK | Setting | When the mailbox is empty, the mailbox thread |
| --- | --- | --- |
| with `Thread.Affinity` (`pr227`) | any, the default included | ends |
| without `Thread.Affinity` (`noaff`) | `Carriers.mpsc(n)` only | waits for the next message |
| any | `Carriers.builtin(n)` | ends |

Each idle actor then keeps one parked thread, with its stack. Nothing limits their number yet.

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
  with no mailbox of its own; `forKey` picks one of them by key.
- **`Envelope`** — one mailbox entry, a message or a lifecycle step.
- **`ActorSchedulers`** — discovery, and `carriers(...)`'s installation.

```
tell
  1. mailbox.offer(envelope)
  2. pending.getAndIncrement()   0 -> 1 ?  wakeMailboxThread():
                                            start a mailbox thread, from the carrier's mailboxThreadFactory
                                            (keep-alive: unpark the waiting one, if any)
mailbox thread
  runs one envelope per count, then awaitNextTell():
    ends                          (keep-alive: yields once, then parks until the next tell)
```

```
do {
  do {
    if (state != STOPPED) {
      envelope = mailbox.poll();
      if (envelope != null) { runEnvelope; interrupted(); }
    }
  } while (pending.decrementAndGet() > 0);
  if (TERMINATING && !cascadeStarted && pending == 0) tryFinishDraining();
} while (awaitNextTell());       // false: the thread ends. Keep-alive: true once a tell woke it
```

- One mailbox thread at a time: only the `tell` from 0 starts or wakes one. An actor's fields need
  no lock.
- Idle actor: no virtual thread, no stack ([keep-alive](#the-keep-alive): one parked thread).
- A mailbox thread never yields while its mailbox has envelopes. An actor whose mailbox never
  empties keeps its carrier; the others on it wait. Fairness is a non-goal.
- Offer, then count: a counted envelope is always there. A `tell` while the mailbox thread runs
  only counts.
- The count starts at 1, held by `start()`: a `tell` before `start()` waits for it. `preStart` is
  the first envelope, `runFinalStop` the last. A restart keeps the mailbox thread and the queue,
  with a new actor instance.

`shutdown()` and `awaitTermination` are in [`api.md`](api.md#10-stopping). The carriers are
JVM-wide daemon threads: no scheduler's `close()` stops anything.

### The wheel thread

Looma's [timers](api.md#5-timers) run on one wheel per carrier. Each wheel has its virtual thread,
`looma-timer-<carrier>`, started from `mailboxThreadFactory(carrier)` at the carrier's first timer.
Without `affine(true)` that factory gives a plain virtual thread, which may run on any carrier.
Under `builtin` the system has one wheel.

```
wheel thread, each round
  drains the inbox and the cancelled queue, then fires the timers that are due
  parks when the wheel is empty; while it holds a timer, wakes at each tick
shutdown()
  cancels every pending timer, soon after, then ends; awaitTermination does not wait for it
```

Under `mpsc` with `affine(true)`, a mailbox thread never leaves its carrier, so it may touch the
wheel of its carrier directly:

```
a mailbox thread starts on carrier-3
  the wheel of carrier-3 runs?   no  -> its timers go through the queues, until it ends
                                 yes -> it marks itself local to that wheel

ref.tell(msg, 50 ms)             ref's home carrier is carrier-3
  from the marked thread         -> straight into the wheel, no queue
  from any other thread          -> the inbox of the wheel; the wheel thread moves it into its slot
                                    (a thread the marked one forks is another thread)
  at the deadline                -> the wheel thread calls ref.tell(msg), on carrier-3

future.cancel(false)
  from the marked thread         -> the timer leaves the wheel at once
  from any other thread          -> the message is freed at once, then
                                    still in the inbox: dropped when the wheel thread drains it
                                    in the wheel: to the cancelled queue, removed at the next tick
```

A pool has no single carrier: the marked thread uses the wheel of its carrier, any other caller a
wheel chosen from its thread id.

Under `builtin`, and under `mpsc` without `affine(true)`, no thread is local: every arm and every
cancel goes through the queues. The switch is `mailboxThreadsStayOnCarrier()`, below.

## The SPI

```java
public interface ActorScheduler extends AutoCloseable {
    int carrierCount();                          // builtin: 1 | mpsc: one per carrier
    ThreadFactory vThreadFactory(int i);         // the threads an actor offloads
    default ThreadFactory mailboxThreadFactory(int i) { return vThreadFactory(i); }   // its mailbox threads
    default boolean mailboxThreadsStayOnCarrier() { return false; }   // mpsc with affine(true): true
    default boolean keepMailboxThreadsAlive() { return false; }       // mpsc on noaff: true
    int currentCarrierId();                      // the calling thread's carrier; builtin: -1
    OptionalInt pinnedCpu();
    OptionalInt clusterOf(int i);                // builtin: empty
    default ThreadFactory platformThreadFactory() { return Thread.ofPlatform().factory(); }
    void close();
    default boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException { ... }
}
```

- `mailboxThreadsStayOnCarrier()`: true only if every thread from `mailboxThreadFactory(i)` runs on
  carrier i alone, one at a time, never stolen. It turns on the direct path of the timers.
- `keepMailboxThreadsAlive()`: true if the mailbox thread of an idle actor waits for the next
  message instead of ending ([the keep-alive](#the-keep-alive)). Read once, when the system is
  built.
- `ActorSchedulerProvider`: creates one, `isAvailable()`, can install itself for `Carriers.Mpsc`.
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

Affinity keeps the **L2**, not the L1. The [user benchmark](benchmark-user.md) measures it with two
sizes of state: one that fits a core's L2, one that does not.

`mpsc` and `mpsc-affine` are the same pool without and with affinity. With them, the benchmarks
measure what keeping an actor on its carrier brings, all else being equal.
