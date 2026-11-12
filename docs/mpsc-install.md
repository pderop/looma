# Installing the MPSC scheduler

`ActorSystem.builder().carriers(Carriers.mpsc(n)).build()` installs the JDK's MPSC scheduler. It
runs on the calling thread, `main` below, before the JVM's first virtual thread. This page says what
it does, step by step.

With `pinned(true)`, the default, each carrier ends up on one CPU. The install also keeps the JDK's
own threads off those CPUs. The first section lists these threads.

## The threads the JDK starts on demand

On Linux, a new thread gets the CPUs of the thread that starts it. It keeps them for its whole
life: the JDK never changes them.

The JDK starts some of its threads late, from the first thread that needs them:

| Thread | Does | Started by | When |
|---|---|---|---|
| `mpsc-carrier-<id>` | runs the virtual threads | the thread that sets up virtual threads | at the first virtual thread, or when `sun.nio.ch.Poller` loads (see below) |
| `VirtualThread-unblocker` | resumes a virtual thread blocked on a monitor | same | same |
| `Read-Poller` | waits for the reads of the virtual threads that are not on an MPSC carrier | the thread that loads `sun.nio.ch.Poller` | at the first network wait of a virtual thread |
| `Write-Poller` | waits for the writes of every virtual thread | same | same |
| `VirtualThread-unparker` | wakes a virtual thread after `sleep`, `parkNanos` or `wait(timeout)` | **the carrier** | at the first timed park that uses it |
| `ForkJoinPool.commonPool-worker-<n>` | the common pool: `parallelStream`, `supplyAsync` | the thread that gives the pool work | when the pool needs one more |
| `ForkJoinPool.commonPool-delayScheduler` | `orTimeout`, `completeOnTimeout`, `delayedExecutor` | the thread that sets the first timeout | at the first timeout |

The JDK has a few `VirtualThread-unparker` threads. A carrier always uses the same one, chosen by
the carrier's id. Several carriers may share one.

An actor's reads need no thread. On Linux, a carrier waits for the reads of its virtual threads in
its own epoll.

Started from a pinned carrier, such a thread keeps that carrier's one CPU:

```
carrier-0 pinned to CPU 2
actor on carrier-0: the JVM's first socket write that must wait
  -> Poller loads -> Read-Poller and Write-Poller start from carrier-0
  -> both run on CPU 2 only, for good
  -> every virtual thread that waits to write is now served from CPU 2

actor on carrier-0: the first sleep(10 ms) of carrier-0
  -> carrier-0 starts its VirtualThread-unparker
  -> it runs on CPU 2 only, for good, and serves other carriers too
```

## What the install does

```
main, with every CPU of the process
  1. checks: not installed yet, no other scheduler, no -D flag that conflicts
  2. set the properties: useMpsc, parallelism = n, mpscSteal
  3. load sun.nio.ch.Poller          -> Read-Poller and Write-Poller start from main
                                        on Linux, the carriers and the unblocker too
  4. start one virtual thread        -> the carriers and the unblocker, if not yet
                                     -> is it on an mpsc-carrier? no: undo 2, and fail
  5. the common pool's factory = AllCpus, unless the application set one
     ── from here, the JVM runs the MPSC scheduler for good ──
  6. save the CPUs of main
  7. orTimeout(1 ns)                 -> the delayScheduler starts from main
  8. check that the common pool uses AllCpus
  9. one Thread.Affinity per carrier, from one group: home 0 to home n-1
     pinned(true) only:
       a. on each home: park 1 ms    -> its VirtualThread-unparker starts,
                                        while no carrier is pinned yet
       b. on each home: pin it       -> from a virtual thread of home i,
                                        sched_setaffinity pins its carrier to CPU i
       c. on each home: check it     -> home i runs on CPU i, or fail
  10. pinned, and the common pool does not use AllCpus: warn
```

Step 3 starts the carriers on Linux, where each carrier has its own epoll: the poller needs the
scheduler first. Elsewhere, step 4 starts them. Either way, `main` starts them.

A failure before step 5 undoes the properties. After it, nothing can be undone: this install, and
every later one in the JVM, throws.

With `pinned(false)`, steps 9a to 9c do not run, and step 10 does not warn. The carriers keep every
CPU, and so do the threads they start. The other steps still run, but they change nothing then:
`AllCpus` gives a thread the CPUs it already has.

## After the install

```
mpsc-carrier-0 ── one CPU     Read-Poller, Write-Poller  ── every CPU of main
mpsc-carrier-1 ── one CPU     VirtualThread-unblocker    ── every CPU of main
...                           VirtualThread-unparker     ── every CPU of main
                              delayScheduler             ── every CPU of main
                              common pool workers        ── every CPU of main
```

A worker of the common pool may still start from a carrier. `AllCpus` makes it, and the worker's
first act is to take back the CPUs saved in step 6:

```
actor on carrier-0 (CPU 2): parallelStream()
  -> the pool starts a worker from carrier-0  -> it has CPU 2
  -> AllCpus: the worker sets the saved CPUs   -> every CPU of main
```

## What it does not cover

- The application's own threads and executors. Started from an actor, they should come from
  `system.scheduler().platformThreadFactory()`, which does what `AllCpus` does.
- A common pool factory set by the application with
  `-Djava.util.concurrent.ForkJoinPool.common.threadFactory`: it is kept as is.
- A common pool used before the install: it keeps the JDK's factory. Step 10 warns.
- Other JDK threads started on demand, outside virtual threads and the common pool. For example,
  the `process reaper` of `Process.start()`, the cleaner thread of `ByteBuffer.allocateDirect`,
  the `Keep-Alive-Timer` of `HttpURLConnection`. Started from an actor, they keep its carrier's CPU.

## Why it is built this way

**Why the order.** A thread gets its CPUs when it starts, so each JDK thread must start before any
carrier is pinned. Pinning comes last.

**Why load `sun.nio.ch.Poller`.** Loading the class starts the pollers, and on Linux the scheduler
too. It works in every poller mode, because it runs outside the JDK's own setup of virtual threads.

**Why park 1 ms on each carrier.** The carrier itself starts its `VirtualThread-unparker`, at its
first timed park. Parking once on each carrier, before any pin, starts them all from carriers that
still have every CPU. This relies on two JDK details: the unparker is chosen by the carrier's id,
and it never ends. If one of them changes, `MpscInstallTest` fails. It is a workaround, until the
JDK offers another way.

**Why `orTimeout(1 ns)`.** The common pool does not make its `delayScheduler` with its factory, so
`AllCpus` cannot fix it later. Starting it from `main` gives it every CPU.

**Why a factory for the common pool.** The common pool starts workers all the time, from whichever
thread gives it work. No warmup can start them all in advance.

**Why the factory is set after the check.** A failed install must not leave `AllCpus` in the
common pool.

**Why pin from a virtual thread.** An affinity does not say which carrier it lands on. A virtual
thread of home `i` runs on its carrier, so `sched_setaffinity` on the calling thread pins that
carrier. Step 9c checks that no two homes share a carrier.
