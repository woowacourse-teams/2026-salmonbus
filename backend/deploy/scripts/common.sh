#!/usr/bin/env bash
# 훅 스크립트가 함께 쓰는 코드다. api와 worker가 같은 파일을 쓴다.
# 배포 메타데이터(release-manifest.txt)의 component로 api와 worker를 구분한다.
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
    ACTUATOR="http://127.0.0.1:8082/actuator"    # 관리 포트를 클라이언트 포트와 분리했다
    READINESS="http://127.0.0.1:8080/readyz"      # 클라이언트 포트가 트래픽 받을 상태인지 본다
    ;;
  worker)
    CLIENT_PORT=8081
    ACTUATOR="http://127.0.0.1:8081/actuator"    # worker 는 이미 루프백에 묶여 있다
    READINESS=""                                   # worker 는 포트가 8081 하나라 위 health 검사가 같은 포트를 본다
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

# 유닛의 MainPID. 안 돌고 있으면 0. 재시작이 정말 됐는지 전후로 견주려고 훅마다 찍는다
unit_pid() { systemctl show -p MainPID --value "$UNIT" 2>/dev/null || echo 0; }

# api 와 worker 배포가 겹치지 않게 하는 잠금이다.
# flock 은 스크립트가 끝나면 풀려서 훅과 훅 사이를 못 잠근다. 그래서 디렉터리로 잠근다.
# mkdir은 디렉터리가 이미 있으면 실패한다. 확인과 생성을 한 동작으로 처리해 사이에 틈이 없다.
MARKER="$ROOT/.deploying"
MARKER_STALE_SECONDS=900    # 마지막 훅이 시작한 뒤 이만큼 조용하면 죽은 배포로 본다. 훅 상한 300초의 세 배
# 잠금은 있는데 owner를 못 읽었다면 다른 배포가 막 잡고 아직 안 썼거나 잡았다 방금 놓은 순간이다.
# 한 번 보고 단정하지 않고 잠깐 자고 다시 본다
MARKER_CLAIM_TRIES=3
MARKER_CLAIM_WAIT_SECONDS=1
# CodeDeploy 가 훅마다 같은 값을 준다. 그래서 한 배포의 네 훅이 같은 잠금을 쓴다
DEPLOY_ID="${DEPLOYMENT_ID:-$COMPONENT-manual}"

claim_deploy() {
  mkdir -p "$ROOT"
  local tries=0 who id when age
  while [ "$tries" -lt "$MARKER_CLAIM_TRIES" ]; do
    tries=$((tries + 1))
    if mkdir "$MARKER" 2>/dev/null; then
      printf '%s %s %s\n' "$COMPONENT" "$DEPLOY_ID" "$(date +%s)" > "$MARKER/owner"
      # 이 훅이 실패로 끝나면 잠금을 푼다. 성공이면 다음 훅이 쓰게 남긴다.
      # 안 그러면 실패한 배포가 900초 동안 다른 서비스 배포까지 막는다
      trap '_release_on_failure "$?"' EXIT
      return 0
    fi
    who=""; id=""; when=""
    # 2>/dev/null 을 입력 리디렉션보다 앞에 둔다. 뒤에 두면 owner 가 없을 때
    # 셸이 내는 리디렉션 오류를 못 막아서 훅 로그에 그대로 남는다
    read -r who id when 2>/dev/null < "$MARKER/owner" || true
    # 같은 배포의 다음 훅은 기존 잠금을 쓴다. 훅마다 새 셸에서 실행되므로 실패 정리도 다시 등록한다.
    # touch 로 "아직 살아 있다"는 표시를 남긴다. 아래 나이 계산이 이 시각을 본다
    if [ "$id" = "$DEPLOY_ID" ]; then
      touch "$MARKER" 2>/dev/null || true
      trap '_release_on_failure "$?"' EXIT
      return 0
    fi
    # 같은 서비스의 다른 배포면 넘겨받는다.
    # 실패한 배포는 validate 까지 못 가서 잠금을 남긴 채 끝난다. 그대로 두면
    # CodeDeploy 가 바로 거는 롤백이 자기가 남긴 잠금에 막힌다.
    # CodeDeploy가 한 배포 그룹에서 배포 둘이 동시에 진행되지 않도록 한다.
    if [ "$who" = "$COMPONENT" ]; then
      log "$COMPONENT 의 앞 배포가 남긴 잠금을 넘겨받는다"
      rm -rf "$MARKER"
      continue
    fi
    # 나이는 잠금 디렉터리의 mtime 으로 잰다. 잡을 때 생기고 훅이 시작할 때마다 touch 로 갱신되니까
    # "마지막 훅이 시작한 뒤 지난 시간"이다. 훅 하나는 길어야 300초라 900초 조용하면 죽은 배포다.
    # 살아 있는 배포는 15분을 넘겨도 그 사이 훅이 갱신해서 안 뺏긴다.
    # owner 파일이 아니라 디렉터리를 보는 이유: mkdir 과 owner 쓰기 사이에 읽으면 owner 가 비어 있는데
    # 그걸 아주 오래된 잠금으로 치면 다른 배포가 막 잡은 잠금을 뺏는다
    age=$(( $(date +%s) - $(stat -c %Y "$MARKER" 2>/dev/null || date +%s) ))
    if [ "$age" -ge "$MARKER_STALE_SECONDS" ]; then
      log "${who:-알 수 없는} 배포 잠금이 ${age}초째 남아 있다. 버리고 다시 잡는다"
      rm -rf "$MARKER"
      continue
    fi
    # 누가 잡았는지 못 읽었다. 다른 배포가 막 잡고 owner 를 아직 안 썼거나 잡았다 방금 놓았다.
    # 여기서 멈추면 다른 배포는 이미 지나갔는데 이 배포만 막힌다
    if [ -z "$who" ]; then
      # 마지막 차례면 더 볼 기회가 없다. 여기서 자면 배포만 1초 늦는다
      [ "$tries" -lt "$MARKER_CLAIM_TRIES" ] || break
      log "잠금은 있는데 누가 잡았는지 안 적혀 있다. ${MARKER_CLAIM_WAIT_SECONDS}초 뒤 다시 본다 (${tries}/${MARKER_CLAIM_TRIES})"
      sleep "$MARKER_CLAIM_WAIT_SECONDS"
      continue
    fi
    # 누가 잡았는지 읽었다. 진짜로 다른 서비스의 배포가 진행 중이라 다시 봐도 소용없다
    log "$who 배포가 ${age}초째 진행 중이다. 겹치지 않게 여기서 멈춘다"
    exit 1
  done
  log "배포 잠금을 ${MARKER_CLAIM_TRIES}번 잡아 봤는데 못 잡았다"
  exit 1
}

