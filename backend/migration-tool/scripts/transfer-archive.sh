#!/usr/bin/env bash
# 별도 승인 뒤에만 실행한다. 실제 host/user/key/url은 이 파일이나 인자에 넣지 않는다.
set -euo pipefail

required=(
  MIGRATION_ARCHIVE_DIR MIGRATION_SSH_HOST MIGRATION_SSH_USER
  MIGRATION_SSH_IDENTITY_FILE MIGRATION_SSH_KNOWN_HOSTS
  MIGRATION_REMOTE_ARCHIVE_DIR MIGRATION_TRANSFER_APPROVAL_FILE
)
for name in "${required[@]}"; do
  [ -n "${!name:-}" ] || { echo "필수 환경 참조가 없다: $name" >&2; exit 2; }
done

[ -d "$MIGRATION_ARCHIVE_DIR" ] && [ ! -L "$MIGRATION_ARCHIVE_DIR" ] \
  || { echo "archive directory가 안전한 regular directory가 아니다" >&2; exit 1; }
[ "$(stat -f '%Lp' "$MIGRATION_ARCHIVE_DIR" 2>/dev/null || stat -c '%a' "$MIGRATION_ARCHIVE_DIR")" = 700 ] \
  || { echo "archive directory mode는 700이어야 한다" >&2; exit 1; }
[ -f "$MIGRATION_ARCHIVE_DIR/manifest.json" ] \
  || { echo "manifest가 없다" >&2; exit 1; }
[ -f "$MIGRATION_SSH_IDENTITY_FILE" ] && [ ! -L "$MIGRATION_SSH_IDENTITY_FILE" ] \
  || { echo "SSH identity file이 안전한 regular file이 아니다" >&2; exit 1; }
[ "$(stat -f '%Lp' "$MIGRATION_SSH_IDENTITY_FILE" 2>/dev/null || stat -c '%a' "$MIGRATION_SSH_IDENTITY_FILE")" = 400 ] \
  || { echo "SSH identity file mode는 400이어야 한다" >&2; exit 1; }
[ -f "$MIGRATION_SSH_KNOWN_HOSTS" ] && [ ! -L "$MIGRATION_SSH_KNOWN_HOSTS" ] \
  || { echo "pinned known_hosts file이 없다" >&2; exit 1; }
[ -f "$MIGRATION_TRANSFER_APPROVAL_FILE" ] && [ ! -L "$MIGRATION_TRANSFER_APPROVAL_FILE" ] \
  || { echo "transfer approval receipt가 없다" >&2; exit 1; }
[ "$(stat -f '%Lp' "$MIGRATION_TRANSFER_APPROVAL_FILE" 2>/dev/null || stat -c '%a' "$MIGRATION_TRANSFER_APPROVAL_FILE")" = 600 ] \
  || { echo "transfer approval receipt mode는 600이어야 한다" >&2; exit 1; }

manifest_sha=$(sha256sum "$MIGRATION_ARCHIVE_DIR/manifest.json" 2>/dev/null | awk '{print $1}' \
  || shasum -a 256 "$MIGRATION_ARCHIVE_DIR/manifest.json" | awk '{print $1}')
jq -e '
  .archiveSchemaVersion == "salmonbus-s3-rds-private-archive-manifest-v2"
  and (.shards | type == "array" and length > 0)
  and all(.shards[];
    (.file | test("^shards/dt=[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{3}_sha256-[0-9a-f]{64}\\.jsonl\\.zst$"))
    and (.sha256 | test("^[0-9a-f]{64}$")))
' "$MIGRATION_ARCHIVE_DIR/manifest.json" >/dev/null \
  || { echo "manifest transfer shape이 올바르지 않다" >&2; exit 1; }
expected_files=$(mktemp)
actual_files=$(mktemp)
trap 'rm -f -- "$expected_files" "$actual_files"' EXIT
{
  printf '%s\n' manifest.json manifest.sha256
  jq -er '.shards[].file' "$MIGRATION_ARCHIVE_DIR/manifest.json"
} | LC_ALL=C sort >"$expected_files"
(
  cd "$MIGRATION_ARCHIVE_DIR"
  find . -type f -print | sed 's#^\./##' | LC_ALL=C sort
) >"$actual_files"
cmp -s "$expected_files" "$actual_files" \
  || { echo "archive directory에 manifest 밖 파일이 있다" >&2; exit 1; }
if find "$MIGRATION_ARCHIVE_DIR" -type l -print -quit | grep -q .; then
  echo "archive directory에 symbolic link가 있다" >&2
  exit 1
fi
while IFS= read -r -d '' path; do
  [ "$(stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path")" = 700 ] \
    || { echo "archive 하위 directory mode가 700이 아니다" >&2; exit 1; }
done < <(find "$MIGRATION_ARCHIVE_DIR" -type d -print0)
while IFS= read -r -d '' path; do
  [ "$(stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path")" = 600 ] \
    || { echo "archive file mode가 600이 아니다" >&2; exit 1; }
