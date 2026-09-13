#!/usr/bin/env bash
# ValidateService. 여기서 실패하면 CodeDeploy 가 직전 판으로 되돌린다
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
# 같은 DEPLOYMENT_ID 라 그대로 통과한다. 실패했을 때 잠금을 풀려고 잡는다
claim_deploy

WAIT_SECONDS=90
NEW_DIGEST="$(manifest_value "$MANIFEST" sourceDigest)"
NEW_COMMIT="$(manifest_value "$MANIFEST" commit)"

# readiness 를 쓰지 마라. DB 가 끊겨도 200 UP 이 온다. 실측으로 확인했다.
# DB 까지 보는 것은 /actuator/health 쪽이고 끊기면 503 DOWN 이 온다
wait_for() {
  local what="$1" url="$2" want="$3" body
  # 시한을 검사마다 새로 잡는다. 하나로 두면 앞 검사가 늦게 끝났을 때
  # 뒤 검사가 시간을 못 받고 실패해서 멀쩡한 배포가 되돌아간다
  local deadline=$((SECONDS + WAIT_SECONDS))
  while [ $SECONDS -lt $deadline ]; do
    body="$(curl -fsS --max-time 5 "$url" 2>/dev/null || true)"
    case "$body" in
      *"$want"*) log "$what 확인"; return 0 ;;
    esac
    sleep 3
  done
  log "$what 이 시간 안에 안 됐다. 마지막 응답=$body"
  return 1
}

wait_for "$UNIT health"   "$ACTUATOR/health" '"status":"UP"'
# 지금 응답하는 것이 방금 올린 판이 맞나. 옛 프로세스가 살아 있으면 여기서 걸린다
wait_for "$UNIT 소스 지문" "$ACTUATOR/info"   "\"sourcedigest\":\"$NEW_DIGEST\""
wait_for "$UNIT component" "$ACTUATOR/info"   "\"component\":\"$COMPONENT\""

# 재부팅 뒤에도 올라와야 한다. install.sh 가 enable 을 부르는데 그게 실제로 먹었는지 본다
systemctl is-enabled --quiet "$UNIT" \
  || { log "$UNIT 이 enable 되어 있지 않다. 재부팅하면 안 올라온다"; exit 1; }
log "$UNIT enable 확인"

if [ -n "$SMOKE" ]; then
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$SMOKE")"
  [ "$code" = "200" ] || { log "클라이언트 API 가 $code 로 온다: $SMOKE"; exit 1; }
  log "클라이언트 API 200"
fi

release_deploy
log "배포 확인 끝. component=$COMPONENT commit=$NEW_COMMIT"
