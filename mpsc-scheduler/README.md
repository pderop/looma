# `mpsc-scheduler`

The adapter of the JDK's MPSC scheduler, `io.github.pderop.looma.scheduler.mpsc`, for
`Carriers.mpsc(n)`. It reaches Looma through the SPI of `core` (`io.github.pderop.looma.spi`).
[`docs/schedulers.md`](../docs/schedulers.md#mpsc-the-jdks-mpsc-scheduler) says what `mpsc`
guarantees; this page says how it works.

- `MpscActorSchedulerProvider`: sets the properties, starts the JDK's pollers and timers, finds
  the carriers and pins them (only with `pinned(true)`).
- `MpscPool`: one `Thread.Affinity` per carrier, drawn from one group. Looma does not need to know
  which carrier each one lands on: the carrier of affinity `i` is pinned to the `i`-th CPU of the
  process. None on a JDK without `Thread.Affinity`.
- `MpscActorScheduler`: an actor's mailbox threads get the affinity of its carrier (none unless
  `affine(true)`), its offloaded threads get none. On a JDK without `Thread.Affinity`, it keeps
  the mailbox threads alive: the thread of an idle actor waits for the next message.
- `JdkAffinity`: finds `Thread.Affinity` once, by reflection. The only class that names it.
- `LinuxCpus`: pins a carrier to one CPU with `sched_setaffinity`, and groups the CPUs by L3.
- `AllCpus`: makes threads that run on every CPU, even when an actor starts them. It is the
  factory of the common pool, and of `platformThreadFactory()`. Why it is needed, and what the
  install does step by step: [`docs/mpsc-install.md`](../docs/mpsc-install.md).

## How the MPSC scheduler runs

Each carrier, `mpsc-carrier-<id>`, is one OS thread, with two queues and, on Linux, its own epoll.
Many threads may add to them.

```
carrier-0
  queue        threads with an affinity to carrier-0   -> only carrier-0 runs them
  stealQueue   threads without an affinity             -> carrier-0, or an idle carrier steals them

an actor's mailbox thread  -> an affinity only with affine(true); none by default
an offloaded thread        -> never an affinity

with an affinity     -> the queue of its carrier, at every start and every wake
without one          -> the stealQueue of the carrier that starts it
an idle carrier      -> steals only from another carrier's stealQueue
                        so with affine(true), a mailbox thread is never stolen
```

On a JDK without `Thread.Affinity`, each carrier has one queue. An idle carrier may take any thread
waiting in it: mailbox threads and offloaded threads.

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

## The JDK

