#!/usr/bin/env bash
# CodeBuild와 GitHub CI가 같은 실행 JAR로 같은 배포 패키지를 만든다.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here/.."
commit="${1:?배포할 커밋 SHA를 지정하세요}"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo '커밋 SHA 형식이 잘못됐다' >&2; exit 1; }
bash deploy/verify-build-environment.sh

# 재시작 여부는 JAR 지문으로 못 잰다. buildInfo() 가 넣는 build.time 때문에
# 같은 소스를 다시 빌드해도 JAR SHA-256 이 달라진다. 실측으로 확인했다.
# 업무 모듈·GBIS까지 포함한 입력 목록을 리허설과 공유한다.
# 테스트·문서·프론트·maintenance 전용 변경은 두 서버를 재시작하지 않는다.
source deploy/runtime-source-inputs.sh
API_DIGEST="$(runtime_source_digest api)"
WORKER_DIGEST="$(runtime_source_digest worker)"

# build 는 실행 못 하는 -plain.jar 도 같이 만든다. 이름으로 걸러낸다
API_JAR="$(ls api-app/build/libs/api-app-*.jar | grep -v -- '-plain\.jar$')"
WORKER_JAR="$(ls worker-app/build/libs/worker-app-*.jar | grep -v -- '-plain\.jar$')"

# 스키마 정본은 common 한 곳이라 두 앱이 같은 값을 싣는다
FLYWAY_MAX="$(ls common/src/main/resources/db/migration/V*__*.sql \
  | sed 's#.*/V\([0-9][0-9]*\)__.*#\1#' | sort -n | tail -1)"
APP_VERSION="$(unzip -p "$API_JAR" META-INF/build-info.properties | sed -n 's/^build.version=//p')"
JAVA_VERSION="$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')"

pack() {
  local component="$1" jar="$2" digest="$3"
  local out="build/revision/$component"
  rm -rf "$out"; mkdir -p "$out/jars" "$out/scripts" "$out/systemd" "$out/sql"
  cp "$jar" "$out/jars/$component-app.jar"
  cp "deploy/appspec-$component.yml" "$out/appspec.yml"
  cp deploy/scripts/*.sh "$out/scripts/"; chmod +x "$out/scripts/"*.sh
  cp deploy/sql/*evaluation-work*.sql "$out/sql/"
  cp "deploy/systemd/salmonbus-$component.service" "$out/systemd/"
  {
    echo "component=$component"
    echo "commit=${commit}"
    echo "sourceDigest=$digest"
    echo "artifactSha256=$(sha256sum "$out/jars/$component-app.jar" | cut -d' ' -f1)"
    echo "javaVersion=$JAVA_VERSION"
    echo "appVersion=$APP_VERSION"
    echo "flywayMaxVersion=$FLYWAY_MAX"
    echo "builtAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$out/release-manifest.txt"
  echo "--- $component ---"; cat "$out/release-manifest.txt"
}

pack api    "$API_JAR"    "$API_DIGEST"
pack worker "$WORKER_JAR" "$WORKER_DIGEST"

bash deploy/verify-revision.sh build/revision
