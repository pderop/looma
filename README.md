# Looma

<p align="center">
  <img src="docs/looma-promo.gif" width="436" alt="Actors on Java virtual threads, with carrier affinity.">
</p>

Looma starts from someone else's work. Francesco Nigro's
[**Netty-VirtualThread-Scheduler**](https://github.com/franz1981/Netty-VirtualThread-Scheduler) is a
carrier-affine virtual-thread scheduler: instead of the JDK's `ForkJoinPool`, a pool of carrier
threads on which a virtual thread keeps resuming on the same carrier — and so, as long as that
carrier stays on one core, with its data still in that core's private caches. His
[PERFORMANCE.md](https://github.com/franz1981/Netty-VirtualThread-Scheduler/blob/master/PERFORMANCE.md)
shows what that is worth to a Netty event loop, and it is a genuinely elegant piece of engineering:
it makes a scheduler respect locality without asking the application to know anything about it.
Then Looma asks the next question:

> **Does a carrier-affine scheduler help an actor system?**

An actor owns private state that only it touches: exactly what wants to stay on one core. Looma
is an actor library built to measure whether it helps. The same actors run on the JDK's default
scheduler, or on a carrier-affine one where each actor stays on its carrier. Pinning each carrier to
one CPU is an option on top of that. In the [latest results](docs/results/summary.md), affinity
without pinning uses about as much CPU per request as affinity with pinning (about 5% more at most),
and both use much less than the schedulers without affinity (at depth 256 in the user benchmark, and
in the servlet one). Pinning reaches a higher rate in the user benchmark's default case: 1.07M–1.09M
against 890k–930k requests per second. In the servlet one, 430k in both runs against 430k and 390k.
Two benchmarks compare them:

- [the user benchmark](docs/benchmark-user.md): does an actor's data stay in its core's cache, and
  does that save CPU?
- [the servlet benchmark](docs/benchmark-servlet.md): how far does a server of servlet actors go,
  and what is its latency below that?

## Quick start

The whole API runs on a stock JDK 25: that and Maven are all you need here. The actors then run
on the JDK's default scheduler, without carrier affinity: an actor may move from core to core.
Carrier affinity needs a special JDK: [next section](#with-carrier-affinity).

```sh
git clone https://github.com/pderop/looma && cd looma
mvn -q install -DskipTests      # puts looma-core 1.0-SNAPSHOT in ~/.m2: it is not on Maven Central
```

[`QuickStart.java`](examples/src/main/java/io/github/pderop/looma/examples/quickstart/QuickStart.java):

```java
record Greet(String who) implements Message {}
record Greeting(String text) implements Message {}

static class Greeter implements Actor {
    private int greeted;                    // no lock: the actor runs one message at a time

    @Override
    public void onReceive(Message message, ActorRef sender, ActorContext context) {
        if (message instanceof Greet greet) {
            greeted++;
            System.out.println("greeting " + greet.who() + " (#" + greeted + ") on " + Thread.currentThread());
            if (sender != null) {
                sender.tell(new Greeting("hello " + greet.who()), context.self());
            }
        }
    }
}

public static void main(String[] args) throws Exception {
    ActorSystem.Builder builder = ActorSystem.builder();
    if (args.length > 0 && args[0].equals("mpsc")) {
        builder.carriers(Carriers.mpsc(2));       // carrier affinity: next section
    }
    ActorSystem system = builder.build();
    ActorRef greeter = system.spawn("greeter", Greeter::new);

    greeter.tell(new Greet("Ann"));                                   // fire and forget
    Greeting reply = greeter.ask(new Greet("Bob"), Greeting.class)    // request and reply
            .orTimeout(1, TimeUnit.SECONDS).join();
    System.out.println(reply.text());
    greeter.tell(new Greet("Cid"), 100, TimeUnit.MILLISECONDS);       // in 100 ms

    Thread.sleep(300);
    system.shutdown();
    System.out.println("terminated: " + system.awaitTermination(5, TimeUnit.SECONDS));
}
```

```sh
java -cp core/target/looma-core-1.0-SNAPSHOT.jar \
     examples/src/main/java/io/github/pderop/looma/examples/quickstart/QuickStart.java
```

```
greeting Ann (#1) on VirtualThread[#27,looma-/greeter]/runnable@ForkJoinPool-1-worker-1
greeting Bob (#2) on VirtualThread[#27,looma-/greeter]/runnable@ForkJoinPool-1-worker-1
hello Bob
greeting Cid (#3) on VirtualThread[#33,looma-/greeter]/runnable@ForkJoinPool-1-worker-2
terminated: true
```

How to read it:

```
Ann, Bob     -> one virtual thread, #27: it runs the actor's messages one at a time
Cid (100 ms) -> a new virtual thread, #33: the mailbox was empty, so #27 had ended
worker-1, -2 -> the JDK's scheduler may move the actor to another carrier
                Carriers.mpsc stops that: next section
```

### With carrier affinity

`Carriers.mpsc(n)` keeps each actor on one carrier for life, and pins each carrier to one CPU on
Linux. It needs:

- a special JDK: the Loom JDK 28 (in development), with the MPSC scheduler of
  [openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227), Francesco Nigro's new
  version of his scheduler. There is no binary: build it from source,
  [how](mpsc-scheduler/README.md#the-jdk);
- `looma-mpsc-scheduler` on the classpath;
- the choice in code, before the JVM's first virtual thread:

```java
ActorSystem system = ActorSystem.builder().carriers(Carriers.mpsc(2)).build();
```

The quick start does that when given the argument `mpsc`.

Two settings: `pinned` (each carrier stays on one CPU) and `affinity` (each actor stays on its
carrier). Both are `true` by default, and each one can be turned off on its own:

```java
Carriers.mpsc(2)                                   // the default, pinned(true) and affinity(true): each actor
                                                   // stays on its carrier, each carrier on its CPU
Carriers.mpsc(2).pinned(false)                     // actors stay on their carrier; the OS places the carriers
Carriers.mpsc(2).affinity(false)                   // an actor can run on any carrier: its thread starts on
                                                   // its sender's carrier, and an idle carrier may take it
Carriers.mpsc(2).pinned(false).affinity(false)     // both off: the OS places the carriers, and an actor
                                                   // can run on any carrier
```

Pinning a carrier is a native call. Add `--enable-native-access=ALL-UNNAMED` to the `java` command,
or the JVM prints a warning; the carriers are pinned either way.

With that JDK in SDKMAN as `28-loom-pr227`:

```sh
sdk env                 # reads .sdkmanrc
mvn clean install       # builds every module, runs every test
java --enable-native-access=ALL-UNNAMED \
     -cp core/target/looma-core-1.0-SNAPSHOT.jar:mpsc-scheduler/target/looma-mpsc-scheduler-1.0-SNAPSHOT.jar:examples/target/classes \
     io.github.pderop.looma.examples.quickstart.QuickStart mpsc   # the quick start, on two mpsc carriers
```

The JVM first prints `WARNING: Using experimental MPSC virtual thread scheduler`, then:

```
greeting Ann (#1) on VirtualThread[#41,looma-/greeter]/runnable@mpsc-carrier-0
greeting Bob (#2) on VirtualThread[#41,looma-/greeter]/runnable@mpsc-carrier-0
hello Bob
greeting Cid (#3) on VirtualThread[#43,looma-/greeter]/runnable@mpsc-carrier-0
terminated: true
```

How to read it:

```
Ann, Bob, Cid -> always mpsc-carrier-0: the actor never leaves its carrier
Cid (100 ms)  -> a new virtual thread, #43, on the same carrier
```

### Where next

- [`docs/api.md`](docs/api.md): the API.
- [`docs/schedulers.md`](docs/schedulers.md): the two schedulers, and how a `tell` runs.
- [`docs/mpsc-install.md`](docs/mpsc-install.md): what installing `Carriers.mpsc(n)` does, and the
  threads the JDK starts.
- [`docs/benchmark.md`](docs/benchmark.md): what the two benchmarks share, and how to run them.
- [`docs/benchmark-user.md`](docs/benchmark-user.md): the user benchmark, and its answer.
- [`docs/benchmark-servlet.md`](docs/benchmark-servlet.md): the servlet benchmark, and why it exists.

## Words

```
carrier           an OS thread that runs virtual threads
mailbox thread    the virtual thread that runs an actor's messages, one at a time;
                  the first message of an empty mailbox starts it, it ends once the mailbox is empty
home carrier      the carrier an actor gets at spawn
mpsc              Carriers.mpsc(n): the JDK's MPSC scheduler (pull request 227). By default, an
                  actor's mailbox threads run only on its home carrier, each carrier pinned to
                  one CPU on Linux. The benchmarks call it mpsc
builtin           Carriers.builtin(n): the JDK's default scheduler, a ForkJoinPool.
                  The benchmarks call it jdk
offloaded thread  a thread from context.vThreadFactory(), for blocking work; an idle carrier may steal it
```

## Modules

| Directory | Artifact | Java | Contents |
| --- | --- | --- | --- |
| `core` | `looma-core` | 25 | The API (`io.github.pderop.looma`), the engine (`….impl`), the SPI (`….spi`). |
| `mpsc-scheduler` | `looma-mpsc-scheduler` | 28 | `mpsc`: the adapter of the JDK's MPSC scheduler. [README](mpsc-scheduler/README.md). |
| `examples` | `looma-examples` | 25 | `quickstart`, `hello` (blocking work forked from an actor, [`docs/api.md`](docs/api.md#the-hello-example)), `user` (the user benchmark), `servlet` (the servlet benchmark), `timer` (the timer benchmark). |

Everything depends on `looma-core`, never the reverse, and `looma-core` never names a scheduler
class. `mpsc-scheduler` joins the build only on a JDK 28 or newer. `netty-loom-scheduler` is no
longer built (see Credit).

## Build and test

```sh
mvn clean install            # local builds also apply Spotless formatting
mvn -B verify -P '!dev'      # what CI runs: formatting checked, not applied
```

On a JDK 25 this builds and tests `core` and `examples`. With the Loom JDK it also builds
`mpsc-scheduler` and runs its tests; on a JDK 28 or newer without pull request 227, Maven fails in
`mpsc-scheduler` with `cannot find symbol`. Without SDKMAN, export `JAVA_HOME`; `LOOM_JDK_HOME`
overrides it for the scripts.

The tests run in several Surefire executions, one per scheduler setup:
[`mpsc-scheduler/README.md`](mpsc-scheduler/README.md#tests). To run one class, name the execution
too; `-Dtest=` alone applies to every execution, with the wrong flags:

```sh
mvn -pl mpsc-scheduler test-compile surefire:test@install-test -Dtest=MpscInstallTest
```

## Not to be confused with

- [`forax/loom-actor`](https://github.com/forax/loom-actor) — Rémi Forax's actor library. Unrelated.
- [`actonlang/acton`](https://github.com/actonlang/acton) — the Acton actor language. Also unrelated.
- Any of the several unrelated projects named "Looma". This one is a Java actor runtime.

## Credit

`Carriers.mpsc(n)` runs the MPSC scheduler of
[openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227), by Francesco Nigro: a
queue per carrier, and a `Thread.Affinity` that keeps a virtual thread on one carrier.

Looma's first scheduler was a copy of his
[Netty-VirtualThread-Scheduler](https://github.com/franz1981/Netty-VirtualThread-Scheduler)
(Apache-2.0), in `netty-loom-scheduler`, with documented changes in two files — see
[`netty-loom-scheduler/README.md`](netty-loom-scheduler/README.md) and [`NOTICE`](NOTICE). That
module is no longer built.

## License

Apache License, Version 2.0 — see [`LICENSE`](LICENSE). Copied files carry their upstream
Apache-2.0 notice in addition to this project's own; see [`NOTICE`](NOTICE).
