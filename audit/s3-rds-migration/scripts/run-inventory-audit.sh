#!/bin/zsh
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'usage: %s SALMONBUS_ANALYSIS_REPOSITORY OUTPUT_JSON\n' "$0" >&2
  exit 2
fi

readonly ANALYSIS_REPOSITORY=$1
readonly OUTPUT_JSON=$2
readonly ANALYSIS_COMMIT="c5ff99da81ed0af9916bd5aa5115d89f49258e2c"
readonly SOURCE_PROFILE="default"
readonly SOURCE_REGION="ap-northeast-2"
readonly SOURCE_BUCKET="salmonbus-collector-827325854159-apne2"
readonly BASE_CUTOFF_KST_DATE="2026-09-01"
readonly SCRIPT_DIRECTORY=${0:a:h}

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

git -C "$ANALYSIS_REPOSITORY" archive \
  "$ANALYSIS_COMMIT" \
  server/evaluator/current_public/evaluation/protocol.json \
  | tar -x -C "$task_tmp"

python3 "$SCRIPT_DIRECTORY/audit_inventory.py" \
  --bucket "$SOURCE_BUCKET" \
  --profile "$SOURCE_PROFILE" \
  --region "$SOURCE_REGION" \
  --through-date "$BASE_CUTOFF_KST_DATE" \
  --route-reference-protocol \
    "$task_tmp/server/evaluator/current_public/evaluation/protocol.json" \
  --workers "${S3_AUDIT_WORKERS:-16}" \
  --output "$OUTPUT_JSON"
