# Looma

<p align="center">
  <img src="docs/looma-promo.gif" width="436" alt="Actors on Java virtual threads, with carrier affinity.">
</p>

Looma starts from someone else's work: Francesco Nigro's
[**Netty-VirtualThread-Scheduler**](https://github.com/franz1981/Netty-VirtualThread-Scheduler), a
genuinely elegant piece of engineering. It is a carrier-affine virtual-thread scheduler: instead of
the JDK's `ForkJoinPool`, a pool of carrier threads (the OS threads that run virtual threads) on
which a virtual thread keeps resuming on the same carrier — and so, as long as that carrier stays on
one core, with its data still in that core's private caches. His
[PERFORMANCE.md](https://github.com/franz1981/Netty-VirtualThread-Scheduler/blob/master/PERFORMANCE.md)
shows what that is worth to a Netty event loop. Then Looma asks the next question:

> **Does a carrier-affine scheduler help an actor system?**

An actor owns private state that only it touches: exactly what wants to stay on one core. For that,
two things must hold: the actor keeps running on the same carrier, and that carrier keeps running
on the same core. Looma is an actor library built to measure whether it helps. The same actors run
on the JDK's default scheduler, and on the MPSC scheduler with or without `Thread.Affinity`:

| Scheduler | The actor stays on its carrier | The carrier stays on its core |
| --- | --- | --- |
| the JDK's default scheduler, a `ForkJoinPool` | no: any worker may take its thread | no: the OS places it |
| the MPSC scheduler, without `Thread.Affinity` | no: an idle carrier may take its thread | no: the OS places it |
| the MPSC scheduler, with `Thread.Affinity` | yes, with `affine(true)` | yes, with `pinned(true)` (Linux) |

The MPSC scheduler is the new version of Netty-VirtualThread-Scheduler, inside a Loom JDK.
`Thread.Affinity` is the JDK API that keeps a virtual thread on one
carrier: without it, Looma cannot keep an actor on its carrier, nor pin the carriers. The pull
request of the MPSC scheduler is a draft: whether it keeps `Thread.Affinity` is still being
evaluated, so Looma runs on two builds of it: `pr227`, the pull request, with
`Thread.Affinity`, and `noaff`, a later commit, without it.

The [results](docs/results/summary.md#in-short) answer the question for each build. On `pr227`,
with each actor on its carrier and each carrier on its CPU (`mpsc-affine-pinned`), the MPSC
scheduler beats the JDK's default one on every measure, in both benchmarks: peak rate, latency
and CPU per request. On `noaff`, without affinity (`mpsc`), it uses less CPU or the same, and has a
lower p99 at the same rate, and its peak rate is higher or the same.

With `affine(true)`, only an actor's own messages stay on its carrier. The rest still moves:

- blocking work and I/O go to offloaded virtual threads (`context.vThreadFactory()`): they have no
  affinity, and an idle carrier may take them;
- to balance the load, spread it over a pool of actors (`SpawnOptions.pooled(n)`): with
  `Placement.roundRobin()`, the default of `system.spawn`, its members land on different carriers.

Both MPSC versions need a special JDK, built from source ([the JDK](mpsc-scheduler/README.md#the-jdk)).
Two benchmarks compare them:

- [the user benchmark](docs/benchmark-user.md): each actor walks a chain of its own users: each
  step looks up the next user by id. The chain is in a random order on purpose: the CPU cannot prefetch it,
  so the walk shows reliably whether the actor's data is still in its core's cache. Does that data
  stay there, and does that save CPU?
- [the servlet benchmark](docs/benchmark-servlet.md): a mini servlet container written with actors,
  closer to a real server: sessions, a catalog, a fraud check, a call to a backend, and no pointer
  chasing. How far does it go, and what is its latency below that?

The [results](docs/results/summary.md) give the numbers, and what they show.

## Quick start

The whole API runs on a stock JDK 25: that and Maven are all you need here. The actors then run
on the JDK's default scheduler, without carrier affinity: an actor may move from core to core.
The MPSC scheduler, with or without carrier affinity, needs a special JDK:
[next section](#on-the-mpsc-scheduler).

```sh
git clone https://github.com/pderop/looma && cd looma
mvn -q install -DskipTests      # puts looma-core 1.0-SNAPSHOT in ~/.m2: it is not on Maven Central
```

[`QuickStart.java`](examples/src/main/java/io/github/pderop/looma/examples/quickstart/QuickStart.java):

```java
record Greet(String who) implements Message {}
record Greeting(String text) implements Message {}

static class Greeter implements Actor {
    private int greeted; // no lock: the actor runs one message at a time

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
    if (args.length > 0) {
        switch (args[0]) {
            // Needs a Loom JDK with the MPSC scheduler.
            case "mpsc" -> builder.carriers(Carriers.mpsc(2));
            // Also needs Thread.Affinity.
            case "mpsc-affine-pinned" -> builder.carriers(Carriers.mpsc(2).affine(true).pinned(true));
            default -> throw new IllegalArgumentException(
                    "the argument must be mpsc or mpsc-affine-pinned, not '" + args[0] + "'");
        }
    }
    ActorSystem system = builder.build();
    ActorRef greeter = system.spawn("greeter", Greeter::new);

    greeter.tell(new Greet("Ann")); // fire and forget
    Greeting reply = greeter.ask(new Greet("Bob"), Greeting.class) // request and reply
            .orTimeout(1, TimeUnit.SECONDS)
            .join();
    System.out.println(reply.text());
    greeter.tell(new Greet("Cid"), 100, TimeUnit.MILLISECONDS); // in 100 ms

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
                Carriers.mpsc(n).affine(true) stops that: next section
```

### On the MPSC scheduler

`Carriers.mpsc(n)` runs the actors on the MPSC scheduler of a Loom JDK 28 (in development). There
is no binary: build the JDK from source, [how](mpsc-scheduler/README.md#the-jdk). Looma runs on
two builds of it: `pr227`, with `Thread.Affinity`, and `noaff`, without. Choose it
in code, before the JVM's first virtual thread:

```java
ActorSystem system = ActorSystem.builder().carriers(Carriers.mpsc(2)).build();
```

By default, `Carriers.mpsc(n)` pins nothing and keeps no actor on a carrier. `affine(true)` and
`pinned(true)` work on `pr227` only; `noaff` refuses them. `pinned(true)` pins only on Linux. The
four configs, each with the name the benchmarks give it:

```java
Carriers.mpsc(2)                                   // mpsc: the default. The OS places the carriers; an actor
                                                   // can run on any carrier
Carriers.mpsc(2).affine(true)                      // mpsc-affine: each actor stays on its carrier
Carriers.mpsc(2).pinned(true)                      // mpsc-pinned: each carrier stays on its CPU; an actor
                                                   // can run on any carrier
Carriers.mpsc(2).affine(true).pinned(true)         // mpsc-affine-pinned: each actor stays on its carrier,
                                                   // each carrier on its CPU
```

On `pr227`, the MPSC scheduler can also be chosen by JVM flags instead of `carriers(...)`: it then
keeps each actor on its carrier ([as JVM flags](docs/schedulers.md#as-jvm-flags)).

The quick start on `pr227`, with two carriers, affine and pinned:

```sh
sdk env                 # 28-loom-pr227, from .sdkmanrc
mvn clean install       # builds every module, runs every test
java --enable-native-access=ALL-UNNAMED \
     -cp core/target/looma-core-1.0-SNAPSHOT.jar:mpsc-scheduler/target/looma-mpsc-scheduler-1.0-SNAPSHOT.jar:examples/target/classes \
     io.github.pderop.looma.examples.quickstart.QuickStart mpsc-affine-pinned
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

The classpath, the native access, and the same quick start on `noaff`:
[running Looma on it](mpsc-scheduler/README.md#running-looma-on-it).

### Where next

- [`docs/api.md`](docs/api.md): the API.
- [`docs/schedulers.md`](docs/schedulers.md): the two schedulers, and how a `tell` runs.
- [`docs/mpsc-install.md`](docs/mpsc-install.md): what installing `Carriers.mpsc(n)` does, and the
  threads the JDK starts.
- [`docs/benchmark.md`](docs/benchmark.md): what both benchmarks share: the load, the runs, the
  machine, and how to run them.
- [`docs/benchmark-user.md`](docs/benchmark-user.md): the user benchmark.
- [`docs/benchmark-servlet.md`](docs/benchmark-servlet.md): the servlet benchmark, and why it exists.
- [`docs/results/summary.md`](docs/results/summary.md): the numbers of both benchmarks, and what
  they show.

## Words

```
carrier           an OS thread that runs virtual threads
mailbox thread    the virtual thread that runs an actor's messages, one at a time;
                  the first message of an empty mailbox starts it, it ends once the mailbox is empty
                  (under mpsc, on a JDK without Thread.Affinity, it waits for the next message
                  instead: the keep-alive)
home carrier      the carrier an actor gets at spawn
scheduler         in the code: mpsc or builtin, below. In the benchmarks and the results:
                  one of six names, the four mpsc configs, jdk and jdk-pinned
mpsc              Carriers.mpsc(n): the MPSC scheduler of a Loom JDK 28; its four configs
                  are above. The benchmarks call the default mpsc
builtin           Carriers.builtin(n): the JDK's default scheduler, a ForkJoinPool.
                  The benchmarks call it jdk
offloaded thread  a thread from context.vThreadFactory(), for blocking work; an idle carrier may steal it
```

## Modules

| Directory | Artifact | Java | Contents |
| --- | --- | --- | --- |
| `core` | `looma-core` | 25 | The API (`io.github.pderop.looma`), the engine (`….impl`), the SPI (`….spi`). |
| `mpsc-scheduler` | `looma-mpsc-scheduler` | 28 | `mpsc`: the adapter of the JDK's MPSC scheduler. [README](mpsc-scheduler/README.md). |
| `examples` | `looma-examples` | 25 | `quickstart`, `hello` (blocking work forked from an actor, [`docs/api.md`](docs/api.md#the-hello-example)), `user` (the user benchmark), `servlet` (the servlet benchmark), `timer` (Looma's timers against a `ScheduledThreadPoolExecutor`, run by `scripts/bench-timer.sh`). |

Everything depends on `looma-core`, never the reverse, and `looma-core` never names a scheduler
class. `mpsc-scheduler` joins the build only on a JDK 28 or newer. `netty-loom-scheduler` is no
longer built (see Credit).

## Build and test

```sh
mvn clean install            # local builds also apply Spotless formatting
mvn -B verify -P '!dev'      # what CI runs: formatting checked, not applied
```

On a JDK 25 this builds and tests `core` and `examples`. With a Loom JDK it also builds
`mpsc-scheduler` and runs its tests. On `noaff`, the tests that need `Thread.Affinity` are
skipped. On a JDK 28 or newer without the MPSC scheduler, the tests of `mpsc-scheduler` fail.
Without SDKMAN, export `JAVA_HOME`; `LOOM_JDK_HOME` overrides it for the scripts in `scripts/`.

The tests run in several Surefire executions, one per scheduler setup:
[`mpsc-scheduler/README.md`](mpsc-scheduler/README.md#tests). To run one class, name the execution
too; `-Dtest=` alone applies to every execution, with the wrong flags:

```sh
mvn -pl mpsc-scheduler test-compile surefire:test@install-test -Dtest=MpscInstallTest
```

## Credit

`Carriers.mpsc(n)` runs the MPSC scheduler of
[openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227), by Francesco Nigro: a
queue per carrier, and a `Thread.Affinity` that keeps a virtual thread on one carrier. Another
version, on the branch `mpsc-without-affinity` of his fork, has no `Thread.Affinity`.

Looma's first scheduler was a copy of his
[Netty-VirtualThread-Scheduler](https://github.com/franz1981/Netty-VirtualThread-Scheduler)
(Apache-2.0), in `netty-loom-scheduler`, with documented changes in two files — see
[`netty-loom-scheduler/README.md`](netty-loom-scheduler/README.md) and [`NOTICE`](NOTICE). That
module is no longer built.

## License

Apache License, Version 2.0 — see [`LICENSE`](LICENSE). Copied files carry their upstream
Apache-2.0 notice in addition to this project's own; see [`NOTICE`](NOTICE).
