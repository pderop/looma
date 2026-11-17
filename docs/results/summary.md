# Results

| Build | `Thread.Affinity` | Results | What it is |
| --- | --- | --- | --- |
| `pr227` | yes | [at a glance](pr227/summary.md) · [user](pr227/user/summary.md) · [servlet](pr227/servlet/summary.md) | [openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227) |
| `noaff` | no | [at a glance](noaff/summary.md) · [user](noaff/user/summary.md) · [servlet](noaff/servlet/summary.md) | the branch `mpsc-without-affinity` of `franz1981/loom` |

The commit measured: the `java` line in the header of each summary.

## Setup

- Machine: an AMD Ryzen 9 9950X, 10 carriers, one per core
  ([the machine](../benchmark.md#amd-ryzen-9-9950x)).
- Each scheduler runs 2 rounds. A round: a measure at 150k req/s (r1, r2), then a ramp: from
  150k req/s, 20k more at each step, until a step is not kept up with.
- A latency is from the planned send time of a request to its answer. cpu µs/req: the server's
  CPU per request, the clients' threads left out.
- The scenarios: user `small`, the data of an actor fits in one core's L2; user `big`, it does
  not ([the user benchmark](../benchmark-user.md), depth 256). Servlet: a small web application.
  The fraud table of each container alone is about one core's L2; its sessions, and the bodies and
  pages of its requests, come on top ([the servlet benchmark](../benchmark-servlet.md)).

The schedulers:

| scheduler | builds | actor stays on its carrier | carriers pinned to one CPU |
| --- | --- | --- | --- |
| `mpsc-affine-pinned` | `pr227` | yes | yes |
| `mpsc-affine` | `pr227` | yes | no |
| `mpsc-pinned` | `pr227` | no | yes |
| `mpsc` | both | no | no |
| `jdk-pinned` | `pr227` | no | yes |
| `jdk` | both | no | no |

- builds: the builds it runs on.
- `mpsc*`: the JDK's MPSC scheduler. `jdk*`: the JDK's default scheduler, a `ForkJoinPool`.
- An actor stays on its carrier only with `Thread.Affinity`: `pr227` only.
- On `noaff`, under `mpsc`, the thread of an idle actor waits for the next message instead of
  ending ([the keep-alive](../schedulers.md#the-keep-alive)).

How to read:

- Two values, as 830k/850k: rounds 1 and 2. A range, as 92–94: the lowest and the highest of
  the two rounds, one value when they are equal. cpu µs/req and migrations: the mean of the two
  rounds.
- (r2): the r1 run did not keep up, so the value is r2 only.
- The bars and the lines: each ramp, r1 and r2. The dots: the mean of the runs at 150k req/s. The
  walk, other L3 and StackChunks charts: one run each, in round 1.
- The charts by ramp step show `mpsc-affine-pinned`, `mpsc` and `jdk`: dashed for
  `pr227`, solid for `noaff`. A point is the mean of r1 and r2; a line ends at the
  last step both ramps kept up with (the max rates: the bar charts). The other charts show every
  scheduler.

<!-- begin charts -->

<!-- end charts -->

## In short

Three comparisons, one table each. A cell gives the first scheduler of the title, then the second.

- highest rate with p99 < 1 ms: the last step of the ramp kept up with before the first step whose
  p99 is above 1 ms. none: already above at 150k req/s.
- migrations: the share of the checked messages that ran on another carrier than the message
  before ([Migrations](#migrations)).

### `pr227`: `mpsc-affine-pinned` vs `jdk`

The gain shows in every aspect, in all three scenarios. The CPU gain is smallest in user `big`.
With affinity, walking the actor's data costs fewer cycles, and fewer loads come from another L3.
With no work per actor (depth 0), affinity changes little: the gap is between the MPSC schedulers
and `jdk`.

<!-- begin vs pr227 mpsc-affine-pinned pr227 jdk -->

| aspect | user `small` | servlet | user `big` |
| --- | --- | --- | --- |
| peak rate | 1,050k/1,070k vs 650k/670k | 430k/430k vs 350k/350k | 190k/190k vs 150k/170k |
| highest rate with p99 < 1 ms | 1,030k/1,050k vs 470k/450k | 410k/430k vs none/none | 170k/170k vs none/none |
| p99 µs at 150k req/s | 30 vs 88–92 | 77–78 vs 1,997–2,148 | 534–541 vs 9,994–15,057 |
| cpu µs/req at 150k req/s | 15.16 vs 40.45 | 32.19 vs 55.20 | 54.36 vs 64.14 |
| migrations | 0.0% vs 62.8% | 0.0% vs 69.6% | 0.0% vs 4.9% |

<!-- end vs -->

### `noaff`: `mpsc` vs `jdk`

Without `Thread.Affinity`:

- user `small`: `mpsc` is ahead on every aspect.
- servlet: ahead on every aspect except the migrations. Its run at 150k req/s of round 1 did
  not keep up: a short stall dropped 199 requests
  ([`servlet-r1-mpsc.out`](noaff/servlet/runs/servlet-r1-mpsc.out)). Its values at 150k req/s
  are those of round 2.
- user `big`: a higher peak rate in round 2, the same in round 1; a lower p99; the same CPU.

<!-- begin vs noaff mpsc noaff jdk -->

| aspect | user `small` | servlet | user `big` |
| --- | --- | --- | --- |
| peak rate | 830k/850k vs 650k/690k | 370k/370k vs 330k/330k | 150k/190k vs 150k/150k |
| highest rate with p99 < 1 ms | 690k/690k vs 510k/510k | 310k/330k vs none/none | none/none vs none/none |
| p99 µs at 150k req/s | 85–87 vs 87–91 | 85 (r2) vs 1,736–2,181 | 2,018–3,426 vs 20,890–33,587 |
| cpu µs/req at 150k req/s | 29.86 vs 40.58 | 45.40 (r2) vs 55.02 | 64.97 vs 64.41 |
| migrations | 43.9% vs 63.5% | 74.0% (r2) vs 69.5% | 4.2% vs 3.8% |

<!-- end vs -->

### `mpsc`: `noaff` vs `pr227`

- peak rate: higher in both ramps of user `small` and of servlet. In user `big`, lower in round
  1, higher in round 2.
- CPU at 150k req/s: 10% more in servlet, 7% more in user `small`, the same in user `big`.
- fewer GCs (user `small`, servlet), and more live stacks (user `small`).

<!-- begin vs noaff mpsc pr227 mpsc -->

| aspect | user `small` | servlet | user `big` |
| --- | --- | --- | --- |
| peak rate | 830k/850k vs 610k/570k | 370k/370k vs 290k/310k | 150k/190k vs 170k/170k |
| highest rate with p99 < 1 ms | 690k/690k vs 530k/550k | 310k/330k vs 290k/290k | none/none vs none/none |
| p99 µs at 150k req/s | 85–87 vs 84 | 85 (r2) vs 96–122 | 2,018–3,426 vs 1,553–8,684 |
| cpu µs/req at 150k req/s | 29.86 vs 27.92 | 45.40 (r2) vs 41.11 | 64.97 vs 65.06 |
| migrations | 43.9% vs 56.7% | 74.0% (r2) vs 78.7% | 4.2% vs 6.1% |

<!-- end vs -->

## Peak rate and highest rate with p99 under 1 ms

![User benchmark, case small: max rate](charts/user-small-max.svg)

![Servlet benchmark: max rate](charts/servlet-max.svg)

![User benchmark, case big: max rate](charts/user-big-max.svg)

- One bar per ramp, r1 then r2. Pale: the peak rate, the last step kept up with. Solid: the highest
  rate with p99 < 1 ms. none: already above 1 ms at 150k req/s.

## p99 at 150k req/s

![p99 at 150k req/s](charts/p99-150k.svg)

At the other rates, the p99 at each step of each ramp. User `big` has no chart by step.

![User benchmark: p99 by rate](charts/user-p99.svg)

![Servlet benchmark: p99 by rate](charts/servlet-p99.svg)

## CPU per request at 150k req/s

![CPU per request at 150k req/s](charts/cpu-150k.svg)

At the other rates, the CPU per request at each step of each ramp.

![User benchmark: CPU per request by rate](charts/user-cpu.svg)

![Servlet benchmark: CPU per request by rate](charts/servlet-cpu.svg)

![Walk cycles and loads from another L3](charts/walk-l3.svg)

- walk cycles/req: the cycles spent in the walk alone, per request.
- other L3: the share of the sampled loads that came from behind another L3.

![CPU per request with no work per actor](charts/cpu-depth0.svg)

Depth 0: the actor does not walk its objects.

## Migrations

![Migrations at 150k req/s](charts/migrations-150k.svg)

How they are counted: each actor checks one message in 64. At the end of that message, it notes
its carrier: the end of the thread's `toString()`, after its `@`. At the start of its next message,
it looks again: a different carrier is a migration. The value: migrations / checked messages, all
actors, during the measure at 150k req/s, about 93,800 checked messages per run. Servlet: the fraud
detectors only.

## GCs and live stacks

![GCs at 150k req/s](charts/gcs-150k.svg)

![Live stacks of virtual threads](charts/stackchunks.svg)

- GCs: user `small` and servlet.
- Live StackChunks: user `small`. The stacks of virtual threads alive at the end of the measure of
  the c2c run. The count during the warm-up is in [`charts/stackchunks.csv`](charts/stackchunks.csv).

## Sources

- Tables: filled by [`scripts/publish.sh`](../../scripts/publish.sh) from the At a glance and the
  In short of the summaries of each build, linked at the top. Their notes say how each number is
  read from the files of the runs.
- Charts: [`charts/user.csv`](charts/user.csv), [`charts/servlet.csv`](charts/servlet.csv) (one row
  per ramp step), [`charts/user-max.csv`](charts/user-max.csv),
  [`charts/servlet-max.csv`](charts/servlet-max.csv) (one row per ramp), from the consoles of the
  ramps. [`charts/measure-150k.csv`](charts/measure-150k.csv) (one row per run), from the consoles of the
  runs at 150k req/s and of their GC logs. [`charts/walk-l3.csv`](charts/walk-l3.csv), from the
  Walk and perf c2c tables of the summaries. [`charts/stackchunks.csv`](charts/stackchunks.csv),
  from the consoles of the c2c runs.
- Run it: [build the JDK](../../mpsc-scheduler/README.md#the-jdk),
  [run the benchmarks](../benchmark.md#running-it), [what a campaign contains](campaigns.md).
