#!/usr/bin/env bash
# 훅 스크립트를 리눅스 컨테이너에서 예행연습한다. AWS 없이 돌릴 수 있는 유일한 검증이다.
#
#   bash backend/deploy/rehearsal/run.sh
#
# systemctl · curl · java 를 흉내로 바꿔 끼우고 배포를 네 번 돌린다.
# 첫 배포, api 만 바뀐 배포, 아무것도 안 바뀐 배포, health 가 안 오르는 배포다.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
docker run --rm \
  -v "$HERE":/rehearse:ro \
  -v "$HERE/..":/deploy:ro \
  amazonlinux:2023 bash /rehearse/rehearse.sh
