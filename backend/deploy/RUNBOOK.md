# 배포와 복구 절차

담당자가 아니어도 이 문서만 보고 배포와 롤백을 할 수 있어야 한다.

## 배포 파일과 설정 경로

API와 Worker를 따로 배포한다. 배포 패키지와 systemd 서비스도 각각 나눈다.

```text
/opt/salmonbus/api/releases/<소스지문>/       배포 버전별 디렉터리. 최근 세 개를 보관한다
/opt/salmonbus/api/current                    현재 실행 중인 배포 버전을 가리키는 심볼릭 링크
/opt/salmonbus/api/previous                   직전 배포 버전
/opt/salmonbus/worker/...                     Worker도 같은 디렉터리 구조를 사용한다

/etc/salmonbus/api.env                        API 환경변수
/etc/salmonbus/worker.env                     Worker 환경변수
/var/lib/salmonbus/model/current/             모델 계수 파일. 수동으로 배치한다
/var/lib/salmonbus/walking/current/           정류장 쌍 도보 거리 파일. 수동으로 배치한다
```

**배포는 `/opt/salmonbus/` 아래만 교체한다.** `/etc/salmonbus/`와 `/var/lib/salmonbus/`는 변경하지 않는다.

## 포트

```text
8080   API 클라이언트.  외부 접근을 허용한다. 배포 검증용 /readyz도 여기 있다
8082   API Actuator.    127.0.0.1에만 바인딩한다
8081   Worker.          127.0.0.1에만 바인딩한다. Actuator도 이 포트를 사용한다
```

**API의 Actuator를 클라이언트 포트와 분리했다.** systemd 유닛의 `MANAGEMENT_SERVER_PORT`와
`MANAGEMENT_SERVER_ADDRESS`로 관리 포트를 설정했으므로 `application.yml`은 수정하지 않았다.

## 최초 1회 수동 설정

```bash
sudo useradd -r -s /sbin/nologin salmonbus
sudo mkdir -p /opt/salmonbus /etc/salmonbus /var/lib/salmonbus/model/current
sudo chown -R salmonbus:salmonbus /var/lib/salmonbus

# preflight.sh가 권한 600과 소유자 root:root를 확인한다. 다르면 배포를 중단한다.
sudo install -m 600 -o root -g root /dev/null /etc/salmonbus/api.env
sudo install -m 600 -o root -g root /dev/null /etc/salmonbus/worker.env
sudo vi /etc/salmonbus/api.env       # DB_URL · DB_USERNAME · DB_PASSWORD
sudo vi /etc/salmonbus/worker.env    # 위 셋 + GBIS_SERVICE_KEY · COLLECTION_ENABLED · FORECAST_ENABLED
```

`FORECAST_STALENESS`는 넣지 않는다. 예보 신선도 창은 api-app이 `/board`·`/watch`에서 약속한 창과 짝이라
worker 쪽만 바꾸면 조용히 어긋난다. 예보를 켠 Worker(`FORECAST_ENABLED=true`)는 이 변수가 있으면 기동에서 멈춘다.

첫 배포에서는 수집과 예보를 비활성화한다.

```text
COLLECTION_ENABLED=false
FORECAST_ENABLED=false
```

**`api.env`에 `GBIS_SERVICE_KEY`를 넣지 않는다.** API는 그 값을 읽지 않는다.
빌드된 JAR에서 `gbis` 문자열은 0개다. 이 값을 넣으면 사용하지 않는 API에도 인증 정보가 저장된다.

`/usr/bin/java`는 21이어야 하고 CodeDeploy agent가 설치돼 있어야 한다.
조건을 충족하지 않으면 `preflight.sh`가 배포를 중단한다.

**배포 과정에서 systemd 유닛을 설치하고 `enable`까지 실행한다.** 별도로 수동 설정할 필요는 없다.
`validate.sh`가 `is-enabled`로 확인하며 재부팅 후 자동 시작하지 않는 상태면 배포가 실패한다.

### 인증 정보 저장 위치 확인

인스턴스 역할에 Parameter Store 읽기 권한이 있으면 그쪽을 쓰는 편이 낫다. 서버에서 한 번만 확인하면 된다.

```bash
aws ssm get-parameter --name /salmonbus/probe --with-decryption --region ap-northeast-2
```

`ParameterNotFound`가 오면 **권한이 있다.** `AccessDeniedException`이 오면 권한이 없으므로
아래 env 파일 방식을 쓴다. 이 문서는 env 파일을 기준으로 설명한다.

### 환경변수 파일을 작성할 때 주의할 점

**systemd와 bash는 환경변수 파일을 읽는 규칙이 다르다.** systemd 252와 bash에서 각각 확인했다.

| 값에 포함된 문자 | **앱에 전달할 때**(systemd `EnvironmentFile=`) | 확인 명령을 실행할 때(`set -a; . 파일`) |
| --- | --- | --- |
| `+` `/` `=` `%` | 그대로 | 그대로 |
| `$` | 그대로. systemd는 변수 치환을 하지 않는다 | **별도 오류 없이 뒤쪽 값이 잘린다** |
| 따옴표 하나 | 그대로 | **파일 읽기가 실패한다** |
| 줄 끝이 CRLF | **CR을 제거한다** | 값 끝에 CR 바이트가 남는다 |
| 끝에 공백 | **제거한다** | 그대로 남는다 |

**앱에 전달하는 값은 systemd가 위 규칙에 따라 처리한다.** 현재 포털에서 제공하는 인증키는 영문과 숫자 64자라
두 방식 모두 값을 변형하지 않는다.

**아래 확인 명령을 사용할 때는 줄 끝 형식에 주의한다.** 파일이 CRLF로 저장돼 있으면
`sha256sum`으로 구한 값이 포털 값과 달라진다. 앱은 정상 작동해도 대조 결과가 달라
키가 잘못됐다고 오인할 수 있다. 이 경우 `sed -i 's/\r$//'`로 줄 끝을 먼저 수정한다.

## 배포하기

브랜치마다 파이프라인과 배포 대상이 따로 있다. 2026-10-13 콘솔 읽기 검증과 2026-10-17 v1.0.0 배포에서 확인했다.

| 브랜치 | 파이프라인 | 배포 대상 | 서버 설정 경로 |
| --- | --- | --- | --- |
| `dev` | `salmonbus-backend-dev-cd` | 공유 dev 서버(`dev.salmonbus.com`) | `/etc/salmonbus-dev/` |
| `main` | `salmonbus-backend-cd` | 운영 EC2(`www.salmonbus.com`) | `/etc/salmonbus/` |

**운영 배포는 `dev` → `main` 릴리즈 PR을 머지하는 것이다.** 기능 PR은 `dev`에 머지하고 dev 서버에서 확인한 뒤 릴리즈 PR로 모아 낸다.
GitHub push 웹훅이 파이프라인을 시작한다. 파이프라인별 웹훅 필터는 권한이 없어 확인하지 못했다.
머지 뒤 파이프라인이 시작되지 않으면 콘솔에서 `변경 사항 릴리스`를 누른다. 아래는 운영 파이프라인 기준이다. **수동 승인 단계는 없다**(2026-09-15 확인).

릴리즈 PR을 머지하기 전에 후보 커밋으로 bootJar를 만들고 `backend/buildspec.yml`의 패키징과 `deploy/verify-revision.sh`를 실제 JAR에 돌려 통과를 확인한다.
이 검사는 운영 빌드에서만 실제 JAR로 돈다. dev 빌드는 이 검사를 거치지 않아, v1.0.0은 운영 빌드에서 이 검사에 처음 걸려 실패했다.

dev 파이프라인은 `dev/server/buildspec.yml`로 빌드하고 `dev/server/deploy`의 훅으로 배포한다. dev 배포는 초기 데이터가 달라지는 변경이면
API의 BeforeInstall에서 `DEV_DATA_CHANGE_REQUIRES_PLAN`으로 멈춘다. 노선 데이터(`dev/data/routes.json`·`catalog-routes.json`)나
`dev/server/compose.yaml`이 바뀐 경우, 그리고 데이터 계약 지문(`dev/data/java`와 `AlightingAwareFeatureVector.java`로 계산한다)이
`dev/server/tools`의 `CompatiblePreparation`에 적어 둔 검토 순서를 따르지 않는 값으로 바뀐 경우다.
그 밖의 변경(마이그레이션, 모델 번들 포함)은 여기서 멈추지 않는다.

```text
Source   GitHub main에서 코드를 가져온다
Build    salmonbus-backend-build가 backend/buildspec.yml을 읽는다
         ./gradlew clean build --no-daemon 으로 전체 테스트를 다시 실행하고
         ApiRevision · WorkerRevision 두 배포 패키지를 생성한다
Deploy   DeployApi(run order 1) → DeployWorker(run order 2). 한 스테이지 안에서 차례로 실행된다
```

**API를 먼저 배포하고 검증한 뒤 Worker를 배포한다.** 두 프로세스를 동시에 재시작하지 않는다.

배포 메타데이터는 Build 로그 끝에 찍힌다. 승인 단계가 없으므로 배포를 멈추는 용도가 아니라 무엇이 나갔는지 확인하는 용도다.

```text
component=api
commit=...
sourceDigest=...        <-- 이 값이 같으면 해당 서비스를 재시작하지 않는다
artifactSha256=...      <-- 무결성 확인용
flywayMaxVersion=12
```

## 변경된 서비스만 재시작

`install.sh`가 새 배포 패키지의 `sourceDigest`와 현재 실행 중인 배포 버전의 값을 대조한다.
다르면 서비스를 중지한 뒤 다시 시작한다. `start.sh`는 재시작 여부와 이유(소스 지문 변경 · 유닛 변경 · 변경 없음),
전후 PID를 훅 로그에 남긴다.

**소스 지문이 같아도 systemd 유닛 파일이 바뀌면 재시작한다.**
`MANAGEMENT_SERVER_PORT` · `Restart=` · `EnvironmentFile=` · 힙 크기는 소스가 아니라 유닛에 있으므로
`sourceDigest`가 같아도 바뀔 수 있다. 새 설정을 적용하려면 서비스를 재시작해야 한다.

```text
소스 지문이 다르다                 중지 후 다시 시작한다
소스 지문이 같고 유닛이 다르다      중지 후 다시 시작한다
둘 다 같다                        실행 중이면 유지하고 중지 상태면 시작한다
```

`sourceDigest`는 JAR이 아니라 **런타임에 사용하는 소스 파일**로 계산한 지문이다.

```text
공통    build.gradle · settings.gradle · gradle.properties · gradle.lockfile
        gradle/wrapper/gradle-wrapper.properties · gradle/libs.versions.toml
        deploy/runtime-source-inputs.sh
api     common · api-app · business/member
worker  common · worker-app · business/route-catalog · business/observations
        business/forecasting · business/api-call-quota · integrations/gbis-client
        libraries/operation-log
모듈마다 src/main · build.gradle · gradle.lockfile 을 입력으로 넣는다.
```

정본 목록은 `deploy/runtime-source-inputs.sh`의 `runtime_source_inputs()`다. 목록에 적혀 있어도 아직 없는 파일은
건너뛰고, 그 파일이 생기는 순간부터 지문에 들어간다. `maintenance-app`, 실행 JAR 시험 모듈 `runtime-test`, 각 모듈의 `src/test`는 어느 지문에도 들어가지 않는다.

**JAR 지문으로는 소스 변경 여부를 판단할 수 없다.** `buildInfo()`가 추가하는 `build.time` 때문에 같은 소스를 다시 빌드해도
JAR의 SHA-256이 달라진다. 실측으로 확인했다. 소스 지문을 쓰면 테스트·문서·프론트만 바뀐 배포에서는
서비스를 재시작하지 않는다. 다만 `build.gradle`은 지문 입력이라 테스트 전용 의존만 바꿔도 지문이 달라진다.
테스트만 손본 변경이라도 `build.gradle`을 건드렸으면 재시작을 전제한다.

## 배포 검증

`validate.sh`가 다섯 가지를 순서대로 확인한다. 하나라도 실패하면 거기서 멈추고 CodeDeploy가 롤백한다.

```text
health        8082 /actuator/health가 UP이다. DB 연결까지 본다
sourcedigest  8082 /actuator/info의 sourcedigest가 방금 놓은 배포 버전과 같다. 옛 프로세스가 살아 있으면 여기서 걸린다
component     8082 /actuator/info의 component가 이 배포 그룹의 것이다
enable        systemctl is-enabled. 재부팅 뒤에도 자동 시작한다
readyz        api만. 8080 /readyz가 200 UP이다. 기동이 끝나 트래픽 받을 상태라는 뜻이고 DB는 보지 않는다
```

**업무 API(`/api/v1/routes`)로 배포를 검증하지 않는다.** 그 응답은 노선 데이터와 모델 상태에 따라 바뀌므로
데이터 사정으로 정상 배포가 롤백될 수 있다. 8080은 `/readyz`로만 본다. `/readyz`는 Spring Boot Actuator의
readiness 그룹을 `management.endpoint.health.probes.add-additional-paths`로 8080에 연 것이다.

시간 상한은 넷이다. 전체 270초, 최초 health 확인 240초, 이후 검사 하나 90초, 요청 하나 5초.
최초 health 확인과 이후 검사는 전체에서 남은 시간을 넘지 않는다. 검사는 3초 간격으로 다시 묻되
남은 시간을 넘기는 요청이나 대기는 하지 않는다. **전체 상한에 잠금 확인 대기(최대 2초)와 health 실패 뒤 DB 진단(15초)을
더해도 appspec의 `ValidateService` timeout(300초)보다 짧아야 한다.** CodeDeploy가 먼저 훅을 종료하면 아래 요약 로그가
남지 않고 배포 잠금도 풀리지 않는다. 리허설이 `validate.sh`·`common.sh`·appspec을 읽어 확인한다.

최초 health 확인은 JVM 기동과 **대기 중인 마이그레이션 전체**를 기다린다. Flyway가 마이그레이션을 실행하는 연결은
API 연결의 `socketTimeout`(60초) 대신 `migration.network-timeout`(150초)까지 응답을 기다린다. 연결의 원래 값이
0(무제한)이거나 150초보다 길면 줄이지 않는다. 마이그레이션 SQL의 `statement_timeout`은 150초보다 짧아야 하며
`MigrationNetworkTimeoutCallbackTest`가 확인한다. `statement_timeout`을 둔 마이그레이션은 서버가 먼저 문장을 취소하고
되돌리지만, 두지 않은 마이그레이션은 150초에 연결만 끊기고 서버 문장은 잠금을 쥔 채 계속 돌 수 있다.

**`network-timeout`은 문장 하나의 상한이라 배포 시간을 보장하지 않는다.** 문장마다 150초 안에 끝나도 기동 시간과
다른 문장을 더하면 최초 health 확인 240초를 넘을 수 있다. 그러면 Flyway는 성공했는데 검증이 실패하고, CodeDeploy가
바뀐 스키마 위에 직전 JAR를 올린다. 직전 JAR는 그 스키마로 기동하지 못할 수 있다(아래 롤백 절의 스키마 설명).
마이그레이션을 배포에 넣기 전에 대기 중인 마이그레이션의 실행 시간(문장마다 `statement_timeout`이 상한이다)과
기동 시간의 합이 240초 안에 들어오는지 계산한다. 넘을 수 있으면 마이그레이션을 여러 배포로 나누거나 appspec timeout과
이 상한을 함께 늘린다. 늘릴 때는 위 합 조건과 `common.sh`의 잠금 회수 기준(900초)도 다시 맞춘다.
저장소에는 마이그레이션만 따로 적용하는 도구가 없다.

훅 로그에는 검사마다 시도 횟수·HTTP 상태·curl 종료 코드·경과 시간이 남고 끝에 한 줄 요약이 남는다.
응답 본문은 기록하지 않는다. 최상위 `status`와 `sourcedigest`·`component` 값만 남긴다.

배포 전후 상태도 같은 로그에 남는다. `preflight.sh`가 배포 전 PID와 지문을 찍고, 앱이 쥔 연결 풀을 거치지 않는
**새 DB 연결**이 붙는지 `psql`로 한 번 확인한다. `start.sh`가 재시작 이유와 전후 PID를 찍고, `validate.sh`가
끝에 PID를 다시 찍는다. health가 실패하면 그 직후 새 DB 연결을 한 번 더 열어 DB가 안 되는 것인지
앱의 풀만 굳은 것인지 갈라 둔다. **DB 진단은 기록만 하고 배포를 막지 않는다.** 비밀번호는 `psql` 환경변수로만
넘기고 명령줄과 로그에 남기지 않는다. 15초 안에 응답이 없으면 끊는다.

