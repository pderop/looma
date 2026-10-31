# The user benchmark

**The question:** an actor owns private data, and only that actor reads it. If the scheduler
keeps the actor on one core, the data stays in that core's cache. Does that save CPU?

**How it answers:** the same program runs under several schedulers, at the same request rate. Each
request makes an actor read a few hundred of its objects. The benchmark compares the CPU each
scheduler spends per request, the latency, and how often actors moved to another core.

**The answer**, on an AMD Ryzen 9 9950X: when an actor's data fits in its core's L2 cache, the
schedulers that keep actors on their core spend less CPU per request than the JDK's scheduler:
42.1% less for `affine`, 57.3% less for `mpsc`. When the data fits in no cache, the JDK's
scheduler cannot keep up with the rate. With its workers pinned, it keeps up, but spends 16% to
19% more CPU per request than those two. The numbers:
[`results/2026-10-31-ryzen9-9950x/summary.md`](results/2026-10-31-ryzen9-9950x/summary.md).

In those results, `affine` is Looma's copy of the Netty-VirtualThread-Scheduler, no longer built
since. `mpsc` is the scheduler that `Carriers.affine(n)` runs today.

- Code: [`examples/…/examples/user`](../examples/src/main/java/io/github/pderop/looma/examples/user)
- Scripts: [`scripts/bench.sh`](../scripts/bench.sh) runs it, and
  [`scripts/publish.sh`](../scripts/publish.sh) copies a result to the docs

## Words used here

- **Virtual thread**: a Java thread that the JVM runs on top of a few OS threads.
- **Carrier**: one of those OS threads. A virtual thread runs on a carrier.
- **Scheduler**: what picks the carrier on which a virtual thread runs.
- **Park**: a virtual thread that waits (for a socket, for a lock) gives its carrier back.
  When it is woken up, the scheduler picks a carrier for it again, maybe another one.
- **Carrier-affine scheduler**: each actor has a home carrier, and always runs on it. The threads
  it offloads start there, but an idle carrier may steal them. In this benchmark, `mpsc` is
  carrier-affine.
- **Work stealing**: an idle carrier takes work queued on another carrier.
- **Pinned**: an OS thread that may run on one CPU only.
- **CPU, core**: Linux shows two CPUs per core when the core runs two threads at once (SMT). The
  two CPUs of a core share its caches.
- **L2, L3**: CPU caches. Each core has its own L2. The L3 is shared by a group of cores (on AMD,
  a CCD of 8 cores). Memory (DRAM) is much slower than both.

## What it measures

### The program

The program plays a made-up service that recommends users. Each actor owns a group of users. To
answer a request, the actor calls a backend, then walks a chain of friends through its users.
The story does not matter: what matters is that each request makes the actor read and write a
few hundred of its own objects, spread over memory.

```
FrontendServer ──▶ Main.handleRequest ──▶ UserGroup ──GET──▶ BackendServer
 (the load)                               (an actor)         (another process)
```

**At start**, in [`Main`](../examples/src/main/java/io/github/pderop/looma/examples/user/Main.java):

1. The JVM creates the scheduler named by `-Dbench.scheduler`, with one carrier per carrier CPU.
2. It creates one `UserGroup` actor per carrier, placed on that carrier.
3. Each group creates its users (`--users-per-group`), on its own carrier: the core that reads
   them is the one that wrote them first. Group `i` owns the users `i`, `i + groups`,
   `i + 2 × groups`, and so on.
   - A user has a friend, a visit count, a score and the time of its last visit.
   - The users are created in a random order. The friendships form one big cycle, in another
     random order. So a walk jumps around in memory, and never loops on a few users.
4. Each group opens a fixed pool of connections to the backend.

**The load**, `FrontendServer`, runs in the benchmark JVM. It is not an HTTP server: it calls
`Main.handleRequest` directly. It has one platform thread per client CPU, each pinned to its CPU.
Each thread sends its requests on a fixed schedule, so that all the threads together send
`--rate` requests per second. A late request does not delay the next ones. When a thread has
too many requests without an answer, it drops the new ones and counts them. Each request asks
for a random user.

**One request:**

1. `Main.handleRequest` sends a `Recommend` message to the group that owns the user, with `ask`.
2. The actor gets `Recommend`. It starts a virtual thread for the GET, and goes on with its next
   message: it does not wait. A group can have many GETs in flight.
