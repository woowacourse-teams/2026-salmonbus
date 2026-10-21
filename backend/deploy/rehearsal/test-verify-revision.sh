#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf -- "$scratch"' EXIT
mkdir -p "$scratch/revision/jars"
passed=0
check() {
  local name="$1" expected="$2" body="$3" actual=0
  bash "$here/makejar.sh" "$scratch/revision/jars/api-app.jar" api-app "$body" '2026-10-17T00:00:00Z'
  bash "$here/../verify-revision.sh" "$scratch/revision" > "$scratch/result" 2>&1 || actual=$?
  if { [ "$expected" = pass ] && [ "$actual" -ne 0 ]; } || { [ "$expected" = fail ] && [ "$actual" -eq 0 ]; }; then
    echo "실패: $name" >&2; exit 1
  fi
  if grep -q 'sentinel-secret' "$scratch/result"; then
    echo "실패: 오류 출력에 값이 포함됐다" >&2; exit 1
  fi
  passed=$((passed + 1)); echo "통과: $name"
}
docs=$'springdoc:\n  swagger-ui:\n    url: /api/docs/openapi\n'
check '고정 Swagger 문서 경로는 허용한다' pass "$docs"
check 'DB 환경변수는 허용한다' pass $'spring:\n  datasource:\n    url: ${DB_URL}\n    username: ${DB_USERNAME}\n    password: ${DB_PASSWORD}\n'
check 'Swagger 아래라도 다른 URL은 차단한다' fail $'springdoc:\n  swagger-ui:\n    url: sentinel-secret\n'
check '같은 경로여도 datasource의 URL은 차단한다' fail $'spring:\n  datasource:\n    url: /api/docs/openapi\n'
check '다른 상위 설정 아래의 Swagger 경로는 차단한다' fail $'other:\n  springdoc:\n    swagger-ui:\n      url: /api/docs/openapi\n'
check 'Swagger 뒤의 DB URL은 차단한다' fail "$docs"$'spring:\n  datasource:\n    url: sentinel-secret\n'
check 'Swagger 형제 설정의 URL은 차단한다' fail "$docs"$'  other:\n    url: /api/docs/openapi\n'
check '같은 문서의 비밀번호는 차단한다' fail "$docs"$'spring:\n  datasource:\n    password: sentinel-secret\n'
check '같은 문서의 사용자 이름은 차단한다' fail "$docs"$'spring:\n  datasource:\n    username: sentinel-secret\n'
check '같은 문서의 서비스키는 차단한다' fail "$docs"$'gbis:\n  service-key-b: sentinel-secret\n'
check '같은 문서의 새 형식 서비스키는 차단한다' fail "$docs"$'gbis:\n  keys:\n    e: sentinel-secret\n'
check '한 줄로 적은 새 형식 서비스키도 차단한다' fail "$docs"$'gbis:\n  keys: e=sentinel-secret\n'
check '별칭 서비스키 맵도 차단한다' fail "$docs"$'gbis:\n  named-keys:\n    e: sentinel-secret\n'
check '새 형식 서비스키의 환경변수 자리표시자는 허용한다' pass "$docs"$'gbis:\n  keys:\n    e: ${GBIS_KEYS_E:}\n'
check '같은 위치라도 뒤에 숨긴 URL은 차단한다' fail "$docs"$'    url: sentinel-secret\n'
echo "비밀 검사 회귀 테스트: $passed 통과"
