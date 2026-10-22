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

dev 파이프라인은 `dev/server/buildspec.yml`로 빌드하고 `dev/server/deploy`의 훅으로 배포한다. dev 배포는 `dev/data/java`·`dev/data/models`·
`ForecastFeatureContract.java`가 바뀌면 데이터 전환 승인을 요구하며 멈춘다. 그 밖의 변경(마이그레이션 포함)은 이 승인 단계에서 멈추지 않는다.

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

시간 상한은 셋이다. 전체 240초, 검사 하나 90초, 요청 하나 5초. 검사는 3초 간격으로 다시 묻되
남은 시간을 넘기는 요청이나 대기는 하지 않는다. **전체 상한은 appspec의 `ValidateService` timeout(300초)보다
짧아야 한다.** CodeDeploy가 먼저 훅을 종료하면 아래 요약 로그가 남지 않고 배포 잠금도 풀리지 않는다.
리허설이 두 값을 읽어 확인한다.

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
sudo -u salmonbus cp manifest.json weights.safetensors /var/lib/salmonbus/model/current/
sudo sed -i 's/^MODEL_BUNDLE_PROMOTE_ON_START=.*/MODEL_BUNDLE_PROMOTE_ON_START=true/' /etc/salmonbus/worker.env
sudo systemctl restart salmonbus-worker
# 모델 계수 적용을 확인한 뒤 반드시 다시 비활성화한다.
sudo sed -i 's/^MODEL_BUNDLE_PROMOTE_ON_START=.*/MODEL_BUNDLE_PROMOTE_ON_START=false/' /etc/salmonbus/worker.env
```

**활성화한 채로 두면 배포로 재시작할 때마다 디스크의 모델 계수가 적용된다.**
운영 기본값은 `false`이며 systemd 유닛에도 같은 값이 설정돼 있다.

## 운영 첫 V39 전환

운영 데이터가 있는 DB에서는 prepare 승인 없이 V39가 실행되지 않는다. 승인 없이 새 배포 버전을 내보내면 API의 BeforeInstall에서 멈춘다.

```text
1. 자동 배포와 정비 작업을 멈춘다. 같은 DB에 붙은 psql 같은 수동 세션은 자동 검사가 막지 않으므로 직접 정리한다
2. 배포 전 DB 백업을 확인한다. prepare는 백업을 만들거나 검증하지 않는다
3. 새 배포 버전의 scripts/와 sql/이 나란히 있는 디렉터리에서 실행한다
   sudo bash scripts/evaluation-work-transition.sh prepare <백업 식별자>
