#!/bin/sh
set -eu
# Requires a JDK 21, Android SDK 36 and optional HAIL_SIGNING_* environment.
cd "$(dirname "$0")/.."
exec sh ./gradlew :app:testDebugUnitTest :app:assembleRelease -PhailOriginalPackage=true --no-daemon --max-workers=2 "$@"
