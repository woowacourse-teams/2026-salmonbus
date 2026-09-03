#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'usage: %s SALMONBUS_ANALYSIS_REPOSITORY OUTPUT_JSON\n' "$0" >&2
  exit 2
fi

readonly ANALYSIS_REPOSITORY=$1
readonly OUTPUT_JSON=$2
readonly ANALYSIS_COMMIT="c5ff99da81ed0af9916bd5aa5115d89f49258e2c"
readonly PYTHON_EXECUTABLE=${V41_PYTHON_BIN:-python3}
readonly AWS_READ_PROFILE=${AWS_PROFILE:-salmonbus-admin}
readonly AWS_READ_REGION=${AWS_REGION:-ap-northeast-2}
readonly COLLECTOR_BUCKET="salmonbus-collector-827325854159-apne2"
readonly SCRIPT_DIRECTORY=$(cd "$(dirname "$0")" && pwd -P)

git -C "$ANALYSIS_REPOSITORY" cat-file -e "$ANALYSIS_COMMIT^{commit}"
if [[ -e "$OUTPUT_JSON" || -L "$OUTPUT_JSON" ]]; then
  printf 'output already exists: %s\n' "$OUTPUT_JSON" >&2
  exit 1
fi

task_tmp_link=$(mktemp -d)
task_tmp=$(cd "$task_tmp_link" && pwd -P)
cleanup() {
  find "$task_tmp" -depth ! -type d -delete 2>/dev/null || true
  find "$task_tmp" -depth -type d -empty -delete 2>/dev/null || true
}
trap cleanup EXIT

git -C "$ANALYSIS_REPOSITORY" archive "$ANALYSIS_COMMIT" server/evaluator | tar -x -C "$task_tmp"

export AWS_PROFILE="$AWS_READ_PROFILE"
export AWS_REGION="$AWS_READ_REGION"
export PYTHONPATH="$task_tmp/server/evaluator"

"$PYTHON_EXECUTABLE" "$SCRIPT_DIRECTORY/audit_source.py" \
  --bucket "$COLLECTOR_BUCKET" \
  --region "$AWS_READ_REGION" \
  --from-date 2026-08-14 \
  --through-date 2026-09-01 \
  --protocol "$task_tmp/server/evaluator/current_public/evaluation/protocol.json" \
  --workers "${V41_SOURCE_WORKERS:-8}" \
  --output "$OUTPUT_JSON"
