#!/usr/bin/env bash
# run.sh의 일회용 Linux 컨테이너에서만 실행한다. DB 검사 자체는 PostgreSQL 통합 테스트가 담당한다.
set -euo pipefail
[ -f /.dockerenv ] && [ -d /rehearse ] && [ -d /deploy ] || exit 64
source /deploy/scripts/evaluation-work-transition.sh
TEST_DIRECTORY="$(mktemp -d)"
PASS=0
printf 'flywayMaxVersion=39\n' > "$TEST_DIRECTORY/new-manifest"
printf 'flywayMaxVersion=38\n' > "$TEST_DIRECTORY/old-manifest"
printf 'component=worker\n' > "$TEST_DIRECTORY/broken-manifest"

expect_blocked() {
  if "$@" > "$TEST_DIRECTORY/output" 2>&1; then echo '차단해야 하는 요청을 허용했다' >&2; exit 1; fi
}

run_case() {
  local name="$1" body="$2"
  (
    SCHEMA=pending; APPROVED=yes; RUNNING=inactive; WRITERS=0; PROCESS_STATUS=1
    transition_schema() { [ "$SCHEMA" != unavailable ] || return 1; echo "$SCHEMA"; }
    transition_approved() { [ "$APPROVED" = yes ]; }
    transition_no_writers() { [ "$WRITERS" = 0 ]; }
    systemctl() {
      case "$1" in
        show) echo "$RUNNING" ;;
        stop) RUNNING=inactive; echo stopped >> "$TEST_DIRECTORY/actions" ;;
        daemon-reload) : ;;
        *) return 64 ;;
      esac
    }
    pgrep() { return "$PROCESS_STATUS"; }
    eval "$body"
  )
  PASS=$((PASS+1)); echo "  통과: $name"
}

run_case '전환 준비가 없으면 API 배포를 막는다' '
  APPROVED=no
  expect_blocked transition_check api "$TEST_DIRECTORY/new-manifest" deploy
  expect_blocked transition_check_deployment api "$TEST_DIRECTORY/new-manifest"
'
run_case 'worker가 실행 중이면 승인이 있어도 배포를 막는다' '
  RUNNING=active
  expect_blocked transition_check api "$TEST_DIRECTORY/new-manifest" deploy
'
run_case 'worker가 종료 중이면 기다린 것으로 간주하지 않는다' '
  RUNNING=deactivating
  expect_blocked transition_check api "$TEST_DIRECTORY/new-manifest" deploy
'
run_case '정비 연결이 남아 있으면 배포를 막는다' '
  WRITERS=1
  expect_blocked transition_check api "$TEST_DIRECTORY/new-manifest" deploy
'
run_case 'DB 상태를 읽지 못하면 배포를 막는다' '
  SCHEMA=unavailable
  expect_blocked transition_check api "$TEST_DIRECTORY/new-manifest" deploy
'
run_case '프로세스 조회가 실패하면 중지됐다고 간주하지 않는다' '
  PROCESS_STATUS=2
  expect_blocked transition_check api "$TEST_DIRECTORY/new-manifest" deploy
'
run_case '수동으로 실행한 worker JAR도 남아 있으면 막는다' '
  PROCESS_STATUS=0
  expect_blocked transition_check api "$TEST_DIRECTORY/new-manifest" deploy
'
run_case '중지와 승인을 확인하면 API 전환을 허용한다' '
  transition_check api "$TEST_DIRECTORY/new-manifest" deploy
  transition_check api "$TEST_DIRECTORY/new-manifest" runtime
'
run_case 'API가 전환하기 전에 새 worker가 먼저 시작하지 못한다' '
  expect_blocked transition_check worker "$TEST_DIRECTORY/new-manifest" runtime
'
run_case '빈 DB는 승인 테이블 없이 API로 먼저 설치한다' '
  SCHEMA=empty; APPROVED=no
  transition_check api "$TEST_DIRECTORY/new-manifest" runtime
  expect_blocked transition_check worker "$TEST_DIRECTORY/new-manifest" runtime
'
run_case '전환 후 새 worker의 시작을 허용한다' '
  SCHEMA=work; APPROVED=no
  transition_check worker "$TEST_DIRECTORY/new-manifest" runtime
'
run_case '전환 후 구 JAR로 돌아가는 자동 재시작을 막는다' '
  SCHEMA=work
  expect_blocked transition_check worker "$TEST_DIRECTORY/old-manifest" runtime
  expect_blocked transition_check api "$TEST_DIRECTORY/old-manifest" runtime
