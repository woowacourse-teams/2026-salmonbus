#!/usr/bin/env bash
# 실제 common.sh의 잠금 코드만 임시 폴더와 별도 셸에서 검사한다.
# AWS·systemd·/opt/salmonbus는 사용하지 않는다. Linux 리허설에서 실행한다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/test-deploy-lock.sh"
TIMEOUT="$(command -v timeout || command -v gtimeout || true)"
if [ -z "$TIMEOUT" ]; then
  echo "GNU timeout이 필요하다. Docker 리허설 run.sh로 실행한다." >&2
  exit 1
fi

TESTS=(
  successful_first_hook_keeps_lock
  failed_first_hook_cleans_lock
  following_hook_registers_exit_trap
  failed_following_hook_cleans_lock
  failed_command_in_following_hook_cleans_lock
  successful_following_hook_keeps_lock
  four_separate_hooks_clean_lock_on_last_failure
  successful_validation_releases_lock
  next_component_can_claim_after_failure
  failure_cleanup_preserves_another_owner
  other_component_is_rejected
  same_component_takeover_keeps_cleanup
  stale_lock_of_other_component_is_taken_over
  live_lock_of_other_component_is_not_taken_over
  following_hook_refreshes_lock_age
)

fail() { echo "실패: $*" >&2; exit 1; }

run_hook() {
  local body="$1" component="${2:-api}" deployment="${3:-d-api-1}"
  # 호출마다 별도 bash 프로세스를 실행한다. 다음 훅이 첫 훅의 EXIT trap을 이어받지 않도록 해야 한다.
  # 파이프 버퍼 때문에 대기하지 않도록 출력을 파일로 받는다. 실행은 10초로 제한한다.
  if "$TIMEOUT" --kill-after=1s 10s bash -c '
    set -euo pipefail
    ROOT="$1"; COMPONENT="$2"; DEPLOYMENT_ID="$3"
    log() { printf "%s\n" "$*"; }
    source "$4"
    eval "$5"
  ' hook "$CASE_ROOT" "$component" "$deployment" "$LOCK_SOURCE" "$body" \
      > "$HOOK_OUTPUT" 2>&1; then
    HOOK_RC=0
  else
    HOOK_RC=$?
  fi
}

assert_exit() {
  if [ "$HOOK_RC" -ne "$1" ]; then
    cat "$HOOK_OUTPUT" >&2
    fail "종료 코드: 기대=$1 실제=$HOOK_RC"
  fi
}

assert_owner() {
  local component="" deployment="" created=""
  [ -f "$MARKER/owner" ] || fail "잠금 소유자 파일이 없다"
  read -r component deployment created < "$MARKER/owner"
  [ "$component $deployment" = "$1 $2" ] \
    || fail "잠금 소유자: 기대=$1 $2 실제=$component $deployment"
}

assert_released() {
  [ ! -e "$MARKER" ] || fail "실패한 배포의 잠금이 남았다"
}

claim_first_hook() {
  run_hook 'claim_deploy'
  assert_exit 0
  [ -d "$MARKER" ] || fail "성공한 첫 훅이 잠금을 남기지 않았다"
}

successful_first_hook_keeps_lock() {
  claim_first_hook
  assert_owner api d-api-1
}

failed_first_hook_cleans_lock() {
  run_hook $'claim_deploy\nexit 47'
  assert_exit 47
  assert_released
}

following_hook_registers_exit_trap() {
  claim_first_hook
  run_hook $'claim_deploy\ntrap -p EXIT'
  assert_exit 0
  grep -q '_release_on_failure' "$HOOK_OUTPUT" || fail "후속 훅의 EXIT trap이 없다"
}

failed_following_hook_cleans_lock() {
  claim_first_hook
  run_hook $'claim_deploy\nexit 47'
  assert_exit 47
  assert_released
}

failed_command_in_following_hook_cleans_lock() {
  claim_first_hook
  run_hook $'claim_deploy\nfalse'
  assert_exit 1
  assert_released
}

successful_following_hook_keeps_lock() {
  claim_first_hook
  run_hook 'claim_deploy'
  assert_exit 0
  assert_owner api d-api-1
}

four_separate_hooks_clean_lock_on_last_failure() {
  claim_first_hook
  local i
  for i in 1 2; do
    run_hook 'claim_deploy'
    assert_exit 0
    assert_owner api d-api-1
  done
  run_hook $'claim_deploy\nexit 47'
  assert_exit 47
  assert_released
}

successful_validation_releases_lock() {
  claim_first_hook
  run_hook $'claim_deploy\nrelease_deploy'
  assert_exit 0
  assert_released
}

next_component_can_claim_after_failure() {
  claim_first_hook
  run_hook $'claim_deploy\nexit 47'
  assert_exit 47
  run_hook 'claim_deploy' worker d-worker-1
  assert_exit 0
  assert_owner worker d-worker-1
}

