# The benchmarks

Looma has two benchmarks. They run the same schedulers, on the same machine, from two scripts that
share their code.

| Benchmark | What it is | The question |
| --- | --- | --- |
| [The user benchmark](benchmark-user.md) | Each actor walks through its own objects | Does an actor's data stay in its core's cache, and does that save CPU? |
| [The servlet benchmark](benchmark-servlet.md) | A mini servlet container, written with actors | How far does the server go, and what is its latency below that? |

This page gives what they share. Each benchmark has its own page for the rest.

- Scripts: [`scripts/bench-user.sh`](../scripts/bench-user.sh) and
  [`scripts/bench-servlet.sh`](../scripts/bench-servlet.sh) run them,
  [`scripts/publish.sh`](../scripts/publish.sh) copies the results to the docs.
  [`scripts/jmh-user.sh`](../scripts/jmh-user.sh) and [`scripts/jmh-servlet.sh`](../scripts/jmh-servlet.sh)
  run one under JMH, at a fixed rate or in a ramp, after
  [`scripts/jmh-user-start-backend.sh`](../scripts/jmh-user-start-backend.sh) or
  [`scripts/jmh-servlet-start-backend.sh`](../scripts/jmh-servlet-start-backend.sh) has started its backend.
  [`scripts/bench-backend.sh`](../scripts/bench-backend.sh) loads a backend alone with h2load, to see
  how many requests per second it answers.

## The load

[`LoadGenerator`](../examples/src/main/java/io/github/pderop/looma/examples/utils/LoadGenerator.java)
runs in the server JVM. It calls the server directly: nothing goes over the network between the
client and the server. Both benchmarks use it, each with its own requests.

- One platform thread per client CPU, pinned to it.
- A fixed schedule, open loop: together, `--rate` requests per second (150,000 by default), or the rate of the
  step in a ramp. A late request does not delay the next ones.
- Too many requests without an answer: the new ones are dropped, and counted.
- Requests sent too late: the run is not a measurement. Too late means the send lag p99 is above
  half the time between two sends of a thread.
- A latency is from the planned send time to the answer.

The client's CPU is not counted. The client threads run in the server JVM, so the server's CPU is
the JVM's CPU minus theirs. One piece of client work stays on the server: the actor completes the
client's future on its carrier. The client only takes the time there, and hands the request back
to its own thread.

## The runs

**One run** = one JVM, one scheduler. It warms up, then measures for 20 seconds. Each server JVM has
`-Xms2g -Xmx2g -XX:+AlwaysPreTouch`, G1, and a GC log (the epsilon runs: 12g and Epsilon; the servlet
backend: 1g).

The main number is **cpu µs/req**: the JVM's CPU time, less the client threads', divided by the
requests answered.

The console says "not a measurement" when the clients could not send at the rate, when some requests
got no good answer in time, or when the clients sent too late. A run where the script could not pin
the workers is not a measurement either.

**The ramp:** last, two more runs per scheduler (`--ramp-runs`), ramp1 and ramp2, each in a JVM of
its own. After the warm-up of each:

```
the rate starts at --rate. Each step:
  10 seconds measured
  2 s for the late answers of the step, then its report
  "not a measurement" in the report: the ramp stops
  the rate goes up by --step (20,000), a little every 100 ms over 3 seconds, thrown away
```

Within a run, the server never restarts, and the rate never jumps. The highest rate kept up with by
each run is the `max req/s` of the summary, as ramp1 / ramp2. `--max-rate` stops the ramp earlier, even if the server keeps up.

## The schedulers

Same jar, same JVM flags. Only the scheduler changes. `jdk-pinned` is a `jdk` run whose carriers the
script pins.

| Scheduler | What it is |
| --- | --- |
| `mpsc` | `Carriers.mpsc(n)`: the JDK's MPSC scheduler (openjdk/loom pull request 227). Each carrier pinned to its CPU. An actor never leaves its carrier. An idle carrier steals only the threads an actor started. |
| `jdk` | `Carriers.builtin(n)`: the JDK's default scheduler, same carrier count, not pinned. |
| `jdk-pinned` | `jdk`, each carrier pinned to one CPU by the script (Linux only). |
| `mpsc-unpinned` | `Carriers.mpsc(n).pinned(false)`: `mpsc` with no carrier pinned. |
| `mpsc-no-affinity` | `Carriers.mpsc(n).affinity(false)`: `mpsc` where an actor can run on any carrier: its thread starts on the carrier of its sender, and an idle carrier may take it. |
| `mpsc-unpinned-no-affinity` | `Carriers.mpsc(n).pinned(false).affinity(false)`: the same, with no carrier pinned. |

`--schedulers` picks them, e.g. `scripts/bench-user.sh --schedulers mpsc,jdk`. Default: all six.
[`schedulers.md`](schedulers.md) explains each one.

## The machine

### AMD Ryzen 9 9950X

