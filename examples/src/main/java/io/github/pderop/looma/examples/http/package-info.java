/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * A mini HTTP/1.1 server that turns the locality premise into a number an
 * external load generator can produce.
 *
 * <p>
 * This is a whole-system measurement rather than a microbenchmark: real
 * sockets, a real request lifecycle, a third-party client ({@code wrk} /
 * {@code wrk2}, HTTP/1.1, one request in flight per connection), and a workload
 * {@code perf c2c} can be pointed at.
 *
 * <p>
 * <b>Why this package holds no scheduler-specific code.</b> Half the point is
 * running the identical server on the JDK builtin scheduler as the control, so
 * the example is scheduler-neutral: it never names a scheduler, and which one
 * it gets comes from the JVM flags and the classpath alone. It uses nothing but
 * the public actor contract — {@code spawn}, {@code tell},
 * {@code vThreadFactory} — with one read-only exception:
 * {@link io.github.pderop.looma.examples.http.HttpBenchMain} probes
 * {@link io.github.pderop.looma.spi.ActorScheduler} to print the carrier-to-CPU
 * map, because a silently-unpinned run is otherwise indistinguishable from a
 * pinned one.
 *
 * <h2>The mechanism</h2>
 *
 * One actor per connection, spawned as a root actor with an explicit
 * {@code Placement.carrier(...)} — connection <i>n</i> on carrier
 * {@code n % carriers} — rather than the round-robin default, whose cursor is
 * shared with every other {@code ActorSystem.spawn} in the process, so the
 * connections spread evenly and reproducibly over the carriers and each one
 * then stays on the carrier it was given. Each connection actor owns <b>a
 * private byte array and nothing else</b> — no state is shared between actors
 * anywhere in this package, which is why nothing in it needs a lock.
 *
 * <p>
 * A request is: the actor walks its own array along a pointer chase, modifying
 * every line, copies it into the connection's snapshot buffer, hands that to a
 * fresh virtual thread from the connection's own factory, and that thread
 * follows the same chase through the snapshot and blocks on a real HTTP call to
 * {@link io.github.pderop.looma.examples.http.MockUpstream}, decoding the JSON
 * body and re-encoding it. The answer comes back to the actor as a message, so
 * the actor modifies its array again and forks one last virtual thread to write
 * the response. What the forked thread read out of the snapshot comes back with
 * the answer and is folded into the next modification, so no part of the chain
 * is dead code. The snapshot buffer is allocated once per connection and
 * overwritten by each request: a fresh array per request would put gigabytes
 * per second of garbage, and the collections that follow, into the measured
 * path.
 *
 * <p>
 * That decode/re-encode is the same handler body as the upstream project's
 * {@code HandoffHttpServer} — it is what gives a request a CPU cost at all, and
 * what makes the response carry backend data rather than a constant.
 *
 * <p>
 * On the carrier-affine scheduler all of that — socket read, dispatch, every
 * modification, upstream call, socket write — resolves to one carrier, so an
 * actor's array stays resident in that core's <em>private L2</em> for the whole
 * life of the connection. It does not stay in L1d: a 64 KiB array is already
 * larger than the 48 KiB L1d, and the other connections on the same carrier
 * flush it while this one is parked. On the JDK builtin scheduler those
 * placements are untied, so the lines an actor dirtied on one core have to be
 * snooped away from it by whichever core resumes it next — a HITM per line, on
 * state that never needed to move. The cost of a migration is therefore the
 * number of dirty lines it drags across, which is why the gap should grow
 * roughly linearly with the array size until a carrier's connections stop
 * fitting in its L2.
 *
 * <p>
 * <b>The wait in the middle is real I/O, not a sleep.</b> A timed park resumes
 * from the JDK's timer queue, so it never exercises the per-carrier sub-poller
 * ({@code jdk.pollerMode=3} → Master-Poller → eventfd → home carrier) that the
 * locality argument rests on. A blocking socket round trip does, and it also
 * gives the carrier its own ready fds to batch — the effect described in the
 * upstream project's {@code PERFORMANCE.md}. The mock must run in its own
 * process on its own cpuset; see its Javadoc.
 *
 * <h2>Three ways to get a wrong answer</h2>
 *
 * <ul>
 * <li><b>Sharing the array between connections.</b> It would ping-pong under
 * both implementations alike and cancel the differential — and it would put a
 * lock, or a data race, into the one loop whose cost is the measurement. Each
 * actor owns its own; see
 * {@link io.github.pderop.looma.examples.http.ConnectionActor}.
 * <li><b>An oversized working set.</b> A carrier has to keep
 * {@code connsPerCarrier * 2 * stateKiB} resident — every connection owns its
 * {@code state} array <em>and</em> a same-sized {@code snapshot} buffer — so
 * once that exceeds a P-core's 2 MiB private L2 every access misses regardless
 * of placement. The driver measures three points — nothing touched, inside the
 * L2, past it — and the advantage at the top point must fall back onto the
 * nothing-touched baseline; that collapse is how a run proves it was measuring
 * residency at all. Note that at the driver's defaults the middle point is 1.5
 * MiB of a 2 MiB L2, which is tighter than it looks: see
 * {@code docs/performance.md}.
 * <li><b>A client-limited run.</b> Per-connection concurrency is 1 (response
 * ordering, see {@link io.github.pderop.looma.examples.http.ConnectionActor}),
 * so throughput cannot exceed {@code connections / upstreamRtt}; a result
 * sitting on that ceiling measured the client.
 * </ul>
 *
 * <p>
 * And the control that falsifies everything: {@code -Dhttp.stateKiB=0} gives
 * every connection a zero-length array, so no request touches any state at all.
 * A gap that survives it is scheduling overhead, not locality.
 *
 * @see io.github.pderop.looma.examples.http.ConnectionActor
 */
package io.github.pderop.looma.examples.http;
