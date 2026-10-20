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
package io.github.pderop.looma.scheduler.netty;

import java.util.List;

import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.spi.ActorScheduler;
import io.github.pderop.looma.spi.ActorSchedulerProvider;
import io.netty.loom.scheduler.EventLoopSchedulerGroup;
import io.netty.loom.scheduler.NettyScheduler;

/**
 * Publishes the carrier-affine scheduler of
 * {@code franz1981/Netty-VirtualThread-Scheduler} to {@code core}'s
 * {@link ActorSchedulerProvider} discovery, registered under
 * {@code META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider}.
 *
 * <p>
 * This class is the <b>only</b> compile-time link between {@code looma} and
 * {@code io.netty.loom.*}; nothing in {@code core}, {@code examples} or
 * {@code benchmarks} imports either package. That is what lets those three
 * modules compile at {@code release=25} without {@code --enable-preview} while
 * this one is built on the Loom JDK.
 */
public final class NettyActorSchedulerProvider implements ActorSchedulerProvider {

	/**
	 * What {@link #install(Carriers.Affine)} installed in this JVM, or
	 * {@code null}. Static because the pool is: {@link java.util.ServiceLoader}
	 * hands out a new provider instance per walk.
	 */
	private static Carriers.Affine installed;

	/**
	 * Why the pool this provider installed cannot be used, or null. Set when the
	 * JDK installed the scheduler with another size than asked: it cannot be
	 * undone.
	 */
	private static String unusable;

	/**
	 * Required by {@link java.util.ServiceLoader}.
	 */
	public NettyActorSchedulerProvider() {
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * {@link NettyScheduler#isAvailable()} answers the only question that matters:
	 * whether the JDK actually resolved
	 * {@code jdk.virtualThreadScheduler.implClass} to it. Having this jar on the
	 * classpath is not the same as having the scheduler installed — without the
	 * flag the JDK keeps its own built-in scheduler, and reporting {@code true}
	 * here would hand every actor a carrier pool that nothing else in the JVM uses.
	 */
	@Override
	public boolean isAvailable() {
		try {
			return NettyScheduler.isAvailable();
		} catch (Throwable e) {
			return false;
		}
	}

	/** A system found by discovery: its actor loops can be stolen. */
	@Override
	public ActorScheduler create() {
		return new NettyActorScheduler(EventLoopSchedulerGroup.instance());
	}

	/**
	 * A system built with {@code carriers}: its actor loops stay home if
	 * {@link Carriers.Affine#actorsStayHome()} asked for it. Only this system:
	 * another one on the same pool makes its own choice.
	 */
	@Override
	public ActorScheduler create(Carriers.Affine carriers) {
		return new NettyActorScheduler(EventLoopSchedulerGroup.instance(), carriers.actorsStealable());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * Sets the properties the command line would otherwise carry —
	 * {@code jdk.virtualThreadScheduler.implClass} and the {@code io.netty.loom.*}
	 * ones {@link NettyCarrierProperties} lists — then makes the JDK resolve its
	 * scheduler, and checks that the pool it got is the one asked for.
	 *
	 * <p>
	 * Each property is read exactly once, by a {@code static final} or during
	 * {@code VirtualThread.<clinit>}, so a property set after that moment is
	 * silently ignored. Three cases are refused rather than risked:
	 * <ul>
	 * <li>the command line already names the scheduler with
	 * {@code -Djdk.virtualThreadScheduler.implClass}: its pool may already be
	 * running on its own values, and no property read tells which. Configure it
	 * there or here, not in both places;
	 * <li>an earlier call in this JVM installed different {@code carriers};
	 * <li>the JDK already resolved the builtin scheduler — some virtual thread
	 * started before this call — which {@link NettyScheduler#isAvailable()}
	 * reporting {@code false} after the properties were set says unambiguously.
	 * </ul>
	 * A second call for the same pool is allowed: it is what building two actor
	 * systems in one process does. {@link Carriers.Affine#actorsStealable()} may
	 * differ between the two, since it is not part of the pool.
	 *
	 * <p>
	 * If the install fails before the JDK installed the scheduler, the properties
	 * are removed again, so that they do not describe a scheduler this JVM does not
	 * run.
	 */
	@Override
	public boolean install(Carriers.Affine carriers) {
		synchronized (NettyActorSchedulerProvider.class) {
			if (unusable != null) {
				throw new IllegalStateException(unusable);
			}
			if (installed != null) {
				if (!samePool(installed, carriers)) {
					throw new IllegalStateException("this JVM's carrier pool is already installed as " + installed
							+ " and cannot also be " + carriers);
				}
				return true;
			}
			if (System.getProperty(NettyCarrierProperties.IMPL_CLASS) != null) {
				throw new IllegalStateException("-D" + NettyCarrierProperties.IMPL_CLASS + " is on the command line,"
						+ " so the scheduler is configured there: cannot also configure it as " + carriers
						+ ". Drop the -D flags or the carriers(...) call");
			}
			List<String> added = NettyCarrierProperties.set(carriers);
			boolean available;
			try {
				// Makes the JDK resolve its scheduler. A failure there (the topology, native
				// access) can be an Error: it is reported as what it is, not as a missing
				// provider.
				available = NettyScheduler.isAvailable();
			} catch (Throwable e) {
				NettyCarrierProperties.clear(added);
				throw new IllegalStateException("could not install " + carriers + ": " + e, e);
			}
			if (!available) {
				NettyCarrierProperties.clear(added);
				throw new IllegalStateException("a virtual thread started under the JDK builtin scheduler before "
						+ carriers + " could be installed: build the actor system before anything in this JVM"
						+ " starts a virtual thread");
			}
			// From here on the JDK runs this scheduler for good: the properties describe
			// it, and stay.
			int size = EventLoopSchedulerGroup.instance().size();
			if (size != carriers.count()) {
				unusable = "the carrier pool was installed with " + size + " carriers, not the " + carriers.count()
						+ " asked for by " + carriers;
				throw new IllegalStateException(unusable);
			}
			installed = carriers;
			return true;
		}
	}

	/**
	 * Whether two configurations describe the same pool: all but actorsStealable.
	 */
	private static boolean samePool(Carriers.Affine a, Carriers.Affine b) {
		return a.count() == b.count() && a.pinnedToCpus() == b.pinnedToCpus() && a.workStealing() == b.workStealing();
	}
}
