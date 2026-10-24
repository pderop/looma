# The user benchmark

**The question:** an actor owns private data, and only that actor reads it. If the scheduler
keeps the actor on one core, the data stays in that core's cache. Does that save CPU?

**How it answers:** the same program runs under four schedulers, at the same request rate. Each
request makes an actor read a few hundred of its objects. The benchmark compares the CPU each
scheduler spends per request, the latency, and how often actors moved to another core.

**The answer**, on an AMD Ryzen 9 9950X: when an actor's data fits in its core's L2 cache, the
schedulers that keep actors on their core spend 33.4% to 42.8% less CPU per request than the
JDK's scheduler. When the data fits in no cache, the JDK's scheduler cannot keep up with the rate.
With its workers pinned, it keeps up, but spends 15% to 18% more CPU per request. The numbers:
[`results/2026-10-24-ryzen9-9950x/summary.md`](results/2026-10-24-ryzen9-9950x/summary.md).

- Code: [`examples/…/examples/user`](../examples/src/main/java/io/github/pderop/looma/examples/user)
- Scripts: [`scripts/bench.sh`](../scripts/bench.sh) runs it, and
  [`scripts/publish.sh`](../scripts/publish.sh) copies a result to the docs

## Words used here

- **Virtual thread**: a Java thread that the JVM runs on top of a few OS threads.
- **Carrier**: one of those OS threads. A virtual thread runs on a carrier.
- **Scheduler**: what picks the carrier on which a virtual thread runs.
- **Park**: a virtual thread that waits (for a socket, for a lock) gives its carrier back.
  When it is woken up, the scheduler picks a carrier for it again, maybe another one.
- **Carrier-affine scheduler**: each virtual thread has a home carrier, and resumes on it. In
  this benchmark, `stealing-local` and `stealing-local-home` are carrier-affine.
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
   When the response arrives, the JDK's Master-Poller thread wakes it up, and the scheduler runs
   it on a carrier. It gives the connection back, and sends `UpstreamAnswered` to the actor.
4. The actor gets `UpstreamAnswered`. It walks `--depth` users: it starts at the user asked for,
   then follows the friends. It writes in each one (visits, score, time). Then it answers the
   client.
5. The client records the latency, from the planned send time to the answer.

Between steps 2 and 4, the actor's mailbox is empty, so its virtual thread ends.
`UpstreamAnswered` starts a new one, and the scheduler picks the carrier it runs on. If it is the same carrier as before, the
users are still in that core's cache, and the walk is fast. If not, the users must be read
again, and the walk is slow.

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
| `stealing-local` | The Netty carrier-affine scheduler. One carrier per CPU, each pinned to its CPU. An idle carrier can steal work, but only from carriers that share its L3 cache. |
| `stealing-local-home` | `stealing-local` with `actorsStayHome()`, a Looma patch: an idle carrier can steal work only from carriers that share its L3 cache, and never an actor. Only the GET threads can move. |
| `jdk` | The JDK's default scheduler, with as many carriers as the others. Its carriers are not pinned. |
| `jdk-pinned` | The JDK's default scheduler, with each carrier pinned to one CPU (Linux only). |

The carrier-affine schedulers also set `jdk.pollerMode=3`, so that each carrier has its own
read poller. `jdk` keeps the JDK's default mode.

### Where things run

