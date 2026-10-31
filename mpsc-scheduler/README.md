# `mpsc-scheduler`

This module holds the adapter of the JDK's MPSC scheduler, `io.github.pderop.looma.scheduler.mpsc`,
for `Carriers.mpsc(n)`. It reaches Looma through the SPI declared in `core`
(`io.github.pderop.looma.spi`). [`docs/schedulers.md`](../docs/schedulers.md#mpsc-the-jdks-mpsc-scheduler)
says what the scheduler does.

- `MpscActorSchedulerProvider`: sets the properties, starts the JDK's pollers and timers, finds
  the carriers and pins them.
- `MpscPool`: one `Thread.Affinity` per carrier, found by starting a virtual thread with each one
  and reading the name of its carrier.
- `MpscActorScheduler`: an actor's bursts get the affinity of its carrier, its offloaded threads
  get none.
- `LinuxCpus`: pins a carrier to one CPU with `sched_setaffinity`, and groups the CPUs by L3.
- `AllCpus`: makes threads that run on every CPU, even when an actor starts them. It is the
  factory of the common pool, and of `platformThreadFactory()`.

## Threads started from an actor

A new thread runs only on the CPUs of the thread that starts it. A thread started from an actor
would keep the one CPU of its carrier. So the install:

- starts the JDK's pollers, its timers for virtual threads, and the timer of `CompletableFuture`,
  before it pins the carriers;
- makes `AllCpus` the factory of the common pool, unless the application set one with
  `-Djava.util.concurrent.ForkJoinPool.common.threadFactory`. It must run before the common pool
  is first used, or it warns.

The application's own threads and executors are not covered. Started from an actor, they should
come from `system.scheduler().platformThreadFactory()`.

## The JDK

The MPSC scheduler and `Thread.Affinity` exist only in the JDK of openjdk/loom pull request 227:
see [Building the Loom JDK](../README.md#building-the-loom-jdk). `Thread.Affinity` is not a
preview API, so this module compiles at `release=28` without `--enable-preview`. The root
`pom.xml`'s `loom-jdk` profile adds it to the reactor on a JDK 28 or newer.

## Tests

`mvn -pl mpsc-scheduler -am test` runs two Surefire executions:

- `default-test`: the actor suites of `core`, and `MpscAffinityTest`, on four MPSC carriers
  chosen by flags;
- `install-test`: `Carriers.mpsc(n)` installing the scheduler, with no flag, one JVM per class.
  On Linux, it also checks that each carrier has its own CPU, and that these threads keep every
  CPU when an actor starts them or uses them: the JDK's pollers, its timers, the common pool and
  `platformThreadFactory()`. Elsewhere, it checks that the carriers have no cluster, since
  nothing pins them.

Without `-am`, Maven takes `looma-core` from `~/.m2`, which may be older than the sources.