done < <(find "$MIGRATION_ARCHIVE_DIR" -type f -print0)
grep -qx "$manifest_sha  manifest.json" "$MIGRATION_ARCHIVE_DIR/manifest.sha256" \
  || { echo "manifest digest file이 manifest와 다르다" >&2; exit 1; }
while IFS=$'\t' read -r file digest; do
  actual=$(sha256sum "$MIGRATION_ARCHIVE_DIR/$file" 2>/dev/null | awk '{print $1}' \
    || shasum -a 256 "$MIGRATION_ARCHIVE_DIR/$file" | awk '{print $1}')
  [ "$actual" = "$digest" ] \
    || { echo "archive shard digest가 manifest와 다르다" >&2; exit 1; }
done < <(jq -er '.shards[] | [.file, .sha256] | @tsv' "$MIGRATION_ARCHIVE_DIR/manifest.json")
approval_manifest=$(jq -er '.manifestSha256' "$MIGRATION_TRANSFER_APPROVAL_FILE")
approval_action=$(jq -er '.action' "$MIGRATION_TRANSFER_APPROVAL_FILE")
expires_at=$(jq -er '.expiresAt' "$MIGRATION_TRANSFER_APPROVAL_FILE")
approval_schema=$(jq -er '.schemaVersion' "$MIGRATION_TRANSFER_APPROVAL_FILE")
jq -e 'keys == ["action", "expiresAt", "manifestSha256", "schemaVersion"]' \
  "$MIGRATION_TRANSFER_APPROVAL_FILE" >/dev/null \
  || { echo "transfer approval 필드가 올바르지 않다" >&2; exit 1; }
[ "$approval_schema" = salmonbus-transfer-approval-v1 ] \
  && [ "$approval_action" = RSYNC_ARCHIVE_TRANSFER ] && [ "$approval_manifest" = "$manifest_sha" ] \
  || { echo "transfer approval identity가 archive와 다르다" >&2; exit 1; }
python3 - "$expires_at" <<'PY'
import datetime
import sys

expires = datetime.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
if expires <= datetime.datetime.now(datetime.timezone.utc):
    raise SystemExit("transfer approval이 만료됐다")
PY

case "$MIGRATION_REMOTE_ARCHIVE_DIR" in
  /var/lib/salmonbus/migration/*) ;;
  *) echo "remote archive directory가 승인된 root 아래가 아니다" >&2; exit 1 ;;
esac
[[ "$MIGRATION_REMOTE_ARCHIVE_DIR" =~ ^/var/lib/salmonbus/migration/[A-Za-z0-9._/-]+$ ]] \
  && [[ "$MIGRATION_REMOTE_ARCHIVE_DIR" != *"/../"* ]] \
  || { echo "remote archive directory 문법이 안전하지 않다" >&2; exit 1; }
[[ "$MIGRATION_SSH_HOST" =~ ^[A-Za-z0-9.-]+$ ]] \
  && [[ "$MIGRATION_SSH_USER" =~ ^[A-Za-z_][A-Za-z0-9_-]*$ ]] \
  || { echo "SSH endpoint 문법이 안전하지 않다" >&2; exit 1; }

ssh_options=(
  -i "$MIGRATION_SSH_IDENTITY_FILE"
  -o BatchMode=yes
  -o IdentitiesOnly=yes
  -o ConnectTimeout=10
  -o StrictHostKeyChecking=yes
  -o "UserKnownHostsFile=$MIGRATION_SSH_KNOWN_HOSTS"
)
remote="$MIGRATION_SSH_USER@$MIGRATION_SSH_HOST"

# incoming은 검증 전 이름이다. remote final archive로 쓰는 rename은 별도 검증 단계가 한다.
ssh "${ssh_options[@]}" "$remote" \
  "umask 077; mkdir -p -- '$MIGRATION_REMOTE_ARCHIVE_DIR.incoming'"
# rsync 는 --append 계열과 --partial-dir 을 함께 못 쓴다("--append cannot be used with
# --partial-dir"). --partial-dir 쪽을 남긴다. --append 는 목적지에 남은 앞부분이 source 의
# 올바른 접두라고 가정하는데, .incoming 에 다른 archive 의 잔여물이 있으면 그 위에 이어붙는다.
# 재개는 --partial/--partial-dir 로 충분하고, 전송 뒤 원격 digest 대조가 정본 검증이다.
rsync -a --partial --partial-dir=.rsync-partial \
  --chmod=Du=rwx,Dgo=,Fu=rw,Fgo= \
  -e "ssh -i '$MIGRATION_SSH_IDENTITY_FILE' -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=yes -o UserKnownHostsFile='$MIGRATION_SSH_KNOWN_HOSTS'" \
  -- "$MIGRATION_ARCHIVE_DIR/" "$remote:$MIGRATION_REMOTE_ARCHIVE_DIR.incoming/"

echo "transfer complete; remote archive는 아직 incoming이며 hash/schema 검증과 final rename이 남았다"