```text
[06:11:50] 배포 전: PID=894172 지문=2222bbbb3333
[06:11:50] DB 진단(배포 전): 새 연결 성공. 312ms host=salmonbus-db.cqsc6pyqhwww.ap-northeast-2.rds.amazonaws.com db=salmonbus
[06:12:01] api 를 재시작한다. 이유=소스 지문 변경 2222bbbb3333 -> 3333cccc4444 PID=894172
[06:12:02] salmonbus-api 이 완전히 멈췄다
[06:12:02] salmonbus-api 시작했다. PID=901234
[06:12:03] health 시도 1: http=000 curl=7 경과=0초
[06:12:06] health 시도 2: http=503 curl=0 status=DOWN 경과=3초
[06:12:09] health 확인. 시도=3 소요=6초 http=200
[06:12:09] sourcedigest 확인. 시도=1 소요=0초 http=200
[06:12:09] component 확인. 시도=1 소요=0초 http=200
[06:12:09] enable 확인. 경과=6초
[06:12:09] readyz 확인. 시도=1 소요=0초 http=200
[06:12:09] 배포 검증 끝. component=api commit=... PID=901234 경과=6초 종료 코드=0
```

실패하면 `배포 검증 실패. 단계=... 사유=... PID=... 경과=...초 종료 코드=1` 한 줄로 단계와 사유를 남긴다.
사유에는 시간 상한, 시도 횟수, 마지막 응답의 HTTP 상태 또는 curl 종료 코드가 들어간다.

## 롤백

**롤백은 대부분 자동으로 된다.** `validate.sh`가 실패하면 CodeDeploy가 해당 배포 그룹에서 직전에 성공한 버전을
다시 배포한다. `salmonbus-api-prod`와 `salmonbus-worker-prod`는 별도 배포 그룹이므로
**API 배포가 실패해도 Worker는 변경하지 않는다.** 반대도 같다.

롤백할 버전은 `previous`가 가리키는 경로에 이미 저장돼 있다. `install.sh`는 **해당 버전을 삭제하지 않고
그대로 사용하며 `current`가 가리키는 경로만 변경한다.** 삭제 후 다시 압축을 풀면 롤백할 파일이 없어진다.

훅이 실패로 끝나면 그 훅이 배포 잠금을 푼다. 훅이 강제로 종료돼(CodeDeploy timeout, 재부팅) 잠금이 남으면
같은 서비스의 다음 배포는 그 잠금을 **넘겨받는다.** 그렇지 않으면 자동 롤백이 앞선 배포가 남긴 잠금 때문에 차단된다.
다른 서비스의 잠금이면 마지막 훅이 시작한 뒤 15분이 지났을 때만 죽은 배포로 보고 회수한다. 15분이 안 됐으면
진행 중인 배포로 보고 대기하지 않고 배포를 중단한다. 진행 중인 배포는 훅마다 잠금을 갱신하므로 15분을 넘겨도 뺏기지 않는다.

**Worker를 GBIS 키 별칭 형식 이전 버전으로 되돌릴 때는 롤백하기 전에 `worker.env`를 옛 형식으로 바꾼다.**
이전 버전은 `GBIS_SERVICE_KEY`가 없으면 시작하지 않고, 도착정보 한도가 `ARRIVALS_DAILY_LIMIT` 40,000,
`ARRIVALS_KEY_DAILY_LIMIT` 10,000을 넘어도 시작하지 않는다. 직전에 성공한 배포가 이전 버전이면 자동 롤백도
그 버전의 preflight나 기동에서 멈춘다. 이때는 아래 명령을 돌린 뒤 수동으로 롤백한다.

```bash
sudo sed -i -E '/^GBIS_SERVICE_KEY(_[BCD])?=[[:space:]]*$/d; s/^GBIS_KEYS_A=/GBIS_SERVICE_KEY=/; s/^GBIS_KEYS_([BCD])=/GBIS_SERVICE_KEY_\1=/' /etc/salmonbus/worker.env
grep -cE '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env                             # 1 이어야 한다
grep -E '^ARRIVALS_(DAILY|KEY_DAILY)_LIMIT=' /etc/salmonbus/worker.env              # 40000·10000 이하여야 한다
```

값이 빈 `GBIS_SERVICE_KEY` 계열 줄은 먼저 지운다. 남겨 두면 이름을 바꾼 줄과 겹쳐 뒤에 있는 빈 값이 쓰인다.
a~d가 아닌 별칭의 키(`GBIS_SERVICE_KEY_E`, `GBIS_KEYS_E` 등)와 `GBIS_LIMITS_*`·`ARRIVALS_LIMITS_*` 줄은 이전 버전이
읽지 않는다. 그 키는 쓰지 않고, 남은 키는 위치정보·노선정보는 `GBIS_DAILY_LIMIT`, 도착정보는 `ARRIVALS_KEY_DAILY_LIMIT`로 센다.
바꾼 파일은 지금 버전에서도 그대로 쓸 수 있다.

수동으로 롤백할 때는 대상 서비스를 하나만 선택한다.

```bash
C=api            # 또는 worker
sudo ln -sfn "$(readlink -f /opt/salmonbus/$C/previous)" /opt/salmonbus/$C/current
sudo systemctl restart salmonbus-$C
curl -s http://127.0.0.1:8082/actuator/info     # api. 현재 실행 중인 배포 버전을 확인한다
curl -s http://127.0.0.1:8081/actuator/info     # worker
```

프론트엔드가 `/api/v1/routes/{routeId}/watch`로 전환된 뒤에는 `/watch`가 없는 api 버전으로 되돌리지 않는다. 판정 보드 화면 전체가 오류가 된다.

**스키마는 롤백되지 않는다.** Flyway 마이그레이션은 새 버전을 적용하는 방향으로만 실행한다.
새 배포 버전에서 `V13`을 실행한 뒤 롤백하면 코드는 이전 버전이지만 스키마는 `V13`이다.
두 앱 모두 `ddl-auto: validate`를 사용하므로 **열을 삭제하거나 이름을 바꾼 마이그레이션이면 롤백한 앱도 시작하지 못한다.**

따라서 한 배포에는 **직전 API와 직전 Worker가 그대로 실행될 수 있는 마이그레이션만** 포함한다.
열·테이블·제약을 삭제하거나 이름을 바꾸려면 이전 코드가 해당 항목을 사용하지 않도록 먼저 배포한 뒤
다음 배포에서 처리한다.

## 환경변수 변경

환경변수는 배포와 별개로 언제든 변경할 수 있다. **파일 수정만으로 실행 중인 프로세스에 반영되지는 않는다.**

```bash
C=worker          # 또는 api
sudo vi "/etc/salmonbus/${C}.env"
sudo systemctl restart "salmonbus-${C}"
```

환경변수는 프로세스를 시작할 때 한 번 적용된다. **`api.env`를 수정했으면 `salmonbus-api`를 재시작해야 한다.**
`worker`만 재시작하면 API는 이전 값을 계속 사용한다.

**`salmonbus-worker` 프로세스 두 개를 동시에 실행하면 안 된다.** 일일 API 호출 한도 소모량이 정확히 두 배가 된다.
한도 10,000회 중 두 노선 수집에 8,556회를 사용한다. `start.sh`는 `restart`를 사용하지 않고
`stop` 후 프로세스가 완전히 종료됐는지 확인한 다음 `start`를 실행한다.

## 수집 활성화·비활성화

```bash
sudo sed -i 's/^COLLECTION_ENABLED=.*/COLLECTION_ENABLED=true/' /etc/salmonbus/worker.env
sudo systemctl restart salmonbus-worker
```

**수집을 활성화하면 15초마다 Open API를 실제로 호출한다.** 활성화 후에는 Worker를 KST 01:00~04:00에 배포한다.
다른 시간대는 수집 간격이 15~20초이므로 Worker가 중지된 동안 관측 데이터가 누락된다.

## 모델 계수 파일 교체

```bash
# 검증한 전체 numeric bundle을 별도 디렉터리에 준비한다. manifest의 모든 참조 파일이 필요하다.
sudo -u salmonbus cp -R /path/to/verified-bundle /var/lib/salmonbus/model/new-release
sudo systemctl stop salmonbus-worker
# MODEL_BUNDLE_DIRECTORY를 새 디렉터리로 지정한다. 기존 디렉터리는 보존한다.
sudo sed -i 's|^MODEL_BUNDLE_DIRECTORY=.*|MODEL_BUNDLE_DIRECTORY=/var/lib/salmonbus/model/new-release|' /etc/salmonbus/worker.env
sudo sed -i 's/^MODEL_BUNDLE_PROMOTE_ON_START=.*/MODEL_BUNDLE_PROMOTE_ON_START=true/' /etc/salmonbus/worker.env
sudo systemctl restart salmonbus-worker
# 모델 계수 적용을 확인한 뒤 반드시 다시 비활성화한다.
sudo sed -i 's/^MODEL_BUNDLE_PROMOTE_ON_START=.*/MODEL_BUNDLE_PROMOTE_ON_START=false/' /etc/salmonbus/worker.env
```

**활성화한 채로 두면 배포로 재시작할 때마다 디스크의 모델 계수가 적용된다.**
운영 기본값은 `false`이며 systemd 유닛에도 같은 값이 설정돼 있다.

## 정류장 도보 거리 교체

탑승 후보 조회 API가 읽는 정류장 쌍 도보 거리(`stop_walk_distance`)는 레포에 두지 않는다. 파일 두 개를 Worker가 읽는 디렉터리에 두고 재시작한다.
V49 이후 배포 버전이어야 하고, 테이블이 비어 있어도 API는 같은 정류장을 다시 지나는 후보만 응답한다.

```bash
sudo -u salmonbus mkdir -p /var/lib/salmonbus/walking/current
sudo -u salmonbus cp stop-walk-distances.csv.gz stop-walk-distances.json /var/lib/salmonbus/walking/current/
sudo systemctl restart salmonbus-worker
journalctl -u salmonbus-worker | grep 'event=stop_walk_distances'   # loaded result=LOADED checksum=… pairCount=…
```

- 두 파일의 `sha256`·`pairCount`가 맞지 않거나 형식이 다르면 적재하지 않고 `event=stop_walk_distances_load_failed`를 남긴다. 기존 거리는 그대로다.
- 지금 적재된 파일과 같으면 `result=UNCHANGED`로 쓰지 않는다. 배포로 재시작할 때마다 같은 파일을 다시 쓰지 않는다.
- 되돌리려면 이전 파일 두 개를 같은 디렉터리에 두고 재시작한다.
- 파일이 없으면 `event=stop_walk_distances_missing`을 남기고 건너뛴다. 수집을 끈 Worker는 읽지 않는다.

## 도착 정보·노선 형상 켜기

기본은 꺼져 있다. 켜려면 두 앱에 같은 내부 토큰을 넣는다. **켠 앱에 토큰이 비어 있으면 그 앱이 시작하지 않는다.**

| 파일 | 넣는 줄 |
| --- | --- |
| `api.env` | `ARRIVALS_ENABLED=true` · `ARRIVAL_INTERNAL_TOKEN` |
| `worker.env` | `ARRIVALS_ENABLED=true` · `ARRIVAL_INTERNAL_TOKEN`(api와 같은 값) · `ROUTE_GEOMETRY_ENABLED=true` |

```bash
token="$(openssl rand -hex 32)"
for c in api worker; do
  printf 'ARRIVALS_ENABLED=true\nARRIVAL_INTERNAL_TOKEN=%s\n' "$token" | sudo tee -a "/etc/salmonbus/$c.env" >/dev/null
done
unset token
echo 'ROUTE_GEOMETRY_ENABLED=true' | sudo tee -a /etc/salmonbus/worker.env >/dev/null
for c in api worker; do
  sudo grep '^ARRIVAL_INTERNAL_TOKEN=' "/etc/salmonbus/$c.env" | cut -d= -f2- | tr -d '\n' | sha256sum | cut -c1-8
done
```

마지막 두 줄의 출력이 같아야 한다. 새 배포 버전이 이미 돌고 있으면 worker를 먼저 재시작하고 API를 재시작한다. API는 `127.0.0.1:8081`의 worker에 도착 정보 갱신을 요청한다.
배포 전에 넣으면 배포가 재시작할 때 적용된다. 이전 배포 버전은 이 값을 읽지 않는다.

켜기 전에 확인할 것:

- 도착 정보는 위치정보와 다른 API다. 각 키에 버스도착정보 API(`busarrivalservice`) 활용신청이 있어야 한다. 코드는 키마다 `ARRIVALS_KEY_DAILY_LIMIT`(기본 10,000회, 별칭별로 `ARRIVALS_LIMITS_<별칭>`)까지 쓰고, 전체는 키별 한도의 합이다
- 도착 정보와 형상 조회는 쿼리스트링을 쓴다. CloudFront의 `/api/*` 동작이 쿼리스트링을 원본으로 넘겨야 한다
- 지도는 FE 운영 CodeBuild의 `WEBPACK_NAVER_MAP_KEY_ID`와 NAVER Cloud Web 서비스 URL에 운영 주소가 있어야 표시된다
- 형상은 카탈로그를 갱신한 뒤 노선마다 한 번 수집한다. 그 전에는 형상 조회가 미수집 상태로 응답한다

## 정류장 지도 켜기

정류장 지도(`/api/v2/stations/tiles/14/{x}/{y}`, `/api/v2/stations/{stationId}`)는 V59·V60이 만든 `bus_station`을 읽는다.
기초 명부 설치와 요청한 칸의 갱신은 따로 켠다. **둘 다 기본은 꺼져 있다.** 꺼져 있어도 API는 저장된 목록을 응답한다.

| 파일 | 넣는 줄 |
| --- | --- |
| `worker.env` | `STATION_SEED_ENABLED=true` · `STATION_SEED_PATH`(명부 파일 절대 경로) |
| `worker.env` | `STATION_FILL_ENABLED=true` · `STATION_INTERNAL_TOKEN` |
| `api.env` | `STATION_REFRESH_ENABLED=true` · `STATION_INTERNAL_TOKEN`(worker와 같은 값) · `STATION_WORKER_BASE_URL`(기본 `http://127.0.0.1:8081`) |

명부 파일은 레포의 `dev/data/stations-gbis-20261020.csv.gz`다. 국토교통부 전국 버스정류장 위치정보에서 경기(`GGB`) 행만 추린 UTF-8 gzip이고,
출처·기준일·해시는 옆의 `stations-gbis-20261020.source.json`에 있다. 앱은 파일을 내려받지 않으므로 Worker가 읽는 디렉터리에 직접 둔다.

```bash
sudo -u salmonbus mkdir -p /var/lib/salmonbus/stations
sudo -u salmonbus cp stations-gbis-20261020.csv.gz /var/lib/salmonbus/stations/
sha256sum /var/lib/salmonbus/stations/stations-gbis-20261020.csv.gz   # source.json의 sha256과 같아야 한다
printf 'STATION_SEED_ENABLED=true\nSTATION_SEED_PATH=/var/lib/salmonbus/stations/stations-gbis-20261020.csv.gz\n' \
    | sudo tee -a /etc/salmonbus/worker.env >/dev/null
sudo systemctl restart salmonbus-worker
journalctl -u salmonbus-worker | grep 'event=station_seed_installed'   # dataDate=2026-10-20 stations=… changed=…
```

- 명부는 Worker를 시작할 때 설치한다. 같은 파일이나 더 오래된 기준일의 파일은 다시 설치하지 않으며 `changed=false`로 남는다
- 파일이 없거나 깨졌거나 기준일이 미래이면 설치만 건너뛰고 Worker는 그대로 수집을 이어 간다. 기존 명부는 바뀌지 않고 `event=station_seed_install_failed`가 남는다. 다른 Worker가 설치 중이면 `event=station_seed_skipped`다
- 원본 CP949 파일을 그대로 쓸 때만 `STATION_SEED_ENCODING=MS949`를 넣는다

요청한 칸의 갱신을 켜려면 두 앱에 같은 내부 토큰을 넣는다. **켠 앱에 토큰이 비어 있으면 그 앱이 시작하지 않는다.**

```bash
token="$(openssl rand -hex 32)"
printf 'STATION_FILL_ENABLED=true\nSTATION_INTERNAL_TOKEN=%s\n' "$token" | sudo tee -a /etc/salmonbus/worker.env >/dev/null
printf 'STATION_REFRESH_ENABLED=true\nSTATION_INTERNAL_TOKEN=%s\n' "$token" | sudo tee -a /etc/salmonbus/api.env >/dev/null
unset token
for c in api worker; do
  sudo grep '^STATION_INTERNAL_TOKEN=' "/etc/salmonbus/$c.env" | cut -d= -f2- | tr -d '\n' | sha256sum | cut -c1-8
done
```

두 줄의 출력이 같아야 한다. worker를 먼저 재시작하고 API를 재시작한다. API는 열람한 칸 가운데 갱신이 필요한 칸만
worker의 `POST /internal/stations/refresh`로 넘기고, worker가 GBIS 주변 정류소 조회로 그 칸을 다시 채운다.

- 호출 횟수는 기존 `api-call-quota`의 `daily_call_quota` 장부에 `api_service='BUS_STATION'` 행으로 따로 센다. 위치·노선·도착 정보 한도는 줄지 않는다
- worker는 GBIS 키 별칭 `a` 하나로 정류소 API(`busstationservice`)를 부른다. 그 키에 활용신청이 있어야 한다
- 확인: 지도 칸 하나를 열어 본다. 명부가 설치됐으면 `READY`다

