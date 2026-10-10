#!/usr/bin/env bash
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
claim_deploy

major="$(/usr/bin/java -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
[[ $major == 21 ]] || { log 'Java 21을 확인해 주세요.'; exit 1; }
id "$SERVICE_USER" >/dev/null 2>&1 || { log '앱 실행 계정을 확인해 주세요.'; exit 1; }
if [[ $COMPONENT == worker ]]; then
  [[ -f $MODEL_DIRECTORY/manifest.json && -f $MODEL_DIRECTORY/weights.safetensors ]] || {
    log 'dev 모델 준비가 필요합니다.'; exit 1;
  }
fi

mkdir -p "$BASE" "$RELEASES"
available=$(df --output=avail -m "$BASE" | tail -1)
[[ ${available:-0} -ge 1024 ]] || { log '배포할 디스크 공간이 부족합니다.'; exit 1; }
log "$COMPONENT dev 배포 준비 확인"
