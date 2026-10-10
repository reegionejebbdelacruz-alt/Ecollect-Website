#!/bin/sh
# EcoCollect launcher (Mac/Linux) — requires Java 17+
cd "$(dirname "$0")"
exec java Main.java "$@"