```bash
curl -s http://127.0.0.1:8080/api/v2/stations/tiles/14/13976/6354 | head -c 300
```

끄려면 켤 때 넣은 `*_ENABLED`를 `false`로 바꾸고 재시작한다. 이미 설치한 명부와 수집 결과는 DB에 남고 API는 계속 그 목록을 응답한다.

## 로그인 켜기

아래 명령은 운영 경로(`/etc/salmonbus`) 기준이다. **dev 서버는 `/etc/salmonbus-dev`다.** 서비스 이름(`salmonbus-api`)과 실행 사용자(`salmonbus`)는 같다.
명령 앞에서 `ETC=/etc/salmonbus`(dev는 `ETC=/etc/salmonbus-dev`)를 정하고 시작한다.

기본은 꺼져 있다(`AUTH_ENABLED=false`). 꺼져 있으면 남은 인증 쿠키가 있어도 `/api/auth/*`·`/api/me`가 404이고 공개 조회 API는 로그인과 무관하게 동작한다.
**로그인을 켰는데 필수 값이 비어 있거나 짧으면 API가 시작하지 않는다.** 비밀은 dev와 운영에서 서로 다른 값을 쓴다.

| `$ETC/api.env`에 넣는 줄 | 값 |
| --- | --- |
| `AUTH_ENABLED` | `true` |
| `AUTH_PUBLIC_BASE_URL` | 사용자가 보는 주소. 운영 `https://www.salmonbus.com`, dev `https://dev.salmonbus.com`. 제공자 콘솔에 등록한 콜백 주소(`<주소>/api/auth/{kakao,apple}/callback`)와 글자 하나까지 같아야 한다 |
| `AUTH_TOKEN_SECRET` | 액세스 토큰 서명 비밀. 32바이트 이상. 바꾸면 발급된 액세스 토큰만 무효가 되고 클라이언트가 갱신으로 이어 간다 |
| `AUTH_REFRESH_TOKEN_SECRET` | 리프레시 토큰 서명 비밀. 32바이트 이상. **바꾸면 모든 사용자가 다시 로그인해야 한다** |
| `AUTH_KAKAO_ENABLED` · `AUTH_KAKAO_CLIENT_ID` · `AUTH_KAKAO_CLIENT_SECRET` · `AUTH_KAKAO_ADMIN_KEY` | 카카오 앱의 REST API 키, 클라이언트 시크릿, 어드민 키. 어드민 키는 탈퇴한 회원의 카카오 연결을 끊는 데 쓴다 |
| `AUTH_APPLE_ENABLED` · `AUTH_APPLE_CLIENT_ID` · `AUTH_APPLE_TEAM_ID` · `AUTH_APPLE_KEY_ID` · `AUTH_APPLE_PRIVATE_KEY_PATH` | 애플 Services ID, Team ID, 키 ID, `.p8` 파일 경로. 키 내용은 env에 넣지 않는다 |
| `AUTH_APPLE_APP_ID` · `AUTH_KAKAO_NATIVE_APP_KEY` | 앱 네이티브 로그인 ID 토큰의 대상. iOS 번들 ID(`com.salmonbus.app`)와, 웹 로그인과 **같은 카카오 앱**의 네이티브 앱 키. 비밀이 아니다. 비우면 그 제공자의 앱 로그인만 404이고 웹 로그인은 그대로다. 앱 애플 탈퇴는 `AUTH_APPLE_KEY_ID` 키가 이 번들 ID의 primary App ID 그룹에 묶여 있어야 한다(키 상세의 Sign In with Apple 설정이 `<Team ID>.com.salmonbus.app`) |

두 서명 비밀은 서버에서 만들고 출력하지 않는다.

```bash
for name in AUTH_TOKEN_SECRET AUTH_REFRESH_TOKEN_SECRET; do
  printf '%s=%s\n' "$name" "$(openssl rand -hex 32)" | sudo tee -a "$ETC/api.env" >/dev/null
done
sudo grep -cE '^AUTH_(TOKEN|REFRESH_TOKEN)_SECRET=[0-9a-f]{64}$' "$ETC/api.env"   # 2 이어야 한다
```

애플 `.p8` 파일은 API 프로세스(`salmonbus` 사용자)만 읽게 둔다. 유닛이 `ProtectHome=true`라 홈 디렉터리에 두면 읽지 못한다.

```bash
sudo install -d -m 750 -o root -g salmonbus "$ETC/apple"
sudo install -m 640 -o root -g salmonbus AuthKey_<키ID>.p8 "$ETC/apple/"
echo "AUTH_APPLE_PRIVATE_KEY_PATH=$ETC/apple/AuthKey_<키ID>.p8" | sudo tee -a "$ETC/api.env" >/dev/null
sudo -u salmonbus test -r "$ETC/apple/AuthKey_<키ID>.p8" && echo readable
```

CloudFront에는 로그인 경로 동작을 `/api/*`보다 **앞 순서로** 추가한다. dev와 운영은 CloudFront 배포가 따로라 각각 추가한다. 공개 조회 경로는 지금처럼 쿠키를 받지 않는다.

| 경로 | 설정 |
| --- | --- |
| `/api/auth/*`, `/api/me`, `/api/me/*` | 허용 메서드 전부(GET·HEAD·OPTIONS·PUT·POST·PATCH·DELETE). 캐시 끔. 원본으로 쿠키 `__Host-sb_at`·`__Host-sb_rt`·`__Host-XSRF-TOKEN`·`__Host-sb_session`, 쿼리스트링 전부, 헤더 `Authorization`·`X-XSRF-TOKEN`·`Origin`을 넘긴다 |

- 지금 `/api/*` 동작은 GET·HEAD만 허용해 POST가 `index.html` 200으로 샌다. 위 동작이 없으면 로그아웃·갱신·닉네임 변경·탈퇴가 조용히 실패한다
- 오류 응답 403은 CloudFront가 `index.html`로 바꾸므로 API는 403을 쓰지 않는다. 로그인 경로도 401·400·409로만 거절한다
- `/api/auth/*/start`와 앱 로그인 nonce(`/api/auth/*/native/nonce`)는 익명 요청마다 10분짜리 임시 세션 행을 만든다. WAF에서 이 두 경로와 `/api/auth/refresh`, 앱 로그인(`/api/auth/*/native`)에 IP별 요청 제한을 건다

켠 뒤 확인할 것:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/auth/session                  # 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/me                            # 401
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://127.0.0.1:8080/api/auth/kakao/start  # 302 카카오 주소
```

운영 중 확인할 지표와 로그:

- `auth.refresh.rejected{reason=REUSED}`가 늘면 탈취된 리프레시 토큰이 다시 쓰였을 수 있다. 같은 시각 로그에 "이전 세대 리프레시 토큰이 다시 제시되어" 경고가 남는다
- `auth.notification{provider=APPLE}`은 애플 서버 간 알림(`POST /api/auth/apple/notifications`)이다. 알림 주소는 애플 개발자 계정의 기본 App ID(`com.salmonbus.app`)에 등록한다. `result=rejected`가 알림마다 나오고 로그의 이유가 `reason=claims`면 대개 알림 대상(`aud`)이 `AUTH_APPLE_CLIENT_ID`와도 `AUTH_APPLE_APP_ID`(iOS 번들 ID)와도 다르다. `reason=claims`에는 발급자(`iss`)나 만료(`exp`)가 맞지 않는 경우도 들어 있다. `AUTH_APPLE_APP_ID`를 비워 두면 서버는 대상이 번들 ID인 알림을 모두 거절한다. `result=unavailable`(응답 503)은 애플 공개키를 받지 못한 경우다. 서버는 공개키를 30초에 두 번까지만 받으므로, 처음 보는 키 ID로 서명한 알림이 30초 안에 이어서 오면 횟수를 넘긴 알림은 `result=unavailable`이다. 애플이 쓰는 키 ID로 서명한 알림은 그동안에도 처리된다. 인증 없는 주소라 위조 요청으로도 생기고, 로그는 공개키를 받지 못했을 때와 같은 ERROR 한 줄이다. 컨트롤러에 닿기 전에 막힌 요청(Content-Type이 JSON이 아니면 415, 본문이 JSON이 아니면 400)은 `auth.notification`에 찍히지 않고 `event=api_failure status=415 ... endpoint=/api/auth/apple/notifications` 같은 INFO 로그 한 줄로만 남는다
- `auth.unlink{result=not_linked}`는 카카오가 "이미 연결되지 않은 사용자"라고 답한 경우다. 해제된 것으로 보지만, 이 값이 탈퇴마다 나오면 `AUTH_KAKAO_ADMIN_KEY`가 다른 앱(dev 등)의 키일 수 있다. 해제 결과를 DB에 기록하지 못해 다시 시도한 요청에서도 한 번씩 나오므로, 가끔 나오는 것은 정상이다
- `auth.unlink{result=failed}`는 카카오 연결 해제 실패다. 서버가 5분마다 확인해 1분부터 두 배씩 늘어나는 간격으로 다시 시도하고, 10번 실패하면 멈춘다. 멈춘 요청은 `SELECT id, provider, attempts, last_error, updated_at FROM provider_unlink WHERE status = 'GAVE_UP'`으로 본다
- `auth.withdraw{provider=APPLE,result=revoke_failed}`는 애플 토큰 취소 실패다. 이때는 회원을 지우지 않고 `withdraw=failed`로 돌려보내므로 회원과 로그인은 그대로 남고 사용자가 다시 시도한다. 탈퇴할 때마다 나오면 `AUTH_APPLE_*` 설정과 `.p8` 파일을 확인한다
- `auth.withdraw{provider=APPLE,result=code_rejected}`는 앱 애플 탈퇴에서 애플이 인가 코드를 거절했거나 받은 ID 토큰이 맞지 않은 경우다. 사용자가 다시 인증하면 대개 해결된다. 앱 탈퇴마다 나오면 `AUTH_APPLE_APP_ID`와, 키가 그 번들 ID 그룹에 묶였는지 확인한다. `apple_unavailable`은 애플이 응답하지 않은 경우다
- `GAVE_UP` 행은 서버가 다시 시도하기를 멈춘 카카오 해제 요청이다. 탈퇴한 회원의 제공자 식별자를 담고 있어 30일 뒤 서버가 지운다(매일 04:23). 그 전에 아래처럼 손으로 끊고 지울 수 있다. 어드민 키는 출력하지 않는다. `subject`는 해당 행에서 따로 조회한다

```bash
id=<provider_unlink id>; sub=<그 행의 subject>
sudo grep '^AUTH_KAKAO_ADMIN_KEY=' "$ETC/api.env" | cut -d= -f2- | sed 's/^/Authorization: KakaoAK /' \
  | curl -s -o /dev/null -w '%{http_code}\n' -X POST https://kapi.kakao.com/v1/user/unlink \
      -H @- -d target_id_type=user_id -d "target_id=$sub"   # 키는 표준 입력으로만 넘긴다
