#!/usr/bin/env bash
#
# Runs the servlet benchmark under JMH (examples/src/test, ServletBench.java), to look at it with
# the JMH profilers. The backend must be running: start `scripts/jmh-servlet-start-backend.sh` first.
#
#   scripts/jmh-servlet.sh                       # both schedulers at 300000, no profiler
#   scripts/jmh-servlet.sh --schedulers mpsc,mpsc-no-affinity,jdk
#   scripts/jmh-servlet.sh -- -p fraudKiB=128 -p fraudFeatures=32
#   scripts/jmh-servlet.sh -- -jvmArgsAppend -Dservlet.sessions=10000
#
# Prerequisites: Linux, the Loom JDK selected (`sdk env`), and the project built (`mvn install`).
# The default rate: 300000. After --, JMH takes -p fraudKiB=<KiB> (the table of a fraud detector, a
# power of two, 1024 by default) and -p fraudFeatures=<n> (the records a check touches, 256 by
# default).

set -euo pipefail

BENCH=servlet
CLASS=ServletBench
PROPERTY=servlet
RATES=300000
source "$(dirname "$0")/lib/jmh-common.sh" "$@"
