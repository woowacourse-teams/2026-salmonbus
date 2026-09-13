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
chmod 600 /etc/salmonbus/*.env; chown root:root /etc/salmonbus/*.env

printf '#!/bin/sh\necho "openjdk version \\"21.0.12\\" 2026-08-18" >&2\n' > /usr/bin/java

cat > /usr/local/bin/systemctl <<'EOF'
#!/bin/bash
echo "$*" >> /work/systemctl.log
case "$1" in
  is-active)     grep -qx "$3" /work/running 2>/dev/null && exit 0 || exit 3 ;;
  enable)        grep -qx "$2" /work/enabled 2>/dev/null || echo "$2" >> /work/enabled; exit 0 ;;
  is-enabled)    grep -qx "$3" /work/enabled 2>/dev/null && exit 0 || exit 1 ;;
  stop)          grep -vx "$2" /work/running > /work/r.tmp 2>/dev/null || true; mv /work/r.tmp /work/running; exit 0 ;;
  start)         grep -qx "$2" /work/running 2>/dev/null || echo "$2" >> /work/running; exit 0 ;;
  daemon-reload) exit 0 ;;
  # 흉내가 모르는 명령에 성공을 돌려주면 깨진 훅이 여기를 그냥 통과한다
  *) echo "systemctl 흉내가 모르는 명령이다: $*" >&2; exit 64 ;;
esac
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
[ -n "$url" ] || { echo "curl 흉내: 주소가 없다" >&2; exit 2; }
# 상태 파일이 없으면 UP 으로 넘기지 않는다. 준비가 빠진 것을 통과시키면 안 된다
[ -f /work/health ] || { echo "curl 흉내: /work/health 가 없다" >&2; exit 2; }
state="$(cat /work/health)"
# 아는 주소만 답한다. 포트나 경로가 틀리면 여기서 걸린다
case "$url" in
  http://127.0.0.1:8082/actuator/health|http://127.0.0.1:8082/actuator/info) comp=api ;;
  http://127.0.0.1:8081/actuator/health|http://127.0.0.1:8081/actuator/info) comp=worker ;;
  http://127.0.0.1:8080/api/v1/routes) comp=api ;;
  *) echo "curl 흉내가 모르는 주소다: $url" >&2; exit 2 ;;
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
echo "curl 흉내가 모르는 경로다: $url" >&2; exit 2
EOF
chmod +x /usr/bin/java /usr/local/bin/systemctl /usr/local/bin/curl
echo UP > "$WORK/health"; : > "$WORK/running"; : > "$WORK/enabled"
: > "$WORK/systemctl.log"; rm -f "$WORK/stale"
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
DEPLOY_SEQ=0
deploy() {
  local component="$1" expect="${2:-ok}"
  local a="$ARCHIVE/$component" rc=0
  DEPLOY_SEQ=$((DEPLOY_SEQ + 1))
  export DEPLOYMENT_ID="d-$component-$DEPLOY_SEQ" 
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
[ ! -d /opt/salmonbus/.deploying ] && ok "배포 잠금이 풀렸다" || bad "배포 잠금이 남았다"
grep -qx salmonbus-api /work/enabled && grep -qx salmonbus-worker /work/enabled \
  && ok "둘 다 enable 됐다. 재부팅해도 올라온다" || bad "enable 안 된 것이 있다"

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
# 겹침이 나는 길은 restart 를 쓰거나 stop 없이 start 하는 것이다
grep -q '^restart ' /work/systemctl.log \
  && bad "restart 를 썼다. 내렸다 올리는 것을 눈으로 못 본다" || ok "restart 를 안 쓴다"
stop_line=$(grep -n '^stop salmonbus-api$' /work/systemctl.log | head -1 | cut -d: -f1)
start_line=$(grep -n '^start salmonbus-api$' /work/systemctl.log | head -1 | cut -d: -f1)
[ -n "$stop_line" ] && [ -n "$start_line" ] && [ "$stop_line" -lt "$start_line" ] \
  && ok "stop 이 start 보다 먼저다" || bad "stop=$stop_line start=$start_line 순서가 아니다"
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
mkdir -p /opt/salmonbus/.deploying
printf 'api d-api-999 %s\n' "$(date +%s)" > /opt/salmonbus/.deploying/owner
if bash "$ARCHIVE/worker/scripts/preflight.sh" > "$WORK/lock.out" 2>&1; then
  bad "api 배포 중인데 worker 가 들어왔다"
else
  grep -q '도는 중' "$WORK/lock.out" && ok "worker 가 막혔다" || bad "막히긴 했는데 이유가 다르다"
fi
rm -rf /opt/salmonbus/.deploying

sec "5-2. 한 번 실패한 직후 바로 되돌리기 (CodeDeploy 가 하는 순서)"
# 성공 -> 실패 -> 그 직전 판 재배포. 이때 current 는 실패한 판, previous 는 직전 성공 판이다
RB_GOOD=aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666777788889999aaaa
RB_BAD=bbbb2222cccc3333dddd4444eeee5555ffff6666777788889999aaaabbbb1111
make_revision api RBGOOD ggggggg0001 "$RB_GOOD" "2026-09-02T06:00:00Z"
deploy api
echo DOWN > /work/health
make_revision api RBBAD bbbbbbb0002 "$RB_BAD" "2026-09-02T06:10:00Z"
deploy api fail
echo UP > /work/health
[ "$(readlink -f /opt/salmonbus/api/current)" = "/opt/salmonbus/api/releases/$RB_BAD" ] \
  && ok "실패한 판이 current 다" || bad "current=$(readlink -f /opt/salmonbus/api/current)"
[ "$(readlink -f /opt/salmonbus/api/previous)" = "/opt/salmonbus/api/releases/$RB_GOOD" ] \
  && ok "직전 성공 판이 previous 다" || bad "previous=$(readlink -f /opt/salmonbus/api/previous)"
# CodeDeploy 의 자동 롤백은 직전 성공 revision 을 그대로 다시 배포하는 것이다
: > /work/systemctl.log
make_revision api RBGOOD ggggggg0001 "$RB_GOOD" "2026-09-02T06:00:00Z"
deploy api
[ "$(readlink -f /opt/salmonbus/api/current)" = "/opt/salmonbus/api/releases/$RB_GOOD" ] \
  && ok "current 가 직전 성공 판으로 돌아왔다" || bad "current=$(readlink -f /opt/salmonbus/api/current)"
grep -qx salmonbus-api /work/running \
  && ok "되돌린 뒤에도 서비스가 돈다" || bad "되돌렸는데 안 돈다"

sec "6. 옛 프로세스가 응답하면 validate 가 잡나"
: > /work/systemctl.log
echo "2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111" > /work/stale     # 새 판을 올렸는데 옛 판이 응답하는 상황
make_revision api APIv3 ddddddd4444 3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111bbbb2222 "2026-09-02T04:00:00Z"
deploy api fail
rm -f /work/stale

sec "7. health 가 안 오르면 실패로 끝나는가"
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

sec "9-2. 배포판 목록을 손대면 releases 밖을 건드리나"
before_count=$(/bin/ls -1d /opt/salmonbus/api/releases/*/ 2>/dev/null | wc -l)
mkdir -p /opt/salmonbus/바깥/지워지면안됨 && touch /opt/salmonbus/바깥/지워지면안됨/파일
for evil in "../../../바깥/지워지면안됨" "짧은지문" "$(printf 'z%.0s' $(seq 1 64))"; do
  make_revision api EVIL zzzzzzz9999 1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999 "2026-09-02T09:00:00Z"
  sed -i "s#^sourceDigest=.*#sourceDigest=$evil#" "$ARCHIVE/api/release-manifest.txt"
  if bash "$ARCHIVE/api/scripts/preflight.sh" > "$WORK/evil.out" 2>&1; then
    bad "손댄 목록이 preflight 를 통과했다: $evil"
  else
    ok "손댄 목록을 preflight 가 막았다"
  fi
