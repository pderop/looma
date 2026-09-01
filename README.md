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
workload run either on the JDK's default virtual-thread scheduler or on the carrier-affine one —
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
and its carrier handed back to the pool; when the wait completes, the continuation is submitted to
the scheduler again — which is free to run it **anywhere**.

For a stateless request handler that is genuinely free, and the JDK's `ForkJoinPool` is right not to
care. For an actor it is not free, because an actor is *defined* by the state it keeps. And this is
not an edge case: **every** actor parks on an empty mailbox and is unparked by the next `tell`,
constantly. That is the migration point.

```
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

That last transfer is a **HITM**: a load that hits a cache line held *Modified* in another core's
private cache. The owning core must be interrogated, the line forwarded, the previous owner
invalidated — roughly an order of magnitude more expensive than the L1 hit it replaced, and paid
**per cache line the actor had dirtied**.

Nothing was shared. Nothing needed a lock. The state was private by construction, and it still paid
coherence traffic, purely because the scheduler moved the code away from the data.

### What affinity changes

Give every actor a home carrier, pin every carrier to a CPU, and the picture collapses to one core:
the state is first-touched, written and read back on the same CPU for the whole life of the actor.
Two things follow for free — children inherit their parent's carrier, so a whole subtree resolves to
one core; and blocking work offloaded through `ActorContext.vThreadFactory()` runs there too.

Affinity preserves **L2, not L1**: a 64 KiB array already overflows a 48 KiB L1d, and the other
actors the carrier ran meanwhile have flushed what was left. That is what the benchmark is sized
around.

| Where the line is | Typical cost |
| --- | --- |
| L1d, this core | ~5 cycles |
| L2, this core | ~15 cycles |
| L3, clean | ~40-50 cycles |
| **Another core's L2, Modified (HITM)** | **~70-90 cycles** |

An actor holding 64 KiB of state holds 1024 cache lines, and migrating it means dragging them
across one snoop at a time. Hence the falsifiable claim the benchmark is built on: **an affinity
advantage must grow with the amount of per-actor state, and must vanish once that state no longer
fits in a core's private cache anyway.**

### Where affinity stops being free

- An actor bound to a carrier cannot be helped by an idle neighbour — a **fixed utilization
  handicap**, which only pays for itself while the locality saving covers it.
- **Work stealing** (`-Dio.netty.loom.workstealing.enabled=true`) is the release valve: an idle
  sibling may run a *queued* actor loop. Which way the trade goes is a property of the workload, not
  a setting to pick by taste.
- **Pinning** — a native frame on the stack, i.e. a JNI or foreign-function call — holds the whole
  carrier rather than one pooled worker, and there is nothing to grow. Keep it off the actor loop.
  (`synchronized` is *not* a pin: [JEP 491](https://openjdk.org/jeps/491) removed monitor pinning in
  JDK 24.)

Blocking inside `onReceive` is cheap and allowed — what it costs, and when to fork instead, is in
[`docs/api.md`](docs/api.md#6-blocking-work).

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
        if (message instanceof Greet greet) {
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
| `netty-loom-scheduler` | `looma-netty-scheduler` | 28 + `--enable-preview` | A byte-for-byte copy of the upstream scheduler under `io.netty.loom.*`, plus the adapter `….scheduler.netty` that exposes it through Looma's SPI. |
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
them, the `loom-jdk` profile stays inactive — the carrier-affine module is not even in the reactor —
and every `ActorSystem` runs on the JDK's builtin virtual-thread scheduler. That is a complete actor
runtime: `tell`, `ask`, hierarchy, supervision, graceful stop. What you do not get is carrier
affinity. CI's `build-jdk25` job is this path.

**The carrier-affine scheduler needs a Loom-enabled JDK 28**, not a stock one. Stock 28 has virtual threads; it
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

A real HTTP/1.1 server on real sockets, driven by a third-party load generator — a workload
`perf c2c` can be pointed at, unlike an in-JVM microbenchmark. The server is
`examples/…/examples/http`; the driver is `scripts/run-http-bench.sh`.

Every connection is an actor owning a **private** array, pinned to carrier *n % carriers*. Each
request walks that array, makes a blocking call to a mock upstream, then walks it again. Nothing is
shared between actors, so any coherence traffic a run produces was created by the scheduler moving
an actor off the core that dirtied its lines.

The array size is swept to place the working set on either side of the caches:

| `stateKiB` | per-carrier footprint | what it shows |
| --- | --- | --- |
| `0` | nothing touched | dispatch cost alone — any gap here is not locality |
| `64` | 1.5 MiB — past L1d, inside the 2 MiB L2 | **the locality effect** |
| `256` | 6 MiB — past L2 | the effect **must** collapse back onto the `0` result |

Three configurations of the same binary, switched by JVM flags only:

| | carriers pinned | stealing | What it answers |
| --- | --- | --- | --- |
| `carrier` | yes | no | Does the locality exist, and what is it worth |
| `stealing` | yes | yes | What stealing costs — a measurement, not a default |
| `jdk` | — | — | The control: same server, same cores, no affinity |

### The result

At the operating point — 64 KiB per connection, too big for L1d, still inside the private L2 — on an
i9-14900K with 8 carriers:

| | requests/s | cycles per request |
| --- | ---: | ---: |
| JDK builtin *(control)* | 103 221 | 219 686 |
| carrier-affine | 123 697 &nbsp;**+20 %** | 176 951 &nbsp;**−19 %** |
| carrier-affine + stealing | **132 194 &nbsp;+28 %** | 180 184 &nbsp;−18 % |

Same server, same cores, same binary — only JVM flags differ. The second column is the one that
matters: **fewer cycles per request means the work got cheaper**, not that the machine was pushed
harder. The extra throughput follows from it.

Move the state out of the L2 (`256` KiB) and the advantage disappears entirely, which is what says
the gain came from cache residency and not from a scheduler that is simply faster. Full tables,
`perf c2c` corroboration and caveats: [`docs/performance.md`](docs/performance.md).

### Running it

```sh
sdk env                           # the scripts follow the same JAVA_HOME as Maven
mvn clean install                 # must be redone on the benchmark machine: a preview class
                                  # file only runs on the JDK build that produced it

