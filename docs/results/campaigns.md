# The results

One folder per campaign, named `<date>-<cpu>`. Each one has its `summary.md`, and the files every
number of it comes from. [`../benchmark.md`](../benchmark.md) explains the benchmark itself.

## The campaigns

### AMD Ryzen 9 9950X

- [`2026-10-31-ryzen9-9950x/summary.md`](2026-10-31-ryzen9-9950x/summary.md): the latest.
  `mpsc`, `jdk` and `jdk-pinned`, on the JDK of openjdk/loom pull request 227, without
  `--enable-preview`. It replaces two earlier campaigns of the same day. The first ran the same
  setups under their old names, `stealing-local-home` and `mpsc-home`. The second ran `affine`,
  the copied Netty scheduler, next to `mpsc`, `jdk` and `jdk-pinned`, all with
  `--enable-preview`; it is in the git history, at commit `268ff6b`. Without the flag, `mpsc`
  spent the same CPU per request as with it, within 4%, and so did `jdk` and `jdk-pinned`.
- [`2026-10-24-ryzen9-9950x/summary.md`](2026-10-24-ryzen9-9950x/summary.md): `stealing-local`,
  `stealing-local-home`, `jdk` and `jdk-pinned`, on the Loom JDK of builds.shipilev.net, after
  [#9](https://github.com/pderop/looma/pull/9). `stealing-local-home` spends less CPU per request
  than `stealing-local` in both cases.
- [`2026-10-22-ryzen9-9950x/summary.md`](2026-10-22-ryzen9-9950x/summary.md): the same four,
  before #9. The scheduler then started its scan for an idle carrier at a random one, instead of
  the first one. #9 removed that change.

The machine, and where each part of the benchmark runs on it:
[`../benchmark.md`](../benchmark.md#amd-ryzen-9-9950x).

### Older campaigns

Before 2026-10-31, `affine` and `mpsc` had options, and the campaigns compared their setups under
names of their own:

| Old name | What it was | Now |
| --- | --- | --- |
| `stealing-local-home` | the copied scheduler, stealing within an L3, actors never stolen | `affine` |
| `stealing-local` | the same, but an idle carrier could also steal an actor | removed |
| `stealing-global-home` | `stealing-local-home`, stealing from any carrier | removed |
| `mpsc-home` | the MPSC scheduler, stealing from any carrier, actors never stolen | `mpsc` |
| `mpsc-home-poller3` | `mpsc-home` with `jdk.pollerMode=3` | removed |

`stealing-global-home` and `mpsc-home-poller3` ran only in short campaigns, not published.

In the campaigns of 2026-10-22 and 2026-10-24, and in the one of commit `268ff6b`, `affine` (or
its old name, `stealing-local-home`) is that copied scheduler. Since then, the copy is no longer
built, `Carriers.affine(n)` runs the MPSC scheduler, and the runs of the MPSC scheduler are still
named `mpsc`.

## What a campaign contains

`scripts/bench.sh` writes a campaign to `bench/<date>-<time>/`, and
[`scripts/publish.sh`](../../scripts/publish.sh) copies it here. A campaign folder holds:

- **`summary.md`**: the results.
- **`<case>-c2c-<scheduler>.txt`**: the full `perf c2c` report of each case and scheduler.
- **`runs/<case>-<run>-<scheduler>-<depth>.out`** and **`.gc.log`**: the console and the GC log
  of each run, for example `runs/default-r1-mpsc-256.out`,
  `runs/big-c2c-jdk-256.out` or `runs/default-epsilon-mpsc-256.out`.
- **`runs/<case>-stat-<scheduler>.csv`**: the `perf stat` counts of each thread.
- **`runs/<case>-c2c-<scheduler>.data`**: the `perf c2c` data, which only `perf report` reads.
  It stays in `bench/`: `publish.sh` does not copy it.

Every number of `summary.md` comes from one of these files, and each of its sections says which.

`summary.md` starts with a header: the date, the machine, the CPU, the turbo, the JDK, the CPUs
of each part, the heap, and the command. Then, with no reading of the numbers:

- **The runs:** how the runs were made.
- **At a glance:** per scheduler and case, the cpu µs/req at depth 256.
- **In short:** per case, depth and scheduler, the CPU and the latency of r1 to r3.
- **Controls:** per case, depth and scheduler, the rate reached, the GC pauses, the live
  StackChunks.
- **perf stat:** per case and scheduler, from its stat run, with the formulas.
- **perf c2c:** per case and scheduler, from its c2c run, with the formulas.
- **Epsilon:** per case and scheduler, the CPU with G1 and with Epsilon, and the heap used.

Each section gives its table first, then the formulas and the files its numbers come from.
The campaigns of 2026-10-22 and 2026-10-24 give the sections in another order, the sources first,
and the raw counts of perf stat and perf c2c before the computed columns.

| Column | What it is |
| --- | --- |
| **cpu µs/req** | the CPU cost of one request, the mean of r1 to r3. All schedulers serve the same rate, so this is the main number to compare. "not a measurement" when a run could not keep up with its rate, a GET failed, or a pinning failed |
| **cpu range** | the lowest and the highest cpu µs/req of r1 to r3: how much the runs differ |
| **vs jdk** | cpu µs/req against `jdk`'s, in the same case and at the same depth. Empty when `jdk` is not a measurement |
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
| **note** | in perf stat and perf c2c: "this run: not a measurement" when the stat or c2c run itself was not, "r1 to r3: not a measurement" when the runs of that case and scheduler were not; their counts are then not comparable with the others. In Controls: "not a measurement", followed by the runs that were not, as in "(r1 to r3, c2c run, stat run)" |

## Where the numbers come from

Every number of `summary.md` is read from a file of `runs/`, or from a c2c report, or computed
from other numbers of the same line. This section shows how, on one line of each table.

### perf stat

The line, from [`2026-10-22-ryzen9-9950x/summary.md`](2026-10-22-ryzen9-9950x/summary.md) (in
newer campaigns, the computed columns come before the raw counts):

| case | scheduler | instructions | cycles | requests | instructions/req | IPC | cycles per instruction | cycles/req | note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| default | jdk | 287,653,929,416 | 307,912,670,779 | 2,100,000 | 136,978 | 0.93 | 1.07 | 146,625 | |

It comes from `2026-10-22-ryzen9-9950x/runs/default-stat-jdk.csv`, the output of `perf stat` during the stat
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
   14 s. The line `throughput` of the console of the run,
   `2026-10-22-ryzen9-9950x/runs/default-stat-jdk-256.out`,
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
  as a line like this one, from `2026-10-24-ryzen9-9950x/runs/default-epsilon-stealing-local-home-256.gc.log`
  (`stealing-local-home` is the old name of `affine`, see [Older campaigns](#older-campaigns)):
  `space 12582912K,  75% used [0x0000000500000000, 0x000000074619cdd8, 0x0000000800000000)`.
  The three addresses are the start of the heap, its top (where the next object would go) and
  its end. heap used = top - start: here 0x74619cdd8 - 0x500000000 = 9,766,030,808 bytes, so
  9,313 MiB.

```
difference = cpu µs/req Epsilon - cpu µs/req G1
```
