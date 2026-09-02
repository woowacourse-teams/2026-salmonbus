#!/usr/bin/env bash
# EC2 를 흉내낸 예행연습.
# CodeDeploy 는 revision 을 자기 폴더에 풀고 거기서 훅을 돌린다.
# files: 섹션이 그 내용을 /opt/salmonbus/<component>/staging 으로 복사한다. 그 흐름 그대로 흉내낸다.
# revision 이 둘(api·worker)이라 배포도 둘씩 돈다
set -uo pipefail
PASS=0; FAIL=0
ok()  { echo "  통과: $*"; PASS=$((PASS+1)); }
bad() { echo "  실패: $*"; FAIL=$((FAIL+1)); }
sec() { echo; echo "======== $* ========"; }

DEPLOY=/deploy
WORK=/work
ARCHIVE=/archive

sec "0. 서버 흉내 준비"
dnf -q install -y unzip findutils util-linux shadow-utils python3 diffutils procps-ng >/dev/null 2>&1
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
  stop)      grep -vx "$2" /work/running > /work/r.tmp 2>/dev/null; mv /work/r.tmp /work/running; exit 0 ;;
  start)     grep -qx "$2" /work/running 2>/dev/null || echo "$2" >> /work/running; exit 0 ;;
esac
exit 0
EOF

# /actuator 를 흉내낸다. info 는 지금 도는 판의 release.env 를 읽어 답한다.
# /work/stale 이 있으면 옛 프로세스가 아직 응답하는 상황이 된다
cat > /usr/local/bin/curl <<'EOF'
#!/bin/bash
url=""; want_code=0
for a in "$@"; do
  case "$a" in
    http*) url="$a" ;;
    '%{http_code}') want_code=1 ;;
  esac
done
state="$(cat /work/health 2>/dev/null || echo UP)"
comp=""
case "$url" in
  *:8082/*) comp=api ;;
  *:8081/*) comp=worker ;;
esac
if [ "$want_code" = "1" ]; then
  [ "$state" = "UP" ] && { echo 200; exit 0; }
  echo 503; exit 0
fi
[ "$state" = "UP" ] || { echo '{"status":"DOWN"}'; exit 22; }
case "$url" in
  */health) echo '{"groups":["liveness","readiness"],"status":"UP"}'; exit 0 ;;
  */info)
    envf="/opt/salmonbus/$comp/current/release.env"
    d="$(sed -n 's/^INFO_SOURCEDIGEST=//p' "$envf" 2>/dev/null)"
    c="$(sed -n 's/^INFO_COMPONENT=//p' "$envf" 2>/dev/null)"
    [ -f /work/stale ] && d="$(cat /work/stale)"
    echo "{\"sourcedigest\":\"$d\",\"component\":\"$c\",\"build\":{}}"
    exit 0 ;;
esac
exit 0
EOF
chmod +x /usr/local/bin/java /usr/local/bin/systemctl /usr/local/bin/curl
echo UP > "$WORK/health"; : > "$WORK/running"; : > "$WORK/systemctl.log"; rm -f "$WORK/stale"
ok "java · systemctl · curl 흉내"

source /rehearse/digest.sh

# revision 한 벌을 만든다. buildspec 의 pack() 과 같은 모양이다
make_revision() {
  local component="$1" body="$2" commit="$3" digest="$4" stamp="$5"
  local a="$ARCHIVE/$component"
  rm -rf "$a"; mkdir -p "$a/jars" "$a/scripts" "$a/systemd"
  python3 /rehearse/makejar.py "$a/jars/$component-app.jar" "$component-app" "$body" "$stamp"
  cp "$DEPLOY/appspec-$component.yml" "$a/appspec.yml"
  cp "$DEPLOY/scripts/"*.sh "$a/scripts/"; chmod +x "$a/scripts/"*.sh
  cp "$DEPLOY/systemd/salmonbus-$component.service" "$a/systemd/"
  {
    echo "component=$component"
    echo "commit=$commit"
    echo "sourceDigest=$digest"
    echo "artifactSha256=$(sha256sum "$a/jars/$component-app.jar" | cut -d' ' -f1)"
    echo "javaVersion=21.0.12"
    echo "appVersion=0.0.1-SNAPSHOT"
    echo "flywayMaxVersion=12"
    echo "builtAt=$stamp"
  } > "$a/release-manifest.txt"
}

