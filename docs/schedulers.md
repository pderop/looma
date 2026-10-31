# The schedulers, and the engine

There is one actor engine. An actor runs on a virtual thread only while it has messages: one
virtual thread per burst of messages. The scheduler decides which carrier runs those threads.
Looma ships three:

| In code | What it is | Carriers pinned | An actor moves | An idle carrier steals |
| --- | --- | --- | --- | --- |
| `Carriers.affine(n)` | Looma's copy of the [Netty-VirtualThread-Scheduler](https://github.com/franz1981/Netty-VirtualThread-Scheduler) of Francesco Nigro | yes, one CPU each | never | the other threads, from the carriers of its L3 |
| `Carriers.mpsc(n)` | the JDK's MPSC scheduler, from [openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227) | yes, one CPU each | never | the other threads, from any carrier |
| `Carriers.builtin(n)` | the JDK's default scheduler, today a `ForkJoinPool` | no | at every burst | anything |

"The other threads" are the ones an actor offloads through `ActorContext.vThreadFactory()`: an
I/O call, for example. Pinning works on Linux only; elsewhere the carriers float.

`affine` and `mpsc` have no options. Each one is the setup the [benchmark](benchmark.md) found the
fastest for it. `builtin` is the control they are compared with.

## Choosing one

### In code

```java
ActorSystem system = ActorSystem.builder()
        .carriers(Carriers.affine(8))       // or Carriers.mpsc(8), or Carriers.builtin(8)
        .build();
```

`build()` sets the JDK properties of that scheduler, then checks that they took:

| `Carriers` | Properties it sets |
| --- | --- |
| `affine(n)` | `jdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler`, `io.netty.loom.schedulers=n`, `io.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology`, `io.netty.loom.workstealing.enabled=true`, `io.netty.loom.workstealing.scope=CLUSTER_LOCAL`, `jdk.pollerMode=3` |
| `mpsc(n)` | `jdk.virtualThreadScheduler.useMpsc=true`, `jdk.virtualThreadScheduler.mpscSteal=true`, `jdk.virtualThreadScheduler.parallelism=n` |
| `builtin(n)` | `jdk.virtualThreadScheduler.parallelism=n` |

The JDK reads these properties once, while it initialises virtual threads. So `build()` must run
**before the JVM's first virtual thread**. It throws `IllegalStateException` rather than run on
another scheduler when:

- a virtual thread already ran;
- a property it sets already holds another value, from a `-D` flag;
- the command line already chooses a scheduler, with `-Djdk.virtualThreadScheduler.implClass` or
  `-Djdk.virtualThreadScheduler.useMpsc`;
- no provider can install it: no `looma-netty-scheduler` on the classpath, no `--enable-preview`,
  or a JDK without the MPSC scheduler;
- an earlier system in this JVM was built with another pool. A second system on the same pool is
  fine.

One case goes undetected: `builtin(n)` after a virtual thread already ran. The JDK has sized its
pool, and `n` is silently not what is in force.

`--enable-preview` and `--enable-native-access=ALL-UNNAMED` are launch flags, not properties: they
stay on the command line.

### As JVM flags

Without `carriers(...)`, the scheduler comes from the flags, and `build()` finds it. The flags are
those of the table above, plus the options of the copied scheduler, which `affine(n)` fixes:

| Property | Default | Meaning |
| --- | --- | --- |
| `io.netty.loom.topology` | none | `io.netty.loom.topology.LinuxCarrierTopology` pins the carriers and groups them by L3. With none, the carriers are not pinned |
| `io.netty.loom.workstealing.enabled` | `false` | work stealing |
| `io.netty.loom.workstealing.scope` | `GLOBAL` | `CLUSTER_LOCAL`: steal only within an L3 |
| `io.netty.loom.yield.us` | `50` | how long a carrier drains its queues before it checks for I/O and stealing, in µs |
| `io.netty.loom.resumed.continuations` | `1024` | initial run-queue capacity per carrier |
| `io.netty.loom.replaceBuiltinScheduler` | `false` | run every virtual thread of the JVM on the carriers; not used by Looma |

With the flags, the actors of `affine` still never move: that is in Looma's adapter, not in a
property. The MPSC carriers are not pinned: only `mpsc(n)` pins them.

The copied scheduler logs one line at start-up, for instance `topology=LinuxCarrierTopology
carriers=8 clusters=2 stealScope=CLUSTER_LOCAL workStealing=true`.

