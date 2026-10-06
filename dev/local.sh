#!/usr/bin/env bash
set -euo pipefail
umask 077

WORKSPACE_DIR=$(cd "$(dirname "$0")/.." && pwd -P)
LOCAL_DIR="$WORKSPACE_DIR/.local"
LOCAL_ENV_DIR="$LOCAL_DIR/env"
LOCAL_PROJECT="salmonbus-local-$(printf '%s' "$WORKSPACE_DIR" | shasum -a 256 | cut -c 1-10)"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

read_setting() {
  awk -F= -v key="$2" '$1 == key { count++; value = substr($0, length(key) + 2) } END { if (count != 1) exit 1; print value }' "$LOCAL_ENV_DIR/$1.env"
}

check_file() {
  local file="$LOCAL_ENV_DIR/$1.env" mode
  [[ -f $file && ! -L $file ]] || fail '로컬 설정 파일이 없거나 올바른 파일이 아닙니다. init을 먼저 실행해 주세요.'
  case $(uname -s) in
    Darwin) mode=$(stat -f '%Lp' "$file") ;;
    *) mode=$(stat -c '%a' "$file") ;;
  esac
  [[ $mode == 600 ]] || fail '로컬 설정 파일의 권한을 600으로 맞춰 주세요.'
  awk -F= -v allowed="$2" 'NF == 0 || /^[[:space:]]*#/ { next } $1 !~ allowed { exit 1 }' "$file" || fail '로컬 설정 파일에 지원하지 않는 항목이 있습니다.'
}

