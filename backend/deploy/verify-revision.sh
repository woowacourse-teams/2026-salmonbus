#!/usr/bin/env bash
# 배포판에 비밀이 섞였는지 본다. CodeBuild 가 부르고 예행연습이 같은 파일을 돌린다.
#
# scripts/ 아래가 아니라서 배포판에 안 담기고 EC2 로 안 나간다.
# 값은 한 번도 안 찍는다. 걸렸을 때도 어느 열인지만 말한다
set -euo pipefail

root="${1:?배포판 폴더를 달라}"

fail() { echo "비밀 검사 실패: $*" >&2; exit 1; }

if find "$root" \( -name '.env*' -o -name '*.pem' -o -name '*.jks' -o -name '*.p12' \) \
     -print -quit | grep -q .; then
  fail "비밀 파일이 배포판에 들어 있다"
fi

shopt -s nullglob
jars=("$root"/*/jars/*.jar "$root"/jars/*.jar)
[ "${#jars[@]}" -gt 0 ] || fail "$root 에 JAR 이 없다"

for jar in "${jars[@]}"; do
  if unzip -l "$jar" | grep -qiE '\.env|secret|\.pem|\.jks'; then
    fail "$(basename "$jar") 의 항목 이름에 비밀로 보이는 것이 있다"
  fi

  for yml in $(unzip -Z1 "$jar" | grep -E '^BOOT-INF/classes/application.*\.(ya?ml|properties)$' || true); do
    # 줄마다 본다. 파일 전체로 보면 같은 열이 다른 줄에서 자리표시자를 쓰는 것만으로 통과한다
    offending="$(unzip -p "$jar" "$yml" \
      | grep -E '^[[:space:]]*(url|username|password|service-key)[[:space:]]*:' \
      | grep -v '\${' || true)"
    if [ -n "$offending" ]; then
      keys="$(printf '%s\n' "$offending" \
        | sed -E 's/^[[:space:]]*([A-Za-z-]+)[[:space:]]*:.*/\1/' | tr '\n' ' ')"
      fail "$(basename "$jar") 의 $yml 에 자리표시자가 아닌 값이 있다 (열: $keys)"
    fi
  done
done

echo "비밀 검사 통과"
