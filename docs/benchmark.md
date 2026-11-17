# The benchmarks

What the [user benchmark](benchmark-user.md) and the [servlet benchmark](benchmark-servlet.md)
share: the load, the runs, the schedulers, the machine, and how to run them. Each page describes
its own program. The numbers: [the results](results/summary.md).

Both have the same shape. The server runs Looma actors in one JVM. The load is sent from that same
JVM. Each request calls a backend, another JVM, over a real socket.

## The load

[`LoadGenerator`](../examples/src/main/java/io/github/pderop/looma/examples/utils/LoadGenerator.java)
runs in the server JVM. It calls the server directly: nothing goes over the network between the
client and the server. Both benchmarks use it, each with its own requests.

- One platform thread per client CPU, pinned to it.
- A fixed schedule, open loop: together, `--rate` requests per second (150,000 by default), or
  the rate of the step in a ramp. A late request does not delay the next ones.
- Too many requests without an answer: the new ones are dropped, and counted.
- A latency is from the planned send time to the answer.

The client's CPU is not counted. The client threads run in the server JVM, so the server's CPU is
the JVM's CPU minus theirs. One piece of client work stays on the server: the actor completes the
client's future on its carrier. The client only takes the time there, and hands the request back
to its own thread.

## The runs

**One run** = one JVM, one scheduler. 10 seconds of warm-up, then 20 seconds measured. Each server
JVM has `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`, G1, and a GC log. The servlet backend has 1g. The user
benchmark's epsilon run, the same run with no GC, has 12g and Epsilon.

The main number is **cpu µs/req**: the JVM's CPU time, less the client threads', divided by the
requests answered.

The console says **"not a measurement"** when the clients could not send at the rate, when some
requests got no good answer in time, or when the clients sent too late (their send lag p99 is above
half the time between two sends of a thread). A `jdk-pinned` run whose carriers the script could
not pin is not a measurement either.

**The rounds:** each scheduler gets `--rounds` rounds (2). A round: a measure at `--rate` (r1, r2),
then a ramp (r1-ramp, r2-ramp). Round 1 also has the perf runs, between the two, one of each kind
per scheduler: each benchmark's page lists them. In a round, each of these goes through every
scheduler before the next one starts.

**The ramp:** the last run of a round, in a JVM of its own. After the warm-up:

```
the rate starts at --rate. Each step:
  10 seconds measured
  2 s for the late answers of the step, then its report
  "not a measurement" in the report: the ramp stops
  the rate goes up by --step (20,000), a little every 100 ms over 3 seconds, thrown away
```

Within a run, the server never restarts, and the rate never jumps. The highest rate kept up with by
each run is the `max req/s` of the summary, as r1/r2.

## The schedulers

Same jar, same JVM flags. Only the scheduler changes, and with it the way the JDK waits for
sockets. No flag sets the JDK's poller mode, so on Linux it follows the scheduler:

```
MPSC scheduler   mode 4: each carrier has its own epoll
jdk              mode 2: pollers that run as virtual threads
```

Here, a scheduler is one of six names. Five come from the code: the four configs of the MPSC
scheduler (`mpsc`, `mpsc-affine`, `mpsc-pinned`, `mpsc-affine-pinned`) and `jdk`. [The table of
`schedulers.md`](schedulers.md) explains each one. The benchmarks add a sixth, `jdk-pinned`: `jdk`,
with each carrier pinned to one CPU by the script (Linux only).

