# `netty-loom-scheduler`

**This module is no longer built, since 2026-10-31.** It stays in the repository for a possible
patch upstream. `Carriers.mpsc(n)` runs the JDK's MPSC scheduler instead:
[`mpsc-scheduler`](../mpsc-scheduler/README.md).

It holds a copy of Francesco Nigro's carrier-affine virtual thread scheduler, byte for byte except
for two patches, and the adapter that made it `Carriers.affine(n)`. When it was built, it needed a
Loom JDK 28 with `--enable-preview`. The JDK loaded the scheduler named by
`-Djdk.virtualThreadScheduler.implClass` from the system class loader, so its jar had to be on the
flat class path.

## Origin

- Project: [`franz1981/Netty-VirtualThread-Scheduler`](https://github.com/franz1981/Netty-VirtualThread-Scheduler)
- Commit: `7e712d8ae96baa7bfc904e3527d119a5b3ac60f9`
- Copied packages:
  - `io.netty.loom.scheduler.*` (including `.jfr`), from `bootstrap/src/{main,test}/java/io/netty/loom/scheduler/**`
  - `io.netty.loom.topology.*`, from `topology/src/{main,test}/java/io/netty/loom/topology/**`
- License: Apache License, Version 2.0. The attribution and the Section 4(b) statement are in
  `NOTICE`, at the repository root.

## The two patches

The full diff is [`looma-home-queue.patch`](looma-home-queue.patch). In the two files, each change
is marked `Looma:`.

- `EventLoopScheduler`: each carrier gets a second queue, the **home queue**. Only that carrier
  drains it: stealing never reads it. `homeVirtualThreadFactory()` sends its virtual threads there,
  so they never leave their carrier. The carrier alternates between its two queues. Looma gave the
  home factory to the mailbox threads, the ordinary one to offloaded threads. Test:
  `HomeVirtualThreadTest`.
- `NettyScheduler`: the constructor starts the JDK's pollers before the carriers. Started later
  from a pinned carrier, they would have kept its one CPU.

## The copied files

- Everything under `src/{main,test}/java/io/netty/loom/**` stays diffable against upstream: no
  edit beyond the patch, no formatting. Spotless excluded `**/io/netty/loom/**`.
- Looma's own files (the adapter, the tests that need package access, the JMH benchmarks run by
  `scripts/run-wake-jmh.sh`) carry the plain Apache-2.0 header.

## Refreshing from upstream

Copy the files again from a clone of the upstream project at the wanted commit, then apply the
patch:

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

If the patch no longer applies, port it by hand, then regenerate it, with the upstream files in
`a/` and the patched ones in `b/`:

```sh
S=netty-loom-scheduler/src/main/java/io/netty/loom/scheduler
{ diff -u a/$S/EventLoopScheduler.java b/$S/EventLoopScheduler.java
  diff -u a/$S/NettyScheduler.java b/$S/NettyScheduler.java
} > netty-loom-scheduler/looma-home-queue.patch
```

Then update the commit above and in `NOTICE`, and check with `diff -r` that every other copied
file is identical to upstream.
