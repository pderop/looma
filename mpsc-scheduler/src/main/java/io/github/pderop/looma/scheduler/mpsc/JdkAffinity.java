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
package io.github.pderop.looma.scheduler.mpsc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * {@code Thread.Affinity}, if this JDK has it. Looked up once, when this class
 * loads.
 *
 * <p>
 * The first JDK of openjdk/loom pull request 227 has it. The later one does
 * not. This class is the only place that names it, so the module compiles and
 * runs on both. It is used only to set up the carriers and the factories, never
 * for a message.
 */
final class JdkAffinity {

	/** Whether this JDK has {@code Thread.Affinity}. */
	static final boolean SUPPORTED;

	/** {@code Thread.Affinity.newGroup()}, or null. */
	private static final MethodHandle NEW_GROUP;

	/** {@code Thread.Affinity.Group.newAffinity()}, or null. */
	private static final MethodHandle NEW_AFFINITY;

	/** {@code Thread.Builder.OfVirtual.affinity(Thread.Affinity)}, or null. */
	private static final MethodHandle WITH_AFFINITY;

	static {
		MethodHandle newGroup = null;
		MethodHandle newAffinity = null;
		MethodHandle withAffinity = null;
		try {
			Class<?> affinity = Class.forName("java.lang.Thread$Affinity");
			Class<?> group = Class.forName("java.lang.Thread$Affinity$Group");
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			newGroup = lookup.findStatic(affinity, "newGroup", MethodType.methodType(group));
			newAffinity = lookup.findVirtual(group, "newAffinity", MethodType.methodType(affinity));
			withAffinity = lookup.findVirtual(Thread.Builder.OfVirtual.class, "affinity",
					MethodType.methodType(Thread.Builder.OfVirtual.class, affinity));
		} catch (ReflectiveOperationException e) {
			// This JDK has no Thread.Affinity.
			newGroup = null;
			newAffinity = null;
			withAffinity = null;
		}
		NEW_GROUP = newGroup;
		NEW_AFFINITY = newAffinity;
		WITH_AFFINITY = withAffinity;
		SUPPORTED = withAffinity != null;
	}

	private JdkAffinity() {
	}

	/**
	 * One group of {@code count} affinities: one per carrier.
	 *
	 * @throws IllegalStateException
	 *             if this JDK has no {@code Thread.Affinity}
	 */
	static Object[] newHomes(int count) {
		requireSupported();
		try {
			Object group = NEW_GROUP.invoke();
			Object[] homes = new Object[count];
			for (int i = 0; i < count; i++) {
				homes[i] = NEW_AFFINITY.invoke(group);
			}
			return homes;
		} catch (Throwable e) {
			throw new IllegalStateException("could not create the affinities of the MPSC carriers", e);
		}
	}

	/**
	 * Returns {@code builder.affinity(home)}.
	 *
	 * @throws IllegalStateException
	 *             if this JDK has no {@code Thread.Affinity}
	 */
	static Thread.Builder.OfVirtual withAffinity(Thread.Builder.OfVirtual builder, Object home) {
		requireSupported();
		try {
			return (Thread.Builder.OfVirtual) WITH_AFFINITY.invoke(builder, home);
		} catch (Throwable e) {
			throw new IllegalStateException("could not give a virtual thread the affinity " + home, e);
		}
	}

	private static void requireSupported() {
		if (!SUPPORTED) {
			throw new IllegalStateException("this JDK has no Thread.Affinity");
		}
	}
}
