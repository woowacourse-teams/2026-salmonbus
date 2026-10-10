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
  COMPOSE_PROFILES= COMPOSE_ENV_FILES= COMPOSE_PARALLEL_LIMIT=2 docker compose --env-file /dev/null \
    --project-directory "$WORKSPACE_DIR" --project-name "$LOCAL_PROJECT" \
    --file "$WORKSPACE_DIR/compose.yaml" "$@"
}

builder_name() {
  local repository endpoint
  repository=$(git -C "$WORKSPACE_DIR" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) \
    || repository="$WORKSPACE_DIR"
  endpoint=$(docker context inspect --format '{{.Endpoints.docker.Host}}')
  printf 'salmonbus-local-%s\n' "$(printf '%s\n%s\n' "$repository" "$endpoint" | cksum | awk '{print $1}')"
}

prepare_builder() {
  local builder
  builder=$(builder_name)
  if ! docker buildx inspect "$builder" >/dev/null 2>&1; then
    docker buildx create --name "$builder" --driver docker-container --driver-opt memory=2g \
      --buildkitd-config "$WORKSPACE_DIR/dev/docker/buildkitd.toml" >/dev/null
  fi
  docker buildx inspect --bootstrap "$builder" >/dev/null
  export BUILDX_BUILDER="$builder"
}

doctor() {
  local builder memory
  memory=$(docker info --format '{{.MemTotal}}')
  awk -v bytes="$memory" 'BEGIN { printf "Docker 메모리: %.1fGiB (권장 4GiB 이상)\n", bytes/1073741824 }'
  printf '%s\n' '호스트 저장 공간:'
  df -h "$WORKSPACE_DIR"
  printf '%s\n' 'Docker 전체 사용량:'
  docker system df
  builder=$(builder_name)
  if [[ $(docker inspect --format '{{.State.Running}}' "buildx_buildkit_${builder}0" 2>/dev/null) == true ]]; then
    printf '%s\n' '이 저장소의 빌드 캐시:'
    docker buildx du --builder "$builder" | tail -n 4
  fi
}

command=${1:-help}
case $command in
  init|check|build|prepare|up|status|stop|logs|scenario|reset-data|doctor|clean-cache)
    check_docker
    shift
    case $command in
      init) compose run --rm settings-init ;;
      check) compose config --quiet ;;
      build) prepare_builder; compose build ;;
      doctor) doctor ;;
      clean-cache)
        [[ $# == 1 && $1 == --yes ]] || fail '저장소의 오래된 빌드 캐시를 정리하려면 clean-cache --yes로 실행해 주세요.'
        builder=$(builder_name)
        docker buildx inspect "$builder" >/dev/null 2>&1 || fail '이 저장소의 빌드 캐시가 아직 없습니다.'
        docker buildx prune --builder "$builder" --filter until=24h --force
        ;;
      scenario)
        [[ $# -ge 1 && $# -le 2 ]] || fail 'scenario list 또는 scenario <모드|showcase> [노선|all]로 실행해 주세요.'
        compose exec -T gbis-mock java -cp '/var/wiremock/lib/*:/var/wiremock/extensions/*' com.gustler.localgbis.ScenarioControl "$@"
        ;;
      prepare)
        [[ $# == 0 ]] || fail 'prepare 뒤에는 인자를 넣지 않습니다.'
        prepare_builder
        compose up --detach --build --wait local-init
        ;;
      up)
        case ${1:-} in
          '') prepare_builder; compose up --detach --build ;;
          --watch) prepare_builder; compose up --watch --build ;;
          *) fail 'up 또는 up --watch로 실행해 주세요.' ;;
        esac
        ;;
      status) compose ps ;;
      stop) compose down --remove-orphans ;;
      reset-data)
        [[ $# == 1 && $1 == --yes ]] || fail '실행 이력과 채팅을 지우려면 reset-data --yes로 실행해 주세요.'
        for service in postgres mongodb gbis-mock; do
          compose exec -T "$service" true >/dev/null 2>&1 || fail '먼저 로컬 환경을 실행해 주세요.'
        done
        compose stop frontend api worker
        compose run --rm --no-deps local-init reset
        compose exec -T mongodb bash /local-tools/reset-chat.sh
        compose exec -T gbis-mock java -cp '/var/wiremock/lib/*:/var/wiremock/extensions/*' com.gustler.localgbis.ScenarioControl normal all
        compose up --detach --no-build --wait --wait-timeout 240 frontend
        printf '%s\n' '로컬 실행 이력과 채팅을 정리했습니다. 브라우저를 새로고침해 주세요.'
        ;;
      logs)
        case ${1:-} in
          api|worker|frontend|postgres|mongodb|local-init|local-ready|settings-init|gbis-mock) compose logs --tail 100 "$1" ;;
          *) fail 'logs 뒤에 서비스 이름을 적어 주세요.' ;;
        esac
        ;;
    esac
    ;;
  help) printf '%s\n' './dev/local.sh up [--watch] | status | logs <서비스> | stop | doctor | clean-cache --yes | scenario <모드> [노선|all] | reset-data --yes' ;;
  *) fail '지원하지 않는 명령입니다. help를 확인해 주세요.' ;;
esac