scripts/run-http-bench.sh                 # closed loop: throughput and perf counters
RATE=20000 scripts/run-http-bench.sh      # open loop: latency percentiles at a fixed rate
FAST=1 scripts/run-http-bench.sh          # ~2 min smoke run — proves the harness, measures nothing
```

`jbang`, `taskset` and `perf` must be on `PATH`. The full campaign is about nine minutes.
**Nothing measured under `FAST=1` is a result** — its 5 s window has run-to-run variance the size
of the effect being compared.

The defaults are sized for an **i9-14900K** and nothing in the driver reads the topology, so on
another CPU the cpusets and state sizes have to be recomputed. Machine preparation — frequency
pinned, turbo off, `perf` permissions — is not done by the driver either, and skipping it turns the
numbers into noise rather than making the run fail.

| | |
| --- | --- |
| [`docs/benchmark.md`](docs/benchmark.md) | Why it is built this way, sizing for your machine, and how to read a run |
| [`docs/machine-prep.md`](docs/machine-prep.md) | Per-machine checklist before a run |
| [`docs/performance.md`](docs/performance.md) | Measured results |

---

## Documentation

| | |
| --- | --- |
| [`docs/api.md`](docs/api.md) | The API guide: actors, messages, `tell`/`ask`, hierarchy, supervision, placement, offloading, shutdown. |
| [`docs/carrier.md`](docs/carrier.md) | The carrier-affine scheduler: placement rules, work stealing, the `io.netty.loom.*` properties, why it is a separate module. |
| [`docs/jdk.md`](docs/jdk.md) | The JDK builtin scheduler, and the shared engine documented once in its terms. |
| [`docs/benchmark.md`](docs/benchmark.md) | The HTTP benchmark method: why it is built this way, sizing, how to read a run. |
| [`docs/performance.md`](docs/performance.md) | The latest measured campaign, and what it does and does not show. |
| [`docs/machine-prep.md`](docs/machine-prep.md) | Per-machine checklist before a benchmark run. |
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
