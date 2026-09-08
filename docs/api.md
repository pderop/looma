# The Looma API

Everything an application touches lives in one package, `io.github.pderop.looma`. Nine types, no
annotations, no configuration file, no scheduler ever named in code.

```java
import io.github.pderop.looma.*;
```

| Type | What it is |
| --- | --- |
| `ActorSystem` | The root of a hierarchy. Spawns root actors, resolves paths, shuts everything down. |
| `Actor` | The POJO you write: `onReceive` plus four lifecycle hooks. |
| `ActorRef` | An opaque, thread-safe handle: `tell` and `ask`. |
| `ActorContext` | An actor's view of itself, its children, and the system. |
| `Message` | The marker interface every payload implements. |
| `SupervisionStrategy` / `Directive` | What happens when `onReceive` throws. |
| `Placement` / `SpawnOptions` | Optional home-carrier placement, supervision, and pool size at spawn time. |

The scheduler SPI (`io.github.pderop.looma.spi`) is for people *implementing* a scheduler, not for
people using actors — see [`carrier.md`](carrier.md) and [`jdk.md`](jdk.md).

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
        socket = open();               // runs on this actor's own loop, before the first message
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
actor's home carrier, so its pages are **first-touched by the core that will use them**. Under an
affine scheduler that is the difference between state that starts hot and state that has to be
migrated once.

## 3. Starting a system, spawning actors

```java
ActorSystem system = ActorSystem.builder().build();

ActorRef account = system.spawn("account", Account::new);
```

`ActorSystem.builder()` is the only entry point; no caller ever names an implementation type. The
builder has exactly two knobs, and applications normally use neither:

```java
ActorSystem system = ActorSystem.builder()
        .scheduler(someScheduler)                  // bypasses discovery — tests and benchmark controls
        .mailboxQueueFactory(myQueueFactory)       // per-actor envelope queue; default ConcurrentLinkedQueue
        .build();
```

The `factory` passed to `spawn` is a `Supplier<Actor>`, not an instance, because the engine calls it
**again** to obtain a fresh instance after a `RESTART`.

Names must be non-blank, contain no `/` (which would forge a path), and be unique among siblings.