# 200 이면 끊겼다. 400 이고 본문 code 가 -101 이면 이미 끊긴 것이다. 둘 다 DB 에서 DELETE FROM provider_unlink WHERE id = <id>; 로 지운다
```

켠 직후에는 테스트 계정으로 카카오 로그인 → 탈퇴(`DELETE /api/me`)를 한 번 해 본다. 다시 로그인할 때 카카오 동의 화면이 다시 뜨면 연결 해제까지 동작한 것이다.

**애플 회원이 있는 동안 `AUTH_APPLE_ENABLED`를 끄지 않는다.** 애플 회원은 애플 재인증으로만 탈퇴할 수 있어 끄면 탈퇴할 길이 없어진다.

**카카오 회원이나 대기 중인 카카오 해제 요청이 있는 동안 `AUTH_KAKAO_ENABLED`를 끄지 않는다.** 끄면 해제 클라이언트가 없어 대기 요청이 매번 실패하고, 약 8시간 반 뒤 포기된 다음 30일 뒤 식별자째 지워진다. 그동안 카카오 쪽 연결은 끊기지 않는다.

로그인을 끄려면 `AUTH_ENABLED=false`로 바꾸고 API를 재시작한다. 끄기는 로그인 공개를 되돌리는 스위치이고 이미 발급된 세션을 끊지 않는다. 테이블과 회원은 그대로 남고, 같은 `AUTH_REFRESH_TOKEN_SECRET`으로 다시 켜면 아직 만료되지 않은 세션이 이어진다. 세션을 끊어야 하면 아래 「모든 세션 끊기(보안 사고)」를 따른다.
끈 동안에는 회원이 탈퇴할 수 없다. 만료된 리프레시 토큰 계통 정리(매시 17분), 연결 해제 재시도(5분마다), 포기한 요청 정리(매일 04:23)도 멈춘다.

### 모든 세션 끊기(보안 사고)

서명 비밀이 샜으면 두 비밀(`AUTH_REFRESH_TOKEN_SECRET`·`AUTH_TOKEN_SECRET`)을 모두 새로 만들고 API를 재시작한다. 모든 사용자가 다시 로그인해야 하고, 진행 중인 애플 탈퇴 티켓도 무효가 된다. 옛 줄을 지운 뒤 위의 비밀 생성 명령을 다시 실행한다.

```bash
sudo sed -i '/^AUTH_\(TOKEN\|REFRESH_TOKEN\)_SECRET=/d' "$ETC/api.env"
# 여기서 위의 비밀 생성 명령을 실행한다. grep 결과가 2 이어야 한다
sudo systemctl restart salmonbus-api
```

비밀은 안전하고 발급된 세션만 끊으려면 DB에서 리프레시 토큰 계통을 지운다. 재시작은 필요 없다. 이미 나간 액세스 토큰은 만료까지 최대 15분 더 쓰인다.

```sql
DELETE FROM refresh_token;  -- 모든 회원
DELETE FROM refresh_token WHERE member_id = (SELECT id FROM member WHERE public_id = '<회원 public_id>');  -- 한 회원만
```

사용자는 스스로 `DELETE /api/me/sessions`로 다른 기기의 세션까지 끊을 수 있다.

## 푸시 알림 켜기

기본은 꺼져 있다(`PUSH_ENABLED=false`). 꺼져 있으면 발송 작업과 APNs 클라이언트를 만들지 않는다.
켰는데 값이 비면 API가 시작하지 않는다. 키 내용은 env에 넣지 않는다. `APNS_TEAM_ID`는 `AUTH_APPLE_TEAM_ID`와 별개다.

| `$ETC/api.env`에 넣는 줄 | 설명 |
| --- | --- |
| `PUSH_ENABLED` | 켜려면 `true` |
| `APNS_TEAM_ID` | APNs Team ID. `AUTH_APPLE_TEAM_ID`와 별개다 |
| `APNS_KEY_ID` | APNs 키 ID |
| `APNS_PRIVATE_KEY_PATH` | `.p8` 파일 경로. 키 내용은 env에 넣지 않는다 |
| `APNS_TOPIC` | 앱 번들 ID |

V75는 기존 `push_device` 행을 지운다. 발송 기록은 기기 외래 키를 따라 함께 지워지고, 알림 설정은 남는다. 알림이 들어 있는 앱 빌드보다 서버를 먼저 배포한다. 배포 뒤에 앱이 기기 토큰을 다시 등록해야 발송된다. `sid`가 없는 예전 액세스 토큰은 등록에서 401이 나고, 웹은 갱신한 뒤 같은 요청을 다시 보낸다.

## 운영 원본 이관

`archive-app`은 수집·예보와 별도 프로세스다. 닫힌 날짜의 예보 기록을 S3로 내보내고, SAL-434가 지울 옛 테이블을 통째로 내보낸다.

`deploy/package-archive-revision.sh`가 별도 JAR·unit과 소스/아티팩트 지문을 만든다.
운영 환경은 `/etc/salmonbus/archive.env`에 설정한다.
`archive.env.example`은 값 목록이며 실사용 비밀 파일이 아니다.
프로세스는 `/opt/salmonbus/archive/current/jars/archive-app.jar`에서 실행한다.
unit의 256MiB heap/384MiB MemoryMax/CPUQuota=50%는 초기 제한값으로 서버 여유에 맞게 확인한다.
DB 연결은 최대 1개이며 nice만으로 자원 격리가 된다고 보지 않는다.
웹 서버를 띄우지 않으므로 지표의 외부 수집은 실행 환경에서 따로 연결해야 하며 구조화 로그는 journal과 Loki에서 읽는다.

이전 이관이 S3에 올려 둔 기존 객체는 지우지 않는다. 지울 일이 생기면 인계 검증을 마친 뒤 정확한 객체 목록으로 따로 한다.

### 옛 이관 기록 테이블의 FK 정리

`historical_import_record`, `migration_source_record`는 SAL-122 이관 도구가 만든 장부이고 Flyway 마이그레이션에는 없다. 두 테이블의 `observation_batch` 참조 FK가 노선 버전 1·2의 08-14~09-02 수집 배치 삭제를 막는다. 별도 승인을 받은 뒤 아래 순서로 FK 제약만 지운다. 테이블과 행은 남긴다.

1. 두 테이블을 행 단위 JSON으로 내보낸다. 예: `\copy (SELECT to_jsonb(t)::text FROM historical_import_record t ORDER BY 1) TO 'historical_import_record.jsonl'`. 줄 수가 `count(*)`와 같은지 확인한다.
2. gzip으로 묶어 SHA-256을 기록하고 `ARCHIVE_LEGACY_EXPORT_PREFIX` 아래 `manual/historical-import-records/`에 올린다. 다시 내려받아 해시와 줄 수가 같은지 확인한다.
3. Worker와 이관 앱을 멈추고 `deploy/sql/drop-historical-import-batch-fks.sql`을 실행한다. 마지막 조회에 두 테이블의 제약이 없어야 한다.
4. Worker와 이관 앱을 다시 시작한다. 11-13에 운영에 직접 만든 `ix_archive_historical_import_target_batch_fk`는 이 뒤로 쓰이지 않지만 지우는 것은 따로 정한다.

### 날짜 단위 내보내기

예보 기록 테이블 다섯 개(`forecast_run`, `forecast_record`, `stop_pass`, `pass_label`, `forecast_unresolved`)는 `source_date`(한국 날짜)로 나뉜다. 이관 앱은 닫힌 날짜의 파티션(`<table>_pYYYYMMDD`)을 통째로 S3에 내보내고, 다시 읽어 확인한 뒤, 설정이 허락하면 떼어 지운다.

스위치(`/etc/salmonbus/archive.env`, 바꾸면 이관 서비스만 재시작한다):

| 키 | 기본값 | 뜻 |
|---|---|---|
| `ARCHIVE_PARTITION_EXPORT_ENABLED` | `false` | 켜야 빈이 생긴다. 꺼져 있으면 카탈로그도 읽지 않는다 |
| `ARCHIVE_PARTITION_EXPORT_DROP_ENABLED` | `false` | 확인을 마친 파티션을 떼어 지운다 |
| `ARCHIVE_PARTITION_EXPORT_RETENTION_DAYS` | `3` | 오늘 포함 DB에 남기는 날 수(2~30). `source_date <= 오늘 - 3`이 대상이다 |
| `ARCHIVE_PARTITION_EXPORT_PREFIX` | `salmonbus/prod/lake-v3/` | S3 키 접두. 영문·숫자·`/`·`_`·`=`·`-`만 쓰고 `/`로 끝낸다. `/`나 `salmonbus-be/`로 시작하면 기동하지 않는다 |
| `ARCHIVE_PARTITION_EXPORT_INTERVAL` | `1h` | 회차 사이 쉬는 시간(1분~24시간). 기동 1분 뒤 첫 회차 |
| `ARCHIVE_PARTITION_EXPORT_PAGE_SIZE` | `2000` | 한 번에 읽는 줄 수(1~10000) |
| `ARCHIVE_PARTITION_EXPORT_LOCK_TIMEOUT` | `2s` | 떼어 지울 때 잠금을 기다리는 시간(100ms~10초) |

값이 범위를 벗어나면 기동하지 않는다.

켜는 순서:

1. `ARCHIVE_PARTITION_EXPORT_ENABLED=true`, `DROP_ENABLED=false`로 내보내기만 한다. `status=EXPORTED`·`status=VERIFIED` 로그와 `daily_export`의 `verified_at`을 본다.
2. 하루치를 아래 "되살리는 방법"으로 S3 파일만 써서 되살려 원본 파티션과 줄 단위로 같은지 확인한다.
3. `DROP_ENABLED=true`로 바꾸고 재시작한다. `status=DROPPED`와 `daily_export.dropped_at`을 본다.

S3 구조:

```
<prefix><table>/source_date=YYYY-MM-DD/NNNNNN-<gz sha256>.jsonl.gz
<prefix><table>/source_date=YYYY-MM-DD/manifest-<manifest sha256>.json
```

- 한 줄은 `to_jsonb(row)::text` 원문, 줄 끝 `\n`, gzip이다. 파일 하나는 평문 8MiB 안팎에서 나눈다. 파일 번호 순서로 이어 붙이면 기본 키 순서의 전체 줄이다.
- manifest(JSON)는 `format`(`salmonbus-partition-v1`), `table`, `sourceDate`, `partition`, `rows`, `orderBy`(기본 키), `columns`(이름과 `format_type` 표기, 부모 테이블 열 순서), `exportedAt`, `files`(`number`, `key`, `sha256`, `rows`, `bytes`)를 담는다.
- 올릴 때는 조건부 PUT(`If-None-Match: *`)·SHA-256 체크섬·다시 읽어 바이트 비교를 쓴다. 파일 이름이 내용 해시라 같은 내용을 다시 올려도 겹치지 않는다.
- 장부 `daily_export`는 (테이블, 날짜)마다 한 줄이고 `object_key`·`sha256`은 manifest의 키와 해시다. manifest와 모든 파일을 다시 읽어 해시·줄 수를 확인한 뒤에만 적는다.
- 값: float8은 `extra_float_digits=1`로 왕복이 보장되는 최단 표기로 나온다. JSON 숫자로 바뀌며 지수 없이 풀어 쓴다. `NaN`·`Infinity`는 문자열, `-0`은 `0`이 된다(값 비교로는 같다). `timestamptz`는 UTC 오프셋이 붙은 마이크로초 표기, `date`는 `YYYY-MM-DD`, `bigint`는 정수 그대로다.

지우기 전 확인(하나라도 어긋나면 지우지 않는다):

1. `DROP_ENABLED=true`다.
2. 장부에 그 (테이블, 날짜) 줄이 있고 `verified_at`이 있으며 `dropped_at`이 없다.
3. 지금 S3에서 manifest를 다시 읽어 SHA-256이 장부와 같고, 모든 파일의 해시·줄 수가 manifest와 같고, 줄 수 합이 장부와 같다.
4. 잠금 없이 센 파티션 줄 수가 장부와 같다.
5. 지우는 트랜잭션에서 advisory lock(`pg_try_advisory_xact_lock(20261115, hashtext(파티션 이름))`)을 잡고, 장부 줄을 다시 잠가 보고, 파티션에 `SHARE` 잠금을 잡아 새 줄을 막은 뒤 다시 센 줄 수가 장부와 같다.

그다음 같은 트랜잭션에서 `ALTER TABLE <table> DETACH PARTITION <partition>`, `DROP TABLE <partition>`, `dropped_at`을 적는다. `DETACH`는 부모 테이블과 기본 파티션에 `ACCESS EXCLUSIVE` 잠금을 잡으므로 커밋까지 그 테이블의 읽기·쓰기를 잠깐 막는다. 기본 파티션이 있어 `DETACH CONCURRENTLY`는 쓸 수 없다. 잠금을 `LOCK_TIMEOUT` 안에 못 얻으면 되돌리고 다음 회차에 다시 한다.

되살리는 방법(DB 정의나 장부 없이 S3만으로):

1. `daily_export`나 S3 목록에서 manifest를 고르고 내려받아 SHA-256이 파일 이름의 해시와 같은지 본다.
2. manifest의 `columns`로 빈 테이블을 만든다. 예: `CREATE TABLE restored_forecast_record (source_date date, vehicle_observation_id bigint, ... , expected_seats double precision[], ...)`.
3. 파일마다 내려받아 해시를 확인하고 압축을 푼 줄을 넣는다. 예: `INSERT INTO restored_forecast_record SELECT * FROM jsonb_populate_recordset(NULL::restored_forecast_record, '[줄1,줄2,...]'::jsonb)`. 줄이 많으면 텍스트 열 하나인 `staging`에 `\copy staging(line) FROM 'file.jsonl' WITH (FORMAT csv, QUOTE e'\x01', DELIMITER e'\x02')`로 넣은 뒤(기본 텍스트 형식은 JSON의 역슬래시를 바꾼다) `jsonb_populate_record(NULL::restored_..., line::jsonb)`로 넣는다.
4. 넣은 줄 수가 manifest `rows`와 같은지, 원본이 남아 있으면 `EXCEPT ALL`로 양쪽 차이가 0인지 본다.

이 절차는 테스트 `PartitionRestore`(archive-app 테스트 소스)와 같다. 운영용 정비 명령은 아직 없다.

실패했을 때 보는 것:

- 로그는 `event=partition_export`다. `status=RUN`(회차 요약, `oldestPendingDate`), `EXPORTED`, `VERIFIED`, `DROPPED`, `DEFERRED reason=LOCK_TIMEOUT|BUSY`, `DROP_REFUSED reason=ROW_COUNT_CHANGED`, `FAILED reason=<사유>`, `DEFAULT_ROWS`, `SKIPPED reason=UNEXPECTED_BOUND|ALREADY_DROPPED`.
- 지표는 `salmonbus_archive_partitions{stage=exported|dropped}`, `salmonbus_archive_rows{stage=partition_exported|partition_dropped}`, `salmonbus_archive_bytes{stage=partition_exported}`, `salmonbus_archive_stage{stage=partition_export|partition_verify|partition_drop}`, `salmonbus_archive_partition_failures{table,reason}`, `salmonbus_archive_partition_deferrals{table,reason}`, `salmonbus_archive_partition_default_rows{table}`, `salmonbus_archive_partition_oldest_pending_days`다. 외부 수집은 따로 연결해야 한다.
- `ROW_COUNT_CHANGED`: 내보낸 뒤 그 날짜에 줄이 더 들어왔다. 자동으로 다시 내보내지 않는다. 원인을 확인한 뒤 그 (테이블, 날짜)의 `daily_export` 줄을 지우면 다음 회차에 새 manifest로 다시 내보낸다. 이미 올라간 S3 파일은 지우지 않는다.
- `MANIFEST_HASH_MISMATCH`, `FILE_HASH_MISMATCH`, `FILE_ROW_COUNT_MISMATCH`, `MANIFEST_ROW_COUNT_MISMATCH`, `MANIFEST_MISMATCH`: S3 객체가 장부와 다르다. 지우지 않는다. 객체 버전과 버킷 수명 주기 설정을 확인한다.
- `EXPORT_CONTENT_MISMATCH`: 다른 실행이 같은 (테이블, 날짜)에 내용이 다른 manifest를 먼저 장부에 적었다. 지우지 않는다. 두 manifest를 비교한다.
- `LEDGER_DROPPED_PARTITION_PRESENT`: 장부는 지웠다고 하는데 파티션이 있다. 손으로 다시 만든 파티션인지 확인한다.
- `salmonbus_archive_partition_default_rows`가 0보다 크면 날짜 파티션이 없을 때 들어온 줄이다. 기본 파티션의 줄은 내보내지 않는다. worker의 파티션 유지 작업 실패 로그(`event=pipeline_partition_create_failed`)를 본다.
- `salmonbus_archive_partition_oldest_pending_days`가 보관 날짜 수보다 계속 크면 대상 파티션이 쌓이고 있다.

### 옛 테이블 내보내기

SAL-434가 지울 옛 테이블 25개(목록은 `deploy/sql/sal434-03-drop-legacy-tables.sql`의 `target_tables`가 정본이고, 이관 앱의 `LegacyExportRepository.TABLES`가 같은 목록인지 시험이 대조한다)를 통째로 S3에 내보낸다. 테이블마다 기본 키 순서로 읽어 조각으로 나누고, 조각마다 올리고 다시 읽어 확인한 뒤 장부 `daily_export`에 한 줄을 적는다. **이 기능은 줄을 지우지 않는다.** 옛 테이블을 지우는 것은 사람이 승인하고 실행하는 `sal434-03`뿐이다. 운영 순서는 「SAL-434 전환 절차」의 6-1번이다.

스위치(`/etc/salmonbus/archive.env`, 바꾸면 이관 서비스만 재시작한다):

| 키 | 기본값 | 뜻 |
|---|---|---|
| `ARCHIVE_LEGACY_EXPORT_ENABLED` | `false` | 켜야 빈이 생긴다. 꺼져 있으면 DB를 보지 않는다 |
| `ARCHIVE_LEGACY_EXPORT_PREFIX` | `salmonbus/prod/lake-v3-legacy/` | `ARCHIVE_PARTITION_EXPORT_PREFIX`와 서로 포함하면 기동하지 않는다 |
| `ARCHIVE_LEGACY_EXPORT_INTERVAL` | `1m` | 회차가 끝난 뒤 다음 회차까지 쉬는 시간(10초~24시간). 기동 1분 뒤 첫 회차 |
| `ARCHIVE_LEGACY_EXPORT_PAGE_SIZE` | `2000` | 한 번에 읽는 줄 수(1~10000) |
| `ARCHIVE_LEGACY_EXPORT_PAGE_PAUSE` | `200ms` | 페이지 사이 쉬는 시간(0~60초). 쉬는 동안 DB 연결을 쥐지 않는다 |
| `ARCHIVE_LEGACY_EXPORT_SEGMENT_SIZE` | `64MB` | 조각 하나의 평문 크기 목표(64KB~1GB). 조각 안의 파일은 평문 8MiB 안팎으로 나뉜다 |
| `ARCHIVE_LEGACY_EXPORT_MAX_ROWS_PER_RUN` | `2000000` | 한 회차에 읽는 최대 줄 수 |
| `ARCHIVE_LEGACY_EXPORT_MAX_RUN_DURATION` | `10m` | 한 회차의 최대 시간(1초~6시간). 페이지·조각 경계에서 확인하므로 문장 하나만큼 넘을 수 있다 |
| `ARCHIVE_LEGACY_EXPORT_STATEMENT_TIMEOUT` | `60s` | 페이지 읽기·범위 세기 문장 하나의 제한 시간(1초~10분) |

읽는 방식과 속도 조절:

- 테이블을 목록 순서로 하나씩 처리한다. 페이지는 `SELECT to_jsonb(t)::text, jsonb_build_array(<기본 키>)::text FROM <table> t WHERE (<기본 키>) > (<직전 페이지 마지막 키>) ORDER BY <기본 키> LIMIT <PAGE_SIZE>`다. 기본 키 인덱스를 행 비교로 따라가므로 테이블 앞부분을 다시 읽지 않는다. 페이지마다 읽기 전용 트랜잭션 하나로 읽고 끝내므로 연결은 그 페이지를 읽는 동안에만 쥔다.
- 세션 설정은 날짜 단위 내보내기와 같다(`extra_float_digits=1`, 시간대 UTC, 병렬 읽기 끔, 문장 제한 시간). 여기에 `bytea_output='hex'`를 더한다.
- 한 회차는 `MAX_ROWS_PER_RUN`이나 `MAX_RUN_DURATION`에 먼저 닿는 곳에서 멈추고, 그때까지 읽은 줄을 조각 하나로 닫아 장부에 적는다. 다음 회차는 장부의 마지막 조각 `key_to` 다음 키부터 읽는다. 기동할 때도 장부에서 이어 간다.
- 이관 앱의 예약 작업은 스레드 하나와 DB 연결 1개를 나눠 쓴다. 옛 테이블 내보내기 한 회차가 도는 동안(`MAX_RUN_DURATION`까지) 날짜 단위 내보내기는 기다린다.
- 걸리는 시간은 운영에서 재야 한다. 읽는 속도의 상한은 대략 `PAGE_SIZE / (페이지 읽는 시간 + PAGE_PAUSE)`이고, 회차 사이에 `INTERVAL`만큼 쉰다. 페이지 하나에 0.3초가 걸린다면 기본값으로 초당 4000줄 안팎이다. 옛 테이블은 수억 줄이고 대부분이 `seat_forecast`와 `forecast_evaluation_result`라서 하루 가까이 걸린다(추정). 끝난 뒤 범위 다시 세기가 같은 테이블을 한 번 더 읽는다(인덱스만 읽는 계획이 되면 덜 읽는다. 추정).
- DB가 느려지면 `PAGE_PAUSE`를 늘리거나 `PAGE_SIZE`·`MAX_ROWS_PER_RUN`을 줄이거나 `INTERVAL`을 늘리고 이관 앱을 재시작한다. 멈추려면 스위치를 끄고 재시작한다. 다시 켜면 장부에서 이어 간다. RDS 처리량 크레딧과 `salmonbus_archive_legacy_rows_per_second`를 함께 본다.

S3 구조와 장부:

```
<prefix><table>/segment=NNNNNN/NNNNNN-<gz sha256>.jsonl.gz
<prefix><table>/segment=NNNNNN/manifest-<manifest sha256>.json
```

- 파일 한 줄은 `to_jsonb(row)::text` 원문, 줄 끝 `\n`, gzip이다(날짜 단위 내보내기와 같은 형식). 값이 JSON `null`인(SQL NULL이 아닌) `jsonb` 열이 있는 줄에만 `"$jsonb_null": ["열 이름", ...]` 키가 더 붙는다. `to_jsonb`는 두 값을 똑같이 `null`로 쓰기 때문이다.
- manifest(JSON)는 `format`(`salmonbus-legacy-segment-v1`), `table`, `segment`, `after`(앞 조각의 `keyTo`, 첫 조각은 `null`), `keyFrom`, `keyTo`, `rows`, `orderBy`(기본 키), `columns`, `exportedAt`, `files`를 담는다. 조각 n은 기본 키가 `after` 초과 `keyTo` 이하인 줄이다. 키는 `jsonb_build_array(<기본 키>)` 텍스트다(예: `[123, 4]`).
- 장부 `daily_export`: 옛 테이블 조각은 `source_date = 2000-01-01`, `segment`(0부터), `key_from`·`key_to`, `row_count`, `object_key`·`sha256`(manifest의 키와 해시), `verified_at`을 적는다. manifest와 모든 파일을 다시 읽어 해시·줄 수를 확인한 뒤에만 적는다. 적을 때 테이블마다 advisory lock(`pg_advisory_xact_lock(20261116, hashtext(테이블 이름))`)을 잡고, 장부의 마지막 조각 `key_to`가 이 조각의 시작과 같고 번호가 그다음일 때만 적는다. 날짜 단위 내보내기는 같은 테이블에 `segment = 0` 한 줄을 날짜마다 쓴다.
- 장부에 적지 못한 조각(업로드 도중 끊김 등)은 다음 회차가 같은 키부터 다시 만든다. 이미 올라간 파일은 지우지 않는다.
- 값: 날짜 단위 내보내기의 값 설명과 같다. 옛 테이블 열 타입(`bigint`, `integer`, `boolean`, `varchar`, `uuid`, `date`, `timestamptz`, `double precision`, `jsonb`, `bytea`, `xid8`, `text`)과 `smallint`, `real`, `numeric`, `char`, `timestamp`, 배열(다차원 포함, `jsonb[]` 제외)이 JSON을 거쳐 같은 값·같은 텍스트로 돌아오는 것을 시험으로 고정했다. 이 밖의 타입(`interval`, `json`, `jsonb[]` 등) 열이 있으면 그 테이블은 `UNSUPPORTED_COLUMN_TYPE`로 실패하고 내보내지 않는다. 기본 키가 없는 테이블은 `PRIMARY_KEY_REQUIRED`로 실패한다(마이그레이션 기준 25개 모두 기본 키가 있다). 배열의 하한이 1이 아니면(`'[0:1]={1,2}'`) 하한이 사라진다.

끝났다는 판정:

1. 마지막 조각 `key_to` 다음 키로 읽은 페이지가 비었다.
2. 조각마다 그 범위(앞 조각 `key_to` 초과, 자기 `key_to` 이하)를 다시 센 줄 수가 장부의 `row_count`와 같다. 범위가 겹치지 않고 키 공간을 빈틈없이 덮으므로, 이것은 장부 합이 그 순간의 줄 수와 같다는 뜻이다.

둘 다 맞으면 `status=COMPLETE`와 `salmonbus_archive_legacy_complete{table}=1`이다. 다시 세기는 프로세스 안에서 한 번만 하고(기동하면 다시 한다), 회차 상한에 닿으면 다음 회차가 이어 센다. 끝난 테이블도 회차마다 마지막 키 뒤를 한 페이지 읽어 새 줄이 있으면 이어서 내보낸다.

다시 센 값이 장부와 다르면 `status=AUDIT_MISMATCH`(조각 번호, `kind`, `ledgerRows`, `currentRows`, 범위)와 `status=MISMATCH`가 남고 `salmonbus_archive_legacy_mismatched_segments{table}`가 0보다 크다. `kind=ROWS_REMOVED`는 이미 내보낸 범위에서 줄이 지워진 것이고(S3에는 지워진 줄도 있다), `kind=ROWS_ADDED_BEHIND_CURSOR`는 내보낸 범위 안에 새 줄이 들어온 것이다(그 줄은 S3에 없다). 내보내기는 장부를 고치지 않는다. 이대로면 `sal434-03`이 장부 합과 줄 수가 달라 멈춘다. 원인(멈추지 않은 옛 경로, 수동 조작)을 먼저 없앤 뒤, 승인을 받아 그 테이블의 어긋난 조각부터 끝까지의 장부 줄을 지우고(`DELETE FROM daily_export WHERE table_name = '<table>' AND source_date = '2000-01-01' AND segment >= <n>`, S3 파일은 남긴다) 이관 앱을 재시작하면 그 조각의 시작 키부터 다시 내보낸다. 같은 키에서 값만 바뀐 줄은 줄 수로 알아보지 못한다. 그래서 내보내기 전에 옛 경로가 쓰지 않는지 먼저 확인한다.

되살리는 방법은 날짜 단위 내보내기와 같고 두 가지를 더한다. 조각 번호 순서로 manifest를 읽으며 `segment`가 0부터 이어지고 `after`가 앞 조각 `keyTo`와 같은지 본다. `jsonb` 열은 줄의 `$jsonb_null` 배열에 이름이 있으면 JSON `null`로 넣는다(예: `CASE WHEN jsonb_exists(r->'$jsonb_null', 'feature_snapshot') THEN 'null'::jsonb ELSE p.feature_snapshot END`). 시험 `PartitionRestore.restoreLegacy`가 이 절차다.

실패했을 때 보는 것:

- 로그는 `event=legacy_export`다. `status=RUN`(회차 요약: `rows`, `segments`, `completeTables`, `stoppedBy=NONE|ROW_LIMIT|TIME_LIMIT|INTERRUPTED`, `rowsPerSecond`), `SEGMENT`(테이블, 조각 번호, 줄 수, `keyFrom`·`keyTo`, 바이트, 장부 누계), `COMPLETE`, `AUDIT_MISMATCH`, `MISMATCH`, `FAILED reason=<사유>`, `UNSUPPORTED_COLUMNS`, `ABSENT`(테이블이 이미 없다).
- 지표는 `salmonbus_archive_legacy_exported_rows{table}`(장부 줄 수 합), `salmonbus_archive_legacy_segments{table}`, `salmonbus_archive_legacy_complete{table}`, `salmonbus_archive_legacy_mismatched_segments{table}`, `salmonbus_archive_legacy_complete_tables`, `salmonbus_archive_legacy_rows_per_second`(직전 회차), `salmonbus_archive_legacy_failures{table,reason}`, `salmonbus_archive_rows{stage=legacy_exported,table}`, `salmonbus_archive_bytes{stage=legacy_exported}`, `salmonbus_archive_stage{stage=legacy_page|legacy_segment|legacy_audit}`다. 마지막 키는 지표 라벨에 넣지 않고 `SEGMENT` 로그에 남긴다.
- `LEDGER_CHANGED`: 장부의 마지막 조각이 기대와 다르다(다른 실행이 먼저 적었거나 사람이 장부를 고쳤다). 다음 회차가 장부를 다시 읽어 이어 간다. `LEDGER_GAP`·`LEDGER_UNVERIFIED`: 장부 조각 번호에 빈틈이 있거나 확인 시각·키가 빈 줄이 있다. 사람이 장부를 확인한다.
- `MANIFEST_HASH_MISMATCH`, `FILE_HASH_MISMATCH`, `FILE_ROW_COUNT_MISMATCH`, `MANIFEST_ROW_COUNT_MISMATCH`, `MANIFEST_MISMATCH`: 올린 직후 다시 읽은 S3 객체가 다르다. 장부에 적지 않았다.

## 인증 정보를 출력하지 않고 확인하기

```bash
stat -c '%a' /etc/salmonbus/worker.env                          # 600 이어야 한다
grep -c '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env          # 1 이어야 한다
grep -cE '^(GBIS_SERVICE_KEY|GBIS_KEYS_A)=' /etc/salmonbus/worker.env   # 별칭 a를 GBIS_KEYS_A로 옮겼다면 이 줄로 센다. 1 이어야 한다
grep '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env | cut -d= -f2- | tr -d '\n' | sha256sum | cut -c1-8
```

마지막 명령의 출력에서 앞 8자를 포털 화면의 값과 대조한다. `grep -c`의 `-c`를 빼면 값이 그대로 출력된다.

**앱이 시작됐다는 사실만으로 인증키가 올바르다고 판단할 수 없다.** 잘못된 키로도 4초 만에 시작하고 health는 200을 반환한다.
노선 버전이 없는 DB에서는 수집이 Open API를 호출하지 않으므로 오류도 발생하지 않는다.

## GBIS 키 여러 개 쓰기

Worker는 버스 위치정보 API와 도착정보 API를 설정한 키 전부에 나눠 호출한다. 호출마다 다음 키로 넘어가고, 그날 한도가 찬 키는 건너뛴다.
노선정보 API는 별칭 a의 키만 쓴다. 키 개수에는 상한이 없고, 바꾼 설정은 Worker를 다시 시작하면 반영된다.

키마다 **별칭**을 하나 붙인다. 하루 호출 장부(`daily_call_quota.key_alias`)는 별칭으로 센다.
같은 별칭이면 키 값을 바꿔도 그날 쓴 횟수와 제외 상태를 이어 받는다. 순서를 바꿔도 장부는 그대로다.

| 별칭 | `worker.env` 변수 |
| --- | --- |
| a | `GBIS_SERVICE_KEY`. 반드시 있어야 한다 |
| 그 밖 | `GBIS_SERVICE_KEY_<별칭>`. 예: `GBIS_SERVICE_KEY_B`, `GBIS_SERVICE_KEY_E`, `GBIS_SERVICE_KEY_TEAM5` |

`GBIS_KEYS_<별칭>`(a는 `GBIS_KEYS_A`)도 읽지만, 이전 버전으로 롤백할 때 다시 바꿔야 하니 위 형식을 쓴다.

- 별칭은 영문 소문자와 숫자 1~16자다. 환경변수 이름은 대문자로 쓰고, 별칭 부분은 소문자로 읽는다(`GBIS_SERVICE_KEY_TEAM5` → `team5`). 밑줄과 하이픈은 쓸 수 없다.
- 같은 별칭을 두 방식으로 함께 적으면(`GBIS_SERVICE_KEY_E`와 `GBIS_KEYS_E`) Worker가 시작하지 않는다. 한쪽만 남긴다.
- 같은 키를 두 별칭에 넣으면 Worker가 시작하지 않는다. 한도를 두 번 세기 때문이다. 퍼센트 인코딩한 값과 원래 값도 같은 키로 본다.
- `GBIS_SERVICE_KEY_E=`처럼 값이 빈 줄은 없는 것으로 본다. `GBIS_KEYS_<별칭>=`처럼 값이 빈 줄은 오류다. 쓰지 않을 키는 줄을 지운다.
- `GBIS_KEYS=` 한 줄에 여러 키를 적는 형식은 쓰지 않는다.
- 별칭 형식 이전 버전은 a~d만 읽는다. 이전 버전으로 되돌릴 때는 "롤백"을 본다.

**하루 한도는 키마다 포털에서 받은 값으로 센다.** 따로 적지 않은 키는 공통 값을 쓴다.

| API | 공통 값 | 키 하나만 다르게 |
| --- | --- | --- |
| 위치정보·노선정보 | `gbis.daily-limit`(기본 10,000회, `GBIS_DAILY_LIMIT`) | `GBIS_LIMITS_<별칭>=1000` |
| 도착정보 | `arrivals.key-daily-limit`(기본 10,000회, `ARRIVALS_KEY_DAILY_LIMIT`) | `ARRIVALS_LIMITS_<별칭>=1000` |

- 새로 받은 키처럼 한도가 다른 키는 그 키의 한도 줄을 함께 넣는다. 포털 한도보다 크게 넣지 않는다.
- 설정에 없는 별칭에 한도를 주거나 1보다 작게 주면 Worker가 시작하지 않는다.
- 도착정보 전체 예산과 초당 호출 수는 키별 한도의 합에서 정한다. 키를 늘리면 초당 호출 수는 재시작하자마자, 전체 예산은 다음 날(한국 시각)부터 늘어난다. 그날 전체 예산은 그날 첫 장부 행의 값으로 끝까지 센다. `ARRIVALS_DAILY_LIMIT`는 더 읽지 않는다. `ARRIVALS_KEY_DAILY_LIMIT`를 10,000보다 높이면 이전 버전으로 롤백하기 전에 다시 낮춰야 한다.
- 한도를 바꿔도 그날 이미 만든 장부 행은 처음 정한 한도로 끝까지 센다. 바꾼 한도는 다음 날(한국 시각) 행부터 적용된다. 도착정보는 낮춘 한도만 그날 바로 적용된다.

### 키 추가

```bash
sudo vi /etc/salmonbus/worker.env    # GBIS_SERVICE_KEY_H= 줄을 추가한다. 한도가 다른 키면 GBIS_LIMITS_H=, ARRIVALS_LIMITS_H= 줄도 넣는다
sudo systemctl restart salmonbus-worker
journalctl -u salmonbus-worker --no-pager | grep 'GBIS 키 슬롯' | tail -1
```

마지막 명령의 출력에서 슬롯 목록과 한도 합을 확인한다. a~h 8개면 `GBIS 키 슬롯 8개로 수집한다. 슬롯=a,b,c,d,e,f,g,h 하루 한도 합=80000`이다.
이 로그는 수집을 활성화한 Worker에서만 남는다. 넣은 키는 "인증 정보를 출력하지 않고 확인하기"의 명령에서
`^GBIS_SERVICE_KEY=`를 `^GBIS_SERVICE_KEY_H=`처럼 바꿔 포털 값과 대조한다.

### 키 교체와 제거

- 같은 별칭의 값만 바꾸면 새 키도 그날 그 별칭이 쓴 횟수와 제외 상태를 이어 받는다. 그날 바로 새 한도로 쓰려면 새 별칭으로 넣는다.
- 줄을 지우고 다시 시작하면 그 별칭을 더 쓰지 않는다. 장부의 그날 행은 남아 있고, 같은 날 다시 넣으면 남은 횟수부터 이어서 센다.

### 키 1개로 되돌리기

```bash
sudo sed -i -E '/^GBIS_SERVICE_KEY=[[:space:]]*$/d; s/^GBIS_KEYS_A=/GBIS_SERVICE_KEY=/; /^(GBIS|ARRIVALS)_LIMITS_A=/b; /^GBIS_SERVICE_KEY_[A-Za-z0-9]+=|^GBIS_KEYS_[A-Za-z0-9]+=|^(GBIS|ARRIVALS)_LIMITS_[A-Za-z0-9]+=/d' /etc/salmonbus/worker.env
grep -cE '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env    # 1 이어야 한다
sudo systemctl restart salmonbus-worker
```

별칭 a의 키와 a의 한도 줄(`GBIS_LIMITS_A`·`ARRIVALS_LIMITS_A`)만 남기고 다른 별칭의 키와 한도 줄을 지운다. 공통 한도 줄은 그대로 둔다.
a를 `GBIS_KEYS_A`에 적었다면 `GBIS_SERVICE_KEY`로 이름을 바꿔 남긴다. `GBIS_SERVICE_KEY` 줄을 손으로 지우지 않는다. 별칭 a가 없으면 Worker가 시작하지 않는다.
재시작한 뒤에는 별칭 a만 쓴다. 장부의 다른 별칭 행은 그날 남아 있지만 더 늘지 않는다.

### 하루 한도 늘리기

노선 하나는 하루 4,278회를 호출한다. 20노선이면 85,560회라 키 4개 × 10,000회로는 오후에 한도가 바닥난다.
한도는 노선이 함께 쓰므로 바닥나면 기존 노선 수집도 자정까지 멈춘다.

포털에서 **모든 키의 위치정보 API 한도를 같은 값 이상으로 늘린 뒤에** 그 값을 넣는다. 키 4개로 20노선이면 키당 21,390회 이상이어야 한다.

```bash
echo 'GBIS_DAILY_LIMIT=25000' | sudo tee -a /etc/salmonbus/worker.env >/dev/null
sudo systemctl restart salmonbus-worker
journalctl -u salmonbus-worker --no-pager | grep 'GBIS 키 슬롯' | tail -1
```

**한 키라도 포털 한도가 이 값보다 작으면 넣지 않는다.** 포털이 22로 거절할 때까지 그 키를 쓴다.
이 값은 노선정보 API 예산에도 같이 적용된다. 노선정보 API 한도를 늘리지 않았다면 아래 "키별 사용량"으로 `BUS_ROUTE` 사용량을 확인한다.

### 키별 사용량

```sql
SELECT api_service, key_alias, reserved_calls, daily_limit
  FROM daily_call_quota
 WHERE provider = 'GBIS' AND kst_date = (now() AT TIME ZONE 'Asia/Seoul')::date
 ORDER BY api_service, key_alias;
