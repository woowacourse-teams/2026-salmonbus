#!/usr/bin/env bash
# AfterInstall. staging 에 풀린 것을 배포 버전 디렉터리로 옮기고 current 링크를 바꾼다
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
claim_deploy

NEW_DIGEST="$(manifest_value "$MANIFEST" sourceDigest)"
OLD_DIGEST="$(manifest_value "$CURRENT/release-manifest.txt" sourceDigest)"
release="$(release_path "$NEW_DIGEST")"

# 유닛 파일을 교체한다. 소스가 그대로여도 유닛이 바뀌었으면 다시 시작해야
# 새 ExecStart 와 EnvironmentFile 이 먹는다
install_unit() {
  local unit_file="$1" target="/etc/systemd/system/$UNIT.service"
  if cmp -s "$unit_file" "$target"; then
    echo no
    return 0
  fi
  install -m 644 -o root -g root "$unit_file" "$target"
  systemctl daemon-reload
  log "유닛 갱신: $UNIT.service"
  echo yes
}

if [ -d "$release" ]; then
  # 이미 있는 배포 버전이다. 롤백이 여기로 온다.
  # CodeDeploy 의 자동 롤백은 직전 성공 revision 을 그대로 다시 배포하는 것이라
  # 그 배포 버전이 previous 로 남아 있다. 지우고 다시 풀면 롤백할 것이 없어진다.
  # 있는 것을 그대로 쓰고 current 만 옮긴다
  log "이미 있는 배포 버전이다. 다시 안 풀고 그대로 쓴다: $release"
  unit_changed="$(install_unit "$STAGING/systemd/$UNIT.service")"
  rm -rf "$STAGING"
else
  log "배포 버전 $release 을 놓는다"
  mkdir -p "$RELEASES"
  mv "$STAGING" "$release"
  unit_changed="$(install_unit "$release/systemd/$UNIT.service")"
fi

# 실행 중인 배포 버전을 /actuator/info 로 알아보게 한다. systemd 가 이 파일을 같이 읽는다.
# 배포 메타데이터는 지금 배포하는 revision 것을 쓴다. 롤백이면 옛 commit 이 들어가는 게 맞다
{
  echo "MANAGEMENT_INFO_ENV_ENABLED=true"
  echo "INFO_COMPONENT=$COMPONENT"
  echo "INFO_COMMIT=$(manifest_value "$MANIFEST" commit)"
  echo "INFO_SOURCEDIGEST=$NEW_DIGEST"
} > "$release/release.env"

# 주인은 root 로 두고 서비스 사용자에게는 읽기만 준다.
# 앱이 자기 JAR 과 훅 스크립트를 고칠 수 있으면 안 된다
chown -R "root:$SERVICE_USER" "$release"
chmod -R go-w "$release"
find "$release" -type d -exec chmod g+rx {} +
find "$release" -type f -exec chmod g+r {} +

# 재부팅 뒤에도 올라오게 한다. 여러 번 불러도 같은 결과다
systemctl enable "$UNIT" >/dev/null 2>&1 || { log "$UNIT enable 실패"; exit 1; }

current_now="$(readlink -f "$CURRENT" 2>/dev/null || true)"
if [ "$current_now" = "$release" ]; then
  # 실행 중인 배포 버전을 그대로 다시 배포한 것이다. 링크를 안 건드린다
  log "current 가 이미 $release 다"
else
  [ -n "$current_now" ] && ln -sfn "$current_now" "$PREVIOUS"
  ln -sfn "$release" "$CURRENT"
  log "current -> $release"
fi

# start.sh 가 읽는다. 왜 재시작하는지도 같이 적는다. 로그에 이유가 없으면 나중에 journal 을 뒤져야 한다
old_short="${OLD_DIGEST:0:12}"
if [ "$NEW_DIGEST" != "$OLD_DIGEST" ]; then
  printf 'changed=yes\nreason=소스 지문 변경 %s -> %s\n' "${old_short:-없음}" "${NEW_DIGEST:0:12}" > "$CHANGED"
elif [ "$unit_changed" = "yes" ]; then
  # 소스는 그대로인데 유닛이 바뀌었다. 다시 시작해야 새 ExecStart 와 EnvironmentFile 이 먹는다
  printf 'changed=yes\nreason=유닛 변경\n' > "$CHANGED"
  log "$COMPONENT 소스 지문이 그대로다. 유닛이 바뀌어 재시작한다"
else
  printf 'changed=no\nreason=변경 없음\n' > "$CHANGED"
  log "$COMPONENT 소스 지문이 그대로다. 재시작하지 않는다"
fi

# 배포 버전마다 52MB 다. 셋만 남긴다. 지금 쓰는 것과 직전 것은 안 지운다
keep_current="$(readlink -f "$CURRENT" 2>/dev/null || true)"
keep_previous="$(readlink -f "$PREVIOUS" 2>/dev/null || true)"
ls -1dt "$RELEASES"/*/ 2>/dev/null | tail -n +4 | while read -r old; do
  old="${old%/}"
  [ "$old" = "$keep_current" ] && continue
  [ "$old" = "$keep_previous" ] && continue
  rm -rf "$old"
done
