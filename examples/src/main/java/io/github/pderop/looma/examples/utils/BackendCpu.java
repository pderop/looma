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
package io.github.pderop.looma.examples.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The CPU time of the backend process, read from {@code /proc/<pid>/stat}.
 * Without a pid, or off Linux, it reads nothing and prints nothing.
 */
public record BackendCpu(long pid, long clkTck) {

	/** utime + stime, in clock ticks, or -1. */
	public long ticks() {
		if (pid <= 0) {
			return -1;
		}
		try {
			String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
			// The command name may hold spaces: the fields start after the last ')'.
			String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
			return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}

	/** The ticks used since {@code start}, or -1 if one of the two reads failed. */
	public long ticksSince(long start) {
		long end = ticks();
		return start < 0 || end < 0 ? -1 : end - start;
	}

	public void print(long ticks, long elapsedNanos) {
		if (pid > 0 && ticks >= 0) {
			System.out.printf(Locale.ROOT, "backend cpu  %.2f CPUs busy%n",
					ticks / (double) clkTck / (elapsedNanos / 1e9));
		}
	}
}
