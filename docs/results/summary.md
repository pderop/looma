# Results

| Build | `Thread.Affinity` | Results | What it is |
| --- | --- | --- | --- |
| `pr227` | yes | [at a glance](pr227/summary.md) · [user](pr227/user/summary.md) · [servlet](pr227/servlet/summary.md) · [servlet skewed](pr227/servlet-skewed/summary.md) | [openjdk/loom pull request 227](https://github.com/openjdk/loom/pull/227) |
| `noaff` | no | [at a glance](noaff/summary.md) · [user](noaff/user/summary.md) · [servlet](noaff/servlet/summary.md) · [servlet skewed](noaff/servlet-skewed/summary.md) | the branch `mpsc-without-affinity` of `franz1981/loom` |

The commit and the kernel of each benchmark: the table The runs, in the "at a glance" page of
each build.

## Setup

- Machine: an AMD Ryzen 9 9950X, one carrier per core
  ([the machine](../benchmark.md#amd-ryzen-9-9950x)).
- Each benchmark runs alone, with its own settings. Each scheduler runs one round or more (r1,
  r2, ...). A round: a measure at a fixed rate, then a ramp: from a start rate, up by a fixed
  step, until a step is not kept up with.
- The settings of a benchmark: the header of its summary (carriers, build, kernel, command) and
  its section The runs (rounds, rate, ramp).
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
| `mpsc` | both | no | no |
| `jdk` | `pr227` | no | no |

- builds: the builds it ran on, here.
- `mpsc*`: the JDK's MPSC scheduler. `jdk`: the JDK's default scheduler, a `ForkJoinPool`.
- An actor stays on its carrier only with `Thread.Affinity`: `pr227` only.
- On `noaff`, under `mpsc`, the thread of an idle actor waits for the next message instead of
  ending ([the keep-alive](../schedulers.md#the-keep-alive)).

How to read:

- The commands: the measure runs at `--rate`; the ramp starts there and adds `--step` at each step.
- The bars and the lines: each ramp, r1 and r2. The dots: the mean of the measures, r1 and r2; a
  measure that did not keep up with its rate is left out. The walk, other L3 and StackChunks charts: one run each, in round 1.
- Max rate: one bar per ramp, r1 then r2. Pale: the peak rate, the last step kept up with. Solid:
  the highest rate with p99 < 1 ms. none: already above 1 ms at the first step.
- The charts by ramp step show `mpsc-affine-pinned`, `mpsc` and `jdk`: dashed for
  `pr227`, solid for `noaff` (`mpsc` only). A point is the mean of r1 and r2; a line ends at the
  last step both ramps kept up with (the max rates: the bar charts). The other charts show every
  scheduler.

<!-- begin charts -->

<!-- end charts -->

## User benchmark

```
sdk use java 28-loom-pr227
scripts/bench-user.sh --users-per-group 8000,200000 --depth 256 --rate 150000 --step 20000 --out bench/user-pr227
sdk use java 28-loom-noaff
scripts/bench-user.sh --users-per-group 8000,200000 --depth 256 --rate 150000 --step 20000 --out bench/user-noaff
```

8000 users per group is `small`, 200000 is `big`. Each measure also runs at depth 0.

### Max rate

![User benchmark, case small: max rate](charts/user-small-max.svg)

![User benchmark, case big: max rate](charts/user-big-max.svg)

### p99

![User benchmark: p99 at 150k](charts/user-p99-measure.svg)

At the other rates, the p99 at each step of each ramp. Case `big` has no chart by step.

![User benchmark: p99 by rate](charts/user-p99.svg)

### CPU per request

![User benchmark: CPU per request at 150k](charts/user-cpu-measure.svg)

At the other rates, the CPU per request at each step of each ramp.

![User benchmark: CPU per request by rate](charts/user-cpu.svg)

![User benchmark: walk cycles and loads from another L3](charts/user-walk-l3.svg)

- walk cycles/req: the cycles spent in the walk alone, per request.
- other L3: the share of the sampled loads that came from behind another L3.

![User benchmark: CPU per request with no work per actor](charts/user-cpu-depth0.svg)

Depth 0: the actor does not walk its objects.

### Migrations

![User benchmark: migrations at 150k](charts/user-migrations.svg)

How they are counted: each actor checks one message in 64. At the end of that message, it notes
its carrier: the end of the thread's `toString()`, after its `@`. At the start of its next message,
it looks again: a different carrier is a migration. The value: migrations / checked messages, all
actors, during the measure.

### GCs and live stacks

![User benchmark: GCs at 150k](charts/user-gcs.svg)

![User benchmark: live stacks of virtual threads](charts/user-stackchunks.svg)

- GCs: case `small`.
- Live StackChunks: case `small`. The stacks of virtual threads alive at the end of the measure of
  the c2c run. The count during the warm-up is in [`charts/stackchunks.csv`](charts/stackchunks.csv).

## Servlet benchmark

```
sdk use java 28-loom-pr227
scripts/bench-servlet.sh --containers 10 --rate 150000 --step 20000 --out bench/servlet-pr227
sdk use java 28-loom-noaff
scripts/bench-servlet.sh --containers 10 --rate 150000 --step 20000 --out bench/servlet-noaff
```

### Max rate

![Servlet benchmark: max rate](charts/servlet-max.svg)

### p99

![Servlet benchmark: p99 at 150k](charts/servlet-p99-measure.svg)

At the other rates, the p99 at each step of each ramp.

![Servlet benchmark: p99 by rate](charts/servlet-p99.svg)

### CPU per request

![Servlet benchmark: CPU per request at 150k](charts/servlet-cpu-measure.svg)

At the other rates, the CPU per request at each step of each ramp.

![Servlet benchmark: CPU per request by rate](charts/servlet-cpu.svg)

![Servlet benchmark: loads from another L3](charts/servlet-l3.svg)

- other L3: the share of the sampled loads that came from behind another L3.

### Migrations

![Servlet benchmark: migrations at 150k](charts/servlet-migrations.svg)

Counted as in the user benchmark, on the fraud detectors only.

### GCs

![Servlet benchmark: GCs at 150k](charts/servlet-gcs.svg)

## Skewed load

A few actors get much more work than the others. The servlet runs 20 containers on its 10
carriers (`--containers 20`). The 2 containers that start on carrier 0 get half the requests,
25% each, the 18 others 2.8% each ([a skewed load](../benchmark.md#a-skewed-load)). Only the
affine schedulers keep them on carrier 0. Results:
[pr227](pr227/servlet-skewed/summary.md), [noaff](noaff/servlet-skewed/summary.md).

```
sdk use java 28-loom-pr227
scripts/bench-servlet.sh --schedulers mpsc,mpsc-affine-pinned,jdk --hot-carrier 50 --containers 20 --rate 150000 --step 20000 --out bench/servlet-skewed20-pr227
sdk use java 28-loom-noaff
scripts/bench-servlet.sh --schedulers mpsc --hot-carrier 50 --containers 20 --rate 150000 --step 20000 --out bench/servlet-skewed20-noaff
```

![Servlet benchmark, skewed load: max rate](charts/servlet-skewed-max.svg)

## Sources

- Notes of the charts, between `<!-- begin charts -->` and `<!-- end charts -->`: written by
  [`scripts/publish.sh`](../../scripts/publish.sh), empty when every ramp kept up with a step.
- Charts: [`charts/user.csv`](charts/user.csv), [`charts/servlet.csv`](charts/servlet.csv) (one row
  per ramp step), [`charts/user-max.csv`](charts/user-max.csv),
  [`charts/servlet-max.csv`](charts/servlet-max.csv),
  [`charts/servlet-skewed-max.csv`](charts/servlet-skewed-max.csv) (one row per ramp), from the
  consoles of the ramps. [`charts/measure.csv`](charts/measure.csv) (one row per measure), from
  the consoles of the measures and of their GC logs. [`charts/walk-l3.csv`](charts/walk-l3.csv),
  from the Walk and perf c2c tables of the summaries. [`charts/stackchunks.csv`](charts/stackchunks.csv),
  from the consoles of the c2c runs.
- Run it: [build the JDK](../../mpsc-scheduler/README.md#the-jdk),
  [run the benchmarks](../benchmark.md#running-it), [what a campaign contains](campaigns.md).
