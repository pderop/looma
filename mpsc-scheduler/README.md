# `mpsc-scheduler`

The adapter of the JDK's MPSC scheduler, `io.github.pderop.looma.scheduler.mpsc`, for
`Carriers.affine(n)`. It reaches Looma through the SPI of `core` (`io.github.pderop.looma.spi`).
[`docs/schedulers.md`](../docs/schedulers.md#affine-the-jdks-mpsc-scheduler) says what `affine`
guarantees; this page says how it works.

- `MpscActorSchedulerProvider`: sets the properties, starts the JDK's pollers and timers, finds
  the carriers and pins them.
- `MpscPool`: one `Thread.Affinity` per carrier, drawn from one group. Looma does not need to know
  which carrier each one lands on: the carrier of affinity `i` is pinned to CPU `i`.
- `MpscActorScheduler`: an actor's mailbox threads get the affinity of its carrier, its offloaded
  threads get none.
- `LinuxCpus`: pins a carrier to one CPU with `sched_setaffinity`, and groups the CPUs by L3.
- `AllCpus`: makes threads that run on every CPU, even when an actor starts them. It is the
  factory of the common pool, and of `platformThreadFactory()`.

## How the MPSC scheduler runs

Each carrier, `mpsc-carrier-<id>`, is one OS thread, with two queues and, on Linux, its own epoll.
Many threads may add to them.

```
carrier-0
  queue        VTs with an affinity (an actor's mailbox threads)  -> only carrier-0 takes them
  stealQueue   VTs without one (offloaded threads)                -> carrier-0, or an idle carrier

a thread with an affinity  -> the queue of its carrier, at every start and every wake
a thread without one       -> the stealQueue of the carrier that starts it
an idle carrier steals     -> from another carrier's stealQueue only: never an actor's mailbox thread
```

**The loop of a carrier:**

```
carrier-0
  1. run the virtual threads of its two queues, for one round
  2. epoll_wait(0): sockets ready?  -> unpark their virtual threads -> back to their queue
  3. nothing to do?  -> try to steal from another carrier's stealQueue
                        still nothing -> epoll_wait(-1): sleep in its epoll
```

**A virtual thread of carrier-0 reads a socket with no data:**

```
VT-a on carrier-0: read()          -> no data
  -> the JDK registers the socket in carrier-0's epoll
  -> VT-a parks; carrier-0 runs the next virtual thread
data arrives
  -> carrier-0's epoll_wait returns the socket
  -> carrier-0 unparks VT-a -> back to VT-a's queue on carrier-0
```

**A virtual thread of carrier-0 writes to a full socket:**

```
VT-a on carrier-0: write()         -> the send buffer is full
  -> the JDK registers the socket in the Write-Poller's epoll
  -> VT-a parks
the peer reads, the socket has room
  -> the Write-Poller unparks VT-a -> back to VT-a's queue on carrier-0
  -> if carrier-0 sleeps in its epoll, the Write-Poller wakes it
```

A `write()` that fits in the send buffer does not park.

| Who waits | To read | To write |
|---|---|---|
| a virtual thread on an MPSC carrier | the epoll of its carrier | `Write-Poller` |
| any other thread | `Read-Poller` | `Write-Poller` |

The scheduler never sees the bytes: it sees only threads that park and threads that are unparked.

## Threads started from an actor

A new thread runs only on the CPUs of the thread that starts it. The JDK starts these threads
from the first thread that needs them:

| Thread | Does | Started |
|---|---|---|
| `mpsc-carrier-<id>` | runs the virtual threads | at the first virtual thread |
| `Read-Poller` | reads for the threads that are not on an MPSC carrier | when the class `sun.nio.ch.Poller` loads |
| `Write-Poller` | every wait to write | same |
| `VirtualThread-unparker` | wakes a virtual thread after `sleep` or a timed park | at the first timed park that uses it |
| `ForkJoinPool.commonPool-worker-<n>` | the common pool: `parallelStream`, `supplyAsync` | when the pool needs one more |
| `ForkJoinPool.commonPool-delayScheduler` | `orTimeout`, `delayedExecutor` | at the first timeout |

Started from a pinned carrier, such a thread would keep its one CPU:

```
carrier-0 pinned to CPU 2
VT on carrier-0: the first read() of the JVM
  -> Poller loads -> Read-Poller and Write-Poller start from carrier-0
  -> both run on CPU 2 only, for good
```

So `Carriers.affine(n)` installs the scheduler from the calling thread, `main` here, which still
has every CPU of the process:

```
main
  1. set the properties: useMpsc, parallelism = n, mpscSteal
  2. load sun.nio.ch.Poller          -> Read-Poller and Write-Poller start from main
  3. start one virtual thread        -> is it on an mpsc-carrier? no: undo 1, and fail
  4. the common pool's factory = AllCpus, unless the application set one
     ── from here, the JVM runs the MPSC scheduler for good ──
  5. save the CPUs of main
  6. orTimeout(1 ns)                 -> the common pool's delayScheduler starts from main
  7. check that the common pool uses AllCpus; if it started before, warn
  8. a. n times: a new affinity, one virtual thread with it, its carrier read from its name
     b. on each carrier: park 1 ms   -> that carrier's VirtualThread-unparker starts
     c. on each carrier: pin it to its CPU  (last)
```

After it:

```
mpsc-carrier-0 ── one CPU     Read-Poller, Write-Poller  ── every CPU of main
mpsc-carrier-1 ── one CPU     VirtualThread-unparker     ── every CPU of main
...                           common pool                ── every CPU of main
```

A worker of the common pool may still start from a carrier. `AllCpus` makes it, and the worker's
first act is to take back the CPUs saved in step 5:

```
actor on carrier-0 (CPU 2): parallelStream()
  -> the pool starts a worker from carrier-0  -> it has CPU 2
  -> AllCpus: the worker sets the saved CPUs   -> every CPU of main
```

The delayScheduler is not made by the pool's factory: that is why step 6 starts it. Step 4 must
run before the common pool is first used, or it warns; an application that set
`-Djava.util.concurrent.ForkJoinPool.common.threadFactory` keeps its own.

The application's own threads and executors are not covered. Started from an actor, they should
come from `system.scheduler().platformThreadFactory()`, which does what `AllCpus` does.

## The JDK

The MPSC scheduler and `Thread.Affinity` exist only in the JDK of
[openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227), at commit `731930db54`,
on the [`fibers` branch of `openjdk/loom`](https://github.com/openjdk/loom/tree/fibers). There is
no binary. `Thread.Affinity` is not a preview API, so this module compiles at `release=28` without
`--enable-preview`. The root `pom.xml`'s `loom-jdk` profile adds it to the build on a JDK 28 or
newer.

Building it takes about 15 minutes on 16 cores, and about 3 GB of disk.

**1. The tools.** On Ubuntu or Debian:

```sh
sudo apt-get install -y autoconf make zip unzip file gcc g++ \
  libasound2-dev libcups2-dev libfontconfig1-dev \
  libx11-dev libxext-dev libxrender-dev libxrandr-dev libxtst-dev libxt-dev
```

On macOS: the Xcode command line tools (`xcode-select --install`) and `autoconf`
(`brew install autoconf`).

**2. A boot JDK**, a JDK 27 or 28. The Loom build of builds.shipilev.net is one; CI uses it:

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
Looma needs no graphics, so two stub scripts are enough:

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

bash configure --with-boot-jdk="$HOME/boot-jdk" --disable-warnings-as-errors \
  --with-native-debug-symbols=none --with-version-opt=pr227-731930d \
  METAL="$HOME/.metal-stubs/metal" METALLIB="$HOME/.metal-stubs/metallib"
make images
```

`--with-version-opt` makes `java -version` name the commit (`28-internal-pr227-731930d`); the
benchmark's `summary.md` copies that line.

**5. SDKMAN.** Register the image under the name `.sdkmanrc` expects. It is in
`build/<os>-<arch>-server-release/images/jdk`, for example `linux-x86_64` or `macosx-aarch64`.
SDKMAN only links to that folder: keep it.

```sh
sdk install java 28-loom-pr227 "$HOME/openjdk-loom-pr227/build/linux-x86_64-server-release/images/jdk"
sdk env                                            # in this project
java -version                                      # ... (build 28-internal-pr227-731930d)
javap 'java.lang.Thread$Affinity'                  # no error: this is the right JDK
```

## Tests

The scheduler is set once per JVM, so `mvn -pl mpsc-scheduler -am test` runs three Surefire
executions. `core` runs the same contract suites on the builtin.

| Execution | Flags | Runs |
| --- | --- | --- |
| `default-test` | the MPSC scheduler, 4 carriers, stealing | the actor suites of `core`; `MpscAffinityTest`: an actor never leaves its carrier, an offloaded thread can be stolen; `MpscTimerTest` and `MpscTimerStressTest`: timers on the target's carrier, direct and queued arms and cancels |
| `install-test` | no scheduler flag, one JVM per class | `carriers(...)` installing `affine`, and refusing to once a virtual thread has run, or below a `maxPoolSize`. On Linux: each carrier on its own CPU, and the threads an actor makes keep every CPU: the JDK's pollers and timers, the common pool, `platformThreadFactory()`. Elsewhere: the carriers have no cluster |
| `no-native-access-test` | `--illegal-native-access=deny` | the same install in a JVM that refuses native calls: it runs, and nothing is pinned |

Without `-am`, Maven takes `looma-core` from `~/.m2`, which may be older than the sources.
