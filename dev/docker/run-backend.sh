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
  api-app) ;;
  worker-app)
    [[ ${GBIS_SERVICE_KEY:-} == local-only-placeholder ]] || fail
    for key in $(compgen -e | grep '^GBIS_SERVICE_KEY_' || true); do
      [[ -z ${!key:-} ]] || fail
    done
    [[ -z $(compgen -e | grep '^GBIS_KEYS' || true) ]] || fail
    [[ ${GBIS_BASE_URL:-http://gbis-mock:8080} == http://gbis-mock:8080 ]] || fail
    [[ ${MODEL_BUNDLE_DIRECTORY:-/local/models/coldstart-20261016} == /local/models/coldstart-20261016 ]] || fail
    ;;
  *) fail ;;
esac

exec ./gradlew --init-script /local-tools/runtime.init ":${LOCAL_COMPONENT}:bootRun" \
    --offline --no-daemon --console=plain
