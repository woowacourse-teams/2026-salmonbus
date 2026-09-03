#!/usr/bin/env bash
set -euo pipefail

readonly PRODUCER_COMMIT="c5ff99da81ed0af9916bd5aa5115d89f49258e2c"
readonly BUCKET="salmonbus-collector-827325854159-apne2"
readonly SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
if [[ $# -lt 2 || $# -gt 3 ]]; then
  printf 'usage: %s SALMONBUS_ANALYSIS_REPOSITORY OUTPUT_JSON [SNAPSHOT_AT_UTC]\n' "$0" >&2
  exit 2
fi
readonly ANALYSIS_REPOSITORY=$1
readonly OUTPUT_JSON=$2
readonly SNAPSHOT_AT_UTC=${3:-2026-09-02T13:00:00Z}
readonly AWS_READ_PROFILE=${AWS_PROFILE:-salmonbus-admin}
readonly AWS_READ_REGION=${AWS_REGION:-ap-northeast-2}
readonly BUILD_WORKERS=${V41_BUILD_WORKERS:-8}
if [[ -e "$OUTPUT_JSON" || -L "$OUTPUT_JSON" ]]; then
  printf 'output already exists: %s\n' "$OUTPUT_JSON" >&2
  exit 1
fi
task_directory=$(mktemp -d)
cleanup() { find "$task_directory" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
git -C "$ANALYSIS_REPOSITORY" archive "$PRODUCER_COMMIT" server/evaluator | tar -x -C "$task_directory"
export AWS_PROFILE="$AWS_READ_PROFILE"
export AWS_REGION="$AWS_READ_REGION"
export PYTHONPATH="$task_directory/server/evaluator:$SCRIPT_DIRECTORY"
python3 "$SCRIPT_DIRECTORY/audit_route_catchup.py" \
  --bucket "$BUCKET" \
  --region "$AWS_READ_REGION" \
  --date 2026-09-02 \
  --snapshot-at "$SNAPSHOT_AT_UTC" \
  --boundary-3330 2026-09-02T10:27:52.390820Z \
  --boundary-1650-lower 2026-09-02T12:49:33.041299Z \
  --boundary-1650-upper 2026-09-02T12:49:35.539364Z \
  --protocol "$task_directory/server/evaluator/current_public/evaluation/protocol.json" \
  --workers "$BUILD_WORKERS" \
  --output "$OUTPUT_JSON"
