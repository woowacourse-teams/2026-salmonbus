#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$REPO/backend/build/shared-dev"
TOOLS="$REPO/dev/server/tools/build/libs/shared-dev-tools.jar"

dev_tools() {
  env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u CLASSPATH \
    java -Xms16m -Xmx64m -jar "$TOOLS" "$@"
}

check() {
  [[ ${CODEBUILD_PROJECT_ARN:-} =~ ^arn:aws:codebuild:ap-northeast-2:[0-9]{12}:project/salmonbus-backend-dev-build$ ]]
  [[ ${CODEBUILD_INITIATOR:-} == codepipeline/salmonbus-backend-dev-cd ]]
  [ "$(id -u)" = 0 ] || { echo 'DEV_BUILD_REQUIRES_ROOT' >&2; exit 1; }
  case "$(uname -s):$(uname -m)" in Linux:aarch64|Linux:arm64) ;; *) echo 'DEV_BUILD_REQUIRES_LINUX_ARM64' >&2; exit 1 ;; esac
  for tool in java docker unzip sha256sum find grep setsid; do command -v "$tool" >/dev/null; done
  java -version 2>&1 | grep -Eq 'version "21[.]'
  case "$(docker info --format '{{.Architecture}}')" in aarch64|arm64) ;; *) echo 'DEV_DOCKER_REQUIRES_ARM64' >&2; exit 1 ;; esac
  test ! -d "$REPO/backend/src"
}

clean_cache_controls() {
  local cache="${GRADLE_USER_HOME:?}/caches/modules-2"
  if [ -d "$cache" ]; then
    find -H "$cache" \( -name '*.lock' -o -name 'gc.properties' \) -delete
  fi
}

case "${1:-}" in
  check) check ;;
  prepare-tools)
    check
    "$REPO/backend/gradlew" -p "$REPO/dev/server/tools" clean test serverTest jar --no-daemon --max-workers=2 --console=plain
    dev_tools check-build --repo "$REPO"
    ;;
  build)
    check
    dev_tools check-build --repo "$REPO"
    cd "$REPO/backend"
    ./gradlew clean build --no-daemon --max-workers=2 --console=plain
    ./gradlew --init-script ../dev/server/data.init :worker-app:sharedDevDataTest :worker-app:sharedDevDataJar \
      --no-daemon --max-workers=2 --console=plain
    cd "$REPO/dev/wiremock-extension"
    ./gradlew clean test jar --no-daemon --max-workers=2 --console=plain
    dev_tools image-context --repo "$REPO" --output "$WORK/image-context"
    docker build --platform linux/arm64 --tag "salmonbus-gbis-replay:${CODEBUILD_RESOLVED_SOURCE_VERSION}" "$WORK/image-context"
    docker image save --output "$WORK/wiremock-image.tar" "salmonbus-gbis-replay:${CODEBUILD_RESOLVED_SOURCE_VERSION}"
    ;;
  package)
    clean_cache_controls
    test "${CODEBUILD_BUILD_SUCCEEDING:-0}" = 1
    dev_tools package --repo "$REPO" --codebuild --wiremock-archive "$WORK/wiremock-image.tar" \
      --output "$WORK/revision"
    ;;
  *) echo 'Usage: build.sh check|prepare-tools|build|package' >&2; exit 64 ;;
esac
