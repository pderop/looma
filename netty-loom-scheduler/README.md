# `netty-loom-scheduler`

This module is a copy of the upstream carrier-affine virtual thread scheduler, **byte-for-byte
except for two documented patches, one per file** (below), plus the `ActorSchedulerProvider` / `ActorScheduler`
adapter that lets `looma` reach it through the SPI declared in `core`
(`io.github.pderop.looma.spi`).

## Origin

- Project: [`franz1981/Netty-VirtualThread-Scheduler`](https://github.com/franz1981/Netty-VirtualThread-Scheduler)
- Commit: `7e712d8ae96baa7bfc904e3527d119a5b3ac60f9`
- Copied packages:
  - `io.netty.loom.scheduler.*` (including `.jfr`), from `bootstrap/src/{main,test}/java/io/netty/loom/scheduler/**`
  - `io.netty.loom.topology.*`, from `topology/src/{main,test}/java/io/netty/loom/topology/**`
- Licence: Apache License, Version 2.0. See `NOTICE` at the repository root for the
  full attribution block and the Section 4(b) statement.

## The Looma patch: a home queue that stealing never reads

Two copied files are modified: `io/netty/loom/scheduler/EventLoopScheduler.java` and
`io/netty/loom/scheduler/IdleCarrierTracker.java`. The complete diff against the originals is
[`looma-home-queue.patch`](looma-home-queue.patch); each file says so at its top, and every
change in it is marked `Looma:`.

- Each carrier gets a second run queue, the **home queue**. Only that carrier drains it; work
  stealing reads the ordinary run queue only.
- `homeVirtualThreadFactory()` creates virtual threads whose continuations go to the home queue:
  they never run on another carrier, even with work stealing on. `virtualThreadFactory()` is
  unchanged, and its virtual threads stay stealable.
- The carrier alternates between its two queues when it drains them, so neither starves the
  other. Parking checks both; signalling an idle sibling to steal looks at the stealable one
  only.

Looma uses it for `Carriers.affine(n)...actorsStayHome()`: actor loops come from the home
factory, the virtual threads an actor offloads from the ordinary one. The test is
`HomeVirtualThreadTest` (Looma's own file, run by the `workstealing-test` execution).

In `IdleCarrierTracker`, `wakeFirstIdle()` picks the idle carrier to wake as a searcher by
scanning from a random member instead of the first. Scanning from the first wakes the
lowest-numbered idle carrier every time. On the AMD Ryzen 9 9950X, 12 carriers at 300k req/s,
the busiest carrier used 2.45-2.48 times the CPU of the idlest with `hybrid` and 2.16-2.27 times
with `stealing-local`; from a random member, 1.15-1.18 times with both, at the same CPU per
request and a p99.9 10-20% lower. The test is `WakeFromRandomMemberTest` (Looma's own file):
it checks the order of the scan, from every start, with one word and with several.

## The copied files are not edited, and never formatted

Everything under `src/main/java/io/netty/loom/**` and `src/test/java/io/netty/loom/**`
is kept **diffable against upstream**:

- Do not edit these files beyond the patch above. A bug found in them belongs upstream, not
  here. A change to the patch is made in the file, then the patch is regenerated (see below).
- Spotless is configured (root `pom.xml`) to exclude `**/io/netty/loom/**` from both
  formatting and license-header stamping — do not remove that exclude, and do not
  reformat these files by hand either.
- Everything else Looma-specific — the adapter, and the tests that need package-private
  visibility into `io.netty.loom.scheduler` (`EventLoopSchedulerWorkStealingTest`,
  `HomeVirtualThreadTest`, `WakeFromRandomMemberTest`) — lives in its own file,
  carrying the plain Apache-2.0 header rather than the upstream project's copyright line,
  never mixed into a copied file.

## Refreshing from upstream

To pick up an upstream change, re-run the same `cp` this copy was taken with, against
a clone of `franz1981/Netty-VirtualThread-Scheduler` checked out at the commit you want
(`<upstream-clone>` below is that clone's path), **then re-apply the Looma patch**:

```sh
UPSTREAM=<upstream-clone>
DEST=netty-loom-scheduler/src

cp "$UPSTREAM"/bootstrap/src/main/java/io/netty/loom/scheduler/*.java \
   "$DEST"/main/java/io/netty/loom/scheduler/
cp "$UPSTREAM"/bootstrap/src/main/java/io/netty/loom/scheduler/jfr/*.java \
   "$DEST"/main/java/io/netty/loom/scheduler/jfr/
cp "$UPSTREAM"/topology/src/main/java/io/netty/loom/topology/LinuxCarrierTopology.java \
   "$DEST"/main/java/io/netty/loom/topology/

cp "$UPSTREAM"/bootstrap/src/test/java/io/netty/loom/scheduler/{CarrierTopologyTest,ClusterStateTest,IdleCarrierTrackerTest,MpscUnboundedQueueTest,ReplaceBuiltinSchedulerTest}.java \
   "$DEST"/test/java/io/netty/loom/scheduler/
cp "$UPSTREAM"/topology/src/test/java/io/netty/loom/topology/{FakeClusterTopology,LinuxCarrierTopologyTest}.java \
   "$DEST"/test/java/io/netty/loom/topology/

patch -p1 < netty-loom-scheduler/looma-home-queue.patch
```

If the patch no longer applies, port it by hand, then regenerate it from the two versions of the
files:

```sh
S=netty-loom-scheduler/src/main/java/io/netty/loom/scheduler
{ diff -u a/$S/EventLoopScheduler.java b/$S/EventLoopScheduler.java
  diff -u a/$S/IdleCarrierTracker.java b/$S/IdleCarrierTracker.java
} > netty-loom-scheduler/looma-home-queue.patch
```

(`a/` holding the upstream files, `b/` the patched ones.) Then update the commit hash above and
in `NOTICE`, and check with `diff -r` that every other copied file is identical to upstream. Do
not run `mvn spotless:apply` afterwards — the exclude means it will not touch these files, but
running it on the whole repo after a manual edit elsewhere is a habit worth keeping deliberate.

## Why this module needs a separate JDK / preview flag

`NettyScheduler` (`io.netty.loom.scheduler.NettyScheduler`) is the JDK SPI
implementation class installed via
`-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler` — set on
the command line, or by `ActorSystem.builder().carriers(Carriers.affine(n))` through
`NettyActorSchedulerProvider.install`, which sets it before the JVM's first virtual
thread. That
SPI is a **preview feature of a Loom-enabled JDK 28+**, not of stock JDK 25. That is
why this module alone compiles at `release=28` with `--enable-preview` (both on the
compiler and on Surefire), while `core` and `examples` compile at
`release=25` without it — see the root `pom.xml`'s `loom-jdk` profile
(`<jdk>[28,)</jdk>`), which is the only thing that adds this module to the reactor.

## Flat-classpath / system-classloader constraint

The JDK resolves the `implClass` named above via
`Class.forName(cn, true, ClassLoader.getSystemClassLoader())`, called from
`VirtualThread.<clinit>`. That means `NettyScheduler` — and everything it
transitively loads, i.e. this whole module — **must be visible to the system
classloader**, not merely to whatever application classloader loads `looma`
itself. In practice: this jar (and its runtime dependencies) must sit on the flat
`-cp`/`-classpath` the JVM was launched with, or on `-Xbootclasspath/a:` under a
container that isolates the application classloader (Spring Boot's repackaged jar,
Quarkus, OpenLiberty). A module that is merely a `compile`-scope Maven dependency of
some other jar, unpacked into a nested classloader, will not be found.

## Building this module in isolation

`mvn -pl netty-loom-scheduler test` (i.e. **without** `-am`) resolves its `looma-core`
and `looma-core` `test-jar` dependencies from the local Maven repository (`~/.m2`)
rather than from the reactor, which may be **stale** relative to the `core` sources
currently on disk. Run `mvn -pl netty-loom-scheduler -am test` (or `mvn install` on
`core` first) if you need a guaranteed-fresh `core`.
