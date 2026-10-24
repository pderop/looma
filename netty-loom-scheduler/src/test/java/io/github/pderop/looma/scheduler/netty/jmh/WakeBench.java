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
package io.github.pderop.looma.scheduler.netty.jmh;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

import io.netty.loom.scheduler.EventLoopScheduler;
import io.netty.loom.scheduler.NettyScheduler;
import io.netty.loom.topology.LinuxCarrierTopology;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

/**
 * How fast the carriers get through bursts of stolen work.
 *
 * <p>
 * One op is one burst. The producer, a virtual thread of carrier 0, starts
 * {@code tasks} stealable virtual threads on carrier 0. Each one spins
 * {@code spinMicros} on the CPU. The op ends when the last one is done. The
 * other carriers are idle between bursts, so they must be woken to steal. The
 * order in which the idle carriers are woken is what this benchmark compares.
 *
 * <p>
 * The JMH thread does not start the tasks itself: it asks the producer for a
 * burst, then waits for it. So the tasks are started from carrier 0, as in an
 * actor that starts a virtual thread.
 *
 * <p>
 * Before each burst, the JMH thread can wait {@code pauseMicros}. The wait is
 * not measured. It gives the carriers time to park.
 *
 * <p>
 * At the end of a run, it prints two lines. {@code SHARE} gives the part of the
 * tasks each carrier ran. {@code CONCURRENCY} gives how many tasks were running
 * when a task started: it shows that the tasks really ran side by side.
 *
 * <p>
 * Run it with {@code scripts/run-wake-jmh.sh}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class WakeBench {

	// One counter per carrier, on its own cache line.
	private static final int STRIDE = 16;
	private static final int MAX_CARRIERS = 64;

	@Param({"2", "4", "16"})
	int tasks;

	@Param({"5", "50"})
	int spinMicros;

	@Param({"0", "500", "2000"})
	int pauseMicros;

	// The CPU of the JMH thread. It must not be one of the carriers' CPUs.
	@Param({"8"})
	int jmhCpu;

	private ThreadFactory factory;
	private int carriers;
	private long spinNanos;
	private long pauseNanos;
	private Runnable task;
	private Thread producer;
	private volatile Thread jmhThread;
	private volatile boolean go;
	private volatile boolean done;
	private volatile boolean stopped;
	private volatile boolean measuring;
	private final AtomicInteger remaining = new AtomicInteger();
	private final AtomicInteger running = new AtomicInteger();
	private final AtomicLongArray ran = new AtomicLongArray(MAX_CARRIERS * STRIDE);
	private final AtomicLongArray concurrency = new AtomicLongArray(MAX_CARRIERS + 1);

	@Setup(Level.Trial)
	public void setup() {
		// The carriers start here and take the CPUs of this thread. So pin this thread
		// only after.
		var group = NettyScheduler.group();
		LinuxCarrierTopology.setAffinity(jmhCpu);
		carriers = group.size();
		factory = group.scheduler(0).virtualThreadFactory();
		spinNanos = TimeUnit.MICROSECONDS.toNanos(spinMicros);
		pauseNanos = TimeUnit.MICROSECONDS.toNanos(pauseMicros);
		task = () -> {
			int now = running.incrementAndGet();
			long end = System.nanoTime() + spinNanos;
			while (System.nanoTime() < end) {
				Thread.onSpinWait();
			}
			if (measuring) {
				concurrency.getAndIncrement(Math.min(now, MAX_CARRIERS));
				ran.getAndIncrement(EventLoopScheduler.currentRunningScheduler().id() * STRIDE);
			}
			running.decrementAndGet();
			if (remaining.decrementAndGet() == 0) {
				LockSupport.unpark(producer);
			}
		};
		producer = factory.newThread(this::produce);
		producer.start();
	}

	// The producer: one burst each time the JMH thread asks for one.
	private void produce() {
		while (true) {
			while (!go) {
				if (stopped) {
					return;
				}
				LockSupport.park();
			}
			go = false;
			remaining.set(tasks);
			for (int i = 0; i < tasks; i++) {
				factory.newThread(task).start();
			}
			while (remaining.get() != 0) {
				LockSupport.park();
			}
			done = true;
			LockSupport.unpark(jmhThread);
		}
	}

	@Setup(Level.Iteration)
	public void startIteration(IterationParams params) {
		measuring = params.getType() == IterationType.MEASUREMENT;
	}

	// The pause before a burst. JMH does not count it in the score.
	@Setup(Level.Invocation)
	public void pause() {
		long end = System.nanoTime() + pauseNanos;
		long left;
		while ((left = end - System.nanoTime()) > 0) {
			LockSupport.parkNanos(left);
		}
	}

	@Benchmark
	public void burst() {
		jmhThread = Thread.currentThread();
		done = false;
		go = true;
		LockSupport.unpark(producer);
		while (!done) {
			LockSupport.park();
		}
	}

	@TearDown(Level.Trial)
	public void report() throws InterruptedException {
		stopped = true;
		LockSupport.unpark(producer);
		producer.join();
		long total = 0;
		for (int c = 0; c < carriers; c++) {
			total += ran.get(c * STRIDE);
		}
		var sb = new StringBuilder();
		sb.append("SHARE tasks=").append(tasks).append(" spin=").append(spinMicros).append(" pause=")
				.append(pauseMicros).append(" total=").append(total).append(" :");
		for (int c = 0; c < carriers; c++) {
			sb.append(String.format(" c%d=%.1f%%", c, 100.0 * ran.get(c * STRIDE) / Math.max(1, total)));
		}
		sb.append("\nCONCURRENCY tasks=").append(tasks).append(" spin=").append(spinMicros).append(" pause=")
				.append(pauseMicros).append(" :");
		for (int k = 1; k <= carriers; k++) {
			sb.append(String.format(" %d=%.1f%%", k, 100.0 * concurrency.get(k) / Math.max(1, total)));
		}
		System.out.println();
		System.out.println(sb);
	}
}
