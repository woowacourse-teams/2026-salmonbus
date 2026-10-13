#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd -P)
[[ -z ${DOCKER_HOST:-} ]] || { echo 'Use a local Docker context.' >&2; exit 1; }
[[ $(docker context inspect --format '{{.Endpoints.docker.Host}}') == unix://* ]] \
  || { echo 'Use a local Unix-socket Docker context.' >&2; exit 1; }

project="salmonbus-local-check-$(date +%s)-$$"
builder=${LOCAL_TEST_BUILDER:-$project}
own_builder=false

# Use two free small subnets even when a developer's default Docker pools are full.
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
    raise SystemExit('No free isolated test subnets in 10.254.0.0/16.')
print(available[0], available[1])
PY
)
read -r access_subnet local_subnet <<< "$subnets"
temporary=$(mktemp -d)
override="$temporary/compose.yaml"

cat > "$override" <<EOF
services:
  api:
    image: $project-api:check
    ports: !override
      - "127.0.0.1::8080"
  worker:
    image: $project-worker:check
  local-init:
    image: $project-init:check
  frontend:
    image: $project-frontend:check
    ports: !override
      - "127.0.0.1::3000"
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

[[ -z $(docker ps -aq --filter "label=com.docker.compose.project=$project") ]]
[[ -z $(docker volume ls -q --filter "label=com.docker.compose.project=$project") ]]

cleanup() {
  local result=$?
  trap - EXIT
  if ((result != 0)); then
    compose logs --no-color --tail 40 2>&1 \
      | sed -E 's/[0-9a-f]{48}/[redacted]/g' >&2 || true
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  for component in api worker init frontend mock; do
    docker image rm "$project-$component:check" >/dev/null 2>&1 || true
  done
  if $own_builder; then
    docker buildx rm "$builder" >/dev/null 2>&1 || true
  fi
  rm -f "$override"
  rmdir "$temporary"
  exit "$result"
}
trap cleanup EXIT

if [[ -z ${LOCAL_TEST_BUILDER:-} ]]; then
  docker buildx create --name "$builder" --driver docker-container --driver-opt memory=2g \
    --buildkitd-config "$ROOT/dev/docker/buildkitd.toml" >/dev/null
  own_builder=true
fi

compose config --quiet
compose build --builder "$builder"
compose up --detach --no-build --wait --wait-timeout 300

api="http://$(compose port api 8080)"
frontend="http://$(compose port frontend 3000)"
for path in /actuator/health /api/v1/routes /api/v1/routes/234000050/board /api/v1/routes/234000050/vehicles; do
  curl --fail --silent --show-error "$api$path" >/dev/null
done
curl --fail --silent --show-error "$frontend/" >/dev/null

containers=$(compose ps --quiet)
for container in $containers; do
  [[ $(docker inspect --format '{{.State.OOMKilled}}' "$container") == false ]]
done
docker stats --no-stream --format '{{.Name}}: {{.MemUsage}}' $containers

compose exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U salmonbus_local -d salmonbus_local <<'SQL'
CREATE TABLE local_smoke_persistence (id integer PRIMARY KEY);
INSERT INTO local_smoke_persistence VALUES (1);
SQL
volumes=$(docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}' | sort)
compose down --remove-orphans
[[ $(docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}' | sort) == "$volumes" ]]
compose up --detach --no-build --wait --wait-timeout 300
[[ $(compose exec -T postgres psql -X -q -A -t -v ON_ERROR_STOP=1 -U salmonbus_local -d salmonbus_local \
  -c 'SELECT count(*) FROM local_smoke_persistence') == 1 ]]
api="http://$(compose port api 8080)"
curl --fail --silent --show-error "$api/api/v1/routes/234000050/board" >/dev/null
printf '%s\n' 'Local cold-start, offline runtime, first forecast, HTTP and stop/restart persistence checks passed.'
