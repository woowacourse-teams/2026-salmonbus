#!/usr/bin/env bash
# EC2 환경을 모의한 배포 리허설이다.
# CodeDeploy 는 revision 을 자기 폴더에 풀고 거기서 훅을 돌린다.
# files: 섹션이 그 내용을 /opt/salmonbus/<component>/staging 으로 복사한다. 그 흐름 그대로 재현한다.
# revision 이 둘(api·worker)이라 배포도 둘씩 실행된다
set -uo pipefail
PASS=0; FAIL=0
ok()  { echo "  통과: $*"; PASS=$((PASS+1)); }
bad() { echo "  실패: $*"; FAIL=$((FAIL+1)); }
sec() { echo; echo "======== $* ========"; }

DEPLOY=/deploy
WORK=/work
ARCHIVE=/archive

sec "0. 서버 모의 환경 준비"
dnf -q install -y zip unzip findutils util-linux shadow-utils diffutils procps-ng >/dev/null 2>&1
useradd -r -s /sbin/nologin salmonbus 2>/dev/null || true
id salmonbus >/dev/null 2>&1 && ok "salmonbus 사용자" || bad "사용자 생성 실패"

mkdir -p /etc/salmonbus "$WORK" /etc/systemd/system   # EC2 에는 systemd 가 있어서 이 디렉터리가 있다
printf 'DB_URL=jdbc:postgresql://rds:5432/salmonbus\nDB_USERNAME=x\nDB_PASSWORD=rehearsal-secret-7f3a\n' > /etc/salmonbus/api.env
printf 'DB_URL=jdbc:postgresql://rds:5432/salmonbus\nDB_USERNAME=x\nDB_PASSWORD=rehearsal-secret-7f3a\nGBIS_SERVICE_KEY=z\n' > /etc/salmonbus/worker.env
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
  start)         if ! grep -qx "$2" /work/running 2>/dev/null; then
                   echo "$2" >> /work/running
                   # start 할 때마다 PID 를 하나 올린다. 재시작했는지를 PID 로 본다
                   n=$(( $(cat /work/pidseq 2>/dev/null || echo 1000) + 1 )); echo "$n" > /work/pidseq; echo "$n" > "/work/pid.$2"
                 fi; exit 0 ;;
  show)          u="${*: -1}"; grep -qx "$u" /work/running 2>/dev/null && cat "/work/pid.$u" || echo 0; exit 0 ;;
  daemon-reload) exit 0 ;;
  # 모의 구현이 모르는 명령에 성공을 돌려주면 깨진 훅이 여기를 그냥 통과한다
  *) echo "systemctl 모의 구현이 모르는 명령이다: $*" >&2; exit 64 ;;
esac
EOF

# /actuator 와 8080 /readyz 를 모의한다. info 는 현재 실행 중인 배포 버전의 release.env 를 읽어 답한다.
# validate 가 -f 를 안 쓰니 503 도 종료 코드 0 으로 돌려준다.
#   /work/health 가 DOWN   관리 포트가 503 {"status":"DOWN"} 을 낸다
#   /work/ready 가 DOWN    8080 /readyz 만 503 을 낸다. 관리 포트는 멀쩡하다
#   /work/refuse 가 있으면  연결 자체가 안 된다 (curl 종료 코드 7, 상태 코드 000)
#   /work/stale 이 있으면   옛 프로세스가 아직 응답하는 상황을 재현한다
# 부른 주소는 /work/curl.log 에 쌓는다. validate 가 뭘 물었는지 여기서 본다
cat > /usr/local/bin/curl <<'EOF'
#!/bin/bash
url=""; fmt=""
while [ $# -gt 0 ]; do
  case "$1" in
    -w) fmt="$2"; shift ;;
    -o|--max-time) shift ;;
    http*) url="$1" ;;
  esac
  shift
done
[ -n "$url" ] || { echo "curl 모의 구현: 주소가 없다" >&2; exit 2; }
echo "$url" >> /work/curl.log
# 상태 파일이 없으면 UP 으로 넘기지 않는다. 준비가 빠진 것을 통과시키면 안 된다
[ -f /work/health ] || { echo "curl 모의 구현: /work/health 가 없다" >&2; exit 2; }
# 아는 주소만 답한다. 포트나 경로가 틀리면 여기서 걸린다
case "$url" in
  http://127.0.0.1:8082/actuator/health|http://127.0.0.1:8082/actuator/info) comp=api;    state="$(cat /work/health)" ;;
  http://127.0.0.1:8081/actuator/health|http://127.0.0.1:8081/actuator/info) comp=worker; state="$(cat /work/health)" ;;
  http://127.0.0.1:8080/readyz) comp=api; state="$(cat /work/ready 2>/dev/null || echo UP)" ;;
  *) echo "curl 모의 구현이 모르는 주소다: $url" >&2; exit 2 ;;
