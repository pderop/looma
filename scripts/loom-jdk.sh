# Resolves the JDK the benchmark JVMs must run on, and exports LOOM_JAVA (its bin/java).
# Sourced by every script here that launches a JVM; not meant to be run on its own.
#
# Set LOOM_JDK_REQUIRED=0 before sourcing to skip the "is this really a Loom build" probe below,
# for a JVM that installs no scheduler and uses no preview feature and therefore runs on a stock
# JDK. run-user-bench.sh does not use it: it launches carrier runs from the same script as the
# control runs, so it needs the Loom JDK anyway.
#
# There is only ONE JDK in play: Maven runs on the Loom JDK 28 itself -- `sdk env` reads
# .sdkmanrc, which names 28-loom -- so $JAVA_HOME already is the Loom JDK and these scripts simply
# follow it. $LOOM_JDK_HOME is still honoured, and still wins, for a one-off run against a
# different Loom build than the one the build uses.
#
# Resolution order: $LOOM_JDK_HOME, then $JAVA_HOME, then `java` on $PATH.

if [ -n "${LOOM_JDK_HOME:-}" ]; then
	_loom_jdk_home=$LOOM_JDK_HOME
	_loom_jdk_origin='$LOOM_JDK_HOME'
elif [ -n "${JAVA_HOME:-}" ]; then
	_loom_jdk_home=$JAVA_HOME
	_loom_jdk_origin='$JAVA_HOME'
elif command -v java >/dev/null 2>&1; then
	_loom_jdk_home=$(cd "$(dirname "$(command -v java)")/.." && pwd)
	_loom_jdk_origin='java on $PATH'
else
	echo "no JDK found -- run 'sdk env' (it selects 28-loom, see .sdkmanrc) or export JAVA_HOME" >&2
	exit 1
fi

LOOM_JAVA="$_loom_jdk_home/bin/java"

if [ ! -x "$LOOM_JAVA" ]; then
	echo "no java at $LOOM_JAVA ($_loom_jdk_origin does not look like a JDK image)" >&2
	exit 1
fi

# A stock JDK 28 sits right next to the Loom one in SDKMAN and reports the same version; it only
# fails much later, deep inside a run, with "cannot find symbol: Thread.VirtualThreadScheduler".
# Same probe for every entry point, so a shell and a build cannot silently disagree.
if [ "${LOOM_JDK_REQUIRED:-1}" = 1 ] \
	&& ! "$_loom_jdk_home/bin/javap" 'java.lang.Thread$VirtualThreadScheduler' >/dev/null 2>&1; then
	echo "$_loom_jdk_home ($_loom_jdk_origin) is not a Loom build -- it has no Thread.VirtualThreadScheduler." >&2
	echo "Run 'sdk env' in the project root, or point JAVA_HOME at the Loom JDK 28." >&2
	exit 1
fi

export LOOM_JAVA
