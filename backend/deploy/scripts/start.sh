#!/usr/bin/env bash
# ApplicationStart. 소스가 바뀐 서비스만 내렸다 올린다
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
claim_deploy

# worker 는 두 벌이 겹치면 하루 호출 한도가 두 배로 나간다.
# restart 대신 stop 하고 완전히 내려간 것을 본 뒤에 start 한다
stop_unit() {
  systemctl stop "$UNIT" || true
  local deadline=$((SECONDS + 60))
  while [ $SECONDS -lt $deadline ]; do
    if ! systemctl is-active --quiet "$UNIT" && ! pgrep -f "$JAR" >/dev/null 2>&1; then
      log "$UNIT 이 완전히 내려갔다"
      return 0
    fi
    sleep 1
  done
  log "$UNIT 이 60초 안에 안 내려갔다"
  return 1
}

changed="$(sed -n 's/^changed=//p' "$CHANGED" 2>/dev/null || echo yes)"

if [ "$changed" = "no" ]; then
  log "$COMPONENT 는 소스가 그대로라 재시작하지 않는다"
  if ! systemctl is-active --quiet "$UNIT"; then
    log "그런데 돌고 있지 않다. 올린다"
    stop_unit
    systemctl start "$UNIT"
  fi
  exit 0
fi

stop_unit
systemctl start "$UNIT"
log "$UNIT 올렸다"