done
[ -f /opt/salmonbus/바깥/지워지면안됨/파일 ] \
  && ok "releases 밖 파일이 그대로다" || bad "releases 밖이 지워졌다"
after_count=$(/bin/ls -1d /opt/salmonbus/api/releases/*/ 2>/dev/null | wc -l)
[ "$before_count" = "$after_count" ] \
  && ok "판 수가 안 바뀌었다" || bad "판이 $before_count 에서 $after_count 로 바뀌었다"
rm -rf /opt/salmonbus/바깥

sec "9-3. env 파일 권한이 헐거우면 막나"
chmod 644 /etc/salmonbus/api.env
make_revision api APIPERM ppppppp1111 1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999 "2026-09-02T10:00:00Z"
if bash "$ARCHIVE/api/scripts/preflight.sh" > "$WORK/perm.out" 2>&1; then
  bad "644 인 env 파일이 통과했다"
else
  grep -q '권한이 644' "$WORK/perm.out" && ok "헐거운 권한을 막았다" || bad "막긴 했는데 이유가 다르다"
fi
chmod 600 /etc/salmonbus/api.env

sec "9-4. 잠금을 둘이 동시에 잡으면 하나만 되나"
rm -rf /opt/salmonbus/.deploying
cat > "$WORK/race.sh" <<'RACE'
#!/bin/bash
export DEPLOYMENT_ID="$2"
source /archive/$1/scripts/common.sh
claim_deploy && echo "$1 잡았다" >> /work/race.out
sleep 2
RACE
chmod +x "$WORK/race.sh"
: > "$WORK/race.out"
"$WORK/race.sh" api d-race-api > /dev/null 2>&1 &
"$WORK/race.sh" worker d-race-worker > /dev/null 2>&1 &
wait
won=$(wc -l < "$WORK/race.out")
[ "$won" = "1" ] && ok "둘이 동시에 잡으러 가서 하나만 잡았다" || bad "$won 개가 잡았다"
rm -rf /opt/salmonbus/.deploying

sec "9-5. 실패한 훅이 잠금을 남기나"
rm -rf /opt/salmonbus/.deploying
chmod 644 /etc/salmonbus/api.env
bash "$ARCHIVE/api/scripts/preflight.sh" > /dev/null 2>&1 || true
chmod 600 /etc/salmonbus/api.env
[ ! -d /opt/salmonbus/.deploying ] \
  && ok "실패한 훅이 잠금을 풀고 나갔다" || bad "잠금이 남았다"

sec "9-6. 잠금이 찰나에 비어 있을 때"
# 첫 배포에서 실제로 났다. Deploy 액션 둘이 같이 돌아서
# 한쪽이 잠금을 잡자마자 놓는 사이에 다른 쪽이 읽었다.
# owner 를 못 읽었는데 "다른 배포가 0초째 도는 중" 하고 멈췄다
cat > "$WORK/claim.sh" <<'CLAIM'
#!/bin/bash
export DEPLOYMENT_ID="$2"
source /archive/$1/scripts/common.sh
claim_deploy
CLAIM
chmod +x "$WORK/claim.sh"

# owner 가 아직 안 쓰인 잠금을 남이 곧 놓는다. 다시 보면 잡을 수 있다
rm -rf /opt/salmonbus/.deploying
mkdir -p /opt/salmonbus/.deploying
( sleep 0.5; rm -rf /opt/salmonbus/.deploying ) &
if "$WORK/claim.sh" api d-flicker-1 > "$WORK/flicker.out" 2>&1; then
  ok "누가 잡았는지 안 적힌 잠금이 곧 풀리면 다시 잡는다"
else
  bad "누가 잡았는지 안 적힌 것을 보고 바로 멈췄다"; sed 's/^/      /' "$WORK/flicker.out"
fi
wait
rm -rf /opt/salmonbus/.deploying

# 누가 잡았는지 안 적힌 잠금이 계속 남아 있으면 세 번 다시 보고 멈춘다.
# 그동안 셸이 owner 리디렉션 오류를 stderr 로 흘리면 안 된다
mkdir -p /opt/salmonbus/.deploying
if "$WORK/claim.sh" api d-flicker-2 > "$WORK/noowner.out" 2> "$WORK/noowner.err"; then
  bad "누가 잡았는지 모르는 잠금을 그냥 뺏었다"
else
  ok "누가 잡았는지 끝내 안 나오면 멈춘다"
fi
grep -q '번 잡아 봤는데 못 잡았다' "$WORK/noowner.out" \
  && ok "멈춘 이유가 세 번 다 못 잡은 것이다" \
  || { bad "멈춘 이유가 다르다"; sed 's/^/      /' "$WORK/noowner.out"; }
if [ -s "$WORK/noowner.err" ]; then
  bad "owner 를 읽다가 난 셸 오류가 stderr 로 샜다"; sed 's/^/      /' "$WORK/noowner.err"
else
  ok "owner 가 없어도 stderr 가 깨끗하다"
fi
rm -rf /opt/salmonbus/.deploying

# 진짜로 다른 서비스가 잡고 있으면 다시 보지 않고 바로 멈춘다. 이건 설계다
mkdir -p /opt/salmonbus/.deploying
printf 'worker d-worker-777 %s\n' "$(date +%s)" > /opt/salmonbus/.deploying/owner
if "$WORK/claim.sh" api d-flicker-3 > "$WORK/held.out" 2>&1; then
  bad "worker 가 잡고 있는데 api 가 들어왔다"
else
  grep -q 'worker 배포가' "$WORK/held.out" \
    && ok "누가 잡았는지 읽고 멈췄다" \
    || { bad "막히긴 했는데 이유가 다르다"; sed 's/^/      /' "$WORK/held.out"; }
fi
grep -q '번 잡아 봤는데 못 잡았다' "$WORK/held.out" \
  && bad "누가 잡았는지 아는데도 세 번 다시 봤다" || ok "누가 잡았는지 알면 다시 보지 않는다"
rm -rf /opt/salmonbus/.deploying

sec "9-7. 값이 박힌 JAR 을 배포판 검사가 막나"
mkdir -p "$WORK/rev-ok/jars" "$WORK/rev-bad/jars"
python3 /rehearse/makejar.py "$WORK/rev-ok/jars/api-app.jar" api-app   'spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/salmonbus}
    password: ${DB_PASSWORD:salmonbus}
' "2026-09-02T10:00:00Z"
python3 /rehearse/makejar.py "$WORK/rev-bad/jars/api-app.jar" api-app   'spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/salmonbus}
    password: ${DB_PASSWORD:salmonbus}
  other:
    password: 실제로박힌값1234
' "2026-09-02T10:00:00Z"
bash /deploy/verify-revision.sh "$WORK/rev-ok" > /dev/null 2>&1 \
  && ok "자리표시자만 있는 JAR 은 통과한다" || bad "멀쩡한 JAR 을 막았다"
if bash /deploy/verify-revision.sh "$WORK/rev-bad" > "$WORK/vr.out" 2>&1; then
  bad "값이 박힌 JAR 이 통과했다"
else
  ok "값이 박힌 JAR 을 막았다"
  grep -q '실제로박힌값' "$WORK/vr.out" && bad "오류 문구에 값이 찍혔다" || ok "오류 문구에 값이 안 찍혔다"
fi

sec "10. 훅 로그에 값이 새지 않았는가"
if grep -rqE 'GBIS_SERVICE_KEY=[^$]|DB_PASSWORD=[^$]' "$WORK"/*.out 2>/dev/null; then
  bad "훅 출력에 값이 찍혔다"
else
  ok "훅 출력에 값이 안 찍혔다"
fi

echo; echo "======== 결과: 통과 $PASS · 실패 $FAIL ========"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