'
run_case '배포판의 스키마 번호가 없으면 막는다' '
  expect_blocked transition_check api "$TEST_DIRECTORY/broken-manifest" deploy
'
run_case '알 수 없는 DB 구조에서는 배포하지 않는다' '
  SCHEMA=unsupported
  expect_blocked transition_check api "$TEST_DIRECTORY/new-manifest" deploy
'
run_case '실제 DB 호출 경로는 비밀번호를 인자로 넘기지 않고 조회 실패를 전달한다' '
  source /deploy/scripts/evaluation-work-transition.sh
  timeout() { test "$1" = 15 || return 1; shift; "$@"; }
  psql() {
    test "$PGHOST" = rds && test "$PGPORT" = 5432 && test "$PGDATABASE" = salmonbus \
      && test "$PGUSER" = x && test "$PGSSLMODE" = require \
      && test "$PGAPPNAME" = salmonbus-evaluation-transition && test -n "$PGPASSWORD" || return 1
    case "$*" in *"$PGPASSWORD"*) return 1 ;; esac
    echo work
  }
  test "$(transition_schema api)" = work
  psql() { return 2; }
  expect_blocked transition_schema api
'
run_case '설정 파일 권한이 열려 있으면 DB 연결 전에 막는다' '
  source /deploy/scripts/evaluation-work-transition.sh
  chmod 644 /etc/salmonbus/api.env
  expect_blocked transition_schema api
  chmod 600 /etc/salmonbus/api.env
'
run_case '백업 식별자가 없으면 worker를 중지하기 전에 거절한다' '
  RUNNING=active
  expect_blocked transition_prepare
  test "$RUNNING" = active
'
run_case '예전 패키지 인자를 넘기면 잘못된 사용법을 알리고 중지 전에 거절한다' '
  RUNNING=active
  expect_blocked transition_prepare test-backup "$TEST_DIRECTORY/recovery"
  test "$RUNNING" = active
'
run_case 'prepare는 worker를 중지하고 배포판 밖에 기동 검사를 남긴다' '
  RUNNING=active
  transition_sql() { echo approved >> "$TEST_DIRECTORY/actions"; }
  transition_prepare test-backup
  test "$RUNNING" = inactive
  test -f /etc/salmonbus/evaluation-work-transition.sh
  transition_secure_file /etc/salmonbus/evaluation-work-hold
  grep -q "ExecStartPre=+/bin/bash /etc/salmonbus/evaluation-work-transition.sh runtime worker" \
    /etc/systemd/system/salmonbus-worker.service.d/evaluation-work-transition.conf
  grep -q "runtime api" /etc/systemd/system/salmonbus-api.service.d/evaluation-work-transition.conf
  test "$(head -1 "$TEST_DIRECTORY/actions")" = stopped
  source /etc/salmonbus/evaluation-work-transition.sh
  transition_schema() { echo work; }
  expect_blocked transition_check worker "$TEST_DIRECTORY/old-manifest" runtime
'
run_case '전환 준비가 실패해도 구 worker를 자동 재개하지 않는다' '
  RUNNING=active; WRITERS=1
  expect_blocked transition_prepare test-backup
  test "$RUNNING" = inactive
  test -f /etc/salmonbus/evaluation-work-hold
  expect_blocked transition_check worker "$TEST_DIRECTORY/old-manifest" runtime
'
run_case 'DB 승인 저장이 실패하면 기동 차단을 유지한다' '
  transition_sql() { return 1; }
  expect_blocked transition_prepare test-backup
  test -f /etc/salmonbus/evaluation-work-hold
  expect_blocked transition_check worker "$TEST_DIRECTORY/old-manifest" runtime
'
run_case 'DB를 되돌리지 않고 구 worker 기동 차단을 해제할 수 없다' '
  SCHEMA=work
  expect_blocked transition_resume_legacy
  test -f /etc/salmonbus/evaluation-work-hold
'
run_case '이전 DB와 두 JAR가 일치해야 구 worker 차단을 해제한다' '
  mkdir -p /opt/salmonbus/api/current /opt/salmonbus/worker/current
  printf "flywayMaxVersion=38\n" > /opt/salmonbus/api/current/release-manifest.txt
  printf "flywayMaxVersion=38\n" > /opt/salmonbus/worker/current/release-manifest.txt
  transition_sql() { case "$2" in SELECT*) echo 35 ;; DROP*) : ;; *) return 1 ;; esac; }
  expect_blocked transition_resume_legacy
  test -f /etc/salmonbus/evaluation-work-hold
  transition_sql() { case "$2" in SELECT*) echo 38 ;; DROP*) : ;; *) return 1 ;; esac; }
  transition_resume_legacy
  test ! -e /etc/salmonbus/evaluation-work-hold
  test "$RUNNING" = inactive
