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

CodePipeline `salmonbus-backend-cd`에서 `Release change`를 누른다.

```text
Source           GitHub dev에서 코드를 가져온다
BuildAndTest     salmonbus-backend-build가 backend/buildspec.yml을 읽는다
                 ./gradlew clean build --no-daemon 으로 전체 테스트를 다시 실행하고
                 ApiRevision · WorkerRevision 두 배포 패키지를 생성한다
ManualApproval   담당자가 승인 버튼을 누른다
DeployApi        run order 1
DeployWorker     run order 2
```

**API를 먼저 배포하고 검증한 뒤 Worker를 배포한다.** 두 프로세스를 동시에 재시작하지 않는다.

승인 전에 Build 로그 끝에 출력된 배포 메타데이터를 확인한다.

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
api     api-app/src/main · api-app/build.gradle
        + common/src/main · common/build.gradle · build.gradle · settings.gradle · gradle-wrapper.properties
worker  worker-app/에 같은 목록을 적용한다
```

**JAR 지문으로는 소스 변경 여부를 판단할 수 없다.** `buildInfo()`가 추가하는 `build.time` 때문에 같은 소스를 다시 빌드해도
JAR의 SHA-256이 달라진다. 실측으로 확인했다. 소스 지문을 쓰면 테스트·문서·프론트만 바뀐 배포에서는
서비스를 재시작하지 않는다.

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

실패한 배포는 `validate.sh`까지 실행하지 못해 배포 잠금을 남긴 채 종료된다. 따라서 같은 서비스의
다음 배포는 기존 잠금을 **넘겨받는다.** 그렇지 않으면 자동 롤백이 앞선 배포가 남긴 잠금 때문에 차단된다.
다른 서비스가 잠금을 사용 중이면 대기하지 않고 배포를 중단한다.

수동으로 롤백할 때는 대상 서비스를 하나만 선택한다.

```bash
C=api            # 또는 worker
sudo ln -sfn "$(readlink -f /opt/salmonbus/$C/previous)" /opt/salmonbus/$C/current
sudo systemctl restart salmonbus-$C
curl -s http://127.0.0.1:8082/actuator/info     # api. 현재 실행 중인 배포 버전을 확인한다
curl -s http://127.0.0.1:8081/actuator/info     # worker
```

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

## 인증 정보를 출력하지 않고 확인하기

```bash
stat -c '%a' /etc/salmonbus/worker.env                          # 600 이어야 한다
grep -c '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env          # 1 이어야 한다
grep '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env | cut -d= -f2- | tr -d '\n' | sha256sum | cut -c1-8
```

마지막 명령의 출력에서 앞 8자를 포털 화면의 값과 대조한다. `grep -c`의 `-c`를 빼면 값이 그대로 출력된다.

**앱이 시작됐다는 사실만으로 인증키가 올바르다고 판단할 수 없다.** 잘못된 키로도 4초 만에 시작하고 health는 200을 반환한다.
노선 버전이 없는 DB에서는 수집이 Open API를 호출하지 않으므로 오류도 발생하지 않는다.

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
롤백, 변조된 배포 메타데이터, 잠금을 동시에 잡는 상황을 검사한다.
validate 실패 시나리오는 `VALIDATE_CHECK_SECONDS` 같은 환경변수로 상한을 몇 초로 줄여 실행한다.
CodeDeploy 환경에는 이 변수가 없으므로 운영에서는 기본값이 쓰인다.
리허설은 Bash로 실행하며 테스트용 JAR은 `makejar.sh`가 `zip`으로 만든다.
Python이나 호스트 JDK는 필요하지 않다. 필요한 Linux 도구는 컨테이너 안에 설치한다.

`test-deploy-lock.sh`는 실제 `common.sh`의 잠금 구간을 읽어 12개 회귀 테스트를 실행한다.
검사마다 임시 폴더를 사용하고 훅마다 별도 bash 프로세스를 실행한다. 후속 훅 실패 정리,
성공 시 잠금 유지, 종료 코드 보존, 다른 배포 소유권을 검사한다. 각 훅은 10초로 제한한다.
전체 리허설 결과에서는 이 12개를 한 항목으로 집계한다.
전체 리허설은 `run.sh`로 실행한다. `rehearse.sh`를 호스트에서 직접 실행하면 안 된다.
`/usr/bin/java`, `/etc`, `/opt/salmonbus`를 모의 환경으로 바꾸기 때문이다.

잠금 검사만 빠르게 실행하려면 프로젝트 루트에서 다음을 실행한다.

```bash
docker run --rm -v "$PWD/backend/deploy:/deploy:ro" amazonlinux:2023 \
  bash /deploy/rehearsal/test-deploy-lock.sh
```

이 리허설을 GitHub CI·CodeBuild에서 자동으로 실행하도록 아직 연결하지 않았다.
`rehearsal/`은 CodeDeploy 배포 패키지에 포함하지 않는다.

배포 패키지에 민감 정보가 포함됐는지 검사하는 `verify-revision.sh`도 함께 실행한다.
**CodeBuild에서 실행하는 것과 같은 파일이다.** `scripts/` 밖에 있으며 EC2 배포 패키지에는 포함되지 않는다.

모의 구현은 **지원하지 않는 입력을 받으면 실패한다.** `systemctl`이 지원하지 않는 명령을 받거나 `curl`이 등록되지 않은 주소를
받으면 실패로 종료한다. 훅의 포트나 경로가 잘못되면 리허설을 통과하지 못한다.

`digest.sh`의 `source_digest`는 `buildspec.yml`에 있는 것과 같은 함수다. **수정할 때는 두 파일에 모두 반영한다.**

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