4. API를 배포한다. API의 Flyway가 V39까지 적용한다
5. worker를 배포한다. worker는 V39가 적용된 DB에서만 시작한다
```

prepare는 다음을 확인한 뒤 worker를 멈추고 30분짜리 승인을 DB에 남긴다. 어느 하나라도 안 되면 멈춘다.

- `api.env`와 `worker.env`가 같은 DB를 가리킨다. 호스트·포트·DB 이름·계정이 같아야 한다. URL 쿼리에는 `connectTimeout`·`socketTimeout`·`tcpKeepAlive`와 `options`의 `join_collapse_limit`·`from_collapse_limit`·`geqo_threshold`만 쓸 수 있고, 이 값들은 두 파일에서 달라도 된다. 그 밖의 키는 두 파일에 같은 값으로 들어 있어도 멈추므로, 전환 전에 두 파일에서 빼거나 허용 목록을 먼저 고친다
- 기존 대기 테이블 구조다. **Flyway 버전은 보지 않는다.** 앞선 시도가 일부 마이그레이션을 남겼는지는 사람이 확인한다
- worker와 정비 연결이 남아 있지 않다

30분 승인은 API의 BeforeInstall·ApplicationStart·기동 전 검사와 V39 안에서 확인한다. V39가 끝나면 승인 기록은 지워지고 worker에는 시한이 없다.

**V39가 적용된 뒤에는 옛 JAR가 기동 검사에 막힌다.** CodeDeploy 자동 롤백이 옛 배포 버전으로 바꾸면 서비스가 멈춘 채 남는다.
이전 버전으로 돌아가려면 배포 전 DB와 그 DB에 맞는 이전 배포 파일을 함께 복구한 뒤 실행한다.

```bash
sudo bash /etc/salmonbus/evaluation-work-transition.sh resume-legacy
```

DB의 Flyway 최대 버전과 두 앱 `current`의 `flywayMaxVersion`이 같아야 차단이 풀린다. 서비스는 시작하지 않는다.
V39 전용 롤백 SQL은 V38까지만 되돌리므로 V38 배포 파일이 없으면 쓸 수 없다. V40 이후가 적용된 DB에서는 롤백 SQL이 실행을 거부한다.

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
- `/api/auth/*/start`는 익명 요청마다 10분짜리 임시 세션 행을 만든다. WAF에서 이 경로와 `/api/auth/refresh`에 IP별 요청 제한을 건다

켠 뒤 확인할 것:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/auth/session                  # 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/me                            # 401
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://127.0.0.1:8080/api/auth/kakao/start  # 302 카카오 주소
```

운영 중 확인할 지표와 로그:

- `auth.refresh.rejected{reason=REUSED}`가 늘면 탈취된 리프레시 토큰이 다시 쓰였을 수 있다. 같은 시각 로그에 "이전 세대 리프레시 토큰이 다시 제시되어" 경고가 남는다
- `auth.unlink{result=not_linked}`는 카카오가 "이미 연결되지 않은 사용자"라고 답한 경우다. 해제된 것으로 보지만, 이 값이 탈퇴마다 나오면 `AUTH_KAKAO_ADMIN_KEY`가 다른 앱(dev 등)의 키일 수 있다. 해제 결과를 DB에 기록하지 못해 다시 시도한 요청에서도 한 번씩 나오므로, 가끔 나오는 것은 정상이다
- `auth.unlink{result=failed}`는 카카오 연결 해제 실패다. 서버가 5분마다 확인해 1분부터 두 배씩 늘어나는 간격으로 다시 시도하고, 10번 실패하면 멈춘다. 멈춘 요청은 `SELECT id, provider, attempts, last_error, updated_at FROM provider_unlink WHERE status = 'GAVE_UP'`으로 본다
- `auth.withdraw{provider=APPLE,result=revoke_failed}`는 애플 토큰 취소 실패다. 이때는 회원을 지우지 않고 `withdraw=failed`로 돌려보내므로 회원과 로그인은 그대로 남고 사용자가 다시 시도한다. 탈퇴할 때마다 나오면 `AUTH_APPLE_*` 설정과 `.p8` 파일을 확인한다
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

## 평가 이관과 원본 삭제

worker 유닛이 `FORECAST_ARCHIVE_ENABLED=true`·`FORECAST_ARCHIVE_DELETE_ENABLED=true`로 켠다.
검증을 마친 완료 정산을 S3 `techcourse-project-2026` 버킷의 `salmonbus-be/evaluation-archive/v1/` 아래에 올리고, 다시 내려받아 대조한 뒤 DB 원본을 지운다.
**이 경로에는 환경 구분이 없다.** dev와 운영이 같은 접두어를 쓴다.

켜 두기 전에 확인할 것:

- 인스턴스 역할에 이 접두어의 `s3:PutObject`·`s3:GetObject` 권한이 있다
- 버킷 수명 주기 규칙이 이 접두어의 객체를 지우지 않는다. 지우면 DB 원본을 삭제한 뒤 복구할 곳이 없다

끄려면 `worker.env`에 줄을 넣고 재시작한다. `worker.env`는 유닛의 `Environment=`보다 뒤에 읽혀 값을 덮는다.
삭제만 끄면 이관은 계속하고 DB 원본은 남긴다.

```bash
echo 'FORECAST_ARCHIVE_DELETE_ENABLED=false' | sudo tee -a /etc/salmonbus/worker.env >/dev/null   # 이관도 끄려면 FORECAST_ARCHIVE_ENABLED=false
sudo systemctl restart salmonbus-worker
```

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
