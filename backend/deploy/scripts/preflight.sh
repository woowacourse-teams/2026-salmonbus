#!/usr/bin/env bash
# BeforeInstall. 여기서 걸리면 파일을 안 푼다
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
claim_deploy

log "$COMPONENT 배포 준비를 본다"

command -v java >/dev/null || { log "자바가 없다"; exit 1; }
java -version 2>&1 | head -1
id "$SERVICE_USER" >/dev/null 2>&1 || { log "$SERVICE_USER 사용자가 없다. RUNBOOK 의 최초 1회 절차를 보라"; exit 1; }

# 값은 안 찍는다. 줄이 있는지만 센다
[ -f "$ENV_FILE" ] || { log "$ENV_FILE 이 없다. 사람이 한 번 만들어야 한다"; exit 1; }
grep -qc '^DB_URL=' "$ENV_FILE" || { log "$ENV_FILE 에 DB_URL 이 없다"; exit 1; }
if [ "$COMPONENT" = "worker" ]; then
  grep -qc '^GBIS_SERVICE_KEY=' "$ENV_FILE" || { log "$ENV_FILE 에 GBIS_SERVICE_KEY 가 없다"; exit 1; }
  # 계수 파일 자리는 배포가 안 건드리는 곳이라 여기서 만들기만 한다
  mkdir -p "$MODEL_DIRECTORY"
  chown -R "$SERVICE_USER:$SERVICE_USER" /var/lib/salmonbus
fi

# 배포판 목록이 형식에 맞나. install.sh 가 이 값들로 판 이름을 짓는다
for key in commit sourceDigest artifactSha256; do
  [ -n "$(manifest_value "$MANIFEST" "$key")" ] || { log "배포판 목록에 $key 가 없다"; exit 1; }
done

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
