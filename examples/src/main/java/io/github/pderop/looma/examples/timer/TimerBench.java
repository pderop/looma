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
package io.github.pderop.looma.examples.timer;

import static io.github.pderop.looma.Placement.carrier;
import static io.github.pderop.looma.SpawnOptions.placedOn;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.Message;

/**
 * Compares the timer wheel of Looma with a ScheduledThreadPoolExecutor. Run it
 * with {@code scripts/timer-bench.sh}.
 *
 * <p>
 * It is an open loop. A driver thread works on a fixed schedule: every
 * millisecond it tells each driver actor how many timers to arm. A late slot
 * does not move the next ones. Nothing a timer does changes the rate, so the
 * rate asked is the same in every mode.
 *
 * <p>
 * There is one driver actor per carrier. It arms its timers while it handles
 * one message.
 *
 * <ul>
 * <li>{@code -Dtimer.mode=wheel}: {@code target.tell(message, delay, unit)}.
 * <li>{@code -Dtimer.mode=jdk}: one executor, built with the platform thread
 * factory of the scheduler. Its task does {@code target.tell(message)}.
 * </ul>
 *
 * <p>
 * Two scenarios, set with {@code -Dtimer.scenario}:
 * <ul>
 * <li>{@code expire} (default): each timer tells a target actor of the same
 * carrier. The message carries the time it was due. The target records how late
 * it arrived.
 * <li>{@code sentinel}: each timer is a guard, a {@code Timeout} message due in
 * 30 seconds, cancelled at once. This is the usual timeout that is cancelled
 * when all goes well. The guard must never arrive. In mode jdk the executor
 * removes a cancelled task at once ({@code setRemoveOnCancelPolicy(true)}).
 * </ul>
 *
 * Other properties: {@code timer.rate} (timers per second, all carriers
 * together), {@code timer.delayMillis}, {@code timer.tickMillis},
 * {@code timer.carriers}, {@code timer.targets} (per carrier),
 * {@code timer.warmupSeconds} and {@code timer.measureSeconds}.
 */
public final class TimerBench {

	private static final long LATENESS_MAX_NANOS = TimeUnit.SECONDS.toNanos(10);

	private static final long SLOT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

	private static final int SLOTS_PER_SECOND = 1000;

	/** Tells a driver actor to arm this many timers. */
	record Arm(int count) implements Message {
	}

	/** The timer message. It knows when it was due. */
	record Expire(long dueNanos) implements Message {
	}

	/** The guard of the sentinel scenario. It must never arrive. */
	record Timeout() implements Message {
	}

	/** What the actors share. */
	static final class Shared {
		final boolean wheel;
		final boolean sentinel;
		final long delayNanos;
		final ScheduledThreadPoolExecutor executor;
		final Recorder lateness = new Recorder(LATENESS_MAX_NANOS, 3);
		final LongAdder armed = new LongAdder();
		final LongAdder received = new LongAdder();
		final LongAdder timeouts = new LongAdder();
		final LongAdder slots = new LongAdder();
		final LongAdder missedSlots = new LongAdder();
		volatile boolean measuring;

		Shared(boolean wheel, boolean sentinel, long delayNanos, ScheduledThreadPoolExecutor executor) {
			this.wheel = wheel;
			this.sentinel = sentinel;
			this.delayNanos = delayNanos;
			this.executor = executor;
		}
	}

	/** Receives the timers of the expire scenario. */
	static final class TargetActor implements Actor {
		private final Shared shared;

		TargetActor(Shared shared) {
			this.shared = shared;
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			long now = System.nanoTime();
			switch (message) {
				case Expire expire -> {
					if (shared.measuring) {
						long late = now - expire.dueNanos();
						shared.lateness.recordValue(Math.min(Math.max(0, late), LATENESS_MAX_NANOS));
						shared.received.increment();
					}
				}
				default -> throw new IllegalStateException("unexpected message " + message);
			}
		}
	}

	/** Arms the timers of one carrier. */
	static final class DriverActor implements Actor {
		private final Shared shared;
		private final List<ActorRef> targets;
		private int nextTarget;

		DriverActor(Shared shared, List<ActorRef> targets) {
			this.shared = shared;
			this.targets = targets;
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			switch (message) {
				case Arm arm -> {
					for (int i = 0; i < arm.count(); i++) {
						if (shared.sentinel) {
							armAndCancelGuard(context.self());
						} else {
							armExpire();
						}
					}
					if (shared.measuring) {
						shared.armed.add(arm.count());
					}
				}
				case Timeout timeout -> shared.timeouts.increment();
				default -> throw new IllegalStateException("unexpected message " + message);
			}
		}

