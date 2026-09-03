#!/usr/bin/env bash
set -euo pipefail

readonly PRODUCER_COMMIT="c5ff99da81ed0af9916bd5aa5115d89f49258e2c"
readonly SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
if [[ $# -ne 2 ]]; then
  printf 'usage: %s SALMONBUS_ANALYSIS_REPOSITORY HANDOFF_ROOT\n' "$0" >&2
  exit 2
fi
readonly ANALYSIS_REPOSITORY=$1
readonly ROOT=$2
readonly AWS_READ_PROFILE=${AWS_PROFILE:-salmonbus-admin}
readonly AWS_READ_REGION=${AWS_REGION:-ap-northeast-2}
readonly BUILD_WORKERS=${V41_BUILD_WORKERS:-8}
task_directory=$(mktemp -d)
cleanup() { find "$task_directory" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
git -C "$ANALYSIS_REPOSITORY" archive "$PRODUCER_COMMIT" server/evaluator | tar -x -C "$task_directory"
export AWS_PROFILE="$AWS_READ_PROFILE"
export AWS_REGION="$AWS_READ_REGION"
export PYTHONPATH="$task_directory/server/evaluator:$SCRIPT_DIRECTORY"
python3 "$SCRIPT_DIRECTORY/refresh_route_seed.py" \
  --bucket salmonbus-collector-827325854159-apne2 \
  --region "$AWS_READ_REGION" \
  --from-date 2026-08-14 \
  --through-date 2026-09-01 \
  --protocol "$task_directory/server/evaluator/current_public/evaluation/protocol.json" \
  --source-audit "$ROOT/processed/source-audit.json" \
  --label-watermark-audit "$ROOT/processed/label-watermark-audit.json" \
  --catchup-audit "$ROOT/processed/catchup-audit.json" \
  --route-catchup-audit "$ROOT/processed/final-s3-freeze-closed.json" \
  --workers "$BUILD_WORKERS" \
  --output "$ROOT"
