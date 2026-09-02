#!/usr/bin/env bash
# AfterInstall. staging 에 풀린 것을 판 폴더로 옮기고 바로가기를 세운다
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
claim_deploy

NEW_DIGEST="$(manifest_value "$MANIFEST" sourceDigest)"
OLD_DIGEST="$(manifest_value "$CURRENT/release-manifest.txt" sourceDigest)"
release="$RELEASES/$NEW_DIGEST"

# 소스가 그대로면 판을 새로 놓지 않는다. 지금 도는 판이 바로 그 판이다.
# 여기서 rm -rf 를 하면 돌고 있는 판을 지운다
if [ "$NEW_DIGEST" = "$OLD_DIGEST" ] && [ -d "$release" ]; then
  log "$COMPONENT 소스 지문이 그대로다. 판을 안 건드린다"
  rm -rf "$STAGING"
  echo "changed=no" > "$CHANGED"
  exit 0
fi

log "판 $release 을 놓는다"
# 지문이 다른데 같은 폴더를 가리키면 돌고 있는 판을 지우게 된다. 그 길을 막는다
for busy in "$CURRENT" "$PREVIOUS"; do
  [ "$(readlink -f "$busy" 2>/dev/null || true)" = "$release" ] \
    && { log "$release 을 $busy 가 쓰고 있다"; exit 1; }
done
rm -rf "$release"
mv "$STAGING" "$release"

# 도는 배포를 /actuator/info 로 판별하게 한다. systemd 가 이 파일을 같이 읽는다
{
  echo "MANAGEMENT_INFO_ENV_ENABLED=true"
  echo "INFO_COMPONENT=$COMPONENT"
  echo "INFO_COMMIT=$(manifest_value "$release/release-manifest.txt" commit)"
  echo "INFO_SOURCEDIGEST=$NEW_DIGEST"
} > "$release/release.env"

# systemd 유닛이 바뀌었을 때만 갈아 끼운다
unit_file="$release/systemd/$UNIT.service"
target="/etc/systemd/system/$UNIT.service"
if ! cmp -s "$unit_file" "$target"; then
  install -m 644 -o root -g root "$unit_file" "$target"
  systemctl daemon-reload
  log "유닛 갱신: $UNIT.service"
fi

chown -R "$SERVICE_USER:$SERVICE_USER" "$release"

# 되돌릴 자리를 남기고 바로가기를 옮긴다
if [ -L "$CURRENT" ]; then
  ln -sfn "$(readlink -f "$CURRENT")" "$PREVIOUS"
fi
ln -sfn "$release" "$CURRENT"
echo "changed=yes" > "$CHANGED"
log "current -> $release"

# 판마다 52MB 다. 셋만 남긴다. 지금 쓰는 것과 직전 것은 안 지운다
keep_current="$(readlink -f "$CURRENT" 2>/dev/null || true)"
keep_previous="$(readlink -f "$PREVIOUS" 2>/dev/null || true)"
ls -1dt "$RELEASES"/*/ 2>/dev/null | tail -n +4 | while read -r old; do
  old="${old%/}"
  [ "$old" = "$keep_current" ] && continue
  [ "$old" = "$keep_previous" ] && continue
  rm -rf "$old"
done
