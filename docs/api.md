# The Looma API

Everything an application touches lives in one package, `io.github.pderop.looma`. Ten types, no
annotations, no configuration file. The actor code never names a scheduler; `main` may, through
`Carriers`.

```java
import io.github.pderop.looma.*;
```

| Type | What it is |
| --- | --- |
| `ActorSystem` | The root of a hierarchy. Spawns root actors, resolves paths, shuts everything down. |
| `Actor` | The POJO you write: `onReceive` plus four lifecycle hooks. |
| `ActorRef` | An opaque, thread-safe handle: `tell`, `ask`, and delayed or periodic `tell`. |
| `ActorContext` | An actor's view of itself, its children, and the system. |
| `Message` | The marker interface every payload implements. |
| `SupervisionStrategy` / `Directive` | What happens when `onReceive` throws. |
| `Placement` / `SpawnOptions` | Optional home-carrier placement, supervision, and pool size at spawn time. |
| `Carriers` | Which scheduler carries the process, and how it is sized, when chosen in code. |

The scheduler SPI (`io.github.pderop.looma.spi`) is for people *implementing* a scheduler, not for
people using actors — see [`schedulers.md`](schedulers.md).

---

## 1. Messages

A message is any type implementing the `Message` marker. Records are the natural fit:

```java
public record Deposit(long cents) implements Message {}
public record Withdraw(long cents) implements Message {}
public record GetBalance() implements Message {}          // the request
public record Balance(long cents) implements Message {}   // the reply
```

The marker exists only so `tell` and `ask` cannot be handed an arbitrary `Object`. A message carries
no routing of its own: where it goes is decided entirely by the `ActorRef` it is sent through.

`Message` itself is **not** sealed, and must not be: the engine is protocol-agnostic, and every actor
has its own types. Grouping *one* actor's protocol under a sealed interface that extends `Message`
is idiomatic, and is what makes `onReceive` exhaustive. Nested records are implicitly
`permits`-listed, so the family lives in one type:

```java
public sealed interface AccountMessage extends Message {
    record Deposit(long cents) implements AccountMessage {}
    record Withdraw(long cents) implements AccountMessage {}
    record GetBalance() implements AccountMessage {}      // the request
}

public record Balance(long cents) implements Message {}   // the reply — see ask() below
```

`tell` and `ask` still take `Message`; a sealed subtype *is* a `Message`, so nothing else in the API
changes. `onReceive` is still declared as `Message` — peel with `instanceof`, then `switch` on the
sealed type, and the compiler requires every permitted case.