check_settings() {
  [[ ! -L $LOCAL_DIR && ! -L $LOCAL_ENV_DIR ]] || fail '로컬 설정 디렉터리는 심볼릭 링크로 사용할 수 없습니다.'
  check_file postgres '^(POSTGRES_DB|POSTGRES_USER|POSTGRES_PASSWORD)$'
  check_file mongodb '^(MONGO_INITDB_ROOT_USERNAME|MONGO_INITDB_ROOT_PASSWORD)$'
  check_file api '^(DB_URL|DB_USERNAME|DB_PASSWORD|CHAT_MONGODB_URI)$'
  check_file worker '^(DB_URL|DB_USERNAME|DB_PASSWORD|GBIS_SERVICE_KEY)$'
  local postgres_password mongodb_password component
  postgres_password=$(read_setting postgres POSTGRES_PASSWORD)
  mongodb_password=$(read_setting mongodb MONGO_INITDB_ROOT_PASSWORD)
  [[ $postgres_password =~ ^[0-9a-f]{48}$ && $mongodb_password =~ ^[0-9a-f]{48}$ ]] || fail '개발 계정 설정을 확인해 주세요.'
  [[ $(read_setting postgres POSTGRES_DB) == salmonbus_local ]] || fail '개발 DB 이름을 확인해 주세요.'
  [[ $(read_setting postgres POSTGRES_USER) == salmonbus_local ]] || fail '개발 DB 계정을 확인해 주세요.'
  [[ $(read_setting mongodb MONGO_INITDB_ROOT_USERNAME) == salmonbus_local ]] || fail '개발 MongoDB 계정을 확인해 주세요.'
  for component in api worker; do
    [[ $(read_setting "$component" DB_URL) == jdbc:postgresql://postgres:5432/salmonbus_local ]] || fail 'API와 worker는 로컬 PostgreSQL에만 연결할 수 있습니다.'
    [[ $(read_setting "$component" DB_USERNAME) == salmonbus_local ]] || fail '개발 DB 계정을 확인해 주세요.'
    [[ $(read_setting "$component" DB_PASSWORD) == "$postgres_password" ]] || fail '개발 DB 계정 설정이 서로 다릅니다.'
  done
  [[ $(read_setting api CHAT_MONGODB_URI) == "mongodb://salmonbus_local:${mongodb_password}@mongodb:27017/salmonbus_chat?authSource=admin" ]] || fail 'API는 로컬 MongoDB에만 연결할 수 있습니다.'
  [[ $(read_setting worker GBIS_SERVICE_KEY) == local-only-placeholder ]] || fail '로컬 환경에는 실제 GBIS 키를 넣을 수 없습니다.'
}

init_settings() {
  [[ ! -L $LOCAL_DIR && ! -L $LOCAL_ENV_DIR ]] || fail '로컬 설정 디렉터리는 심볼릭 링크로 사용할 수 없습니다.'
  mkdir -p "$LOCAL_ENV_DIR"
  chmod 700 "$LOCAL_DIR" "$LOCAL_ENV_DIR"
  if [[ -e $LOCAL_ENV_DIR/postgres.env || -e $LOCAL_ENV_DIR/mongodb.env || -e $LOCAL_ENV_DIR/api.env || -e $LOCAL_ENV_DIR/worker.env ]]; then
    check_settings
    printf '%s\n' '기존 로컬 설정을 유지합니다.'
    return
  fi
  mkdir "$LOCAL_DIR/init.lock" 2>/dev/null || fail '다른 초기 준비가 진행 중인지 확인해 주세요.'
  local postgres_password mongodb_password
  LOCAL_INIT_STAGE=$(mktemp -d "$LOCAL_DIR/init.XXXXXX")
  trap 'rm -f "$LOCAL_INIT_STAGE"/postgres.env "$LOCAL_INIT_STAGE"/mongodb.env "$LOCAL_INIT_STAGE"/api.env "$LOCAL_INIT_STAGE"/worker.env; rmdir "$LOCAL_INIT_STAGE" "$LOCAL_DIR/init.lock"' EXIT
  postgres_password=$(openssl rand -hex 24)
  mongodb_password=$(openssl rand -hex 24)
  printf 'POSTGRES_DB=salmonbus_local\nPOSTGRES_USER=salmonbus_local\nPOSTGRES_PASSWORD=%s\n' "$postgres_password" > "$LOCAL_INIT_STAGE/postgres.env"
  printf 'MONGO_INITDB_ROOT_USERNAME=salmonbus_local\nMONGO_INITDB_ROOT_PASSWORD=%s\n' "$mongodb_password" > "$LOCAL_INIT_STAGE/mongodb.env"
  printf 'DB_URL=jdbc:postgresql://postgres:5432/salmonbus_local\nDB_USERNAME=salmonbus_local\nDB_PASSWORD=%s\nCHAT_MONGODB_URI=mongodb://salmonbus_local:%s@mongodb:27017/salmonbus_chat?authSource=admin\n' "$postgres_password" "$mongodb_password" > "$LOCAL_INIT_STAGE/api.env"
  printf 'DB_URL=jdbc:postgresql://postgres:5432/salmonbus_local\nDB_USERNAME=salmonbus_local\nDB_PASSWORD=%s\nGBIS_SERVICE_KEY=local-only-placeholder\n' "$postgres_password" > "$LOCAL_INIT_STAGE/worker.env"
  local component
  for component in postgres mongodb api worker; do
    ln "$LOCAL_INIT_STAGE/$component.env" "$LOCAL_ENV_DIR/$component.env"
  done
  check_settings
  printf '%s\n' '로컬 설정을 준비했습니다.'
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
    --file "$WORKSPACE_DIR/compose.local.yaml" "$@"
}

command=${1:-help}
case $command in
  init) init_settings ;;
  check|build|prepare|up|status|stop|logs)
    check_settings
    check_docker
    shift
    case $command in
      check) compose config --quiet ;;
      build) compose build ;;
      prepare)
        [[ $# == 0 ]] || fail 'prepare 뒤에는 인자를 넣지 않습니다.'
        compose up --detach --build --wait api
        compose --profile data build local-init
        compose --profile data run --no-deps --rm local-init
        ;;
      up)
        case ${1:-} in
          '') compose up --detach --build ;;
          --watch) compose up --watch --build ;;
          *) fail 'up 또는 up --watch로 실행해 주세요.' ;;
        esac
        ;;
      status) compose --profile worker --profile data ps ;;
      stop) compose --profile worker --profile data stop ;;
      logs)
        case ${1:-} in
          api|worker|frontend|postgres|mongodb|local-init) compose --profile worker --profile data logs --tail 100 "$1" ;;
          *) fail 'logs 뒤에 서비스 이름을 적어 주세요.' ;;
        esac
        ;;
    esac
    ;;
  help) printf '%s\n' './dev/local.sh init | check | build | prepare | up [--watch] | status | logs <서비스> | stop' ;;
  *) fail '지원하지 않는 명령입니다. help를 확인해 주세요.' ;;
esac
