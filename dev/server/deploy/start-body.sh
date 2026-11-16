#!/usr/bin/env bash
# 개발 환경 ApplicationStart. common.sh의 verify-target과 systemd preflight가 전환 순서를 검사한다.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
claim_deploy

# unit_pid 는 UNIT 을 읽는다
pid_of() { UNIT="$1" unit_pid; }

# worker 가 둘 겹치면 하루 호출 한도가 두 배로 나간다.
# restart 대신 stop 하고 완전히 멈춘 것을 본 뒤에 start 한다
stop_unit() {
  local unit="$1" jar="$2"
  systemctl stop "$unit" || true
  local deadline=$((SECONDS + 60))
  while [ $SECONDS -lt $deadline ]; do
    if ! systemctl is-active --quiet "$unit" && ! pgrep -f "$jar" >/dev/null 2>&1; then
      log "$unit 이 완전히 멈췄다"
      return 0
    fi
    sleep 1
  done
  log "$unit 이 60초 안에 안 멈췄다"
  return 1
}

# install.sh 가 적어 둔 것. 파일이 없으면 재시작하는 쪽으로 간다
changed="$(sed -n 's/^changed=//p' "$CHANGED" 2>/dev/null || echo yes)"
reason="$(sed -n 's/^reason=//p' "$CHANGED" 2>/dev/null || true)"

# 변경이 없고 이미 떠 있으면 그대로 둔다. 그 밖에는 멈춘 것을 확인하고 띄운다
restart_if_needed() {
  local unit="$1" jar="$2" before
  before="$(pid_of "$unit")"
  if [ "$changed" = "no" ]; then
    if systemctl is-active --quiet "$unit"; then
      log "$unit 는 재시작하지 않는다. 이유=${reason:-변경 없음} PID=$before 그대로"
      return 0
    fi
    log "$unit 는 변경이 없는데 실행 중이 아니다. 시작한다"
  else
    log "$unit 를 재시작한다. 이유=${reason:-소스 변경} PID=$before"
  fi
  stop_unit "$unit" "$jar"
  systemctl start "$unit"
  log "$unit 시작했다. PID=$(pid_of "$unit")"
}

restart_if_needed "$UNIT" "$JAR"
# worker 묶음에는 이관 앱이 함께 실려 있다. worker 와 같은 규칙으로 멈추고 띄운다
if [ "$COMPONENT" = worker ]; then
  restart_if_needed salmonbus-archive "$CURRENT/jars/archive-app.jar"
fi
