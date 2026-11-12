#!/usr/bin/env bash
#
# Starts the backend of the servlet benchmark under JMH, warms it up, and keeps it running until
# Ctrl-C. Run it in its own tmux window, then run jmh-servlet.sh as many times as needed.
#
#   scripts/jmh-servlet-start-backend.sh
#   scripts/jmh-servlet-start-backend.sh --warmup 40 -- --rates 300000
#
# Prerequisites: Linux, the Loom JDK selected (`sdk env`), and the project built (`mvn install`).

set -euo pipefail

BENCH=servlet
MAIN=io.github.pderop.looma.examples.servlet.ServletBackend
PROPERTY=servlet
HEAP=(-Xms1g -Xmx1g -XX:+AlwaysPreTouch)
source "$(dirname "$0")/lib/jmh-backend.sh" "$@"
