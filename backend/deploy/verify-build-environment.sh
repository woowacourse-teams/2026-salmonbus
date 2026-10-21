#!/usr/bin/env bash
# 빌드 전 필수 도구와 소스 배치를 두 빌드 환경에서 같은 기준으로 확인한다.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here/.."
for tool in java unzip sha256sum awk find; do
  command -v "$tool" >/dev/null || { echo "$tool 가 없다" >&2; exit 1; }
done
java_version="$(java -version 2>&1)"
grep -Eq 'version "21[.]' <<< "$java_version" || { echo 'Java 21이 필요하다' >&2; exit 1; }
test ! -d src || { echo 'backend/src에 루트 소스가 남아 있다' >&2; exit 1; }
