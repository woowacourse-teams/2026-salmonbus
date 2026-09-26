#!/usr/bin/env bash
# 실행 JAR에 들어가는 프로젝트의 입력 목록이다. CodeBuild와 배포 리허설이 함께 사용한다.
# backend를 기준으로 상대 경로를 해시하므로 checkout 위치가 바뀌어도 지문은 같다.

runtime_source_inputs() {
  local component="${1:-}" module
  local modules=(common)
  case "$component" in
    api)
      modules+=(api-app)
      ;;
    worker)
      modules+=(worker-app business/route-catalog business/observations
        business/forecasting business/api-call-quota integrations/gbis-client)
      ;;
    *)
      printf '소스 지문을 계산할 서버는 api 또는 worker여야 한다: %s\n' "$component" >&2
      return 64
      ;;
  esac

  printf '%s\n' build.gradle settings.gradle gradle.properties gradle.lockfile \
    gradle/wrapper/gradle-wrapper.properties gradle/libs.versions.toml \
    deploy/runtime-source-inputs.sh
  for module in "${modules[@]}"; do
    printf '%s\n' "$module/src/main" "$module/build.gradle" "$module/gradle.lockfile"
  done
}

source_digest() (
  set -o pipefail
  [ "$#" -gt 0 ] || { printf '소스 지문 입력이 없다\n' >&2; return 64; }
  find "$@" -type f -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum \
    | sha256sum | cut -d' ' -f1
)

runtime_source_digest() (
  set -o pipefail
  local component="${1:-}" runtime_root="${2:-.}" input inputs
  local existing=()
  inputs="$(runtime_source_inputs "$component")" || return $?
  cd "$runtime_root" || return 1
  while IFS= read -r input; do
    # 모듈 이관 중 아직 생성하지 않은 디렉터리와 선택적인 Gradle 파일은 제외한다.
    # 나중에 파일이 생기면 같은 목록에서 자동으로 계산에 포함된다.
    [ ! -e "$input" ] || existing+=("$input")
  done <<< "$inputs"
  [ "${#existing[@]}" -gt 0 ] || { printf 'backend 런타임 입력을 찾지 못했다\n' >&2; return 1; }
  source_digest "${existing[@]}"
)

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  set -euo pipefail
  runtime_source_digest "${1:-}" "${2:-.}"
fi
