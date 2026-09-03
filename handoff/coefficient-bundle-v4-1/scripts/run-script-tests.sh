#!/usr/bin/env bash
set -euo pipefail

readonly PRODUCER_COMMIT="c5ff99da81ed0af9916bd5aa5115d89f49258e2c"
if [[ $# -ne 1 ]]; then
  printf 'usage: %s SALMONBUS_ANALYSIS_REPOSITORY\n' "$0" >&2
  exit 2
fi
readonly ANALYSIS_REPOSITORY=$1
readonly SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
task_directory=$(mktemp -d)
cleanup() {
  find "$task_directory" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT
git -C "$ANALYSIS_REPOSITORY" archive "$PRODUCER_COMMIT" server/evaluator \
  | tar -x -C "$task_directory"
PYTHONPATH="$task_directory/server/evaluator:$SCRIPT_DIRECTORY" \
  python3 "$SCRIPT_DIRECTORY/test_contract.py"
