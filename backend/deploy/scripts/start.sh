#!/usr/bin/env bash
# ApplicationStart. 바뀐 서비스만 내렸다 올린다
source "$(dirname "$0")/common.sh"
take_lock

changed="$(sed -n 's/^changed=//p' "$CURRENT/.changed" 2>/dev/null || true)"

restart_one() {
  local unit="$1"
  # restart 는 내린 뒤에 올린다. worker 가 두 벌 겹치는 구간이 안 생긴다
  log "$unit 재시작"
  systemctl restart "$unit"
}

case " $changed " in
  *" api "*)    restart_one "$API_UNIT" ;;
  *)            log "$API_UNIT 은 안 바뀌었다. 그대로 둔다"
                systemctl is-active --quiet "$API_UNIT" || restart_one "$API_UNIT" ;;
esac

case " $changed " in
  *" worker "*) restart_one "$WORKER_UNIT" ;;
  *)            log "$WORKER_UNIT 은 안 바뀌었다. 그대로 둔다"
                systemctl is-active --quiet "$WORKER_UNIT" || restart_one "$WORKER_UNIT" ;;
esac
