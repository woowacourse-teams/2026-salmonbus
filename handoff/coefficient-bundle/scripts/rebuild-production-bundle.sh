#!/usr/bin/env bash
set -euo pipefail

# Replays the approved private in-process final fit. It only lists/gets S3
# objects and writes the requested local output directory. It never persists
# row-level examples and never mutates AWS.

readonly PRODUCER_COMMIT="c5ff99da81ed0af9916bd5aa5115d89f49258e2c"
readonly SOURCE_MANIFEST_SHA256="3e1628f0240515db38c73f04dac6346596a28a663bac7d55e0ef84af25e15536"
readonly ROUTE_REFERENCE_VERSION="gbis-2026-08-19"
readonly CREATED_AT="2026-08-23T15:00:00Z"
readonly FROM_DATE="2026-08-14"
readonly THROUGH_DATE="2026-08-23"
readonly COLLECTOR_BUCKET="salmonbus-collector-827325854159-apne2"

if [[ $# -ne 2 ]]; then
  printf 'usage: %s SALMONBUS_ANALYSIS_REPOSITORY OUTPUT_DIRECTORY\n' "$0" >&2
  exit 2
fi

readonly ANALYSIS_REPOSITORY=$1
readonly BUNDLE_OUTPUT=$2
readonly AWS_READ_PROFILE=${AWS_PROFILE:-salmonbus-admin}
readonly AWS_READ_REGION=${AWS_REGION:-ap-northeast-2}
readonly BUILD_WORKERS=${A18_BUILD_WORKERS:-8}
readonly PYTHON_EXECUTABLE=${A18_PYTHON_BIN:-python3}

if [[ -e "$BUNDLE_OUTPUT" || -L "$BUNDLE_OUTPUT" ]]; then
  printf 'output already exists: %s\n' "$BUNDLE_OUTPUT" >&2
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

git -C "$ANALYSIS_REPOSITORY" archive "$PRODUCER_COMMIT" \
  server/model server/evaluator | tar -x -C "$task_tmp"

export AWS_PROFILE="$AWS_READ_PROFILE"
export AWS_REGION="$AWS_READ_REGION"
export A18_COLLECTOR_BUCKET="$COLLECTOR_BUCKET"
export PYTHONPATH="$task_tmp/server/evaluator:$task_tmp"

"$PYTHON_EXECUTABLE" -m current_public.evaluation.final_bundle_adapter \
  --from-date "$FROM_DATE" \
  --through-date "$THROUGH_DATE" \
  --protocol "$task_tmp/server/evaluator/current_public/evaluation/protocol.json" \
  --expected-source-manifest-sha256 "$SOURCE_MANIFEST_SHA256" \
  --route-reference-version "$ROUTE_REFERENCE_VERSION" \
  --created-at "$CREATED_AT" \
  --workers "$BUILD_WORKERS" \
  --output "$BUNDLE_OUTPUT"
