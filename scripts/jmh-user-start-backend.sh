#!/usr/bin/env bash
#
# Starts the backend of the user benchmark under JMH, warms it up, and keeps it running until
# Ctrl-C. Run it in its own tmux window, then run jmh-user.sh or jmh-pool.sh as many times as needed.
#
#   scripts/jmh-user-start-backend.sh
#   scripts/jmh-user-start-backend.sh --warmup 40 -- --rates 590000
#
# Prerequisites: Linux, the Loom JDK selected (`sdk env`), and the project built (`mvn install`).

set -euo pipefail

BENCH=user
MAIN=io.github.pderop.looma.examples.user.BackendServer
PROPERTY=bench
HEAP=()
source "$(dirname "$0")/lib/jmh-backend.sh" "$@"
