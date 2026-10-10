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
  compose*) printf 'compose-builder=%s\n' "${BUILDX_BUILDER:-}" >> "$LOCAL_TEST_CALLS" ;;
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

> "$temporary/calls"
run stop >/dev/null
grep -Eq 'down --remove-orphans' "$temporary/calls"
! grep -Eq -- '--volumes' "$temporary/calls"
printf '%s\n' 'Local CLI builder, cleanup confirmation, read-only diagnostics and data-preserving stop checks passed.'
