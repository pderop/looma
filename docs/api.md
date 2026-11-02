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
| `Placement` / `SpawnOptions` | At spawn: the actor's home carrier, its supervision, a pool size. |
| `Carriers` | Which scheduler carries the process, and how it is sized, when chosen in code. |

The scheduler SPI (`io.github.pderop.looma.spi`) is for people implementing a scheduler — see
[`schedulers.md`](schedulers.md). An application uses one part of it: `system.scheduler()`, for
`platformThreadFactory()` ([§6](#platform-threads-started-from-an-actor)).

---

## 1. Messages

A message is any type implementing the `Message` marker. Records fit. Group one actor's messages
under a sealed interface: `switch` is then exhaustive.

```java
public sealed interface AccountMessage extends Message {
    record Deposit(long cents) implements AccountMessage {}
    record Withdraw(long cents) implements AccountMessage {}
    record GetBalance() implements AccountMessage {}      // the request
}

public record Balance(long cents) implements Message {}   // the reply — see ask() below
```

A message carries no routing: where it goes is the `ActorRef` it is sent through. Messages cross
carriers, so cores: **treat them as immutable.** Nothing enforces it.

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

- `onReceive` is **never invoked concurrently for the same instance**: fields need no
  synchronization. The four lifecycle hooks run under the same guarantee.
- `sender` is the reference given to `tell(message, sender)`, `null` for `tell(message)`. For an
  `ask`, it stands for the asker, and **replying to it is the only thing that completes the
  future**.

### Lifecycle hooks

```java
public class Connection implements Actor {

    private Socket socket;

    @Override
    public void preStart(ActorContext context) throws Exception {
        socket = open();               // before the first message
    }

    @Override
    public void postStop(ActorContext context) throws Exception {
        socket.close();                // after the mailbox drained and every child fully stopped
    }
}
```

| Hook | When | If it throws |
| --- | --- | --- |
| `preStart(context)` | once, before the first message — even if none is ever sent | the actor is stopped immediately; no strategy is consulted |
| `postStop(context)` | once, after the mailbox is drained and every child has fully stopped | logged; termination completes anyway |
| `preRestart(cause, message, context)` | on the failing instance, before it is discarded. Defaults to `postStop` | the restart becomes a `STOP`, logged |
| `postRestart(cause, context)` | on the fresh instance, before it resumes. Defaults to `preStart` | the restart becomes a `STOP`, logged |

`preStart` is the first mailbox entry: it completes before any message, but not necessarily before
`spawn` returns. Allocate per-actor state in `preStart`, not in the constructor
([why](#12-why-it-is-built-this-way)).

## 3. Starting a system, spawning actors

```java
ActorSystem system = ActorSystem.builder().build();

ActorRef account = system.spawn("account", Account::new);
```

`ActorSystem.builder()` is the only entry point. It has three settings, all optional, plus
`scheduler(...)` for tests:

```java
ActorSystem system = ActorSystem.builder()
        .carriers(Carriers.affine(8))        // chooses and sizes the JVM's scheduler, in code
        .mpscQueueFactory(myQueueFactory)    // every MPSC queue: mailboxes and timer queues; default ConcurrentLinkedQueue
        .timerTick(Duration.ofMillis(10))    // precision of delayed and periodic tell; default 10 ms
        .build();

ActorSystem.builder().scheduler(someScheduler);    // bypasses everything — tests only
```

`carriers(...)` is `Carriers.affine(n)` (an actor never leaves its home carrier) or
`Carriers.builtin(n)` (the JDK's default scheduler). Without it, the system takes whatever the JVM
flags chose. `build()` must run **before the JVM's first virtual thread**, and throws rather than
run on the wrong scheduler: [`schedulers.md`](schedulers.md#in-code).

- `spawn` takes a `Supplier<Actor>`, not an instance: a `RESTART` calls it again for a fresh one.
- Names must be non-blank, contain no `/`, and be unique among siblings.
- One `spawn` can create **N actors of the same type** behind one reference:
  [Pools](#pools-n-actors-of-one-type-behind-one-reference).

## 4. `tell` and `ask`

```java
account.tell(new AccountMessage.Deposit(500), context.self());   // fire and forget, with a sender to reply to
account.tell(new AccountMessage.Deposit(500));                   // no sender — from main(), a test, a callback

CompletableFuture<Balance> reply = account.ask(new AccountMessage.GetBalance(), Balance.class);
```

`ask` passes a single-use reply channel as the message's `sender`. The actor replies as it would to
anyone:

```java
case AccountMessage.GetBalance ignored -> sender.tell(new Balance(balance), context.self());
```

- The reply completes the future on the mailbox thread of the actor that replies. A stage chained
  with `thenApply` runs there too, and that actor handles nothing else meanwhile: chain slow work
  with `thenApplyAsync`.
- The future completes **at most once**: a second reply is dropped.
- `ask` has no timeout of its own. Bound it with `orTimeout`:

```java
account.ask(new AccountMessage.GetBalance(), Balance.class)
        .orTimeout(1, TimeUnit.SECONDS)
        .join();
```

### The reply type is declared, and checked

`ask(message, Balance.class)` returns a `CompletableFuture<Balance>`: no cast. A reply of another
type fails the future with a `ClassCastException` naming both types, when it arrives. That wrong
reply **settles** the future: a correct reply sent after it is dropped.

When a reply has several shapes, give them a sealed supertype and `switch` over the result:

```java
sealed interface LookupResult extends Message permits Found, NotFound {}

LookupResult result = db.ask(new Lookup(key), LookupResult.class).join();

String value = switch (result) {              // exhaustive, no default needed
    case Found found -> found.value();
    case NotFound ignored -> "";
};
```

`ask(message)` is `ask(message, Message.class)`: it accepts any reply.

| Situation | `tell` | `ask` |
| --- | --- | --- |
| target already stopped, or unknown | silently dropped | the returned future is already failed with `IllegalStateException` |
| system shutting down | dropped once that actor is stopping: a root at once, a descendant when the cascade reaches it | the returned future is already failed with `IllegalStateException` |
| target accepted the request, then stopped without replying | — | future stays open — bound it with `orTimeout` |
| no reply | — | future stays open — bound it with `orTimeout` |

An `ActorRef` is safe to share across threads, store in a message, or hold past the life of its
actor. Two lookups of the same live actor return the same instance.

## 5. Timers

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

A timer only does a `tell`: there is no task to run later.

### How a timer runs

Looma uses a timer wheel, one per carrier: less precise than a `ScheduledThreadPoolExecutor` (to
one tick, 10 ms by default), but much cheaper when actors arm and cancel many timers.

```
ref.tell(msg, 50 ms)                      ref's home carrier is carrier-3
  -> the timer goes to the wheel of carrier-3
  -> at the deadline, the wheel's virtual thread calls ref.tell(msg, sender)   (local to carrier-3)
  -> for a one-shot timer, the returned future then completes: the message is in the mailbox
```

- The wheel's virtual thread starts at the first timer of its carrier. While the wheel holds a
  timer, even one far in the future, the thread wakes at every tick.
- Under `affine`, once a timer has run on its carrier, an actor arms and cancels the timers of its
  own carrier with no queue; any other caller goes through a queue of the wheel:
  [`schedulers.md`](schedulers.md#the-wheel-thread).
- The queues of the wheel have no limit, like the mailboxes: an arm is never refused. A queue set
  with `Builder.mpscQueueFactory` must have no limit either.

### The tick

```java
ActorSystem.builder().timerTick(Duration.ofMillis(5)).build();
```

The default is 10 ms, the minimum 1 ms. `timerTick` throws `NullPointerException` for `null` and
`IllegalArgumentException` below 1 ms.

```
precision      a timer fires on the first tick at or after its deadline
               -> never early
               -> about one tick late when the carrier is free; later when the carrier is busy,
                  since the wheel thread waits its turn on the carrier
delay <= 0     -> fires at the next tick
period         -> each deadline is one period after the previous deadline, not after the send
falling behind -> after a pause, or with a period shorter than the tick: the missed periods
                  fire on the following ticks, one per tick
```

A `null` message or unit throws `NullPointerException`; a period of zero or less,
`IllegalArgumentException`.

### Cancel and get

```
future.cancel(...)    -> true, and nothing is sent   if this call cancels a one-shot not yet gone
                      -> false                       if the message has gone, or the timer was
                                                     already cancelled (by you, or by the system)
future.get()          -> one-shot: returns after the send
                      -> periodic: blocks until cancel, then throws CancellationException
future.getDelay(unit) -> time left to the next send
future.isDone()       -> true from the start of the send; get() still waits until the send has returned
```

- A cancel that races the send of a periodic timer may return `true` and still let one message go.
- A cancel frees the message at once.
- `cancel` ignores its argument: it never interrupts anything.

### Stopping

```
shutdown()
  -> the wheel threads cancel every pending timer, soon after, then end
  -> awaitTermination does not wait for them: a future may still be pending when it returns

tell(msg, delay) after shutdown()   -> the future comes back already cancelled; nothing is sent

one-shot, target stopped at the deadline   -> the message is dropped, as for any tell
periodic, target stopping                  -> the timer cancels itself, at the latest at its next
                                              send or when the wheel passes its slot
```

### Timers on a pool, or on an ask's sender

```
pool.tell(msg, delay)                     a pool has no single carrier
  -> from an actor: the wheel of the actor's carrier
  -> from any other thread: a wheel chosen from its thread id (the same thread, the same wheel)
  -> at the deadline, the pool picks a member, as for any tell

pool.tellAtFixedRate(...)  -> the timer cancels itself once all members are stopping
```

The sender of an `ask` refuses timers: it throws `UnsupportedOperationException`.

### Timers with a `ScheduledExecutorService`

The delayed `tell` is the preferred way to send a message later. For a task, or a finer precision,
use your own `ScheduledExecutorService`, built from the
[platform thread factory](#platform-threads-started-from-an-actor) of the system:

```java
ThreadFactory platform = system.scheduler().platformThreadFactory();
ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, platform);
executor.setRemoveOnCancelPolicy(true);    // a cancelled task leaves the queue at once

ScheduledFuture<?> guard = executor.schedule(() -> ref.tell(new Timeout()), 30, TimeUnit.SECONDS);
guard.cancel(false);
```

At the deadline a thread of the executor calls `ref.tell`: one hop to the target's carrier. Its
`shutdown()` is yours to call.

```
a guard, a timeout, or a periodic message to an actor  -> delayed tell
a precision finer than the tick                        -> executor
a Runnable to run, not just a tell                     -> executor
```

## 6. Blocking work

Blocking inside `onReceive` **parks** the mailbox thread: the carrier is handed back, and the
machine keeps working. But the mailbox thread is the only consumer of the mailbox, so the actor
answers nothing until the call returns. Three choices:

```
nothing else to do meanwhile    -> block in onReceive
the work must stay responsive   -> block behind a pool: one blocked member stalls only its own mailbox
the actor must stay responsive  -> fork through context.vThreadFactory(), fold the answer back as a message
```

```java
@Override
public void onReceive(Message message, ActorRef sender, ActorContext context) {
    if (message instanceof Query query) {
        // Capture what the forked thread needs BEFORE starting it: `sender` is a parameter of this
        // call only, and the actor's fields must not be read concurrently with the next message.
        ActorRef self = context.self();
        ActorRef replyTo = sender;

        context.vThreadFactory().newThread(() -> {
            Result result = blockingJdbcCall(query);        // parks; the carrier keeps working
            replyTo.tell(new Answer(result), self);         // fold the answer back in as a message
        }).start();
    }
}
```

`vThreadFactory()` is the only way to offload:

```
under affine     -> the thread starts on the actor's home carrier; an idle carrier may steal it
under builtin    -> a plain virtual thread
every thread     -> counted as in-flight work: awaitTermination waits for it
created, never started -> the count never drops: awaitTermination times out (a caller bug)
```

`ActorContext` is thread-safe: a forked thread may use it, as above. It must **not** touch the
actor's fields: take what it needs by value, and send the outcome back as a message.

### Platform threads started from an actor

Under `affine` on Linux, a carrier runs on one CPU, and a thread started from it would keep that
CPU. Looma covers the threads the JDK starts. For your own threads and executors, take the factory
from the system:

```java
ThreadFactory platform = system.scheduler().platformThreadFactory();
ExecutorService workers = Executors.newFixedThreadPool(4, platform);
```

An executor starts its threads at its first tasks, so this matters even for one created in `main`.
Under the builtin, the factory is `Thread.ofPlatform().factory()`. What the install covers:
[`mpsc-scheduler/README.md`](../mpsc-scheduler/README.md#threads-started-from-an-actor).

### Native calls hold the carrier

A **native frame** on the stack — a JNI call, or a foreign-function downcall — cannot unmount the
virtual thread, so it holds its carrier:

```
builtin -> that ForkJoinPool worker is lost until the call returns; the pool grows to compensate
affine  -> the pool is fixed: every actor homed on that carrier waits
```

Keep native calls out of mailbox threads and out of anything forked from them. `synchronized` no
longer pins ([JEP 491](https://openjdk.org/jeps/491), JDK 24).

### StructuredTaskScope

Under `affine`, a `StructuredTaskScope` opened in `onReceive` must be closed before `onReceive`
returns, as the JDK requires. Once a timer has run on the carrier, a scope left open makes the JDK
throw `StructureViolationException` when the mailbox thread ends.

## 7. The hierarchy

A child is spawned from its parent's context:

```java
public class Db implements Actor {

    private ActorRef worker;

    @Override
    public void onReceive(Message message, ActorRef sender, ActorContext context) {
        if (worker == null) {
            worker = context.spawn("worker", Worker::new);   // path: /db/worker
        }
        worker.tell(message, context.self());            // same carrier under affine: no cross-core hop
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
- **`findActor` always resolves from the root.** From `/db/worker`, `context.findActor("sibling")`
  resolves `/sibling`, *not* `/db/sibling`. A path with no `/` is a root name.

```java
Optional<ActorRef> ref = system.findActor("/db/worker");
Optional<ActorRef> db  = system.findActor("db");        // same as "/db"
```

## 8. Supervision

When `onReceive` throws, a `SupervisionStrategy` decides one of three things:

| `Directive` | Effect |
| --- | --- |
| `RESUME` | Log, discard the message being processed, continue with the same instance and its state. **The default.** |
| `RESTART` | Run `preRestart` on the failing instance, obtain a fresh one from the `Supplier`, run `postRestart`, resume. The failed message is discarded, not retried. Children are left running. |
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

A strategy that **counts** must not be allocated in `supervisorStrategy()`:

```java
// BROKEN: a fresh counter on every failure, so the limit never trips
public SupervisionStrategy supervisorStrategy() {
    return new MaxRestarts(3);
}
```

Pass it at spawn time, or keep the counter in a field of the actor.

- **Only `onReceive` is supervised.** Each lifecycle hook has the fixed policy of the table in §2.
  A strategy that throws, and a `RESTART` whose `Supplier` throws or returns `null`, are a `STOP`,
  logged.
- **Self-supervision only**: nothing escalates to a parent, and `RESTART` keeps the children. So an
  actor that spawns its children in `preStart` cannot be restarted: the default `postRestart` calls
  `preStart`, which spawns at paths the old children still hold, and the restart becomes a `STOP`.
  Spawn them on a message, or give the actor a strategy that never restarts.

## 9. Placement

Every actor gets a **home carrier** at spawn, fixed for its life. Under `affine`, its mailbox
threads run only there. Defaults:

- `ActorSystem.spawn` → `Placement.roundRobin()`, the next carrier from a system-wide cursor;
- `ActorContext.spawn` → `Placement.inherit()`, the parent's carrier: messages between them stay
  on one core.

```java
// a specific carrier — e.g. an acceptor spreading connections deterministically
system.spawn("conn-" + n, ConnectionHandler::new,
        SpawnOptions.placedOn(Placement.carrier(Math.floorMod(n, system.carrierCount()))));

// co-locate a new actor with an existing one
context.spawn("helper", Helper::new,
        SpawnOptions.placedOn(Placement.carrier(context.homeCarrierIdOf(other))));

// opt a child OUT of its parent's carrier: every parent↔child tell then crosses carriers
context.spawn("independent", Worker::new,
        SpawnOptions.placedOn(Placement.roundRobin()));
```

`SpawnOptions` is immutable: every `placedOn` / `supervisedBy` / `withPlacement` / `withStrategy`
returns a new instance. "Unset" means not calling the method; `null` is rejected.

```java
SpawnOptions options = SpawnOptions.placedOn(Placement.carrier(3))
                                   .withStrategy(cause -> Directive.RESTART);
```

- `Placement.carrier(id)` is bounds-checked at spawn against `carrierCount()`.
- `Placement.inherit()` on `ActorSystem.spawn` throws `IllegalArgumentException`: a root has no
  parent.
- Under the builtin, `carrierCount()` is `1`: every actor's home is 0, and the same placement code
  runs unchanged.

### Placing by key

To keep everything that serves one key (a customer, a session) on one carrier: the actor for key
`k` on carrier `k % carrierCount()`, shard `i` of each service on carrier `i % carrierCount()`.

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

The pricer of `customer` is on carrier `customer % carriers` only when `carriers` divides `64`.

### Pools: N actors of one type behind one reference

`SpawnOptions.pooled(n)` (or `.withPoolSize(n)`) makes one `spawn` create **n** actors from the same
factory, behind one `ActorRef` that spreads `tell` and `ask` over them round-robin:

```java
ActorRef db = system.spawn("db", DbActor::new, SpawnOptions.pooled(4));

db.tell(new Query(id));                              // → one of /db-0 … /db-3
Row row = db.ask(new Query(id), Row.class).join();   // answered once, by the actor it went to
system.stop(db);                                     // stops all four

ActorRef io = context.spawn("io", BlockingIoActor::new, SpawnOptions.pooled(4));   // a child pool
```

- **The point is blocking work** ([§6](#6-blocking-work)): one member in a blocking call stalls only
  its own mailbox.
- **Each member is an ordinary actor**, at its own path (`/db-0` … `/db-3`), supervised and stopped
  on its own. There is no router actor: a message pays the one hop it would to a lone actor.
- **The reference is not an actor.** `path()` is `/db`, but nothing is registered there:
  `findActor("/db")` is empty.
- **No pool-wide ordering.** Two messages may run at once on two members.
- **Round-robin only.** A stopped member is skipped.
- `poolSize` is at least `1`; a pool of `1` is a plain spawn, with no `-0` suffix. A pool whose
  name collides partway through leaves nothing behind: the members already made are started, then
  stopped, before the `IllegalStateException`.

#### Placement and pools

The placement applies to **each member**:

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

### Asserting affinity

`homeCarrierIdOf(ref)`, on `ActorSystem` and `ActorContext`, is the only sound basis for an
affinity assertion:

```java
assertEquals(system.homeCarrierIdOf(parent), system.homeCarrierIdOf(child));
```

It throws `IllegalArgumentException` for anything that is not one spawned actor: an `ask`'s sender,
and a pool. Ask one of its members:

```java
int carrier = system.homeCarrierIdOf(system.findActor("/db-0").orElseThrow());
```

## 10. Stopping

```java
system.stop(ref);       // from any thread — main(), a test, a callback
context.stop(ref);      // from inside an actor
```

Both run the same graceful cascading stop:

```
stop(ref)
  -> drain the target's mailbox
  -> stop and drain every descendant, innermost first
  -> postStop on the way back up
  -> unregister
```

`ref` may be any live actor; stopping a stopped or unknown one does nothing. Stopping the last
actor does **not** shut the system down. Only `shutdown()` does:

```java
system.shutdown();                                   // does not block
if (!system.awaitTermination(5, TimeUnit.SECONDS)) {
    // something is still in flight — an actor blocked, or a vThreadFactory thread never finished
}
```

- `shutdown()` stops every root, and the cascade runs as above. From then on `spawn` throws
  `IllegalStateException`; `tell` and `ask` follow the table of [§4](#4-tell-and-ask).
- `awaitTermination` returns `true` once every actor is unregistered **and** every mailbox thread
  and `vThreadFactory()` thread has ended.
- The carriers are JVM-wide: the JDK creates them at the first virtual thread, and they outlive any
  system.

## 11. What Looma does not do

- **No parent supervision, no escalation, no death watch.** Self-supervision only; there is no
  `Terminated` message.
- **No remoting, no clustering, no persistence.** A system is one JVM.
- **No typed actors.** `onReceive` takes `Message` and dispatches with `switch`.
- **No stashing, no `become`/state machines.** Keep the state in a field.
- **No mailbox bounds or priorities.** One `ConcurrentLinkedQueue` per actor by default;
  `Builder.mpscQueueFactory` replaces it with anything MPSC, unbounded and non-blocking.
- **No scheduler service.** Timers only `tell` ([§5](#5-timers)). For a task, use your own
  executor.
- **No dead-letter queue.** A `tell` to a stopped actor is dropped, not diverted.

## 12. Why it is built this way

- **`Message` is a marker, not sealed.** It only keeps an arbitrary `Object` out of `tell`. The
  engine knows no protocol, and each actor has its own: one actor's sealed family is the place for
  exhaustiveness. Replies outside that family keep the inbound `switch` to what the actor handles.
- **State in `preStart`.** `preStart` runs on the actor's home carrier: with pinned carriers, the
  state is first touched by the core that will use it, so it starts hot.
- **A `Supplier`, not an instance**, so that a restart gets a fresh actor.
- **The reply type is a `Class<R>`, not inferred.** An inferred `<R>` would cast unchecked: a wrong
  reply would fail somewhere else, or be stored and fail much later. The `Class` lets `ask` check
  the reply when it arrives. A wrong first reply settles the future, so that a buggy actor does not
  look healthy because its second reply was right.
- **`ask` fails at once on an unanswerable request** instead of queuing it to a mailbox that will
  never reply.
- **Placement defaults keep a subtree on one core.** Placing a child elsewhere turns every
  parent↔child `tell` into a cross-carrier hop: do it only when the actor's own load wants it.
- **A pool needs no spread for blocking work:** a blocked virtual thread unmounts, so the `n`
  mailboxes are what buy the responsiveness. Spreading matters when the members compute.
- **`homeCarrierIdOf`, not the running carrier, in tests:** under `affine` a thread an actor
  offloads may be stolen, and under the builtin anything may run anywhere.
- **One timer wheel per carrier, run by a thread of that carrier**: the timer's `tell` needs no
  hand-over between threads, for the same reason actors keep to their home carrier. After the
  first timer of the carrier, a timeout armed and cancelled at once never touches a queue, and
  holds no message after the cancel.
- **A tick, not an exact deadline:** a wheel costs little per timer, and 10 ms suits timeouts and
  heartbeats. Looma owns the wheel thread and stops it; an executor is one more thing to stop.

## See also

- [`schedulers.md`](schedulers.md) — the two schedulers, choosing one, and how a `tell` runs.
- The javadoc of every type above: the reference for the exact exception contracts.
