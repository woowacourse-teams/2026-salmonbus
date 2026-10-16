#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd -P)
[[ -z ${DOCKER_HOST:-} ]] || { echo 'Use a local Docker context.' >&2; exit 1; }
[[ $(docker context inspect --format '{{.Endpoints.docker.Host}}') == unix://* ]] \
  || { echo 'Use a local Unix-socket Docker context.' >&2; exit 1; }
project="salmonbus-geometry-check-$(date +%s)-$$"
temporary=$(mktemp -d)
override="$temporary/compose.yaml"
report_dir=${GEOMETRY_REPORT_DIR:-$ROOT/.work/geometry-local}
mkdir -p "$report_dir"

subnets=$(python3 - <<'PY'
import ipaddress
import json
import subprocess

ids = subprocess.check_output(['docker', 'network', 'ls', '--quiet'], text=True).split()
networks = json.loads(subprocess.check_output(['docker', 'network', 'inspect', *ids], text=True))
used = [ipaddress.ip_network(config['Subnet']) for network in networks
        for config in (network.get('IPAM', {}).get('Config') or [])
        if config.get('Subnet') and ':' not in config['Subnet']]
available = [subnet for subnet in ipaddress.ip_network('10.254.0.0/16').subnets(new_prefix=24)
             if not any(subnet.overlaps(existing) for existing in used)]
if len(available) < 2:
    raise SystemExit('No free isolated geometry test subnets.')
print(available[0], available[1])
PY
)
read -r access_subnet local_subnet <<< "$subnets"
cat > "$override" <<EOF
services:
  api:
    image: $project-api:check
    environment:
      SPRING_PROFILES_ACTIVE: api-docs-local
    ports: !override
      - "127.0.0.1::8080"
  worker:
    image: $project-worker:check
  local-init:
    image: $project-init:check
  gbis-mock:
    image: $project-mock:check
networks:
  access:
    ipam:
      config:
        - subnet: $access_subnet
  local:
    ipam:
      config:
        - subnet: $local_subnet
EOF

compose() {
  COMPOSE_PROFILES= COMPOSE_ENV_FILES= COMPOSE_PARALLEL_LIMIT=2 \
    docker compose --env-file /dev/null --project-directory "$ROOT" --project-name "$project" \
    --file "$ROOT/compose.local.yaml" --file "$override" "$@"
}

cleanup() {
  local result=$?
  trap - EXIT
  if ((result != 0)); then
    compose logs --no-color --tail 60 2>&1 | sed -E 's/[0-9a-f]{48}/[redacted]/g' >&2 || true
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  for component in api worker init mock; do
    docker image rm "$project-$component:check" >/dev/null 2>&1 || true
  done
  rm -f "$override"
  rm -f "$temporary/worker.log"
  rmdir "$temporary"
  exit "$result"
}
trap cleanup EXIT

[[ -z $(docker ps -aq --filter "label=com.docker.compose.project=$project") ]]
[[ -z $(docker volume ls -q --filter "label=com.docker.compose.project=$project") ]]
compose config --quiet
compose build --builder "${LOCAL_TEST_BUILDER:-default}" api worker local-init gbis-mock
compose up --detach --no-build --wait --wait-timeout 300 worker
api="http://$(compose port api 8080)"
python3 "$ROOT/dev/tests/assert-route-geometry.py" --api "$api" --data "$ROOT/dev/data" \
  --output "$report_dir/before.json" --check-docs

geometry_count() {
  compose exec -T postgres psql -X -q -A -t -v ON_ERROR_STOP=1 -U salmonbus_local -d salmonbus_local \
    -c 'SELECT count(*) FROM route_geometry'
}
catalog_count=$(python3 - "$ROOT" <<'PY'
import json
from pathlib import Path
import sys
p = Path(sys.argv[1]) / 'dev/data'
print(sum(len(json.loads((p / name).read_text())['routes']) for name in ('routes.json', 'catalog-routes.json')))
PY
)
[[ $(geometry_count) == "$catalog_count" ]]

# The read API must retain the last valid geometry when a restarted collector cannot reach upstream.
compose stop gbis-mock
compose restart worker
compose up --detach --no-build --no-deps --wait --wait-timeout 180 worker
for attempt in $(seq 1 30); do
  compose logs --no-color worker > "$temporary/worker.log" 2>&1
  if rg -q 'event=route_catalog_refresh_failed|reason=CATALOG_UNAVAILABLE' "$temporary/worker.log"; then
    break
  fi
  sleep 1
done
rg --max-count 1 'event=route_catalog_refresh_failed|reason=CATALOG_UNAVAILABLE' "$temporary/worker.log" \
  > "$report_dir/upstream-failure.txt"
python3 "$ROOT/dev/tests/assert-route-geometry.py" --api "$api" --data "$ROOT/dev/data" \
  --output "$report_dir/upstream-offline.json" --compare "$report_dir/before.json"
[[ $(geometry_count) == "$catalog_count" ]]

# Reuse all seeded volumes through full stack shutdown/startup. Identical shapes must not append duplicates.
volumes=$(docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}' | sort)
compose down --remove-orphans
[[ $(docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}' | sort) == "$volumes" ]]
compose up --detach --no-build --wait --wait-timeout 300 worker
api="http://$(compose port api 8080)"
python3 "$ROOT/dev/tests/assert-route-geometry.py" --api "$api" --data "$ROOT/dev/data" \
  --output "$report_dir/after-restart.json" --compare "$report_dir/before.json"
[[ $(geometry_count) == "$catalog_count" ]]
compose logs --no-color local-init > "$report_dir/seed-restart.txt"
rg -q '"result":"preserved"' "$report_dir/seed-restart.txt"
printf '%s\n' "All catalog geometry, source points, stop visits, version pins, ETags and restart persistence passed: $report_dir"