```

`reserved_calls`는 보내기 전에 예약한 호출 수다. 포털이 센 호출 수와 다를 수 있다.

### 포털이 거절한 키

위치정보 호출에 포털이 22(하루 한도 초과), 30(등록되지 않은 키), 31(기한 만료)로 답하면 Worker는 그 키의
그날 장부를 한도까지 채운다. 그 키는 한국 자정까지 쓰지 않고, ERROR 로그를 한 줄 남긴다.
**키가 1개면 22를 받은 뒤로 그날 위치정보 수집이 멈춘다.** 그 뒤의 묶음은 `NOT_RESERVED`로 남는다.

```bash
journalctl -u salmonbus-worker --no-pager | grep 'GBIS 키를 거절' | tail -5
```

로그의 `채우기 전 사용량`은 채우기 직전의 `reserved_calls`다.
**채우기 전 사용량은 장부에 남지 않고 이 로그에만 있다.** 잘못 제외해서 그날 다시 써야 하면
아래에서 `764`를 로그의 `채우기 전 사용량`으로, `'b'`를 로그의 `슬롯`으로 바꿔 실행한다.

```sql
UPDATE daily_call_quota
   SET reserved_calls = 764
 WHERE provider = 'GBIS' AND api_service = 'BUS_LOCATION' AND key_alias = 'b'
   AND kst_date = (now() AT TIME ZONE 'Asia/Seoul')::date;
