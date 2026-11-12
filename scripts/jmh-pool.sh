#!/usr/bin/env bash
#
# Runs the pool benchmark under JMH (examples/src/test, PoolBench.java): the connection pools of the
# user benchmark, compared. It uses the backend of the user benchmark: start
# `scripts/jmh-user-start-backend.sh` first.
#
#   scripts/jmh-pool.sh                          # both schedulers at 450000, the lbd-lifo pool
#   scripts/jmh-pool.sh --pools lbd-lifo,fair-lifo,fair-fifo
#
# Prerequisites: Linux, the Loom JDK selected (`sdk env`), and the project built (`mvn install`).
# The default rate: 450000. The size of a pool: -p connections=<n>, after --.
#
#   --pools <list>          lbd-lifo, abq, abq-fair, fair-lifo, fair-fifo. Default: lbd-lifo.

set -euo pipefail

BENCH=pool
CLASS=PoolBench
PROPERTY=bench
RATES=450000
POOLS=lbd-lifo
source "$(dirname "$0")/lib/jmh-common.sh" "$@"
