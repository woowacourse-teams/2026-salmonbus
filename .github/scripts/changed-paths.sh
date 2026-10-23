#!/usr/bin/env bash
# 이번 실행이 검사할 변경에 CHANGED_PATH_PATTERNS 의 경로 패턴이 걸리는지 GITHUB_OUTPUT 의 relevant 로 알린다.
#
#   CHANGED_PATH_PATTERNS='backend/* dev/server/api.yml' bash .github/scripts/changed-paths.sh
#
# 워크플로 paths 필터 대신 쓴다. paths 필터로 워크플로가 아예 돌지 않으면 필수 체크가 대기 상태로 남아
# 머지를 막는다. 잡은 항상 돌고, 이 결과가 false 면 검사 단계를 건너뛰고 같은 이름의 체크를 성공으로 남긴다.
#
# 패턴은 공백으로 나누고 bash [[ == ]] 글롭으로 대조한다. '*' 는 '/' 도 넘는다.
# 변경 목록을 정하지 못하면(수동 실행, 브랜치 첫 push, 강제 push 로 이전 커밋을 받지 못함) 검사 대상으로 본다.
set -euo pipefail

report() {
  echo "relevant=$1" >> "$GITHUB_OUTPUT"
  echo "$2"
  exit 0
}

# read 로 나눠야 패턴이 작업 트리의 파일 이름으로 펼쳐지지 않는다.
read -r -a patterns <<< "${CHANGED_PATH_PATTERNS:-}"
[ "${#patterns[@]}" -gt 0 ] || { echo 'CHANGED_PATH_PATTERNS 가 비어 있다' >&2; exit 64; }

case "${GITHUB_EVENT_NAME:-}" in
  pull_request)
    # actions/checkout 은 PR 을 대상 브랜치에 병합한 커밋을 받는다(fetch-depth 2 필요).
    # 첫 부모가 대상 브랜치라 둘의 차이가 이 PR 이 병합으로 바꾸는 파일이다.
    base="$(git rev-parse --verify --quiet 'HEAD^1')" || report true '병합 커밋의 부모를 찾지 못해 검사 대상으로 본다.'
    ;;
  push)
    base="${BEFORE_SHA:-}"
    if [ -z "$base" ] || [ "$base" = 0000000000000000000000000000000000000000 ]; then
      report true '이전 커밋이 없는 push 라 검사 대상으로 본다.'
    fi
    git fetch --no-tags --quiet --depth=1 origin "$base" || report true "이전 커밋 $base 를 받지 못해 검사 대상으로 본다."
    ;;
  *)
    report true "${GITHUB_EVENT_NAME:-알 수 없는} 이벤트라 검사 대상으로 본다."
    ;;
esac

# 이름 바꾸기를 삭제와 추가로 나눠야 옮겨 나간 쪽 경로도 목록에 남는다.
# 기본 출력은 한글 등 비ASCII 경로를 따옴표와 8진수로 감싸 패턴에 맞지 않는다. 원래 이름을 NUL 로 나눠 받는다.
changed="$(mktemp)"
git -c core.quotePath=false diff --name-only --no-renames -z "$base" HEAD > "$changed" \
  || report true '변경 목록을 만들지 못해 검사 대상으로 본다.'
while IFS= read -r -d '' file; do
  [ -n "$file" ] || continue
  for pattern in "${patterns[@]}"; do
    # shellcheck disable=SC2053 # 패턴을 글롭으로 대조한다.
    if [[ "$file" == $pattern ]]; then
      report true "검사 대상 변경: $file"
    fi
  done
done < "$changed"
report false '검사 대상 경로의 변경이 없다. 검사 단계를 건너뛰고 성공으로 남긴다.'