```

재시작하지 않아도 다음 수집부터 그 키를 다시 쓴다. 포털이 같은 코드로 다시 답하면 또 제외한다.

## 상태 확인

```bash
systemctl status salmonbus-api salmonbus-worker
journalctl -u salmonbus-worker -n 100 --no-pager

curl -s http://127.0.0.1:8082/actuator/health    # api
curl -s http://127.0.0.1:8082/actuator/info      # component · commit · sourcedigest
curl -s http://127.0.0.1:8081/actuator/health    # worker
curl -s http://127.0.0.1:8081/actuator/info
curl -s http://127.0.0.1:8080/readyz              # api 8080이 트래픽 받을 상태인지. DB는 보지 않는다
```

**`/actuator/health/readiness`를 DB 확인에 쓰면 안 된다.** DB 연결이 끊겨도 200 `UP`을 반환한다.
이 그룹에는 `readinessState`만 들어 있다. DB 연결까지 확인하는 경로는 `/actuator/health`이며
연결이 끊기면 503 `DOWN`을 반환한다. 8080의 `/readyz`는 이 readiness 그룹을 8080에 연 것이라 성질이 같다.
`validate.sh`는 DB를 `/actuator/health`로, 8080이 트래픽 받을 상태인지를 `/readyz`로 나눠 확인한다.

**health만으로는 수집이 실제로 실행되는지 확인할 수 없다.** 현재 Worker의 health에는
`db` · `diskSpace` · `ping` · `ssl` 같은 기본 항목뿐이라 API와 응답이 같다.
수집을 활성화한 뒤에는 `observation_batch`의 최근 행을 확인한다.

```sql
select max(started_at) from observation_batch;
```

## 후속 작업

**Worker의 수집 데이터가 최신인지 확인하는 검사는 이 티켓에서 추가하지 않았다.**
현재 `validate.sh`는 프로세스와 DB만 확인한다. 수집 작업이 중단되거나 Open API 호출이
계속 실패해도 배포 검증은 통과한다.

첫 배포는 `COLLECTION_ENABLED=false`라 확인할 수집 결과가 없다.
**수집을 활성화할 때 이 검사를 추가한다.** 다음 두 방식 중 하나가 필요하다.

| 확인 방식 | 수정 대상 |
| --- | --- |
| 최근 성공 시각을 확인하는 health 항목 | `worker-app`에 `HealthIndicator`를 하나 추가한다 |
| 스케줄러 heartbeat | 수집 회차마다 시각을 기록하고 health에 반영한다 |

두 방식 모두 애플리케이션 코드 수정이 필요하므로 배포 티켓 범위에서 제외했다.
수집을 활성화하는 배포 전에 별도 티켓을 만들어야 한다.

## 훅 스크립트를 고칠 때

**`set -x`를 쓰면 안 된다.** 훅 로그는 인스턴스에 남는다. env 값이 로그에 출력되면 파일 권한만으로 보호할 수 없다.

AWS에 접근하지 않고 Docker에서 검증할 수 있다.

```bash
bash backend/deploy/rehearsal/run.sh
```

리눅스 컨테이너에서 `systemctl` · `curl` · `java`를 모의 구현으로 대체하고 배포 시나리오를 검증한다.
첫 배포, api만 변경된 배포, 변경 없는 배포, 동시 배포, 이전 프로세스의 응답,
health 실패, 관리 포트 연결 실패, 8080만 응답하지 않는 경우, 전체 시간 상한, DB 진단 실패와 무응답,
롤백, DB가 계속 죽어 있어 롤백도 실패하는 경우, 변조된 배포 메타데이터, 잠금을 동시에 잡는 상황을 검사한다.
validate 실패 시나리오는 `VALIDATE_CHECK_SECONDS` 같은 환경변수로 상한을 몇 초로 줄여 실행한다.
CodeDeploy 환경에는 이 변수가 없으므로 운영에서는 기본값이 쓰인다.
리허설은 Bash로 실행하며 테스트용 JAR은 `makejar.sh`가 `zip`으로 만든다.
Python이나 호스트 JDK는 필요하지 않다. 필요한 Linux 도구는 컨테이너 안에 설치한다.

`test-deploy-lock.sh`는 실제 `common.sh`의 잠금 구간을 읽어 15개 회귀 테스트를 실행한다.
검사마다 임시 폴더를 사용하고 훅마다 별도 bash 프로세스를 실행한다. 후속 훅 실패 정리,
성공 시 잠금 유지, 종료 코드 보존, 다른 배포 소유권, 오래된 잠금 회수와 진행 중인 잠금 보존을 검사한다.
각 훅은 10초로 제한한다. 전체 리허설 결과에서는 이 15개를 한 항목으로 집계한다.
전체 리허설은 `run.sh`로 실행한다. `rehearse.sh`를 호스트에서 직접 실행하면 안 된다.
`/usr/bin/java`, `/etc`, `/opt/salmonbus`를 모의 환경으로 바꾸기 때문이다.

잠금 검사만 빠르게 실행하려면 프로젝트 루트에서 다음을 실행한다.

```bash
docker run --rm -v "$PWD/backend/deploy:/deploy:ro" amazonlinux:2023 \
  bash /deploy/rehearsal/test-deploy-lock.sh
