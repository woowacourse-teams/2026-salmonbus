#!/usr/bin/env bash
# EC2 를 흉내낸 예행연습.
# CodeDeploy 는 배포판을 자기 폴더(/archive)에 풀고 거기서 훅을 돌린다.
# files: 섹션이 그 내용을 /opt/salmonbus/staging 으로 복사한다. 그 흐름 그대로 흉내낸다
set -uo pipefail
PASS=0; FAIL=0
ok()  { echo "  통과: $*"; PASS=$((PASS+1)); }
bad() { echo "  실패: $*"; FAIL=$((FAIL+1)); }
sec() { echo; echo "======== $* ========"; }

DEPLOY=/deploy
WORK=/work
ARCHIVE=/archive

sec "0. 서버 흉내 준비"
dnf -q install -y unzip findutils util-linux shadow-utils python3 diffutils >/dev/null 2>&1
useradd -r -s /sbin/nologin salmonbus 2>/dev/null || true
id salmonbus >/dev/null 2>&1 && ok "salmonbus 사용자" || bad "사용자 생성 실패"

mkdir -p /etc/salmonbus "$WORK" /etc/systemd/system   # EC2 에는 systemd 가 있어서 이 디렉터리가 있다
printf 'DB_URL=jdbc:postgresql://rds:5432/salmonbus\nDB_USERNAME=x\nDB_PASSWORD=y\n' > /etc/salmonbus/api.env
printf 'DB_URL=jdbc:postgresql://rds:5432/salmonbus\nDB_USERNAME=x\nDB_PASSWORD=y\nGBIS_SERVICE_KEY=z\n' > /etc/salmonbus/worker.env
chmod 600 /etc/salmonbus/*.env

printf '#!/bin/sh\necho "openjdk version \\"21.0.12\\"" >&2\n' > /usr/local/bin/java
cat > /usr/local/bin/systemctl <<'EOF'
#!/bin/bash
echo "$*" >> /work/systemctl.log
case "$1" in
  is-active) grep -qx "$3" /work/running 2>/dev/null && exit 0 || exit 3 ;;
  restart)   grep -qx "$2" /work/running 2>/dev/null || echo "$2" >> /work/running; exit 0 ;;
esac
exit 0
EOF
cat > /usr/local/bin/curl <<'EOF'
#!/bin/bash
[ "$(cat /work/health 2>/dev/null || echo UP)" = "UP" ] \
  && { echo '{"groups":["liveness","readiness"],"status":"UP"}'; exit 0; }
echo '{"status":"DOWN"}'; exit 22
EOF
chmod +x /usr/local/bin/java /usr/local/bin/systemctl /usr/local/bin/curl
echo UP > "$WORK/health"; : > "$WORK/running"; : > "$WORK/systemctl.log"
ok "java · systemctl · curl 흉내"

source /rehearse/digest.sh

make_archive() {
  local api_body="$1" worker_body="$2" commit="$3" build="$4" stamp="$5"
  rm -rf "$ARCHIVE"; mkdir -p "$ARCHIVE/jars" "$ARCHIVE/scripts" "$ARCHIVE/systemd"
  python3 /rehearse/makejar.py "$ARCHIVE/jars/api-app.jar"    api-app    "$api_body"    "$stamp"
  python3 /rehearse/makejar.py "$ARCHIVE/jars/worker-app.jar" worker-app "$worker_body" "$stamp"
  cp "$DEPLOY/appspec.yml"    "$ARCHIVE/appspec.yml"
  cp "$DEPLOY/scripts/"*.sh   "$ARCHIVE/scripts/"; chmod +x "$ARCHIVE/scripts/"*.sh
  cp "$DEPLOY/systemd/"*.service "$ARCHIVE/systemd/"
  {
    echo "commit=$commit"; echo "buildNumber=$build"; echo "builtAt=$stamp"
    echo "apiDigest=$(stable_digest "$ARCHIVE/jars/api-app.jar")"
    echo "workerDigest=$(stable_digest "$ARCHIVE/jars/worker-app.jar")"
  } > "$ARCHIVE/release-manifest.txt"
}

# CodeDeploy 흉내. Install 이벤트가 archive 를 staging 으로 복사한다
run_deploy() {
  local label="$1"
  echo "--- $label ---"
  bash "$ARCHIVE/scripts/preflight.sh" > "$WORK/preflight.out" 2>&1 \
    && ok "preflight" || { bad "preflight"; sed 's/^/      /' "$WORK/preflight.out"; return 1; }
  rm -rf /opt/salmonbus/staging; mkdir -p /opt/salmonbus/staging
  cp -a "$ARCHIVE/." /opt/salmonbus/staging/
  bash "$ARCHIVE/scripts/install.sh" > "$WORK/install.out" 2>&1 \
    && ok "install" || { bad "install"; sed 's/^/      /' "$WORK/install.out"; return 1; }
  bash "$ARCHIVE/scripts/start.sh" > "$WORK/start.out" 2>&1 \
    && ok "start" || { bad "start"; sed 's/^/      /' "$WORK/start.out"; return 1; }
  bash "$ARCHIVE/scripts/validate.sh" > "$WORK/validate.out" 2>&1 \
    && ok "validate" || { bad "validate"; sed 's/^/      /' "$WORK/validate.out"; return 1; }
  return 0
}

sec "1. 빌드 시각만 다르면 지문이 같은가"
python3 /rehearse/makejar.py "$WORK/a1.jar" api-app SAME    "2026-09-02T01:00:00Z"
python3 /rehearse/makejar.py "$WORK/a2.jar" api-app SAME    "2026-09-02T09:30:00Z"
python3 /rehearse/makejar.py "$WORK/a3.jar" api-app CHANGED "2026-09-02T01:00:00Z"
s1=$(sha256sum "$WORK/a1.jar"|cut -d' ' -f1); s2=$(sha256sum "$WORK/a2.jar"|cut -d' ' -f1)
d1=$(stable_digest "$WORK/a1.jar"); d2=$(stable_digest "$WORK/a2.jar"); d3=$(stable_digest "$WORK/a3.jar")
[ "$s1" != "$s2" ] && ok "그냥 sha256 은 매번 달라진다" || bad "sha256 이 같다"
[ "$d1" = "$d2" ]  && ok "stable_digest 는 같다" || bad "stable_digest 가 다르다"
[ "$d1" != "$d3" ] && ok "내용이 바뀌면 stable_digest 도 바뀐다" || bad "내용이 바뀌었는데 지문이 같다"

sec "2. 첫 배포 (/opt/salmonbus 이 아예 없는 상태)"
rm -rf /opt/salmonbus
make_archive APIv1 WORKERv1 aaaaaaa1111 1 "2026-09-02T01:00:00Z"
run_deploy "1차"
[ "$(readlink -f /opt/salmonbus/current)" = "/opt/salmonbus/releases/aaaaaaa-1" ] \
  && ok "current 가 첫 판을 가리킨다" || bad "current=$(readlink -f /opt/salmonbus/current 2>/dev/null)"
grep -qx salmonbus-api /work/running && grep -qx salmonbus-worker /work/running \
  && ok "첫 배포에 둘 다 올라갔다" || bad "안 올라간 서비스가 있다"

sec "3. api 만 바뀐 배포"
: > /work/systemctl.log
make_archive APIv2 WORKERv1 bbbbbbb2222 2 "2026-09-02T02:00:00Z"
run_deploy "2차"
grep -q 'restart salmonbus-api' /work/systemctl.log \
  && ok "api 는 재시작했다" || bad "api 가 재시작 안 됐다"
grep -q 'restart salmonbus-worker' /work/systemctl.log \
  && bad "worker 도 재시작했다. 바뀐 것만 재시작이 안 된다" || ok "worker 는 안 건드렸다"
[ "$(readlink -f /opt/salmonbus/previous)" = "/opt/salmonbus/releases/aaaaaaa-1" ] \
  && ok "previous 가 직전 판을 가리킨다" || bad "previous=$(readlink -f /opt/salmonbus/previous 2>/dev/null)"

sec "4. 아무것도 안 바뀐 배포"
: > /work/systemctl.log
make_archive APIv2 WORKERv1 ccccccc3333 3 "2026-09-02T03:00:00Z"
run_deploy "3차"
grep -q restart /work/systemctl.log \
  && bad "안 바뀌었는데 재시작했다" || ok "둘 다 안 건드렸다"

sec "5. health 가 안 오르면 실패로 끝나는가 (되돌리기가 걸리는 자리)"
echo DOWN > /work/health
make_archive APIv3 WORKERv1 ddddddd4444 4 "2026-09-02T04:00:00Z"
rm -rf /opt/salmonbus/staging; mkdir -p /opt/salmonbus/staging
cp -a "$ARCHIVE/." /opt/salmonbus/staging/
bash "$ARCHIVE/scripts/preflight.sh" >/dev/null 2>&1
bash "$ARCHIVE/scripts/install.sh"   >/dev/null 2>&1
bash "$ARCHIVE/scripts/start.sh"     >/dev/null 2>&1
if bash "$ARCHIVE/scripts/validate.sh" > "$WORK/v.out" 2>&1; then
  bad "health 가 DOWN 인데 validate 가 통과했다"
else
  ok "validate 가 실패로 끝난다 -> CodeDeploy 가 되돌린다"
fi
echo UP > /work/health

sec "6. 판을 셋만 남기는가"
n=$(ls -1d /opt/salmonbus/releases/*/ 2>/dev/null | wc -l)
[ "$n" -le 3 ] && ok "판 $n 개만 남았다" || bad "판이 $n 개다"

sec "7. env 파일을 안 건드렸는가"
[ "$(stat -c '%a' /etc/salmonbus/worker.env)" = "600" ] \
  && ok "worker.env 권한 600 그대로" || bad "권한이 바뀌었다"
grep -qc '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env \
  && ok "키 줄이 그대로 있다" || bad "키 줄이 없어졌다"

sec "8. 훅 로그에 값이 새지 않았는가"
if grep -rqE 'GBIS_SERVICE_KEY=[^$]|DB_PASSWORD=[^$]' "$WORK"/*.out 2>/dev/null; then
  bad "훅 출력에 값이 찍혔다"
else
  ok "훅 출력에 값이 안 찍혔다"
fi

echo; echo "======== 결과: 통과 $PASS · 실패 $FAIL ========"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
