# `mpsc-scheduler`

The adapter of the JDK's MPSC scheduler, `io.github.pderop.looma.scheduler.mpsc`, for
`Carriers.mpsc(n)`. It reaches Looma through the SPI of `core` (`io.github.pderop.looma.spi`).
[`docs/schedulers.md`](../docs/schedulers.md#mpsc-the-jdks-mpsc-scheduler) says what `mpsc`
guarantees; this page says how it works.

- `MpscActorSchedulerProvider`: sets the properties, starts the JDK's pollers and timers, finds
  the carriers and pins them (not with `pinned(false)`).
- `MpscPool`: one `Thread.Affinity` per carrier, drawn from one group. Looma does not need to know
  which carrier each one lands on: the carrier of affinity `i` is pinned to CPU `i`.
- `MpscActorScheduler`: an actor's mailbox threads get the affinity of its carrier (none with
  `affinity(false)`), its offloaded threads get none.
- `LinuxCpus`: pins a carrier to one CPU with `sched_setaffinity`, and groups the CPUs by L3.
- `AllCpus`: makes threads that run on every CPU, even when an actor starts them. It is the
  factory of the common pool, and of `platformThreadFactory()`.

## How the MPSC scheduler runs

Each carrier, `mpsc-carrier-<id>`, is one OS thread, with two queues and, on Linux, its own epoll.
Many threads may add to them.

```
carrier-0
  queue        VTs with an affinity (an actor's mailbox threads)  -> only carrier-0 takes them
  stealQueue   VTs without one (offloaded threads, and the mailbox -> carrier-0, or an idle carrier
               threads under affinity(false))

a thread with an affinity  -> the queue of its carrier, at every start and every wake
a thread without one       -> the stealQueue of the carrier that starts it
an idle carrier steals     -> from another carrier's stealQueue only: with affinity, never an actor's
                              mailbox thread
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

The JDK starts some of its threads from the first thread that needs them. Started from a pinned
carrier, they would keep its one CPU. What the install does about it, step by step:
[`docs/mpsc-install.md`](../docs/mpsc-install.md).

## The JDK

The MPSC scheduler and `Thread.Affinity` exist only in the JDK of
[openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227), at commit `5766f815f7`,
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
curl -fsSL https://github.com/franz1981/loom/archive/5766f815f7248a52bae73950b1c7a32532b872c0.tar.gz \
  | tar -xz --strip-components=1
```

**4. The build.** On Linux:

```sh
bash configure --with-boot-jdk="$HOME/boot-jdk" --disable-warnings-as-errors \
  --with-native-debug-symbols=none --with-version-opt=pr227-5766f81
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
  --with-native-debug-symbols=none --with-version-opt=pr227-5766f81 \
  METAL="$HOME/.metal-stubs/metal" METALLIB="$HOME/.metal-stubs/metallib"
make images
```

`--with-version-opt` makes `java -version` name the commit (`28-internal-pr227-5766f81`); the
benchmark's `summary.md` copies that line.

**5. SDKMAN.** Register the image under the name `.sdkmanrc` expects. It is in
`build/<os>-<arch>-server-release/images/jdk`, for example `linux-x86_64` or `macosx-aarch64`.
SDKMAN only links to that folder: keep it.

```sh
sdk install java 28-loom-pr227 "$HOME/openjdk-loom-pr227/build/linux-x86_64-server-release/images/jdk"
sdk env                                            # in this project
java -version                                      # ... (build 28-internal-pr227-5766f81)
javap 'java.lang.Thread$Affinity'                  # no error: this is the right JDK
```

## Tests

The scheduler is set once per JVM, so `mvn -pl mpsc-scheduler -am test` runs three Surefire
executions. `core` runs the same contract suites on the builtin.

| Execution | Flags | Runs |
| --- | --- | --- |
| `default-test` | the MPSC scheduler, 4 carriers, stealing | the actor suites of `core`; `MpscAffinityTest`: an actor never leaves its carrier, an offloaded thread can be stolen; `MpscTimerTest` and `MpscTimerStressTest`: timers on the target's carrier, direct and queued arms and cancels |
| `install-test` | no scheduler flag, one JVM per class | `carriers(...)` installing `mpsc` (also unpinned, and without affinity), and refusing to once a virtual thread has run, or below a `maxPoolSize`. On Linux: each carrier on its own CPU, and the threads an actor makes keep every CPU: the JDK's pollers and timers, the common pool, `platformThreadFactory()`. Elsewhere: the carriers have no cluster |
| `no-native-access-test` | `--illegal-native-access=deny` | the same install in a JVM that refuses native calls: it runs, and nothing is pinned |

Without `-am`, Maven takes `looma-core` from `~/.m2`, which may be older than the sources.