'
write_database_env() {
  printf 'DB_URL=%s\nDB_USERNAME=%s\nDB_PASSWORD=p\n' "$2" "${3:-x}" > "$TEST_DIRECTORY/$1.env"
}

run_case '두 앱의 DB 주소가 같으면 같은 DB로 본다' '
  write_database_env api jdbc:postgresql://rds:5432/salmonbus
  write_database_env worker jdbc:postgresql://rds/salmonbus
  transition_same_database "$TEST_DIRECTORY/api.env" "$TEST_DIRECTORY/worker.env"
'
run_case 'worker에만 연결 시간과 플래너 설정이 있어도 같은 DB로 본다' '
  write_database_env api jdbc:postgresql://rds:5432/salmonbus
  write_database_env worker "jdbc:postgresql://rds:5432/salmonbus?connectTimeout=5&socketTimeout=300&tcpKeepAlive=true&options=-c%20join_collapse_limit%3D20%20-c%20from_collapse_limit%3D20%20-c%20geqo_threshold%3D20"
  transition_same_database "$TEST_DIRECTORY/api.env" "$TEST_DIRECTORY/worker.env"
  write_database_env worker "jdbc:postgresql://rds:5432/salmonbus?options=-cjoin_collapse_limit=20+-c+geqo_threshold=20"
  transition_same_database "$TEST_DIRECTORY/api.env" "$TEST_DIRECTORY/worker.env"
'
run_case '호스트·포트·DB·계정이 하나라도 다르면 거절한다' '
  write_database_env api jdbc:postgresql://rds:5432/salmonbus
  write_database_env worker jdbc:postgresql://other:5432/salmonbus
  expect_blocked transition_same_database "$TEST_DIRECTORY/api.env" "$TEST_DIRECTORY/worker.env"
  write_database_env worker jdbc:postgresql://rds:5433/salmonbus
  expect_blocked transition_same_database "$TEST_DIRECTORY/api.env" "$TEST_DIRECTORY/worker.env"
  write_database_env worker jdbc:postgresql://rds:5432/other
  expect_blocked transition_same_database "$TEST_DIRECTORY/api.env" "$TEST_DIRECTORY/worker.env"
  write_database_env worker jdbc:postgresql://rds:5432/salmonbus y
  expect_blocked transition_same_database "$TEST_DIRECTORY/api.env" "$TEST_DIRECTORY/worker.env"
'
run_case '스키마·보안 옵션이나 허용하지 않은 세션 설정이 있으면 거절한다' '
  write_database_env api jdbc:postgresql://rds:5432/salmonbus
  for query in currentSchema=other sslmode=disable "options=-c%20search_path%3Dother" \
      "options=-c%20join_collapse_limit%3D20%20-c%20search_path%3Dother" "options=-c" "options=join_collapse_limit%3D20" \
      "socketTimeout=abc" "tcpKeepAlive=yes" "connectTimeout" \
      "options=-c%20join_collapse_limit%3D20%0A-c%20search_path%3Dother" \
      "options=-c%20join_collapse_limit%3D20%0D%0A-c%20search_path%3Dother" \
      "options=-c%20join_collapse_limit%3D20\\n-c%20search_path%3Dother"; do
    write_database_env worker "jdbc:postgresql://rds:5432/salmonbus?$query"
    expect_blocked transition_same_database "$TEST_DIRECTORY/api.env" "$TEST_DIRECTORY/worker.env"
  done
  write_database_env worker jdbc:postgresql://rds:5432/salmonbus
  write_database_env api "jdbc:postgresql://rds:5432/salmonbus?currentSchema=other"
  expect_blocked transition_same_database "$TEST_DIRECTORY/api.env" "$TEST_DIRECTORY/worker.env"
'
run_case 'DB 대상이 다르면 worker를 중지하기 전에 prepare를 거절한다' '
  RUNNING=active
  cp /etc/salmonbus/worker.env "$TEST_DIRECTORY/worker.env.saved"
  sed -i "s#^DB_URL=.*#DB_URL=jdbc:postgresql://other:5432/salmonbus#" /etc/salmonbus/worker.env
  rm -f /etc/salmonbus/evaluation-work-hold
  expect_blocked transition_prepare test-backup
  cp "$TEST_DIRECTORY/worker.env.saved" /etc/salmonbus/worker.env
  test "$RUNNING" = active
  test ! -e /etc/salmonbus/evaluation-work-hold
'
echo "======== 전환 차단 검사: 통과 $PASS · 실패 0 ========"
