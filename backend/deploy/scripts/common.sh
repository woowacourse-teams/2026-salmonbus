#!/usr/bin/env bash
# 훅 스크립트가 같이 쓰는 것들. api 와 worker 가 같은 파일을 쓴다.
# 어느 쪽인지는 배포판 목록의 component 로 갈린다.
#
# set -x 를 쓰지 마라. 훅 로그가 인스턴스에 남고 env 값이 찍히면 파일 권한이 소용없다
set -euo pipefail

REVISION="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$REVISION/release-manifest.txt"

log() { echo "[$(date -u +%H:%M:%S)] $*"; }

manifest_value() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  awk -F= -v k="$key" '$1==k {print $2}' "$file"
}

COMPONENT="$(manifest_value "$MANIFEST" component)"
case "$COMPONENT" in
  api)
    CLIENT_PORT=8080
    ACTUATOR="http://127.0.0.1:8082/actuator"    # 관리 포트를 클라이언트 포트와 갈랐다
    SMOKE="http://127.0.0.1:8080/api/v1/routes"
    ;;
  worker)
    CLIENT_PORT=8081
    ACTUATOR="http://127.0.0.1:8081/actuator"    # worker 는 이미 루프백에 묶여 있다
    SMOKE=""
    ;;
  *)
    echo "release-manifest.txt 의 component 를 못 읽었다: '${COMPONENT}'" >&2
    exit 1
    ;;
esac

ROOT=/opt/salmonbus
BASE="$ROOT/$COMPONENT"
STAGING="$BASE/staging"
RELEASES="$BASE/releases"
CURRENT="$BASE/current"
PREVIOUS="$BASE/previous"
CHANGED="$BASE/.changed"

SERVICE_USER=salmonbus
UNIT="salmonbus-$COMPONENT"
ENV_FILE="/etc/salmonbus/$COMPONENT.env"
MODEL_DIRECTORY=/var/lib/salmonbus/model/current
JAR="$CURRENT/jars/$COMPONENT-app.jar"

# api 와 worker 배포가 겹치지 않게 하는 표시다.
# flock 은 스크립트가 끝나면 풀려서 훅과 훅 사이를 못 잠근다. 그래서 파일로 남긴다
MARKER="$ROOT/.deploying"
MARKER_STALE_SECONDS=900

claim_deploy() {
  mkdir -p "$ROOT"
  if [ -f "$MARKER" ]; then
    local who age
    who="$(awk '{print $1}' "$MARKER")"
    age=$(( $(date +%s) - $(awk '{print $2}' "$MARKER") ))
    if [ "$who" != "$COMPONENT" ] && [ "$age" -lt "$MARKER_STALE_SECONDS" ]; then
      log "$who 배포가 ${age}초째 도는 중이다. 겹치지 않게 여기서 멈춘다"
      exit 1
    fi
  fi
  printf '%s %s\n' "$COMPONENT" "$(date +%s)" > "$MARKER"
}

release_deploy() { rm -f "$MARKER"; }