		private void armExpire() {
			ActorRef target = targets.get(nextTarget);
			nextTarget = (nextTarget + 1) % targets.size();
			Expire expire = new Expire(System.nanoTime() + shared.delayNanos);
			if (shared.wheel) {
				target.tell(expire, shared.delayNanos, TimeUnit.NANOSECONDS);
			} else {
				shared.executor.schedule(() -> target.tell(expire), shared.delayNanos, TimeUnit.NANOSECONDS);
			}
		}

		private void armAndCancelGuard(ActorRef self) {
			ScheduledFuture<?> guard;
			if (shared.wheel) {
				guard = self.tell(new Timeout(), 30, TimeUnit.SECONDS);
			} else {
				guard = shared.executor.schedule(() -> self.tell(new Timeout()), 30, TimeUnit.SECONDS);
			}
			guard.cancel(false);
		}
	}

	/**
	 * The timers per slot of a carrier: the rate is cut into whole timers, and the
	 * remainder is spread over the slots.
	 */
	static int timersInSlot(long carrierRate, long slot) {
		return (int) ((slot + 1) * carrierRate / SLOTS_PER_SECOND - slot * carrierRate / SLOTS_PER_SECOND);
	}

	/** The rate of one carrier: the remainder goes to the first carriers. */
	static long carrierRate(long rate, int carriers, int carrier) {
		return rate / carriers + (carrier < rate % carriers ? 1 : 0);
	}

	/**
	 * Every millisecond, tells each driver actor how many timers to arm. The
	 * schedule is fixed: a late slot does not delay the next ones. A slot that
	 * starts a whole slot late, or more, is counted as missed.
	 */
	private static void drive(Shared shared, List<ActorRef> drivers, long rate, AtomicBoolean stop) {
		int carriers = drivers.size();
		long[] rates = IntStream.range(0, carriers).mapToLong(i -> carrierRate(rate, carriers, i)).toArray();
		long next = System.nanoTime();
		long slot = 0;
		while (!stop.get()) {
			long wait = next - System.nanoTime();
			if (wait > 0) {
				LockSupport.parkNanos(wait);
				continue;
			}
			if (shared.measuring) {
				shared.slots.increment();
				if (-wait >= SLOT_NANOS) {
					shared.missedSlots.increment();
				}
			}
			for (int i = 0; i < rates.length; i++) {
				int count = timersInSlot(rates[i], slot % SLOTS_PER_SECOND);
				if (count > 0) {
					drivers.get(i).tell(new Arm(count));
				}
			}
			slot++;
			next += SLOT_NANOS;
		}
	}

	public static void main(String[] args) throws Exception {
		int carriers = Integer.getInteger("timer.carriers", Runtime.getRuntime().availableProcessors());
		long rate = Long.getLong("timer.rate", 100_000);
		int targetsPerCarrier = Integer.getInteger("timer.targets", 100);
		long delayMillis = Long.getLong("timer.delayMillis", 10);
		long tickMillis = Long.getLong("timer.tickMillis", 1);
		String mode = System.getProperty("timer.mode", "wheel");
		String scenario = System.getProperty("timer.scenario", "expire");
		int warmup = Integer.getInteger("timer.warmupSeconds", 5);
		int measure = Integer.getInteger("timer.measureSeconds", 15);
		if (!mode.equals("wheel") && !mode.equals("jdk")) {
			throw new IllegalArgumentException("timer.mode must be wheel or jdk, not " + mode);
		}
		if (!scenario.equals("expire") && !scenario.equals("sentinel")) {
			throw new IllegalArgumentException("timer.scenario must be expire or sentinel, not " + scenario);
		}

		// First, before any virtual thread starts: the JVM picks its scheduler when
		// the first one starts.
		ActorSystem system = ActorSystem.builder().carriers(Carriers.affine(carriers))
				.timerTick(Duration.ofMillis(tickMillis)).build();

		// The worker takes back main's CPUs when it starts, whatever thread starts
		// it. So it comes from the platform thread factory of the scheduler.
		ScheduledThreadPoolExecutor executor = null;
		if (mode.equals("jdk")) {
			ThreadFactory base = system.scheduler().platformThreadFactory();
			executor = new ScheduledThreadPoolExecutor(1, runnable -> {
				Thread thread = base.newThread(runnable);
				thread.setName("timer-stpe");
				thread.setDaemon(true);
				return thread;
			});
			executor.setRemoveOnCancelPolicy(true);
		}
		long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMillis);
		Shared shared = new Shared(mode.equals("wheel"), scenario.equals("sentinel"), delayNanos, executor);

