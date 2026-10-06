#!/usr/bin/env bash
set -euo pipefail
umask 077

source /local-tools/settings.sh
LOCAL_DIR=/local-settings
LOCAL_ENV_DIR="$LOCAL_DIR/env"
init_settings
chown 10001:10001 "$LOCAL_DIR" "$LOCAL_ENV_DIR"
for component in postgres mongodb api worker; do
  chown 10001:10001 "$LOCAL_ENV_DIR/$component.env"
done
