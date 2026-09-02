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

# api 와 worker 배포가 겹치지 않게 하는 잠금이다.
# flock 은 스크립트가 끝나면 풀려서 훅과 훅 사이를 못 잠근다. 그래서 디렉터리로 잠근다.
# mkdir 은 있으면 실패하는 하나짜리 동작이라 확인과 생성 사이가 안 벌어진다
MARKER="$ROOT/.deploying"
MARKER_STALE_SECONDS=900
# CodeDeploy 가 훅마다 같은 값을 준다. 그래서 한 배포의 네 훅이 같은 잠금을 쓴다
DEPLOY_ID="${DEPLOYMENT_ID:-$COMPONENT-manual}"

claim_deploy() {
  mkdir -p "$ROOT"
  local tries=0 who id when age
  while [ "$tries" -lt 3 ]; do
    tries=$((tries + 1))
    if mkdir "$MARKER" 2>/dev/null; then
      printf '%s %s %s\n' "$COMPONENT" "$DEPLOY_ID" "$(date +%s)" > "$MARKER/owner"
      # 이 훅이 실패로 끝나면 잠금을 푼다. 성공이면 다음 훅이 쓰게 남긴다.
      # 안 그러면 실패한 배포가 900초 동안 다른 서비스 배포까지 막는다
      trap '_release_on_failure "$?"' EXIT
      return 0
    fi
    who=""; id=""; when=""
    read -r who id when < "$MARKER/owner" 2>/dev/null || true
    # 같은 배포의 다음 훅이면 그대로 쓴다
    [ "$id" = "$DEPLOY_ID" ] && return 0
    # 같은 서비스의 다른 배포면 넘겨받는다.
    # 실패한 배포는 validate 까지 못 가서 잠금을 쥔 채 끝난다. 그대로 두면
    # CodeDeploy 가 바로 거는 롤백이 자기가 남긴 잠금에 막힌다.
    # 한 배포 그룹에 배포가 둘 동시에 안 도는 것은 CodeDeploy 가 지킨다
    if [ "$who" = "$COMPONENT" ]; then
      log "$COMPONENT 의 앞 배포가 남긴 잠금을 넘겨받는다"
      rm -rf "$MARKER"
      continue
    fi
    # 나이는 owner 파일이 아니라 잠금 디렉터리가 만들어진 시각으로 잰다.
    # mkdir 과 owner 쓰기 사이의 찰나에 읽으면 owner 가 비는데,
    # 그것을 아주 오래된 잠금으로 세면 남이 막 잡은 것을 뺏는다
    age=$(( $(date +%s) - $(stat -c %Y "$MARKER" 2>/dev/null || date +%s) ))
    if [ "$age" -ge "$MARKER_STALE_SECONDS" ]; then
      log "${who:-알 수 없는} 배포 잠금이 ${age}초째 남아 있다. 버리고 다시 잡는다"
      rm -rf "$MARKER"
      continue
    fi
    log "${who:-다른} 배포가 ${age}초째 도는 중이다. 겹치지 않게 여기서 멈춘다"
    exit 1
  done
  log "배포 잠금을 못 잡았다"
  exit 1
}

_release_on_failure() {
  [ "${1:-0}" -eq 0 ] || release_deploy
  return 0
}

# 내가 잡은 잠금만 푼다. 남의 것을 풀면 겹침을 막는 뜻이 없어진다
release_deploy() {
  local id=""
  read -r _ id _ < "$MARKER/owner" 2>/dev/null || true
  [ "$id" = "$DEPLOY_ID" ] && rm -rf "$MARKER"
  return 0
}

# 판 폴더 경로를 만든다. 이 값이 root 권한 삭제와 이동에 쓰여서 형식을 먼저 본다.
# 배포판 목록을 손대면 releases 밖을 건드릴 수 있는 곳이다
release_path() {
  local digest="$1" base resolved
  [ "${#digest}" -eq 64 ] || { log "sourceDigest 가 64자가 아니다 (${#digest}자)"; exit 1; }
  case "$digest" in
    *[!0-9a-f]*) log "sourceDigest 에 16진수가 아닌 글자가 있다"; exit 1 ;;
  esac
  mkdir -p "$RELEASES"
  base="$(cd "$RELEASES" && pwd -P)"
  resolved="$base/$digest"
  # 형식 검사를 통과해도 정규화한 경로를 한 번 더 본다. 둘 중 하나만으로는 약하다
  case "$resolved" in
    "$base"/?*) ;;
    *) log "판 경로가 releases 밖을 가리킨다"; exit 1 ;;
  esac
  printf '%s' "$resolved"
}
