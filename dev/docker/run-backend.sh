#!/usr/bin/env bash
set -euo pipefail

source /local-tools/settings.sh
case ${LOCAL_COMPONENT:-} in
  api-app) load_settings api ;;
  worker-app) load_settings worker ;;
  *) fail '로컬 백엔드 서비스를 확인해 주세요.' ;;
esac

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
    for key in GBIS_SERVICE_KEY_B GBIS_SERVICE_KEY_C GBIS_SERVICE_KEY_D; do
      [[ -z ${!key:-} ]] || fail
    done
    [[ ${GBIS_BASE_URL:-http://gbis-mock:8080} == http://gbis-mock:8080 ]] || fail
    [[ ${MODEL_BUNDLE_DIRECTORY:-/local/models/development-v1} == /local/models/development-v1 ]] || fail
    ;;
  *) fail ;;
esac

exec ./gradlew ":${LOCAL_COMPONENT}:bootRun" --offline --no-daemon --console=plain
