# Looma

**Actors on Java virtual threads, pinned to a core.**

### Where this comes from

Looma starts from someone else's work. Francesco Nigro's
[**Netty-VirtualThread-Scheduler**](https://github.com/franz1981/Netty-VirtualThread-Scheduler) is a
carrier-affine virtual-thread scheduler: instead of the JDK's `ForkJoinPool`, a pool of carrier
threads pinned one per CPU, on which a virtual thread keeps resuming on the same carrier — and so on
the same core, with its data still in that core's private caches. His
[PERFORMANCE.md](https://github.com/franz1981/Netty-VirtualThread-Scheduler/blob/master/PERFORMANCE.md)
shows what that is worth to a Netty event loop, and it is a genuinely elegant piece of engineering:
it makes a scheduler respect locality without asking the application to know anything about it.

Reading it raised an obvious question, and this repository is the attempt to answer it:

> **Does that scheduler help an actor system?**

It ought to. An actor is *defined* by private state that only it touches — which is exactly the shape
of thing that wants to stay on one core. But "ought to" is not a measurement, so Looma exists to
produce one.

### What Looma is

A deliberately **minimal** actor API, plus the seam that lets the same actors run on either
scheduler.

The API borrows the vocabulary everyone already knows from Akka — `tell` and `ask`, a parent/child
hierarchy with paths, supervision directives (resume / restart / stop), a graceful cascading stop —
and stops there. Nine types in one package. No typed behaviours, no `become`, no stashing, no
remoting, no clustering, no persistence, no dead-letter queue; see
[what it does not do](docs/api.md#10-what-looma-does-not-do). That is on purpose: it has to be big
enough to write a realistic workload against, and small enough that a benchmark is comparing the two
schedulers rather than a framework.

Below the API, `spi.ActorScheduler` is the seam. The *same* actor, the same engine, the same
workload run either on the JDK's default virtual-thread scheduler or on Franz's carrier-affine one —
switched by JVM flags, never by application code. That is what makes the comparison honest, and it
is what the [HTTP benchmark](#the-http-cache-locality-benchmark) drives.

Looma is a lab, not a production framework: there is no intent to publish it as one.

- [Why carrier affinity](#why-carrier-affinity) — the rationale, with pictures
- [Quickstart](#quickstart) and the [API guide](docs/api.md)
- [The two schedulers](#the-two-schedulers)
- [Build and test](#build-and-test), [running the examples](#running-the-examples)
- [The HTTP cache-locality benchmark](#the-http-cache-locality-benchmark)

---

## Why carrier affinity

### A virtual thread does not stay put

A virtual thread is a *continuation* plus a scheduler. When it blocks, the continuation is unmounted
and its carrier is handed back to the pool; when whatever it was waiting for completes, the
continuation is submitted to the scheduler again — and the scheduler is free to run it **anywhere**.

The JDK's default scheduler is a `ForkJoinPool` in FIFO-async mode. A resumed continuation is pushed
onto the submitting thread's queue, or onto the shared submission queue, and any idle worker may
steal it. There is no notion of "where this virtual thread ran last", because there is no reason for
the JDK to have one: virtual threads are designed for throughput under I/O, and for a stateless
request handler, migration is genuinely free.

For an actor it is not free, because an actor is *defined* by the state it keeps. And this is not
about an actor doing something unusual: **every** actor parks on an empty mailbox and is unparked by
the next `tell`, constantly, as a matter of course. That is the migration point.

```
 default virtual-thread scheduler
 ────────────────────────────────

    ┌── FJP worker 0 ─── CPU 0 ────────────────────────┐
    │  actor A drains its mailbox, runs onReceive      │
    │  A's 64 KiB of state is now dirty in CPU 0's     │   L1d ~48 KiB   ~5 cycles
    │  L1d / L2 — first touched here, written here     │   L2  ~2 MiB    ~15 cycles
    └──────────────────────────────────────────────────┘

         mailbox empty → A's loop parks. Every actor, all the time.
         a `tell` unparks it → the continuation goes back to the pool
                                │
                                ▼   any idle worker may take it

    ┌── FJP worker 5 ─── CPU 5 ────────────────────────┐
    │  actor A resumes here                            │
    │  Its lines are still MODIFIED in CPU 0's cache.  │   every touched line:
    │  CPU 5 must snoop each one away from CPU 0.      │   HITM ~70-90 cycles
    └──────────────────────────────────────────────────┘
```

That last transfer has a name in Intel's performance vocabulary: **HITM** — a load that hits a cache
line held *Modified* in another core's private cache. The line cannot simply be copied; the owning
core must be interrogated, the line written back or forwarded, and the previous owner invalidated.
On a modern x86 part that is roughly an order of magnitude more expensive than the L1 hit it
replaced, and the cost is paid **per cache line the actor had dirtied**.

Nothing was shared. Nothing needed a lock. The actor's state was private by construction — and it
still paid coherence traffic, purely because the scheduler moved the code away from the data.

### What affinity changes

Give every actor a home carrier, pin every carrier to a CPU, and the picture collapses to one core:

```
 carrier-affine scheduler
 ────────────────────────

    ┌── carrier 3 ─── CPU 6 (pinned with sched_setaffinity) ───────────────┐
    │                                                                       │
    │  actor A runs onReceive                                               │
    │    │                                                                  │
    │    │  it has a blocking call to make, and wants to stay responsive    │
    │    ├─ meanwhile, so it does not block: it forks a virtual thread      │
    │    │  from ctx.vThreadFactory() — also carrier 3 — and returns.       │
    │    │                                              ─────────┐          │
    │    ▼                                                       │ parks    │
    │  A's loop takes the next message, then parks on an         │ on I/O   │
    │  empty mailbox. The carrier runs actors B, C...            │          │
    │                                                            ▼          │
    │  A is told the result ◄─────── the forked VT resumes ON CPU 6         │
    │  A resumes ON CPU 6 — its lines are still in this core's private L2   │
    └───────────────────────────────────────────────────────────────────────┘
```

L1d is 48 KiB on this P-core: a 64 KiB array already overflows it, and the other actors the carrier
ran while A was parked have flushed whatever was left. Affinity preserves **L2**, not L1. That is
the whole point of sizing the HTTP benchmark the way it is — see
[Why 0 / 64 / 256 KiB](#why-0--64--256-kib--this-is-an-l2-experiment-not-an-l1-one).

Note that *nothing* in that picture depends on the fork: an actor that simply blocks in `onReceive`
parks and resumes on carrier 3 just the same, and keeps the same locality. The fork is about the
actor's own responsiveness, which is a separate question — [see below](#blocking-in-an-actor-what-it-actually-costs).

The actor's continuation goes back on *its own* carrier's run queue. The carrier is pinned, so the
run queue is effectively "work for CPU 6". The state was first-touched on CPU 6, is written on CPU 6,
and is read back on CPU 6 for the whole life of the actor.

Two more things come along for free once that holds:

- **Children inherit the parent's carrier by default**, so a whole subtree of actors — and the
  messages between them — resolves to one core. A parent-to-child `tell` never crosses a cache
  boundary.
- **Offloaded blocking work stays home too.** `ActorContext.vThreadFactory()` creates virtual
  threads on the actor's own carrier, so a snapshot handed to a forked thread is read on the core
  that wrote it.

### Blocking in an actor: what it actually costs

An actor *may* block inside `onReceive`, and on a virtual thread that is genuinely cheap: the
continuation **parks**, the carrier is handed straight back, and the machine keeps working. No OS
thread is consumed, nothing deadlocks. That part of Loom does exactly what it promises, and this
project does not ask you to pretend otherwise.

What blocking costs is narrower, and it has nothing to do with the carrier:

> **An actor's loop is the only consumer of its own mailbox.** For as long as `onReceive` sits inside
> a blocking call, that actor answers nothing. Its neighbours have loops of their own and keep
> running; its own messages queue up behind a thread that is doing nothing but waiting.

So the rule is conditional, not absolute:

- **Block, if there is nothing else the actor could usefully do meanwhile.** A connection actor
  speaking HTTP/1.1 has to answer in request order anyway — there is no line to be
  head-of-line-blocked, and forking would only add a hop.
- **Fork, if the actor must stay responsive while it waits** — it serves several conversations, it
  has a control channel, it must accept a `Cancel`, it aggregates. Then `onReceive` should touch the
  state and return, and the wait belongs on a virtual thread from `ActorContext.vThreadFactory()`
  that sends the outcome back as an ordinary message:

```java
public void onReceive(Message message, ActorRef sender, ActorContext context) {
    switch (message) {
        case Query query -> {
            // Capture what the forked thread needs BEFORE starting it: `sender` is a parameter of
            // this call only, and the next message may already be dispatching.
            ActorRef self = context.self();
            ActorRef replyTo = sender;

            context.vThreadFactory().newThread(() -> {
                Result result = blockingCall(query);           // parks; carrier keeps working
                self.tell(new QueryDone(result, replyTo), self);
            }).start();
            // returns immediately — the mailbox keeps draining
        }
        case QueryDone done -> {
            updateState(done.result());                        // back on the actor's own loop
            done.replyTo().tell(new Answer(done.result()), context.self());
        }
        default -> { }
    }
}
```

The forked thread never touches the actor's fields: it takes what it needs by value and hands the
outcome back as a message. That is what keeps the "one message at a time" guarantee intact, and the
actor's state free of synchronization — a callback writing into the actor from another thread would
throw all of it away.

**Use `vThreadFactory()`, not `Thread.ofVirtual()` or a shared pool.** It is the only offload
mechanism this API offers, and deliberately so: it is also what carries the actor's affinity to the
forked work. Under the carrier-affine scheduler the new virtual thread is pinned to the actor's own
home carrier, so a snapshot written by the actor is read on the same core and the answer comes home
with no cross-carrier hop. A plain virtual thread gets none of that, and is not counted as in-flight
work by `awaitTermination` either.

### The case that is genuinely bad: pinning

*Pinning* is the one thing that is not a park. `synchronized` around a blocking call, or a JNI call,
cannot unmount the virtual thread at all, so it holds its **carrier**, not merely its own loop.

Under the JDK's scheduler that costs one `ForkJoinPool` worker, and the pool compensates by growing.
Under a carrier-affine scheduler there is nothing to grow: the carrier is pinned to a CPU, and every
actor that calls it home stops until the pin is released. Use `ReentrantLock` rather than
`synchronized`, and keep JNI off the actor loop and off anything forked from it.

### The cache hierarchy, in one table

Why any of this is worth arranging, on a typical modern x86 core:

| Where the line is | Typical cost | Shared with |
| --- | --- | --- |
| L1d, this core | ~4-5 cycles | nobody (SMT sibling excepted) |
| L2, this core | ~14-15 cycles | nobody (SMT sibling excepted) |
| L3 (LLC), clean | ~40-50 cycles | every core on the die |
| **L3 / another core's L2, Modified (HITM)** | **~70-90 cycles** | the core that dirtied it |
| DRAM | ~200-300 cycles | — |

An actor holding 64 KiB of state holds 1024 cache lines. Migrating it *at worst* means dragging all
1024 across, one snoop at a time — and, because the loads in a real traversal are dependent on each
other, that cost cannot be hidden by out-of-order execution the way independent misses can.

The corollary is the falsifiable part, and the benchmark below is built around it: **the cost of a
migration is the number of dirty lines it drags across**, so an affinity advantage must grow with
the amount of per-actor state, and must vanish once that state no longer fits in a core's private
cache anyway.

### Where affinity stops being free

Strict affinity is a real trade, not a free win, and Looma exposes both sides of it:

- An actor bound to a carrier cannot be helped by an idle neighbour. A carrier that draws a burst of
  expensive messages queues them while other carriers sit idle. That is a **fixed utilization
  handicap**, and it only pays for itself while the per-message work is small enough for the
  locality saving to cover it.
- **Work stealing** (`-Dio.netty.loom.workstealing.enabled=true`) is the release valve: a *queued*
  actor loop may be run by an idle sibling carrier. It trades locality for balance, and which of the
  two wins is a property of the workload, not a setting to pick by taste. An actor that is already
  mounted is never preempted mid-message; the home carrier never changes.
- **Pinning hurts far more here** than under the JDK's scheduler, for the reason above. It is the
  one actor-side mistake a carrier-affine scheduler punishes disproportionately.

---

## Quickstart

```xml
<dependency>
    <groupId>io.github.pderop</groupId>
    <artifactId>looma-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

```java
record Greet(String who) implements Message {}
record Greeting(String text) implements Message {}

class Greeter implements Actor {
    private int greeted;                       // no synchronization: one message at a time

    @Override
    public void onReceive(Message message, ActorRef sender, ActorContext context) {
        if (message instanceof Greet greet && sender != null) {
            greeted++;
            sender.tell(new Greeting("hello " + greet.who()), context.self());
        }
    }
}

ActorSystem system = ActorSystem.builder().build();
ActorRef greeter = system.spawn("greeter", Greeter::new);

Greeting reply = greeter.ask(new Greet("Pierre"), Greeting.class)
        .orTimeout(1, TimeUnit.SECONDS)
        .join();

system.shutdown();
system.awaitTermination(5, TimeUnit.SECONDS);
```

Nothing in that code names a scheduler. `ActorSystem.builder().build()` takes whichever
`ActorScheduler` is installed in the process; which one that is comes from JVM flags and the
classpath. **The full API guide, with examples for every surface, is
[`docs/api.md`](docs/api.md)** — hierarchy and paths, `tell` vs `ask`, supervision, placement,
offloading blocking work, and shutdown.

---

## The two schedulers

The actor engine is written **once**, in `io.github.pderop.looma.impl`: the mailbox, one virtual
thread per actor (drain until empty, then park), the parent/child hierarchy, per-actor supervision,
`ask`, and the graceful cascading stop. No scheduler holds a copy of any of it.

The seam below the engine is `spi.ActorScheduler`: how many carriers there are, and which
virtual-thread factory starts an actor's loop — and its offloaded threads — on one.

| | one message at a time per actor | fixed home carrier (locality) | carrier count |
| --- | --- | --- | --- |
| `JdkActorScheduler` (builtin) | yes | no | `1` |
| `NettyActorScheduler` (affine) | yes | yes | one per carrier thread |

That is the entire difference, and it is deliberate: a supervision or draining bug fixed on one side
only would silently invalidate every comparison this project exists to make.

`shutdown()` and `awaitTermination` behave identically on both — a graceful draining cascade, then
termination once every actor is unregistered *and* every unit of in-flight work has finished,
counting actor loops and every thread created through `ActorContext.vThreadFactory()`.

### Which scheduler a process gets

`ActorSystem.Builder.build()` resolves, in order:

1. an explicit `scheduler(ActorScheduler)` — used by tests and benchmark controls, not by
   applications;
2. otherwise `ServiceLoader` discovery of an `ActorSchedulerProvider` whose `isAvailable()` is
   `true`;
3. otherwise `JdkActorScheduler`.

There is no builder knob to "force the JDK one". Without
`-Djdk.virtualThreadScheduler.implClass`, the affine provider reports unavailable and the builtin is
what is left. A provider that fails to *link* — a preview-compiled class on a stock JDK throws
`UnsupportedClassVersionError` — is caught and skipped too.

The flags that install the affine scheduler:

| Property | Meaning |
| --- | --- |
| `-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler` | Installs the carrier-affine scheduler, process-wide. Without it you get the JDK builtin. |
| `-Dio.netty.loom.schedulers=N` | Carrier pool size. |
| `-Dio.netty.loom.topology=io.netty.loom.topology.LinuxCarrierTopology` | Pins carriers to CPUs and groups them by L3 (Linux only). |
| `-Dio.netty.loom.workstealing.enabled=true` | Work stealing between carriers. |

Full table with defaults: [`docs/carrier.md`](docs/carrier.md). Looma itself reads **no** system
property of its own.

A third-party scheduler plugs in exactly the way this project's own does: implement
`spi.ActorScheduler` and `spi.ActorSchedulerProvider`, register the provider under
`META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider`, and put the jar on the system
classloader's classpath. `looma-core` never learns its name.

---

## Modules

Three Maven modules, and the arrows only point one way — everything depends on `looma-core`, never
the reverse, and `looma-core` never names a scheduler class.

| Directory | Artifact | Java | Contents |
| --- | --- | --- | --- |
| `core` | `looma-core` | 25 | The API (`io.github.pderop.looma`), the engine (`….impl`), the scheduler and mailbox SPIs (`….spi`). |
| `netty-loom-scheduler` | `looma-netty-scheduler` | 28 + `--enable-preview` | A byte-for-byte copy of franz1981's scheduler under `io.netty.loom.*`, plus the adapter `….scheduler.netty` that exposes it through Looma's SPI. |
| `examples` | `looma-examples` | 25 | `….examples.hello` (the carrier-affinity trace) and `….examples.http` (the benchmark server). |

That is also a **JDK split**. `core` and `examples` compile at `release=25` and run on a stock JDK.
`netty-loom-scheduler` is the only module built against the preview virtual-thread-scheduler SPI
(`Thread.VirtualThreadScheduler`), so it joins the reactor only on a Loom JDK, through the root
pom's `loom-jdk` profile (`<jdk>[28,)</jdk>`).

Inside `core` the API sits at the root package and the engine lives under `impl`, where everything
but `DefaultActorSystem` is package-private — so "the engine is not API" is a compiler constraint
rather than a naming convention.

**One JVM, one scheduler.** `-Djdk.virtualThreadScheduler.implClass` is process-wide and resolved
once in `VirtualThread.<clinit>`, so a single JVM cannot host both configurations. That is why the
contract test suites are subclassed once per scheduler and run in separate Surefire executions.

---

## Prerequisites

**A regular JDK 25 is enough to use Looma** (Temurin, Oracle, … — the GA release, not a Loom
build). `core` and `examples` compile at `release=25`. On one of those, `mvn verify` builds and tests
them, the `loom-jdk` profile stays inactive — Franz's scheduler module is not even in the reactor —
and every `ActorSystem` runs on the JDK's builtin virtual-thread scheduler. That is a complete actor
runtime: `tell`, `ask`, hierarchy, supervision, graceful stop. What you do not get is carrier
affinity. CI's `build-jdk25` job is this path.

**Franz's scheduler needs a Loom-enabled JDK 28**, not a stock one. Stock 28 has virtual threads; it
does not have the preview SPI `Thread.VirtualThreadScheduler` that
`-Djdk.virtualThreadScheduler.implClass` plugs into. Point Maven at a stock 28 and the scheduler
module fails with `cannot find symbol`; a JVM launched by hand *silently ignores* the flag. That SPI
lives on the OpenJDK Loom project, currently the
[`fibers` branch of `openjdk/loom`](https://github.com/openjdk/loom/tree/fibers). Nothing in this
build downloads a JDK.

### Getting the Loom JDK

Binaries, rebuilt continuously, from [Aleksey Shipilëv's
builds](https://builds.shipilev.net/openjdk-jdk-loom/) — the same tarball CI fetches:

```sh
# linux x86_64; other archs (aarch64, …) sit next to this file at the same URL
curl -fsSL -o loom-jdk.tar.xz \
  https://builds.shipilev.net/openjdk-jdk-loom/openjdk-jdk-loom-linux-x86_64-server.tar.xz
mkdir -p "$HOME/.local/jdk-loom"
tar -xJf loom-jdk.tar.xz -C "$HOME/.local/jdk-loom" --strip-components=1
"$HOME/.local/jdk-loom/bin/javap" 'java.lang.Thread$VirtualThreadScheduler'   # must succeed
```

The tarball is overwritten in place with no versioned alias; the major version can move. CI asserts
it still matches `<java.version>` in `netty-loom-scheduler/pom.xml` (currently 28). Alternatives:
build from [`openjdk/loom`](https://github.com/openjdk/loom) (`fibers`), or
`docker pull shipilev/openjdk:loom`.

`.sdkmanrc` names `28-loom`. That is a **local** SDKMAN identifier, not a catalog candidate — after
the extract:

```sh
sdk install java 28-loom "$HOME/.local/jdk-loom"
sdk env                  # reads .sdkmanrc
mvn clean install
```

Without SDKMAN, `export JAVA_HOME` to the same image. When building the whole project Maven runs on
that JDK itself — no toolchain. `LOOM_JDK_HOME` overrides it in `scripts/loom-jdk.sh` for a one-off
against a different Loom build. Every script probes with `javap` before launching anything, so a
stock 28 cannot slip through.

## Build and test

```sh
mvn clean install
```

Local builds also *apply* Spotless formatting at the `compile` phase (profile `dev`, active unless
`-DskipFormat` is passed). CI only *checks* it — run the same combination before pushing:

```sh
mvn -B verify -P '!dev'
```

Tests run in separate Surefire executions, one per scheduler configuration:

| Module | Execution | Test classes | JVM |
| --- | --- | --- | --- |
| `core` | `default-test` | the contract suites over the JDK builtin, plus `examples`' `Http1CodecTest` | no flags — never `-Djdk.virtualThreadScheduler.implClass`, which would leak process-wide into the control JVM |
| `netty-loom-scheduler` | `default-test` | the vendored scheduler's own tests (`io.netty.loom.*`) | scheduler installed, 2 carriers |
| | `carrier-test` | `….scheduler.netty` — the same contract suites, carrier-affine | scheduler installed, 2 carriers, stealing off |
| | `workstealing-test` | the same, plus `EventLoopSchedulerWorkStealingTest` | scheduler installed, 4 carriers, stealing on, `FakeClusterTopology` |
| | `replaceBuiltinScheduler-test` | `ReplaceBuiltinSchedulerTest` | upstream's own execution, copied verbatim |

They all run in one `mvn test`, and a failure in any of them is a real failure: a test that passes
under `carrier-test` and fails under `workstealing-test` is the whole reason the suite runs twice.

To run a **single class**, name the execution too — `-Dtest=` alone overrides the include filter of
*every* execution at once, so the class is also attempted in a JVM with the wrong flags:

```sh
mvn -pl netty-loom-scheduler test-compile surefire:test@carrier-test -Dtest=CarrierAffinityTest
```

## Running the examples

```sh
scripts/run-hello.sh
```

A script rather than an `exec-maven-plugin` execution: `--enable-preview` and the scheduler class
are read at JVM startup, so they must be on the command line of the JVM that runs the code. The
classpath comes from `scripts/examples-classpath.sh`, which builds the module if needed and prints
nothing else.

```
[NettyScheduler] topology=NONE carriers=2 clusters=1 stealScope=GLOBAL workStealing=false replaceBuiltinScheduler=false
[/hello] handling 'Pierre' on VirtualThread[#32,looma-/hello]/runnable@carrier-0
[/hello] handling 'John' on VirtualThread[#32,looma-/hello]/runnable@carrier-0
[/hello] handling 'Bob' on VirtualThread[#32,looma-/hello]/runnable@carrier-0
[/hello/audit] audited Hello[name=Pierre] from /hello on VirtualThread[#33,looma-/hello/audit]/runnable@carrier-0
[/hello/audit] audited Hello[name=John] from /hello on VirtualThread[#33,looma-/hello/audit]/runnable@carrier-0
[/hello/audit] audited Hello[name=Bob] from /hello on VirtualThread[#33,looma-/hello/audit]/runnable@carrier-0
[/hello] blocking (simulated I/O) for 'Pierre' on VirtualThread[#35]/runnable@carrier-0
[/hello] blocking (simulated I/O) for 'John' on VirtualThread[#36]/runnable@carrier-0
[/hello] blocking (simulated I/O) for 'Bob' on VirtualThread[#37]/runnable@carrier-0
[/hello] greeting 'Pierre' on VirtualThread[#35]/runnable@carrier-0
Greeting[text=Greeting Pierre]
[/hello] greeting 'John' on VirtualThread[#36]/runnable@carrier-0
Greeting[text=Greeting John]
[/hello] greeting 'Bob' on VirtualThread[#37]/runnable@carrier-0
Greeting[text=Greeting Bob]
```

Every line names `carrier-0`: the `/hello` actor's own `onReceive`, the `/hello/audit` **child** it
spawned from its context, and each `vThreadFactory()` thread that simulates a blocking call and
answers the `ask`. No cross-carrier hop anywhere in the trace — that is the whole point of the
example.

Two things to read in it. The actor's loop is **one virtual thread for life** (`#32` for all three
messages, `#33` for the child), while each offloaded call gets a fresh one (`#35`, `#36`, `#37`) —
and those are on `carrier-0` too, which is the affinity being demonstrated. And all three `handling`
lines print before any `blocking` line: the actor forks and returns rather than waiting, so its
mailbox keeps draining while three simulated I/Os are in flight. Run it without the JVM flags and
the actor system still works — the `carrier-N` suffixes simply stop being meaningful.

---

## The HTTP cache-locality benchmark

A real HTTP/1.1 server on real sockets, driven by a third-party load generator, doing a real request
lifecycle — and, unlike an in-JVM microbenchmark, a workload `perf c2c` can be pointed at. The
server is `examples/…/examples/http`; the driver is `scripts/run-http-bench.sh`.

### What it measures

Every connection is an actor owning a **private** byte array. Nothing is shared — no session map, no
lock, no counter in common — so any coherence traffic the run produces was created by the scheduler,
not by the application. Each connection actor is a **root** actor spawned with an explicit
`Placement.carrier(n % carriers)`, so connection *n* deterministically lands on carrier *n %
carriers*.

One request:

```
        ┌──────────────── the actor, on its home carrier ────────────────┐
read ─► │ modify own array ─► copy it ──┐                               │
        └───────────────────────────────┼───────────────────────────────┘
                                        ▼  fork a virtual thread
                         read the snapshot ─► blocking GET to the mock
                                        │
        ┌───────────────────────────────┼───────────────────────────────┐
        │ ◄── message with body+checksum┘                               │
        │ modify own array again ─► fork a virtual thread ─────────────►│ write
        └───────────────────────────────────────────────────────────────┘
```

Four details are load-bearing:

- **The walk is a pointer chase.** Each cache line stores the index of the next line to visit — a
  single random cycle over every line, laid out once per connection. Written the obvious way,
  `array[random.nextInt(n)]`, the CPU knows a dozen addresses in advance and issues those loads in
  parallel, which hides exactly the per-line latency difference being measured. A dependent chain
  cannot be overlapped. The order is random rather than sequential for a second reason: a contiguous
  scan is what the L2 streamer prefetcher exists to hide. And every visit is a read-modify-**write**,
  so each line is left dirty and the next core must take it away rather than merely share it.
- **The pair of modifications straddles a park.** One alone measures nothing; the question is which
  core the *second* one runs on relative to the first.
- **What separates them is a real socket round trip, not a sleep.** A timed park resumes from the
  JDK's timer queue and never exercises the per-carrier sub-poller the locality argument rests on. A
  mock upstream must therefore be running in its own process on its own cores; the server probes it
  at startup and refuses to bind without it. The mock's think time defaults to **0 µs** — it answers
  as soon as it has read the request — so the wait is only that round trip, not an extra delay on
  top of it. See [the wait-to-work ratio](#the-nine-runs-and-how-they-can-be-falsified) for why.
- **The snapshot is what makes this race-free with no synchronisation at all.** The actor keeps
  mutating its own array while the forked thread reads a copy nobody else can see. The buffer is
  allocated once per connection and overwritten — a fresh array per request would turn this into an
  allocation benchmark, and the young collections would sweep the very lines whose residency is
  being measured.

### Why 0 / 64 / 256 KiB — this is an L2 experiment, not an L1 one

The P-core this campaign is sized for (i9-14900K, one thread per physical core, HT sibling idle):

| Where the line sits | Size on this part | Typical hit | Shared with |
| --- | --- | --- | --- |
| L1d | **48 KiB** | ~5 cycles | nobody |
| L2 | **2 MiB**, private | ~15 cycles | nobody |
| L3 | 36 MiB | ~40-50 cycles | every core on the die |
| HITM (Modified in another core's L2) | — | ~70-90 cycles | the core that dirtied it |

A 64 KiB array is already larger than L1d. And while one connection is parked on the mock, the
other 11 connections of that carrier walk *their* arrays through the same 48 KiB. **No scheduler
can keep this working set in L1d** — one other request has already flushed it. What affinity can
preserve is L2: 12 connections × (64 KiB of `state` + 64 KiB of `snapshot`) = **1.5 MiB**, which
still fits in the 2 MiB private L2. Coming back to the same core is then an L2 hit; landing on
another core is a HITM against a line that never needed to move. That is the operating point, and
that is why the chase exists: the difference being measured is ~15 vs ~80 cycles *per line*, and
only a dependent, random, dirtying walk actually pays it.

| `stateKiB` | per-carrier footprint (`12 × 2 × stateKiB`) | Against those caches |
| --- | --- | --- |
| `0` | nothing touched | dispatch-only control — any gap here is scheduling, not locality |
| `64` | 1.5 MiB | **past L1d, inside L2** — where the locality term has to live |
| `256` | 6 MiB | **past L2** — the gap **must** collapse back onto the `0` result |

**Size against `connsPerCarrier × 2 × stateKiB`**, never `stateKiB` alone: every connection actor
owns its `state` array *and* a same-sized `snapshot` buffer, so both count against the L2 budget.
The driver's defaults leave little headroom (1.5 MiB of 2 MiB). The
[2026-09-01 campaign](docs/performance.md) nevertheless found the locality term there; a looser
operating point (`CONNS_PER_CARRIER=6`, or `STATE_KIB_POINTS="0 32 256"`) would be expected to show
a larger one still.

### The nine runs, and how they can be falsified

Three configurations × three per-connection state sizes:

| Configuration | carriers pinned | stealing | What it answers |
| --- | --- | --- | --- |
| `carrier` | yes | no | Does the locality exist, and what is it worth |
| `stealing` | yes | yes | What stealing costs — a measurement, not a default |
| `jdk` | — | — | The control: same server, same cores, no affinity |

The server binary is identical in all nine; only JVM flags differ, and the driver asserts the
scheduler class out of the server log after bind — a missing `--enable-preview` would otherwise
produce a full set of plausible numbers under the wrong label.

Three devices exist to attack the answer rather than confirm it:

- **The three-point argument.** Size against `connsPerCarrier × 2 × stateKiB`, never `stateKiB`
  alone. If the gap at `256` does not fall back onto the `0` result, cache residency was never what
  separated the schedulers at the operating point.
- **The predicted slope.** A migration costs one HITM per dirty line, so the `jdk`-minus-`carrier`
  **cycles/request delta** should grow roughly linearly in `stateKiB`. A gap that does not scale with
  the amount of state is not the gap this benchmark claims to measure. Read that criterion in cycles,
  not as a ratio: a ratio collapses mechanically as the denominator grows, whether or not the
  advantage changed.
- **The wait-to-work ratio.** Per-connection concurrency is 1 (HTTP/1.1 answers in order), so a
  request costs `think + cpu` and only `cpu` depends on the scheduler. The largest ratio two
  schedulers can ever show in *throughput* is `(think + cpu_slow) / (think + cpu_fast)`, never
  `cpu_slow / cpu_fast`. At a 1 ms think time against ~50 µs of work, a scheduler costing twice the
  cycles surfaces as ~5 % in rps — a real difference, made invisible by arithmetic alone.

  **This is why `MOCK_THINK_MICROS` defaults to `0`:** every microsecond of upstream think time is
  dilution of the only term the scheduler controls. At `0` the mock answers as soon as it has read
  the request, the wait collapses to the loopback round trip the server has to pay anyway, and the
  difference is as visible in rps as it can be. What `0` does *not* remove is the reason the mock
  exists — the server still makes a real blocking socket call, so its continuation still parks on
  the per-carrier sub-poller rather than on the JDK's timer queue. Raise it only to model a
  genuinely slow backend, and then read cycles/request rather than rps.

  Two things `0` changes, worth knowing before reading a run. The mock **stops spinning**: its
  reactor only busy-polls inside the last millisecond before a response is due, and with nothing
  pending it blocks in `select()` instead. And the mock is **much closer to being the bottleneck**,
  since the server now asks for responses as fast as they can be produced — so check the client's
  rps against the concurrency ceiling — `connections / upstreamRoundTrip`, which nothing prints, so
  work it out by hand — at *every* point, and raise `MOCK_THREADS` before concluding anything from a
  run whose rps sits on it.

  The same arithmetic is why a run must be checked for CPU saturation before its rps is read at all:
  divide `task-clock` in `*.perfstat.txt` by (measured seconds × carriers), and below roughly 90 %
  the carriers were idling on the backend and that run compares nothing.

### The machine: defaults are sized for an i9-14900K

**The driver's defaults only make sense on that part**, and the reasoning matters more than the
numbers. Read the real topology first:

```sh
lscpu -e     # cores, HT siblings, P vs E
lscpu -C     # cache levels, shared vs private
```

On a 14900K, `lscpu -e` shows CPUs 0-15 as 8 P-cores **with Hyper-Threading** (each `CORE` value
appears twice — CPU 0 and CPU 1 are the same physical core), and CPUs 16-31 as 16 E-cores. Two
logical CPUs of one core share the execution units, the 48 KiB L1d **and the 2 MiB L2** — the
`L1d:L1i:L2:L3` column proves it, CPUs 0 and 1 both reporting L2 id `0`.

That is why `SERVER_CPUS` takes **one CPU per physical core** and never both siblings:

```sh
SERVER_CPUS=0,2,4,6,8,10,12,14    # 8 carriers, one per physical P-core, 2 MiB private L2 each
CLIENT_CPUS=16-27                 # 12 E-cores for the load generator
MOCK_CPUS=28-31                   # the 4th E-core L2 cluster, for the mock upstream
```

Two carriers on CPUs 0 and 1 would share one 2 MiB L2, halving the budget the sizing assumes. This
is not hypothetical: the topology maps carrier *i* to the *i*-th CPU **of the affinity mask**, so an
unrestricted run pairs carriers on HT siblings *first*. Always pass an explicit mask.

Three processes, three **disjoint** cpusets. The mock gets its own so that it can never preempt a
carrier — the run would then measure that instead. (At a non-zero `MOCK_THINK_MICROS` it also
busy-polls inside the last millisecond before each response is due, which makes the separation not
merely advisable but mandatory.)

Two consequences of this particular CPU, worth knowing before over-reading any ratio:

- 8 carriers × 1.5 MiB is 12 MiB against a **36 MiB L3**, so at the operating point a migration
  never costs a DRAM round trip: the contrast is an L2 hit against an L3/HITM snoop. Expect
  single-digit percent to low multiples, not 10×. At `stateKiB=256` the total is 48 MiB, past that
  L3 as well — so the top point falsifies more than private-L2 residency, which is more than the
  argument needs.
- `lscpu -e` reports L3 id `0` for every CPU — one L3 for the whole chip — so `CLUSTER_LOCAL` permits
  exactly the same steals as `GLOBAL`, and steal *scoping* is not measurable on this part at all.

**On another CPU, recompute all three cpusets and both state sizes from `lscpu`.** Nothing in the
driver reads the topology.

### Preparing the machine

None of this is done by the driver, and skipping it does not make a run fail — it makes the numbers
noise instead of signal. Terse checklist: [`docs/machine-prep.md`](docs/machine-prep.md).

```sh
# 1. Fix the frequency. `powersave` ramps up during the measurement and drops between bursts,
#    which is exactly the pattern of a carrier waking to serve a request.
sudo cpupower frequency-set -g performance
echo 1 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo

# 2. Let perf measure without full root. This one fails QUIETLY: too high a value and perf
#    produces nothing, the run completes, and you lose cycles/request with only a warning.
sudo sysctl kernel.perf_event_paranoid=1
perf list | grep -i mem_load    # cpu_core/mem_load_retired.l2_hit|l3_hit/ must exist
perf list | grep -i xsnp        # the snoop events perf c2c needs

# 3. Optional: take the HT siblings offline, so nothing else lands on a carrier's core.
for c in 1 3 5 7 9 11 13 15; do echo 0 | sudo tee /sys/devices/system/cpu/cpu$c/online; done
```

`no_turbo=1` caps the cores at their base frequency: turbo drifts within a run, between runs, and
**between cores** — on a 14900K, `MAXMHZ` is 6000 for CPUs 12-15 against 5700 for the rest, and
since connections are placed on carrier *n % carriers*, that bias would land on the same carriers
every run. The `cpu_core/` prefix on the perf events is not decoration either: the chip is hybrid, so
P-cores and E-cores have distinct PMUs.

The driver *records* `no_turbo`, the governors and `perf_event_paranoid` into `<out>/env.txt` before
the first run, and warns on stderr — it never sets them. Everything above is reverted the way it was
set.

### Running it

```sh
command -v jbang taskset perf     # jbang launches wrk/wrk2; jbang and taskset are mandatory

sdk env                           # the scripts follow the same JAVA_HOME as Maven
mvn clean install                 # must be redone on the benchmark machine: a preview class file
                                  # only runs on the exact JDK build that produced it

scripts/run-http-bench.sh                 # closed loop: throughput and perf counters
RATE=20000 scripts/run-http-bench.sh      # open loop: latency percentiles at a fixed rate
FAST=1 scripts/run-http-bench.sh          # ~2 min smoke run — proves the harness, measures nothing
```

The full campaign is about nine minutes: nine runs of a 5 s warm-up plus a 30 s measured window,
then three 35 s `perf c2c` captures (~110 MiB of `.c2c.data`). `FAST=1` brings that under two
minutes by shortening the windows and skipping the captures — it still starts all nine runs, so it
proves the whole path works end to end, but run-to-run variance at a 5 s window is the size of the
effect being compared. **Nothing measured under `FAST=1` is a result.**

Every knob is an environment variable, with the i9 defaults above: `SERVER_CPUS`, `CLIENT_CPUS`,
`MOCK_CPUS`, `MOCK_THINK_MICROS`, `STATE_KIB_POINTS`, `CONNS_PER_CARRIER`, `DURATION`, `WARMUP`,
`RATE`, `HEAP`, `FAST`. An explicit `DURATION`/`WARMUP` wins over `FAST=1`.
`WRK=wrk WRK2=wrk2 scripts/run-http-bench.sh` uses native binaries instead of jbang.

Everything lands in `target/http-bench/<timestamp>/`: `*.client.txt` (with the exact command in
`*.client.cmd`), `*.perfstat.txt`, `*.c2c.txt`, `env.txt`, and each server's own log.

### The load generator, and reading what comes out

`wrk` and `wrk2` through `jbang`. `RATE` unset gives **`wrk`, closed loop** — one request in flight
per connection, the next sent when the response lands, which is what measures a saturation
throughput. `RATE=<req/s>` gives **`wrk2 -R --latency`, open loop** — the client sends at that rate
whatever the server does, and queues client-side when it cannot keep up.

That distinction is not cosmetic. In a closed loop a server stall makes the client *stop sending*
during exactly the bad window, so the requests that would have suffered are never issued and never
appear in the histogram. That is **coordinated omission**. Rule: **publish throughput from the
closed-loop run, and latency only from an open-loop run** at a rate below the throughput the closed
loop found.

**The headline metric is cycles per request** — `cpu_core/cycles/` from `*.perfstat.txt` divided by
the request count in `*.client.txt` — not raw rps, because it absorbs frequency drift. Then, in
order:

1. **Check saturation before reading any rps at all** (`task-clock` ÷ measured seconds ÷ carriers).
2. **The `0` KiB control must be flat.** Whatever gap survives there is dispatch cost, not locality.
3. **The `256` KiB point must fall back onto the `0` KiB point**, in cycles/request delta.
4. **The gap should scale with `stateKiB`** between those two ends.
5. **`perf c2c` should order the three configurations**: `carrier` lowest HITM, `stealing` above it,
   `jdk` at the top. This is the qualitative evidence behind the quantitative one.
6. **Check the concurrency ceiling** against the throughput obtained. Per-connection concurrency is
   1, so throughput cannot exceed `connections / upstreamRoundTrip`; nothing prints that figure, so
   compute it (the `stateKiB=0` run's average latency is a usable upper bound on the round trip).
   Sitting on it means the run measured the client and the mock, not the schedulers.

The most recent campaign (`target/http-bench/20260901-130357/`, mock think time **0 µs**) found a
locality term of ~26 000 cycles/request at the 64 KiB / L2 operating point. Full tables and the
interpretation: [`docs/performance.md`](docs/performance.md).

---

## Documentation

| | |
| --- | --- |
| [`docs/api.md`](docs/api.md) | The API guide: actors, messages, `tell`/`ask`, hierarchy, supervision, placement, offloading, shutdown. |
| [`docs/carrier.md`](docs/carrier.md) | The carrier-affine scheduler: placement rules, work stealing, the `io.netty.loom.*` properties, why it is a separate module. |
| [`docs/jdk.md`](docs/jdk.md) | The JDK builtin scheduler, and the shared engine documented once in its terms. |
| [`docs/performance.md`](docs/performance.md) | The latest measured campaign, and what it does and does not show. |
| [`docs/machine-prep.md`](docs/machine-prep.md) | Per-machine checklist before a benchmark run. |
| [`docs/yield-policy.md`](docs/yield-policy.md) | Open question: should the actor loop's yield trigger be a duration rather than a count? |
| [`netty-loom-scheduler/README.md`](netty-loom-scheduler/README.md) | The vendored copy: upstream commit, refresh procedure, why it is never edited. |

## Not to be confused with

- [`forax/loom-actor`](https://github.com/forax/loom-actor) — Rémi Forax's actor library. Unrelated.
- [`actonlang/acton`](https://github.com/actonlang/acton) — the Acton actor language. Also unrelated.
- Any of the several unrelated projects named "Looma" (educational software, animation tooling).
  This one is a Java actor runtime.

## Credit

The carrier-affine scheduler vendored into `netty-loom-scheduler/src/main/java/io/netty/loom` is a
byte-for-byte, unmodified copy of
[franz1981/Netty-VirtualThread-Scheduler](https://github.com/franz1981/Netty-VirtualThread-Scheduler)
(Apache-2.0), by Francesco Nigro. See [`NOTICE`](NOTICE) for the exact upstream commit and packages,
and [`netty-loom-scheduler/README.md`](netty-loom-scheduler/README.md) for the refresh procedure.

## License

Apache License, Version 2.0 — see [`LICENSE`](LICENSE). Vendored files carry their upstream
Apache-2.0 notice in addition to this project's own; see [`NOTICE`](NOTICE).
