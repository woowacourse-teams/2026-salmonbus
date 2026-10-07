#!/usr/bin/env bash
set -euo pipefail
source /local-tools/settings.sh

case ${1:-} in
  postgres) load_settings postgres; exec /usr/local/bin/docker-entrypoint.sh postgres ;;
  mongodb)
    load_settings mongodb
    exec /usr/local/bin/docker-entrypoint.sh mongod --config /local/config/mongodb.yml
    ;;
  *) fail '로컬 DB 서비스를 확인해 주세요.' ;;
esac
