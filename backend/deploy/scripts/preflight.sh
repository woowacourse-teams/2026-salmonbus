#!/usr/bin/env bash
# BeforeInstall. 여기서 걸리면 파일을 안 푼다
source "$(dirname "$0")/common.sh"
take_lock

log "준비 상태를 본다"

command -v java >/dev/null || { log "자바가 없다"; exit 1; }
java -version 2>&1 | head -1

id "$SERVICE_USER" >/dev/null 2>&1 || { log "$SERVICE_USER 사용자가 없다. 최초 설치 절차를 보라"; exit 1; }

for f in /etc/salmonbus/api.env /etc/salmonbus/worker.env; do
  [ -f "$f" ] || { log "$f 가 없다. 사람이 한 번 만들어야 한다"; exit 1; }
  # 값은 안 찍는다. 줄이 있는지만 센다
  grep -qc '^DB_URL=' "$f" || { log "$f 에 DB_URL 이 없다"; exit 1; }
done
grep -qc '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env \
  || { log "worker.env 에 GBIS_SERVICE_KEY 가 없다"; exit 1; }

# JAR 두 개가 105MB 쯤이고 판을 몇 개 남긴다. 여유를 본다
avail=$(df --output=avail -m "$ROOT" 2>/dev/null | tail -1 || echo 0)
[ "${avail:-0}" -ge 1024 ] || { log "디스크 여유가 ${avail}MB 뿐이다"; exit 1; }

mkdir -p "$RELEASES"
rm -rf "$STAGING"
log "준비 끝. 여유 ${avail}MB"