`--schedulers` picks them, e.g. `scripts/bench-user.sh --schedulers mpsc,jdk`. The default depends
on the build of the JDK ([the two builds](../mpsc-scheduler/README.md#the-jdk)):

- `pr227` has `Thread.Affinity`: all six.
- `noaff` has none: `mpsc` and `jdk`. It also runs `jdk-pinned` if asked. The scripts refuse
  the others before any run. There, with `mpsc`, the mailbox thread of an idle actor waits for the
  next message ([the keep-alive](schedulers.md#the-keep-alive)).

## The machine

### AMD Ryzen 9 9950X

- **CPU:** AMD Ryzen 9 9950X (Zen 5), one socket.
- **OS:** Linux 7.0 (Ubuntu).
- **JDK:** the two builds above, from source. The `java` line of each summary names the commit.
- **Setup:** turbo off (every core at 4.3 GHz), by `scripts/lib/linux-setup.sh`
  ([Running it](#running-it)). ITMT off, by a boot option (`amd_prefcore=disable`). The header of
  each summary shows both.

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
carriers     CPUs 2-7,10-13              10 carriers: 6 on CCD0, 4 on CCD1, one per core (its SMT twin idle)
clients      CPUs 0,1,16,17              cores 0 and 1 (CCD0)
backend      CPUs 8,9,14,15,24,25,30,31  cores 8, 9, 14 and 15 (CCD1)
```

## Running it

Needs Linux, one of the two builds of the Loom JDK
([how to build them](../mpsc-scheduler/README.md#the-jdk)), and `perf` (the script refuses to run on
Linux without it, unless `--no-perf`).

```sh
sdk env                                 # 28-loom-pr227 (the build pr227), from .sdkmanrc;
                                        # or export JAVA_HOME
                                        # noaff: sdk use java 28-loom-noaff
mvn clean install                       # once
scripts/bench-user.sh                   # the user benchmark; CPU lists of the AMD machine by default
scripts/bench-servlet.sh                # the servlet benchmark
scripts/publish.sh bench/user-<date>-<time> bench/servlet-<date>-<time>
                                        # publish the runs, of one JDK or more: -> docs/results/<jdk>/
```

Each script writes `bench/user-<date>-<time>/` or `bench/servlet-<date>-<time>/` (`--out` changes
it): its `summary.md` and the files behind each number. What `publish.sh` does with them:
[`results/campaigns.md`](results/campaigns.md).

Each script first runs [`scripts/lib/linux-setup.sh`](../scripts/lib/linux-setup.sh) with sudo:
turbo off, `performance` governor, perf allowed. `scripts/lib/linux-setup.sh --check` shows the
state, `--restore` (or a reboot) puts it back. Then it runs its benchmark: the
[user benchmark](benchmark-user.md#the-runs) or the [servlet benchmark](benchmark-servlet.md#the-runs).

More options, shared by the two scripts:

```sh
scripts/bench-user.sh --schedulers mpsc,jdk --rounds 1    # two schedulers, one round
scripts/bench-user.sh --step 0                            # no ramp: a round is only its measure
scripts/bench-user.sh --ramps-only                        # only the ramps: no measure, no perf run
scripts/bench-user.sh --no-perf                           # no perf or epsilon run
scripts/bench-user.sh --max-rate 400000                   # the ramp stops after 400,000 req/s
scripts/bench-user.sh --summary-only --out bench/user-<date>-<time>
                                                          # runs nothing: writes the tables of a campaign again
```

`--help` lists every option of a script.

Other scripts:

- [`scripts/jmh-user.sh`](../scripts/jmh-user.sh) and [`scripts/jmh-servlet.sh`](../scripts/jmh-servlet.sh)
  run one benchmark under JMH, at a fixed rate or in a ramp. First,
  [`scripts/jmh-user-start-backend.sh`](../scripts/jmh-user-start-backend.sh) or
  [`scripts/jmh-servlet-start-backend.sh`](../scripts/jmh-servlet-start-backend.sh) starts its backend.
- [`scripts/bench-backend.sh`](../scripts/bench-backend.sh) loads a backend alone with h2load, to see
  how many requests per second it answers.

### Where things run

Three options of both scripts, three CPU lists (commas and ranges: `2-7,10-13`), no CPU in two
lists. Defaults: the AMD machine ([The layout](#the-layout), above). `lscpu -e` shows each CPU's
core and caches.

| Option | Runs there |
| --- | --- |
| `--cpus-carriers` | one carrier per CPU; one CPU per core (the script refuses two CPUs of a core) |
| `--cpus-client` | the load, one `LoadGenerator` thread per CPU |
| `--cpus-backend` | the backend, its threads on these CPUs (one reactor per CPU by default, `--reactors`) |

Who pins what:

```
server JVM                -> taskset on the carrier CPUs (by the script)
backend JVM               -> taskset on the backend CPUs (by the script)
client threads            -> pin themselves, to the client CPUs
mpsc, mpsc-affine         -> carriers not pinned: they move between the carrier CPUs
mpsc-pinned,              -> carriers pin themselves, one CPU each: carrier i on the i-th CPU of the
mpsc-affine-pinned           server's mask
jdk                       -> carriers not pinned: they move between the carrier CPUs
jdk-pinned                -> each carrier pinned to one carrier CPU, by the script, during the warm-up
```

Elsewhere than Linux nothing is pinned: a smoke test of the script, not a measurement.

## Why it is built this way

- **A real socket:** a virtual thread woken when its socket is ready is where the scheduler picks
  the carrier. `Thread.sleep` would not take this path. The backend is a separate process: its CPU
  is not counted.
- **Rounds:** each run is a new JVM, with its own JIT and GC choices. In a round, the schedulers
  follow each other, so a drift of the machine hits every scheduler alike.
- **A fixed rate:** the same work for every scheduler. Latency from the planned send time, so a late
  client cannot hide a delay.
- **Pinned clients:** the load stays off the carriers, and counts its own answers.
- **One carrier per core:** two carriers on one core would share its L2.
- **`taskset` on the JVM:** gives the carriers their CPU list, and keeps the GC and the JIT on the
  carrier CPUs, away from the clients and the backend.
- **`jdk-pinned`:** tells whether pinning alone makes the difference.
- **A fair pool of connections:** a connection given back goes straight to the call that has waited
  the longest. So a call that arrives later cannot take it first.
- **The connection given back last:** the connections in use stay in the caches.
- **One backend thread per backend CPU:** the backend must not be the limit. A connection stays on
  the thread it got at accept. The connections in use are the ones given back last, so with fewer
  threads, some of them would get most of the calls.