# CodeDeploy 흉내. Install 이벤트가 revision 을 staging 으로 복사한다
deploy() {
  local component="$1" expect="${2:-ok}"
  local a="$ARCHIVE/$component" rc=0
  for hook in preflight install start validate; do
    if [ "$hook" = "install" ]; then
      rm -rf "/opt/salmonbus/$component/staging"
      mkdir -p "/opt/salmonbus/$component/staging"
      cp -a "$a/." "/opt/salmonbus/$component/staging/"
    fi
    if ! bash "$a/scripts/$hook.sh" > "$WORK/$component-$hook.out" 2>&1; then
      rc=1
      [ "$expect" = "ok" ] && { bad "$component $hook.sh"; sed 's/^/      /' "$WORK/$component-$hook.out"; }
      break
    fi
  done
  if [ "$expect" = "ok" ]; then
    [ $rc -eq 0 ] && ok "$component 배포 네 훅 통과"
  else
    [ $rc -ne 0 ] && ok "$component 배포가 실패로 끝난다" || bad "$component 배포가 통과해 버렸다"
  fi
  return $rc
}

sec "1. 소스 지문이 안정적인가"
mkdir -p "$WORK/src1/main" "$WORK/src2/main"
echo "hello" > "$WORK/src1/main/A.java"; echo "world" > "$WORK/src1/main/B.java"
d1="$(cd "$WORK/src1" && source_digest main)"
d2="$(cd "$WORK/src1" && source_digest main)"
[ "$d1" = "$d2" ] && ok "같은 소스를 두 번 세면 같다" || bad "두 번 세니 달라진다"
echo "changed" > "$WORK/src1/main/B.java"
d3="$(cd "$WORK/src1" && source_digest main)"
[ "$d1" != "$d3" ] && ok "소스가 바뀌면 지문도 바뀐다" || bad "소스가 바뀌었는데 지문이 같다"
mkdir -p "$WORK/src1/test"; echo "t" > "$WORK/src1/test/T.java"
d4="$(cd "$WORK/src1" && source_digest main)"
[ "$d3" = "$d4" ] && ok "테스트만 늘어도 지문이 안 바뀐다" || bad "테스트가 지문을 흔든다"

sec "2. 첫 배포. api 먼저, worker 나중"
rm -rf /opt/salmonbus
make_revision api    APIv1    aaaaaaa1111 1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999 "2026-09-02T01:00:00Z"
make_revision worker WORKERv1 aaaaaaa1111 9999888877776666555544443333222211110000ffffeeeeddddccccbbbbaaaa "2026-09-02T01:00:00Z"
deploy api
deploy worker
[ "$(readlink -f /opt/salmonbus/api/current)" = "/opt/salmonbus/api/releases/1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999" ] \
  && ok "api current 가 소스 지문으로 이름 붙은 판을 가리킨다" || bad "api current=$(readlink -f /opt/salmonbus/api/current 2>/dev/null)"
grep -qx salmonbus-api /work/running && grep -qx salmonbus-worker /work/running \
  && ok "둘 다 올라갔다" || bad "안 올라간 것이 있다"
[ ! -f /opt/salmonbus/.deploying ] && ok "배포 표시가 지워졌다" || bad "배포 표시가 남았다"

sec "3. api 소스만 바뀐 배포"
: > /work/systemctl.log
make_revision api    APIv2    bbbbbbb2222 2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111 "2026-09-02T02:00:00Z"
make_revision worker WORKERv1 bbbbbbb2222 9999888877776666555544443333222211110000ffffeeeeddddccccbbbbaaaa "2026-09-02T02:00:00Z"
deploy api
deploy worker
grep -q 'stop salmonbus-api' /work/systemctl.log \
  && ok "api 는 내렸다 올렸다" || bad "api 가 재시작 안 됐다"
