# The benchmark

**The question:** an actor owns private data. If the scheduler keeps it on one core, does the data
stay in that core's cache, and does that save CPU?

**How:** the same program, under several schedulers, at 150,000 requests per second. Each request
makes an actor read a few hundred of its objects. Compared: CPU per request, latency, and how often
actors moved.

**The answer**, on an AMD Ryzen 9 9950X, in CPU µs per request (lower is better):

| Case | `mpsc` (each actor stays on its core) | `jdk` (the JDK's default scheduler) | `jdk-pinned` |
| --- | --- | --- | --- |
| `default`: data fits in a core's L2 | **15.65** | 37.02 | 43.81 |
| `big`: data does not fit in the caches | **63.91** | cannot keep up: not a measurement | 75.97 |

From [`results/2026-11-05-ryzen9-9950x/summary.md`](results/2026-11-05-ryzen9-9950x/summary.md),
"At a glance": depth 256, the mean of 3 runs. In `default`, `mpsc` spends 57.7% less than `jdk`; in `big`, `jdk-pinned` spends 18.9% more than `mpsc`.

- Code: [`examples/…/examples/user`](../examples/src/main/java/io/github/pderop/looma/examples/user)
- Scripts: [`scripts/bench.sh`](../scripts/bench.sh) runs it, [`scripts/publish.sh`](../scripts/publish.sh)
  copies a result to the docs

## What it measures

### The program

A made-up service that recommends users. Each actor owns a group of users. A request makes it
call a backend, then walk a chain of friends through its users, writing in each one.

```
FrontendServer ──▶ Main.handleRequest ──▶ UserGroup ──GET──▶ BackendServer
 (the load)                               (an actor)         (another process)
```

**At start**, in [`Main`](../examples/src/main/java/io/github/pderop/looma/examples/user/Main.java):

```
1. the scheduler of -Dbench.scheduler, one carrier per CPU of --cpus-carriers
2. one UserGroup actor per carrier, placed on it
3. each group creates its users (--users-per-group) on its own carrier
     group i owns users i, i + groups, i + 2 × groups, ...
     a user: a friend, a visit count, a score, the time of its last visit
     created in a random order; the friendships form one cycle, in another random order
4. each group opens a fixed pool of connections to the backend
```

**The load**, `FrontendServer`, in the benchmark JVM, calls `Main.handleRequest` directly (no HTTP).

- One platform thread per client CPU, pinned to it.
- A fixed schedule: together, `--rate` requests per second (150,000 by default). A late request
  does not delay the next ones.
- Too many requests without an answer: the new ones are dropped, and counted.
- Each request asks for a random user.

**One request:**

```
1. handleRequest -> ask(Recommend) to the group that owns the user
2. actor: Recommend   -> starts a virtual thread for the GET, goes on with its next message
3. GET thread         -> sends an HTTP GET to the backend, parks on the response
                         the response arrives: the JDK wakes it (with mpsc: the carrier's own
                         epoll) -> back on a carrier -> tell(UpstreamAnswered)
4. actor: UpstreamAnswered -> walks --depth users from the one asked for, writes in each -> answers
5. client             -> records the latency, from the planned send time
```

Between 2 and 4, the mailbox is empty: the actor's virtual thread ends. `UpstreamAnswered` starts
a new one, and the scheduler picks its carrier:

```
same carrier   -> the users are still in that core's cache -> fast walk
other carrier  -> the users are read again                  -> slow walk
```

**The backend**, [`BackendServer`](../examples/src/main/java/io/github/pderop/looma/examples/user/BackendServer.java),
in its own JVM: one thread accepts, the others answer `200 OK` with a small fixed body, at once.

**One run** = one JVM, one scheduler: 5 s of warm-up, counters reset, 20 s measured. It prints:

- **cpu µs/req**: the JVM's CPU time, less the client threads', divided by the requests served.
  The main number.
- **migrations**: how often an actor ran a message on another carrier than the one before, on one
  message in 64.
- the requests per second, the latencies, and whether it kept up with the rate.

**The cases**, by `--users-per-group`, and `--depth` (users read per request, 256 by default). A
user is about 82 bytes.

| Case | Users per group | One group |
| --- | --- | --- |
| `default` | 8000 | about 640 KiB |
| `big` | 200000 | about 16 MiB |

### The schedulers

Same jar, same JVM flags. Only `-Dbench.scheduler` changes: `mpsc` or `jdk`. `jdk-pinned` is a
`jdk` run whose carriers the script pins.

| Scheduler | What it is |
| --- | --- |
| `mpsc` | `Carriers.affine(n)`: the JDK's MPSC scheduler (openjdk/loom pull request 227). Each carrier pinned to its CPU. An actor never leaves its carrier. An idle carrier steals only the GET threads. |
| `jdk` | `Carriers.builtin(n)`: the JDK's default scheduler, same carrier count, not pinned. |
| `jdk-pinned` | `jdk`, each carrier pinned to one CPU by the script (Linux only). |

`--schedulers` picks them, e.g. `scripts/bench.sh --schedulers mpsc,jdk`. Default: all three.
[`schedulers.md`](schedulers.md) explains each one.

## The machine

### AMD Ryzen 9 9950X

- **CPU:** AMD Ryzen 9 9950X (Zen 5), one socket.
- **OS:** Linux 7.0 (Ubuntu).
- **JDK:** the Loom JDK of openjdk/loom pull request 227, built from source
  (`28-internal-pr227-731930d`).
- **Setup:** turbo off (every core at 4.3 GHz), by `prepare-linux-bench.sh` ([Running it](#running-it)).

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

- 16 cores, 32 CPUs: CPU `n` and `n + 16` are the same core (SMT), and share its L1 and L2.
- Two CCDs of 8 cores: 0-7 and 8-15. One memory node.

| Cache | Size | Shared by |
| --- | --- | --- |
| L1 data | 48 KiB per core | one core |
| L2 | 1 MiB per core | one core |
| L3 | 32 MiB per CCD | the 8 cores of a CCD |

Where an actor finds its data, by where it resumes:

```
same core            -> its L2                     fastest
other core, same CCD -> the shared L3
other CCD            -> through the Infinity Fabric slowest
```

#### The layout

```
carriers     CPUs 2-7,10-15     12 carriers: 6 per CCD, one per core (its SMT twin idle)
clients      CPUs 0,1,16,17     cores 0 and 1 (CCD0)
backend      CPUs 8,9,24,25     cores 8 and 9 (CCD1)
```

Where the data lives, 12 groups, 6 per CCD:

```
default  one group about 640 KiB: fits in a 1 MiB L2
         12 groups about 7.5 MiB: several L2s; the 6 of a CCD (3.8 MiB) fit in its 32 MiB L3
         -> a moved actor reads its data from the L3 (same CCD),
            or through the Infinity Fabric (other CCD)
big      one group about 16 MiB: does not fit in an L2
         the 6 of a CCD (about 94 MiB) do not fit in its L3
         -> every actor reads most of its data from memory, moved or not
```

## Running it

Needs Linux, the Loom JDK of openjdk/loom pull request 227
([how to build it](../mpsc-scheduler/README.md#the-jdk)), and `perf` (the script refuses to run on
Linux without it).

```sh
sdk env                                 # the Loom JDK, from .sdkmanrc; or export JAVA_HOME
mvn clean install                       # once
scripts/bench.sh                        # both cases; CPU lists of the AMD machine by default
scripts/publish.sh bench/<date>-<time>  # keep it: -> docs/results/<date>-<cpu>/, the only one kept
```

`bench.sh` first runs [`scripts/prepare-linux-bench.sh`](../scripts/prepare-linux-bench.sh) with sudo:
turbo off, `performance` governor, perf allowed. `--check` shows the state, `--restore` (or a
reboot) puts it back.

Other runs:

```sh
scripts/bench.sh --cpus-carriers <list> --cpus-client <list> --cpus-backend <list>   # elsewhere
scripts/bench.sh --users-per-group 8000              # the default case only
scripts/bench.sh --schedulers mpsc,jdk --reps 1      # two schedulers, one repetition
```

`scripts/bench.sh --help` lists every option.

### Where things run

Three options of `scripts/bench.sh`, three CPU lists (commas and ranges: `2-7,10-15`), no CPU in two
lists. Defaults: the AMD machine ([The layout](#the-layout), above). `lscpu -e` shows each CPU's
core and caches.

| Option | Runs there |
| --- | --- |
| `--cpus-carriers` | one carrier and one group per CPU; one CPU per core (the script refuses two CPUs of a core) |
| `--cpus-client` | the load, one `FrontendServer` thread per CPU |
| `--cpus-backend` | the backend, one thread per CPU |

The client's CPU is not counted. One piece of client work runs on the server: the actor completes
the client's future on its carrier. The client only takes the time there, and hands the request
back to its own thread.

Who pins what:

```
benchmark JVM  -> taskset on the carrier CPUs (by the script)
backend JVM    -> taskset on the backend CPUs (by the script)
mpsc carriers  -> pin themselves, one CPU each
client threads -> pin themselves, to the client CPUs
jdk-pinned     -> the script pins each JDK carrier, during the warm-up
jdk            -> carriers not pinned: they move between the carrier CPUs
```

Elsewhere than Linux nothing is pinned: a smoke test of the script, not a measurement.

What the script does:

```
1. prepare the machine; start the backend once, under taskset on the backend CPUs
2. every run: taskset on the carrier CPUs, -Xms2g -Xmx2g -XX:+AlwaysPreTouch, a GC log
3. for each case (default, then big):
   a. for each repetition (--reps, 3): every scheduler at --depth, then at depth 0
   b. c2c run, per scheduler:     perf c2c over the measure, minus 3 s at each end;
                                  live StackChunks counted during the warm-up and after the
                                  measure (full GCs, left out of the GC column)
   c. stat run, per scheduler:    perf stat, cycles and instructions of the server's threads
   d. walk run, per scheduler:    perf record of the cycles, with UserGroup.walk kept out of
                                  line: the cycles of the walk alone
   e. epsilon run, per scheduler: -XX:+UseEpsilonGC -Xms12g -Xmx12g: no GC
                                  (what a run allocates: the Epsilon table of summary.md)
4. summary.md, both cases
```

3 schedulers: 30 runs per case, 60 in all, about 31 minutes.

Output: `bench/<date>-<time>/` (`--out` changes it): `summary.md` and the files behind each number.
[`results/campaigns.md`](results/campaigns.md) explains them.

`publish.sh` copies `summary.md`, the `<case>-c2c-<scheduler>.txt` and `<case>-walk-<scheduler>.txt`
reports, and the consoles, GC logs and `perf stat` files of `runs/` to `docs/results/<date>-<cpu>/`.
Only the latest campaign is kept: it removes the folders of the others first. The perf data files
are not copied. `publish.sh -n` shows what it would copy.

## Results

Latest: [`results/2026-11-05-ryzen9-9950x/summary.md`](results/2026-11-05-ryzen9-9950x/summary.md).
What it contains, and where each number comes from: [`results/campaigns.md`](results/campaigns.md).

## Why it is built this way

- **A graph walk:** each user gives the next one, so the CPU cannot read ahead. The walk goes as fast
  as the cache that holds the users.
- **The walk writes:** the data is modified in the cache of the core that runs it. A moved actor
  reads it again: from the L3, the old core, or memory.
- **A real socket:** a virtual thread woken when its socket is ready is where the scheduler picks
  the carrier. `Thread.sleep` would not take this path. The backend is a separate process: its CPU
  is not counted.
- **Repetitions:** each run is a new JVM, with its own JIT and GC choices. They follow each other,
  so a drift of the machine hits every scheduler alike.
- **Depth 0:** everything but the walk: the messages, the GET, the scheduling.
- **The walk run:** `UserGroup.walk` kept out of line has a name of its own in `perf`. Its cycles
  are the walk alone.
- **A fixed rate:** the same work for every scheduler. Latency from the planned send time, so a late
  client cannot hide a delay.
- **Pinned clients:** the load stays off the carriers, and counts its own answers.
- **One carrier per core:** two carriers on one core would share its L2.
- **`taskset` on the JVM:** gives `mpsc` its CPU list, and keeps the GC and the JIT on the carrier
  CPUs, away from the clients and the backend.
- **`jdk-pinned`:** tells whether pinning alone makes the difference.
- **StackChunks counted:** if parked stacks piled up on the heap, the GC would do more under some
  schedulers. Two counts show it.
