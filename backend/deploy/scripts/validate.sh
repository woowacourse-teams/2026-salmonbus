#!/usr/bin/env bash
# ValidateService. 여기서 실패하면 CodeDeploy 가 직전 배포 버전으로 되돌린다.
#
# 순서대로 다섯을 본다. 하나라도 실패하면 거기서 끝낸다.
#   health        관리 포트 /actuator/health 가 UP 인가. DB 연결까지 본다
#   sourcedigest  /actuator/info 가 방금 놓은 배포 버전을 답하는가. 옛 프로세스가 살아 있으면 여기서 걸린다
#   component     /actuator/info 의 component 가 이 배포 그룹의 것인가
#   enable        재부팅 뒤에도 자동 시작하는가
#   readyz        api 만. 클라이언트 포트 8080 이 트래픽 받을 상태인가. 업무 데이터는 안 읽는다
#
# 검사마다 시도 횟수·HTTP 상태·curl 종료 코드·경과 시간을 남기고 끝에 한 줄로 요약한다.
# 응답 본문은 통째로 안 찍는다. status 와 sourcedigest·component 값만 남긴다
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
# 같은 DEPLOYMENT_ID 라 그대로 통과한다. 실패했을 때 잠금을 풀려고 잡는다
claim_deploy

# 시간 상한 세 겹. 전체 상한은 appspec 의 ValidateService timeout(300초)보다 짧아야 한다.
# CodeDeploy 가 먼저 훅을 죽이면 요약 로그가 안 남고 잠금도 못 푼다.
# 환경변수는 리허설이 실패 시나리오를 몇 초로 줄여 돌리는 데 쓴다. CodeDeploy 환경에는 없다
TOTAL_SECONDS="${VALIDATE_TOTAL_SECONDS:-240}"      # 검증 전체
CHECK_SECONDS="${VALIDATE_CHECK_SECONDS:-90}"       # 검사 하나
REQUEST_SECONDS="${VALIDATE_REQUEST_SECONDS:-5}"    # 요청 하나
RETRY_SECONDS="${VALIDATE_RETRY_SECONDS:-3}"        # 다시 묻기 전 대기
STARTED=$SECONDS

NEW_DIGEST="$(manifest_value "$MANIFEST" sourceDigest)"
NEW_COMMIT="$(manifest_value "$MANIFEST" commit)"

elapsed() { echo $((SECONDS - STARTED)); }
remaining() { local r=$((TOTAL_SECONDS - SECONDS + STARTED)); [ "$r" -gt 0 ] && echo "$r" || echo 0; }
min() { [ "$1" -lt "$2" ] && echo "$1" || echo "$2"; }

# 실패 요약. exit 1 이면 claim_deploy 가 건 trap 이 잠금을 푼다
fail_validate() {
  log "배포 검증 실패. 단계=$1 사유=$2 경과=$(elapsed)초 종료 코드=1"
  exit 1
}

# JSON 응답에서 키 하나의 값만 뽑는다. 본문 전체를 로그에 안 실으려고 쓴다
field_of() {
  printf '%s' "$2" | grep -oE "\"$1\":\"[^\"]*\"" | head -1 | sed 's/^"[^"]*":"//; s/"$//' || true
}

# curl 종료 코드를 말로 푼다
curl_reason() {
  case "$1" in
    7)  echo "연결 실패(curl=7)" ;;
    28) echo "요청 시간 초과(curl=28)" ;;
    *)  echo "curl 오류(curl=$1)" ;;
  esac
}