esac
# 본문을 쓰고, -w 에 %{http_code} 가 있으면 진짜 curl 처럼 줄바꿈 뒤에 상태 코드를 붙인다
answer() {
  [ -n "$2" ] && printf '%s' "$2"
  case "$fmt" in *'%{http_code}'*) printf '\n%s' "$1" ;; esac
  exit "$3"
}
[ -f /work/refuse ] && answer 000 '' 7
if [ "$state" != "UP" ]; then
  case "$url" in */readyz) answer 503 '{"status":"OUT_OF_SERVICE"}' 0 ;; esac
  answer 503 '{"status":"DOWN"}' 0
fi
case "$url" in
  */health) answer 200 '{"groups":["liveness","readiness"],"status":"UP"}' 0 ;;
  */readyz) answer 200 '{"status":"UP"}' 0 ;;
  */info)
    envf="/opt/salmonbus/$comp/current/release.env"
    d="$(sed -n 's/^INFO_SOURCEDIGEST=//p' "$envf" 2>/dev/null)"
    c="$(sed -n 's/^INFO_COMPONENT=//p' "$envf" 2>/dev/null)"
    [ -f /work/stale ] && d="$(cat /work/stale)"
    answer 200 "{\"sourcedigest\":\"$d\",\"component\":\"$c\",\"build\":{}}" 0 ;;
esac
echo "curl 모의 구현이 모르는 경로다: $url" >&2; exit 2
EOF
# psql 모의 구현. 받은 인자를 /work/psql.log 에 쌓는다. PGPASSWORD 가 없거나 비밀번호가 인자에 보이면 실패다.
# /work/db 가 DOWN 이면 인증 실패, HANG 이면 답을 안 한다. 훅의 timeout 이 죽여야 한다
cat > /usr/local/bin/psql <<'EOF'
#!/bin/bash
printf '%s\n' "$*" >> /work/psql.log
[ -n "${PGPASSWORD:-}" ] || { echo "psql 모의 구현: PGPASSWORD 가 없다" >&2; exit 2; }
case "$*" in *"$PGPASSWORD"*) echo "psql 모의 구현: 비밀번호가 명령줄에 있다" >&2; exit 2 ;; esac
case "$(cat /work/db 2>/dev/null || echo UP)" in
  UP)   echo 1; exit 0 ;;
  HANG) exec sleep 60 ;;
  *)    echo 'psql: error: connection to server at "rds" (10.0.0.5), port 5432 failed: FATAL:  password authentication failed for user "x"' >&2; exit 2 ;;
esac
EOF
chmod +x /usr/bin/java /usr/local/bin/systemctl /usr/local/bin/curl /usr/local/bin/psql
echo UP > "$WORK/health"; : > "$WORK/running"; : > "$WORK/enabled"
: > "$WORK/systemctl.log"; : > "$WORK/curl.log"; : > "$WORK/psql.log"
rm -f "$WORK/stale" "$WORK/ready" "$WORK/refuse" "$WORK/db" "$WORK/pidseq"
ok "java · systemctl · curl · psql 모의 구현"

source /rehearse/digest.sh