Looma reads no system property of its own.

### On the classpath

For `affine` and `mpsc`, `looma-netty-scheduler` must be on the classpath, or there is no provider:
discovery falls back to the builtin, and `carriers(...)` throws.

```xml
<dependency>
    <groupId>io.github.pderop</groupId>
    <artifactId>looma-netty-scheduler</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

For `affine`, the jar must be visible to the **system** classloader: the JDK loads `implClass` with
`Class.forName(cn, true, ClassLoader.getSystemClassLoader())`. Under a plain `-cp` launch (Surefire,
the `scripts/`) that is automatic; under a container that interposes its own classloader (a Spring
Boot fat jar, Quarkus) it is not.

A provider that fails to link — a preview-compiled class on a stock JDK — is caught and skipped by
discovery, which keeps looking.

## `affine`: Looma's copy of the Netty-VirtualThread-Scheduler

- Each carrier is pinned to one CPU of the JVM's list, and grouped with the carriers that share
  its L3 cache.
- Every actor has one **home carrier** for life, chosen at spawn.
- Each carrier has two queues. The **home queue** holds the actors' bursts, and only that carrier
  reads it. The **usual queue** holds the other threads.
- An idle carrier steals from the usual queue of a carrier of its own L3. It never reads a home
  queue, so it never takes an actor.
- A stolen thread runs once on the thief, then goes back to its home carrier.
- `jdk.pollerMode=3`: each carrier has its own read poller, a virtual thread.

The home queue is one of the two changes Looma makes to its copy of the scheduler. The other one
starts the JDK's poller threads before the carriers pin themselves. A new thread runs only on the
CPUs of the thread that starts it, so a poller started later from a pinned carrier would stay on
that carrier's CPU. See [`netty-loom-scheduler/README.md`](../netty-loom-scheduler/README.md).

## `mpsc`: the JDK's MPSC scheduler

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
  Before that, it starts the JDK's poller threads, for the reason given above.
- On Linux, the JDK uses poller mode 4 by default with this scheduler: each carrier has its own
  epoll, and looks at it between two rounds of its queue. There is no read poller thread.

## `builtin`: the JDK's default scheduler

Every virtual thread it hands out, the bursts and the offloaded ones, is a plain
`Thread.ofVirtual()` on the JDK's default scheduler. Never the MPSC one: `builtin(n)` refuses to
run when `useMpsc` is set. You get it when `carriers(Carriers.builtin(n))` asks for it, or when,
without `carriers(...)`, no flag chooses another scheduler.

`carrierCount()` is 1: every placement resolves to that carrier, and `Placement.carrier(i)` for
any `i > 0` throws at spawn.

## Placement

With `affine` and `mpsc`, each actor has a home carrier, chosen at spawn. Two defaults, each
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

The engine is in `io.github.pderop.looma.impl`, once, for all three schedulers:

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
if (TERMINATING && !cascadeStarted) tryFinishDraining();
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
    int carrierCount();                          // builtin: 1 | affine, mpsc: one per carrier
    ThreadFactory vThreadFactory(int i);         // the threads an actor offloads
    default ThreadFactory actorLoopFactory(int i) { return vThreadFactory(i); }   // its bursts
    int currentCarrierId();                      // the calling thread's carrier; builtin: -1
    OptionalInt pinnedCpu();
    OptionalInt clusterOf(int i);                // builtin: empty
    void close();
}
```

`ActorSchedulerProvider` creates one, says whether it `isAvailable()`, and can install itself for
`Carriers.Affine` or `Carriers.Mpsc`. A third-party scheduler plugs in the same way as Looma's own:
implement both, register the provider under
`META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider`, and `core` never learns its
name.

`ActorRuntime` wraps every factory it gets before anything uses it, so that each thread it creates
counts as in-flight work; a scheduler never counts its own.

**The mailbox queue** is the other SPI: `spi.MailboxQueueFactory`, set with
`ActorSystem.Builder.mailboxQueueFactory(...)`, never discovered. The default wraps
`ConcurrentLinkedQueue`. A queue must be at least MPSC and must not block in `poll`.

## Tests

The `netty-loom-scheduler` module runs its tests in several Surefire executions, because the
scheduler is configured once per JVM:

