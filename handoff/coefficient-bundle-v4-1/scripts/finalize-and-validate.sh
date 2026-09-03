#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  printf 'usage: %s HANDOFF_ROOT\n' "$0" >&2
  exit 2
fi

readonly HANDOFF_ROOT=$1
readonly SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
readonly BUNDLE="$HANDOFF_ROOT/bundle"
readonly PROCESSED="$HANDOFF_ROOT/processed"
readonly CHECKPOINT_SCRIPT="/Users/idonghun/paseo/workspaces/2026-salmonbus/checkpoint.sh"
readonly ANALYSIS_REPOSITORY=${SALMONBUS_ANALYSIS_REPOSITORY:-/Users/idonghun/IdeaProjects/salmonbus-analysis}
validation_cache=$(mktemp -d)
cleanup() {
  find "$validation_cache" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT
export V41_GRADLE_USER_HOME="$validation_cache"

if [[ ! -f "$BUNDLE/manifest.json" || ! -f "$BUNDLE/weights.safetensors" ]]; then
  printf 'provisional bundle is missing\n' >&2
  exit 1
fi
mkdir -p "$PROCESSED"

"$SCRIPT_DIRECTORY/run-script-tests.sh" "$ANALYSIS_REPOSITORY" \
  2>&1 | tee "$PROCESSED/python-contract-tests.log"
"$SCRIPT_DIRECTORY/prepare_golden_fixture.py" --bundle "$BUNDLE"
"$SCRIPT_DIRECTORY/verify-with-dev-java.sh" probe "$BUNDLE" \
  | tee "$PROCESSED/java-probe.log"
"$SCRIPT_DIRECTORY/finalize_from_java_probe.py" \
  --bundle "$BUNDLE" \
  --probe-log "$PROCESSED/java-probe.log" \
  --receipt "$PROCESSED/java-finalization.json"
"$SCRIPT_DIRECTORY/verify-with-dev-java.sh" verify "$BUNDLE" \
  | tee "$PROCESSED/java-bundle-verify.log"
"$SCRIPT_DIRECTORY/verify-with-dev-java.sh" loader-only "$BUNDLE" \
  | tee "$PROCESSED/java-loader-only.log"
"$SCRIPT_DIRECTORY/verify-with-dev-java.sh" core-test \
  | tee "$PROCESSED/java-core-tests.log"
"$SCRIPT_DIRECTORY/validate_bundle.py" \
  --bundle "$BUNDLE" \
  --contexts "$PROCESSED/runtime-contexts.json" \
  --output "$PROCESSED/python-bundle-validation.json"
"$SCRIPT_DIRECTORY/finalize_seed_scope.py" --root "$HANDOFF_ROOT"
"$SCRIPT_DIRECTORY/validate_seed.py" \
  --seed "$HANDOFF_ROOT/seed/cell-hourly-aggregate.json.gz" \
  --receipt "$HANDOFF_ROOT/seed/receipt.json" \
  --output "$PROCESSED/seed-validation.json"
"$SCRIPT_DIRECTORY/validate_temp_exclusion.py" \
  --root "$HANDOFF_ROOT" \
  --output "$PROCESSED/temp-exclusion-negative-test.json"
"$SCRIPT_DIRECTORY/privacy_scan.py" \
  --root "$HANDOFF_ROOT" \
  --output "$PROCESSED/privacy-scan.json"
python3 "$SCRIPT_DIRECTORY/finalize_validation_receipt.py" --root "$HANDOFF_ROOT"
"$SCRIPT_DIRECTORY/privacy_scan.py" \
  --root "$HANDOFF_ROOT" \
  --output "$PROCESSED/privacy-scan.json"

if [[ -x "$CHECKPOINT_SCRIPT" ]]; then
  "$CHECKPOINT_SCRIPT" \
    "v4-1 로컬 exact Java loader/golden·core test 및 Python/seed/privacy 검증 통과; 전송·배포·DB write 없음" \
    >/dev/null
fi
