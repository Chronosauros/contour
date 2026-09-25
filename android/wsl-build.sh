#!/usr/bin/env bash
# Build from Linux or WSL with the toolchain in toolchain.env (copy toolchain.env.example and edit it; the copy is
# gitignored). Plain ./gradlew works too - this wrapper only adds the optional flags below.
#
# If Android Studio on Windows shares the repo, it writes android/local.properties with a Windows sdk.dir (C:\...).
# AGP prefers that file over ANDROID_HOME, so a WSL build would fail; the wrapper parks the file for the duration of
# the build and puts it back afterwards.
#
# CONTOUR_BUILD_ROOT (optional) moves every build directory off the repo, e.g. when the repo sits on a slow mount.
# CONTOUR_SIGNING (optional, maintainer) signs the release APK, see app/build.gradle.kts.
#
# Usage (from anywhere):
#   android/wsl-build.sh :core:test
#   android/wsl-build.sh :app:assembleRelease
#   android/wsl-build.sh :app:assemblePerf
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$here/toolchain.env" ]]; then
  # shellcheck source=toolchain.env.example
  source "$here/toolchain.env"
else
  source "$here/toolchain.env.example"
fi
cd "$here"

parked=""
if [[ -f local.properties ]] && grep -qiE '^sdk\.dir=[A-Z]:' local.properties; then
  parked="local.properties.windows-parked"
  mv local.properties "$parked"
  trap 'mv -f "'"$parked"'" local.properties 2>/dev/null || true' EXIT
fi

flags=()
if [[ -n "${CONTOUR_BUILD_ROOT:-}" ]]; then
  flags+=(--project-cache-dir "$CONTOUR_BUILD_ROOT/project-cache"
          -Pcontour.buildRoot="$CONTOUR_BUILD_ROOT"
          -Pkotlin.project.persistent.dir="$CONTOUR_BUILD_ROOT/kotlin-persistent")
fi
[[ -n "${CONTOUR_SIGNING:-}" ]] && flags+=(-Pcontour.signing="$CONTOUR_SIGNING")
# no exec: the EXIT trap must run to put local.properties back
./gradlew "${flags[@]}" "$@"
