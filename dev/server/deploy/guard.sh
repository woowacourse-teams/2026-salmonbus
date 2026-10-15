#!/usr/bin/env bash
set -euo pipefail

SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE="$(cd "$SCRIPTS/.." && pwd)"
HOOK="${1:?hook is required}"
case "$HOOK" in preflight|install|start|validate) ;; *) exit 64 ;; esac

TOOLS=/opt/salmonbus-dev/tools/shared-dev-tools.jar
if [[ -f "$BUNDLE/basis/tools/shared-dev-tools.jar" ]]; then
  TOOLS="$BUNDLE/basis/tools/shared-dev-tools.jar"
fi

dev_prepare() {
  /usr/bin/env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u CLASSPATH \
    /usr/bin/java -Xms16m -Xmx96m -jar "$TOOLS" "$1" --bundle "$BUNDLE"
}

recover_failure() {
  local code="$1"
  dev_prepare recover-deployment || {
    echo 'DEV_PREPARATION_RECOVERY_REQUIRED' >&2
    exit 1
  }
  exit "$code"
}

if [[ "$HOOK" == preflight && "${DEPLOYMENT_GROUP_NAME:-}" == salmonbus-backend-dev-api ]]; then
  dev_prepare prepare-deployment || recover_failure "$?"
fi
if bash "$SCRIPTS/$HOOK-body.sh"; then
  if [[ "$HOOK" == validate ]]; then
    dev_prepare complete-deployment || recover_failure "$?"
  fi
else
  recover_failure "$?"
fi
