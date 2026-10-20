# Looma

**Actors on Java virtual threads, pinned to a core.**

Looma starts from Francesco Nigro's
[**Netty-VirtualThread-Scheduler**](https://github.com/franz1981/Netty-VirtualThread-Scheduler): a
carrier-affine virtual-thread scheduler, where a pool of carrier threads replaces the JDK's
`ForkJoinPool`, each carrier pinned to a CPU, and a virtual thread keeps resuming on the same
carrier — so on the same core, with its data still in that core's caches. His
[PERFORMANCE.md](https://github.com/franz1981/Netty-VirtualThread-Scheduler/blob/master/PERFORMANCE.md)
shows what that is worth to a Netty event loop. Looma asks the next question:

> **Does that scheduler help an actor system?**

An actor is defined by private state that only it touches, which is exactly what wants to stay on
one core. Looma exists to measure whether it does.

## What Looma is

- **A deliberately minimal actor API**: `tell` and `ask`, a parent/child hierarchy with paths,
  supervision (resume / restart / stop), a graceful cascading stop, and pools of N actors behind
  one reference. Ten types in one package — enough to write a realistic workload against, small
  enough that a benchmark compares schedulers rather than frameworks. See
  [what it does not do](docs/api.md#10-what-looma-does-not-do).
- **One engine, two schedulers.** The same actors run on the JDK's builtin scheduler or on the
  carrier-affine one, chosen by `ActorSystem.builder().carriers(...)` or by JVM flags — never by
  the actor code.
- **A benchmark** that runs the same workload under each scheduler and compares CPU per request,
  latency and migrations.

Looma is a lab, not a production framework.

## Quickstart

```sh
# 1. A Loom JDK 28 (needed for the carrier-affine scheduler; see Prerequisites)
curl -fsSL -o loom-jdk.tar.xz \
  https://builds.shipilev.net/openjdk-jdk-loom/openjdk-jdk-loom-linux-x86_64-server.tar.xz
mkdir -p "$HOME/.local/jdk-loom"
tar -xJf loom-jdk.tar.xz -C "$HOME/.local/jdk-loom" --strip-components=1
sdk install java 28-loom "$HOME/.local/jdk-loom"   # or: export JAVA_HOME=$HOME/.local/jdk-loom
sdk env                                            # reads .sdkmanrc, picks 28-loom

# 2. Build, and run every test under every scheduler configuration
mvn clean install

# 3. Carrier affinity in one trace: an actor, its child and its offloaded work, all on one carrier
scripts/run-hello.sh

# 4. The benchmark: carrier-affine against the JDK builtin, same binary
scripts/run-user-bench.sh --rate 50000
```

The benchmark pins with `taskset` and `sched_setaffinity`, so it measures something only on Linux.
On macOS it runs unpinned: a smoke test, not a measurement. How to run and read it:
[`docs/benchmark.md`](docs/benchmark.md).

## Minimal example

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

Nothing there names a scheduler: `build()` takes whichever one the process has. To choose it in
code instead — before the JVM's first virtual thread:

```java
ActorSystem system = ActorSystem.builder()
        .carriers(Carriers.affine(8).pinned())      // carrier-affine, 8 carriers, one per CPU
        .build();
```

The carrier-affine scheduler also needs `looma-netty-scheduler` on the classpath and
`--enable-preview` on the command line. Everything else is in [`docs/api.md`](docs/api.md) and
[`docs/schedulers.md`](docs/schedulers.md).

## Documentation

| | |
| --- | --- |
| [`docs/api.md`](docs/api.md) | The API: actors, messages, `tell`/`ask`, hierarchy, supervision, placement, pools, offloading, shutdown. |
| [`docs/schedulers.md`](docs/schedulers.md) | The two schedulers: choosing one in code or by flags, placement, work stealing (`GLOBAL`, `CLUSTER_LOCAL`, `actorsStayHome()`), the engine and the SPI, and why carrier affinity. |
| [`docs/benchmark.md`](docs/benchmark.md) | The benchmark: workload, sizes, running it, every option, reading the report, the controls, profiling, and the results per machine. |
| [`netty-loom-scheduler/README.md`](netty-loom-scheduler/README.md) | The copy of the Netty scheduler: upstream commit, Looma's two patches, refresh procedure. |

## Modules

| Directory | Artifact | Java | Contents |
| --- | --- | --- | --- |
| `core` | `looma-core` | 25 | The API (`io.github.pderop.looma`), the engine (`….impl`), the scheduler and mailbox SPIs (`….spi`). |
| `netty-loom-scheduler` | `looma-netty-scheduler` | 28 + `--enable-preview` | A copy of the upstream scheduler under `io.netty.loom.*` (two documented patches), and the adapter `….scheduler.netty` that exposes it through Looma's SPI. |
| `examples` | `looma-examples` | 25 | `….examples.hello` (the affinity trace) and `….examples.user` (the benchmark). |

Everything depends on `looma-core`, never the reverse, and `looma-core` never names a scheduler
class. Inside `core`, everything under `impl` but `DefaultActorSystem` is package-private.

## Prerequisites

**A stock JDK 25 is enough to use Looma.** On one, `mvn verify` builds and tests `core` and
`examples`, the carrier-affine module is not even in the reactor, and every `ActorSystem` runs on
the JDK's builtin scheduler: a complete actor runtime, without carrier affinity.

**The carrier-affine scheduler needs a Loom JDK 28.** A stock 28 does not have the preview SPI
(`Thread.VirtualThreadScheduler`) that `-Djdk.virtualThreadScheduler.implClass` plugs into; Maven
then fails with `cannot find symbol`, and a JVM launched by hand silently ignores the flag. The SPI
lives on the [`fibers` branch of `openjdk/loom`](https://github.com/openjdk/loom/tree/fibers).

Binaries, rebuilt continuously, are at [builds.shipilev.net](https://builds.shipilev.net/openjdk-jdk-loom/)
(the commands in the Quickstart; other architectures sit next to the x86_64 tarball). Check one
with `"$HOME/.local/jdk-loom/bin/javap" 'java.lang.Thread$VirtualThreadScheduler'`. The tarball is
overwritten in place; CI asserts its major version still matches `<java.version>` in
`netty-loom-scheduler/pom.xml`. Alternatives: build `openjdk/loom` (`fibers`), or
`docker pull shipilev/openjdk:loom`.

`.sdkmanrc` names `28-loom`, a local SDKMAN identifier for that image. Without SDKMAN, export
`JAVA_HOME`; Maven runs on that JDK itself, no toolchain. `LOOM_JDK_HOME` overrides it for the
scripts, which all check for a Loom build before launching anything.

## Build and test

```sh
mvn clean install            # local builds also apply Spotless formatting
mvn -B verify -P '!dev'      # what CI runs: formatting checked, not applied
```

The scheduler is configured once per JVM, so the tests run in several Surefire executions, one per
configuration: the contract suites over the builtin in `core`, then again over the carrier-affine
scheduler with and without work stealing, plus `carriers(...)` installing the scheduler in a fresh
JVM per class. The table is in [`docs/schedulers.md`](docs/schedulers.md#tests). To run a single
class, name the execution too — `-Dtest=` alone applies to every execution, with the wrong flags:

```sh
mvn -pl netty-loom-scheduler test-compile surefire:test@carrier-test -Dtest=CarrierAffinityTest
```

## The hello example

```sh
scripts/run-hello.sh
```

`hello.Main` asks for two carriers with `carriers(Carriers.affine(2))`, so the only flag it needs
is `--enable-preview`. Every line it prints names `carrier-0`: the `/hello` actor's `onReceive`,
the `/hello/audit` child it spawned, and each thread it offloads through `vThreadFactory()` to
simulate a blocking call:

```
[/hello] handling 'Pierre' on VirtualThread[#32,looma-/hello]/runnable@carrier-0
[/hello/audit] audited Hello[name=Pierre] from /hello on VirtualThread[#33,looma-/hello/audit]/runnable@carrier-0
[/hello] blocking (simulated I/O) for 'Pierre' on VirtualThread[#35]/runnable@carrier-0
[/hello] greeting 'Pierre' on VirtualThread[#35]/runnable@carrier-0
Greeting[text=Greeting Pierre]
```

The actor's loop is one virtual thread for life (`#32`); each offloaded call gets a fresh one
(`#35`), on the same carrier.

## Not to be confused with

- [`forax/loom-actor`](https://github.com/forax/loom-actor) — Rémi Forax's actor library. Unrelated.
- [`actonlang/acton`](https://github.com/actonlang/acton) — the Acton actor language. Also unrelated.
- Any of the several unrelated projects named "Looma". This one is a Java actor runtime.

## Credit

The carrier-affine scheduler in `netty-loom-scheduler/src/main/java/io/netty/loom` is a copy of
[franz1981/Netty-VirtualThread-Scheduler](https://github.com/franz1981/Netty-VirtualThread-Scheduler)
(Apache-2.0), by Francesco Nigro, with documented changes in two files — see
[`netty-loom-scheduler/README.md`](netty-loom-scheduler/README.md) and [`NOTICE`](NOTICE).

## License

Apache License, Version 2.0 — see [`LICENSE`](LICENSE). Copied files carry their upstream
Apache-2.0 notice in addition to this project's own; see [`NOTICE`](NOTICE).