# revision 한 벌을 만든다. buildspec 의 pack() 과 같은 모양이다
make_revision() {
  local component="$1" body="$2" commit="$3" digest="$4" stamp="$5"
  local a="$ARCHIVE/$component"
  rm -rf "$a"; mkdir -p "$a/jars" "$a/scripts" "$a/systemd"
  bash /rehearse/makejar.sh "$a/jars/$component-app.jar" "$component-app" "$body" "$stamp"
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

# CodeDeploy 모의 구현. Install 이벤트가 revision 을 staging 으로 복사한다
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
[ "$d1" = "$d2" ] && ok "같은 소스에서 지문을 두 번 계산해도 같다" || bad "두 번 계산하니 지문이 달라진다"
echo "changed" > "$WORK/src1/main/B.java"
d3="$(cd "$WORK/src1" && source_digest main)"
[ "$d1" != "$d3" ] && ok "소스가 바뀌면 지문도 바뀐다" || bad "소스가 바뀌었는데 지문이 같다"
mkdir -p "$WORK/src1/test"; echo "t" > "$WORK/src1/test/T.java"
d4="$(cd "$WORK/src1" && source_digest main)"
[ "$d3" = "$d4" ] && ok "테스트만 늘어도 지문이 안 바뀐다" || bad "테스트가 지문을 바꾼다"

sec "2. 첫 배포. api 먼저, worker 나중"
rm -rf /opt/salmonbus
: > /work/curl.log
make_revision api    APIv1    aaaaaaa1111 1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999 "2026-09-02T01:00:00Z"
make_revision worker WORKERv1 aaaaaaa1111 9999888877776666555544443333222211110000ffffeeeeddddccccbbbbaaaa "2026-09-02T01:00:00Z"
deploy api
deploy worker
[ "$(readlink -f /opt/salmonbus/api/current)" = "/opt/salmonbus/api/releases/1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999" ] \
  && ok "api current 가 소스 지문으로 이름 붙은 배포 버전을 가리킨다" || bad "api current=$(readlink -f /opt/salmonbus/api/current 2>/dev/null)"
grep -qx salmonbus-api /work/running && grep -qx salmonbus-worker /work/running \
  && ok "둘 다 시작됐다" || bad "시작 안 된 것이 있다"
[ ! -d /opt/salmonbus/.deploying ] && ok "배포 잠금이 풀렸다" || bad "배포 잠금이 남았다"
grep -qx salmonbus-api /work/enabled && grep -qx salmonbus-worker /work/enabled \
  && ok "둘 다 enable 됐다. 재부팅해도 자동 시작한다" || bad "enable 안 된 것이 있다"
grep -q 'http://127.0.0.1:8080/readyz' /work/curl.log \
  && ok "api validate 가 8080 /readyz 를 물었다" || bad "8080 /readyz 를 안 물었다"
grep -q '/api/v1/routes' /work/curl.log \
  && bad "업무 API 를 아직 부른다" || ok "업무 API 는 부르지 않는다"
[ "$(grep -c '8080/readyz' /work/curl.log)" = "1" ] \
  && ok "worker validate 는 8080 을 안 본다" || bad "8080 /readyz 를 $(grep -c '8080/readyz' /work/curl.log) 번 물었다"
grep -q 'readyz 확인\. 시도=1 소요=[0-9]*초 http=200' /work/api-validate.out \
  && ok "검사마다 시도 횟수와 HTTP 상태가 남는다" || { bad "readyz 로그가 다르다"; sed 's/^/      /' /work/api-validate.out; }
grep -q '배포 검증 끝\. component=api commit=aaaaaaa1111 PID=[1-9][0-9]* 경과=[0-9]*초 종료 코드=0' /work/api-validate.out \
  && ok "요약에 PID·경과·종료 코드가 남는다" || { bad "요약이 다르다"; grep '배포 검증' /work/api-validate.out | sed 's/^/      /'; }
grep -q '배포 전: PID=0 지문=없음' /work/api-preflight.out \
  && ok "첫 배포 전 상태를 남긴다" || { bad "배포 전 상태 로그가 다르다"; sed 's/^/      /' /work/api-preflight.out; }
grep -q 'DB 진단(배포 전): 새 연결 성공\. [0-9]*ms host=rds db=salmonbus' /work/api-preflight.out \
  && ok "preflight 가 DB 에 새 연결을 붙여 봤다" || { bad "DB 진단 로그가 다르다"; sed 's/^/      /' /work/api-preflight.out; }
grep -q 'salmonbus-api 시작했다\. PID=[1-9][0-9]*' /work/api-start.out \
  && ok "start 가 새 PID 를 남긴다" || { bad "start 로그가 다르다"; sed 's/^/      /' /work/api-start.out; }

sec "3. api 소스만 바뀐 배포"
: > /work/systemctl.log
make_revision api    APIv2    bbbbbbb2222 2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111 "2026-09-02T02:00:00Z"
make_revision worker WORKERv1 bbbbbbb2222 9999888877776666555544443333222211110000ffffeeeeddddccccbbbbaaaa "2026-09-02T02:00:00Z"
deploy api
deploy worker
grep -q 'stop salmonbus-api' /work/systemctl.log \
  && ok "api 는 중지 후 다시 시작했다" || bad "api 가 재시작 안 됐다"
grep -q 'stop salmonbus-worker' /work/systemctl.log \
  && bad "worker 도 중지됐다. 바뀐 것만 재시작이 안 된다" || ok "worker 는 재시작하지 않았다"
# restart를 쓰거나 stop 없이 start를 실행하면 겹침이 생긴다.
grep -q '^restart ' /work/systemctl.log \
  && bad "restart 를 썼다. 완전히 종료된 뒤에 시작했는지 확인할 수 없다" || ok "restart 를 안 쓴다"
stop_line=$(grep -n '^stop salmonbus-api$' /work/systemctl.log | head -1 | cut -d: -f1)
start_line=$(grep -n '^start salmonbus-api$' /work/systemctl.log | head -1 | cut -d: -f1)
[ -n "$stop_line" ] && [ -n "$start_line" ] && [ "$stop_line" -lt "$start_line" ] \
  && ok "stop 이 start 보다 먼저다" || bad "stop=$stop_line start=$start_line 순서가 아니다"
[ "$(readlink -f /opt/salmonbus/api/previous)" = "/opt/salmonbus/api/releases/1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999" ] \
  && ok "api previous 가 직전 배포 버전을 가리킨다" || bad "api previous=$(readlink -f /opt/salmonbus/api/previous 2>/dev/null)"
before=$(sed -n 's/.*api 를 재시작한다\. 이유=소스 지문 변경 1111aaaa2222 -> 2222bbbb3333 PID=\([0-9]*\)$/\1/p' /work/api-start.out)
after=$(sed -n 's/.*salmonbus-api 시작했다\. PID=\([0-9]*\)$/\1/p' /work/api-start.out)
if [ -n "$before" ] && [ -n "$after" ] && [ "$before" != "$after" ]; then
  ok "재시작 이유와 전후 PID($before -> $after)가 남는다"
else
  bad "재시작 이유나 PID 로그가 다르다"; sed 's/^/      /' /work/api-start.out
fi
grep -q "PID=$after 경과=" /work/api-validate.out \
  && ok "validate 요약의 PID 가 새 프로세스다" || bad "validate 요약의 PID 가 다르다"
grep -q 'worker 는 재시작하지 않는다\. 이유=변경 없음 PID=[1-9][0-9]* 그대로' /work/worker-start.out \
  && ok "worker 는 이유와 PID 를 남기고 재시작하지 않는다" || { bad "worker start 로그가 다르다"; sed 's/^/      /' /work/worker-start.out; }

sec "4. 아무 소스도 안 바뀐 배포"
: > /work/systemctl.log
make_revision api    APIv2    ccccccc3333 2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111 "2026-09-02T03:00:00Z"
make_revision worker WORKERv1 ccccccc3333 9999888877776666555544443333222211110000ffffeeeeddddccccbbbbaaaa "2026-09-02T03:00:00Z"
deploy api
deploy worker
grep -qE 'stop salmonbus' /work/systemctl.log \
  && bad "안 바뀌었는데 중지됐다" || ok "둘 다 재시작하지 않았다"
[ -d /opt/salmonbus/api/releases/2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111 ] \
  && ok "실행 중인 배포 버전을 안 지웠다" || bad "실행 중인 배포 버전이 사라졌다"
grep -q 'api 는 재시작하지 않는다\. 이유=변경 없음 PID=[1-9][0-9]* 그대로' /work/api-start.out \
  && ok "api 도 이유와 PID 를 남기고 재시작하지 않는다" || { bad "api start 로그가 다르다"; sed 's/^/      /' /work/api-start.out; }

sec "5. api 배포가 진행 중이면 worker 가 안 끼어드나"
mkdir -p /opt/salmonbus/.deploying
printf 'api d-api-999 %s\n' "$(date +%s)" > /opt/salmonbus/.deploying/owner
if bash "$ARCHIVE/worker/scripts/preflight.sh" > "$WORK/lock.out" 2>&1; then
  bad "api 배포 중인데 worker 가 들어왔다"
else
  grep -q '진행 중이다' "$WORK/lock.out" && ok "worker 가 막혔다" || bad "막히긴 했는데 이유가 다르다"
fi
rm -rf /opt/salmonbus/.deploying

sec "5-2. 한 번 실패한 직후 바로 롤백 (CodeDeploy 가 하는 순서)"
# 성공 -> 실패 -> 직전 배포 버전 재배포. 이때 current 는 실패한 쪽, previous 는 직전 성공 버전이다
RB_GOOD=aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666777788889999aaaa
RB_BAD=bbbb2222cccc3333dddd4444eeee5555ffff6666777788889999aaaabbbb1111
make_revision api RBGOOD ggggggg0001 "$RB_GOOD" "2026-09-02T06:00:00Z"
deploy api
echo DOWN > /work/health
make_revision api RBBAD bbbbbbb0002 "$RB_BAD" "2026-09-02T06:10:00Z"
deploy api fail
echo UP > /work/health
[ "$(readlink -f /opt/salmonbus/api/current)" = "/opt/salmonbus/api/releases/$RB_BAD" ] \
  && ok "실패한 배포 버전이 current 다" || bad "current=$(readlink -f /opt/salmonbus/api/current)"
[ "$(readlink -f /opt/salmonbus/api/previous)" = "/opt/salmonbus/api/releases/$RB_GOOD" ] \
  && ok "직전 성공 배포 버전이 previous 다" || bad "previous=$(readlink -f /opt/salmonbus/api/previous)"
# CodeDeploy의 자동 롤백은 직전 성공 revision을 그대로 다시 배포한다.
: > /work/systemctl.log
make_revision api RBGOOD ggggggg0001 "$RB_GOOD" "2026-09-02T06:00:00Z"
deploy api
[ "$(readlink -f /opt/salmonbus/api/current)" = "/opt/salmonbus/api/releases/$RB_GOOD" ] \
  && ok "current 가 직전 성공 배포 버전으로 돌아왔다" || bad "current=$(readlink -f /opt/salmonbus/api/current)"
grep -qx salmonbus-api /work/running \
  && ok "롤백한 뒤에도 서비스가 실행 중이다" || bad "롤백했는데 서비스가 실행 중이 아니다"

# 6 부터 7-4 까지는 validate 가 실패하는 시나리오라 상한을 몇 초로 줄인다. CodeDeploy 환경에는 없는 변수다
export VALIDATE_CHECK_SECONDS=4 VALIDATE_RETRY_SECONDS=1

sec "6. 옛 프로세스가 응답하면 validate 가 잡나"
: > /work/systemctl.log
echo "2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111" > /work/stale     # 새 배포 버전을 시작했는데 옛 프로세스가 응답하는 상황
make_revision api APIv3 ddddddd4444 3333cccc4444dddd5555eeee6666ffff7777000088889999aaaa1111bbbb2222 "2026-09-02T04:00:00Z"
deploy api fail
rm -f /work/stale
grep -q 'health 확인\. 시도=1 ' /work/api-validate.out \
  && ok "health 는 통과했다" || bad "health 로그가 없다"
grep -q 'sourcedigest 시도 1: http=200 curl=0 sourcedigest=2222bbbb' /work/api-validate.out \
  && ok "옛 프로세스가 답한 지문이 시도마다 남는다" || { bad "응답한 지문이 로그에 없다"; sed 's/^/      /' /work/api-validate.out; }
grep -q '배포 검증 실패\. 단계=sourcedigest 사유=시간 초과(검사 상한 4초) 시도=[0-9]* 마지막=응답이 다르다 (sourcedigest=2222bbbb' /work/api-validate.out \
  && ok "요약 한 줄에 단계·사유·시도·마지막 응답이 있다" || { bad "요약이 다르다"; grep '배포 검증' /work/api-validate.out | sed 's/^/      /'; }

sec "7. health 가 안 오르면 실패로 끝나는가"
echo DOWN > /work/health
make_revision api APIv4 eeeeeee5555 4444dddd5555eeee6666ffff7777000088889999aaaa1111bbbb2222cccc3333 "2026-09-02T05:00:00Z"
deploy api fail
echo UP > /work/health
grep -q 'health 시도 1: http=503 curl=0 status=DOWN 경과=' /work/api-validate.out \
  && ok "시도마다 HTTP 상태와 status 가 남는다" || { bad "시도 로그가 다르다"; sed 's/^/      /' /work/api-validate.out; }
tries=$(grep -c '^\[[0-9:]*\] health 시도 ' /work/api-validate.out)
[ "$tries" -ge 3 ] && ok "상한 4초 동안 $tries 번 다시 물었다" || bad "$tries 번밖에 안 물었다"
grep -q '단계=health 사유=시간 초과(검사 상한 4초) 시도='"$tries"' 마지막=http=503 status=DOWN PID=[1-9][0-9]* 경과=' /work/api-validate.out \
  && ok "요약의 시도 횟수가 실제 시도와 같다" || { bad "요약이 다르다"; grep '배포 검증' /work/api-validate.out | sed 's/^/      /'; }
grep -q 'DB 진단(health 실패): 새 연결 성공' /work/api-validate.out \
  && ok "health 가 죽은 직후에 DB 는 붙는다고 남긴다" || { bad "health 실패 후 DB 진단이 없다"; sed 's/^/      /' /work/api-validate.out; }
[ ! -d /opt/salmonbus/.deploying ] && ok "실패한 validate 가 잠금을 풀었다" || bad "잠금이 남았다"

sec "7-2. 8082 에 연결이 안 되면 그게 로그에 남는가"
touch /work/refuse
make_revision api APIv5 fffffff6666 5555eeee6666ffff7777000088889999aaaa1111bbbb2222cccc3333dddd4444 "2026-09-02T05:10:00Z"
deploy api fail
rm -f /work/refuse
grep -q 'health 시도 1: http=000 curl=7 경과=' /work/api-validate.out \
  && ok "연결 실패가 http=000 curl=7 로 남는다" || { bad "연결 실패 로그가 다르다"; sed 's/^/      /' /work/api-validate.out; }
grep -q '단계=health 사유=시간 초과(검사 상한 4초) 시도=[0-9]* 마지막=연결 실패(curl=7)' /work/api-validate.out \
  && ok "사유가 연결 실패다" || { bad "사유가 다르다"; grep '배포 검증' /work/api-validate.out | sed 's/^/      /'; }

sec "7-3. 8082 는 UP 인데 8080 이 안 되면 실패로 끝나는가"
echo DOWN > /work/ready
make_revision api APIv6 ggggggg7777 6666ffff7777000088889999aaaa1111bbbb2222cccc3333dddd4444eeee5555 "2026-09-02T05:20:00Z"
deploy api fail
rm -f /work/ready
grep -q 'enable 확인\.' /work/api-validate.out \
  && ok "8082 검사와 enable 은 통과했다" || { bad "앞 검사가 통과하지 않았다"; sed 's/^/      /' /work/api-validate.out; }
grep -q 'readyz 시도 1: http=503 curl=0 status=OUT_OF_SERVICE' /work/api-validate.out \
  && ok "8080 의 503 이 status 와 함께 남는다" || { bad "readyz 시도 로그가 다르다"; sed 's/^/      /' /work/api-validate.out; }
grep -q '단계=readyz 사유=시간 초과' /work/api-validate.out \
  && ok "8080 만 안 되면 배포가 실패한다" || bad "8080 실패가 요약에 없다"

sec "7-4. 전체 상한이 검사 상한보다 먼저 오면 거기서 멈추나"
export VALIDATE_TOTAL_SECONDS=3 VALIDATE_CHECK_SECONDS=90
echo "6666ffff7777000088889999aaaa1111bbbb2222cccc3333dddd4444eeee5555" > /work/stale
make_revision api APIv7 hhhhhhh8888 7777000088889999aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666 "2026-09-02T05:30:00Z"
t0=$SECONDS
deploy api fail
dt=$((SECONDS - t0))
rm -f /work/stale
unset VALIDATE_TOTAL_SECONDS
grep -q '단계=sourcedigest 사유=시간 초과(전체 3초 중 남은 [0-9]초)' /work/api-validate.out \
  && ok "검사 상한 90초 대신 전체에서 남은 시간을 썼다" || { bad "사유가 다르다"; grep '배포 검증' /work/api-validate.out | sed 's/^/      /'; }
[ "$dt" -le 20 ] && ok "배포가 ${dt}초 만에 끝났다" || bad "검사 상한 90초를 기다렸다 (${dt}초)"
unset VALIDATE_CHECK_SECONDS VALIDATE_RETRY_SECONDS

sec "7-5. DB 가 안 붙거나 답이 없어도 배포는 가나"
echo DOWN > /work/db
make_revision api APIv8 iiiiiii9999 88889999aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff666677770000 "2026-09-02T05:40:00Z"
deploy api
grep -q 'DB 진단(배포 전): 새 연결 실패\. [0-9]*ms rc=2 host=rds db=salmonbus: psql: error: .* 배포는 계속한다' /work/api-preflight.out \
  && ok "인증 실패해도 rc 와 오류 문구만 남기고 배포는 간다" || { bad "DB 진단 실패 로그가 다르다"; sed 's/^/      /' /work/api-preflight.out; }
echo HANG > /work/db
make_revision api APIv9 jjjjjjj0000 9999aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666777700008888 "2026-09-02T05:50:00Z"
t0=$SECONDS
deploy api
dt=$((SECONDS - t0))
rm -f /work/db
grep -q 'DB 진단(배포 전): 새 연결 실패\. 15초 안에 응답이 없다 host=rds db=salmonbus\. 배포는 계속한다' /work/api-preflight.out \
  && ok "답이 없으면 15초에 끊고 배포는 간다" || { bad "무응답 로그가 다르다"; sed 's/^/      /' /work/api-preflight.out; }
[ "$dt" -ge 14 ] && [ "$dt" -le 40 ] && ok "끊는 데 ${dt}초 걸렸다" || bad "15초에 안 끊고 ${dt}초 걸렸다"

# validate.sh 의 전체 상한 기본값이 appspec 의 ValidateService timeout 보다 짧은지 두 파일을 읽어 본다.
# 길면 CodeDeploy 가 먼저 죽여서 요약도 잠금 정리도 없이 끝난다
hook_timeout=$(awk '/ValidateService:/{f=1} f && /timeout:/{print $2; exit}' /deploy/appspec-api.yml)
total_default=$(sed -n 's/.*VALIDATE_TOTAL_SECONDS:-\([0-9]*\)}.*/\1/p' /deploy/scripts/validate.sh)
if [ -n "$total_default" ] && [ -n "$hook_timeout" ] && [ "$total_default" -lt "$hook_timeout" ]; then
  ok "전체 상한 기본값 ${total_default}초 < ValidateService timeout ${hook_timeout}초"
else
  bad "전체 상한(${total_default:-?})이 훅 timeout(${hook_timeout:-?})보다 짧지 않다"
fi

sec "8. 배포 버전을 셋만 남기는가"
n=$(/bin/ls -1d /opt/salmonbus/api/releases/*/ 2>/dev/null | wc -l)
[ "$n" -le 3 ] && ok "api 배포 버전 $n 개" || bad "api 배포 버전이 $n 개다"

sec "9. 배포가 안 건드려야 할 것"
[ "$(stat -c '%a' /etc/salmonbus/worker.env)" = "600" ] \
  && ok "worker.env 권한 600 그대로" || bad "권한이 바뀌었다"
grep -qc '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env \
  && ok "키 줄이 그대로 있다" || bad "키 줄이 없어졌다"
[ -d /var/lib/salmonbus/model/current ] \
  && ok "모델 계수 디렉터리가 배포 밖에 만들어졌다" || bad "모델 계수 디렉터리가 없다"

sec "9-2. 배포 메타데이터를 변조하면 releases 밖을 건드리나"
before_count=$(/bin/ls -1d /opt/salmonbus/api/releases/*/ 2>/dev/null | wc -l)
mkdir -p /opt/salmonbus/바깥/지워지면안됨 && touch /opt/salmonbus/바깥/지워지면안됨/파일
for evil in "../../../바깥/지워지면안됨" "짧은지문" "$(printf 'z%.0s' $(seq 1 64))"; do
  make_revision api EVIL zzzzzzz9999 1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999 "2026-09-02T09:00:00Z"
  sed -i "s#^sourceDigest=.*#sourceDigest=$evil#" "$ARCHIVE/api/release-manifest.txt"
  if bash "$ARCHIVE/api/scripts/preflight.sh" > "$WORK/evil.out" 2>&1; then
    bad "변조한 배포 메타데이터가 preflight 를 통과했다: $evil"
  else
    ok "변조한 배포 메타데이터를 preflight 가 막았다"
  fi
done
[ -f /opt/salmonbus/바깥/지워지면안됨/파일 ] \
  && ok "releases 밖 파일이 그대로다" || bad "releases 밖이 지워졌다"
after_count=$(/bin/ls -1d /opt/salmonbus/api/releases/*/ 2>/dev/null | wc -l)
[ "$before_count" = "$after_count" ] \
  && ok "배포 버전 수가 안 바뀌었다" || bad "배포 버전 수가 $before_count 에서 $after_count 로 바뀌었다"
rm -rf /opt/salmonbus/바깥

sec "9-3. env 파일 권한이 600 이 아니면 막나"
chmod 644 /etc/salmonbus/api.env
make_revision api APIPERM ppppppp1111 1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777000088889999 "2026-09-02T10:00:00Z"
if bash "$ARCHIVE/api/scripts/preflight.sh" > "$WORK/perm.out" 2>&1; then
  bad "644 인 env 파일이 통과했다"
else
  grep -q '권한이 644' "$WORK/perm.out" && ok "600 이 아닌 권한을 막았다" || bad "막긴 했는데 이유가 다르다"
fi
chmod 600 /etc/salmonbus/api.env

sec "9-4. 잠금을 둘이 동시에 잡으면 하나만 잡히나"
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

sec "9-5-2. 별도 셸에서 실행된 후속 훅도 실패하면 잠금을 푸나"
if bash "$DEPLOY/rehearsal/test-deploy-lock.sh" > "$WORK/lock-regression.out" 2>&1; then
  ok "후속 훅이 실패하면 잠금을 풀고 종료 코드와 소유권도 지킨다"
else
  bad "후속 훅이 잠금을 안 풀었다"
  sed 's/^/      /' "$WORK/lock-regression.out"
fi

sec "9-6. 잠금은 있는데 owner 가 없을 때"
# 첫 배포에서 실제로 난 문제다. Deploy 액션 둘이 동시에 실행돼서
# 한쪽이 잠금을 잡자마자 놓는 사이에 다른 쪽이 읽었다.
# owner 를 못 읽었는데 "다른 배포가 0초째 진행 중" 하고 멈췄다
cat > "$WORK/claim.sh" <<'CLAIM'
#!/bin/bash
export DEPLOYMENT_ID="$2"
source /archive/$1/scripts/common.sh
claim_deploy
CLAIM
chmod +x "$WORK/claim.sh"

# owner 가 아직 안 쓰인 잠금을 다른 배포가 곧 놓는다. 다시 보면 잡을 수 있다
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

sec "9-7. 자리표시자가 아닌 값이 든 JAR 을 배포 패키지 검사가 막나"
mkdir -p "$WORK/rev-ok/jars" "$WORK/rev-bad/jars"
bash /rehearse/makejar.sh "$WORK/rev-ok/jars/api-app.jar" api-app   'spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/salmonbus}
    password: ${DB_PASSWORD:salmonbus}
' "2026-09-02T10:00:00Z"
bash /rehearse/makejar.sh "$WORK/rev-bad/jars/api-app.jar" api-app   'spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/salmonbus}
    password: ${DB_PASSWORD:salmonbus}
  other:
    password: 실제로박힌값1234
' "2026-09-02T10:00:00Z"
bash /deploy/verify-revision.sh "$WORK/rev-ok" > /dev/null 2>&1 \
  && ok "자리표시자만 있는 JAR 은 통과한다" || bad "자리표시자만 있는 JAR 을 막았다"
if bash /deploy/verify-revision.sh "$WORK/rev-bad" > "$WORK/vr.out" 2>&1; then
  bad "자리표시자가 아닌 값이 든 JAR 이 통과했다"
else
  ok "자리표시자가 아닌 값이 든 JAR 을 막았다"
  grep -q '실제로박힌값' "$WORK/vr.out" && bad "오류 문구에 값이 찍혔다" || ok "오류 문구에 값이 안 찍혔다"
fi

sec "10. 훅 로그에 값이 새지 않았는가"
if grep -rqE 'GBIS_SERVICE_KEY=[^$]|DB_PASSWORD=[^$]' "$WORK"/*.out 2>/dev/null; then
  bad "훅 출력에 값이 찍혔다"
else
  ok "훅 출력에 값이 안 찍혔다"
fi
if grep -q '"groups"\|"build":{}' "$WORK"/*-validate.out 2>/dev/null; then
  bad "validate 로그에 응답 본문이 통째로 찍혔다"
else
  ok "validate 로그에 응답 본문이 안 찍힌다"
fi
if grep -rq 'rehearsal-secret-7f3a' "$WORK"/*.out "$WORK"/psql.log 2>/dev/null; then
  bad "DB 비밀번호가 훅 출력이나 psql 명령줄에 찍혔다"
else
  ok "DB 비밀번호가 훅 출력과 psql 명령줄에 없다"
fi
grep -q 'sslmode=require' "$WORK/psql.log" \
  && ok "psql 에 sslmode=require 를 준다" || bad "psql 에 sslmode=require 가 없다"

echo; echo "======== 결과: 통과 $PASS · 실패 $FAIL ========"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
