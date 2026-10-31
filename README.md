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
- **One engine, three schedulers.** The same actors run on the JDK's builtin scheduler, on Looma's
  copy of the carrier-affine one, or on the JDK's MPSC scheduler, chosen by
  `ActorSystem.builder().carriers(...)` or by JVM flags — never by the actor code.
- **A benchmark** that runs the same workload under each scheduler and compares CPU per request,
  latency and migrations.

Looma is a lab, not a production framework.

## Quickstart

```sh
# 1. The Loom JDK of openjdk/loom pull request 227, built from source and registered in
#    SDKMAN as 28-loom-pr227: see "Building the Loom JDK" below. Then, in this project:
sdk env                                            # reads .sdkmanrc, picks 28-loom-pr227

# 2. Build, and run every test under every scheduler configuration
mvn clean install

# 3. Carrier affinity in one trace: an actor and its child stay on one carrier, its offloaded work can move
scripts/run-hello.sh

# 4. The benchmark: the carrier-affine schedulers against the JDK builtin, same binary. One
#    repetition of both cases, about 21 minutes; the results go to bench/<date>-<time>/summary.md.
#    The default CPUs are those of an AMD Ryzen 9 9950X. On another machine, give yours:
#    --cpus-carriers, --cpus-client and --cpus-backend (docs/benchmark.md, "Where things run").
scripts/bench.sh --reps 1
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
        .carriers(Carriers.affine(8))   // or Carriers.mpsc(8), or Carriers.builtin(8)
        .build();
```

`affine(8)` and `mpsc(8)` keep each actor on one carrier for life, each carrier pinned to one CPU.
They have no options: each one is the setup the benchmark found the fastest.

`affine` and `mpsc` also need `looma-netty-scheduler` on the classpath, `--enable-preview` on the
command line, and the Loom JDK below. Everything else is in [`docs/api.md`](docs/api.md) and
[`docs/schedulers.md`](docs/schedulers.md).

## Documentation

| | |
| --- | --- |
| [`docs/api.md`](docs/api.md) | The API: actors, messages, `tell`/`ask`, hierarchy, supervision, placement, pools, offloading, shutdown. |
| [`docs/schedulers.md`](docs/schedulers.md) | The three schedulers (`affine`, `mpsc`, `builtin`): what each one does, choosing one in code or by flags, placement, the engine and the SPI, and why carrier affinity. |
| [`docs/benchmark.md`](docs/benchmark.md) | The benchmark: the question and the answer, the program, the schedulers it compares, running it, the machine, and why it is built this way. |
| [`docs/results/README.md`](docs/results/README.md) | The published campaigns, what a campaign folder contains, and where each number of a `summary.md` comes from. |
| [`netty-loom-scheduler/README.md`](netty-loom-scheduler/README.md) | The copy of the Netty scheduler: upstream commit, Looma's two patches, refresh procedure. |

## Modules

| Directory | Artifact | Java | Contents |
| --- | --- | --- | --- |
| `core` | `looma-core` | 25 | The API (`io.github.pderop.looma`), the engine (`….impl`), the scheduler and mailbox SPIs (`….spi`). |
| `netty-loom-scheduler` | `looma-netty-scheduler` | 28 + `--enable-preview` | A copy of the upstream scheduler under `io.netty.loom.*` (two documented patches), the adapter `….scheduler.netty` that exposes it through Looma's SPI, and `….scheduler.mpsc`, the adapter of the JDK's MPSC scheduler. |
| `examples` | `looma-examples` | 25 | `….examples.hello` (the affinity trace) and `….examples.user` (the benchmark). |

Everything depends on `looma-core`, never the reverse, and `looma-core` never names a scheduler
class. Inside `core`, everything under `impl` but `DefaultActorSystem` is package-private.

## Prerequisites

**A stock JDK 25 is enough to use Looma.** On one, `mvn verify` builds and tests `core` and
`examples`, the carrier-affine module is not even in the reactor, and every `ActorSystem` runs on
the JDK's builtin scheduler: a complete actor runtime, without carrier affinity.

**The carrier-affine schedulers need the Loom JDK of
[openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227)**, at commit `731930db54`.
It has two things a stock JDK 28 does not:

- the preview SPI (`Thread.VirtualThreadScheduler`) that
  `-Djdk.virtualThreadScheduler.implClass` plugs into, for the Netty scheduler; it lives on the
  [`fibers` branch of `openjdk/loom`](https://github.com/openjdk/loom/tree/fibers);
- the MPSC scheduler and `Thread.Affinity`, which the pull request adds.

On another JDK, Maven fails with `cannot find symbol`. There is no binary: build it from source,
as below.

`.sdkmanrc` names `28-loom-pr227`, a local SDKMAN identifier for that build. Without SDKMAN, export
`JAVA_HOME`; Maven runs on that JDK itself, no toolchain. `LOOM_JDK_HOME` overrides it for the
scripts, which all check for a Loom build before launching anything.

### Building the Loom JDK

About 15 minutes on 16 cores, and about 3 GB of disk.

**1. The tools.** On Ubuntu or Debian:

```sh
sudo apt-get install -y autoconf make zip unzip file gcc g++ \
  libasound2-dev libcups2-dev libfontconfig1-dev \
  libx11-dev libxext-dev libxrender-dev libxrandr-dev libxtst-dev libxt-dev
```

On macOS: the Xcode command line tools (`xcode-select --install`) and `autoconf`
(`brew install autoconf`).

**2. A boot JDK**, a JDK 27 or 28. The Loom build of builds.shipilev.net is one (a JDK 28); CI
uses it:

```sh
mkdir -p "$HOME/boot-jdk"
curl -fsSL https://builds.shipilev.net/openjdk-jdk-loom/openjdk-jdk-loom-linux-x86_64-server.tar.xz \
  | tar -xJ -C "$HOME/boot-jdk" --strip-components=1
```

Other systems have their tarball next to this one on builds.shipilev.net.

**3. The sources** of the pull request, at the commit Looma uses:

```sh
mkdir -p "$HOME/openjdk-loom-pr227" && cd "$HOME/openjdk-loom-pr227"
curl -fsSL https://github.com/franz1981/loom/archive/731930db54a052cc4549db7361816f16a0119835.tar.gz \
  | tar -xz --strip-components=1
```

**4. The build.** On Linux:

```sh
bash configure --with-boot-jdk="$HOME/boot-jdk" --disable-warnings-as-errors \
  --with-native-debug-symbols=none --with-version-opt=pr227-731930d
make images
```

On macOS, `configure` also needs the Metal compiler, which recent Xcode versions no longer ship.
Looma needs no graphics, so two stub scripts are enough. Create them once:

```sh
mkdir -p "$HOME/.metal-stubs"
cat > "$HOME/.metal-stubs/metal" <<'STUB'
#!/bin/bash
# No Metal toolchain: writes an empty output file, so that the build goes on.
out=""; prev=""
for a in "$@"; do [ "$prev" = "-o" ] && out="$a"; prev="$a"; done
[ -n "$out" ] && { mkdir -p "$(dirname "$out")"; : > "$out"; }
exit 0
STUB
cp "$HOME/.metal-stubs/metal" "$HOME/.metal-stubs/metallib"
chmod +x "$HOME/.metal-stubs/metal" "$HOME/.metal-stubs/metallib"
```

Then, with a macOS boot JDK in `$HOME/boot-jdk`:

```sh
bash configure --with-boot-jdk="$HOME/boot-jdk" --disable-warnings-as-errors \
  --with-native-debug-symbols=none --with-version-opt=pr227-731930d \
  METAL="$HOME/.metal-stubs/metal" METALLIB="$HOME/.metal-stubs/metallib"
make images
```

`--with-version-opt` is not needed, but `java -version` then names the commit
(`28-internal-pr227-731930d`), and the benchmark's `summary.md` copies that line.

**5. SDKMAN.** Register the image under the name `.sdkmanrc` expects. The image is in
`build/<os>-<arch>-server-release/images/jdk`, for example `linux-x86_64` or `macosx-aarch64`:

```sh
sdk install java 28-loom-pr227 "$HOME/openjdk-loom-pr227/build/linux-x86_64-server-release/images/jdk"
```

SDKMAN only links to that folder: keep it. Then check the JDK, in this project:

```sh
sdk env
java -version                                      # ... (build 28-internal-pr227-731930d)
javap 'java.lang.Thread$Affinity'                  # no error: this is the right JDK
```

## Build and test

```sh
mvn clean install            # local builds also apply Spotless formatting
mvn -B verify -P '!dev'      # what CI runs: formatting checked, not applied
```

The scheduler is configured once per JVM, so the tests run in several Surefire executions, one per
configuration: the contract suites over the builtin in `core`, then again over `affine` with and
without work stealing, and over `mpsc`, plus `carriers(...)` installing a scheduler in a fresh JVM
per class. The table is in [`docs/schedulers.md`](docs/schedulers.md#tests). To run a single
class, name the execution too — `-Dtest=` alone applies to every execution, with the wrong flags:

```sh
mvn -pl netty-loom-scheduler test-compile surefire:test@carrier-test -Dtest=CarrierAffinityTest
```

## The hello example

```sh
scripts/run-hello.sh
```

`hello.Main` asks for the JDK's MPSC scheduler with two carriers, `carriers(Carriers.mpsc(2))`,
so the only flag it needs is `--enable-preview`. Each line names the carrier it ran on:

```
[/hello] handling 'Pierre' on VirtualThread[#38,looma-/hello]/runnable@mpsc-carrier-0
[/hello] handling 'John' on VirtualThread[#38,looma-/hello]/runnable@mpsc-carrier-0
[/hello] handling 'Bob' on VirtualThread[#38,looma-/hello]/runnable@mpsc-carrier-0
[/hello] blocking (simulated I/O) for 'Pierre' on VirtualThread[#41]/runnable@mpsc-carrier-1
[/hello] blocking (simulated I/O) for 'John' on VirtualThread[#42]/runnable@mpsc-carrier-1
[/hello] blocking (simulated I/O) for 'Bob' on VirtualThread[#43]/runnable@mpsc-carrier-1
[/hello/audit] audited Hello[name=Pierre] from /hello on VirtualThread[#39,looma-/hello/audit]/runnable@mpsc-carrier-0
...
[/hello] greeting 'Pierre' on VirtualThread[#41]/runnable@mpsc-carrier-1
Greeting[text=Greeting Pierre]
...
```

- The `/hello` actor, and the `/hello/audit` child it spawned, always run on `mpsc-carrier-0`:
  an actor never leaves its carrier, and a child gets its parent's.
- Each call `/hello` offloads through `vThreadFactory()`, to simulate a blocking call, starts on
  `mpsc-carrier-0` too. The other carrier is idle, so it steals them: they run on
  `mpsc-carrier-1`, and sometimes on `mpsc-carrier-0`.
- An actor runs on a virtual thread only while it has messages: the three messages arrive
  together, so one virtual thread of `/hello` (`#38`) handles them all, then ends. Each offloaded
  call gets a fresh one (`#41` to `#43`).

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