```

GitHub CI의 `backend-deploy-contract` job(`.github/workflows/backend-ci.yml`)이 `deploy/rehearsal/run.sh`를 실행한다.
CodeBuild에는 아직 연결하지 않았다.
`rehearsal/`은 CodeDeploy 배포 패키지에 포함하지 않는다.

배포 패키지에 민감 정보가 포함됐는지 검사하는 `verify-revision.sh`도 함께 실행한다.
**CodeBuild에서 실행하는 것과 같은 파일이다.** `scripts/` 밖에 있으며 EC2 배포 패키지에는 포함되지 않는다.

모의 구현은 **지원하지 않는 입력을 받으면 실패한다.** `systemctl`이 지원하지 않는 명령을 받거나 `curl`이 등록되지 않은 주소를
받으면 실패로 종료한다. 훅의 포트나 경로가 잘못되면 리허설을 통과하지 못한다.

입력 목록과 `source_digest`·`runtime_source_digest`는 `deploy/runtime-source-inputs.sh` 한 곳에만 있다.
`rehearsal/digest.sh`와 `buildspec.yml`은 이 파일을 `source` 한다. **고칠 때는 이 파일만 고친다.**

**훅 스크립트를 바꾼 뒤 첫 배포가 실패하면 롤백은 직전 성공 패키지에 든 옛 스크립트로 실행된다.**
배포 패키지에 `scripts/`가 같이 들어 있고 롤백은 그 패키지를 다시 배포하는 것이기 때문이다. 롤백 로그가 옛 모양이어도 정상이다.
그래서 훅이 남기는 파일(`.changed`, `.deploying`, `release.env`)의 형식을 바꿀 때는 옛 스크립트가 그대로 읽을 수 있도록
**줄을 더하기만 하고 기존 줄의 의미는 바꾸지 않는다.** `.changed`에 `reason=` 줄을 더한 것이 그 예다.

리허설의 `systemctl`은 모의 구현이므로 systemd가 실제로 유닛을 읽는지 확인할 수 없다.
systemd 252를 컨테이너에 띄워 아래 아홉을 따로 확인했다.

```text
유닛이 systemd-analyze verify를 통과한다
salmonbus 사용자로 실행된다
권한 0600, 소유자 root:root인 /etc/salmonbus/api.env를 읽는다
배포 버전 식별용 release.env도 함께 읽는다
유닛의 MANAGEMENT_SERVER_PORT 설정이 적용된다
current 심볼릭 링크가 가리키는 JAR로 실행된다
0이 아닌 코드로 종료하면 Restart=on-failure가 재시작한다
143으로 종료하면 SuccessExitStatus=143이 성공으로 처리한다
```

## 수집 실패 배치 종료와 알림

수집 저장 예외가 발생하면 원래 `collection_attempt` 실패 로그를 남기고,
별도 트랜잭션에서 해당 배치의 `outcome`만 종료 상태로 바꾼다.
`RESERVED`는 `ABANDONED_BEFORE_SEND`, `DISPATCHING`은 `UNKNOWN_AFTER_DISPATCH`다.
후자는 DB에 확정된 수집 결과가 없다는 뜻이며 응답 미수신·저장 실패는 원인 로그로 구분한다.
성공 상태, 응답·입력 확정·완료 시각, 관측 또는 발행이 있는 배치는 변경하지 않는다.
배치와 기존 시각·호출 한도는 보존하며 과거 요청을 다시 전송하지 않는다.

DB 장애로 즉시 종료도 실패하면 `collection_batch_close_failed`를 기록하고 원래 수집 예외를 유지한다.
수집 노선이 설정돼 있으면 Worker의 첫 수집 회차와 이후 매 수집 회차 시작 전에
이전 회차에서 남은 미완료를 최대 100건씩 정리하고, 그다음 현재 노선 수집을 시작한다.
별도 정리 스케줄이나 2시간 대기 기준은 두지 않는다. 같은 단일 수집 스레드에서 호출하며,
구 Worker가 완전히 종료되고 같은 DB에 다른 수집 프로세스가 없어야 한다.
병렬 Worker나 비동기 수집으로 바꾸기 전에는 작업 소유권과 종료 판정부터 다시 설계해야 한다.
잠긴 배치는 기다리지 않고 다음 회차로 넘기며, 실제 종료 커밋 뒤 `collection_batch_recovery`에
건수와 배치 ID 목록을 남긴다. V68의 미완료 부분 인덱스가 필요하며 V68 전환과 같이 구 Worker를
중지한 상태에서 migration을 적용한다. 인덱스 생성은 수집 이력을 한 번 읽고 쓰기 잠금을 쓰므로
운영 적용은 별도로 수행한다. 새 JVM을 병렬 수집자로 확장하는 임대 계약은 포함하지 않는다.

알림은 기존 Grafana Discord 수신처 `salmonbus-service-discord`를 사용한다.
`salmonbus-diag-collection-failure`는 최근 5분에 내부 수집·종료·회수 예외가 한 번이라도 발생한 경우,
`salmonbus-diag-collection-recovery`는 이전 회차의 미완료 배치를 종료한 경우다.
단순 정상 관측 갱신 중단은 기존 노선별 3분 중단 알림으로 계속 구분한다.
새 규칙은 **일시 정지 상태로 준비**하며, 코드 작성이나 JSON 생성만으로 운영에 적용하지 않는다.
Worker 배포·observability 지표 수집·실패/복구 검증 뒤 활성화한다.
그룹 대기 30초, 묶음 간격 5분, 반복 간격 1시간을 사용하며 실제 도착 시각은 평가·수집 지연도 포함한다.
최근 5분에 사건이 없어서 알림이 복구된 것을 DB나 GBIS 원인 해결로 해석하지 않는다.
여기서 5분은 알림의 사건 조회·묶음 기준이며, 미완료 정리를 실행하는 주기가 아니다.

Discord 알림을 받으면 DB 원인 분석 화면의 수집 실패 및 상태 종료·회수 로그에서
`routeId`, `batchId`, `stage`, `reason`, `sqlState`를 확인한다.
53100이면 DB 여유 공간과 `could not extend file` 로그를 확인한다. 자동 증설이나 원본 삭제는 하지 않는다.
23503이면 `referenceRouteVersionId`, `stopOrder`, `stopId`와 노선 갱신 결과를 확인한다.
자동 종료는 미완료 상태를 정리한 것이며 수집 데이터 복원이나 원인 해결을 뜻하지 않는다.

## SAL-434 전환 절차

SAL-434는 예보·채점·보관을 새 경로(`forecast_run`·`forecast_record`·`stop_pass`·`pass_label`·`forecast_unresolved`, 날짜 파티션)로 옮긴다. PR이 머지돼 배포되는 순간 바뀌는 동작은 없다. 새 경로의 스위치는 모두 기본값이 꺼짐이고, Flyway에는 새 테이블(V81)과 그 테이블을 읽는 뷰(V82, V83)를 더하는 마이그레이션만 있다. V83은 `forecast_outcome` 뷰의 정의만 바꾸고 열은 V82와 같다. 옛 경로는 그대로 돈다.

아래 아홉 차례(6번 뒤의 6-1 포함)를 순서대로 진행한다. **차례마다 결과를 보이고 다음으로 넘어간다.** 「승인」 표시가 있는 차례는 실행 전에 따로 승인을 받는다. 트리거·테이블을 지우거나 모양을 바꾸는 일은 Flyway가 아니라 `deploy/sql/sal434-*.sql`을 사람이 실행한다. 이 파일들은 배포 패키지의 `sql/`에 함께 실린다(`/opt/salmonbus/api/current/sql/`). 배포 훅은 이 파일을 실행하지 않는다.

| 파일 | 차례 | 하는 일 | 되돌리기 |
| --- | --- | --- | --- |
| `sal434-01-precheck.sql` | 5·6-1·7·8 전후 | 읽기 전용. 건드릴 객체, 크기, 누적 통계, 장부(옛 테이블별 실제 줄 수·장부 합·조각 수), 가드 판정을 보인다 | 없음 |
| `sal434-02-drop-legacy-triggers.sql` | 5 | 옛 정산 트리거 9개와 함수 7개 삭제 | `sal434-02-rollback-recreate-legacy-triggers.sql` |
| `sal434-03-drop-legacy-tables.sql` | 7 | 옛 테이블 25개와 뷰 9개 삭제 | 없음(S3 내보내기에서만 되살린다) |
| `sal434-04a-prepare-observation-partition.sql` | 8 | 관측 두 테이블에 `source_date` 열, 확인 제약, 유일 인덱스 준비 | 파일 머리 주석의 문장 |
| `sal434-04-partition-observation-tables.sql` | 8 | 관측 두 테이블을 날짜 파티션으로 바꿈 | `sal434-04-rollback-unpartition-observation-tables.sql` |

각 파일의 머리 주석에 실행 조건, 잡는 잠금, 예상 시간, 가드가 있다. 가드가 맞지 않으면 파일은 아무것도 바꾸지 않고 `SAL-434:`로 시작하는 오류로 멈춘다. 오류 문구에 무엇을 먼저 해야 하는지 적혀 있다.

### DB에 붙는 방법

운영은 worker 계정이 모든 테이블의 소유자이고 api와 같은 계정이다(2026-11-15 확인). `worker.env`의 값으로 붙는다. **env 파일을 `source`하지 않는다.** 값의 `&` 같은 문자가 셸에서 해석된다. 비밀번호는 `PGPASSWORD`로만 넘기고 명령줄에 쓰지 않는다.

```bash
E=/etc/salmonbus/worker.env
url="$(sudo sed -n 's/^DB_URL=//p' "$E")"; hp="${url#jdbc:postgresql://}"; hp="${hp%%/*}"; db="${url##*/}"
export PGHOST="${hp%%:*}" PGDATABASE="${db%%\?*}" PGSSLMODE=require PGAPPNAME=salmonbus-sal434-transition
[ "$hp" = "${hp#*:}" ] && export PGPORT=5432 || export PGPORT="${hp#*:}"
export PGUSER="$(sudo sed -n 's/^DB_USERNAME=//p' "$E")"
IFS= read -r PGPASSWORD < <(sudo sed -n 's/^DB_PASSWORD=//p' "$E"); export PGPASSWORD
cd /opt/salmonbus/api/current/sql
psql -X -v ON_ERROR_STOP=1 -P pager=off -f sal434-01-precheck.sql > ~/sal434-precheck-$(date +%Y%m%d-%H%M).txt
```

승인값이 필요한 파일은 같은 세션에서 값을 먼저 넣는다. 날짜는 실행하는 날의 한국 날짜다. `-c`와 `-f`는 한 세션에서 차례로 실행된다.

```bash
psql -X -v ON_ERROR_STOP=1 -c "SET sal434.confirm_drop = '$(TZ=Asia/Seoul date +%F)'" -f sal434-03-drop-legacy-tables.sql
```

### 스위치

| 환경변수 | 파일 | 기본값 | 뜻 |
| --- | --- | --- | --- |
| `FORECAST_SHADOW_ENABLED` | worker.env | `false` | `FORECAST_PIPELINE=v1`일 때 옛 경로 옆에서 새 경로를 돌려 예측값을 대조하고 새 테이블에 쓴다. `v2`에서는 무시한다(기동 로그 `event=pipeline_shadow_ignored`) |
| `FORECAST_SCORING` | worker.env | `off` | `shadow`: 새 채점을 돌려 새 테이블에 쓴다. 옛 정산은 그대로 돈다. `v1`이면 그림자 예보의 사건을 받고 옛 정산과 대조하며, `v2`면 본선의 사건을 받고 대조하지 않는다. `v2`: 새 채점이 본선이고 옛 정산 작업을 돌리지 않는다(`FORECAST_PIPELINE=v2`에서만) |
| `BOARD_FORECAST_SOURCE` | api.env | `legacy` | `auto`: 노선마다 두 출처 가운데 최신 배치가 더 큰 쪽. `record`: 새 예보 기록만 읽는다 |
| `FORECAST_PIPELINE` | worker.env | `v1` | `v2`: 새 경로가 본선이다. 옛 예보 쓰기(`seat_forecast`·`forecast_publication`), `input_confirmed_at` 갱신, 체크포인트 파일·`alighting_history_checkpoint` 쓰기, `route` 행 잠금을 하지 않는다 |
| `FORECAST_LANES` | worker.env | `2` | `v2` 본선의 노선별 실행 줄 수. 같은 노선은 항상 같은 줄에서 순서대로 처리한다 |
| `FORECAST_LANE_QUEUE_CAPACITY` | worker.env | `1000` | 줄 하나의 길이. 넘치면 넣지 않고 다음 줍기(30초)에 맡긴다 |
| `FORECAST_REPLAY_EXTENDED_WINDOW` | worker.env | `12h` | 6시간(`FORECAST_REPLAY_WINDOW`)을 되감은 상태가 모자랄 때 한 번 늘려 되감는 범위. 12시간을 넘으면 worker가 기동하지 않는다 |
| `FORECAST_SHUTDOWN_TIMEOUT` | worker.env | `10s` | 정상 종료 때 실행 줄과 채점 줄이 받은 일을 끝내기를 기다리는 한도. 넘기면 남은 일을 버린다. 스프링의 단계별 종료 시한(30초)과 더한 값이 systemd의 `TimeoutStopSec`(45초) 안이어야 한다 |
| `ARCHIVE_HISTORY_HOLD` | archive.env | `checkpoint` | 최근 관측의 줄 단위 이관을 미루는 기준. `checkpoint`: 옛 체크포인트 보호선을 본다. `window`: 그 표를 보지 않고 `ARCHIVE_HISTORY_HOLD_WINDOW`보다 최근인 날짜를 이관하지 않는다 |
| `ARCHIVE_HISTORY_HOLD_WINDOW` | archive.env | `12h` | `window`의 창. worker가 되감는 가장 긴 범위(`FORECAST_REPLAY_EXTENDED_WINDOW`, 12시간) 이상으로 둔다. 1시간보다 짧으면 이관 앱이 기동하지 않는다 |
| `ARCHIVE_PARTITION_EXPORT_ENABLED` | archive.env | `false` | 닫힌 날짜의 파티션을 테이블마다 S3로 내보내고 다시 읽어 검증한다 |
| `ARCHIVE_PARTITION_EXPORT_DROP_ENABLED` | archive.env | `false` | 장부에 검증된 파티션을 떼어 내고 지운다 |
| `ARCHIVE_LEGACY_EXPORT_ENABLED` | archive.env | `false` | 옛 테이블 25개를 기본 키 순서의 조각으로 S3에 내보내고 다시 읽어 검증한다. 지우지 않는다 |
| `COLLECTION_BULK_INSERT` | worker.env | `false` | 관측을 한 문장으로 넣고 예약·전송을 한 커밋으로 한다 |

스위치를 바꾼 뒤에는 해당 서비스를 재시작해야 반영된다(「환경변수 변경」).

`FORECAST_ENABLED=true`인 worker가 받아들이는 예보 스위치 조합은 아래 넷뿐이다. 나머지 조합은 worker가 기동하지 않고 무엇을 바꿔야 하는지 오류로 남긴다(`FORECAST_SCORING=v2`인데 `FORECAST_PIPELINE=v1`, `FORECAST_SCORING=shadow`인데 그림자도 `v2`도 아님, `FORECAST_PIPELINE=v2`인데 `FORECAST_SCORING=off`). 스위치를 둘 바꿀 때는 한 번에 바꿔 이 표의 한 줄에서 다른 줄로 옮긴다.

| `FORECAST_PIPELINE` | `FORECAST_SHADOW_ENABLED` | `FORECAST_SCORING` | 상태 | 옛 정산 작업 | 채점 대조 |
| --- | --- | --- | --- | --- | --- |
| `v1` | `false` | `off` | 기본값. 옛 경로만 돈다 | 돈다 | 없음 |
| `v1` | `true` | `off` 또는 `shadow` | 그림자 예보(차례 1), 그림자 채점(차례 2) | 돈다 | `shadow`일 때 10분마다 |
| `v2` | 무시 | `shadow` | 새 예보가 본선(차례 4-1). 옛 정산은 남은 옛 예측만 마저 처리한다 | 돈다 | 없음(옛 예측이 더 생기지 않는다) |
| `v2` | 무시 | `v2` | 완전 전환(차례 4-2) | 돌지 않는다 | 없음 |

dev와 로컬에서 먼저 해 볼 때는 네 가지를 알고 시작한다.

- 새 경로는 확정 프로필(`alighting-confirmed-closure-v2`) 모델만 처리한다. 운영 모델은 확정 프로필이고, dev 서버도 `MODEL_BUNDLE_DIRECTORY`로 같은 번들을 쓰고 있어 그림자부터 그대로 해 볼 수 있다(2026-11-15 확인. 번들을 바꿨으면 `manifest.json`의 `input_profile`을 다시 본다). 저장소의 로컬 기본 모델(`dev/data/model-reference`)은 확정 프로필이 아니다. 그래서 로컬 Docker 구성에서는 그림자를 켜도 새 테이블에 아무것도 쌓이지 않고, `FORECAST_PIPELINE=v2`에서는 그 노선들을 `unsupported_profile`로 건너뛴다(경고 로그 `event=pipeline_forecast_unsupported_profile`). 그러면 예보가 나오지 않고 보드는 발행이 끊긴 것으로 보아 503(`NO_RECENT_OBSERVATION`)을 낸다. `salmonbus_pipeline_forecast_batches_total{result="unsupported_profile"}`가 늘지 않는지로 확인한다.
- 채점 그림자(2번)는 옛 발행이 모델 배포에 적어 둔 정산 근거(`model_deployment.settlement_context`)를 읽는다. 옛 발행은 `FORECAST_COMPACT_SETTLEMENT_WRITE=true`일 때만 이 근거를 적는다. 운영은 켜져 있다(2026-11-15 확인). 꺼진 환경에서 2번을 켜면 새 채점이 예측을 `SETTLEMENT_EVIDENCE_MISSING`으로 닫아 옛 정산과 대조할 정답이 생기지 않는다. dev 서버는 `dev/server/worker.yml`의 `forecast.compact-settlement-write`를 먼저 본다. 4-1부터는 본선이 근거를 직접 적는다.
- 개발 데이터 초기화(`reset`, `LocalData --reset-generated`)는 관측 테이블과 옛 예보·정산 테이블만 비우고 새 테이블 다섯 개는 비우지 않는다. 스위치를 켠 뒤 초기화를 돌리면 지워진 관측을 가리키는 줄이 새 테이블에 남는다. 초기화 직후 `TRUNCATE forecast_run, forecast_record, stop_pass, pass_label, forecast_unresolved;`를 실행하고 worker를 재시작한다.
- 로컬 구성의 프론트 대기 조건(`dev/docker/forecast-ready.sql`)과 스모크 검사(`dev/tests/smoke-local.sh`)는 `forecast_publication`을 본다. 로컬에서 `FORECAST_PIPELINE=v2`로 띄우면 옛 발행이 생기지 않아 프론트가 기다린다. 옛 코드를 지우는 배포에서 이 둘과 초기화 대상을 함께 고친다.

### 차례

**시작 전. 저장 공간.** 1번을 켠 뒤부터 새 테이블에 줄이 쌓이고, 6번에서 파티션 삭제를 켜기 전에는 지워지지 않는다. 옛 예보·정산이 쓰는 양은 4번까지 그대로이고 옛 테이블은 7번까지 줄지 않는다. 그래서 1번부터 7번까지 DB는 지금 늘던 속도에 새 테이블 분량을 더한 만큼 늘어난다.
- 확인: 「DB 저장 공간 여유」 패널(위 V80 배포 전 확인과 같은 위치)에서 최근 며칠의 하루 감소량을 읽고, 지금 여유가 7번까지 걸릴 기간을 버티는지 계산한다. 1·2번의 관찰 기간만 14일이다. RDS 저장 공간 자동 조정이 켜져 있는지도 본다.
- 모자라면 1번을 켜기 전에 저장 공간을 늘린다.
- 1번을 켜고 하루 뒤 `sal434-01-precheck.sql`이 보이는 새 테이블 파티션 크기로 하루 분량을 재서 다시 계산한다. 차례를 넘어갈 때마다 여유를 다시 본다.

**1. 예보 그림자.** `FORECAST_SHADOW_ENABLED=true`로 worker를 재시작한다.
- 넘어가는 기준: 7일 동안 새 경로와 옛 경로의 예측값 차이 0건. 기간 중 worker를 일부러 한 번 재시작해 되감기 뒤에도 0건인지 본다. 지표는 `salmonbus_pipeline_shadow_predictions_total{result}`(`same`, `value_differs`, `legacy_only`, `new_only`, `excluded`)와 `salmonbus_pipeline_shadow_batches_total{result}`(`mismatch`, `failed`가 0이어야 한다), 로그는 `event=pipeline_shadow_mismatch`·`pipeline_shadow_value_differs`다. `excluded`는 다시 뜬 직후 운행 시작을 못 본 차량이라 차이로 세지 않는다.
- 되돌리기: `false`로 재시작한다. 새 테이블에 쓴 줄은 남지만 아무도 읽지 않는다.

**2. 채점 그림자.** 1번을 켠 채 `FORECAST_SCORING=shadow`로 worker를 재시작한다(그림자 없이 `shadow`만 켜면 worker가 기동하지 않는다).
- 확인: 기동 뒤 `event=pipeline_scoring_start_rejected`가 없고, `salmonbus_pipeline_scoring_written_total{table="pass_label"}`가 늘며, `salmonbus_pipeline_scoring_waiting`과 `salmonbus_pipeline_scoring_queue`가 쌓이지 않는다. 사건을 버리면 `salmonbus_pipeline_scoring_dropped_total`이 늘고 그 노선을 다시 되감는다(`salmonbus_pipeline_scoring_replays_total{reason,result}`).
- 대조: 10분마다(`FORECAST_SCORING_COMPARISON_INTERVAL`) 수신 뒤 3시간(`FORECAST_SCORING_COMPARISON_DELAY`)이 지난 배치를 예측마다 옛 정산 결과와 대조해 `salmonbus_pipeline_scoring_compared_total{result}`로 낸다. `same`이 대부분이어야 하고 `unexplained`·`legacy_only`·`new_only`가 0이어야 한다. `intended_hole`(수집 구멍 표시)과 `intended_midnight`(자정 직후 직전 통과)는 미리 적어 둔 차이라 따로 센다. `unexplained`가 나오면 `event=pipeline_scoring_unexplained` 로그(회차마다 5건까지)에서 관측·목표·두 결과를 본다.
- 다시 뜬 뒤: 채점은 노선마다 저장까지 마친 마지막 사건에서 130분 앞부터(길어도 26시간) 예보와 통과를 다시 읽어, 멈춘 동안 닫지 못한 예측을 닫는다. 수집 구멍과 관측 상한 기억은 `forecast_run`의 `source_incomplete`·`counted_rows`에서 되살린다. 26시간보다 오래 멈췄다면 그 앞의 예측은 닫히지 않은 채 남는다. 기동할 때 최근 26시간에 예보가 있던 노선을 모두 한 번 되감으므로, 이력이 있는 상태에서 처음 켜면 첫 기동의 채점 되감기가 길다.
- 넘어가는 기준: 7일 동안 `unexplained` 0건.
- 되돌리기: `off`로 재시작한다.

**3. 이용자 API 출처.** `BOARD_FORECAST_SOURCE=auto`로 api를 재시작한다.
- 넘어가는 기준: dev에서 같은 시점의 두 출처 응답을 필드 단위로 대조해 같다. 운영은 `api.board` 지연과 오류율이 전과 같다.
- 되돌리기: `legacy`로 재시작한다.

**4. 새 경로를 본선으로.** 두 번에 나눠 바꾼다. 3번(`BOARD_FORECAST_SOURCE=auto`)이 먼저다. `auto`는 노선마다 최신 배치 쪽을 읽으므로 본선이 바뀐 뒤 첫 새 배치부터 새 기록을 읽는다.

*4-1. 새 예보 본선.* worker.env를 `FORECAST_PIPELINE=v2`, `FORECAST_SCORING=shadow`로 바꾸고(`FORECAST_SHADOW_ENABLED`는 두어도 무시된다) worker를 재시작한다. **같은 때** archive.env에 `ARCHIVE_HISTORY_HOLD=window`를 넣고 이관 앱을 재시작한다. 본선은 `alighting_history_checkpoint`를 더 갱신하지 않으므로 `checkpoint`로 두면 이관 보호선이 멈춘 자리에 남는다(더 오래 미루는 쪽이라 위험하지는 않지만 이관이 쌓인다).
- 이때 바뀌는 동작:
  - 수집 커밋 뒤 신호를 받으면 노선별 실행 줄(`forecast-lane-<번호>-`, `FORECAST_LANES`개)에서 배치를 처리한다. 착수 시각은 처리를 시작할 때 찍고(DB 저장 자릿수인 마이크로초로 자른다) `forecast_run.confirmed_at`에 예측과 한 트랜잭션으로 적는다. 수신 뒤 90초를 넘겼으면 예보 없이 `LATE` 실행 줄만 남긴다. 옛 경로는 착수 시각을 먼저 따로 커밋해서 계산 중에 죽어도 처음 시각으로 다시 예보했지만, 본선은 다시 처리할 때 새 시각으로 판정하므로 그런 배치는 `LATE`가 된다.
  - 기동 직후와 30초마다(`forecast.interval`) 신선도 창(10분) 안에서 "성공한 배치인데 `forecast_run`도 `input_confirmed_at`도 없는 것"을 줍는다. 옛 경로가 착수 시각을 찍은 배치는 이미 이력에 들어가 있어 다시 처리하지 않는다.
  - 기동하면 노선 상태가 없으므로 노선마다 첫 배치에서 6시간을 되감는다. 착수 시각은 배치마다 `forecast_run.confirmed_at`이 있으면 그것, 없으면 `input_confirmed_at`이다. 그래서 넘어간 직후에도 옛 경로가 처리한 배치와 본선이 처리한 배치를 빈틈 없이 잇는다.
  - 되감은 상태가 모자라면(최근에 보인 차량의 운행 시작을 보지 못했거나 이력이 미완) `FORECAST_REPLAY_EXTENDED_WINDOW`로 한 번 더 되감는다(`salmonbus_pipeline_forecast_replay_extensions_total{result}`: `resolved`, `unresolved`, `failed`). 그래도 모자라면 지금처럼 그 차량이나 노선을 예보에서 뺀다. 연결 시작을 보지 못한 차량이 닫아서 저장하지 못한 통과는 `salmonbus_pipeline_forecast_passes_unattributed_total`로 센다.
  - 되감기에 실패하는 등 예보 단계를 돌리지 못하고 끝난 배치가 닫았을 통과는, 다음에 그 노선을 되감을 때 찾아 저장하고 채점에 넘긴다(`salmonbus_pipeline_forecast_passes_recovered_total`).
  - 처리 중 예외가 난 배치는 같은 줄에서 한 번 다시 처리한다(`salmonbus_pipeline_forecast_batches_total{result="retried"}`). 또 실패하면 그 노선의 다음 배치가 그 배치부터 수신 순서로 다시 시도하고(`held`, 로그 `event=pipeline_forecast_held`), 수신 뒤 90초가 지나면 `LATE`로 닫는다. 한 배치가 계속 실패하면 그 노선의 예보가 최대 90초쯤 밀린다. 앞서 반영한 배치보다 먼저 수신한 배치가 뒤늦게 오면 이력에 넣지 않는다(`out_of_order`).
  - 정상 종료(배포, 재시작) 때는 수집과 줍기를 먼저 멈추고, 실행 줄과 채점 줄이 받은 일을 끝낸 뒤 채점 결과를 마지막으로 쓰고(`event=pipeline_scoring_final_write`) DB 연결을 닫는다. 한도는 `FORECAST_SHUTDOWN_TIMEOUT`이다. 다 끝내면 `event=pipeline_shutdown_drained`, 한도를 넘겨 남은 일을 버리면 `event=pipeline_shutdown_abandoned`(버린 수가 필드에 있다), 단계가 실패하면 `event=pipeline_shutdown_stage_failed`가 남는다. 버린 배치는 다시 뜬 뒤 줍기가 잡고, 수신 뒤 90초가 지났으면 `LATE`가 된다. 강제 종료에서는 이 처리가 돌지 않는다.
  - 모델 배포의 정산 근거(`model_deployment.settlement_context`)는 옛 발행 대신 본선이 (배포, 노선 판본)마다 한 번 적는다. 새 채점이 읽는다.
  - 옛 정산 작업은 그대로 돌며 남은 옛 예측을 마저 정산한다. 채점 대조 작업은 돌지 않는다.
- 확인: `salmonbus_pipeline_forecast_batches_total{result}`에서 `published`·`empty`가 늘고 `failed`가 0이다. `salmonbus_pipeline_forecast_replays_total{result="succeeded"}`가 노선 수만큼 한 번씩 늘고 `failed`가 없다. `salmonbus_pipeline_forecast_excluded_total{reason}`은 다시 뜬 직후에만 늘고 곧 멈춘다. 재시작한 뒤에는 앞 프로세스의 마지막 로그가 `event=pipeline_shutdown_drained`인지 본다. `sal434-01-precheck.sql`의 3절에서 `old_forecast_published`가 멈추고 `new_forecast_run`·`new_stop_pass_closed`가 계속 늘어난다.
- 넘어가는 기준: 운영 관찰 기간 동안 아래가 안정적이다.
  - 예보 지연: `salmonbus_pipeline_forecast_confirmation_lag_seconds`(수신→착수 분포)와 `salmonbus_pipeline_forecast_batch_duration_seconds`.
  - 늦어서 건너뛴 배치: `salmonbus_pipeline_forecast_batches_total{result="late"}`. 옛 경로의 수신 뒤 90초 초과 비율과 비슷해야 한다.
  - 줄 길이: `salmonbus_pipeline_forecast_queue{lane}`. 계속 쌓이면 `FORECAST_LANES`를 늘린다. 넣지 못한 배치는 `salmonbus_pipeline_forecast_rejected_total`.
  - 채점 대기: `salmonbus_pipeline_scoring_waiting`, `salmonbus_pipeline_scoring_queue`.
  - DB 연결: worker의 Hikari 풀은 5이고(`application.yml`), 본선의 줄 둘·줍기·수집·옛 정산·채점이 함께 쓴다. 줄은 문장을 실행하는 동안만 연결을 잡지만 `hikaricp_connections_pending`이 자주 0보다 크면 풀 크기를 올린다.
- 되돌리기: worker.env를 `FORECAST_PIPELINE=v1`, `FORECAST_SCORING=off`(또는 그림자를 켜고 `shadow`)로 바꾸고, 정비 명령 `alighting-history-bootstrap`으로 체크포인트를 다시 만든 뒤 worker를 재시작한다. archive.env는 `ARCHIVE_HISTORY_HOLD=checkpoint`로 되돌린다. 옛 테이블과 트리거가 남아 있으므로 이 차례까지는 스위치로 되돌릴 수 있다. 본선이 처리한 구간의 배치에는 `input_confirmed_at`이 없어 옛 경로의 이력에 들어가지 않는다(부트스트랩이 다시 만든다).

*4-2. 새 채점 본선.* 4-1이 안정된 뒤 `FORECAST_SCORING=v2`로 worker를 재시작한다. 옛 정산 작업(`ArrivalLabelJob`, 하차 반영 모델의 대기열 채우기·투영 정리·정산)이 등록되지 않는다. 이 작업이 하는 일은 옛 정산뿐이라 다른 일은 멈추지 않는다.
- 확인: worker 로그에서 `alighting_settlement_tick` 작업 기록이 사라지고, `salmonbus_pipeline_scoring_written_total`은 계속 는다.
- 되돌리기: `FORECAST_SCORING=shadow`로 재시작하면 옛 정산 작업이 다시 돈다.

**5. 옛 정산 트리거 삭제(승인).** 4번 관찰을 마치고 옛 정산이 멈춘 것을 확인한 뒤 실행한다.
1. `sal434-01-precheck.sql`을 실행해 보관한다. 4절에 트리거 9개·함수 7개가 보인다.
2. `sal434-02-drop-legacy-triggers.sql`을 실행한다. 가드가 최근 15분 안의 옛 정산 흔적(근거 저장, 배치 임대)과 옛 예보 발행, 90초 동안의 옛 정산·예보 테이블 쓰기를 보고, 새 채점의 통과 기록이 24시간 안에 있는지 본다. 약 90초 걸린다.
3. 다시 `sal434-01-precheck.sql`을 실행해 4절이 비었는지 본다.
- 넘어가는 기준: 트리거가 없어진 뒤 수집·예보·채점 지표가 그대로다.
- 되돌리기: `sal434-02-rollback-recreate-legacy-triggers.sql`을 실행한 뒤 4번의 되돌리기를 한다. 이 SQL은 활성 옛 정산 근거를 모두 무효로 표시해 처음부터 다시 만들게 한다. 트리거가 없던 동안 발행한 예측의 옛 정산 대기 줄은 다시 생기지 않는다. 7번 뒤에는 쓸 수 없다.

**6. 날짜 단위 내보내기.** archive.env에 `ARCHIVE_PARTITION_EXPORT_ENABLED=true`를 넣고 이관 앱을 재시작한다. 지우기 스위치는 아직 켜지 않는다.
- 넘어가는 기준: 하루치를 S3 파일만으로 되살려 예측별 결과가 옛 결과 줄과 맞는다. 그다음 `ARCHIVE_PARTITION_EXPORT_DROP_ENABLED=true`로 새 테이블의 오래된 파티션 삭제를 켠다.
- 옛 테이블 25개는 이 스위치가 아니라 6-1번에서 내보낸다.
- 되돌리기: 스위치를 끈다. 지우기 스위치를 켜기 전까지는 DB의 줄이 그대로다.

**6-1. 옛 테이블 내보내기.** 7번이 장부를 확인한다. **계약: 줄이 있는 옛 테이블마다 `daily_export`에서 `table_name`이 그 테이블이고 `verified_at`이 찍힌 줄의 `row_count` 합이 테이블의 줄 수와 같고, 옛 테이블 조각(`source_date = 2000-01-01`)의 번호가 0부터 빈틈없이 이어져야 한다.** 동작과 설정은 「옛 테이블 내보내기」에 있다.
1. **줄 단위 이관을 멈춘다.** 노선 판본 이관은 내보낸 줄을 지우므로 함께 돌면 장부와 줄 수가 어긋난다. archive.env에 `ARCHIVE_ENABLED=false`를 넣는다(이 둘을 함께 켜면 이관 앱이 기동하지 않는다).
2. **옛 경로가 더 쓰지 않는지 확인한다.** 4·5번이 끝나 있어야 한다. `sal434-01-precheck.sql`을 몇 분 간격으로 두 번 실행해 2절의 대상 테이블 `inserts`·`updates`·`deletes`가 늘지 않는지, 3절의 `old_*` 시각이 멈췄는지 본다.
3. archive.env에 `ARCHIVE_LEGACY_EXPORT_ENABLED=true`를 넣고 이관 앱을 재시작한다. 처음에는 기본값으로 두고 RDS 처리량 크레딧, `api.board` 지연, `salmonbus_archive_legacy_rows_per_second`를 보며 `PAGE_PAUSE`·`PAGE_SIZE`·`MAX_ROWS_PER_RUN`·`INTERVAL`을 조절한다. 걸리는 시간은 운영에서 재야 한다(「옛 테이블 내보내기」의 추정 참고).
4. 진행은 `status=SEGMENT`·`status=RUN` 로그와 `salmonbus_archive_legacy_exported_rows{table}`로 본다. 테이블마다 `status=COMPLETE`와 `salmonbus_archive_legacy_complete{table}=1`이 나오면 끝이다. `status=MISMATCH`가 있으면 원인을 찾고 「옛 테이블 내보내기」의 방법으로 다시 내보낸다.
5. `sal434-01-precheck.sql`을 `-c "SET sal434.count_legacy_rows = 'exact'"`와 함께 실행해 8-2절에서 테이블마다 `exact_rows`와 `verified_ledger_rows`가 같고 `sal434_03_ledger_check`가 `ok`인지 본다. 옛 테이블 전체를 읽으므로 크레딧이 충분할 때 한다. 출력을 보관한다.
6. `ARCHIVE_LEGACY_EXPORT_ENABLED=false`로 이관 앱을 재시작하고 7번 승인을 받는다. 켜 둔 채면 회차마다 테이블의 마지막 키 뒤를 읽어 `sal434-03`의 읽기 관찰 가드(120초)가 멈춘다. 5번의 줄 수 확인은 스위치를 끈 뒤에 해도 된다.
- 되돌리기: 스위치를 끄고 재시작한다. DB의 줄은 그대로이고 장부와 S3 파일은 남는다.

**7. 옛 테이블 삭제(승인).** 다음을 모두 확인한 뒤 실행한다. **이 차례는 되돌릴 수 없다.**
- 6-1번이 끝났다. 모든 옛 테이블이 `status=COMPLETE`이고 `sal434-01`의 8-2절이 `ok`다. 그 뒤 옛 테이블에 쓴 곳이 없다.
- api·worker에 옛 테이블을 매핑한 코드가 없는 배포본이 올라가 있다. **SAL-434 배포본의 api는 `seat_forecast`·`forecast_publication` JPA 엔티티를 갖고 있고 `ddl-auto: validate`라서, 테이블을 지운 뒤 다시 뜨면 스키마 검증에서 멈춘다.** 옛 경로 코드를 지운 후속 배포가 먼저다. SQL은 배포본을 볼 수 없으므로 사람이 확인한다.
- `BOARD_FORECAST_SOURCE=record`로 api를 재시작했다(`auto`도 옛 테이블을 읽는다).
- 옛 이관 앱(노선 판본 이관, `ARCHIVE_ENABLED`)이 멈춰 있다. 옛 테이블을 읽는 대시보드·수동 스크립트·저장소 밖 도구(`docs/salmon-action` 추출기 등)를 멈췄다. 가드가 120초 동안 읽기 통계를 보고 읽는 곳이 있으면 멈춘다.
- **운영 서버의 배포 가드 사본을 바꿨다.** SAL-175 전환 때 `prepare`가 `/etc/salmonbus/evaluation-work-transition.sh`와 서비스 기동 전 검사(`/etc/systemd/system/salmonbus-{api,worker}.service.d/evaluation-work-transition.conf`)를 설치했다. 옛 사본은 7번 뒤의 DB를 "지원하지 않는 상태"로 보고 서비스 재시작을 막는다. SAL-434 배포본의 스크립트는 옛 대기 테이블·뷰·`seat_forecast`가 모두 없고 새 예보 테이블이 있는 DB를 `retired`로 받아들인다(V81 이상 배포본만). 하나만 없어진 중간 상태는 계속 막는다.

  ```bash
  ls -l /etc/salmonbus/evaluation-work-transition.sh /etc/systemd/system/salmonbus-*.service.d/evaluation-work-transition.conf
  # 사본이 있으면 이번 배포판의 스크립트로 바꾼다. 서비스는 재시작하지 않는다
  sudo install -m 644 -o root -g root /opt/salmonbus/api/current/scripts/evaluation-work-transition.sh /etc/salmonbus/evaluation-work-transition.sh
  sudo bash /etc/salmonbus/evaluation-work-transition.sh runtime api && echo 통과      # 지금은 work로 통과해야 한다
  sudo bash /etc/salmonbus/evaluation-work-transition.sh runtime worker && echo 통과
  ```

- RDS EBS 처리량 크레딧(최근 1시간 최소 20% 이상)과 여유 저장공간을 확인했다. 가드가 옛 테이블 전체(운영 DB 용량의 대부분)를 한 번 센다. 쓰기는 막고 읽기는 허용한 채 수십 분이 걸릴 수 있다(추정).

실행 순서는 다음과 같다.
1. `sal434-01-precheck.sql`을 실행해 보관한다. 5-1절에서 `referencing_is_target=false`인 FK가 관측 두 테이블 쪽(`vehicle_observation`, 운영의 `historical_import_record`·`migration_source_record`)뿐인지, 5-2절에서 대상 밖의 뷰가 `operational_observation`뿐인지, 5-3절이 비었는지, 8-2절에서 줄이 있는 테이블마다 장부 합이 실제 줄 수와 같은지(`sal434_03_ledger_check`) 본다. 6-1절의 운영 전용 테이블이 대상을 가리키면 사람이 처리 방법을 정한다.
2. `sal434-03-drop-legacy-tables.sql`을 `sal434.confirm_drop`과 함께 실행한다. 지우기 전에 테이블마다 정확한 줄 수와 크기가 NOTICE로 남는다. 출력을 보관한다.
3. `sal434-01-precheck.sql`을 다시 실행한다. 10절이 `retired`여야 한다. api·worker를 하나씩 재시작해 배포 가드와 스키마 검증을 통과하는지 본다.
- Flyway는 적용 이력과 파일 체크섬만 보므로 이 뒤의 배포에서도 `validate`·`migrate`가 통과한다(시험으로 확인). 수동으로 지운 상태는 전환이 끝난 뒤 마무리 PR에서 `IF EXISTS` 마이그레이션으로 맞춘다.

**8. 관측 테이블 날짜 나눔(승인), 이관 앱 내림.** dev에서 같은 절차를 먼저 해 보고 걸린 시간을 기록한다. 수집이 뜸한 02~05시에 한다.
1. 운영에만 있는 `historical_import_record`·`migration_source_record`의 FK가 남아 있으면 「옛 이관 기록 테이블의 FK 정리」로 먼저 지운다. 04가 이 FK를 보고 멈춘다.
2. `sal434-04a-prepare-observation-partition.sql`을 실행한다. 열 추가는 바로 끝나고, 확인 제약 확인과 유일 인덱스 다섯 개 생성은 쓰기를 막지 않지만 테이블을 여러 번 읽는다(크레딧 확인). 마지막 조회에서 다섯 인덱스가 모두 `valid`여야 한다.
3. `sal434-04-partition-observation-tables.sql`을 `sal434.confirm_partition`, `sal434.count_rows`와 함께 실행한다. `exact`는 잠금을 잡은 채 두 테이블을 바꾸기 전과 뒤에 세어 대조한다. 그동안 수집 쓰기가 기다린다. `storage`는 세지 않고 저장 파일이 그대로 파티션이 됐는지만 본다. dev 리허설에서 `exact`에 걸린 시간을 보고 고른다.
4. `sal434-01-precheck.sql`의 7·9절에서 `observation_batch_legacy`·`vehicle_observation_legacy`, 오늘부터 사흘 뒤까지의 날짜 파티션, 기본 파티션을 확인한다. 수집 한 회차 뒤 새 줄이 오늘 파티션에 들어갔는지 본다.
5. 부모 통계를 모은다: `ANALYZE observation_batch; ANALYZE vehicle_observation;`(파티션 전체에서 표본을 읽는다).
6. 이관 앱(`salmonbus-archive`)의 옛 노선 판본 이관을 내린다. 이관 앱은 CodeDeploy 대상이 아니므로 서버에서 서비스를 멈추고 지운다.
- 이 SQL 뒤에 달라지는 것:
  - 두 테이블 사이의 FK(`fk_observation_batch`)가 없어진다. 관측은 응답을 받은 날, 배치는 예약한 날을 기본값으로 받아 자정 근처에 날짜가 다를 수 있어서, 날짜를 넣은 FK를 둘 수 없다.
  - 유일 제약은 날짜 파티션 안에서만 지킨다(`id`, 노선 판본+시도 키, 배치+줄 번호, 배치+차량). 한 배치의 관측은 한 트랜잭션에서 들어가 같은 날짜가 되므로 배치 안 유일성의 뜻은 그대로다. 시도 키는 예약 시각을 담고 있어 날짜가 다른 같은 키는 생기지 않는다.
  - 수집이 켜진 worker(`COLLECTION_ENABLED=true`)의 날짜 파티션 유지 작업은 두 테이블이 파티션 테이블(`relkind='p'`)이면 예보 스위치와 무관하게 오늘부터 사흘 뒤까지 같은 이름 규칙(`<테이블>_pYYYYMMDD`)으로 파티션을 미리 만든다. 일반 테이블인 동안에는 카탈로그만 읽는다. 기동 직후 한 번, 그 뒤 1시간마다 돌며 만든 파티션은 `event=pipeline_partitions_created`, 기본 파티션에 쌓인 줄은 `salmonbus_pipeline_partition_default_rows{table}`로 본다. 만들지 못하면 새 줄이 기본 파티션에 쌓이고(동작은 한다), 기본 파티션에 그 날짜의 줄이 생긴 뒤에는 그 날짜 파티션을 만들 수 없다.
- 되돌리기: `sal434-04-rollback-unpartition-observation-tables.sql`을 `sal434.confirm_partition_rollback`과 함께 실행한다. 날짜·기본 파티션의 줄을 id 그대로 옛 테이블로 옮기고 원래 이름·IDENTITY·FK를 되돌린다. 옮기는 줄이 옛 유일 제약에 걸리면 멈춘다.

**9. 수집 묶음 넣기.** `COLLECTION_BULK_INSERT=true`로 worker를 재시작한다.
- 넘어가는 기준: 수집 지표(회차 시간, 실패, 하루 한도 사용량)가 전과 같다.
- 되돌리기: `false`로 재시작한다.
