#!/usr/bin/env bash
set -euo pipefail

readonly PRODUCER_COMMIT="c5ff99da81ed0af9916bd5aa5115d89f49258e2c"
readonly BUCKET="salmonbus-collector-827325854159-apne2"
readonly SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
if [[ $# -ne 3 ]]; then
  printf 'usage: %s SALMONBUS_ANALYSIS_REPOSITORY OBSERVED_AT_UTC OUTPUT_JSON\n' "$0" >&2
  exit 2
fi
readonly ANALYSIS_REPOSITORY=$1
readonly OBSERVED_AT_UTC=$2
readonly OUTPUT_JSON=$3
readonly AWS_READ_PROFILE=${AWS_PROFILE:-salmonbus-admin}
readonly AWS_READ_REGION=${AWS_REGION:-ap-northeast-2}
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
export PYTHONPATH="$task_directory/server/evaluator"
python3 "$SCRIPT_DIRECTORY/observe_s3_freeze_inventory.py" \
  --bucket "$BUCKET" \
  --region "$AWS_READ_REGION" \
  --date 2026-09-02 \
  --observed-at "$OBSERVED_AT_UTC" \
  --output "$OUTPUT_JSON"