_release_on_failure() {
  [ "${1:-0}" -eq 0 ] || release_deploy
  return 0
}

# 이 배포가 잡은 잠금만 푼다. 다른 배포의 잠금을 풀면 겹침을 막을 수 없다
release_deploy() {
  local id=""
  read -r _ id _ 2>/dev/null < "$MARKER/owner" || true
  [ "$id" = "$DEPLOY_ID" ] && rm -rf "$MARKER"
  return 0
}

# 배포 버전 디렉터리 경로를 만든다. 이 값이 root 권한 삭제와 이동에 쓰여서 형식을 먼저 본다.
# 배포 메타데이터를 변조하면 releases 밖을 지우거나 덮어쓸 수 있어서 여기서 막는다
release_path() {
  local digest="$1" base resolved
  [ "${#digest}" -eq 64 ] || { log "sourceDigest 가 64자가 아니다 (${#digest}자)"; exit 1; }
  case "$digest" in
    *[!0-9a-f]*) log "sourceDigest 에 16진수가 아닌 글자가 있다"; exit 1 ;;
  esac
  mkdir -p "$RELEASES"
  base="$(cd "$RELEASES" && pwd -P)"
  resolved="$base/$digest"
  # 형식 검사를 통과해도 정규화한 경로를 한 번 더 본다. 한 가지 검사만으로는 막기 어렵다
  case "$resolved" in
    "$base"/?*) ;;
    *) log "배포 버전 경로가 releases 밖을 가리킨다"; exit 1 ;;
  esac
  printf '%s' "$resolved"
}

# DB 에 새 연결을 하나 열어 본다. 앱이 쥔 풀을 안 거치는 별개 연결이다.
# 9월 14일에 health 는 죽었는데 psql 은 바로 붙었다. DB 가 죽은 건지 앱 풀만 굳은 건지 이걸로 가른다.
# 로그에만 남기고 배포는 안 막는다. 비밀번호는 PGPASSWORD 로 psql 에만 준다. 명령줄에 실으면 ps 에 보인다
DB_PROBE_SECONDS=15
db_probe() {
  local label="$1" url user pass host port name t0 ms out rc=0
  command -v psql >/dev/null 2>&1 || { log "DB 진단($label) 생략: psql 이 없다"; return 0; }
  url="$(sed -n 's/^DB_URL=//p' "$ENV_FILE" 2>/dev/null | head -1 || true)"
  user="$(sed -n 's/^DB_USERNAME=//p' "$ENV_FILE" 2>/dev/null | head -1 || true)"
  pass="$(sed -n 's/^DB_PASSWORD=//p' "$ENV_FILE" 2>/dev/null | head -1 || true)"
  # jdbc:postgresql://호스트:포트/디비?옵션 을 psql 이 받는 모양으로 쪼갠다. 포트가 없으면 5432
  host="${url#jdbc:postgresql://}"; host="${host%%/*}"
  name="${url#jdbc:postgresql://*/}"; name="${name%%\?*}"
  port="${host##*:}"; [ "$port" != "$host" ] || port=5432; host="${host%%:*}"
  if [ -z "$host" ] || [ -z "$name" ] || [ -z "$user" ]; then
    log "DB 진단($label) 생략: $ENV_FILE 의 DB_URL 을 못 읽었다"
    return 0
  fi
  t0=$(date +%s%3N)
  out="$(PGPASSWORD="$pass" timeout "$DB_PROBE_SECONDS" psql \
    "host=$host port=$port dbname=$name user=$user sslmode=require connect_timeout=10" \
    -Atqc 'select 1' 2>&1)" || rc=$?
  ms=$(( $(date +%s%3N) - t0 ))
  if [ "$rc" -eq 0 ]; then
    log "DB 진단($label): 새 연결 성공. ${ms}ms host=$host db=$name"
    return 0
  fi
  # 오류 문구에 비밀번호가 섞일 일은 없지만 한 번 더 지운다
  [ -z "$pass" ] || out="${out//"$pass"/***}"
  out="$(printf '%s' "$out" | head -1 | cut -c1-200)"
  if [ "$rc" -eq 124 ]; then
    log "DB 진단($label): 새 연결 실패. ${DB_PROBE_SECONDS}초 안에 응답이 없다 host=$host db=$name. 배포는 계속한다"
  else
    log "DB 진단($label): 새 연결 실패. ${ms}ms rc=$rc host=$host db=$name: ${out:-출력 없음}. 배포는 계속한다"
  fi
  return 0
}
