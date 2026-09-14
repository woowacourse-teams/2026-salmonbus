#!/usr/bin/env bash
# 배포 리허설용 가짜 JAR이다. 실제 bootJar나 컴파일된 클래스를 만들지 않는다.
# 같은 인자는 같은 ZIP이 되도록 항목 순서·내용·시각·파일 권한을 고정한다.
set -euo pipefail

if [ "$#" -ne 4 ]; then
  printf '사용법: bash makejar.sh <outputPath> <artifact> <body> <builtAt>\n' >&2
  exit 64
fi

output="$1"
artifact="$2"
body="$3"
built_at="$4"
# 작업 디렉터리를 옮겨도 상대 경로와 '-'로 시작하는 파일명을 그대로 처리한다.
case "$output" in
  /*) ;;
  *) output="$PWD/$output" ;;
esac
[ ! -d "$output" ] || { printf '출력 경로는 파일이어야 한다\n' >&2; exit 1; }
command -v zip >/dev/null 2>&1 || { printf 'zip 명령이 필요하다\n' >&2; exit 1; }

scratch_root="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
scratch="$(mktemp -d "$scratch_root/salmonbus-makejar.XXXXXXXX")"
cleanup() {
  local status=$?
  # 이 실행이 mktemp로 만든 경로만 지운다. 상위 임시 디렉터리는 대상이 아니다.
  case "$scratch" in
    "$scratch_root"/salmonbus-makejar.*)
      [ ! -L "$scratch" ] && rm -rf -- "$scratch" ;;
    *) printf '임시 경로가 예상과 달라 정리하지 않았다\n' >&2 ;;
  esac
  return "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

(
  cd "$scratch"
  export TZ=UTC LC_ALL=C
  # 호출자의 zip 옵션이 항목이나 메타데이터를 바꾸지 않게 한다.
  unset ZIPOPT
  mkdir -p META-INF BOOT-INF/classes/com/gustler
  printf '%s\n' \
    'Manifest-Version: 1.0' \
    'Main-Class: org.springframework.boot.loader.launch.JarLauncher' \
    > META-INF/MANIFEST.MF
  printf 'build.artifact=%s\nbuild.group=com.gustler\nbuild.name=%s\nbuild.time=%s\nbuild.version=0.0.1-SNAPSHOT\n' \
    "$artifact" "$artifact" "$built_at" > META-INF/build-info.properties
  printf '%s' "$body" > BOOT-INF/classes/application.yml
  : > BOOT-INF/classes/com/gustler/Main.class
  for ((i = 0; i < 40; i++)); do
    printf '%s' "$body" >> BOOT-INF/classes/com/gustler/Main.class
  done

  entries=(
    META-INF/MANIFEST.MF
    META-INF/build-info.properties
    BOOT-INF/classes/application.yml
    BOOT-INF/classes/com/gustler/Main.class
  )
  chmod 644 "${entries[@]}"
  touch -t 198002010000.00 "${entries[@]}"
  zip -q -X archive.zip "${entries[@]}"
)

# 기존 ZIP을 갱신하면 불필요한 옛 항목이 남는다. 새 ZIP으로 파일 자체를 교체한다.
mv -f "$scratch/archive.zip" "$output"