Three lists of CPUs, as `taskset` writes them (`2-7,10-15`). The defaults are those of the AMD
machine ([The layout](#the-layout)); on another machine, give the three lists. `lscpu -e` shows
the core and the caches of each CPU.

- **`--cpus-carriers`**: one carrier and one group per CPU. Give one CPU per core: the script
  refuses two CPUs of the same core.
- **`--cpus-client`**: the load, one `FrontendServer` thread per CPU.
- **`--cpus-backend`**: the backend, one thread per CPU.

The client threads run in the benchmark JVM, but their CPU time is not counted: "cpu µs/req" is
the CPU of the server alone.

Who pins what:

- The script starts the benchmark JVM with `taskset` on the carrier CPUs, and the backend with
  `taskset` on the backend CPUs.
- The carriers of `stealing-local` and `stealing-local-home` pin themselves. Each one takes one
  CPU of the JVM's list.
- The client threads pin themselves too, to the client CPUs.
- The JDK's Master-Poller, the thread that wakes the virtual threads whose socket has data,
  runs on the carrier CPUs, like the other threads of the JVM.
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

You need Linux, a Loom JDK 28 and the project built (the [README](../README.md#quickstart)
shows both), and `perf` for the `perf c2c` and `perf stat` runs.

The script first runs [`scripts/prepare-linux-bench.sh`](../scripts/prepare-linux-bench.sh),
with sudo: turbo off, the `performance` governor, and perf allowed. It saves what it changed;
`sudo scripts/prepare-linux-bench.sh --restore` puts it back, and a reboot does too.
`scripts/prepare-linux-bench.sh --check` shows the current state.

```sh
scripts/bench.sh                                     # on the AMD machine
scripts/bench.sh --cpus-carriers <list> --cpus-client <list> --cpus-backend <list>   # elsewhere
scripts/bench.sh --users-per-group 8000              # the default case only
```

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

That is 72 runs, about 37 minutes; 36 runs for one case. `--help` lists the options.

## What it writes

Everything goes to `bench/<date>-<time>/` (`--out` changes it):

- **`summary.md`**: the results.
- **`<case>-c2c-<scheduler>.txt`**: the full `perf c2c` report of each case and scheduler.
- **`runs/<case>-<run>-<scheduler>-<depth>.out`** and **`.gc.log`**: the console and the GC log
  of each run, for example `runs/default-r1-stealing-local-home-256.out`,
  `runs/big-c2c-jdk-256.out` or `runs/default-epsilon-stealing-local-home-256.out`.
- **`runs/<case>-stat-<scheduler>.csv`**: the `perf stat` counts of each thread.
- **`runs/<case>-c2c-<scheduler>.data`**: the `perf c2c` data, which only `perf report` reads.

Every number of `summary.md` comes from one of these files, and each of its sections says which.

`summary.md` starts with a header: the date, the machine, the CPU, the turbo, the JDK, the CPUs
of each part, the heap, and the command. Then, with no reading of the numbers:

- **The runs:** how the runs were made.
- **perf stat:** per case and scheduler, from its stat run, with the formulas.
- **perf c2c:** per case and scheduler, from its c2c run, with the formulas.
- **Controls:** per case, scheduler and depth, the rate reached, the GC pauses, the live
  StackChunks.
- **In short:** per case, scheduler and depth, the CPU and the latency of r1 to r3.
- **Epsilon:** per case and scheduler, the CPU with G1 and with Epsilon, and the heap used.

| Column | What it is |
| --- | --- |
| **cpu µs/req** | the CPU cost of one request, the mean of r1 to r3. All schedulers serve the same rate, so this is the main number to compare. "not a measurement" when a run could not keep up with its rate, a GET failed, or a pinning failed |
| **cpu range** | the lowest and the highest cpu µs/req of r1 to r3: how much the runs differ |
| **vs jdk** | cpu µs/req against `jdk`'s, in the same case and at the same depth |
| **walk µs** | cpu µs/req at depth 256 minus at depth 0: the cost of reading the users |
| **p50, p99, p99.9 µs** | how long a request waited for its answer: 50%, 99% and 99.9% of the requests got their answer faster than that. The latency starts at the planned send time, not when the request was really sent. The lowest and the highest of r1 to r3 |
| **migrations** | how often an actor ran a message on another carrier than the message before it. One message in 64 is checked |
| **GCs** | the number of GC pauses, the lowest and the highest of r1 to r3. It should be about the same for all schedulers |
| **instructions/req, cycles/req** | the instructions and the cycles of the server's threads, over the requests of the measure |
| **IPC, cycles per instruction** | the instructions run per cycle, and its inverse. A low IPC means the core waits, most often for memory |
| **Load Operations, Load Remote HITM** | copied from the c2c report: the number of sampled loads, and the number that took a line modified behind another L3. On the 9950X, that is a line from the other CCD: what a move to a far core costs |
| **other L3** | Load Remote HITM over Load Operations. Compare this share between schedulers, not the counts: the runs do not sample the same number of loads |
| **DRAM** | the share of the sampled loads served by the memory |
| **live StackChunks** | during the warm-up → after the measure. A `StackChunk` holds the stack of a parked virtual thread |
| **cpu µs/req G1, Epsilon** | the cpu µs/req of the same scheduler at depth 256: with G1, the mean of r1 to r3; with Epsilon, the epsilon run. Their difference is the CPU of the GC, with this caveat: Epsilon never reuses memory, so every allocation touches new memory |
| **heap used** | what the epsilon run allocated, in all: Epsilon frees nothing |
| **note** | in perf stat and perf c2c: "this run: not a measurement" when the stat or c2c run itself was not, "r1 to r3: not a measurement" when the runs of that case and scheduler were not. Their counts are then not comparable with the others |

To keep a result in the docs, [`scripts/publish.sh`](../scripts/publish.sh) copies `summary.md`,
the `<case>-c2c-<scheduler>.txt` files, and the consoles, GC logs and `perf stat` files of
`runs/` to `docs/results/<date>-<cpu>/`, with the date and the CPU read from `summary.md`. It
empties that folder first, so a second campaign of the same day replaces the first. The
`perf c2c` data files are not copied.

```sh
scripts/publish.sh bench/<date>-<time>       # e.g. docs/results/2026-10-24-ryzen9-9950x/summary.md
scripts/publish.sh -n bench/<date>-<time>    # -n: show what it would copy, copy nothing
```

## Where the numbers come from

Every number of `summary.md` is read from a file of `runs/`, or from a c2c report, or computed
from other numbers of the same line. This section shows how, on one line of each table.

### perf stat

The line:

| case | scheduler | instructions | cycles | requests | instructions/req | IPC | cycles per instruction | cycles/req | note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| default | jdk | 287,653,929,416 | 307,912,670,779 | 2,100,000 | 136,978 | 0.93 | 1.07 | 146,625 | |

It comes from `runs/default-stat-jdk.csv`, the output of `perf stat` during the stat
run of `jdk`. The file has one line per thread and counter, for example:

```
ForkJoi..rker-4-372117,24476905534,,cycles,6123516595,100.00,,
ForkJoi..rker-4-372117,22422909869,,instructions,6123503855,100.00,,
```

Split on the commas, field 1 is the thread, field 2 is a number, and field 4 is the word that
says what the number counts: `cycles` or `instructions`. Field 3 is empty, so `,,` comes before
the word. The comment at the top of the file lists every field.

1. **instructions** is the sum of field 2 on the lines whose field 4 is `instructions`, for every
   thread but the `client-*` ones. The client threads are the load, not the server. Lines with
   `<not counted>` have no number: those threads did not run.
2. **cycles** is the same sum, on the lines whose field 4 is `cycles`.
3. **requests** is not counted: it is the rate times the window of perf stat, 150,000 req/s ×
   14 s. The line `throughput` of the console of the run, `runs/default-stat-jdk-256.out`,
   shows that the run kept that rate.
4. The other columns divide these three numbers:

   ```
   instructions/req       = instructions / requests  = 287,653,929,416 / 2,100,000       = 136,978
   cycles/req             = cycles / requests        = 307,912,670,779 / 2,100,000       = 146,625
   IPC                    = instructions / cycles    = 287,653,929,416 / 307,912,670,779 = 0.93
   cycles per instruction = cycles / instructions    = 307,912,670,779 / 287,653,929,416 = 1.07
   ```

   IPC is the instructions run per cycle; cycles per instruction is its inverse. So
   cycles/req = instructions/req × cycles per instruction.

### perf c2c

Load Operations, Load Remote HITM and Load DRAM are copied from the top of the c2c report,
`<case>-c2c-<scheduler>.txt`, block `Trace Event Information`: the lines `Load Operations`,
`Load Remote HITM`, and `Load Local DRAM` plus `Load Remote DRAM`. migrations is the line
`migrations` of the console of the c2c run. Then:

```
other L3 = Load Remote HITM / Load Operations
DRAM     = Load DRAM / Load Operations
```

### Controls and In short

They are read from the console of each run, `runs/<case>-<run>-<scheduler>-<depth>.out`:

- req/s: the line `throughput`.
- cpu µs/req: the line `cpu`. The table gives the mean of r1 to r3, and the lowest and the
  highest.
- latencies: the line `latency`, its p50, p99 and p99.9. The table gives the lowest and the
  highest of r1 to r3.
- migrations: the line `migrations`, the mean of r1 to r3.
- live StackChunks: the line `stackchunks` of the c2c run.
- note: "not a measurement" when the console says the run did not keep up with its rate, a
  GET failed, or a pinning failed. For `jdk-pinned`, the script adds a line at the end of the
  console: `pinning`, how many workers it pinned. Fewer workers pinned than carriers is a
  failed pinning.

GCs is the number of lines `Pause` of the GC log of the run, `runs/<...>.gc.log`, without the
full GCs of the StackChunk counts (`Heap Inspection`).

vs jdk and walk compare two lines of the same table:

```
vs jdk = cpu µs/req / cpu µs/req of jdk, same case and depth, - 1
walk   = cpu µs/req at depth 256 - cpu µs/req at depth 0
```

### Epsilon

- cpu µs/req G1: the same number as in In short, at depth 256.
- cpu µs/req Epsilon: the line `cpu` of the console of the epsilon run,
  `runs/<case>-epsilon-<scheduler>-256.out`.
- heap used: at exit, Epsilon writes the heap in its GC log, `runs/<case>-epsilon-<scheduler>-256.gc.log`,
  as a line like this one, from `runs/default-epsilon-stealing-local-home-256.gc.log`:
  `space 12582912K,  75% used [0x0000000500000000, 0x000000074619cdd8, 0x0000000800000000)`.
  The three addresses are the start of the heap, its top (where the next object would go) and
  its end. heap used = top - start: here 0x74619cdd8 - 0x500000000 = 9,766,030,808 bytes, so
  9,313 MiB.

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```

## Results

One section per machine. Each campaign has its own folder in `docs/results/`, with its
`summary.md`.

### AMD Ryzen 9 9950X

The results:

- [`results/2026-10-24-ryzen9-9950x/summary.md`](results/2026-10-24-ryzen9-9950x/summary.md):
  the latest, after [#9](https://github.com/pderop/looma/pull/9).
- [`results/2026-10-22-ryzen9-9950x/summary.md`](results/2026-10-22-ryzen9-9950x/summary.md):
  before #9. The scheduler then started its scan for an idle carrier at a random one, instead of
  the first one.
  #9 removed that change.

A campaign is two commands, on this machine. The first runs both cases, in about 37 minutes,
with the CPUs of the layout below as its defaults. The second copies the result to the docs.

```sh
scripts/bench.sh                          # writes bench/<date>-<time>/summary.md
scripts/publish.sh bench/<date>-<time>    # copies it to docs/results/<date>-ryzen9-9950x/
```

#### The machine

- **CPU:** AMD Ryzen 9 9950X (Zen 5), one socket.
- **OS:** Linux 7.0 (Ubuntu).
- **JDK:** Loom build of JDK 28 (Shipilev, b59).
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
- The carriers are on both CCDs, 6 on each. `stealing-local` and `stealing-local-home` steal
  only within one CCD: an actor of `stealing-local` that moves stays in its CCD.
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

**Why a real socket.** A virtual thread that waits on a socket is woken up by the JDK's poller,
and the scheduler picks its carrier then. A carrier-affine scheduler puts it back on its home
carrier. `Thread.sleep` would not take this path. The backend runs in its own process, so its
CPU is not counted and it does not slow down the carriers.

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

**Why the JVM runs under `taskset`.** It gives the carrier-affine schedulers their CPU list. It
also keeps the JVM's other threads, like the GC and the JIT, on the carrier CPUs, away from the
clients and the backend.

**Why `jdk-pinned`.** It tells whether pinning alone makes the difference.

**Why `stealing-local` and `stealing-local-home`.** Stealing uses idle CPUs, but it moves actors
away from their data. `stealing-local` only moves them within the same L3 cache.
`stealing-local-home` never moves actors: it only moves the GET threads, which have no data. Both
steal within one L3 only, so they differ in one thing: whether an actor can move.

**Why stealing within one L3.** To steal, the scheduler wakes an idle carrier, and lets only one
such carrier search for work at a time in each group of carriers. With stealing from any carrier,
the group is the whole machine. When that one carrier is slow to start, no other can be woken,
and work waits in the queue of a busy carrier. With stealing within one L3, there is one group
per CCD.

**Why count the StackChunks.** A parked virtual thread keeps its stack in a `StackChunk`, on the
heap. If chunks were kept alive and piled up, the GC would have more to do under some
schedulers. Two counts show whether they pile up.
