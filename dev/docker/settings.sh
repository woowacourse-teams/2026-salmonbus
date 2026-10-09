#!/usr/bin/env bash
fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

read_setting() {
  awk -F= -v key="$2" '$1 == key { count++; value = substr($0, length(key) + 2) } END { if (count != 1) exit 1; print value }' "$LOCAL_ENV_DIR/$1.env"
}

check_file() {
  local file="$LOCAL_ENV_DIR/$1.env" mode
  [[ -f $file && ! -L $file ]] || fail '로컬 설정 파일이 없거나 올바른 파일이 아닙니다. 설정 준비 서비스의 실행 결과를 확인해 주세요.'
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
  postgres_password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
  mongodb_password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
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

load_settings() {
  LOCAL_DIR=/local-settings
  LOCAL_ENV_DIR="$LOCAL_DIR/env"
  check_settings
  local key value
  while IFS='=' read -r key value; do
    [[ $key =~ ^[A-Z][A-Z0-9_]*$ ]] || continue
    [[ -z ${!key:-} || ${!key} == "$value" ]] || fail '환경변수와 개발 설정이 서로 다릅니다.'
    export "$key=$value"
  done < "$LOCAL_ENV_DIR/$1.env"
}
