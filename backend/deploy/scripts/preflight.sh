#!/usr/bin/env bash
# BeforeInstall. 여기서 걸리면 파일을 안 푼다
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
claim_deploy

log "$COMPONENT 배포 준비를 본다"

# 유닛이 /usr/bin/java 를 쓴다. PATH 의 java 를 봐도 그게 뜬다는 뜻이 아니다
[ -x /usr/bin/java ] || { log "/usr/bin/java 가 없다"; exit 1; }
java_major="$(/usr/bin/java -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
[ "$java_major" = "21" ] || { log "/usr/bin/java 가 21 이 아니다 (${java_major:-읽기 실패})"; exit 1; }
log "자바 $java_major"
id "$SERVICE_USER" >/dev/null 2>&1 || { log "$SERVICE_USER 사용자가 없다. RUNBOOK 의 최초 1회 절차를 보라"; exit 1; }

# 값은 안 찍는다. 줄이 있는지만 센다
[ -f "$ENV_FILE" ] || { log "$ENV_FILE 이 없다. 사람이 한 번 만들어야 한다"; exit 1; }
# systemd 가 이 파일을 앱 환경변수로 통째로 넣는다. 남이 읽을 수 있으면 안 된다
env_mode="$(stat -c '%a' "$ENV_FILE")"
env_owner="$(stat -c '%U:%G' "$ENV_FILE")"
[ "$env_mode" = "600" ] || { log "$ENV_FILE 권한이 $env_mode 다. 600 이어야 한다"; exit 1; }
[ "$env_owner" = "root:root" ] || { log "$ENV_FILE 주인이 $env_owner 다. root:root 여야 한다"; exit 1; }
grep -qc '^DB_URL=' "$ENV_FILE" || { log "$ENV_FILE 에 DB_URL 이 없다"; exit 1; }
if [ "$COMPONENT" = "worker" ]; then
  grep -qc '^GBIS_SERVICE_KEY=' "$ENV_FILE" || { log "$ENV_FILE 에 GBIS_SERVICE_KEY 가 없다"; exit 1; }
  # 계수 파일 자리는 배포가 안 건드리는 곳이라 여기서 만들기만 한다
  mkdir -p "$MODEL_DIRECTORY"
  chown -R "$SERVICE_USER:$SERVICE_USER" /var/lib/salmonbus
fi

# 배포판 목록이 형식에 맞나. install.sh 가 이 값으로 판 이름을 짓는다
for key in component commit sourceDigest artifactSha256; do
  [ -n "$(manifest_value "$MANIFEST" "$key")" ] || { log "배포판 목록에 $key 가 없다"; exit 1; }
done

# sourceDigest 는 root 권한 삭제와 이동 경로에 들어간다. 여기서 형식과 경로를 다 본다.
# install.sh 가 쓰기 전에 걸러야 releases 밖을 건드리는 길이 안 생긴다
checked="$(release_path "$(manifest_value "$MANIFEST" sourceDigest)")"
log "판 경로 확인: $checked"

# 받은 JAR 이 빌드 때와 같은 파일인가
want="$(manifest_value "$MANIFEST" artifactSha256)"
got="$(sha256sum "$REVISION/jars/$COMPONENT-app.jar" | cut -d' ' -f1)"
[ "$want" = "$got" ] || { log "JAR 지문이 배포판 목록과 다르다"; exit 1; }

# JAR 하나가 52MB 다. 판을 셋 남기고 여유를 본다
mkdir -p "$BASE"
avail=$(df --output=avail -m "$BASE" 2>/dev/null | tail -1 || echo 0)
[ "${avail:-0}" -ge 1024 ] || { log "디스크 여유가 ${avail}MB 뿐이다"; exit 1; }

mkdir -p "$RELEASES"
rm -rf "$STAGING"
log "준비 끝. 여유 ${avail}MB"
