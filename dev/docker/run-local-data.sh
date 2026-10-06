#!/usr/bin/env bash
set -euo pipefail
source /local-tools/settings.sh
load_settings worker
exec ./gradlew --offline --init-script /local/data/local-data.init :worker-app:prepareLocalData --no-daemon --console=plain
