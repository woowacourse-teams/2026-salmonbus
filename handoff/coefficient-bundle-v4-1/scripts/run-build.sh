#!/usr/bin/env bash
set -euo pipefail

# Replays the frozen collector source in process. AWS access is limited to the
# caller's existing List/Get permissions; the script has no AWS or DB writer.

readonly PRODUCER_COMMIT="c5ff99da81ed0af9916bd5aa5115d89f49258e2c"
readonly FROM_DATE="2026-08-14"
readonly THROUGH_DATE="2026-09-01"
readonly COLLECTOR_BUCKET="salmonbus-collector-827325854159-apne2"

if [[ $# -ne 2 ]]; then
  printf 'usage: %s SALMONBUS_ANALYSIS_REPOSITORY OUTPUT_DIRECTORY\n' "$0" >&2
  exit 2
fi

readonly ANALYSIS_REPOSITORY=$1
readonly OUTPUT_DIRECTORY=$2
readonly SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
readonly AWS_READ_PROFILE=${AWS_PROFILE:-salmonbus-admin}
readonly AWS_READ_REGION=${AWS_REGION:-ap-northeast-2}
readonly BUILD_WORKERS=${V41_BUILD_WORKERS:-8}
readonly PYTHON_EXECUTABLE=${V41_PYTHON_BIN:-python3}
readonly SOURCE_AUDIT="$OUTPUT_DIRECTORY/processed/source-audit.json"
readonly LABEL_WATERMARK_AUDIT="$OUTPUT_DIRECTORY/processed/label-watermark-audit.json"
readonly CATCHUP_AUDIT="$OUTPUT_DIRECTORY/processed/catchup-audit.json"
readonly ROUTE_CATCHUP_AUDIT="$OUTPUT_DIRECTORY/processed/route-catchup-audit.json"

if [[ ! -f "$SOURCE_AUDIT" ]]; then
  printf 'source audit is required first: %s\n' "$SOURCE_AUDIT" >&2
  exit 1
fi
if [[ ! -f "$LABEL_WATERMARK_AUDIT" ]]; then
  printf 'label watermark audit is required first: %s\n' "$LABEL_WATERMARK_AUDIT" >&2
  exit 1
fi
if [[ ! -f "$CATCHUP_AUDIT" ]]; then
  printf 'catch-up audit is required first: %s\n' "$CATCHUP_AUDIT" >&2
  exit 1
fi
if [[ ! -f "$ROUTE_CATCHUP_AUDIT" ]]; then
  printf 'route catch-up audit is required first: %s\n' "$ROUTE_CATCHUP_AUDIT" >&2
  exit 1
fi
if [[ -e "$OUTPUT_DIRECTORY/bundle" || -L "$OUTPUT_DIRECTORY/bundle" ]]; then
  printf 'bundle output already exists: %s/bundle\n' "$OUTPUT_DIRECTORY" >&2
  exit 1
fi
git -C "$ANALYSIS_REPOSITORY" cat-file -e "$PRODUCER_COMMIT^{commit}"

task_tmp_link=$(mktemp -d)
task_tmp=$(cd "$task_tmp_link" && pwd -P)
cleanup() {
  find "$task_tmp" -type f -delete 2>/dev/null || true
  find "$task_tmp" -depth -type d -empty -delete 2>/dev/null || true
}
trap cleanup EXIT

git -C "$ANALYSIS_REPOSITORY" archive "$PRODUCER_COMMIT" server/evaluator \
  | tar -x -C "$task_tmp"

export AWS_PROFILE="$AWS_READ_PROFILE"
export AWS_REGION="$AWS_READ_REGION"
export PYTHONPATH="$task_tmp/server/evaluator:$SCRIPT_DIRECTORY"

"$PYTHON_EXECUTABLE" "$SCRIPT_DIRECTORY/build_v41.py" \
  --bucket "$COLLECTOR_BUCKET" \
  --region "$AWS_READ_REGION" \
  --from-date "$FROM_DATE" \
  --through-date "$THROUGH_DATE" \
  --protocol "$task_tmp/server/evaluator/current_public/evaluation/protocol.json" \
  --source-audit "$SOURCE_AUDIT" \
  --label-watermark-audit "$LABEL_WATERMARK_AUDIT" \
  --catchup-audit "$CATCHUP_AUDIT" \
  --route-catchup-audit "$ROUTE_CATCHUP_AUDIT" \
  --workers "$BUILD_WORKERS" \
  --output "$OUTPUT_DIRECTORY"
