#!/usr/bin/env bash
#
# Runs the carrier-affine hello example (io.github.pderop.looma.examples.hello.Main).
#
# A script rather than an exec-maven-plugin execution, on purpose: `--enable-preview` and
# `--enable-native-access` have to be on the command line of the JVM that runs the code, and
# Maven's own JVM does not have them. The
# scheduler itself -- two carriers -- is chosen in Main, through ActorSystem.Builder.carriers(...),
# so it needs no flag here.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

source "$SCRIPT_DIR/loom-jdk.sh"
CP=$("$SCRIPT_DIR/classpath.sh")

exec "$LOOM_JAVA" \
	--enable-preview \
	--enable-native-access=ALL-UNNAMED \
	-cp "$CP" io.github.pderop.looma.examples.hello.Main