One `spawn` can also create **N actors of the same type** behind a single load-balancing reference —
see [Pools](#pools-n-actors-of-one-type-behind-one-reference).

## 4. The hierarchy

A child is spawned from its parent's context — normally in `preStart`:

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

CompletableFuture<Balance> reply = account.ask(new AccountMessage.GetBalance(), Balance.class);
```

`ask` creates a single-use, unregistered reply channel and passes it as the message's `sender`. The
receiving actor replies exactly as it would to anyone else:

```java
case AccountMessage.GetBalance ignored -> sender.tell(new Balance(balance), context.self());
```

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
| system shutting down | silently dropped | fails synchronously with `IllegalStateException` |
| target accepted the request, then stopped without replying | — | future stays open — bound it with `orTimeout` |
| no reply | — | future stays open — bound it with `orTimeout` |

`ask` is stricter than `tell` on a request known to be unanswerable at call time: it fails
immediately rather than being queued to a mailbox that will never deliver a reply. An accepted
`ask` that never gets a reply is the caller's to time out; Looma does not add a timer of its own.

An `ActorRef` is safe to share across threads, store in a message, or hold past the lifetime of the
actor it points to. Identity is plain reference identity — two lookups of the same live actor return
the same instance.

## 6. Blocking work

An actor runs on a virtual thread, so blocking inside `onReceive` **parks** it: the carrier is handed
back, no OS thread is consumed, and the rest of the machine keeps working. Blocking is not a
correctness problem here, and it is not a scheduler problem.

What it costs is narrower: **an actor's loop is the only consumer of its own mailbox**, so for as long
as `onReceive` sits inside a blocking call, that actor answers nothing. Neighbours have loops of
their own and keep running; this actor's messages queue up behind a thread that is only waiting.

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

- under the **carrier-affine** scheduler, the new virtual thread is pinned to this actor's home
  carrier, so it reads and writes on the same core the actor does;
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

### Pinning is the case that is genuinely bad

A **native frame** on the stack — a JNI call, or a foreign-function downcall — cannot unmount the
virtual thread at all, so it holds its **carrier** rather than merely its own loop. Under the JDK
builtin scheduler that costs one `ForkJoinPool` worker and the pool compensates by growing; under the
carrier-affine scheduler the carrier is pinned to a CPU and there is nothing to grow, so every actor
that calls it home stops until the pin is released. Keep native calls off the actor loop and off
anything forked from it.

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
does not stop the actor's children — which means an actor that spawns children in `preStart` and is
then restarted will fail to re-spawn them at the same paths. Spawn children on a message, or on a
strategy of `STOP`, if that pattern matters to you.

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
  one factory, and only `carrier(0)` is in bounds. Placement code therefore runs unchanged on both
  schedulers.

**Placing a child away from its parent is not free**: every parent↔child `tell` becomes a
cross-carrier hop instead of the same-core delivery the default gives for nothing. Reach for an
explicit placement when the actor's *own* workload wants a specific or spread-out assignment, not by
default.

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

**The point is blocking work.** Each actor in a pool has its own mailbox and its own loop virtual
thread, so one of them sitting in a blocking call stalls *its* mailbox and nothing else — the other
`n-1` keep answering. That is the alternative to `vThreadFactory()` offloading in
[§6](#6-blocking-work): the actor is free to just block. Note that this needs no spread across
carriers to work — a virtual thread blocking on I/O unmounts from its carrier rather than holding it,
so what buys the responsiveness is the `n` mailboxes. Spreading matters when the routees *compute*.

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
nothing behind: the actors already created are stopped before the `IllegalStateException` reaches the
caller.

### Asserting affinity

`homeCarrierIdOf(ref)` is available on both `ActorSystem` and `ActorContext`, and it is the **only**
sound basis for an affinity assertion:

```java
assertEquals(system.homeCarrierIdOf(parent), system.homeCarrierIdOf(child));
```

The home carrier is the only *deterministic* thing about placement. Which carrier a continuation
happens to *run* on may, under work stealing, legitimately be a sibling — asserting against the
running carrier is a flaky test, not a bug in the scheduler. It throws `IllegalArgumentException`
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
back up to the roots. Once it has begun, `tell` is silently dropped, and `ask` and `spawn` throw
`IllegalStateException`.

`awaitTermination` returns `true` once every actor is unregistered **and** every unit of in-flight
work has finished — actor loops *and* every thread created through `vThreadFactory()`. That is what
keeps a caller from observing termination while user-spawned blocking work is still running.

Both schedulers drain identically. Neither owns a pool to stop: the JDK builtin owns nothing, and the
affine scheduler's carriers are a permanent JVM-wide pool created during `VirtualThread.<clinit>`
that outlives any single `ActorSystem`.

## 10. What Looma does not do

Stated plainly, so nothing is inferred from silence:

- **No parent supervision, no escalation, no death watch.** Self-supervision only; there is no
  `Terminated` message.
- **No remoting, no clustering, no persistence.** A system is one JVM.
- **No typed actors.** `onReceive` takes `Message` and dispatches with `switch`; an unrecognised
  message is the actor's own business.
- **No stashing, no `become`/state machines.** Keep the state in a field.
- **No mailbox bounds or priorities.** One `ConcurrentLinkedQueue` per actor by default;
  `Builder.mailboxQueueFactory` replaces it with anything MPSC and non-blocking.
- **No scheduler/timer service.** Use a `ScheduledExecutorService` and `tell` yourself a message.
- **No dead-letter queue.** A `tell` to a stopped actor is dropped, not diverted.

## See also

- [`carrier.md`](carrier.md) — the carrier-affine scheduler and its system properties.
- [`jdk.md`](jdk.md) — the JDK builtin scheduler and the engine internals, documented once.
- The javadoc on every type above; it is the normative reference for the exact exception contracts.
