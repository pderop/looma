/*
 * Copyright 2025 The Netty VirtualThread Scheduler Project
 *
 * The Netty VirtualThread Scheduler Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
// Modified by the Looma project: the constructor starts the JDK's pollers before the
// carriers exist. Every change is marked "Looma:"; the full diff against the
// original is netty-loom-scheduler/looma-home-queue.patch.
package io.netty.loom.scheduler;

/**
 * Custom virtual thread scheduler loaded by the JDK via
 * {@code -Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler}.
 *
 * <p>
 * Eagerly creates the {@link EventLoopSchedulerGroup carrier pool} at
 * construction time — if the JDK is told to use this scheduler, the carriers
 * are needed.
 *
 * <p>
 * Routes virtual thread tasks to their home {@link EventLoopScheduler} when one
 * is assigned (via {@link EventLoopScheduler.SchedulingContext} attachment),
 * otherwise falls back to the JDK's built-in scheduler.
 *
 * <p>
 * When {@code jdk.pollerMode=3} (per-carrier pollers), JDK-internal read-poller
 * threads inherit the caller's scheduler so they stay on the same carrier.
 */
public class NettyScheduler implements Thread.VirtualThreadScheduler {

	static final boolean REPLACE_BUILTIN_SCHEDULER = Boolean
			.parseBoolean(System.getProperty("io.netty.loom.replaceBuiltinScheduler", "false"));

	private static volatile NettyScheduler INSTANCE;

	private final Thread.VirtualThreadScheduler jdkBuiltinScheduler;
	private final EventLoopSchedulerGroup group;
	private final boolean perCarrierPollers;

	public NettyScheduler(Thread.VirtualThreadScheduler jdkBuiltinScheduler) {
		this.jdkBuiltinScheduler = jdkBuiltinScheduler;
		this.perCarrierPollers = Integer.getInteger("jdk.pollerMode", -1) == 3;
		startJdkPollers();
		this.group = new EventLoopSchedulerGroup(this);
		INSTANCE = this;
	}

	// Looma: the JDK starts its Master-Poller and Write-Poller threads the first time
	// a virtual thread waits on a socket, from the carrier that runs it. On Linux, a new
	// thread runs only on the CPUs of the thread that starts it, so both pollers would
	// stay on that carrier's one CPU for good. Started here, before any carrier pins itself,
	// they get the CPUs of the thread that installs this scheduler. On a JDK without
	// this class, the pollers start as before.
	private static void startJdkPollers() {
		try {
			Class.forName("sun.nio.ch.Poller");
		} catch (ClassNotFoundException | LinkageError e) {
			// Nothing to do: the JDK starts its pollers later, as usual.
		}
	}

	public static NettyScheduler instance() {
		return ensureInstalled();
	}

	/**
	 * Returns the global carrier pool, or throws if the scheduler is not installed.
	 */
	public static EventLoopSchedulerGroup group() {
		var scheduler = ensureInstalled();
		if (scheduler == null) {
			throw new IllegalStateException(
					"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler is required");
		}
		return scheduler.group;
	}

	@Override
	public void onStart(Thread.VirtualThreadTask task) {
		if (task.attachment() instanceof EventLoopScheduler.SchedulingContext context) {
			var scheduler = context.assignedScheduler();
			if (scheduler != null) {
				scheduler.execute(task);
				return;
			}
			task.attach(null);
		} else {
			if (perCarrierPollers && Thread.currentThread().isVirtual()) {
				var scheduler = EventLoopScheduler.currentThreadSchedulerContext().runningScheduler();
				if (scheduler != null && task.thread().getName().endsWith("-Read-Poller")) {
					task.attach(new EventLoopScheduler.SchedulingContext(task.thread().threadId(), scheduler,
							EventLoopScheduler.VThreadType.JDK_POLLER));
					scheduler.execute(task);
					return;
				}
			}
			if (REPLACE_BUILTIN_SCHEDULER) {
				var scheduler = group.selectScheduler();
				var context = new EventLoopScheduler.SchedulingContext(task.thread().threadId(), scheduler,
						EventLoopScheduler.VThreadType.VT);
				task.attach(context);
				scheduler.execute(task);
				return;
			}
		}
		jdkBuiltinScheduler.onStart(task);
	}

	@Override
	public void onContinue(Thread.VirtualThreadTask task) {
		if (task.attachment() instanceof EventLoopScheduler.SchedulingContext context) {
			var scheduler = context.assignedScheduler();
			if (scheduler != null) {
				scheduler.execute(task);
				return;
			}
			task.attach(null);
		}
		jdkBuiltinScheduler.onContinue(task);
	}

	private static NettyScheduler ensureInstalled() {
		var instance = INSTANCE;
		if (instance != null) {
			return instance;
		}
		Thread.ofVirtual().unstarted(() -> {
		});
		return INSTANCE;
	}

	public static boolean isAvailable() {
		return ensureInstalled() != null;
	}
}
