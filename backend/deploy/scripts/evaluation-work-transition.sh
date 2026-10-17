#!/usr/bin/env bash
# SAL-175 최초 전환 전, 자동 배포와 정비 작업을 중지한 운영자가 실행한다.
# 1. 복구 가능한 백업을 확인한다. 이 도구는 백업을 생성하거나 검증하지 않는다.
# 2. 기존 API·worker 배포 파일과 그 DB를 복구할 수 있는지 확인한다.
#    새 revision에서 sudo bash scripts/evaluation-work-transition.sh prepare <백업 식별자>
# 3. API를 먼저 배포하고 성공을 확인한 다음 worker를 배포한다.
# prepare 실패 시에도 worker를 자동 재시작하지 않는다. 원인을 확인하고 prepare를 다시 실행한다.
# V39 적용 후 실패: writer를 중지하고 배포 전 DB와 그에 맞는 기존 배포 파일을 복구한 뒤
# sudo bash /etc/salmonbus/evaluation-work-transition.sh resume-legacy 실행.
# V39 전용 롤백 SQL은 V38까지만 되돌린다. 직전 운영 버전으로 복구하는 절차와 혼동하지 않는다.
# resume-legacy는 차단만 해제한다. 서비스 시작은 운영자가 명시적으로 실행한다.
# 다른 호스트의 worker와 정비 작업도 전환 동안 중지 상태를 유지해야 한다.
set -euo pipefail

transition_error() { echo "SAL-175: $*" >&2; return 1; }

transition_secure_file() {
  [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c '%U:%G:%a' "$1")" = root:root:600 ]
}

transition_version() {
  local version
  version="$(awk -F= '$1=="flywayMaxVersion" {print $2}' "$1" 2>/dev/null)" || return 1
  [[ "$version" =~ ^[1-9][0-9]{0,4}$ ]] || return 1
  printf '%s' "$version"
}

transition_planner_options() {
  local decoded tokens index name_value allowed='^[a-z0-9_= -]+$'
  decoded="${1//+/ }"
  decoded="$(printf '%b' "${decoded//%/\\x}")"
  [[ "$decoded" =~ $allowed ]] || return 1
  read -ra tokens <<< "$decoded"
  [ "${#tokens[@]}" -gt 0 ] || return 1
  index=0
  while [ "$index" -lt "${#tokens[@]}" ]; do
    case "${tokens[$index]}" in
      -c) index=$((index + 1)); name_value="${tokens[$index]:-}" ;;
      -c*) name_value="${tokens[$index]#-c}" ;;
      *) return 1 ;;
    esac
    [[ "$name_value" =~ ^(join_collapse_limit|from_collapse_limit|geqo_threshold)=[0-9]{1,4}$ ]] || return 1
    index=$((index + 1))
  done
}

transition_database_target() {
  local url="$1" host port database query parameter name value
  local -a parameters=()
  [[ "$url" =~ ^jdbc:postgresql://([a-zA-Z0-9.-]+)(:([0-9]+))?/([a-zA-Z0-9_-]+)(\?(.*))?$ ]] || return 1
  host="${BASH_REMATCH[1]}"; port="${BASH_REMATCH[3]:-5432}"; database="${BASH_REMATCH[4]}"; query="${BASH_REMATCH[6]}"
  [ -z "$query" ] || IFS='&' read -ra parameters <<< "$query"
  for parameter in "${parameters[@]}"; do
    name="${parameter%%=*}"; value="${parameter#*=}"
    [ "$name" != "$parameter" ] || return 1
    case "$name" in
      connectTimeout|socketTimeout) [[ "$value" =~ ^[0-9]{1,6}$ ]] || return 1 ;;
      tcpKeepAlive) [[ "$value" =~ ^(true|false)$ ]] || return 1 ;;
      options) transition_planner_options "$value" || return 1 ;;
      *) return 1 ;;
    esac
  done
  printf '%s:%s/%s' "$host" "$port" "$database"
}

transition_same_database() {
  local api_target worker_target
  [ "$(sed -n 's/^DB_USERNAME=//p' "$1")" = "$(sed -n 's/^DB_USERNAME=//p' "$2")" ] || return 1
  api_target="$(transition_database_target "$(sed -n 's/^DB_URL=//p' "$1")")" || return 1
  worker_target="$(transition_database_target "$(sed -n 's/^DB_URL=//p' "$2")")" || return 1
  [ "$api_target" = "$worker_target" ]
}

