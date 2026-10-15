#!/usr/bin/env bash
set -euo pipefail
source /local-tools/settings.sh

case ${1:-} in
  postgres)
    load_settings postgres
    exec /usr/local/bin/docker-entrypoint.sh postgres \
      -c shared_buffers=64MB -c max_connections=40 -c work_mem=4MB
    ;;
  mongodb)
    load_settings mongodb
    exec /usr/local/bin/docker-entrypoint.sh mongod --config /local/config/mongodb.yml \
      --wiredTigerCacheSizeGB 0.256
    ;;
  *) fail '로컬 DB 서비스를 확인해 주세요.' ;;
esac
