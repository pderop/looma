# Looma

**Actors on Java virtual threads, which can be kept on one CPU core.**

Looma starts from Francesco Nigro's
[**Netty-VirtualThread-Scheduler**](https://github.com/franz1981/Netty-VirtualThread-Scheduler): a
carrier-affine virtual-thread scheduler, where a pool of carrier threads replaces the JDK's
`ForkJoinPool`, each carrier pinned to a CPU, and a virtual thread keeps resuming on the same
carrier — so on the same core, with its data still in that core's caches. His
[PERFORMANCE.md](https://github.com/franz1981/Netty-VirtualThread-Scheduler/blob/master/PERFORMANCE.md)
shows what that is worth to a Netty event loop. Then Looma asks the next question:

> **Does a carrier-affine scheduler help an actor system?**

An actor owns private state that only it touches: exactly what wants to stay on one core. Looma
is an actor library built to measure whether it helps. The same actors run on the JDK's default
scheduler, or on a carrier-affine one, and a benchmark compares the two:
[the answer](docs/benchmark.md).

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
    ActorSystem system = ActorSystem.builder().build();
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
                Carriers.affine stops that: next section
```

### With carrier affinity

`Carriers.affine(n)` keeps each actor on one carrier for life, each carrier pinned to one CPU on
Linux. It needs:

- a special JDK: the Loom JDK 28 (in development), with the MPSC scheduler of
  [openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227), Francesco Nigro's new
  version of his scheduler. There is no binary: build it from source,
  [how](mpsc-scheduler/README.md#the-jdk);
- `looma-mpsc-scheduler` on the classpath;
- the choice in code, before the JVM's first virtual thread:

```java
ActorSystem system = ActorSystem.builder().carriers(Carriers.affine(2)).build();
```

Pinning a carrier is a native call. Add `--enable-native-access=ALL-UNNAMED` to the `java` command,
or the JVM prints a warning; the carriers are pinned either way.

With that JDK in SDKMAN as `28-loom-pr227`:

```sh
sdk env                 # reads .sdkmanrc
mvn clean install       # builds every module, runs every test
java --enable-native-access=ALL-UNNAMED \
     -cp core/target/looma-core-1.0-SNAPSHOT.jar:mpsc-scheduler/target/looma-mpsc-scheduler-1.0-SNAPSHOT.jar:examples/target/classes \
     io.github.pderop.looma.examples.hello.Main       # the hello example, on two affine carriers
```

The JVM first prints `WARNING: Using experimental MPSC virtual thread scheduler`, then:

```
[/hello] handling 'Pierre' on VirtualThread[#38,looma-/hello]/runnable@mpsc-carrier-0
[/hello] handling 'John' on VirtualThread[#38,looma-/hello]/runnable@mpsc-carrier-0
[/hello] handling 'Bob' on VirtualThread[#38,looma-/hello]/runnable@mpsc-carrier-0
[/hello] blocking (simulated I/O) for 'Pierre' on VirtualThread[#41]/runnable@mpsc-carrier-1
[/hello] blocking (simulated I/O) for 'John' on VirtualThread[#42]/runnable@mpsc-carrier-1
[/hello] blocking (simulated I/O) for 'Bob' on VirtualThread[#43]/runnable@mpsc-carrier-1
[/hello/audit] audited Hello[name=Pierre] from /hello on VirtualThread[#39,looma-/hello/audit]/runnable@mpsc-carrier-0
...
```

```
/hello, /hello/audit -> always mpsc-carrier-0: an actor never leaves its carrier,
                        and a child gets its parent's
offloaded calls      -> start on carrier-0; carrier-1 is idle and may steal them
3 messages at once   -> one virtual thread #38; each offloaded call -> a new thread, #41 to #43
```

### Where next

- [`docs/api.md`](docs/api.md): the API.
- [`docs/schedulers.md`](docs/schedulers.md): the two schedulers, and how a `tell` runs.
- [`docs/benchmark.md`](docs/benchmark.md): the benchmark, its answer, and how to run it.

## Words

```
carrier           an OS thread that runs virtual threads
mailbox thread    the virtual thread that runs an actor's messages, one at a time;
                  the first message of an empty mailbox starts it, it ends once the mailbox is empty
home carrier      the carrier an actor gets at spawn
affine            Carriers.affine(n): an actor's mailbox threads run only on its home carrier,
                  each carrier pinned to one CPU on Linux. It is the JDK's MPSC scheduler
                  (pull request 227). The benchmark calls it mpsc
builtin           Carriers.builtin(n): the JDK's default scheduler, a ForkJoinPool.
                  The benchmark calls it jdk
offloaded thread  a thread from context.vThreadFactory(), for blocking work; an idle carrier may steal it
```

## Modules

| Directory | Artifact | Java | Contents |
| --- | --- | --- | --- |
| `core` | `looma-core` | 25 | The API (`io.github.pderop.looma`), the engine (`….impl`), the SPI (`….spi`). |
| `mpsc-scheduler` | `looma-mpsc-scheduler` | 28 | `affine`: the adapter of the JDK's MPSC scheduler. [README](mpsc-scheduler/README.md). |
| `examples` | `looma-examples` | 25 | `quickstart`, `hello` (the affinity trace), `user` (the benchmark), `timer` (the timer benchmark). |

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

`affine` runs the MPSC scheduler of
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