# 접속 실패·조회 권한 부족·응답 지연은 모두 차단한다. 비밀번호와 DB 오류 원문은 출력하지 않는다.
transition_sql() {
  local component="$1" sql="$2" env_file url user password host port database
  env_file="/etc/salmonbus/$component.env"
  transition_secure_file "$env_file" || { transition_error 'DB 설정 파일의 소유자·권한을 확인하세요'; return 1; }
  url="$(sed -n 's/^DB_URL=//p' "$env_file")"
  user="$(sed -n 's/^DB_USERNAME=//p' "$env_file")"
  password="$(sed -n 's/^DB_PASSWORD=//p' "$env_file")"
  [[ "$url" =~ ^jdbc:postgresql://([a-zA-Z0-9.-]+)(:([0-9]+))?/([a-zA-Z0-9_-]+)(\?.*)?$ ]] \
    || { transition_error 'DB 주소 형식을 확인할 수 없습니다'; return 1; }
  host="${BASH_REMATCH[1]}"; port="${BASH_REMATCH[3]:-5432}"; database="${BASH_REMATCH[4]}"
  [ -n "$user" ] && [ -n "$password" ] || return 1
  command -v psql >/dev/null && command -v timeout >/dev/null || return 1
  PGHOST="$host" PGPORT="$port" PGDATABASE="$database" PGUSER="$user" PGPASSWORD="$password" \
    PGSSLMODE=require PGCONNECT_TIMEOUT=5 PGAPPNAME=salmonbus-evaluation-transition \
    PGOPTIONS='-c statement_timeout=10000 -c lock_timeout=5000 -c search_path=public' \
    timeout 15 psql -X -qAt -v ON_ERROR_STOP=1 -c "$sql" 2>/dev/null
}

transition_schema() {
  transition_sql "$1" "SELECT CASE
    WHEN EXISTS (SELECT 1 FROM pg_class WHERE oid=to_regclass('public.forecast_evaluation_work') AND relkind='r')
      AND EXISTS (SELECT 1 FROM pg_class WHERE oid=to_regclass('public.forecast_evaluation_pending') AND relkind='v') THEN 'work'
    WHEN EXISTS (SELECT 1 FROM pg_class WHERE oid=to_regclass('public.forecast_evaluation_pending') AND relkind='r') THEN 'pending'
    WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'empty'
    ELSE 'unsupported' END"
}

transition_worker_stopped() {
  local state
  state="$(systemctl show -p ActiveState --value salmonbus-worker)" || return 1
  case "$state" in inactive|failed) ;; *) return 1 ;; esac
  # current 링크가 바뀌었어도 이전 release에서 실행 중인 JAR을 확인한다.
  local result=0
  pgrep -f '[/]opt/salmonbus/worker/.*[.]jar' >/dev/null || result=$?
  [ "$result" = 1 ]
}

transition_no_writers() {
  local count
  count="$(transition_sql "$1" "SELECT count(*) FROM pg_stat_activity
    WHERE datname=current_database() AND pid<>pg_backend_pid() AND (backend_type='client backend' OR backend_type IS NULL)
      AND (application_name IN ('salmonbus-worker','salmonbus-historical-migration')
           OR application_name IS NULL OR state IS NULL)")" || return 1
  [ "$count" = 0 ]
}

transition_approved() {
  transition_secure_file /etc/salmonbus/evaluation-work-hold || return 1
  local approved
  approved="$(transition_sql "$1" "SELECT count(*) FROM public.sal175_evaluation_transition
    WHERE id=1 AND expires_at>clock_timestamp() AND backup_reference<>''")" || return 1
  [ "$approved" = 1 ]
}

# BeforeInstall과 ApplicationStart에서 모두 호출한다. DB는 변경하지 않는다.
transition_check() {
  local component="$1" manifest="$2" phase="$3" version schema
  version="$(transition_version "$manifest")" || { transition_error '배포할 스키마 번호를 확인할 수 없습니다'; return 1; }
  schema="$(transition_schema "$component")" || { transition_error 'DB 상태를 확인할 수 없어 배포·기동을 중단합니다'; return 1; }
  case "$schema" in
    work)
      [ "$version" -ge 39 ] || { transition_error '새 DB 구조에서 구 JAR를 시작할 수 없습니다. DB 복원이 먼저입니다'; return 1; }
      ;;
    pending)
      if [ "$version" -ge 39 ]; then
        transition_approved "$component" && transition_worker_stopped && transition_no_writers "$component" \
          || { transition_error 'worker·정비 작업 중지와 prepare 완료를 확인할 수 없습니다'; return 1; }
        if [ "$phase" = runtime ] && [ "$component" = worker ]; then
          transition_error 'API에서 DB 전환을 완료한 뒤 worker를 시작하세요'; return 1
        fi
      elif [ "$component" = worker ] && [ -e /etc/salmonbus/evaluation-work-hold ]; then
        transition_error '전환 중에는 구 worker를 시작할 수 없습니다'; return 1
      fi
      ;;
    empty)
      transition_worker_stopped && transition_no_writers "$component" || return 1
      if [ "$component" = worker ]; then
        transition_error '빈 운영 DB는 API로 먼저 설치하세요'; return 1
      fi
      ;;
    *) transition_error '지원하지 않는 DB 상태입니다'; return 1 ;;
  esac
}