		List<ActorRef> drivers = new ArrayList<>();
		for (int c = 0; c < system.carrierCount(); c++) {
			List<ActorRef> targets = new ArrayList<>();
			if (!shared.sentinel) {
				for (int t = 0; t < targetsPerCarrier; t++) {
					targets.add(
							system.spawn("target-" + c + "-" + t, () -> new TargetActor(shared), placedOn(carrier(c))));
				}
			}
			drivers.add(system.spawn("driver-" + c, () -> new DriverActor(shared, targets), placedOn(carrier(c))));
		}
		String tickText = mode.equals("wheel") ? tickMillis + " ms" : "-";
		System.out.printf(Locale.ROOT,
				"timers       mode %s, scenario %s, rate %,d timers/s, %d targets per carrier, delay %d ms, tick %s, %d carriers%n",
				mode, scenario, rate, shared.sentinel ? 0 : targetsPerCarrier, delayMillis, tickText, carriers);
		if (mode.equals("jdk") && shared.sentinel) {
			System.out.println(
					"note         executor: setRemoveOnCancelPolicy(true), a cancelled guard leaves the queue at once");
		}
		System.out.printf(Locale.ROOT, "window       %ds warm-up, %ds measured%n", warmup, measure);

		AtomicBoolean stop = new AtomicBoolean();
		Thread driver = Thread.ofPlatform().name("timer-driver").start(() -> drive(shared, drivers, rate, stop));

		TimeUnit.SECONDS.sleep(warmup);
		shared.lateness.reset();
		shared.measuring = true;
		// scripts/timer-bench.sh starts perf from this line.
		System.out.printf(Locale.ROOT, "measure      started, %ds%n", measure);
		System.out.flush();
		long start = System.nanoTime();
		Duration startCpu = processCpu();
		TimeUnit.SECONDS.sleep(measure);
		Duration endCpu = processCpu();
		long elapsed = System.nanoTime() - start;
		shared.measuring = false;

		long armed = shared.armed.sum();
		long received = shared.received.sum();
		long slots = shared.slots.sum();
		long missed = shared.missedSlots.sum();
		long asked = Math.round(rate * (elapsed / 1e9));
		long cpuNanos = endCpu.minus(startCpu).toNanos();
		double seconds = elapsed / 1e9;
		Histogram late = shared.lateness.getIntervalHistogram();
		System.out.printf(Locale.ROOT, "asked        %,d timers/s (%,d in %.1f s)%n", rate, asked, seconds);
		System.out.printf(Locale.ROOT, "armed        %,.0f timers/s (%,d in %.1f s)%n", armed / seconds, armed,
				seconds);
		if (!shared.sentinel) {
			System.out.printf(Locale.ROOT, "received     %,.0f timers/s (%,d in %.1f s)%n", received / seconds,
					received, seconds);
		}
		System.out.printf(Locale.ROOT, "missed       %,d of %,d driver slots (%.2f %%)%n", missed, slots,
				slots == 0 ? 0.0 : 100.0 * missed / slots);
		System.out.printf(Locale.ROOT, "cpu          %.2f us/timer (%.2f cores busy)%n", cpuNanos / 1e3 / armed,
				cpuNanos / (double) elapsed);
		if (!shared.sentinel) {
			System.out.printf(Locale.ROOT, "late         p50 %.0f us, p99 %.0f us, p99.9 %.0f us, max %.0f us%n",
					late.getValueAtPercentile(50) / 1e3, late.getValueAtPercentile(99) / 1e3,
					late.getValueAtPercentile(99.9) / 1e3, late.getMaxValue() / 1e3);
		}
		long done = shared.sentinel ? armed : received;
		if (done < asked * 0.99 || missed > slots * 0.01) {
			System.out.println("*** did not keep up ***");
		}
		if (shared.sentinel) {
			// Measure what stays in memory once the cancelled guards are garbage.
			System.gc();
			Runtime runtime = Runtime.getRuntime();
			double heapMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
			System.out.printf(Locale.ROOT, "heap         %.1f MiB after gc%n", heapMiB);
			System.out.printf(Locale.ROOT, "timeouts     %d arrived (must be 0)%n", shared.timeouts.sum());
		}
		System.out.flush();

		stop.set(true);
		driver.join();
		if (executor != null) {
			executor.shutdownNow();
		}
		system.shutdown();
		system.awaitTermination(10, TimeUnit.SECONDS);
		System.exit(0);
	}

	private static Duration processCpu() {
		return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO);
	}
}
