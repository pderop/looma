# The user benchmark

**The question:** an actor owns private data. If the scheduler keeps it on one core, does the data
stay in that core's cache, and does that save CPU?

**How:** the same program, under several schedulers, at 150,000 requests per second. Each request
makes an actor read a few hundred of its objects. Compared: CPU per request, latency, and how often
actors moved.

- Code: [`examples/…/examples/user`](../examples/src/main/java/io/github/pderop/looma/examples/user)
- What the two benchmarks share (the load, the runs, the machine, how to run them):
  [`benchmark.md`](benchmark.md)
- The results: [`results/summary.md`](results/summary.md)

## The program

A made-up service that recommends users. Each actor owns a group of users. A request makes it
call a backend, then walk a chain of friends through its users, writing in each one.

![One request in the user benchmark](images/user-request.svg)

**At start**, in [`Main`](../examples/src/main/java/io/github/pderop/looma/examples/user/Main.java):

```
1. the scheduler of -Dbench.scheduler, one carrier per CPU of --cpus-carriers
2. one UserGroupActor per carrier, placed on it
     without affinity (mpsc, mpsc-pinned, jdk, jdk-pinned), it can run on any carrier
3. each group creates its users (--users-per-group), on the carrier it runs on
     group i owns users i, i + groups, i + 2 × groups, ...
     a user: a friend, a visit count, a score, the time of its last visit
     created in a random order; the friendships form one cycle, in another random order
4. each group opens a fixed pool of 8 connections to the backend
     a GET takes the connection given back last
     when all are in use, the GET waits in the pool: the one waiting the longest gets the next
```

**The cases**, by `--users-per-group`. A user is about 82 bytes.

| Case | Users per group | One group |
| --- | --- | --- |
| `small` | 8000 | about 640 KiB |
| `big` | 200000 | about 16 MiB |

Where the data lives, 10 groups, 6 on CCD0 and 4 on CCD1 ([the layout](benchmark.md#the-layout)):

```
small    one group about 640 KiB: fits in a 1 MiB L2
         10 groups about 6.3 MiB: several L2s; the groups of a CCD (3.8 MiB at most) fit in its 32 MiB L3
         -> a moved actor reads its data from the L3 (same CCD),
            or through the Infinity Fabric (other CCD)
big      one group about 16 MiB: does not fit in an L2
         the groups of a CCD (about 63 to 94 MiB) do not fit in its L3
         -> every actor reads most of its data from memory, moved or not
```

**The load:** `LoadGenerator` sends it, as described in [`benchmark.md`](benchmark.md#the-load).
Here, each request asks for a random user.

**One request:**

```
1. handleRequest      -> ask(Recommend) to the group that owns the user
2. actor: Recommend   -> starts a virtual thread for the GET
3. GET thread         -> borrows a connection from the pool, waits if none is free
                         sends an HTTP GET to the backend, parks on the response
                         the response arrives: the JDK wakes it (with mpsc: the carrier's own
                         epoll) -> back on a carrier -> gives the connection back
4. GET thread         -> tell(UpstreamAnswered) to the group
5. actor: UpstreamAnswered
                      -> walks --depth users (256 by default) from the one asked for,
                         writes in each
6. actor              -> answers the client, which records the latency, from the planned send time
```

At each message after an empty mailbox, the scheduler picks the carrier of the actor's thread: a
new mailbox thread, or the one that waited ([the keep-alive](schedulers.md#the-keep-alive)):

```
same carrier   -> the users are still in that core's cache -> fast walk
other carrier  -> the users are read again                  -> slow walk
```

**The backend**, [`BackendServer`](../examples/src/main/java/io/github/pderop/looma/examples/user/BackendServer.java),
in its own JVM, on [`NioServer`](../examples/src/main/java/io/github/pderop/looma/examples/utils/NioServer.java):
one thread accepts, and one reactor thread per backend CPU (`--reactors`) answers `GET /user` with
`200 OK` and a small fixed body, at once. A connection stays on the thread it was given at accept.

## The runs

Each run is one JVM, as in [the runs](benchmark.md#the-runs) of `benchmark.md`. Besides cpu µs/req,
its console prints:

- **migrations**: how often an actor ran a message on another carrier than the one before, on one
  message in 64.
- the requests per second, the latencies, and whether it kept up with the rate.

What the script does:

```
1. start the backend once, under taskset on the backend CPUs
2. every run: taskset on the carrier CPUs
3. for each case (small, then big), for each round (--rounds, 2):
   a. measure, per scheduler:     a run at --depth, then one at depth 0 (r1, r2)
   round 1 only:
   b. c2c run, per scheduler:     perf c2c over the measure, minus 3 s at each end;
                                  live StackChunks counted during the warm-up and after the
                                  measure (full GCs, left out of the GC column)
   c. stat run, per scheduler:    perf stat, cycles and instructions of the server's threads
   d. walk run, per scheduler:    perf record of the cycles, with UserGroupActor.walk kept out of
                                  line: the cycles of the walk alone
   e. epsilon run, per scheduler: -XX:+UseEpsilonGC -Xms12g -Xmx12g: no GC
                                  (what a run allocates: the Epsilon table of summary.md)
   every round:
   f. ramp, per scheduler:        at depth 256, the ramp of benchmark.md (r1-ramp, r2-ramp): the
                                  max req/s of At a glance
4. summary.md, both cases
```

6 schedulers: 60 runs per case, 120 in all, plus the steps of the ramps. The script prints its
estimate when it starts.

## Running it

[How to run the benchmarks](benchmark.md#running-it). For this one:

```sh
scripts/bench-user.sh                                # this one
scripts/bench-user.sh --users-per-group 8000         # the small case only
scripts/bench-user.sh --no-depth0                    # at --depth only
```

## Why it is built this way

The choices shared with the servlet benchmark are in [`benchmark.md`](benchmark.md#why-it-is-built-this-way).

- **A graph walk:** each user gives the next one, so the CPU cannot read ahead. The walk goes as fast
  as the cache that holds the users.
- **The walk writes:** the data is modified in the cache of the core that runs it. A moved actor
  reads it again: from the L3, the old core, or memory.
- **Two sizes:** the data of an actor fits in one core's L2 in `small`, and not in `big`.
- **Depth 0:** everything but the walk: the messages, the GET, the scheduling.
- **The walk run:** `UserGroupActor.walk` kept out of line has a name of its own in `perf`. Its cycles
  are the walk alone.
- **StackChunks counted:** if parked stacks piled up on the heap, the GC would do more under some
  schedulers. Two counts show it.
