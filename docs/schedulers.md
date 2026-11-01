# The schedulers, and the engine

There is one actor engine. An actor runs on a virtual thread only while it has messages: one
virtual thread per burst of messages. The scheduler decides which carrier runs those threads.
Looma ships two:

| In code | What it is | Carriers pinned | An actor moves | An idle carrier steals |
| --- | --- | --- | --- | --- |
| `Carriers.affine(n)` | the JDK's MPSC scheduler, from [openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227) | yes, one CPU each | never | the other threads, from any carrier |
| `Carriers.builtin(n)` | the JDK's default scheduler, today a `ForkJoinPool` | no | at every burst | anything |

"The other threads" are the ones an actor offloads through `ActorContext.vThreadFactory()`: an
I/O call, for example. Pinning works on Linux only; elsewhere the carriers float.

`affine` has no options. It is the setup the [benchmark](benchmark.md) found the fastest.
`builtin` is the control it is compared with.

`affine` names what Looma guarantees: an actor stays on one carrier. The MPSC scheduler is how the
JDK does it today, in the module `mpsc-scheduler`. The benchmark names the runs `mpsc`, after the
scheduler that ran.

Until 2026-10-31, `affine` was Looma's copy of the
[Netty-VirtualThread-Scheduler](https://github.com/franz1981/Netty-VirtualThread-Scheduler) of
Francesco Nigro, and the MPSC scheduler was `Carriers.mpsc(n)`. That copy, `netty-loom-scheduler`,
is no longer built. In the results up to that date, `affine` is that copy.

## Choosing one

### In code

```java
ActorSystem system = ActorSystem.builder()
        .carriers(Carriers.affine(8))       // or Carriers.builtin(8)
        .build();
```

`build()` sets the JDK properties of that scheduler, then checks that they took:

| `Carriers` | Properties it sets |
| --- | --- |
| `affine(n)` | `jdk.virtualThreadScheduler.useMpsc=true`, `jdk.virtualThreadScheduler.mpscSteal=true`, `jdk.virtualThreadScheduler.parallelism=n`, and `java.util.concurrent.ForkJoinPool.common.threadFactory` unless already set: see [Threads started from an actor](../mpsc-scheduler/README.md#threads-started-from-an-actor) |
| `builtin(n)` | `jdk.virtualThreadScheduler.parallelism=n` |

The JDK reads these properties once, while it initialises virtual threads. So `build()` must run
**before the JVM's first virtual thread**. It throws `IllegalStateException` rather than run on
another scheduler when:

- a virtual thread already ran;
- a property it sets already holds another value, from a `-D` flag;
- the command line already chooses a scheduler, with `-Djdk.virtualThreadScheduler.implClass` or
  `-Djdk.virtualThreadScheduler.useMpsc`;
- no provider can install it: no `looma-mpsc-scheduler` on the classpath for `affine`, or a JDK
  without the MPSC scheduler;
- `-Djdk.virtualThreadScheduler.maxPoolSize` is below the count of `affine(n)`: the JDK would
  start fewer carriers;
- an earlier system in this JVM was built with another pool. A second system on the same pool is
  fine;
- an earlier `affine(n)` failed after the JDK had started the scheduler: nothing can be undone,
  so it fails again, with the first reason.

One case goes undetected: `builtin(n)` after a virtual thread already ran. The JDK has sized its
pool, and `n` is silently not what is in force.

`--enable-native-access=ALL-UNNAMED` is a launch flag, not a property: it stays on the command
line. `affine(n)` pins its carriers through native calls. Without the flag, the JVM warns today;
if it refuses them, as with `--illegal-native-access=deny`, the carriers are not pinned, and Looma
says so.

### As JVM flags

Without `carriers(...)`, the scheduler comes from the flags, and `build()` finds it. The flags are
those of the table above. With the flags, the actors still never move: that is in Looma's adapter,
not in a property. But the carriers are not pinned: only `affine(n)` pins them.

Looma reads no system property of its own.

### On the classpath

`affine` needs its module on the classpath, or there is no provider: discovery falls back to the
builtin, and `carriers(Carriers.affine(n))` throws.

```xml
<dependency>
    <groupId>io.github.pderop</groupId>
    <artifactId>looma-mpsc-scheduler</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

A provider that fails to link — a class compiled for a newer JDK — is caught and skipped by
discovery, which keeps looking.

## `affine`: the JDK's MPSC scheduler

The MPSC scheduler comes with the JDK of openjdk/loom pull request 227, a draft on Loom's
`fibers` branch. It replaces the JDK's default scheduler when
`jdk.virtualThreadScheduler.useMpsc=true`. Each carrier, `mpsc-carrier-<id>`, has its own queue.

- Each actor's bursts get the `Thread.Affinity` of its carrier. A thread with an affinity runs on
  that carrier for life: stealing never takes it.
- The threads an actor offloads have no affinity. They start on the actor's carrier, and an idle
  carrier may take them, from any carrier.
- An affinity does not say which carrier it lands on. At install, Looma starts one thread per
  affinity, and reads its carrier from the thread's name.
- Looma pins each carrier: one thread with that carrier's affinity calls `sched_setaffinity`.
  Before that, it starts the JDK's pollers and timers: see
  [Threads started from an actor](../mpsc-scheduler/README.md#threads-started-from-an-actor).
- On Linux, the JDK uses poller mode 4 by default with this scheduler: each carrier has its own
  epoll, and looks at it between two rounds of its queue. The JDK still starts one `Read-Poller`
  thread, for the virtual threads that run on no MPSC carrier, and a `Write-Poller`. There is no
  `Master-Poller`.

## `builtin`: the JDK's default scheduler

Every virtual thread it hands out, the bursts and the offloaded ones, is a plain
`Thread.ofVirtual()` on the JDK's default scheduler. Never the MPSC one: `builtin(n)` refuses to
run when `useMpsc` is set. You get it when `carriers(Carriers.builtin(n))` asks for it, or when,
without `carriers(...)`, no flag chooses another scheduler.

`carrierCount()` is 1: every placement resolves to that carrier, and `Placement.carrier(i)` for
any `i > 0` throws at spawn.

## Placement

With `affine`, each actor has a home carrier, chosen at spawn. Two defaults, each
overridable per spawn with `SpawnOptions.placedOn(Placement)`:

- a **root** actor is placed round-robin over the carriers (`Placement.roundRobin()`);
- a **child** gets its parent's carrier (`Placement.inherit()`): a parent and its children share
  one core, and a message between them never crosses carriers. Their offloaded work starts on that
  core too, and an idle carrier may steal it.

A pool (`SpawnOptions.pooled(n)`) applies its placement to each actor: `inherit()` or
`carrier(i)` keeps the pool on one carrier, `roundRobin()` spreads it one actor per carrier.

Work that belongs to a key is placed by the key, with `carrier(i)`: the actor for key `k` on
carrier `k % carrierCount()`, shard `i` of a service on carrier `i % carrierCount()`. When the
carrier count divides the shard count, the shard serving key `k` is on that same carrier, and the
actors serving one key share a core. See [`api.md`](api.md#8-placement).

`ActorSystem.homeCarrierIdOf(ActorRef)` reports an actor's home carrier; it rejects a pool
reference. `ActorScheduler.currentCarrierId()` is the carrier of the calling thread. For an actor,
both are the same, since an actor never moves.

## The engine

The engine is in `io.github.pderop.looma.impl`, once, for both schedulers:

- **`ActorRuntime`** — what one system shares: the path registry, the roots, in-flight accounting,
  shutdown and termination, the mailbox-queue factory.
- **`ActorCell`** — one actor: mailbox, bursts, hierarchy, lifecycle hooks,
  supervision. It implements `ActorContext`.
- **`ActorRefImpl`** — the reference to a live cell; **`PromiseRef`** — the single-use reply
  channel an `ask` passes as `sender`; **`PoolRef`** — a round-robin fan-out over a pool's actors,
  with no mailbox of its own.
- **`Envelope`** — one mailbox entry, a message or a lifecycle step.
- **`ActorSchedulers`** — discovery, and `carriers(...)`'s installation.

`tell` is two steps: `mailbox.offer(envelope)`, then `pending.getAndIncrement()`. When the count
goes from 0 to 1, the `tell` starts a virtual thread from the actor's carrier's burst factory: a
**burst**. The burst runs one envelope per count, then ends:

```
do {
  if (state != STOPPED) {
    envelope = mailbox.poll();
    if (envelope != null) { runEnvelope; interrupted(); }
  }
} while (pending.decrementAndGet() > 0);
if (TERMINATING && !cascadeStarted && pending == 0) tryFinishDraining();
```

- The count *is* the serialization: only the `tell` that takes it from 0 starts a burst, so two
  bursts never run at once, and an actor's fields need no synchronization.
- An idle actor has no virtual thread, so no stack: nothing is retained between bursts.
- A burst never yields: virtual threads are not time-sliced, so an actor whose mailbox never
  empties keeps its carrier for as long as it keeps receiving, and the actors sharing that carrier
  wait. Fairness under a saturated mailbox is a non-goal here.
- Offer, then count: a burst that counts an envelope always finds it. A `tell` while a burst runs
  only adds to the count, and that burst runs it.
- The count starts at 1, held by `start()`: a `tell` before `start()` only offers, and the first
  burst, started by `start()`, runs it. `preStart` is the first envelope, `runFinalStop` the last.
  A restart keeps the burst and the queue and installs a new actor instance.

### Shutdown

`shutdown()` starts a graceful cascading stop, the same under every scheduler: every root stopped,
each mailbox drained, the cascade descending to children, `postStop` unwinding back up.
`awaitTermination` returns once every actor is unregistered **and** every unit of in-flight work
has finished: the bursts, and every thread created through `ActorContext.vThreadFactory()`.

No scheduler's `close()` stops anything. The builtin owns no pool; the carriers are JVM-wide
daemon threads, created on first use and never torn down.

## The SPI

```java
public interface ActorScheduler extends AutoCloseable {
    int carrierCount();                          // builtin: 1 | affine: one per carrier
    ThreadFactory vThreadFactory(int i);         // the threads an actor offloads
    default ThreadFactory actorLoopFactory(int i) { return vThreadFactory(i); }   // its bursts
    int currentCarrierId();                      // the calling thread's carrier; builtin: -1
    OptionalInt pinnedCpu();
    OptionalInt clusterOf(int i);                // builtin: empty
    default ThreadFactory platformThreadFactory() { return Thread.ofPlatform().factory(); }
    void close();
}
```

`ActorSchedulerProvider` creates one, says whether it `isAvailable()`, and can install itself for
`Carriers.Affine`. A third-party scheduler plugs in the same way as Looma's own:
implement both, register the provider under
`META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider`, and `core` never learns its
name.

`ActorRuntime` wraps every factory it gets before anything uses it, so that each thread it creates
counts as in-flight work; a scheduler never counts its own.

**The mailbox queue** is the other SPI: `spi.MailboxQueueFactory`, set with
`ActorSystem.Builder.mailboxQueueFactory(...)`, never discovered. The default wraps
`ConcurrentLinkedQueue`. A queue must be at least MPSC and must not block in `poll`.

## Tests

`core` runs the contract suites once, on the builtin scheduler, in its single Surefire execution.
The scheduler module runs them again, in several executions, because the scheduler is configured
once per JVM. In `mpsc-scheduler`:

| Execution | Flags | Runs |
| --- | --- | --- |
| `default-test` | the MPSC scheduler, 4 carriers, stealing | the actor suites, and `MpscAffinityTest`: an actor never leaves its carrier, an offloaded thread can be stolen |
| `install-test` | no scheduler flag, one JVM per class | `carriers(...)` installing `affine`, and refusing to once a virtual thread has run, or below a `maxPoolSize`. On Linux: each carrier on its own CPU, and the threads an actor makes start on every CPU: the JDK's pollers and timers, the common pool, `platformThreadFactory()` |
| `no-native-access-test` | `--illegal-native-access=deny` | `carriers(...)` installing `affine` in a JVM that refuses native calls: it runs, and nothing is pinned |

## Blocking and pinning

A blocking `onReceive` unmounts this actor only: every actor has its own bursts. Offloading still
belongs on `ActorContext.vThreadFactory()`, because a blocked burst stalls this actor's own mailbox.

A *pin* — a native frame on the stack, a JNI or foreign-function call — cannot unmount at all and
holds its carrier. Under the builtin that costs one `ForkJoinPool` worker, which the pool can
compensate for; under `affine` it stalls every virtual thread that calls that carrier
home. `synchronized` no longer pins ([JEP 491](https://openjdk.org/jeps/491), JDK 24).

## Why it is built this way

### Why carrier affinity

A virtual thread is a continuation plus a scheduler. When it parks, its carrier goes back to the
pool; when it is unparked, the continuation is submitted again, and the scheduler may run it
**anywhere**. For a stateless handler that costs nothing. An actor *is* its state, and its virtual
thread ends on an empty mailbox and a new one starts at the next `tell` all the time: every one of
those is a chance to move.

```
    ┌── FJP worker 0 ─── CPU 0 ────────────────────────┐
    │  actor A drains its mailbox, runs onReceive      │
    │  A's state is now in CPU 0's L1d / L2,           │
    │  and dirty there                                 │
    └──────────────────────────────────────────────────┘

         mailbox empty → A's virtual thread ends. Every actor, all the time.
         a `tell` starts a new one → it goes to the pool
                                │
                                ▼   any idle worker may take it

    ┌── FJP worker 5 ─── CPU 5 ────────────────────────┐
    │  actor A resumes here                            │
    │  its lines are not in CPU 5's caches: each one   │
    │  comes back from L3, or from CPU 0 if dirty      │
    └──────────────────────────────────────────────────┘
```

Nothing was shared, nothing needed a lock, and the actor still pays to bring its state back — only
because the scheduler moved the code away from the data. With a home carrier pinned to a CPU, the
state is written and read on one core for the whole life of the actor; its children land there
too. Its offloaded work starts there, and an idle carrier may steal it: that work reads none of the
actor's state.

Affinity preserves the **L2**, not the L1: an actor's state rarely fits a core's L1d, and whatever
else the carrier ran meanwhile has flushed it. Hence the claim the
[benchmark](benchmark.md) is built to test: **an affinity advantage must grow with the per-actor
state while it fits a core's L2, and vanish once it no longer does.**
