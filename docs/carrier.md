# The carrier-affine scheduler

`io.github.pderop.looma.scheduler.netty`, in `netty-loom-scheduler/src/main/java`, over the vendored
`io.netty.loom.*` in the same module.

Every actor has one **home carrier thread** for life. Its loop virtual thread — and every thread it
creates through `ActorContext.vThreadFactory()` — is started from that carrier's factory. With
`io.netty.loom.topology.LinuxCarrierTopology` installed, each carrier is pinned to one CPU with
`sched_setaffinity`, so a home carrier is a home *core*: see the rationale in the
[README](../README.md#why-carrier-affinity).

This is the same locality model
[Netty-VirtualThread-Scheduler](https://github.com/franz1981/Netty-VirtualThread-Scheduler) uses for Netty
event loops, applied to a plain actor system with no Netty dependency at all.

There is only one `ActorSystem` implementation, `DefaultActorSystem` in `core`. This page is about
the `ActorScheduler` it discovers through `ServiceLoader` when the scheduler is installed; without it
you get the [JDK builtin scheduler](jdk.md) and nothing below applies.

## What this scheduler contributes: placement, and nothing else

`DefaultActorSystem` runs the *same* actor engine either way — literally the same
`io.github.pderop.looma.impl` classes, not a second copy of the algorithm: one virtual thread per
actor, drain-then-park, plus the hierarchy, supervision, `ask` and graceful stop. That engine is
documented once, in [`jdk.md`](jdk.md), and is not repeated here.

What differs is only *which factory* starts those virtual threads. Each actor's loop is started from
`vThreadFactory(homeCarrierId)`, which pins the continuation to the actor's own home carrier — and so
is every thread the actor creates for blocking work. Under the JDK builtin the same loop starts from
a plain `Thread.ofVirtual()` factory, with a single carrier index.

Placement follows two **default** rules, each overridable per spawn through
`SpawnOptions.placedOn(Placement)`:

- a **root** actor (`ActorSystem.spawn`) is assigned a carrier round-robin over the group —
  `Placement.roundRobin()`;
- a **child** (`ActorContext.spawn`) is assigned its parent's home carrier —
  `Placement.inherit()`. A parent-to-child `tell` and the reply back up therefore never pay a
  cross-carrier hop, and a whole subtree of actors plus their blocking work shares one core's
  locality.

See [`api.md`](api.md#8-placement) for the caller-facing side of this.

## Home carrier vs. running carrier

Being work-stolen moves where a continuation *runs*; it never moves an actor's home carrier, which is
fixed for its lifetime.

`ActorSystem.homeCarrierIdOf(ActorRef)` exposes that placement and is the only sound basis for an
affinity assertion — see `CarrierAffinityTest`. The same distinction exists inside the scheduler:
`ActorScheduler.currentCarrierId()` maps to `EventLoopScheduler.currentScheduler()` (the *home*
carrier), never to `currentRunningScheduler()`.

## Work stealing

An actor-loop continuation is just another item in its carrier's run queue, so it is eligible for
exactly the same push/pull stealing protocol as everything else queued there: a **queued** (not yet
mounted) loop can be stolen and run by an idle sibling carrier in the same cluster. No special-casing
was needed for that to hold — it falls straight out of reusing the vendored engine as-is.

A loop that is already **mounted** is never preempted mid-envelope. The granularity is the actor
loop, not the individual message.

Stealing is off by default, and that is a measurement rather than a preference. Strict affinity
leaves a carrier's backlog stranded while neighbours idle, which is a fixed utilization handicap;
stealing removes the handicap and gives back some locality. Which one wins depends on how large the
per-message work is relative to the constant dispatch saving — see [`performance.md`](performance.md)
for a campaign where the answer flips with the size of the actor's state.

`EventLoopSchedulerWorkStealingTest` exercises stealing directly against the vendored engine;
`CarrierAffinityTest` covers the actor-level affinity guarantees, which hold unconditionally since
they concern which carrier a loop is *assigned* to, not which one happens to run it.

## Why it is a separate module, reached through an SPI

`-Djdk.virtualThreadScheduler.implClass` is a single, **process-wide** JDK hook: the JDK resolves it
once via `Class.forName(cn, true, ClassLoader.getSystemClassLoader())` during
`VirtualThread.<clinit>`, and only one implementation can be installed per JVM. It is also a
**preview** API, which is what forces the split:

- `netty-loom-scheduler` is compiled with `--enable-preview` at `release=28` and only exists in the
  reactor on a Loom JDK (the root pom's `loom-jdk` profile, `<jdk>[28,)</jdk>`);
- `core` and `examples` compile at `release=25` and run on a stock JDK. Neither names a scheduler
  class: `core` declares `io.github.pderop.looma.spi.ActorScheduler` / `ActorSchedulerProvider`, and
  `impl.ActorSchedulers` finds an implementation through `ServiceLoader` or falls back.

The copy of upstream under `io/netty/loom/**` is **byte-for-byte**, not a port: same package, same
class names, no renamed properties, no renamed JFR events — so it stays diffable against upstream and
a bug found in it belongs upstream, not here. Spotless is configured to leave those files alone for
the same reason. Everything project-specific lives beside it in
`io.github.pderop.looma.scheduler.netty` (the adapter), plus one test that needs package-private
visibility. The exact upstream commit and the refresh procedure are in
[`netty-loom-scheduler/README.md`](../netty-loom-scheduler/README.md) and the repository
[`NOTICE`](../NOTICE).

A third-party scheduler is added the same way: implement both SPI interfaces, register the provider
under `META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider`, and `core` never learns
its name.

### System classloader constraint

`looma-netty-scheduler.jar` must be visible to the **system** classloader at runtime, not merely to
an application classloader: that is the loader `Class.forName` above resolves against. Under plain
`-cp` — a Surefire fork, the `scripts/` launchers — this is automatic. It would only become a problem
under a container that interposes its own classloader (a Spring Boot fat jar, Quarkus, OpenLiberty),
which is out of scope here.

## Configuration — the `io.netty.loom.*` system properties

These are **upstream's** property names, unchanged, because the scheduler is upstream's code
unchanged. Read directly out of the vendored source (`EventLoopSchedulerGroup`, `EventLoopScheduler`,
`NettyScheduler`, `LinuxCarrierTopology`):

| Property | Default | Meaning |
| --- | --- | --- |
| `io.netty.loom.schedulers` | `availableProcessors()` | Carrier pool size (`EventLoopSchedulerGroup`). |
| `io.netty.loom.resumed.continuations` | `1024` | Initial MPSC run-queue capacity per carrier. |
| `io.netty.loom.topology` | none | Fully-qualified `CarrierTopology` implementation for CPU/L3-aware carrier placement (must be system-classloader-visible, public no-arg constructor). Set to `io.netty.loom.topology.LinuxCarrierTopology` for the bundled Linux implementation, or to a test double such as `FakeClusterTopology`. **With no topology set, carriers are not pinned to CPUs at all** and form one flat cluster (`StealScope.GLOBAL`). |
| `io.netty.loom.workstealing.enabled` | `false` | Push/pull work stealing between carriers (`EventLoopScheduler`). Deliberately **not** exposed on `ActorSystem.Builder`: the carrier pool is a JVM-wide singleton created during `VirtualThread.<clinit>`, so a builder could only flip it process-wide for every actor system at once — and making it settable at all costs the flag its `static final`-ness, which is what lets the JIT fold it and delete the whole stealing path from the compiled carrier loop. |
| `io.netty.loom.workstealing.scope` | `GLOBAL` | Read by `LinuxCarrierTopology` only (i.e. only takes effect when `io.netty.loom.topology` names it, or another topology that consults it). `CLUSTER_LOCAL` restricts stealing to carriers sharing an L3. |
| `io.netty.loom.yield.us` | `50` | Continuation-drain time slice per carrier loop pass, in **microseconds** (`EventLoopScheduler.YIELD_DURATION_NS`). |
| `io.netty.loom.replaceBuiltinScheduler` | `false` | Upstream's mode where *every* virtual thread in the JVM, not just those created from a carrier's factory, runs on the carrier pool (`NettyScheduler`). Not used by this project's own runs; covered by `ReplaceBuiltinSchedulerTest`. |

Looma reads no property of its own. Which scheduler a process gets is JVM flags and classpath: with
`implClass` set, discovery finds this adapter; without it, the [JDK builtin](jdk.md) is what is left.
A mailbox-queue replacement is `ActorSystem.Builder.mailboxQueueFactory(...)`, not a property.

## Run recipe

Every way this project runs carrier-affine code needs `--enable-preview`, the scheduler SPI flag, and
FFM native access for `LinuxCarrierTopology`.

`scripts/run-hello.sh` (`io.github.pderop.looma.examples.hello.Main`), 2 carriers, no topology:

```
--enable-preview --enable-native-access=ALL-UNNAMED
-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler
-Dio.netty.loom.schedulers=2
```

`scripts/run-http-bench.sh` adds the topology, so the carriers are actually pinned:

```
-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology
-Dio.netty.loom.schedulers=<one per CPU of the cpuset>
```

The Surefire executions additionally carry upstream's `-Djdk.traceVirtualThreadLocals=false` and
`--add-opens=java.base/java.lang=ALL-UNNAMED` verbatim, so that a test failing here can be reproduced
upstream under an identical command line; the scripts, which are ours, do not.

- `carrier-test` (`netty-loom-scheduler/pom.xml`): 2 carriers, stealing off.
- `workstealing-test`: 4 carriers, `-Dio.netty.loom.workstealing.enabled=true
  -Djdk.trackAllThreads=true -Dio.netty.loom.topology=io.netty.loom.topology.FakeClusterTopology`.
  It is also the only execution that runs `EventLoopSchedulerWorkStealingTest`, whose first line is
  `assumeTrue(EventLoopScheduler.WORK_STEALING_ENABLED)` — read at class init, so anywhere else it
  would self-skip and report green while testing nothing.

Nothing in the build has to arrange for the right JDK: Maven itself runs on the Loom JDK
(`JAVA_HOME`, see `.sdkmanrc`), so every `javac` and every forked Surefire JVM already is the right
one. The scripts resolve the same `JAVA_HOME` and probe it for `Thread.VirtualThreadScheduler` before
launching anything (`scripts/loom-jdk.sh`) — the one place a stock JDK 28 is rejected by name rather
than by a confusing failure much later.

## Carrier lifecycle and `shutdown()`

Carriers are permanent, JVM-wide daemon threads backing `-Djdk.virtualThreadScheduler.implClass` for
the whole process: created once, on first use, and never torn down. An `ActorSystem` neither stops
nor can stop any of them, and `NettyActorScheduler.close()` is a no-op for that reason.

That does **not** make `shutdown()` a mere flag. It starts the engine's graceful cascading stop,
identical to the one the [JDK builtin](jdk.md) runs: every root actor stopped, each mailbox drained,
the cascade descending to children, `postStop` unwinding back up. `awaitTermination(timeout, unit)`
returns once every actor is unregistered **and** every unit of in-flight work has finished — actor
loop virtual threads and every thread created through `ActorContext.vThreadFactory()`.