The MPSC scheduler exists only in the JDK of
[openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227), on the
[`fibers` branch of `openjdk/loom`](https://github.com/openjdk/loom/tree/fibers). Looma runs on two
builds of it:

- `pr227`, the pull request. It has `Thread.Affinity`: every setting of `Carriers.mpsc(n)` works.
- `noaff`, the branch `mpsc-without-affinity` of `franz1981/loom`. It has no `Thread.Affinity`: only
  `Carriers.mpsc(n)` works.

Each build is at a fixed commit, named in step 3 below and in CI's
[`build.yml`](../.github/workflows/build.yml), nowhere else. A new commit changes these two places;
the build names stay.

There is no binary. Looma finds `Thread.Affinity` by reflection, so this module compiles at
`release=28` on both, without `--enable-preview`. The root `pom.xml`'s `loom-jdk` profile adds it
to the build on a JDK 28 or newer.

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

**3. The sources**, at the commit of the build, in a folder of its own:

```sh
BUILD=pr227 SHA=5766f815f7248a52bae73950b1c7a32532b872c0
# or: BUILD=noaff SHA=33d7cd32dc9402386565878b8fcb0d5b664362e1
mkdir -p "$HOME/openjdk-loom-$BUILD" && cd "$HOME/openjdk-loom-$BUILD"
curl -fsSL "https://github.com/franz1981/loom/archive/$SHA.tar.gz" | tar -xz --strip-components=1
```

**4. The build.** On Linux:

```sh
bash configure --with-boot-jdk="$HOME/boot-jdk" --disable-warnings-as-errors \
  --with-native-debug-symbols=none --with-version-opt="$BUILD-${SHA:0:7}"
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
  --with-native-debug-symbols=none --with-version-opt="$BUILD-${SHA:0:7}" \
  METAL="$HOME/.metal-stubs/metal" METALLIB="$HOME/.metal-stubs/metallib"
make images
```

`--with-version-opt` makes `java -version` name the build and its commit (`28-internal-pr227-<commit>`);
the benchmark's `summary.md` copies that line. `scripts/publish.sh` names the folder of the results
after the build, without the commit.

**5. SDKMAN.** Register the image under the name `.sdkmanrc` expects. It is in
`build/<os>-<arch>-server-release/images/jdk`, for example `linux-x86_64` or `macosx-aarch64`.
SDKMAN only links to that folder: keep it.

```sh
sdk install java 28-loom-pr227 "$HOME/openjdk-loom-pr227/build/linux-x86_64-server-release/images/jdk"
sdk env                                            # in this project
java -version                                      # ... (build 28-internal-pr227-<commit>)
javap 'java.lang.MpscVirtualThreadScheduler'       # no error: this is a JDK of the pull request
```

The JDK without `Thread.Affinity` goes under another name, for example `28-loom-noaff`. Switch with
`sdk use java 28-loom-noaff`.

## Running Looma on it

It needs the `looma-mpsc-scheduler` jar on the classpath, next to `looma-core`, and
`--enable-native-access=ALL-UNNAMED`: the install reads the CPUs with a native call, and pinning is
one too. Build Looma once, on `pr227`, from the project root:

```sh
sdk env                 # reads .sdkmanrc
mvn clean install       # builds every module, runs every test
```

The README runs the quick start on `pr227`. On `noaff`, only the config `mpsc`
works:

```sh
sdk use java 28-loom-noaff
java --enable-native-access=ALL-UNNAMED \
     -cp core/target/looma-core-1.0-SNAPSHOT.jar:mpsc-scheduler/target/looma-mpsc-scheduler-1.0-SNAPSHOT.jar:examples/target/classes \
     io.github.pderop.looma.examples.quickstart.QuickStart mpsc   # the quick start, on two mpsc carriers
```

The JVM first prints `WARNING: Using experimental MPSC virtual thread scheduler`, then:

```
greeting Ann (#1) on VirtualThread[#32,looma-/greeter]/runnable@mpsc-carrier-1
greeting Bob (#2) on VirtualThread[#32,looma-/greeter]/runnable@mpsc-carrier-1
hello Bob
greeting Cid (#3) on VirtualThread[#32,looma-/greeter]/runnable@mpsc-carrier-0
terminated: true
```

How to read it:

```
Ann, Bob, Cid -> one virtual thread, #32: it waits for the next message instead of ending
Cid (100 ms)  -> the same thread, now on mpsc-carrier-0: with no affinity, an actor can change carrier
```

## Tests

The scheduler is set once per JVM, so `mvn -pl mpsc-scheduler -am test` runs three Surefire
executions. `core` runs the same contract suites on the builtin.

| Execution | Flags | Runs |
| --- | --- | --- |
| `default-test` | the MPSC scheduler, 4 carriers, stealing | the actor suites of `core`; `MpscAffinityTest`: an actor never leaves its carrier, an offloaded thread can be stolen; `MpscTimerTest` and `MpscTimerStressTest`: timers on the target's carrier, direct and queued arms and cancels |
| `install-test` | no scheduler flag, one JVM per class | `carriers(...)` installing `mpsc` (with each setting on its own and both, and with none), and refusing to once a virtual thread has run, or below a `maxPoolSize`. On Linux: each carrier on its own CPU, and the threads an actor makes keep every CPU: the JDK's pollers and timers, the common pool, `platformThreadFactory()`. Elsewhere: the carriers have no cluster |
| `no-native-access-test` | `--illegal-native-access=deny` | the same install in a JVM that refuses native calls: it runs, and nothing is pinned |

On the JDK without `Thread.Affinity`, the tests that need it are skipped. `MpscJdkTest` checks
that the scheduler follows the JDK, and `MpscNoThreadAffinityTest` that only
`Carriers.mpsc(n)` installs.

Without `-am`, Maven takes `looma-core` from `~/.m2`, which may be older than the sources.
