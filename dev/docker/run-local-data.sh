#!/usr/bin/env bash
set -euo pipefail
source /local-tools/settings.sh
load_settings worker
case ${1:-prepare} in
  prepare) task=prepareLocalData ;;
  reset) task=resetLocalData ;;
  *) fail 'prepare 또는 reset으로 실행해 주세요.' ;;
esac
exec ./gradlew --offline --init-script /local/data/local-data.init ":worker-app:$task" --no-daemon --console=plain
