#!/usr/bin/env bash
# AfterInstall. staging 에 풀린 것을 판 폴더로 옮기고 바로가기를 세운다
source "$(dirname "$0")/common.sh"
take_lock

NEW_MANIFEST="$STAGING/release-manifest.txt"
[ -f "$NEW_MANIFEST" ] || { log "release-manifest.txt 가 없다"; exit 1; }

commit="$(manifest_value "$NEW_MANIFEST" commit)"
build="$(manifest_value "$NEW_MANIFEST" buildNumber)"
release="$RELEASES/${commit:0:7}-${build}"

log "판 $release 을 놓는다"
rm -rf "$release"
mkdir -p "$(dirname "$release")"
mv "$STAGING" "$release"
# staging 은 방금 옮겨져서 없다. 새 목록은 이제 판 폴더에 있다
NEW_MANIFEST="$release/release-manifest.txt"

# 바로가기를 옮기기 전에 지금 도는 판과 대조한다. 옮기고 나면 못 본다
OLD_MANIFEST="$CURRENT/release-manifest.txt"
changed=""
for svc in api worker; do
  old="$(manifest_value "$OLD_MANIFEST" "${svc}Digest")"
  new="$(manifest_value "$NEW_MANIFEST" "${svc}Digest")"
  if [ -z "$old" ] || [ "$old" != "$new" ]; then
    changed="$changed $svc"
  fi
done
echo "changed=${changed# }" > "$release/.changed"
log "바뀐 서비스:${changed:- 없음}"

# systemd 유닛이 바뀌었을 때만 갈아 끼운다
for unit in "$release"/systemd/*.service; do
  target="/etc/systemd/system/$(basename "$unit")"
  if ! cmp -s "$unit" "$target"; then
    install -m 644 -o root -g root "$unit" "$target"
    log "유닛 갱신: $(basename "$unit")"
    NEED_RELOAD=1
  fi
done
[ "${NEED_RELOAD:-0}" = "1" ] && systemctl daemon-reload

chown -R "$SERVICE_USER:$SERVICE_USER" "$release"

# 되돌릴 자리를 남기고 바로가기를 옮긴다
[ -L "$CURRENT" ] && ln -sfn "$(readlink -f "$CURRENT")" "$PREVIOUS"
ln -sfn "$release" "$CURRENT"
log "current -> $release"

# 오래된 판을 셋만 남긴다. JAR 이 판마다 105MB 다
ls -1dt "$RELEASES"/*/ 2>/dev/null | tail -n +4 | xargs -r rm -rf
