#!/usr/bin/env bash
set -euo pipefail
dev_tools() {
  /usr/bin/env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u CLASSPATH \
    /usr/bin/java -Xms16m -Xmx64m -jar /opt/salmonbus-dev/tools/shared-dev-tools.jar "$@"
}
dev_tools verify-target --bundle "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$(dirname "${BASH_SOURCE[0]}")/production-common.sh"

ROOT=/opt/salmonbus-dev
BASE="$ROOT/$COMPONENT"
STAGING="$BASE/staging"
RELEASES="$BASE/releases"
CURRENT="$BASE/current"
PREVIOUS="$BASE/previous"
CHANGED="$BASE/.changed"
ENV_FILE="/etc/salmonbus-dev/$COMPONENT.env"
MODEL_DIRECTORY=/srv/salmonbus-dev/models/reference-20261002
JAR="$CURRENT/jars/$COMPONENT-app.jar"
MARKER="$ROOT/.deploying"

db_probe() { return 0; }
