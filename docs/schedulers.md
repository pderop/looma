# The schedulers, and the engine

One actor engine. An actor runs on a virtual thread only while it has messages: one virtual
thread per burst. The scheduler picks the carrier. Looma ships two:

| In code | What it is | Carriers pinned | An actor moves | An idle carrier steals |
| --- | --- | --- | --- | --- |
| `Carriers.affine(n)` | the JDK's MPSC scheduler, from [openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227) | yes, one CPU each | never | the other threads, from any carrier |
| `Carriers.builtin(n)` | the JDK's default scheduler, a `ForkJoinPool` | no | may, at every burst | anything |

- "The other threads": those an actor offloads through `ActorContext.vThreadFactory()`.
- Pinning: Linux only. Elsewhere the carriers float.
- `affine` has no options: it is the setup the [benchmark](benchmark.md) found the fastest.
  `builtin` is the control.
- `affine` is the guarantee (an actor stays on one carrier). The MPSC scheduler, in the module
  `mpsc-scheduler`, is how the JDK does it today. The benchmark calls its runs `mpsc`.

Until 2026-10-31, `affine` was Looma's copy of the
[Netty-VirtualThread-Scheduler](https://github.com/franz1981/Netty-VirtualThread-Scheduler) of
Francesco Nigro, `netty-loom-scheduler`, no longer built. The campaigns of 2026-10-22 and
2026-10-24 ran it.

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

`--enable-native-access=ALL-UNNAMED` stays on the command line: `affine(n)` pins through native
calls. Without it, the JVM warns. With `--illegal-native-access=deny`, nothing is pinned, and
Looma says so.

### As JVM flags

Without `carriers(...)`, the flags of the table above choose the scheduler, and `build()` finds
it. The actors still never move. The carriers are not pinned: only `affine(n)` pins them.

Looma reads no system property of its own.

### On the classpath

`affine` needs this module. Without it, discovery falls back to the builtin, and
`carriers(Carriers.affine(n))` throws.

```xml
<dependency>
    <groupId>io.github.pderop</groupId>
    <artifactId>looma-mpsc-scheduler</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

A provider that fails to link (compiled for a newer JDK) is skipped.

## `affine`: the JDK's MPSC scheduler

From the JDK of openjdk/loom pull request 227, a draft on Loom's `fibers` branch. On with
`jdk.virtualThreadScheduler.useMpsc=true`. Each carrier, `mpsc-carrier-<id>`, has its own queue.

- An actor's bursts have its carrier's `Thread.Affinity`: never stolen.
- Offloaded threads have none: they start on the actor's carrier, and an idle carrier may take them.
- Pinning: one thread on each carrier calls `sched_setaffinity`, after the JDK's pollers and timers
  started. [What the install does](#what-the-install-does).
- On Linux, poller mode 4 is the default: each carrier has its own epoll.
  [How it runs](#how-the-mpsc-scheduler-runs).

### How the MPSC scheduler runs

Each carrier is one OS thread, with two queues and, on Linux, its own epoll. Many threads may add
to them.

```
carrier-0
  queue        VTs with an affinity (an actor's bursts)  -> only carrier-0 takes them
  stealQueue   VTs without one (offloaded threads)       -> carrier-0, or an idle carrier

a thread with an affinity  -> the queue of its carrier, at every start and every wake
a thread without one       -> the stealQueue of the carrier that starts it
an idle carrier steals     -> from another carrier's stealQueue only: never an actor's burst
```

**The loop of a carrier:**

```
carrier-0
  1. run the virtual threads of its two queues, for one round
  2. epoll_wait(0): sockets ready?  -> unpark their virtual threads -> back to their queue
  3. nothing to do?  -> try to steal from another carrier's stealQueue
                        still nothing -> epoll_wait(-1): sleep in its epoll
```

**A virtual thread of carrier-0 reads a socket with no data:**

```
VT-a on carrier-0: read()          -> no data
  -> the JDK registers the socket in carrier-0's epoll
  -> VT-a parks; carrier-0 runs the next virtual thread
data arrives
  -> carrier-0's epoll_wait returns the socket
  -> carrier-0 unparks VT-a -> back to VT-a's queue on carrier-0
next round
  -> VT-a runs again, after read()
```

No other thread takes part.

**A virtual thread of carrier-0 writes to a full socket:**

```
VT-a on carrier-0: write()         -> the send buffer is full
  -> the JDK registers the socket in the Write-Poller's epoll
  -> VT-a parks
the peer reads, the socket has room
  -> the Write-Poller unparks VT-a -> back to VT-a's queue on carrier-0
  -> if carrier-0 sleeps in its epoll, the Write-Poller wakes it
carrier-0
  -> VT-a runs again, after write()
```

A `write()` that fits in the send buffer does not park, and the `Write-Poller` does nothing.

| Who waits | To read | To write |
|---|---|---|
| a virtual thread on an MPSC carrier | the epoll of its carrier | `Write-Poller` |
| any other thread | `Read-Poller` | `Write-Poller` |

The scheduler never sees the bytes. `read()` and `write()` run in the virtual thread. The
scheduler sees only threads that park and threads that are unparked.

### The threads the JDK starts

A new thread runs only on the CPUs of the thread that starts it. The JDK starts these threads
from the first thread that needs them:

| Thread | Does | Started |
|---|---|---|
| `mpsc-carrier-<id>` | runs the virtual threads | at the first virtual thread |
| `Read-Poller` | reads for the threads that are not on an MPSC carrier | when the class `sun.nio.ch.Poller` loads |
| `Write-Poller` | every wait to write | same |
| `VirtualThread-unparker` | wakes a virtual thread after `sleep` or a timed park | at the first timed park that uses it |
| `ForkJoinPool.commonPool-worker-<n>` | the common pool: `parallelStream`, `supplyAsync` | when the pool needs one more |
| `ForkJoinPool.commonPool-delayScheduler` | `orTimeout`, `delayedExecutor` | at the first timeout |

Without care, a pinned carrier starts some of them:

```
carrier-0 pinned to CPU 2
VT on carrier-0: the first read() of the JVM
  -> Poller loads -> Read-Poller and Write-Poller start from carrier-0
  -> both run on CPU 2 only, for good
```

### What the install does

`Carriers.affine(n)` installs the scheduler from the calling thread, `main` here, which still has
every CPU of the process.

```
main
  1. set the properties: useMpsc, parallelism = n, mpscSteal
  2. load sun.nio.ch.Poller          -> Read-Poller and Write-Poller start from main
  3. start one virtual thread        -> is it on an mpsc-carrier? no: undo 1, and fail
  4. the common pool's factory = AllCpus, unless the application set one
     ── from here, the JVM runs the MPSC scheduler for good ──
  5. save the CPUs of main
  6. orTimeout(1 ns)                 -> the common pool's delayScheduler starts from main
  7. check that the common pool uses AllCpus; if it started before, warn
  8. a. n times: a new affinity, one virtual thread with it, its carrier read from its name
     b. on each carrier: park 1 ms   -> that carrier's VirtualThread-unparker starts
     c. on each carrier: pin it to its CPU  (last)
```

After it:

```
mpsc-carrier-0 ── one CPU     Read-Poller, Write-Poller  ── every CPU of main
mpsc-carrier-1 ── one CPU     VirtualThread-unparker     ── every CPU of main
...                           common pool                ── every CPU of main
```

A worker of the common pool may still start from a carrier. `AllCpus` makes it, and the worker's
first act is to take back the CPUs saved in step 5:

```
actor on carrier-0 (CPU 2): parallelStream()
  -> the pool starts a worker from carrier-0  -> it has CPU 2
  -> AllCpus: the worker sets the saved CPUs   -> every CPU of main
```

`system.scheduler().platformThreadFactory()` does the same for the application's own threads.
The delayScheduler is not made by the pool's factory: that is why step 6 starts it.

## `builtin`: the JDK's default scheduler

- Bursts and offloaded threads: plain `Thread.ofVirtual()`.
- Chosen by `carriers(Carriers.builtin(n))`, or when no flag chooses another scheduler. Refused when
  `useMpsc` is set.
- `carrierCount()` is 1: `Placement.carrier(i)` with `i > 0` throws at spawn.

## Placement

With `affine`, each actor gets a home carrier at spawn. Defaults, changed per spawn with
`SpawnOptions.placedOn(Placement)`:

- **root**: round-robin (`Placement.roundRobin()`);
- **child**: its parent's carrier (`Placement.inherit()`). Messages between them stay on one carrier.

A pool (`SpawnOptions.pooled(n)`): `inherit()` or `carrier(i)` puts it on one carrier,
`roundRobin()` puts one actor per carrier.

By key, with `carrier(i)`:

```
actor for key k        -> carrier k % carrierCount()
shard i of a service   -> carrier i % carrierCount()
carrierCount divides the shard count -> the shard for key k is on the same carrier
```

See [`api.md`](api.md#8-placement). `ActorSystem.homeCarrierIdOf(ActorRef)` gives an actor's home
(not for a pool). `ActorScheduler.currentCarrierId()` gives the calling thread's carrier.

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

```
tell
  1. mailbox.offer(envelope)
  2. pending.getAndIncrement()   0 -> 1 ?  start a burst (a virtual thread, the carrier's burst factory)
burst
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

- One burst at a time: only the `tell` from 0 starts one. An actor's fields need no lock.
- Idle actor: no virtual thread, no stack.
- A burst never yields. An actor whose mailbox never empties keeps its carrier; the others on it
  wait. Fairness is a non-goal.
- Offer, then count: a counted envelope is always there. A `tell` during a burst only counts.
- The count starts at 1, held by `start()`: a `tell` before `start()` waits for it. `preStart` is
  the first envelope, `runFinalStop` the last. A restart keeps the burst and the queue, with a new
  actor instance.

### Shutdown

```
shutdown()
  -> stop every root -> drain each mailbox -> stop the children -> postStop back up
awaitTermination
  -> returns when every actor is gone and every burst and vThreadFactory() thread has ended
```

No scheduler's `close()` stops anything: the carriers are JVM-wide daemon threads.

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
    default boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException;
}
```

- `ActorSchedulerProvider`: creates one, `isAvailable()`, can install itself for `Carriers.Affine`.
- A third-party scheduler: implement both, register the provider in
  `META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider`.
- `ActorRuntime` wraps every factory, so each thread counts as in-flight work.
- **The mailbox queue**: `spi.MailboxQueueFactory`, set with
  `ActorSystem.Builder.mailboxQueueFactory(...)`. Default: `ConcurrentLinkedQueue`. At least MPSC,
  no blocking `poll`.

## Tests

`core` runs the contract suites on the builtin. `mpsc-scheduler` runs them again, one JVM per
setup (the scheduler is set once per JVM):

| Execution | Flags | Runs |
| --- | --- | --- |
| `default-test` | the MPSC scheduler, 4 carriers, stealing | the actor suites, and `MpscAffinityTest`: an actor never leaves its carrier, an offloaded thread can be stolen |
| `install-test` | no scheduler flag, one JVM per class | `carriers(...)` installing `affine`, and refusing to once a virtual thread has run, or below a `maxPoolSize`. On Linux: each carrier on its own CPU, and the threads an actor makes start on every CPU: the JDK's pollers and timers, the common pool, `platformThreadFactory()` |
| `no-native-access-test` | `--illegal-native-access=deny` | `carriers(...)` installing `affine` in a JVM that refuses native calls: it runs, and nothing is pinned |

## Blocking and pinning

```
blocking onReceive  -> unmounts; only this actor's mailbox waits  -> offload with vThreadFactory()
pin (native frame)  -> holds the carrier
                       builtin: that worker is lost until the call returns
                       affine:  every virtual thread homed there waits
```

`synchronized` no longer pins ([JEP 491](https://openjdk.org/jeps/491), JDK 24).

## Why it is built this way

### Why carrier affinity

An unparked virtual thread may run **anywhere**. An actor is its state, and it starts a new
burst at every `tell` after an empty mailbox: each one is a chance to move.

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

With a home carrier pinned to a CPU, the state stays on one core, and the children too. Offloaded
work may be stolen: it reads none of the actor's state.

Affinity keeps the **L2**, not the L1. The [benchmark](benchmark.md) tests the claim: **the gain
grows with the actor's state while it fits a core's L2, and vanishes once it does not.**
