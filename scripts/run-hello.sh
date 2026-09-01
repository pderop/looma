#!/usr/bin/env bash
#
# Runs the carrier-affine hello example (io.github.pderop.looma.examples.hello.Main).
#
# A script rather than an exec-maven-plugin execution, on purpose: the flags below have to be on
# the command line of the JVM that runs the code (`--enable-preview` and the scheduler SPI class
# are both read at JVM startup, and Maven's own JVM has neither), and the poms are deliberately
# kept to what building and testing needs.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

source "$SCRIPT_DIR/loom-jdk.sh"
CP=$("$SCRIPT_DIR/examples-classpath.sh")

exec "$LOOM_JAVA" \
	--enable-preview \
	--enable-native-access=ALL-UNNAMED \
	-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler \
	-Dio.netty.loom.schedulers=2 \
	-cp "$CP" io.github.pderop.looma.examples.hello.Main
