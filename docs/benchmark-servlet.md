# The servlet benchmark

A mini servlet container, written with Looma actors. It serves a small online shop:

- The shop sells products. Each one has a name and a price.
- Each visitor has a session. The server keeps its state.
- `GET /profile`: the visitor views a product. `ProfileServlet` remembers it, and answers with the
  products viewed last, and their prices.
- `POST /cart`: the visitor adds a product to its cart. `CartServlet` adds it, and answers with the
  lines of the cart, and the total.
- First, a servlet sends a check to `FraudDetectorActor`, the fraud detector of its container, for
  the risk of the request. The detector scores it from a table it keeps, and asks the backend for a flag.
  The page shows the risk.
- Then, before it answers, the servlet calls a backend, in another process.

**The question:** how far does the server go, and what is its latency below that, under each
scheduler?

![One request in the servlet benchmark](images/servlet-request.svg)

- Code: [`examples/…/servlet`](../examples/src/main/java/io/github/pderop/looma/examples/servlet)
- What the two benchmarks share (the load, the runs, the machine, how to run them):
  [`benchmark.md`](benchmark.md)
- The results: [`results/summary.md`](results/summary.md)

## The load

`LoadGenerator` sends the load, as described in [`benchmark.md`](benchmark.md#the-load). Here, the
requests are:

- Each request picks a random session id, among `--sessions` (100,000 by default).
- Each request also picks a random product, among `--products` (10,000 by default).
- Half the requests are `GET /profile?viewed=<product>`. The other half are `POST /cart`, with
  the form `product=<product>&qty=<1 to 3>`.
- An answer other than 200 is a failure.

## What a request does

One container per carrier, a pool of actors: `containers.forKey(sid)` sends a session always to
the same one. Its two servlets and its fraud detector are its child actors, with its home carrier:
under `affine(true)`, they stay there. Each servlet is a `ServletActor` that holds a
`ProfileServlet` or a `CartServlet`: the sessions and the page. A wrong path gets a 404, a wrong
method a 405.

```
1. handleRequest     -> ask(request) to the container of the session
2. container         -> tell to the servlet of the path
3. servlet           -> updates the session, tell(FraudCheck) to the detector
4. detector          -> scores the request from its table, arms a timer, starts a virtual thread
   call thread       -> borrows a connection, asks the backend for a flag
                        tell(BackendAnswered) to the detector
5. detector          -> cancels the timer, learns from the flag, in the same records
                        tell(FraudVerdict) to the servlet
6. servlet           -> arms a timer, starts a virtual thread for its call
   call thread       -> borrows a connection from the pool, waits if none is free
                        sends its GET, reads the body into a buffer lent by the servlet,
                        gives the connection back
                        tell(BackendAnswered)
7. servlet           -> cancels the timer, parses the body and takes its buffer back,
                        writes the page in its output buffer, with the risk
                        tell to the container, with the text of the page
8. container         -> answers the client: completes its future, on the container's carrier
```

The state of a session lives in its servlet, in arrays of ints made with the session. A request
writes ints in them and allocates nothing there:

- `ProfileServlet`: the visits, and the products viewed last, each once, the last one first
  (`--recent-max`, 20 by default). The answer lists them with their prices.
- `CartServlet`: the cart, one line per product with its quantity (`--cart-max` lines, 20 by
  default; a new line beyond that drops the oldest). The answer lists the lines with their prices,
  and the total.

The state of the fraud detector is one table of ints per container, made at start and never
resized: `-Dservlet.fraudKiB`, 1 MiB by default, a power of two. A record is four ints: a count,
an amount, the check that touched it last, and a weight. A check touches `-Dservlet.fraudFeatures`
records, 256 by default. Each one is a feature of the request (the session, the product, the
quantity, the time, and mixes of them), hashed to a record. So the records of a check are spread
over the whole table, and there is no reference to follow. A check writes them twice: once to score
the request, once to learn from the backend's answer. About one answer in 64 comes back flagged:
the flag only depends on the size of the body. The detector refuses nothing: the risk only goes in
the page, and the answer stays a 200.

The products are one map, built at start, shared by every servlet and never changed. An unknown
product, or a cart form without a quantity, gets a 400 and no backend call.

The sizes change from one request to the next, from a generator seeded with 1:

| What | Size |
| --- | --- |
| padding of the backend request | 0 to 2 KiB |
| body asked of the backend | 256 bytes to 16 KiB |
| body asked of the backend by the fraud detector, no padding | 256 bytes to 1 KiB |
| page written for the client | 512 bytes to 32 KiB |

The bodies and the pages are written in buffers that each actor reuses.

**The timer.** Each backend call arms a timer on the actor that calls, a delayed `tell` of 3 s
(`-Dservlet.backendTimeoutMillis`). The answer cancels it. If the timer comes first, the client
gets a 504, and the call is interrupted. A call still waiting for a connection ends there. A call
reading the backend loses its connection, and a new one is opened.

**The backend calls.** Each one runs on a virtual thread from `ActorContext.vThreadFactory()`: no
`onReceive` blocks on a socket. Each container has one pool of `--connections` connections (8 by
default), shared by its servlets and its detector. A call takes the connection given back last; when
none is free, it waits, and the one waiting the longest gets the next. So at most carriers ×
`--connections` backend calls run at once.

The server starts the load once every container has its backend connections. If one cannot
connect, the server does not start.

**The backend** is a plain NIO server in another JVM (`-Xms1g -Xmx1g -XX:+AlwaysPreTouch`, G1, with
its own GC log), on the same [`NioServer`](../examples/src/main/java/io/github/pderop/looma/examples/utils/NioServer.java)
as the user benchmark's backend: one accept thread, one reactor thread per backend CPU, no actor and
no virtual thread. It answers `GET /data?bytes=N` with a body of N bytes, cut from one shared buffer.

## The runs

One server JVM per run, under taskset on the carrier CPUs:

```
1. start the backend once, under taskset on the backend CPUs
2. warm the backend: a throwaway server JVM sends --rate for --backend-warmup
   seconds (30), results thrown away (runs/servlet-backend-warmup-<scheduler>.out)
3. for each round (--rounds, 2): the measure of every scheduler (r1, r2); in round 1 only, a
   c2c run of every scheduler, then a stat run of every scheduler; then the ramp of every
   scheduler (r1-ramp, r2-ramp), as in benchmark.md. Each measure:
   a. the containers open their backend connections
   b. the client threads move to the client CPUs
   c. 10 seconds of warm-up, thrown away: the rate goes up from a tenth of --rate to --rate
      (150,000)
   d. 20 seconds measured, then up to 5 s for the last answers, then the report
4. stop the backend
```

Besides [cpu µs/req](benchmark.md#the-runs), the console of each run, and each `STEP` line of
`scripts/jmh-servlet.sh` (`migrations_pct`), give the **migrations** of the fraud detectors: one
message of a detector in 64 is checked, and the line gives the share that ran on another carrier
than the message before.

## Running it

[How to run the benchmarks](benchmark.md#running-it). For this one:

```bash
scripts/bench-servlet.sh                               # this one
scripts/bench-servlet.sh --step 10000                  # smaller steps in the ramp
scripts/bench-servlet.sh --connections 64
scripts/bench-servlet.sh --schedulers mpsc-affine-pinned,mpsc,jdk
```

Under JMH, with the backend of `scripts/jmh-servlet-start-backend.sh`:

```bash
scripts/jmh-servlet.sh --schedulers mpsc-affine-pinned,mpsc,jdk
scripts/jmh-servlet.sh -- -p fraudKiB=128 -p fraudFeatures=32   # a smaller table, fewer records
```

## Why it is built this way

The choices shared with the user benchmark are in [`benchmark.md`](benchmark.md#why-it-is-built-this-way).

**Why this benchmark.** The user benchmark chases pointers on purpose, to show the cache. A real
server does not spend its time that way. This one looks like a small web application: sessions, a
shared catalog, a call to another service, a page to build. It also uses more of Looma:

| What a request uses | User benchmark | Servlet benchmark |
| --- | --- | --- |
| `ask` from the client | yes | yes |
| a virtual thread per backend call | yes | yes |
| child actors, on their parent's carrier | no | the two servlets and the fraud detector of a container |
| a pool of actors, and `forKey` | no | the containers, one key per session |
| messages from actor to actor | no | container to servlet to detector, and back |
| a delayed `tell`, and its cancel | no | one per backend call, two per request |

- **A virtual thread per backend call:** it starts on the actor's carrier, so its socket can be
  registered on that carrier's poller.
- **The timers:** a server guards each call to another service with a timeout. Here each backend
  call arms a timer and cancels it, so a run does as many arms and cancels as backend calls. Almost
  none fires. The timers are part of the cost of a request.
- **The state in arrays of ints:** a request writes in memory that already exists. So it adds no
  work for the GC in the old generation, and GC pauses do not hide what the scheduler does.
- **Buffers that each actor reuses:** a server writes its bodies and pages in buffers before its
  sockets, and reuses them.
- **Sizes that change, from a seed:** real requests and pages are not all the same size. With a
  seed, a run sees the same sizes each time.
- **Parsing and building a page:** a real request does ordinary CPU work too, not only messages. The
  answer carries a checksum of the body, so the JIT cannot remove the parse.
- **A warmed backend:** the backend JIT is ready before the first measure, so the first scheduler is
  not at a disadvantage.
- **The fraud detector:** a real shop checks its requests for fraud, and keeps counters for it in
  memory. It adds two messages and one backend call to each request, as a server with several
  services would.
- **Its table, without pointers:** a check reads and writes records spread over a flat table. On
  the same core as the last check, it finds them in that core's cache; on another core, it fetches
  them from the core that wrote them. So this cost depends on where the scheduler runs the detector,
  and the migrations line shows how often it moved.
- **A table of 1 MiB, 256 records a check:** one table is about one core's L2, all of them several
  times it. A check touches enough records to weigh on each request. Bodies and pages push part of
  the table out between two checks. `-p fraudKiB` and `-p fraudFeatures` try other sizes.
- **A backend call on each check:** every request has the same shape, so a result has no cache hit
  rate to explain.
