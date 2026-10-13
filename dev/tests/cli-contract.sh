#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd -P)
temporary=$(mktemp -d)
trap 'rm -f "$temporary/docker" "$temporary/calls"; rmdir "$temporary"' EXIT

cat > "$temporary/docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$LOCAL_TEST_CALLS"
case "$*" in
  'context inspect '*) printf '%s\n' 'unix:///local-test/docker.sock' ;;
  'info --format '*) printf '%s\n' '8589934592' ;;
  'inspect --format '*) printf '%s\n' 'false' ;;
  'compose version --short') printf '%s\n' "${LOCAL_TEST_COMPOSE_VERSION:-5.0.1}" ;;
  'image prune '*) [[ ${LOCAL_TEST_IMAGE_PRUNE_FAIL:-0} == 0 ]] ;;
  compose*)
    printf 'compose-builder=%s\n' "${BUILDX_BUILDER:-}" >> "$LOCAL_TEST_CALLS"
    if [[ $* == *' build' && ${LOCAL_TEST_BUILD_FAIL:-0} == 1 ]]; then exit 1; fi
    ;;
esac
SH
chmod +x "$temporary/docker"

run() {
  env -u DOCKER_HOST PATH="$temporary:$PATH" LOCAL_TEST_CALLS="$temporary/calls" \
    bash "$ROOT/dev/local.sh" "$@"
}

if run clean-cache >/dev/null 2>&1; then
  echo 'Cache cleanup must require --yes.' >&2; exit 1
fi
! grep -Eq 'prune' "$temporary/calls"

run clean-cache --yes >/dev/null
grep -Eq '^buildx prune --builder salmonbus-local-[0-9]+ --filter until=24h --force$' "$temporary/calls"
! grep -Eq 'system prune|volume rm|image rm|network prune' "$temporary/calls"

> "$temporary/calls"
run doctor >/dev/null
! grep -Eq 'prune|--bootstrap|buildx create|compose.*up' "$temporary/calls"

> "$temporary/calls"
if run up --invalid >/dev/null 2>&1; then
  echo 'Invalid up arguments must fail.' >&2; exit 1
fi
! grep -Eq -- '--bootstrap|buildx create|compose.*up' "$temporary/calls"

> "$temporary/calls"
run build >/dev/null
grep -Eq '^compose-builder=salmonbus-local-[0-9]+$' "$temporary/calls"
grep -Eq '^image prune --force --filter label=com.docker.compose.project=[a-z0-9_-]+$' "$temporary/calls"

> "$temporary/calls"
if LOCAL_TEST_BUILD_FAIL=1 run build >/dev/null 2>&1; then
  echo 'Failed builds must fail before image cleanup.' >&2; exit 1
fi
! grep -Eq 'image prune' "$temporary/calls"

> "$temporary/calls"
run up --watch >/dev/null
grep -Eq 'up --detach --build --wait --wait-timeout 300' "$temporary/calls"
grep -Eq 'watch --no-up --prune' "$temporary/calls"
[[ $(grep -c '^image prune --force --filter label=com.docker.compose.project=' "$temporary/calls") == 2 ]]

> "$temporary/calls"
run stop >/dev/null
grep -Eq 'down --remove-orphans' "$temporary/calls"
! grep -Eq -- '--volumes' "$temporary/calls"

> "$temporary/calls"
if run clean-images >/dev/null 2>&1; then
  echo 'Explicit image cleanup must require --yes.' >&2; exit 1
fi
! grep -Eq 'image prune' "$temporary/calls"
run clean-images --yes >/dev/null
grep -Eq '^image prune --force --filter label=com.docker.compose.project=[a-z0-9_-]+$' "$temporary/calls"
! grep -Eq -- 'image prune .*--all|system prune|volume rm|image rm|network prune' "$temporary/calls"
if LOCAL_TEST_IMAGE_PRUNE_FAIL=1 run clean-images --yes >/dev/null 2>&1; then
  echo 'Explicit cleanup failures must be reported as failures.' >&2; exit 1
fi
LOCAL_TEST_IMAGE_PRUNE_FAIL=1 run build >/dev/null 2>&1

for version in 2.34.0 2.38.2 2.39.0 2.9.0 unknown; do
  > "$temporary/calls"
  if LOCAL_TEST_COMPOSE_VERSION="$version" run build >/dev/null 2>&1; then
    echo "Unsupported Compose version must fail: $version" >&2; exit 1
  fi
  ! grep -Eq -- '--bootstrap|buildx create|compose.*build' "$temporary/calls"
done
for version in 2.40.0 v2.40.0 2.40.3-desktop.1 5.0.1; do
  LOCAL_TEST_COMPOSE_VERSION="$version" run check >/dev/null
done
printf '%s\n' 'Local CLI builder, scoped image cleanup, Watch pruning, read-only diagnostics, data-preserving stop and Compose compatibility checks passed.'