transition_check_deployment() {
  local version
  version="$(transition_version "$2")" || return 1
  # V39 전환과 무관한 기존 배포의 진단·실패 정책은 유지한다.
  if [ "$version" -lt 39 ] && [ ! -f /etc/salmonbus/evaluation-work-transition.sh ]; then return 0; fi
  transition_check "$1" "$2" deploy
}

transition_prepare() {
  local backup="${1:-}" component schema script_dir
  [ "$(id -u)" = 0 ] || { transition_error 'root로 실행하세요'; return 1; }
  [[ "$backup" =~ ^[a-zA-Z0-9:/_.-]{1,200}$ ]] || { transition_error '백업 식별자가 필요합니다'; return 1; }
  [ "$#" = 1 ] || { transition_error 'prepare에는 확인한 백업 식별자만 지정하세요'; return 1; }
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  # api와 worker의 DB가 다르면 단일 DB 전환 절차를 실행하지 않는다.
  for component in api worker; do transition_secure_file "/etc/salmonbus/$component.env" || return 1; done
  transition_same_database /etc/salmonbus/api.env /etc/salmonbus/worker.env \
    || { transition_error 'api와 worker의 DB 대상이 다르거나 허용하지 않은 연결 옵션이 있습니다'; return 1; }
  schema="$(transition_schema api)" || return 1
  case "$schema" in pending) ;; *) transition_error '기존 대기 테이블이 있는 DB에서만 prepare를 실행할 수 있습니다'; return 1 ;; esac
  [ -f "$script_dir/../sql/prepare-evaluation-work-transition.sql" ] || return 1

  # 이전 배포판으로 자동 복귀해도 남는 경로에 기동 검사를 설치한다.
  install -m 644 -o root -g root "${BASH_SOURCE[0]}" /etc/salmonbus/evaluation-work-transition.sh
  install -m 600 -o root -g root /dev/null /etc/salmonbus/evaluation-work-hold
  for component in api worker; do
    mkdir -p "/etc/systemd/system/salmonbus-$component.service.d"
    printf '[Service]\nExecStartPre=+/bin/bash /etc/salmonbus/evaluation-work-transition.sh runtime %s\n' "$component" \
      > "/etc/systemd/system/salmonbus-$component.service.d/evaluation-work-transition.conf"
    chmod 644 "/etc/systemd/system/salmonbus-$component.service.d/evaluation-work-transition.conf"
    chown root:root "/etc/systemd/system/salmonbus-$component.service.d/evaluation-work-transition.conf"
  done
  systemctl daemon-reload
  systemctl stop salmonbus-worker
  transition_worker_stopped && transition_no_writers api \
    || { transition_error 'worker 또는 정비 연결이 남아 있습니다. 자동 재개하지 않습니다'; return 1; }
  # 백업 식별자는 위에서 허용한 문자만 사용한다. SQL 따옴표를 깨는 입력은 거절한다.
  transition_sql api "SET salmonbus.backup_reference='$backup'; $(<"$script_dir/../sql/prepare-evaluation-work-transition.sql")" \
    || { transition_error 'DB 전환 승인을 저장하지 못했습니다. worker는 중지 상태로 남습니다'; return 1; }
  echo 'SAL-175: prepare 완료. 30분 안에 API, 다음으로 worker를 배포하세요. 정비 작업은 재개하지 마세요.'
}

transition_resume_legacy() {
  [ "$(id -u)" = 0 ] || return 1
  [ "$(transition_schema api)" = pending ] && transition_worker_stopped && transition_no_writers api || return 1
  local component version database_version
  database_version="$(transition_sql api 'SELECT max(version::integer) FROM public.flyway_schema_history WHERE success')" || return 1
  for component in api worker; do
    version="$(transition_version "/opt/salmonbus/$component/current/release-manifest.txt")" || return 1
    [ "$version" -lt 39 ] || { transition_error '두 앱 모두 이전 배포판을 먼저 설치하세요'; return 1; }
    [ "$version" = "$database_version" ] || { transition_error '이전 JAR와 DB 스키마 번호가 다릅니다'; return 1; }
  done
  transition_sql api 'DROP TABLE IF EXISTS public.sal175_evaluation_transition' || return 1
  rm -f /etc/salmonbus/evaluation-work-hold
  echo 'SAL-175: 이전 DB 구조와 배포판을 확인했습니다. 서비스는 자동 재개하지 않습니다.'
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  case "${1:-}" in
    prepare) shift; transition_prepare "$@" ;;
    runtime)
      case "${2:-}" in api|worker) ;; *) exit 64 ;; esac
      transition_check "$2" "/opt/salmonbus/$2/current/release-manifest.txt" runtime ;;
    resume-legacy) transition_resume_legacy ;;
    *) echo 'usage: evaluation-work-transition.sh prepare <backup-reference> | runtime api|worker | resume-legacy' >&2; exit 64 ;;
  esac
fi