| Execution | Flags | Runs |
| --- | --- | --- |
| `default-test` | 2 carriers | the copied scheduler's own tests |
| `carrier-test` | `affine`, 2 carriers, no stealing | the actor suites, `CarriersCommandLineTest` |
| `workstealing-test` | `affine`, 4 carriers, stealing, `FakeClusterTopology` | the actor suites again, `EventLoopSchedulerWorkStealingTest`, `HomeVirtualThreadTest` |
| `install-test` | no scheduler flag, one JVM per class | `carriers(...)` installing `affine` and `mpsc`, and refusing to once a virtual thread has run |
| `mpsc-test` | `mpsc`, 4 carriers, stealing | the actor suites, and `MpscAffinityTest`: an actor never leaves its carrier, an offloaded thread can be stolen |
| `replaceBuiltinScheduler-test` | upstream's | `ReplaceBuiltinSchedulerTest` |

The executions carry upstream's `-Djdk.traceVirtualThreadLocals=false` and
`--add-opens=java.base/java.lang=ALL-UNNAMED`, so that a failing upstream test can be reproduced
upstream under the same command line.

## Blocking and pinning

A blocking `onReceive` unmounts this actor only: every actor has its own bursts. Offloading still
belongs on `ActorContext.vThreadFactory()`, because a blocked burst stalls this actor's own mailbox.

A *pin* — a native frame on the stack, a JNI or foreign-function call — cannot unmount at all and
holds its carrier. Under the builtin that costs one `ForkJoinPool` worker, which the pool can
compensate for; under `affine` and `mpsc` it stalls every virtual thread that calls that carrier
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

### Why no options

The benchmark compared the setups of each scheduler. Letting an idle carrier steal an actor
cost CPU: the published campaigns show it, `stealing-local` against `stealing-local-home`. Short
runs, not published, also found stealing within an L3 faster than from any carrier, and the MPSC
scheduler faster with poller mode 4 than with mode 3. So each scheduler keeps one setup. Options
would make every user answer questions the benchmark already answered. The old setups, and their
results, are in [the older campaigns](results/README.md#older-campaigns).

That answer comes from one workload, this benchmark. Another workload could prefer another setup.
Looma is a lab, not a framework: it keeps the one setup it measured.

### Where affinity stops being free

- An actor bound to a carrier cannot be helped by an idle neighbour. Stealing the other threads
  gives back part of the idle CPU, but a busy actor still waits for its own carrier.
- Messages across keys are not local: an actor placed by another key's carrier is a cross-carrier
  hop for every caller from a different key.
- A pin holds a whole carrier, with nothing to grow.

### Why an actor is never stolen

Stealing exists to use idle CPU, and pays in locality. The locality worth keeping is the actors'.
The threads an actor offloads, an I/O call or a computation that reads none of its state, lose
nothing by running elsewhere. A stolen actor would bounce: one burst on the thief, the next back
home.

### Why the engine exists once

A supervision or draining bug fixed on one side only would silently invalidate every comparison
between the schedulers. Only the factory differs, so only placement differs.

### Why a virtual thread per burst

A parked virtual thread keeps its stack on the heap, in `StackChunk`s. An actor that kept one
virtual thread for life kept it parked, with its stack, for as long as it waited for a message:
idle actors cost memory, and the more actors, the more memory. A virtual thread per burst ends
when the mailbox is empty, so an idle actor holds no stack.

### Why a burst never yields

A yield is a second way for a scheduler to move a virtual thread. Under the builtin, the yielded
continuation is submitted externally when the worker's local queue is empty, and pushed on that
queue otherwise; either way another worker can run it. Under `affine` and `mpsc` it goes back to
its home carrier. A yielding burst would therefore give `jdk` runs a migration path the other
runs do not have. The price is the one stated above: a busy actor holds its carrier.

### Why a separate module, reached through an SPI

`jdk.virtualThreadScheduler.implClass` is a single, process-wide, **preview** hook of a Loom JDK 28,
and the MPSC scheduler exists only in the JDK of openjdk/loom pull request 227.
`netty-loom-scheduler` is compiled with `--enable-preview` at `release=28` and only exists in the
reactor on a Loom JDK (the root pom's `loom-jdk` profile). `core` and `examples` compile at
`release=25` and run on a stock JDK; neither names a scheduler class.

The copied code under `io/netty/loom/**` keeps upstream's packages, class names, properties and
JFR events, so it stays diffable against upstream; Spotless leaves it alone for the same reason.
Looma's own code beside it is the adapters, in `io.github.pderop.looma.scheduler.netty` and
`io.github.pderop.looma.scheduler.mpsc`, and the tests that need package-private access.
