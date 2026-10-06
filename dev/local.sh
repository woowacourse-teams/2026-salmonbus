#!/usr/bin/env bash
set -euo pipefail
umask 077

WORKSPACE_DIR=$(cd "$(dirname "$0")/.." && pwd -P)
LOCAL_PROJECT=$(basename "$WORKSPACE_DIR" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

check_docker() {
  [[ -z ${DOCKER_HOST:-} ]] || fail 'DOCKER_HOST를 해제하고 로컬 Docker로 실행해 주세요.'
  local endpoint
  endpoint=$(docker context inspect --format '{{.Endpoints.docker.Host}}')
  [[ $endpoint == unix://* ]] || fail '로컬 Docker context에서만 실행할 수 있습니다.'
  docker info >/dev/null 2>&1 || fail 'Docker를 먼저 실행해 주세요.'
}

compose() {
  COMPOSE_PROFILES= COMPOSE_ENV_FILES= docker compose --env-file /dev/null \
    --project-directory "$WORKSPACE_DIR" --project-name "$LOCAL_PROJECT" \
    --file "$WORKSPACE_DIR/compose.yaml" "$@"
}

command=${1:-help}
case $command in
  init|check|build|prepare|up|status|stop|logs|scenario)
    check_docker
    shift
    case $command in
      init) compose run --rm settings-init ;;
      check) compose config --quiet ;;
      build) compose build ;;
      scenario)
        [[ $# -ge 1 && $# -le 2 ]] || fail 'scenario <normal|empty|unknown-seat|upstream-error> [노선|all]로 실행해 주세요.'
        compose exec -T gbis-mock java -cp '/var/wiremock/lib/*:/var/wiremock/extensions/*' com.gustler.localgbis.ScenarioControl "$@"
        ;;
      prepare)
        [[ $# == 0 ]] || fail 'prepare 뒤에는 인자를 넣지 않습니다.'
        compose up --detach --build --wait local-init
        ;;
      up)
        case ${1:-} in
          '') compose up --detach --build ;;
          --watch) compose up --watch --build ;;
          *) fail 'up 또는 up --watch로 실행해 주세요.' ;;
        esac
        ;;
      status) compose ps ;;
      stop) compose stop ;;
      logs)
        case ${1:-} in
          api|worker|frontend|postgres|mongodb|local-init|settings-init|gbis-mock) compose logs --tail 100 "$1" ;;
          *) fail 'logs 뒤에 서비스 이름을 적어 주세요.' ;;
        esac
        ;;
    esac
    ;;
  help) printf '%s\n' './dev/local.sh up [--watch] | status | logs <서비스> | stop | scenario <모드> [노선|all]' ;;
  *) fail '지원하지 않는 명령입니다. help를 확인해 주세요.' ;;
esac
