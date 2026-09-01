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

# Everything Maven prints goes to stderr: stdout of this script is the classpath and nothing
# else, so it can be captured with $(...).
if [ "${REBUILD:-0}" = 1 ] || [ ! -f "$CP_FILE" ] || [ ! -d "$CLASSES" ]; then
	(cd "$ROOT" && mvn -q -pl examples -am -DskipTests package) >&2
fi

# The build writes classpath.txt itself; regenerate it here if that ever stops being true, so
# this script keeps working against a pom that does not.
if [ ! -f "$CP_FILE" ]; then
	(cd "$ROOT" && mvn -q -pl examples dependency:build-classpath \
		-Dmdep.includeScope=runtime -Dmdep.outputFile="$CP_FILE") >&2
fi

echo "$CLASSES:$(cat "$CP_FILE")"
