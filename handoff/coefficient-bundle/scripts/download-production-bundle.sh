#!/usr/bin/env bash
set -euo pipefail

# Read-only download of the exact release artifact referenced by the live shadow
# snapshot on 2026-09-02. AWS credentials are supplied by the caller's profile.

readonly SOURCE_BUCKET="salmonbus-shadow-serving-827325854159-ap-northeast-2"
readonly SOURCE_KEY="artifacts/backend/c5ff99da81ed0af9916bd5aa5115d89f49258e2c/0226182ff73a0cb82d735268afd92e019137acf677ce5b9f08f0bc6950dba8a0.tar.gz"
readonly SOURCE_VERSION_ID="8KryPG.KVyrzvAWNvl34tBlREdS_Hruq"
readonly ARCHIVE_SHA256="0226182ff73a0cb82d735268afd92e019137acf677ce5b9f08f0bc6950dba8a0"
readonly MANIFEST_SHA256="2dce5eb26299ebe2ccbe02f02cdc97a7aa9b07ab319690f383b0928fd8b40405"
readonly WEIGHTS_SHA256="680ef1823971d4a7ba102fd3b04259cc679b676502a0e6e425115fb33fd97be4"
readonly BUNDLE_SHA256="652ee361876e2ab38993472ea65fcd182f2737bc7d819c4bfdb8c8c3850f9335"

if [[ $# -ne 1 ]]; then
  printf 'usage: %s OUTPUT_DIRECTORY\n' "$0" >&2
  exit 2
fi

readonly BUNDLE_OUTPUT=$1
readonly AWS_READ_PROFILE=${AWS_PROFILE:-salmonbus-admin}
readonly AWS_READ_REGION=${AWS_REGION:-ap-northeast-2}

if [[ -e "$BUNDLE_OUTPUT" || -L "$BUNDLE_OUTPUT" ]]; then
  printf 'output already exists: %s\n' "$BUNDLE_OUTPUT" >&2
  exit 1
fi

task_tmp=$(mktemp -d)
cleanup() {
  find "$task_tmp" -type f -delete 2>/dev/null || true
  find "$task_tmp" -depth -type d -empty -delete 2>/dev/null || true
}
trap cleanup EXIT

aws s3api get-object \
  --profile "$AWS_READ_PROFILE" \
  --region "$AWS_READ_REGION" \
  --bucket "$SOURCE_BUCKET" \
  --key "$SOURCE_KEY" \
  --version-id "$SOURCE_VERSION_ID" \
  "$task_tmp/release.tar.gz" >/dev/null

measured_archive=$(shasum -a 256 "$task_tmp/release.tar.gz" | awk '{print $1}')
[[ "$measured_archive" == "$ARCHIVE_SHA256" ]] || {
  printf 'archive digest mismatch\n' >&2
  exit 1
}

tar -xzf "$task_tmp/release.tar.gz" -C "$task_tmp" \
  ./app/a18/manifest.json ./app/a18/weights.safetensors

measured_manifest=$(shasum -a 256 "$task_tmp/app/a18/manifest.json" | awk '{print $1}')
measured_weights=$(shasum -a 256 "$task_tmp/app/a18/weights.safetensors" | awk '{print $1}')
measured_bundle=$(python3 - "$task_tmp/app/a18/manifest.json" "$task_tmp/app/a18/weights.safetensors" <<'PY'
import hashlib
import pathlib
import sys

digest = hashlib.sha256()
for filename in sys.argv[1:]:
    with pathlib.Path(filename).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
print(digest.hexdigest())
PY
)

[[ "$measured_manifest" == "$MANIFEST_SHA256" ]] || {
  printf 'manifest digest mismatch\n' >&2
  exit 1
}
[[ "$measured_weights" == "$WEIGHTS_SHA256" ]] || {
  printf 'weights digest mismatch\n' >&2
  exit 1
}
[[ "$measured_bundle" == "$BUNDLE_SHA256" ]] || {
  printf 'bundle digest mismatch\n' >&2
  exit 1
}

mkdir -p "$BUNDLE_OUTPUT"
install -m 0644 "$task_tmp/app/a18/manifest.json" "$BUNDLE_OUTPUT/manifest.json"
install -m 0644 "$task_tmp/app/a18/weights.safetensors" "$BUNDLE_OUTPUT/weights.safetensors"

printf '{"bundle_sha256":"%s","release_id":"a18-a748cba6ca735e36","status":"downloaded_and_verified"}\n' \
  "$measured_bundle"