# 검사 하나. 원하는 문자열이 본문에 올 때까지 다시 묻는다.
# 시한은 검사 상한과 전체 잔여 중 짧은 쪽이다. 요청과 대기도 남은 시간을 넘기지 않는다
wait_for() {
  local stage="$1" url="$2" want="$3" key="$4"
  local budget deadline left max_time out why
  local attempt=0 started=$SECONDS rc=0 code=000 body="" seen=""
  budget="$(min "$CHECK_SECONDS" "$(remaining)")"
  [ "$budget" -gt 0 ] || fail_validate "$stage" "전체 ${TOTAL_SECONDS}초를 앞 검사가 다 썼다"
  deadline=$((SECONDS + budget))
  while :; do
    left=$((deadline - SECONDS))
    [ "$left" -gt 0 ] || break
    attempt=$((attempt + 1))
    max_time="$(min "$REQUEST_SECONDS" "$left")"
    rc=0
    # -f 를 안 쓴다. 503 도 응답이라 상태 코드를 남겨야 한다. -w 가 본문 뒤에 줄바꿈과 상태 코드를 붙인다
    out="$(curl -s -w '\n%{http_code}' --max-time "$max_time" "$url" 2>/dev/null)" || rc=$?
    case "$out" in
      *$'\n'*) code="${out##*$'\n'}"; body="${out%$'\n'*}" ;;
      *)       code=000; body="" ;;
    esac
    seen="$(field_of "$key" "$body")"
    if [ "${code:0:1}" = "2" ]; then
      case "$body" in
        *"$want"*)
          log "$stage 확인. 시도=$attempt 소요=$((SECONDS - started))초 http=$code"
          return 0 ;;
      esac
    fi
    log "$stage 시도 $attempt: http=$code curl=$rc${seen:+ $key=$seen} 경과=$((SECONDS - started))초"
    left=$((deadline - SECONDS))
    [ "$left" -gt 0 ] || break
    sleep "$(min "$RETRY_SECONDS" "$left")"
  done
  if [ "$attempt" -eq 0 ]; then why="한 번도 못 물었다"
  elif [ "$rc" -ne 0 ]; then why="$(curl_reason "$rc")"
  elif [ "${code:0:1}" != "2" ]; then why="http=$code${seen:+ $key=$seen}"
  else why="응답이 다르다${seen:+ ($key=$seen)}"
  fi
  if [ "$budget" -lt "$CHECK_SECONDS" ]; then
    fail_validate "$stage" "시간 초과(전체 ${TOTAL_SECONDS}초 중 남은 ${budget}초) 시도=$attempt 마지막=$why"
  fi
  fail_validate "$stage" "시간 초과(검사 상한 ${CHECK_SECONDS}초) 시도=$attempt 마지막=$why"
}

# DB 까지 보는 것은 /actuator/health 다. 끊기면 503 DOWN 이 온다.
# /actuator/health/readiness 는 DB 가 끊겨도 200 UP 이라 여기엔 못 쓴다. 실측으로 확인했다
wait_for health       "$ACTUATOR/health" '"status":"UP"'                    status
# 지금 응답하는 것이 방금 놓은 배포 버전이 맞나. 옛 프로세스가 살아 있으면 여기서 걸린다
wait_for sourcedigest "$ACTUATOR/info"   "\"sourcedigest\":\"$NEW_DIGEST\"" sourcedigest
wait_for component    "$ACTUATOR/info"   "\"component\":\"$COMPONENT\""     component

# 재부팅 뒤에도 올라와야 한다. install.sh 가 enable 을 부르는데 그게 실제로 먹었는지 본다
if systemctl is-enabled --quiet "$UNIT"; then
  log "enable 확인. 경과=$(elapsed)초"
else
  fail_validate enable "$UNIT 이 enable 돼 있지 않다. 재부팅하면 안 올라온다"
fi

# 업무 API 로 검증하지 않는다. 노선 데이터와 모델 상태에 따라 답이 달라져서
# 데이터 사정으로 멀쩡한 배포가 되돌아간다. 8080 이 트래픽 받을 상태인지만 /readyz 로 본다
if [ -n "$READINESS" ]; then
  wait_for readyz "$READINESS" '"status":"UP"' status
fi

release_deploy
log "배포 검증 끝. component=$COMPONENT commit=$NEW_COMMIT 경과=$(elapsed)초 종료 코드=0"
