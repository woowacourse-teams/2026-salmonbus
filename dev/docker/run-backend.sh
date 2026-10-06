#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '%s\n' '로컬 백엔드 설정을 확인해 주세요.' >&2
  exit 1
}

[[ ${DB_URL:-} == jdbc:postgresql://postgres:5432/salmonbus_local ]] || fail
[[ ${DB_USERNAME:-} == salmonbus_local ]] || fail
[[ ${DB_PASSWORD:-} =~ ^[0-9a-f]{48}$ ]] || fail

case ${LOCAL_COMPONENT:-} in
  api-app)
    [[ ${CHAT_MONGODB_URI:-} =~ ^mongodb://salmonbus_local:[0-9a-f]{48}@mongodb:27017/salmonbus_chat\?authSource=admin$ ]] || fail
    ;;
  worker-app)
    [[ ${GBIS_SERVICE_KEY:-} == local-only-placeholder ]] || fail
    ;;
  *) fail ;;
esac

exec ./gradlew ":${LOCAL_COMPONENT}:bootRun" --offline --no-daemon --console=plain
