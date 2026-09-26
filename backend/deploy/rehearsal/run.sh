#!/usr/bin/env bash
# 훅 스크립트와 런타임 소스 지문을 리눅스 컨테이너에서 예행연습한다.
#
#   bash backend/deploy/rehearsal/run.sh
#
# systemctl · curl · java를 모의해 배포·실패·재배포 계약을 확인한다.
# 실제 Boot JAR 기동과 DB 전환·백업 복원 검증은 별도로 수행한다.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
docker run --rm \
  -v "$HERE":/rehearse:ro \
  -v "$HERE/..":/deploy:ro \
  amazonlinux:2023 bash /rehearse/rehearse.sh
