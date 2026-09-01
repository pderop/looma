# The JDK builtin scheduler, and the engine

`io.github.pderop.looma.impl.JdkActorScheduler`, in `core/src/main/java` — package-private, like
everything in that package except `DefaultActorSystem`.

There is **one** `ActorSystem` implementation, `DefaultActorSystem`, which no caller ever names:
`ActorSystem.builder()` hands it out. Every actor is **one virtual thread for life**. What varies is
which `ActorScheduler` supplies the factory that starts that loop, and `JdkActorScheduler` is the one
you get when no other is available: one logical carrier, no affinity. Every virtual thread it hands
out — the actor loop itself, and the ones `ActorContext.vThreadFactory()` creates — is a plain
`Thread.ofVirtual()` scheduled by the JDK's default virtual-thread scheduler.

That is the control the [carrier-affine](carrier.md) configuration is measured against. The engine is
identical; only placement differs. **This page documents the engine once, in JDK terms**, and
`carrier.md` cross-links here rather than repeating it.

## When you get it

`ActorSystem.Builder.build()` resolves its scheduler in this order:

1. an explicit `scheduler(ActorScheduler)` — used by tests and benchmark controls;
2. otherwise `ServiceLoader` discovery of an `ActorSchedulerProvider` (`impl.ActorSchedulers`);
3. otherwise `JdkActorScheduler`.

So you get the JDK builtin when **any** of these is true — and none of them is a flag of this
project:

- the `looma-netty-scheduler` jar is not on the classpath (it is not even built on a JDK before 28);
- it is on the classpath but `-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler`
  was not passed, so `NettyScheduler.isAvailable()` reports `false`;
- it is on the classpath of a stock JDK 25 without `--enable-preview`, so its provider class fails to
  link and discovery skips it (a `LinkageError`, caught in `ActorSchedulers` — which skips that one
  provider and keeps looking, rather than abandoning discovery).

There is no builder knob to "force the JDK one". `scripts/run-http-bench.sh`'s `jdk` configuration is
exactly this: the same cpuset and the same `jdk.virtualThreadScheduler.parallelism` /
`maxPoolSize` as the carrier runs, no preview flag, no `implClass`.

Looma reads no system property of its own, and the HTTP example reads none either. Everything
`io.netty.loom.*` belongs to the carrier-affine scheduler, not here.

## One engine, both schedulers

Every scheduler runs the *same* actor engine, in `io.github.pderop.looma.impl`:

- **`ActorRuntime`** — the state one system shares across every actor it hosts: the path registry,
  the root list, in-flight accounting, shutdown and termination, the mailbox-queue factory.
- **`ActorCell`** — one actor's own state: mailbox, loop virtual thread, hierarchy, lifecycle hooks,
  supervision. It implements `ActorContext` directly.
- **`ActorRefImpl`** — the `ActorRef` handed out for a live cell; **`PromiseRef`** — the single-use,
  unregistered reply channel `ask` passes as the message's `sender`.
- **`Envelope`** — one mailbox entry, either a message or a lifecycle step.
- **`ActorSchedulers`** — the `ServiceLoader` lookup that decides which scheduler a process gets.

There is no 0/1 scheduling gate and no per-message task. `tell` / `enqueue` is:

1. `mailbox.offer(envelope)` (lifecycle envelopes stay exempt from the terminating-message drop);
2. `LockSupport.unpark(loopThread)`.

The loop is started once at spawn, from the resolved carrier's `vThreadFactory`:

```
while (state != STOPPED) {
  envelope = mailbox.poll();
  if (envelope != null) { runEnvelope; interrupted(); yield every 64; continue; }
  if (STOPPED) break;
  if (TERMINATING && !cascadeStarted) { tryFinishDraining(); continue; }
  LockSupport.park();
  Thread.interrupted();
}
```

Serialization *is* the virtual thread itself — that is the whole mechanism, and why an actor's fields
need no synchronization. Cooperative `Thread.yield()` every 64 drained envelopes keeps a CPU-bound
mailbox from holding a carrier indefinitely; virtual threads are not time-sliced, so this is the only
lever that exists against a busy actor. Parking on an empty mailbox is itself a yield point. Whether
64 envelopes is the right trigger, or whether it should be a duration, is still open.