failure_cleanup_preserves_another_owner() {
  claim_first_hook
  run_hook 'claim_deploy
    printf "worker d-worker-2 %s\n" "$(date +%s)" > "$MARKER/owner"
    exit 47'
  assert_exit 47
  assert_owner worker d-worker-2
}

other_component_is_rejected() {
  claim_first_hook
  run_hook 'claim_deploy' worker d-worker-1
  assert_exit 1
  assert_owner api d-api-1
}

same_component_takeover_keeps_cleanup() {
  claim_first_hook
  run_hook $'claim_deploy\nexit 47' api d-api-rollback
  assert_exit 47
  assert_released
}

# 잠금 디렉터리의 mtime 을 과거로 돌린다. 나이 판정이 이 값을 본다
age_lock() { touch -d "-$1 seconds" "$MARKER"; }

stale_lock_of_other_component_is_taken_over() {
  claim_first_hook
  age_lock 1200
  run_hook 'claim_deploy' worker d-worker-1
  assert_exit 0
  assert_owner worker d-worker-1
  grep -q '버리고 다시 잡는다' "$HOOK_OUTPUT" || fail "오래된 잠금을 버렸다는 로그가 없다"
}

live_lock_of_other_component_is_not_taken_over() {
  claim_first_hook
  age_lock 1200
  # 잠금은 20분 전에 만들었지만 api 의 다음 훅이 방금 왔다 갔다. 살아 있는 배포다
  run_hook 'claim_deploy' api d-api-1
  assert_exit 0
  run_hook 'claim_deploy' worker d-worker-1
  assert_exit 1
  assert_owner api d-api-1
  grep -q '진행 중이다' "$HOOK_OUTPUT" || fail "살아 있는 배포라서 멈췄다는 로그가 없다"
}

following_hook_refreshes_lock_age() {
  claim_first_hook
  age_lock 1200
  local before after
  before="$(stat -c %Y "$MARKER")"
  run_hook 'claim_deploy' api d-api-1
  assert_exit 0
  after="$(stat -c %Y "$MARKER")"
  [ "$after" -gt "$before" ] || fail "후속 훅이 잠금 mtime 을 갱신하지 않았다 (전=$before 후=$after)"
}

if [ "${1:-}" = "--case" ]; then
  [ "$#" -eq 3 ] || fail "내부 실행 인자가 잘못됐다"
  TEST_NAME="$2"
  COMMON="$3"
  FOUND=0
  for test_name in "${TESTS[@]}"; do
    [ "$test_name" != "$TEST_NAME" ] || FOUND=1
  done
  [ "$FOUND" -eq 1 ] || fail "등록되지 않은 테스트다: $TEST_NAME"

  # 검사마다 새 폴더를 만들고 해당 검사에서 만든 폴더만 정리한다.
  CASE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/salmonbus-deploy-lock.XXXXXX")"
  readonly CASE_ROOT
  trap 'rm -rf -- "$CASE_ROOT"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  MARKER="$CASE_ROOT/.deploying"
  LOCK_SOURCE="$CASE_ROOT/lock.sh"
  HOOK_OUTPUT="$CASE_ROOT/hook.out"
  # 운영 ROOT 초기화와 나머지 배포 코드는 실행하지 않는다. 잠금 로직은 복제하지 않는다.
  awk '
    /^MARKER="\$ROOT\/\.deploying"$/ { found_start=1 }
    /^release_path\(\) \{/ { found_end=1; exit }
    found_start { print }
    END { if (!found_start || !found_end) exit 1 }
  ' "$COMMON" > "$LOCK_SOURCE" || fail "common.sh의 잠금 구간을 찾지 못했다"
  "$TEST_NAME"
  exit 0
fi

[ "$#" -le 1 ] || fail "사용법: bash test-deploy-lock.sh [검사할 common.sh 경로]"
COMMON="${1:-$HERE/../scripts/common.sh}"
COMMON="$(cd "$(dirname "$COMMON")" && pwd)/$(basename "$COMMON")"
[ -f "$COMMON" ] || fail "common.sh를 찾지 못했다: $COMMON"
REPORT_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/salmonbus-lock-report.XXXXXX")"
readonly REPORT_ROOT
trap 'rm -rf -- "$REPORT_ROOT"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
PASS=0; FAIL=0
for test_name in "${TESTS[@]}"; do
  # 함수를 if 안에서 직접 호출하면 Bash의 errexit가 꺼진다. 별도 셸에서 검사한다.
  if "$TIMEOUT" --kill-after=1s 45s bash "$SCRIPT" --case "$test_name" "$COMMON" \
      > "$REPORT_ROOT/$test_name.out" 2>&1; then
    echo "  통과: $test_name"
    PASS=$((PASS + 1))
  else
    echo "  실패: $test_name"
    cat "$REPORT_ROOT/$test_name.out"
    FAIL=$((FAIL + 1))
  fi
done
echo "잠금 테스트: 통과 $PASS · 실패 $FAIL"
[ "$FAIL" -eq 0 ]
