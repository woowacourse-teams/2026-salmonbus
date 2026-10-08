#!/usr/bin/env bash
set -euo pipefail
source /local-tools/settings.sh
load_settings worker

[[ $DB_URL == jdbc:postgresql://postgres:5432/salmonbus_local ]] || fail '로컬 DB 설정을 확인해 주세요.'
[[ $DB_USERNAME == salmonbus_local && $GBIS_SERVICE_KEY == local-only-placeholder ]] || fail '로컬 개발 환경에서만 실행할 수 있습니다.'
export PGHOST=postgres PGDATABASE=salmonbus_local PGUSER=salmonbus_local
export PGPASSWORD="$DB_PASSWORD" PGCONNECT_TIMEOUT=3
export PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=3000 -c lock_timeout=100'

query="
WITH supported AS (
    SELECT DISTINCT s.route_version_id FROM stop_demand_statistics s
    JOIN route_version v ON v.id=s.route_version_id AND v.valid_to IS NULL
), active AS (
    SELECT m.id FROM model_deployment m
    JOIN local_development_fixture f ON f.bundle_digest=m.bundle_digest
    WHERE m.state='ACTIVE' AND f.singleton
)
SELECT count(*)>0 AND bool_and(EXISTS (
    SELECT 1 FROM forecast_publication p
    WHERE p.route_version_id=s.route_version_id AND p.model_deployment_id=a.id
      AND p.provenance='RECORDED' AND p.prediction_count>0
)) FROM supported s CROSS JOIN active a;"

printf '%s\n' '첫 예보를 준비하고 있습니다.'
deadline=$((SECONDS + 180))
while ((SECONDS < deadline)); do
  if ready=$(timeout 5 psql -X -w -A -t -v ON_ERROR_STOP=1 -c "$query" 2>/dev/null) && [[ $ready == t ]]; then
    printf '%s\n' '로컬 예보가 준비되었습니다. 프론트를 시작합니다.'
    exit 0
  fi
  sleep 2
done
fail '첫 예보를 확인하지 못했습니다. docker compose logs worker로 확인해 주세요.'
