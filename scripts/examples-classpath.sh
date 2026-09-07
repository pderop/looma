#!/usr/bin/env bash
#
# Prints the runtime classpath of the examples module: its own classes plus core and every
# runtime dependency (httpclient5, jackson, ...).
#
# Asked of Maven rather than assembled by hand on purpose: since httpclient5 arrived the runtime
# classpath is no longer just the project's own jars, and the scheduler jar itself only exists on
# a Loom JDK, where it arrives through a profile-scoped dependency.
#
# Usage:  CP=$(scripts/examples-classpath.sh)
# Set REBUILD=1 to force the build even when a classpath file is already there.

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CP_FILE="$ROOT/examples/target/classpath.txt"
CLASSES="$ROOT/examples/target/classes"

# True when classpath.txt is absent or names something that is no longer on disk.
#
# THE FILE OUTLIVES THE JARS IT NAMES, and it names core by its path in the reactor
# (core/target/looma-core-*.jar), not in ~/.m2 -- so a `mvn clean` on core alone, a purged local
# repository, or a checkout half-built on another JDK profile all leave a classpath with a hole
# in it. The JVM says nothing about a classpath entry that is not there: it fails much later, at
# the first class it cannot find, with a NoClassDefFoundError naming some core class rather than
# the jar that is missing. Cheaper to check here than to read that stack trace.
stale_classpath() {
	[ -f "$CP_FILE" ] || return 0
	local entry
	while IFS= read -r entry; do
		[ -n "$entry" ] || continue
		[ -e "$entry" ] || return 0
	done < <(tr ':' '\n' < "$CP_FILE")
	return 1
}

# Everything Maven prints goes to stderr: stdout of this script is the classpath and nothing
# else, so it can be captured with $(...).
if [ "${REBUILD:-0}" = 1 ] || [ ! -d "$CLASSES" ] || stale_classpath; then
	(cd "$ROOT" && mvn -q -pl examples -am -DskipTests package) >&2
fi

# The build writes classpath.txt itself; regenerate it here if that ever stops being true, so
# this script keeps working against a pom that does not.
if [ ! -f "$CP_FILE" ]; then
	(cd "$ROOT" && mvn -q -pl examples dependency:build-classpath \
		-Dmdep.includeScope=runtime -Dmdep.outputFile="$CP_FILE") >&2
fi

# A rebuild that did not fix it means the build itself is not producing what classpath.txt
# names: say so here rather than hand out the broken classpath and let a JVM fail on it.
if stale_classpath; then
	echo "examples/target/classpath.txt names files that do not exist, and a rebuild did not" >&2
	echo "produce them. Run 'mvn -DskipTests package' at the project root and check:" >&2
	while IFS= read -r entry; do
		if [ -n "$entry" ] && [ ! -e "$entry" ]; then
			echo "  missing: $entry" >&2
		fi
	done < <(tr ':' '\n' < "$CP_FILE")
	exit 1
fi

echo "$CLASSES:$(cat "$CP_FILE")"
