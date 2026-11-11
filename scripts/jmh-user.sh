#!/usr/bin/env bash
#
# Runs the user benchmark under JMH (examples/src/test, UserBench.java), to look at it with the JMH
# profilers. The backend must be running: start `scripts/jmh-user-start-backend.sh` first.
#
#   scripts/jmh-user.sh                          # both schedulers at 570000, no profiler
#   scripts/jmh-user.sh --profilers none,gc,perfnorm
#   scripts/jmh-user.sh --rates 550000,570000,590000 --schedulers jdk
#
# Prerequisites: Linux, the Loom JDK selected (`sdk env`), and the project built (`mvn install`).
# The default rate: 570000.

set -euo pipefail

BENCH=user
CLASS=UserBench
PROPERTY=bench
RATES=570000
source "$(dirname "$0")/lib/jmh-common.sh" "$@"