`preStart` is the first lifecycle envelope, enqueued before `Thread.start()`. `runFinalStop` is the
last; then the loop sees `STOPPED` and exits. A restart keeps the same loop thread and the same
queue, and installs a new actor POJO.

Under the carrier-affine scheduler the **same** loop runs on a virtual thread created from that
carrier's factory, so the continuation is home-affine. The carrier's MPSC run queue holds actor-loop
continuations, not per-message tasks. The A/B is locality only.

The lost-wakeup case is handled by the sticky unpark permit: offer, then unpark; park only after a
failed poll. A `tell` before `start()` assigns `loopThread` and then starts the thread, so
`unpark(null)` never happens — envelopes offered while `loopThread` is still null are seen on the
first drain.

That this engine exists exactly once is deliberate: a supervision or draining bug fixed on one side
only would silently invalidate every JDK-versus-carrier comparison this project exists to make.

## The dispatcher and the scheduler SPI

`ActorDispatcher` is a cached factory plus a carrier id. It does not schedule envelopes.

The seam is `io.github.pderop.looma.spi.ActorScheduler`:

```java
public interface ActorScheduler extends AutoCloseable {
    int carrierCount();                    // JDK: 1  | carrier-affine: one per carrier thread
    ThreadFactory vThreadFactory(int i);   // JDK: a plain, unpinned VT factory
    int currentCarrierId();                // JDK: -1 (this thread belongs to no carrier)
    OptionalInt clusterOf(int i);          // JDK: empty (no topology)
    void close();
}
```

`Placement` and `SpawnOptions` are accepted here exactly as on the carrier-affine side — this
scheduler does not special-case or reject them — but `carrierCount()` is `1`, so every valid
placement resolves to the same carrier: `Placement.roundRobin()` always returns it, and so does
`Placement.inherit()` on a child, because the parent has nowhere else to be. Only
`Placement.carrier(0)` is in bounds; any other id throws `IllegalArgumentException` at spawn, from
the same check the carrier-affine side runs against a larger `carrierCount()`.

## Mailbox queue SPI

Each actor's envelope queue is created at spawn by a `spi.MailboxQueueFactory`. The default,
`ConcurrentLinkedMailboxQueue`, wraps `ConcurrentLinkedQueue`.

That factory is **not** ServiceLoader-discovered, unlike the scheduler: there is always a working
default, and two discovered factories would be ambiguous. A replacement is set with
`ActorSystem.Builder.mailboxQueueFactory(...)`.

The queue must be MPSC at minimum (many `tell`s, one consumer — the actor's virtual thread) and must
not block in `poll`: the actor parks itself. `size()` and iterators are not on the interface. No MPSC
implementation ships in `core`; one (JCTools, or the vendored `MpscUnboundedQueue`) plugs in through
the factory.

## In-flight work and `shutdown()`

`ActorSystem.shutdown()` starts the same graceful cascading stop on both schedulers: every root actor
is stopped, each mailbox drains, the cascade descends to children, `postStop` unwinds back up.
`awaitTermination` returns once every actor is unregistered **and** every unit of in-flight work has
finished, counting both the actor loop virtual threads and every thread created through
`ActorContext.vThreadFactory()`.

`JdkActorScheduler.close()` is a no-op: this scheduler owns no pool.

## Blocking `onReceive`

A blocking `onReceive` unmounts **this actor only**. Neighbours have loops of their own — which is
different from a shared run loop, and is also why offloading still belongs on
`ActorContext.vThreadFactory()`: blocking the loop stalls this actor's own mailbox even when it stalls
nobody else's.

*Pinning* is the case that differs between the two schedulers. A native frame — a JNI or
foreign-function call — cannot unmount the virtual thread at all, so it holds its carrier: under the
JDK builtin that costs one `ForkJoinPool` worker, which the pool can compensate for by growing; under
the carrier-affine scheduler it stalls every other virtual thread that calls that carrier home.
(`synchronized` no longer pins: [JEP 491](https://openjdk.org/jeps/491), JDK 24.)