grep -q 'stop salmonbus-worker' /work/systemctl.log \
  && bad "worker 도 내렸다. 바뀐 것만 재시작이 안 된다" || ok "worker 는 안 건드렸다"
[ "$(readlink -f /opt/salmonbus/api/previous)" = "/opt/salmonbus/api/releases/1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999" ] \
  && ok "api previous 가 직전 판을 가리킨다" || bad "api previous=$(readlink -f /opt/salmonbus/api/previous 2>/dev/null)"

sec "4. 아무 소스도 안 바뀐 배포"
: > /work/systemctl.log
make_revision api    APIv2    ccccccc3333 2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111 "2026-09-02T03:00:00Z"
make_revision worker WORKERv1 ccccccc3333 9999888877776666555544443333222211110000ffffeeeeddddccccbbbbaaaa "2026-09-02T03:00:00Z"
deploy api
deploy worker
grep -qE 'stop salmonbus' /work/systemctl.log \
  && bad "안 바뀌었는데 내렸다" || ok "둘 다 안 건드렸다"
[ -d /opt/salmonbus/api/releases/2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111 ] \
  && ok "돌고 있는 판을 안 지웠다" || bad "돌고 있는 판이 사라졌다"

sec "5. api 배포가 도는 중이면 worker 가 안 끼어드나"
printf 'api %s\n' "$(date +%s)" > /opt/salmonbus/.deploying
if bash "$ARCHIVE/worker/scripts/preflight.sh" > "$WORK/lock.out" 2>&1; then
  bad "api 배포 중인데 worker 가 들어왔다"
else
  grep -q '도는 중' "$WORK/lock.out" && ok "worker 가 막혔다" || bad "막히긴 했는데 이유가 다르다"
fi
rm -f /opt/salmonbus/.deploying

sec "6. 옛 프로세스가 응답하면 validate 가 잡나"
: > /work/systemctl.log
echo "2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111" > /work/stale     # 새 판을 올렸는데 옛 판이 응답하는 상황
make_revision api APIv3 ddddddd4444 3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111bbbb2222 "2026-09-02T04:00:00Z"
deploy api fail
rm -f /work/stale

sec "7. health 가 안 오르면 실패로 끝나는가 (되돌리기가 걸리는 자리)"
echo DOWN > /work/health
make_revision api APIv4 eeeeeee5555 4444dddd5555eeee6666ffff7777000088889999aaaa1111bbbb2222cccc3333 "2026-09-02T05:00:00Z"
deploy api fail
echo UP > /work/health

sec "8. 판을 셋만 남기는가"
n=$(/bin/ls -1d /opt/salmonbus/api/releases/*/ 2>/dev/null | wc -l)
[ "$n" -le 3 ] && ok "api 판 $n 개" || bad "api 판이 $n 개다"

sec "9. 배포가 안 건드려야 할 것"
[ "$(stat -c '%a' /etc/salmonbus/worker.env)" = "600" ] \
  && ok "worker.env 권한 600 그대로" || bad "권한이 바뀌었다"
grep -qc '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env \
  && ok "키 줄이 그대로 있다" || bad "키 줄이 없어졌다"
[ -d /var/lib/salmonbus/model/current ] \
  && ok "계수 파일 자리가 배포 밖에 만들어졌다" || bad "계수 파일 자리가 없다"

sec "10. 훅 로그에 값이 새지 않았는가"
if grep -rqE 'GBIS_SERVICE_KEY=[^$]|DB_PASSWORD=[^$]' "$WORK"/*.out 2>/dev/null; then
  bad "훅 출력에 값이 찍혔다"
else
  ok "훅 출력에 값이 안 찍혔다"
fi

echo; echo "======== 결과: 통과 $PASS · 실패 $FAIL ========"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
