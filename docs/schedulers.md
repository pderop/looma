# The schedulers, and the engine

There is one actor engine and one `ActorSystem` implementation, `DefaultActorSystem`, which no
caller names: `ActorSystem.builder()` hands it out. An actor runs on a virtual thread only while
it has messages: one per burst. What varies is which `ActorScheduler` supplies the factory that
starts those threads, and two ship with Looma:

| | Where | Carriers | Home carrier per actor |
| --- | --- | --- | --- |
| JDK builtin (`JdkActorScheduler`) | `core`, `io.github.pderop.looma.impl` | 1 (the JDK's `ForkJoinPool`) | no |
| carrier-affine (`NettyActorScheduler`) | `netty-loom-scheduler`, `io.github.pderop.looma.scheduler.netty` | one per carrier thread | yes, for life |

The carrier-affine scheduler is a copy of the
[Netty-VirtualThread-Scheduler](https://github.com/franz1981/Netty-VirtualThread-Scheduler) of
Francesco Nigro, the locality model it uses for Netty event loops applied to an actor system with no
Netty dependency.

## Which scheduler a process gets

`ActorSystem.Builder.build()` resolves, in order:

1. an explicit `scheduler(ActorScheduler)` — used by tests, not by applications;
2. otherwise `carriers(Carriers)`, which installs the scheduler it describes;
3. otherwise `ServiceLoader` discovery of an `ActorSchedulerProvider` whose `isAvailable()` is
   `true` — whatever the JVM flags installed;
4. otherwise `JdkActorScheduler`.

Setting both 1 and 2 is refused at `build()`.

### In code: `carriers(...)`

```java
ActorSystem system = ActorSystem.builder()
        .carriers(Carriers.affine(8).pinned().workStealing(Carriers.WorkStealing.GLOBAL))
        .build();

// the builtin, sized the same, as a control:
ActorSystem control = ActorSystem.builder().carriers(Carriers.builtin(8)).build();
```

| `Carriers` | Meaning | Properties it sets |
| --- | --- | --- |
| `affine(n)` | the carrier-affine scheduler, `n` carriers | `jdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler`, `io.netty.loom.schedulers=n`, `jdk.pollerMode=3` (one read poller per carrier, kept on it) |
| `.pinned()` | each carrier pinned to one CPU of the process's affinity mask, grouped by L3 (Linux) | `io.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology`; unpinned requires it absent |
| `.workStealing(OFF)` | no stealing (the default) | `io.netty.loom.workstealing.enabled=false`; `scope` required absent |
| `.workStealing(GLOBAL / CLUSTER_LOCAL)` | stealing between any carriers / only within an L3 | `io.netty.loom.workstealing.enabled=true`, `io.netty.loom.workstealing.scope=…` |
| `.actorsStayHome()` | stealing never takes an actor loop, only the threads actors offload | none: Looma's adapter creates the actor loops on each carrier's home queue |
| `builtin(n)` | the JDK builtin, sized | `jdk.virtualThreadScheduler.parallelism=n`; `implClass` required absent |

`build()` sets the properties, then checks that they took. That only works **before the JVM's first
virtual thread**: the JDK resolves its scheduler once, while initialising virtual threads. So
`build()` throws `IllegalStateException` rather than run on something else when:

- a property it sets already holds another value (a `-D` flag), or is set where it must be absent;
- the command line already installs the scheduler with `-Djdk.virtualThreadScheduler.implClass`;
- for `affine`, a virtual thread already ran under the builtin, or no provider can install one (no
  scheduler module on the classpath, or no `--enable-preview`);
- an earlier system in the JVM was built with another pool: affine against builtin, or another
  count, pinning or stealing. Systems on the same pool may differ in `.actorsStayHome()` only:
  it is a choice of each system, not of the pool.

One case goes undetected: `builtin(n)` after a virtual thread already ran — the JDK has sized its
pool, and `n` is silently not what is in force.

`--enable-preview`, and `--enable-native-access=ALL-UNNAMED` for `.pinned()`, are launch flags, not
properties, and stay on the command line.

### As JVM flags

Without `carriers(...)`, the scheduler comes from the flags and the classpath. The properties are
upstream's, unchanged:

| Property | Default | Meaning |
| --- | --- | --- |
| `jdk.virtualThreadScheduler.implClass` | none | `io.netty.loom.scheduler.NettyScheduler` installs the carrier-affine scheduler, process-wide; without it, discovery finds nothing and the builtin is used |
| `io.netty.loom.schedulers` | `availableProcessors()` | carrier pool size |
| `io.netty.loom.topology` | none | a `CarrierTopology` class; `io.netty.loom.topology.LinuxCarrierTopology` pins carriers to CPUs and groups them by L3. **With none, carriers are not pinned** and form one cluster |
| `io.netty.loom.workstealing.enabled` | `false` | work stealing between carriers |
| `io.netty.loom.workstealing.scope` | `GLOBAL` | `CLUSTER_LOCAL` restricts stealing to one cluster; only read by a topology |
| `io.netty.loom.yield.us` | `50` | how long a carrier drains its queues before checking for I/O and stealing, in µs |
| `io.netty.loom.resumed.continuations` | `1024` | initial run-queue capacity per carrier |
| `io.netty.loom.replaceBuiltinScheduler` | `false` | run *every* virtual thread of the JVM on the carriers; not used by Looma |
| `jdk.virtualThreadScheduler.parallelism`, `maxPoolSize` | JDK defaults | size of the builtin |

The scheduler logs one line at start-up, for instance `topology=LinuxCarrierTopology carriers=8
clusters=2 stealScope=CLUSTER_LOCAL workStealing=true`. With `clusters=1`, `CLUSTER_LOCAL` is
`GLOBAL`; with one carrier per cluster, it is no stealing at all.

Looma reads no system property of its own.

### On the classpath

Either way, `looma-netty-scheduler` must be on the classpath, or there is no provider: discovery
falls back to the builtin and `carriers(affine)` throws.

```xml
<dependency>
    <groupId>io.github.pderop</groupId>
    <artifactId>looma-netty-scheduler</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

The jar must be visible to the **system** classloader: the JDK loads `implClass` with
`Class.forName(cn, true, ClassLoader.getSystemClassLoader())`. Under a plain `-cp` launch (Surefire,
the `scripts/`) that is automatic; under a container that interposes its own classloader (a Spring
Boot fat jar, Quarkus) it is not.

A provider that fails to link — a preview-compiled class on a stock JDK — is caught and skipped by
discovery, which keeps looking.

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
goes from 0 to 1, the `tell` starts a virtual thread from the actor's carrier's loop factory: a
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

`shutdown()` starts a graceful cascading stop, the same under both schedulers: every root stopped,
each mailbox drained, the cascade descending to children, `postStop` unwinding back up.
`awaitTermination` returns once every actor is unregistered **and** every unit of in-flight work
has finished: the loops, and every thread created through `ActorContext.vThreadFactory()`.

Neither scheduler's `close()` stops anything. The builtin owns no pool; the carriers are JVM-wide
daemon threads, created on first use and never torn down.

## The SPI

```java
public interface ActorScheduler extends AutoCloseable {
    int carrierCount();                          // builtin: 1 | affine: one per carrier
    ThreadFactory vThreadFactory(int i);         // the threads an actor offloads (and its loop, by default)
    default ThreadFactory actorLoopFactory(int i) { return vThreadFactory(i); }
    int currentCarrierId();                      // the calling thread's HOME carrier; builtin: -1
    OptionalInt pinnedCpu();
    OptionalInt clusterOf(int i);                // builtin: empty
    void close();
}
```

`ActorSchedulerProvider` creates one, says whether it `isAvailable()`, and can `install(Carriers.Affine)`
itself. A third-party scheduler plugs in the same way as Looma's own: implement both, register the
provider under `META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider`, and `core`
never learns its name.

`ActorRuntime` wraps every factory it gets before anything uses it, so that each thread it creates
counts as in-flight work; a scheduler never counts its own.

**The mailbox queue** is the other SPI: `spi.MailboxQueueFactory`, set with
`ActorSystem.Builder.mailboxQueueFactory(...)`, never discovered. The default wraps
`ConcurrentLinkedQueue`. A queue must be at least MPSC and must not block in `poll`.

## The JDK builtin

Every virtual thread it hands out — the loop and the offloaded ones — is a plain `Thread.ofVirtual()`
on the JDK's default scheduler. You get it when `carriers(Carriers.builtin(n))` asks for it, or
when, without `carriers(...)`:

- `looma-netty-scheduler` is not on the classpath (it is not even built on a JDK before 28);
- it is, but `-Djdk.virtualThreadScheduler.implClass` was not passed;
- it is, on a stock JDK without `--enable-preview`, and its provider fails to link.

`Placement` and `SpawnOptions` are accepted, but `carrierCount()` is 1: every placement resolves to
that carrier, and `Placement.carrier(i)` for any `i > 0` throws at spawn.

## The carrier-affine scheduler

Every actor has one **home carrier** for life, chosen at spawn. Its bursts, and every thread it
creates through `ActorContext.vThreadFactory()`, are started from that carrier's factories. With `.pinned()`, each carrier is pinned to one CPU, so a home carrier is a home core.

### Placement

Two defaults, each overridable per spawn with `SpawnOptions.placedOn(Placement)`:

- a **root** actor is placed round-robin over the carriers (`Placement.roundRobin()`);
- a **child** gets its parent's carrier (`Placement.inherit()`): a parent and its children, and
  their offloaded work, share one core, and a message between them never crosses carriers.

A pool (`SpawnOptions.pooled(n)`) applies its placement to each actor: `inherit()` or
`carrier(i)` keeps the pool on one carrier, `roundRobin()` spreads it one actor per carrier.

Work that belongs to a key is placed by the key, with `carrier(i)`: the actor for key `k` on
carrier `k % carrierCount()`, shard `i` of a service on carrier `i % carrierCount()`. When the
carrier count divides the shard count, the shard serving key `k` is on that same carrier, and the
actors serving one key share a core. See [`api.md`](api.md#8-placement).

### Home carrier and running carrier

Work stealing moves where a continuation *runs*; it never moves an actor's home carrier.
`ActorSystem.homeCarrierIdOf(ActorRef)` reports the home carrier and is the only sound basis for an
affinity assertion (`CarrierAffinityTest`); it rejects a pool reference. Likewise,
`ActorScheduler.currentCarrierId()` is the home carrier (`EventLoopScheduler.currentScheduler()`),
never the running one (`currentRunningScheduler()`).

### Work stealing

Each carrier has a run queue of continuations. With stealing on, an idle carrier takes a **queued**
continuation from a sibling's run queue: any sibling under `GLOBAL`, only one in its own cluster
(L3) under `CLUSTER_LOCAL`. A mounted loop is never preempted; the granularity is a continuation,
not a message. A stolen continuation runs once on the thief: its next continuation is routed to its
home carrier again.

**`actorsStayHome()`** keeps the actor loops out of that. Each carrier then has a second queue, the
home queue, which only that carrier drains and no stealer reads. Actor loops are created on it
(`ActorScheduler.actorLoopFactory`), while the threads an actor offloads come from the ordinary
factory and stay stealable. The carrier alternates between its two queues. This is one of the two
changes Looma makes to its copy of the scheduler — see
[`netty-loom-scheduler/README.md`](../netty-loom-scheduler/README.md).

Stealing is off by default.

### Tests

The `netty-loom-scheduler` module runs its tests in several Surefire executions, because the
scheduler is configured once per JVM:

| Execution | Flags | Runs |
| --- | --- | --- |
| `default-test` | 2 carriers | upstream's own tests, and `WakeFromRandomMemberTest` |
| `carrier-test` | 2 carriers, no stealing | the adapter's tests, `CarriersCommandLineTest` |
| `workstealing-test` | 4 carriers, stealing, `FakeClusterTopology` | the adapter's tests again, `EventLoopSchedulerWorkStealingTest`, `HomeVirtualThreadTest` |
| `install-test` | no scheduler flag, one JVM per class | `carriers(...)` installing the scheduler, and refusing to once a virtual thread has run |
| `replaceBuiltinScheduler-test` | upstream's | `ReplaceBuiltinSchedulerTest` |

The executions carry upstream's `-Djdk.traceVirtualThreadLocals=false` and
`--add-opens=java.base/java.lang=ALL-UNNAMED`, so that a failing upstream test can be reproduced
upstream under the same command line.

## Blocking and pinning

A blocking `onReceive` unmounts this actor only: every actor has its own loop. Offloading still
belongs on `ActorContext.vThreadFactory()`, because a blocked loop stalls this actor's own mailbox.

A *pin* — a native frame on the stack, a JNI or foreign-function call — cannot unmount at all and
holds its carrier. Under the builtin that costs one `ForkJoinPool` worker, which the pool can
compensate for; under the carrier-affine scheduler it stalls every virtual thread that calls that
carrier home. `synchronized` no longer pins ([JEP 491](https://openjdk.org/jeps/491), JDK 24).

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
state is written and read on one core for the whole life of the actor; children and offloaded work
land there too.

Affinity preserves the **L2**, not the L1: an actor's state rarely fits a core's L1d, and whatever
else the carrier ran meanwhile has flushed it. Hence the claim the
[benchmark](benchmark.md) is built to test: **an affinity advantage must grow with the per-actor
state while it fits a core's L2, and vanish once it no longer does.**

### Where affinity stops being free

- An actor bound to a carrier cannot be helped by an idle neighbour: a fixed utilization handicap,
  which only pays while the locality saving covers it. Stealing is the release valve, and gives
  locality back — which way the trade goes depends on the workload, hence off by default and
  measured, not assumed.
- Messages across keys are not local: an actor placed by another key's carrier is a cross-carrier
  hop for every caller from a different key.
- A pin holds a whole carrier, with nothing to grow.

### Why `actorsStayHome()`

Stealing exists to use idle CPU, and pays in locality; the locality worth keeping is the actors'.
The threads an actor offloads — an I/O call, a computation that reads none of its state — lose
nothing by running elsewhere. Without it, a stolen actor bounces: one continuation on the thief,
the next back home.

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
queue otherwise; either way another worker can run it. Under the carrier-affine scheduler it goes
back to its home carrier. A yielding burst would therefore give `jdk` runs a migration path the
affine runs do not have. The price is the one stated above: a busy actor holds its carrier.

### Why a separate module, reached through an SPI

`jdk.virtualThreadScheduler.implClass` is a single, process-wide, **preview** hook of a Loom JDK 28.
`netty-loom-scheduler` is compiled with `--enable-preview` at `release=28` and only exists in the
reactor on a Loom JDK (the root pom's `loom-jdk` profile). `core` and `examples` compile at
`release=25` and run on a stock JDK; neither names a scheduler class.

The copied code under `io/netty/loom/**` keeps upstream's packages, class names, properties and
JFR events, so it stays diffable against upstream; Spotless leaves it alone for the same reason.
Looma's own code beside it is the adapter in `io.github.pderop.looma.scheduler.netty` and the
tests that need package-private access.