3. The virtual thread takes a free connection, sends `GET /user`, and parks on the response.
   When the response arrives, the JDK's poller wakes it up (see
   [the poller modes](#the-schedulers)), and the scheduler runs it on a carrier. It gives the
   connection back, and sends `UpstreamAnswered` to the actor.
4. The actor gets `UpstreamAnswered`. It walks `--depth` users: it starts at the user asked for,
   then follows the friends. It writes in each one (visits, score, time). Then it answers the
   client.
5. The client records the latency, from the planned send time to the answer.

Between steps 2 and 4, the actor's mailbox is empty, so its virtual thread ends.
`UpstreamAnswered` starts a new one, and the scheduler picks the carrier it runs on. If it is the
same carrier as before, the users are still in that core's cache, and the walk is fast. If not,
the users must be read again, and the walk is slow.

**The backend**, [`BackendServer`](../examples/src/main/java/io/github/pderop/looma/examples/user/BackendServer.java),
runs in a JVM of its own. One thread accepts the connections. The others read the requests and
answer `200 OK` with a small fixed body, right away.

**One run** is one JVM with one scheduler. The load runs for 5 seconds of warm-up, then the
counters are reset, and 20 seconds are measured. At the end, the JVM prints the requests served
per second, the CPU per request, the migrations, the latencies, and whether it kept up with the
rate.

### The schedulers

All runs use the same jar and the same JVM flags. Only `-Dbench.scheduler` changes.

| Scheduler | What it is |
| --- | --- |
| `mpsc` | `Carriers.affine(n)`: the JDK's MPSC scheduler (openjdk/loom pull request 227). One carrier per CPU, each pinned to its CPU. Each actor has a `Thread.Affinity`: it never leaves its carrier. An idle carrier steals only the GET threads, from any carrier. |
| `jdk` | `Carriers.builtin(n)`: the JDK's default scheduler, with as many carriers as the others. Its carriers are not pinned. |
| `jdk-pinned` | `jdk`, with each carrier pinned to one CPU by the script (Linux only). |

`--schedulers` picks the ones to run, for example `--schedulers mpsc,jdk`. By default, all three.
[`schedulers.md`](schedulers.md) explains each scheduler.

The poller modes:

- `mpsc` uses mode 4. Each carrier has its own epoll, and looks at it between two rounds of its
  queue. The JDK still starts one Read-Poller thread, for the virtual threads that run on no MPSC
  carrier, and a Write-Poller. There is no Master-Poller.
- `jdk` and `jdk-pinned` keep the JDK's default mode.

### Where things run

Three lists of CPUs, with commas and ranges (`2-7,10-15`), and no CPU in two lists. The defaults
are those of the AMD machine ([The layout](#the-layout)); on another machine, give the three
lists. `lscpu -e` shows the core and the caches of each CPU.

- **`--cpus-carriers`**: one carrier and one group per CPU. Give one CPU per core: the script
  refuses two CPUs of the same core.
- **`--cpus-client`**: the load, one `FrontendServer` thread per CPU.
- **`--cpus-backend`**: the backend, one thread per CPU.

The client threads run in the benchmark JVM, but their CPU time is not counted: "cpu µs/req" is
the CPU of the server alone.

Who pins what:

- The script starts the benchmark JVM with `taskset` on the carrier CPUs, and the backend with
  `taskset` on the backend CPUs.
- The carriers of `mpsc` pin themselves. Each one takes one CPU of the JVM's list.
- The client threads pin themselves too, to the client CPUs.
- The JDK's Master-Poller, the thread that wakes the virtual threads whose socket has data,
  runs on the carrier CPUs, like the other threads of the JVM. So do the Write-Poller and, in
  mode 4, the JDK's own Read-Poller.
- In `jdk-pinned`, the script pins each JDK carrier to one carrier CPU, during the warm-up.
- In `jdk`, nothing pins the carriers. They can move between the carrier CPUs.

On macOS nothing is pinned. It is fine to test the script, not to measure.

### The sizes

- `--users-per-group` (8000,200000): the cases, one size of the groups each. Each user takes
  about 82 bytes, so a group of 8000 takes about 640 KiB. 8000 is named `default`, 200000 `big`.
- `--depth` (256): how many users a request reads.

One group must fit in a core's L2 cache, and all the groups together must be several times bigger
than one L2. A moved actor then reads its data from the L3, or from memory if all the groups are
bigger than the L3. That is the `default` case. In the `big` case, 200000 users per group, a
group fits in no cache.

## Running it

You need Linux, the Loom JDK of openjdk/loom pull request 227 and the project built (the
[README](../README.md#quickstart) shows both), and `perf` for the `perf c2c` and `perf stat` runs.

The script first runs [`scripts/prepare-linux-bench.sh`](../scripts/prepare-linux-bench.sh),
with sudo: turbo off, the `performance` governor, and perf allowed. It saves what it changed;
`sudo scripts/prepare-linux-bench.sh --restore` puts it back, and a reboot does too.
`scripts/prepare-linux-bench.sh --check` shows the current state.

```sh
scripts/bench.sh                                     # on the AMD machine
scripts/bench.sh --cpus-carriers <list> --cpus-client <list> --cpus-backend <list>   # elsewhere
scripts/bench.sh --users-per-group 8000              # the default case only
scripts/bench.sh --schedulers mpsc,jdk --reps 1      # two schedulers, one repetition
```

`<list>` is a list of CPUs with commas and ranges only: `2-7,10-15`. The three lists must not
share a CPU, and the carriers need one CPU per core: the script refuses anything else.
[Where things run](#where-things-run) says what each list is for, and how to choose them on
another machine. `scripts/bench.sh --help` lists every option, with its default.

What the script does:

1. It prepares the machine (`prepare-linux-bench.sh`), then starts the backend, once for all the
   runs, under `taskset` on the backend CPUs.
2. It runs each run under `taskset` on the carrier CPUs, with a fixed heap (`-Xms2g -Xmx2g
   -XX:+AlwaysPreTouch`) and a GC log. In `jdk-pinned`, it pins each JDK carrier to one carrier
   CPU, during the warm-up.
3. It runs the cases one after the other: steps 3 to 6 for `default`, then for `big`.
   For each repetition (`--reps`, 3 by default), it runs every scheduler at `--depth`, then at
   depth 0. The repetitions follow each other: r1 runs every scheduler, then r2, then r3.
4. Then, if `perf` is there, one more run per scheduler at `--depth`, the c2c run:
   - `perf c2c` samples the memory loads during the measure, minus 3 seconds at each end;
   - the JVM counts its live `StackChunk`s twice: during the warm-up, and after the measure.
     Each count is a full GC, so none falls in the measure, and the GC column leaves them out.
5. Then one more run per scheduler at `--depth`, the stat run: `perf stat` counts the cycles and
   the instructions of the server's threads during the same window.
6. Then one more run per scheduler at `--depth`, the epsilon run: the Epsilon GC, which frees
   nothing, so no GC runs (`-XX:+UseEpsilonGC -Xms12g -Xmx12g -XX:+AlwaysPreTouch`). A run
   allocates about 5 GiB.
7. It writes `summary.md`, with both cases.

With the 4 default schedulers, that is 72 runs, about 37 minutes; 36 runs for one case.

Everything goes to `bench/<date>-<time>/` (`--out` changes it): `summary.md`, and the files each
of its numbers comes from. [`results/README.md`](results/README.md) explains them, and where each
number comes from.

To keep a result in the docs, [`scripts/publish.sh`](../scripts/publish.sh) copies `summary.md`,
the `<case>-c2c-<scheduler>.txt` files, and the consoles, GC logs and `perf stat` files of
`runs/` to `docs/results/<date>-<cpu>/`, with the date and the CPU read from `summary.md`. It
empties that folder first, so a second campaign of the same day replaces the first. The
`perf c2c` data files are not copied.

```sh
scripts/publish.sh bench/<date>-<time>       # e.g. docs/results/2026-10-31-ryzen9-9950x/summary.md
scripts/publish.sh -n bench/<date>-<time>    # -n: show what it would copy, copy nothing
```

## Results

The latest campaign:
[`results/2026-10-31-ryzen9-9950x/summary.md`](results/2026-10-31-ryzen9-9950x/summary.md). All
the campaigns, and how to read them: [`results/README.md`](results/README.md).

### AMD Ryzen 9 9950X

A campaign is two commands, on this machine. The first runs both cases, with the CPUs of the
layout below as its defaults. The second copies the result to the docs.

```sh
scripts/bench.sh                          # writes bench/<date>-<time>/summary.md
scripts/publish.sh bench/<date>-<time>    # copies it to docs/results/<date>-ryzen9-9950x/
```

#### The machine

- **CPU:** AMD Ryzen 9 9950X (Zen 5), one socket.
- **OS:** Linux 7.0 (Ubuntu).
- **JDK:** the Loom JDK of openjdk/loom pull request 227, built from source
  (`28-internal-pr227-731930d`). The campaigns of 2026-10-22 and 2026-10-24 ran on the Loom JDK
  28 of builds.shipilev.net (b59).
- **Setup:** `scripts/prepare-linux-bench.sh`, run by `bench.sh`: turbo off, so every core runs
  at 4.3 GHz; `performance` governor; perf allowed.

#### Its architecture

```
                         AMD Ryzen 9 9950X
┌─────────────────── CCD0 ───────────────────┐ ┌─────────────────── CCD1 ───────────────────┐
│ core 0   core 1   ...   core 7             │ │ core 8   core 9   ...   core 15            │
│ CPU 0    CPU 1          CPU 7              │ │ CPU 8    CPU 9          CPU 15             │
│ CPU 16   CPU 17         CPU 23             │ │ CPU 24   CPU 25         CPU 31             │
│ [L1+L2]  [L1+L2]        [L1+L2]  1 MiB each│ │ [L1+L2]  [L1+L2]        [L1+L2]  1 MiB each│
│ ──────────── L3: 32 MiB, shared ────────── │ │ ──────────── L3: 32 MiB, shared ────────── │
└────────────────────────────────────────────┘ └────────────────────────────────────────────┘
                 └────────────── Infinity Fabric (links the two) ──────────────┘
```

- **16 cores, 32 CPUs.** Each core runs two threads (SMT), so Linux sees two CPUs per core:
  CPU `n` and CPU `n + 16` are the same core. They share the core's L1 and L2 caches.
- **Two CCDs** (chips of 8 cores): cores 0-7 and cores 8-15.
- **Caches:**

  | Cache | Size | Shared by |
  | --- | --- | --- |
  | L1 data | 48 KiB per core | one core (its 2 CPUs) |
  | L2 | 1 MiB per core | one core (its 2 CPUs) |
  | L3 | 32 MiB per CCD, 64 MiB in all | the 8 cores of a CCD |

- **One memory node:** both CCDs reach the memory the same way.

So where an actor finds its data depends on where it resumes:

1. on the same core: its data is still in that core's L2, the fastest case;
2. on another core of the same CCD: it reads its data again from the shared L3;
3. on a core of the other CCD: it reads its data through the Infinity Fabric, the slowest case.

#### The layout

The CPU lists, the script's defaults:

```
carriers     CPUs 2-7,10-15     12 carriers: 6 per CCD, one per core
clients      CPUs 0,1,16,17     the load generator: both CPUs of cores 0 and 1 (CCD0)
backend      CPUs 8,9,24,25     the backend: cores 8 and 9 (CCD1)
```

- Each carrier has a core to itself: its SMT twin stays idle.
- The carriers are on both CCDs, 6 on each. `mpsc` steals across both. It never moves an
  actor.
- The sizes: one group is about 640 KiB, which fits in a 1 MiB L2. The 12 groups take about
  7.5 MiB, several times one L2. But the 6 groups of a CCD (3.8 MiB) fit in its 32 MiB L3: an
  actor that moves within a CCD finds its data in the L3. So this layout measures the L2 against
  the L3, and the crossing between the two CCDs.

## Why it is built this way

**Why walk a graph.** Each user gives the next one. The CPU cannot read ahead, so the walk goes
only as fast as the cache that holds the users. The users are created in a random order, and the
friendships follow another random order, so the walk jumps around in memory.

**Why the walk writes.** Each step updates the user, so the actor's data is modified in the
cache of the core that runs it. A moved actor must read it again on its new core: from the L3,
from the old core's cache, or from memory.

**Why a real socket.** A virtual thread that waits on a socket is woken up by the JDK's poller, and
the scheduler picks its carrier then. A carrier-affine scheduler puts it back on its home carrier,
unless an idle carrier steals it. `Thread.sleep` would not take this path. The backend runs in its
own process, so its CPU is not counted and it does not slow down the carriers.

**Why repetitions.** Each run is a new JVM: the JIT and the GC make their own choices in each
one. Three runs show whether a result holds. They follow each other, so a drift of the machine
hits every scheduler alike.

**Why depth 0.** At depth 0, a request reads no user: what is left is the cost of the
scheduling. The depth 256 line minus the depth 0 line is the cost of the walk.

**Why a fixed rate.** At the same rate, all schedulers do the same work, so we can compare their
CPU cost and their latency. Latency is measured from the planned send time, so a late client
cannot hide a delay.

**Why pinned clients.** The client threads are the load, not the server. On their own CPUs, they
do not compete with the carriers.

**Why one carrier per core.** The two CPUs of a core share its L2. Two carriers there would share
the cache each one is meant to keep to itself.

**Why the JVM runs under `taskset`.** It gives `mpsc` its CPU list. It
also keeps the JVM's other threads, like the GC and the JIT, on the carrier CPUs, away from the
clients and the backend.

**Why `jdk-pinned`.** It tells whether pinning alone makes the difference.

**Why only these three.** Earlier campaigns also ran other setups of the same schedulers, and,
until 2026-10-31, `affine`, Looma's copy of the Netty-VirtualThread-Scheduler: see
[Older campaigns](results/README.md#older-campaigns). Letting an idle carrier steal an actor cost
CPU, in the published campaigns. Short runs, not published, also found stealing within an L3
faster than from any carrier, and the MPSC scheduler faster with poller mode 4 than with mode 3.
So each scheduler keeps one setup.

**Why count the StackChunks.** A parked virtual thread keeps its stack in a `StackChunk`, on the
heap. If chunks were kept alive and piled up, the GC would have more to do under some
schedulers. Two counts show whether they pile up.
