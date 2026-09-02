#!/usr/bin/env bash
# ValidateService. 여기서 실패하면 CodeDeploy 가 직전 판으로 되돌린다
source "$(dirname "$0")/common.sh"

DEADLINE=$((SECONDS + 120))

# readiness 를 쓰지 마라. DB 가 끊겨도 200 UP 이 온다. 2026-09-02 실측이다.
# DB 까지 보는 것은 /actuator/health 쪽이고 끊기면 503 DOWN 이 온다
wait_healthy() {
  local name="$1" url="$2" body
  while [ $SECONDS -lt $DEADLINE ]; do
    body="$(curl -fsS --max-time 5 "$url" 2>/dev/null || true)"
    case "$body" in
      *'"status":"UP"'*) log "$name 정상"; return 0 ;;
    esac
    sleep 3
  done
  log "$name 이 시간 안에 안 떴다. 마지막 응답=$body"
  return 1
}

wait_healthy "$API_UNIT"    "$API_HEALTH"
wait_healthy "$WORKER_UNIT" "$WORKER_HEALTH"

commit="$(manifest_value "$CURRENT/release-manifest.txt" commit)"
log "배포 확인 끝. commit=$commit"