- **CPU:** AMD Ryzen 9 9950X (Zen 5), one socket.
- **OS:** Linux 7.0 (Ubuntu).
- **JDK:** the Loom JDK of openjdk/loom pull request 227, built from source
  (`28-internal-pr227-5766f81`).
- **Setup:** turbo off (every core at 4.3 GHz), by `scripts/lib/linux-setup.sh` ([Running it](#running-it)).
  ITMT off, by a boot option (`amd_prefcore=disable`). The header of each summary shows both.

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

Needs Linux, the Loom JDK of openjdk/loom pull request 227
([how to build it](../mpsc-scheduler/README.md#the-jdk)), and `perf` (the script refuses to run on
Linux without it).

```sh
sdk env                                 # the Loom JDK, from .sdkmanrc; or export JAVA_HOME
mvn clean install                       # once
scripts/bench-user.sh                   # the user benchmark; CPU lists of the AMD machine by default
scripts/bench-servlet.sh                # the servlet benchmark
scripts/publish.sh bench/user-<date>-<time> bench/servlet-<date>-<time>
                                        # keep them: -> docs/results/, the only campaign kept
```

Each script first runs [`scripts/lib/linux-setup.sh`](../scripts/lib/linux-setup.sh) with sudo:
turbo off, `performance` governor, perf allowed. `scripts/lib/linux-setup.sh --check` shows the
state, `--restore` (or a reboot) puts it back. Then it runs its benchmark: the
[user benchmark](benchmark-user.md#the-runs) or the [servlet benchmark](benchmark-servlet.md#the-runs).

Other runs. The two scripts share these options:

```sh
scripts/bench-user.sh --cpus-carriers <list> --cpus-client <list> --cpus-backend <list>   # elsewhere
scripts/bench-user.sh --schedulers mpsc,jdk --reps 1      # two schedulers, one repetition
scripts/bench-user.sh --step 0                            # no ramp
scripts/bench-user.sh --ramp-runs 1                       # one ramp run per scheduler
scripts/bench-user.sh --no-perf                           # no c2c, stat, walk or epsilon run
scripts/bench-user.sh --max-rate 400000                   # the ramp stops after 400,000 req/s
```

`--help` lists every option of a script.

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
mpsc carriers             -> pin themselves, one CPU each: carrier i on the i-th CPU of the server's mask
mpsc-unpinned             -> carriers not pinned: they move between the carrier CPUs; actors stay on their carrier
mpsc-no-affinity          -> carriers pin themselves; an actor can run on any carrier
mpsc-unpinned-no-affinity -> carriers not pinned; an actor can run on any carrier
client threads            -> pin themselves, to the client CPUs
jdk                       -> carriers not pinned: they move between the carrier CPUs
jdk-pinned                -> each carrier pinned to one carrier CPU, by the script, during the warm-up
```

Elsewhere than Linux nothing is pinned: a smoke test of the script, not a measurement.

### The output

Output: `bench/user-<date>-<time>/` and `bench/servlet-<date>-<time>/` (`--out` changes it), each
with its `summary.md` and the files behind each number.
[`results/campaigns.md`](results/campaigns.md) explains them.

`publish.sh` copies, of each: its `summary.md`, its c2c and walk reports, and the consoles, GC logs
and `perf stat` files of its `runs/`, to `docs/results/user/` and `docs/results/servlet/`. Then it
writes `docs/results/summary.md`: both benchmarks in one table. Only the latest campaign is kept:
it removes the files of the last one first, so give it both runs at once. The perf data files are
not copied. `publish.sh -n` shows what it would copy.

## Results

Latest: [`results/summary.md`](results/summary.md).
What it contains, and where each number comes from: [`results/campaigns.md`](results/campaigns.md).

## Why it is built this way

- **A real socket:** a virtual thread woken when its socket is ready is where the scheduler picks
  the carrier. `Thread.sleep` would not take this path. The backend is a separate process: its CPU
  is not counted.
- **Repetitions:** each run is a new JVM, with its own JIT and GC choices. They follow each other,
  so a drift of the machine hits every scheduler alike.
- **A fixed rate:** the same work for every scheduler. Latency from the planned send time, so a late
  client cannot hide a delay.
- **Pinned clients:** the load stays off the carriers, and counts its own answers.
- **One carrier per core:** two carriers on one core would share its L2.
- **`taskset` on the JVM:** gives `mpsc` its CPU list, and keeps the GC and the JIT on the carrier
  CPUs, away from the clients and the backend.
- **`jdk-pinned`:** tells whether pinning alone makes the difference.
- **A fair pool of connections:** a connection given back goes straight to the call that has waited
  the longest. So a call that arrives later cannot take it first.
- **The connection given back last:** the connections in use stay in the caches.
- **One backend thread per backend CPU:** the backend must not be the limit. A connection stays on
  the thread it got at accept. The connections in use are the ones given back last, so with fewer
  threads, some of them would get most of the calls.