A reply can sit in the same sealed family if you want one namespace; the actor's `switch` then has
to name it. Putting outbound-only replies beside the family, as `Balance` is here, keeps the inbound
`switch` to the messages the actor actually handles. When a reply legitimately has several shapes,
give *those* a sealed supertype of their own — see
[ask](#the-reply-type-is-declared-and-checked).

Messages cross carriers, and therefore cores. **Treat them as immutable.** Nothing enforces it, and
sending a mutable object you keep writing to reintroduces exactly the data race the actor model
removes.

## 2. Writing an actor

An actor runs on a virtual thread only while it has messages: its **mailbox thread**. The first
message of an empty mailbox starts it; it runs the messages one at a time, then ends once the
mailbox is empty. The next message starts a new one.

```java
public class Account implements Actor {

    private long balance;              // no volatile, no lock, no synchronized

    @Override
    public void onReceive(Message message, ActorRef sender, ActorContext context) {
        if (!(message instanceof AccountMessage m)) {
            return;                    // unknown messages are this actor's own business
        }
        switch (m) {                   // exhaustive: every permitted subtype is required
            case AccountMessage.Deposit d -> balance += d.cents();
            case AccountMessage.Withdraw w -> {
                if (w.cents() > balance) {
                    throw new IllegalStateException("overdraft");   // → supervision
                }
                balance -= w.cents();
            }
            case AccountMessage.GetBalance ignored -> {
                if (sender != null) {
                    sender.tell(new Balance(balance), context.self());
                }
            }
        }
    }
}
```

`onReceive` is **never invoked concurrently for the same instance**, so mutable fields need no
synchronization at all. The four lifecycle hooks run under the same guarantee — never concurrently
with `onReceive`, and never concurrently with each other.

The `sender` parameter is the reference given to `tell(message, sender)`. It is `null` only for
`tell(message)`, which names no sender. For a message that arrived through `ask`, it is a single-use
reference standing for the asker, and **replying to it is the only thing that completes the future**.

### Lifecycle hooks

```java
public class Connection implements Actor {

    private Socket socket;

    @Override
    public void preStart(ActorContext context) throws Exception {
        socket = open();               // runs in this actor's first mailbox thread, before the first message
    }

    @Override
    public void postStop(ActorContext context) throws Exception {
        socket.close();                // after the mailbox drained and every child fully stopped
    }
}
```

| Hook | When | If it throws |
| --- | --- | --- |
| `preStart(context)` | once, before the first message — even if none is ever sent | the actor is stopped immediately; no strategy is consulted (it could only restart into the same failure) |
| `postStop(context)` | once, after the mailbox is drained and every child has fully stopped | logged; termination completes anyway |
| `preRestart(cause, message, context)` | on the failing instance, before it is discarded. Defaults to `postStop` | the restart escalates to a `STOP`, logged |
| `postRestart(cause, context)` | on the fresh instance, before it resumes draining. Defaults to `preStart` | the restart escalates to a `STOP`, logged |

`preStart` is enqueued as the first mailbox entry, so it is guaranteed to complete before any
message is delivered — but not necessarily before `spawn` returns to the caller.

Allocate per-actor state in `preStart`, not in the constructor: `preStart` already runs on the
actor's home carrier, so, with carriers pinned to CPUs, its memory is **first touched by the core
that will use it**. Under an affine scheduler that is the difference between state that starts hot
and state that has to be migrated once.

## 3. Starting a system, spawning actors

```java
ActorSystem system = ActorSystem.builder().build();

ActorRef account = system.spawn("account", Account::new);
```

`ActorSystem.builder()` is the only entry point; no caller ever names an implementation type. The
builder has four knobs, and applications normally need at most the first:

```java
ActorSystem system = ActorSystem.builder()
        .carriers(Carriers.affine(8))        // chooses and sizes the JVM's scheduler, in code
        .mpscQueueFactory(myQueueFactory)    // every MPSC queue: mailboxes and timer queues; default ConcurrentLinkedQueue
        .timerTick(Duration.ofMillis(10))    // precision of delayed and periodic tell; default 10 ms
        .build();

ActorSystem.builder().scheduler(someScheduler);    // bypasses everything — tests only
```

`carriers(...)` is the code form of the scheduler's JVM flags: `Carriers.affine(n)` for a
carrier-affine pool, today the JDK's MPSC scheduler, `Carriers.builtin(n)` for the JDK's default
one with its parallelism fixed. They have no options.
Without it, the system takes whatever the flags installed. Because the JDK resolves its scheduler
once per process, `build()` must run **before the JVM's first virtual thread**, and throws rather
than run on the wrong scheduler — see [`schedulers.md`](schedulers.md#in-code) for every case it
refuses.

With `affine`, an actor never leaves its home carrier, so its state stays in that
core's cache. An idle carrier may steal only the threads actors create through
`ActorContext.vThreadFactory()`.

The `factory` passed to `spawn` is a `Supplier<Actor>`, not an instance, because the engine calls it
**again** to obtain a fresh instance after a `RESTART`.

Names must be non-blank, contain no `/` (which would forge a path), and be unique among siblings.

One `spawn` can also create **N actors of the same type** behind a single load-balancing reference —
see [Pools](#pools-n-actors-of-one-type-behind-one-reference).

## 4. The hierarchy

A child is spawned from its parent's context — often in `preStart`, but then the parent cannot be
restarted ([§7](#7-supervision)):

```java
public class Db implements Actor {

    private ActorRef worker;

    @Override
    public void preStart(ActorContext context) {
        worker = context.spawn("worker", Worker::new);   // path: /db/worker
    }

    @Override
    public void onReceive(Message message, ActorRef sender, ActorContext context) {
        worker.tell(message, context.self());            // same carrier: no cross-core hop
    }
}
```

```
ActorSystem
 ├── /account
 └── /db          ← ActorSystem.spawn("db", Db::new)
      └── /db/worker   ← context.spawn("worker", Worker::new)
```

- `ref.name()` is the simple name (`"worker"`); `ref.path()` is the full path (`"/db/worker"`).
- `context.children()` is an immutable **point-in-time snapshot**, never a live view.
- **`findActor` always resolves from the root, on both surfaces.** A worker at `/db/worker` calling
  `context.findActor("sibling")` resolves `/sibling`, *not* `/db/sibling`. There is one resolution
  rule everywhere; a path with no `/` is resolved as a root name.

```java
Optional<ActorRef> ref = system.findActor("/db/worker");
Optional<ActorRef> db  = system.findActor("db");        // same as "/db"
```

## 5. `tell` and `ask`

```java
account.tell(new AccountMessage.Deposit(500), context.self());   // fire and forget, with a sender to reply to
account.tell(new AccountMessage.Deposit(500));                   // no sender — from main(), a test, a callback
account.tell(new AccountMessage.Deposit(500), 50, TimeUnit.MILLISECONDS);  // later — see Timers

CompletableFuture<Balance> reply = account.ask(new AccountMessage.GetBalance(), Balance.class);
```

A `tell` with a delay, or repeated, is in [§10 Timers](#10-timers).

`ask` creates a single-use, unregistered reply channel and passes it as the message's `sender`. The
receiving actor replies exactly as it would to anyone else:

```java
case AccountMessage.GetBalance ignored -> sender.tell(new Balance(balance), context.self());
```

The reply completes the future on the mailbox thread of the actor that replies. A stage
chained with `thenApply` or `thenAccept` runs there too, and that actor handles nothing else
meanwhile: chain slow work with `thenApplyAsync`.

The returned future completes **at most once**: a second reply is silently dropped. There is no
timeout on `ask` itself — an actor that never replies leaves the future open. Bound it with
`CompletableFuture.orTimeout` (or `get` with a timeout) when you need one:

```java
account.ask(new AccountMessage.GetBalance(), Balance.class)
        .orTimeout(1, TimeUnit.SECONDS)
        .join();
```

### The reply type is declared, and checked

The `Class<R>` is what spares the caller a cast — `ask` hands back a `CompletableFuture<Balance>`,
not a `CompletableFuture<Message>` to pattern-match. It is also **enforced**: an actor that replies
with something else fails the future with a `ClassCastException` naming both types, at the moment
the wrong reply arrives.

That check is the whole reason the type is passed as a value rather than inferred from the
assignment. An inferred `<R>` would compile just as prettily and cast unchecked, so a wrong reply
would surface as a `ClassCastException` somewhere else entirely — or be quietly stored in a
collection and blow up much later:

```java
// what an inferred type parameter would allow, and this API does not
CompletableFuture<Balance> f = account.ask(new AccountMessage.GetBalance());
List<Balance> all = List.of(f.join());   // fine at compile time; the object is a Rejected
```

A wrong-typed reply **settles** the promise: a correct reply the actor sends immediately afterwards
is dropped like any other second reply. An actor whose first reply is a bug should not look healthy
because it managed to send a second one.

When a reply legitimately has several shapes, give them a common supertype — a `sealed interface` is
the natural one — and `switch` over the result, still without a cast:

```java
sealed interface LookupResult extends Message permits Found, NotFound {}

LookupResult result = db.ask(new Lookup(key), LookupResult.class).join();

String value = switch (result) {              // exhaustive, no default needed
    case Found found -> found.value();
    case NotFound ignored -> "";
};
```

When there is no useful common supertype at all, the one-argument
`ask(message)` is exactly `ask(message, Message.class)` and accepts anything.

| Situation | `tell` | `ask` |
| --- | --- | --- |
| target already stopped, or unknown | silently dropped (dead letter) | future fails synchronously with `IllegalStateException` |
| system shutting down | dropped once that actor is stopping: a root at once, a descendant when the cascade reaches it | fails synchronously with `IllegalStateException` |
| target accepted the request, then stopped without replying | — | future stays open — bound it with `orTimeout` |
| no reply | — | future stays open — bound it with `orTimeout` |

`ask` is stricter than `tell` on a request known to be unanswerable at call time: it fails
immediately rather than being queued to a mailbox that will never deliver a reply. An accepted
`ask` that never gets a reply is the caller's to time out; Looma adds no timeout to `ask`. A [timer](#10-timers) only sends a `tell`.

An `ActorRef` is safe to share across threads, store in a message, or hold past the lifetime of the
actor it points to. Identity is plain reference identity — two lookups of the same live actor return
the same instance.

## 6. Blocking work

An actor runs on a virtual thread, so blocking inside `onReceive` **parks** it: the carrier is handed
back, no OS thread is consumed, and the rest of the machine keeps working. Blocking is not a
correctness problem here, and it is not a scheduler problem.

What it costs is narrower: **an actor's mailbox thread is the only consumer of its own mailbox**, so
for as long as `onReceive` sits inside a blocking call, that actor answers nothing. Neighbours have
mailbox threads of their own and keep running; this actor's messages queue up behind a thread that
is only waiting.

So:

- **Block, if there is nothing else the actor could usefully do meanwhile** — a strictly sequential
  protocol has no line to be head-of-line-blocked, and forking would only add a hop.
- **Block behind a [pool](#pools-n-actors-of-one-type-behind-one-reference), if the *work* is what
  must stay responsive** — N actors of the same type behind one reference: a blocked one stalls only
  its own mailbox and its `n-1` peers keep serving. No forking, no captured state, no fold-back
  message; the price is that ordering is per actor rather than pool-wide.
- **Fork, if the actor must stay responsive while it waits** — several conversations, a control
  channel, a `Cancel` it must accept, an aggregation to keep feeding:

```java
@Override
public void onReceive(Message message, ActorRef sender, ActorContext context) {
    if (message instanceof Query query) {
        // Capture what the forked thread needs BEFORE starting it: `sender` is a parameter of this
        // call only, and the context must not be read concurrently with the next dispatch.
        ActorRef self = context.self();
        ActorRef replyTo = sender;

        context.vThreadFactory().newThread(() -> {
            Result result = blockingJdbcCall(query);        // parks; the carrier keeps working
            replyTo.tell(new Answer(result), self);         // fold the answer back in as a message
        }).start();
    }
}
```

`vThreadFactory()` is the only offload mechanism, and it is the one that carries the actor's
affinity to the work:

- under **`affine`**, the new virtual thread starts on this actor's home carrier,
  so it runs on the same core the actor does, unless an idle carrier steals it;
- under the **JDK builtin** scheduler, it is a plain virtual thread on the JDK's default scheduler.

The engine counts every thread created through this factory as in-flight work, so `awaitTermination`
will not report termination while one is still running. Two consequences: a thread that is created
but never `start()`ed pins that counter forever — a caller bug, which surfaces as
`awaitTermination` timing out — and the counting is a wrapper only, never a change to which carrier
the thread is bound to.

`ActorContext` is thread-safe and long-lived. Using it from a forked thread — `self().tell(...)` on
completion, as above — is exactly what it is for. What the forked thread must **not** do is touch the
actor's fields: take what it needs by value and hand the outcome back as a message, or the
"one message at a time" guarantee that makes those fields safe is gone.

### Platform threads started from an actor

On Linux, a new thread runs only on the CPUs of the thread that starts it, and under `affine` a
carrier runs on one CPU. A platform thread started from an actor would keep that one CPU. Under
`affine`, Looma gives every CPU back to the threads the JDK starts: its timers, and the
common pool behind `parallelStream` and `supplyAsync`. Your own threads and executors need a
factory that does the same. Take it from the system, and give the executor to the actors that
use it:

```java
ThreadFactory platform = system.scheduler().platformThreadFactory();
ExecutorService workers = Executors.newFixedThreadPool(4, platform);
```

An executor starts its threads at its first tasks, so this matters even for one created in
`main`, if an actor gives it its first tasks. Under the builtin scheduler, the factory is
`Thread.ofPlatform().factory()`. The
[`mpsc-scheduler` README](../mpsc-scheduler/README.md#threads-started-from-an-actor) lists what
the install covers.

### Pinning is the case that is genuinely bad

A **native frame** on the stack — a JNI call, or a foreign-function downcall — cannot unmount the
virtual thread at all, so it holds its **carrier** rather than merely its own mailbox thread. Under
the JDK builtin scheduler that costs one `ForkJoinPool` worker and the pool compensates by growing;
under `affine` the pool is fixed and there is nothing to grow, so every actor that calls that
carrier home stops until the pin is released. Keep native calls out of an actor's mailbox threads
and out of anything forked from them.

`synchronized` is **not** a pin on the JDKs this project targets: [JEP 491](https://openjdk.org/jeps/491)
(JDK 24) unmounts a virtual thread that blocks inside a monitor. `ReentrantLock` is still the better
default for its `tryLock`/timeout surface, but no longer for pinning reasons.

## 7. Supervision

When `onReceive` throws, a `SupervisionStrategy` decides one of three things:

| `Directive` | Effect |
| --- | --- |
| `RESUME` | Log, discard the message being processed, continue with the same instance and its state. **The default.** |
| `RESTART` | Run `preRestart` on the failing instance, obtain a fresh one from the `Supplier`, run `postRestart`, resume draining. The failed message is discarded, not retried. Children are left running. |
| `STOP` | Graceful cascading stop of the actor and its children, then unregister. |

Two places to put a strategy:

```java
// (a) on the actor — called AFRESH ON EVERY FAILURE
@Override
public SupervisionStrategy supervisorStrategy() {
    return cause -> cause instanceof IOException ? Directive.RESTART : Directive.STOP;
}

// (b) at spawn time — held for the life of the actor
ActorRef worker = context.spawn("worker", Worker::new,
        SpawnOptions.supervisedBy(new MaxRestarts(3)));
```

The difference matters and is a real trap. `supervisorStrategy()` is invoked again on every failure,
so a strategy that **counts** must not be allocated there:

```java
// BROKEN: a fresh counter on every failure, so the limit never trips
public SupervisionStrategy supervisorStrategy() {
    return new MaxRestarts(3);
}
```

Pass a counting strategy at spawn time instead, where it is held for the life of the actor — or keep
the counter in a field of the actor.

**Only `onReceive` is supervised.** No strategy is consulted for any lifecycle hook; each has the
fixed policy in the table in §2. A strategy that itself throws, and a `RESTART` whose `Supplier`
throws or returns `null`, are both treated as `STOP` and logged.

Supervision here is **self-supervision only**: nothing escalates to a parent. Unlike Akka, `RESTART`
does not stop the actor's children. So an actor that spawns its children in `preStart` cannot be
restarted: the default `postRestart` calls `preStart`, which spawns at paths the old children
still hold, and the restart turns into a `STOP`. If the actor must be restartable, spawn its
children on a message, or give it a strategy that never restarts.

## 8. Placement

Every actor gets a **home carrier** at spawn time, fixed for its lifetime. Defaults:

- `ActorSystem.spawn` → `Placement.roundRobin()`, the next carrier from a system-wide cursor;
- `ActorContext.spawn` → `Placement.inherit()`, the parent's own carrier.

Those defaults are what you want almost always: a subtree of actors and their offloaded work share
one core's locality, and parent↔child messages never cross one.

```java
// a specific carrier — e.g. an acceptor spreading connections deterministically
system.spawn("conn-" + n, ConnectionHandler::new,
        SpawnOptions.placedOn(Placement.carrier(Math.floorMod(n, system.carrierCount()))));

// co-locate a new actor with an existing one
context.spawn("helper", Helper::new,
        SpawnOptions.placedOn(Placement.carrier(context.homeCarrierIdOf(other))));

// opt a child OUT of its parent's carrier
context.spawn("independent", Worker::new,
        SpawnOptions.placedOn(Placement.roundRobin()));
```

`SpawnOptions` is immutable and complete the moment it is built — there is no terminal call to
forget. Every `placedOn` / `supervisedBy` / `withPlacement` / `withStrategy` returns a new instance:

```java
SpawnOptions options = SpawnOptions.placedOn(Placement.carrier(3))
                                   .withStrategy(cause -> Directive.RESTART);
```

"Unset" is a third state, expressed only by *not* calling the method — never by passing `null`,
which every setter rejects. An unset placement falls back to the surface's own default, an unset
strategy to the actor's `supervisorStrategy()`.

Rules and bounds:

- `Placement.carrier(id)` is bounds-checked at spawn against `carrierCount()`; a negative id is
  rejected by the record's own constructor.
- `Placement.inherit()` on `ActorSystem.spawn` throws `IllegalArgumentException` — a root has no
  parent.
- Under the JDK builtin scheduler `carrierCount()` is `1`, so every valid placement resolves to that
  one factory, and only `carrier(0)` is in bounds. Placement code therefore runs unchanged on every
  scheduler.

**Placing a child away from its parent is not free**: every parent↔child `tell` becomes a
cross-carrier hop instead of the same-core delivery the default gives for nothing. Reach for an
explicit placement when the actor's *own* workload wants a specific or spread-out assignment, not by
default.

### Placing by key

An application whose work belongs to a key (a customer, a session, a tenant) keeps everything that
serves one key on one carrier with `Placement.carrier` alone: the actor for key `k` on carrier
`k % carrierCount()`, shard `i` of each service on carrier `i % carrierCount()`, and each message
routed to shard `k % shards`.

```java
int carriers = system.carrierCount();
ActorRef[] pricers = new ActorRef[64];
for (int i = 0; i < pricers.length; i++) {
    int index = i;
    pricers[i] = system.spawn("pricing-" + i, () -> new Pricing(index),
            SpawnOptions.placedOn(Placement.carrier(i % carriers)));
}
system.spawn("session-" + customer, () -> new Session(customer, pricers),
        SpawnOptions.placedOn(Placement.carrier(customer % carriers)));

// in Session: the session and its pricer share a carrier
pricers[customer % pricers.length].tell(quote, context.self());
```

The pricer for `customer` is on carrier `(customer % 64) % carriers`, which is `customer % carriers`
only when `carriers` divides `64`. With any other shard count some keys cross carriers; the mapping
is the application's, and so is the choice of what to do about it.

### Pools: N actors of one type behind one reference

`SpawnOptions.pooled(n)` (or `.withPoolSize(n)`) makes one `spawn` create **n** actors from the same
factory and hand back a single `ActorRef` that spreads `tell` and `ask` over them round-robin. Both
spawning surfaces take it, and each keeps its own default placement — see [below](#placement-and-pools):

```java
// four actors, load-balanced behind one reference
ActorRef db = system.spawn("db", DbActor::new, SpawnOptions.pooled(4));

db.tell(new Query(id));                              // → one of /db-0 … /db-3
Row row = db.ask(new Query(id), Row.class).join();   // answered once, by the routee it went to
system.stop(db);                                     // stops all four

// the same from inside an actor, for a child pool
ActorRef io = context.spawn("io", BlockingIoActor::new, SpawnOptions.pooled(4));
```

**The point is blocking work.** Each actor in a pool has its own mailbox and its own mailbox
threads, so one of them sitting in a blocking call stalls *its* mailbox and nothing else — the other
`n-1` keep answering. That is the alternative to `vThreadFactory()` offloading in
[§6](#6-blocking-work): the actor is free to just block. Note that this needs no spread across
carriers to work — a virtual thread blocking on I/O unmounts from its carrier rather than holding
it, so what buys the responsiveness is the `n` mailboxes. Spreading matters when the routees
*compute*.

**Each actor of a pool is an ordinary actor.** It is registered at its own path (`/db-0` … `/db-3`),
it shows up in its parent's `children()`, it gets its own instance from the factory, it is supervised
on its own terms, and it is stopped by the ordinary cascade. There is no router actor in the middle:
the returned reference is a plain fan-out, so a message pays exactly the one hop it would to a lone
actor.

What that reference is *not*:

- **It is not an actor.** `path()` reports the pool's path (`/db`), but nothing is registered there:
  `findActor("/db")` is empty, and `findActor("/db-0")` is the live actor.
- **It gives no pool-wide ordering.** Two messages sent through it land in two mailboxes and are
  processed concurrently. Messages that must be ordered relative to each other, or that share mutable
  state, belong to **one** actor — a pool is for independent units of work.
- **It routes round-robin, and only round-robin.** No random, no smallest-mailbox; a stopped routee
  is skipped rather than fed its share, so a dead one never silently swallows `1/n` of the traffic.

#### Placement and pools

**The placement applies to each actor of the pool** — a pool is exactly the actors you would have got
by spawning them one at a time with these options. That one rule gives both shapes, because the
placements already differ: `inherit()` and `carrier(i)` name *one* carrier, so the pool lands there
whole, while `roundRobin()` means *the next* carrier, so applying it per actor spreads the pool one
per carrier. Nothing extra to pass, and no default is touched:

```java
// child: the 4 on the parent's carrier (inherit, the default there)
context.spawn("io", BlockingIoActor::new, SpawnOptions.pooled(4));

// child: the same 4, spread one per carrier
context.spawn("io", BlockingIoActor::new,
        SpawnOptions.pooled(4).withPlacement(Placement.roundRobin()));

// root: the 4 spread (roundRobin, the default there)
system.spawn("db", DbActor::new, SpawnOptions.pooled(4));

// root: the same 4, all on carrier 0
system.spawn("db", DbActor::new,
        SpawnOptions.pooled(4).withPlacement(Placement.carrier(0)));
```

A spread pool has no single home carrier, so **`homeCarrierIdOf(pool)` throws** — and it throws for a
co-located pool too, rather than making the contract depend on a placement the reference no longer
carries. Ask one of its actors, each an ordinary actor at its own path:

```java
int carrier = system.homeCarrierIdOf(system.findActor("/db-0").orElseThrow());
```

`poolSize` must be at least `1`, and a pool of `1` is exactly the single-actor spawn it always was —
same path, no `-0` suffix. A pool whose name collides partway through (`/db-2` already taken) leaves
nothing behind: the actors already created are started, then stopped, before the
`IllegalStateException` reaches the caller. Their `preStart` and `postStop` run.

### Asserting affinity

`homeCarrierIdOf(ref)` is available on both `ActorSystem` and `ActorContext`, and it is the **only**
sound basis for an affinity assertion:

```java
assertEquals(system.homeCarrierIdOf(parent), system.homeCarrierIdOf(child));
```

The home carrier is the only *deterministic* thing about placement. Under `affine` an
actor always runs there, but a thread it offloads may be stolen, and under the builtin anything may
run anywhere — asserting against the running carrier is a flaky test, not a bug in the
scheduler. It throws `IllegalArgumentException`
for any reference that is not one spawned actor: the single-use `sender` an `ask` produces, and a
[pool](#pools-n-actors-of-one-type-behind-one-reference), which stands for `n` of them — assert on
its actors, at `/db-0` and so on.

## 9. Stopping

```java
system.stop(ref);       // from any thread — main(), a test, a callback
context.stop(ref);      // from inside an actor
```

Both run the same graceful cascading stop: drain the target's mailbox, then stop and drain every
descendant innermost-first, then run `postStop` on the way back up, then unregister. Stopping an
already-stopped or unknown reference is a silent no-op.

`ref` may be **any** live actor, not only a descendant of the caller — there is one uniform rule and
no ownership check.

Stopping the last actor does **not** shut the system down. Only `shutdown()` does:

```java
system.shutdown();                                   // does not block
if (!system.awaitTermination(5, TimeUnit.SECONDS)) {
    // something is still in flight — an actor blocked, or a vThreadFactory thread never finished
}
```

`shutdown()` stops every root actor, each mailbox drains, the cascade descends, `postStop` unwinds
back up to the roots. Once it has begun, `ask` fails at once with `IllegalStateException`, and
`spawn` throws it. A `tell` is silently dropped once its actor is stopping: a root at once, a
descendant when the cascade reaches it, after its parent's mailbox has drained.

`awaitTermination` returns `true` once every actor is unregistered **and** every unit of in-flight
work has finished — the actors' mailbox threads *and* every thread created through
`vThreadFactory()`. That is what keeps a caller from observing termination while user-spawned
blocking work is still running.

Every scheduler drains identically. None owns a pool to stop: the JDK builtin owns nothing, and the
carriers of `affine` are a permanent JVM-wide pool created during
`VirtualThread.<clinit>` that outlives any single `ActorSystem`.

## 10. Timers

Three methods on `ActorRef` send a message later. They return a `ScheduledFuture<?>`.

```java
// once, after 50 ms
ScheduledFuture<?> once = ref.tell(new Tick(), 50, TimeUnit.MILLISECONDS);

// once, with a sender
ref.tell(new Tick(), context.self(), 50, TimeUnit.MILLISECONDS);

// first after 100 ms, then every 20 ms
ScheduledFuture<?> repeat =
        ref.tellAtFixedRate(new Tick(), context.self(), 100, 20, TimeUnit.MILLISECONDS);

repeat.cancel(false);                 // stops the sends
```

Looma has no scheduler service and no task to run: a timer only does a `tell`. For a task, or a
finer precision, see [Timers with a `ScheduledExecutorService`](#timers-with-a-scheduledexecutorservice).

### How a timer runs

Each carrier has one timer wheel. A wheel is a ring of slots, one slot per tick.

```
ref.tell(msg, 50 ms)                      ref's home carrier is carrier-3
  -> the caller is an actor on carrier-3 (MPSC scheduler), and the wheel of carrier-3 runs
       -> the timer goes straight into the wheel of carrier-3, no queue
  -> any other caller (main, a platform thread, another carrier, `builtin`),
     or the first timer of carrier-3
       -> the timer goes into the inbox of carrier-3's wheel     (an MPSC queue)
       -> the wheel's virtual thread moves it into the slot of its deadline
  -> at the deadline, that thread calls ref.tell(msg, sender)   (local to carrier-3)
  -> a one-shot future is done
```

The wheel's virtual thread starts at the first timer of its carrier. A carrier that never gets a
timer has no such thread. While its wheel holds a timer, even one far in the future, the thread
wakes at every tick. [`schedulers.md`](schedulers.md#the-wheel-thread) says more.

An actor's mailbox thread ([§2](#2-writing-an-actor)) checks the wheel of its carrier when it
starts:

```
the mailbox thread starts
  -> the wheel of its carrier runs ?    no  -> its timers go through the queues
                                              (a carrier without timers pays one volatile read)
                                        yes -> its timers for that wheel go straight into it
```

With `--enable-preview`, a `StructuredTaskScope` opened in `onReceive` must be closed before
`onReceive` returns, as the JDK requires. Once the wheel of the carrier runs, a scope left open
makes the JDK throw `StructureViolationException` when the actor's thread ends.

Under `builtin` the system has one wheel and no caller counts as local. Every timer takes the
inbox path. One switch decides: only a scheduler whose `mailboxThreadsStayOnCarrier()` returns
true gets the direct path. The builtin returns false.

The inbox is an MPSC queue made by `Builder.mpscQueueFactory`, the same factory as the mailboxes.
A bounded queue that is full cancels the timer: the future comes back cancelled, and a warning is
logged.

### The tick

```java
ActorSystem.builder().timerTick(Duration.ofMillis(5)).build();
```

The default is 10 ms. `timerTick` throws `NullPointerException` for `null` and
`IllegalArgumentException` for zero or negative.

```
precision   a timer fires on the first tick at or after its deadline
            -> never early
            -> about one tick late when the carrier is free; later when the carrier is busy,
               since the wheel thread waits its turn on the carrier
delay <= 0  -> fires at the next tick
period < tick  -> fires at most once per tick
```

A `null` message or unit throws `NullPointerException`. A period of zero or less throws
`IllegalArgumentException`.

A periodic timer keeps its rhythm: each deadline is one period after the previous deadline, not
after the previous send. If the wheel is late, the missed periods fire on the following ticks, one
per tick.

### Cancel and get

```
future.cancel(...)  -> true, and nothing is sent        if a one-shot message has not gone yet
                    -> false                            if a one-shot message has gone
                    -> idempotent
future.get()        -> one-shot: returns after the send
                    -> periodic: blocks until cancel, then throws CancellationException
future.getDelay(unit) -> time left to the next send
future.isDone()     -> true from the start of the send; get() still waits until the send has returned
```

A cancel that races the send of a periodic timer may return `true` and still let one message go.

Where the timer sits decides how fast it leaves the wheel:

```
future.cancel(...)
  -> the caller is an actor on the carrier of the wheel, and the wheel runs
       -> the timer leaves the wheel at once (constant time)
  -> any other caller
       -> the timer is marked cancelled; its message, its sender and its target are freed at once
       -> it goes into the wheel's cancelled queue
       -> the wheel removes it at the next tick
  -> the timer is still in the inbox
       -> it is dropped when the wheel drains the inbox
  -> the cancelled queue is full
       -> the timer stays in its slot until the slot comes up, with no message
```

`cancel` ignores its argument: it never interrupts anything.

### Stopping

```
shutdown()
  -> every pending timer is cancelled
  -> awaitTermination does not wait for timers
  -> the wheel threads end

tell(msg, delay) after shutdown()   -> the future comes back already cancelled; nothing is sent

one-shot, target stopped at the deadline   -> the message is dropped, as for any tell
periodic, target stopping at a tick        -> the timer cancels itself
```

### Pools and promises

```
pool.tell(msg, delay)                     a pool has no single carrier
  -> an actor whose carrier's wheel runs
       -> the timer goes straight into the wheel of its carrier, no queue
  -> any other caller (main, an offloaded thread, the first timer of a carrier)
       -> a wheel chosen from its thread id: the same thread, the same wheel
       -> the timer goes into the inbox of that wheel
  -> at the deadline, the pool picks a member, as for any tell
  -> the message may be sent from another carrier than the member's home

pool.tellAtFixedRate(...)  -> the timer cancels itself once all members are stopping
```

The sender of an `ask` (a `PromiseRef`) refuses timers: it throws `UnsupportedOperationException`.

### Timers with a `ScheduledExecutorService`

The delayed `tell` is the preferred way to send a message later
([Why it is built this way](#why-it-is-built-this-way)). You can also use your own `ScheduledExecutorService`. Build it from the platform thread factory of
the system, as any executor an actor uses
([Platform threads started from an actor](#platform-threads-started-from-an-actor)):

```java
ThreadFactory platform = system.scheduler().platformThreadFactory();
ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, platform);
executor.setRemoveOnCancelPolicy(true);

ScheduledFuture<?> guard = executor.schedule(() -> ref.tell(new Timeout()), 30, TimeUnit.SECONDS);
guard.cancel(false);
```

```
executor.schedule(() -> ref.tell(msg), delay, unit)
  -> a thread of the executor waits for the deadline
  -> at the deadline, it calls ref.tell(msg)
  -> the message is a hop: a cross-thread enqueue to the target's carrier, and maybe a wake-up

setRemoveOnCancelPolicy(true)    -> a cancelled task leaves the queue at once,
                                    not at its deadline
executor.shutdown()              -> yours to call; the shutdown of the system does not
```

Which one:

```
a guard, a timeout, or a periodic message to an actor  -> delayed tell
a precision finer than the tick                        -> executor
a Runnable to run, not just a tell                     -> executor
```

### Why it is built this way

- One wheel per carrier, run by a thread of that carrier, so the timer's `tell` needs no hand-over
  between threads. This is the same reason actors keep to their home carrier.
- A direct insert and a direct remove for an actor of the same carrier: once the wheel runs, a
  timeout that is armed and cancelled at once (the usual case) never touches a queue, and holds no
  message after the cancel.
- A tick, not an exact deadline: a wheel costs little per timer, and a tick of 10 ms suits timeouts
  and heartbeats.
- The delayed `tell` before an executor: its timer sits next to the actor it wakes, so arming,
  cancelling and sending need no hand-over between threads, and Looma owns the thread and the
  shutdown. An executor costs one hop per expiry and one more thing for you to stop. It is the tool
  for what the wheel does not do: a tick is not an exact deadline.

## 11. What Looma does not do

Stated plainly, so nothing is inferred from silence:

- **No parent supervision, no escalation, no death watch.** Self-supervision only; there is no
  `Terminated` message.
- **No remoting, no clustering, no persistence.** A system is one JVM.
- **No typed actors.** `onReceive` takes `Message` and dispatches with `switch`; an unrecognised
  message is the actor's own business.
- **No stashing, no `become`/state machines.** Keep the state in a field.
- **No mailbox bounds or priorities.** One `ConcurrentLinkedQueue` per actor by default;
  `Builder.mpscQueueFactory` replaces it with anything MPSC and non-blocking.
- **No scheduler service.** Timers only `tell` ([§10](#10-timers)); there is no task to run
  later. For one, use your own executor
  ([Timers with a `ScheduledExecutorService`](#timers-with-a-scheduledexecutorservice)).
- **No dead-letter queue.** A `tell` to a stopped actor is dropped, not diverted.

## See also

- [`schedulers.md`](schedulers.md) — the two schedulers, choosing one, work stealing, and the
  engine internals, documented once.
- The javadoc on every type above; it is the normative reference for the exact exception contracts.
