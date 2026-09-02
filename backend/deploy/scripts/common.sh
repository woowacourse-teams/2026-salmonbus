#!/usr/bin/env bash
# 훅 스크립트가 같이 쓰는 것들.
# set -x 를 쓰지 마라. 훅 로그가 인스턴스에 남고 env 값이 찍히면 파일 권한이 소용없다
set -euo pipefail

ROOT=/opt/salmonbus
STAGING="$ROOT/staging"
RELEASES="$ROOT/releases"
CURRENT="$ROOT/current"
PREVIOUS="$ROOT/previous"
LOCK="$ROOT/.deploy.lock"

SERVICE_USER=salmonbus
API_UNIT=salmonbus-api
WORKER_UNIT=salmonbus-worker
API_HEALTH="http://127.0.0.1:8080/actuator/health"
WORKER_HEALTH="http://127.0.0.1:8081/actuator/health"

log() { echo "[$(date -u +%H:%M:%S)] $*"; }

# 훅 하나가 도는 동안 다른 훅이 끼어들지 못하게 한다.
# 훅마다 프로세스가 달라서 스크립트가 끝나면 풀린다. 훅과 훅 사이는 안 잠근다.
# 배포 둘이 겹치는 것은 CodeDeploy 가 배포 그룹마다 하나씩 돌려서 막는다
take_lock() {
  # 첫 배포에는 이 디렉터리가 아예 없다. 잠금 파일을 열기 전에 만든다
  mkdir -p "$ROOT"
  exec 9>"$LOCK"
  flock -n 9 || { log "다른 배포가 도는 중이다"; exit 1; }
}

# manifest 에서 값 하나를 읽는다. 없으면 빈 문자열
manifest_value() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  awk -F= -v k="$key" '$1==k {print $2}' "$file"
}
