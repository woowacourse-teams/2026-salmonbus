#!/usr/bin/env bash
# ApplicationStart. 소스가 바뀐 서비스만 중지 후 다시 시작한다
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
claim_deploy

# worker 가 둘 겹치면 하루 호출 한도가 두 배로 나간다.
# restart 대신 stop 하고 완전히 멈춘 것을 본 뒤에 start 한다
stop_unit() {
  systemctl stop "$UNIT" || true
  local deadline=$((SECONDS + 60))
  while [ $SECONDS -lt $deadline ]; do
    if ! systemctl is-active --quiet "$UNIT" && ! pgrep -f "$JAR" >/dev/null 2>&1; then
      log "$UNIT 이 완전히 멈췄다"
      return 0
    fi
    sleep 1
  done
  log "$UNIT 이 60초 안에 안 멈췄다"
  return 1
}

# install.sh 가 적어 둔 것. 파일이 없으면 재시작하는 쪽으로 간다
changed="$(sed -n 's/^changed=//p' "$CHANGED" 2>/dev/null || echo yes)"
reason="$(sed -n 's/^reason=//p' "$CHANGED" 2>/dev/null || true)"
before="$(unit_pid)"

if [ "$changed" = "no" ]; then
  if systemctl is-active --quiet "$UNIT"; then
    log "$COMPONENT 는 재시작하지 않는다. 이유=${reason:-변경 없음} PID=$before 그대로"
    exit 0
  fi
  log "$COMPONENT 는 변경이 없는데 실행 중이 아니다. 시작한다"
  stop_unit
  systemctl start "$UNIT"
  log "$UNIT 시작했다. PID=$(unit_pid)"
  exit 0
fi

log "$COMPONENT 를 재시작한다. 이유=${reason:-소스 변경} PID=$before"
stop_unit
systemctl start "$UNIT"
log "$UNIT 시작했다. PID=$(unit_pid)"
