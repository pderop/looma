#!/usr/bin/env bash
#
# Prints the runtime classpath of the examples module: its own classes plus core and every
# runtime dependency.
#
# Asked of Maven rather than assembled by hand on purpose: the scheduler jar only exists on a
# Loom JDK, where it arrives through a profile-scoped dependency, so the classpath is not the
# same list on every JDK and cannot be written down here. The examples' one third-party
# dependency is HdrHistogram, for the benchmark's open-loop percentiles; BackendServer is plain
# NIO and Upstream is the HTTP/1.1 it needs and nothing more.
#
# Usage:  CP=$(scripts/classpath.sh)
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

# True when a source file is newer than the newest class its module produced.
#
# THE CHECK ABOVE ONLY ASKS WHETHER THE JARS EXIST, and an existing jar can hold code that is no
# longer on disk: nothing here ever rebuilds once classpath.txt names files that are all present.
# A benchmark then runs the old classes and prints a table that looks exactly like a result. It
# has already happened once, with a constant in ActorCell: edited to fill a table, reverted in the
# source, never rebuilt, and a whole session's numbers described the wrong constant.
#
# A source file OLDER than the classes still gets skipped, here and by Maven, which compares the
# same mtimes -- a revert that restores an old timestamp (git checkout of an older file, mv of a
# backup) is invisible to both. `REBUILD=1` is the way out.
stale_sources() {
	local module ref
	for module in core netty-loom-scheduler examples; do
		[ -d "$ROOT/$module/src/main/java" ] || continue
		[ -d "$ROOT/$module/target/classes" ] || continue
		ref=$(find "$ROOT/$module/target/classes" -name '*.class' -exec ls -t {} + 2> /dev/null | head -1)
		[ -n "$ref" ] || return 0
		[ -z "$(find "$ROOT/$module/src/main/java" -name '*.java' -newer "$ref" -print -quit)" ] || return 0
	done
	return 1
}

# Everything Maven prints goes to stderr: stdout of this script is the classpath and nothing
# else, so it can be captured with $(...).
if [ "${REBUILD:-0}" = 1 ] || [ ! -d "$CLASSES" ] || stale_classpath || stale_sources; then
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
