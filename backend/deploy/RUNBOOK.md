# 배포와 복구 절차

담당자가 아닌 사람이 이 문서만 보고 배포와 되돌리기를 할 수 있어야 한다.

## 무엇이 어디 있나

API 와 Worker 를 따로 배포한다. 배포판도 따로, systemd 서비스도 따로다.

```text
/opt/salmonbus/api/releases/<소스지문>/       판마다 따로 쌓인다. 셋만 남긴다
/opt/salmonbus/api/current                    지금 도는 판을 가리키는 바로가기
/opt/salmonbus/api/previous                   직전 판
/opt/salmonbus/worker/...                     같은 모양으로 하나 더

/etc/salmonbus/api.env                        API 가 읽는 값
/etc/salmonbus/worker.env                     Worker 가 읽는 값
/var/lib/salmonbus/model/current/             계수 파일. 사람이 갖다 놓는다
```

**배포는 `/opt/salmonbus/` 만 갈아엎는다.** `/etc/salmonbus/` 와 `/var/lib/salmonbus/` 는 손대지 않는다.

## 포트

```text
8080   API 클라이언트.  밖에 열린다
8082   API Actuator.    127.0.0.1 에만 묶인다
8081   Worker.          127.0.0.1 에만 묶인다. Actuator 도 여기다
```

**API 의 Actuator 를 클라이언트 포트에서 뗐다.** systemd 유닛의 `MANAGEMENT_SERVER_PORT` 와
`MANAGEMENT_SERVER_ADDRESS` 로 옮긴 것이라 `application.yml` 은 안 고쳤다.

## 최초 1회. 서버에서 사람이 한다

```bash
sudo useradd -r -s /sbin/nologin salmonbus
sudo mkdir -p /opt/salmonbus /etc/salmonbus /var/lib/salmonbus/model/current
sudo chown -R salmonbus:salmonbus /var/lib/salmonbus

sudo install -m 600 -o root -g root /dev/null /etc/salmonbus/api.env
sudo install -m 600 -o root -g root /dev/null /etc/salmonbus/worker.env
sudo vi /etc/salmonbus/api.env       # DB_URL · DB_USERNAME · DB_PASSWORD
sudo vi /etc/salmonbus/worker.env    # 위 셋 + GBIS_SERVICE_KEY · COLLECTION_ENABLED · FORECAST_ENABLED
```

첫 배포는 수집과 예보를 끈 채로 한다.

```text
COLLECTION_ENABLED=false
FORECAST_ENABLED=false
```

**`api.env` 에 `GBIS_SERVICE_KEY` 를 넣지 않는다.** API 는 그 값을 읽는 자리가 없다.
빌드된 JAR 안에 `gbis` 라는 글자가 0개다. 넣으면 안 쓰는 곳에 비밀을 퍼뜨리는 것이다.

자바 21 과 CodeDeploy agent 가 있어야 한다. 없으면 `preflight.sh` 가 배포를 세운다.
systemd 유닛은 배포가 알아서 넣는다.

### 비밀을 어디 둘지 먼저 한 번 재 본다

인스턴스 역할이 Parameter Store 를 읽을 수 있으면 그쪽이 낫다. 서버에서 한 번만 재면 된다.

```bash
aws ssm get-parameter --name /salmonbus/probe --with-decryption --region ap-northeast-2
```

`ParameterNotFound` 가 오면 **권한은 있는 것이다.** `AccessDeniedException` 이 오면 없는 것이고,
그때는 아래 env 파일 방식으로 간다. 지금 이 문서는 env 파일을 전제로 쓰여 있다.

### 값을 적을 때 조심할 것

**이 파일을 읽는 쪽이 둘이고 규칙이 다르다.** systemd 252 와 bash 로 각각 재봤다.

| 값에 든 것 | **앱에 들어갈 때**(systemd `EnvironmentFile=`) | 확인 명령을 돌릴 때(`set -a; . 파일`) |
| --- | --- | --- |
| `+` `/` `=` `%` | 그대로 | 그대로 |
| `$` | 그대로. systemd 는 안 푼다 | **뒤가 조용히 잘린다** |
| 따옴표 하나 | 그대로 | **파일 읽기가 통째로 깨진다** |
| 줄 끝이 CRLF | **CR 을 떼어낸다** | 값 끝에 안 보이는 바이트가 붙는다 |
| 끝에 공백 | **떼어낸다** | 그대로 남는다 |

**앱 쪽은 systemd 가 다 정리해 준다.** 지금 포털이 주는 인증키는 영문과 숫자 64자라
어느 쪽으로도 안 깨진다.

**문제가 되는 것은 아래 확인 명령 쪽이다.** 파일이 CRLF 로 저장돼 있으면
`sha256sum` 으로 뜬 값이 포털 값과 달라진다. 앱은 멀쩡히 도는데 대조만 어긋나서
키가 틀린 줄 알고 헛짚게 된다. 그럴 때는 `sed -i 's/\r$//'` 로 줄 끝을 먼저 고친다.

## 배포하기

CodePipeline `salmonbus-backend-cd` 에서 `Release change` 를 누른다.

```text
Source           GitHub dev 에서 코드를 받는다
BuildAndTest     salmonbus-backend-build 가 backend/buildspec.yml 을 읽는다
                 ./gradlew clean build --no-daemon 으로 테스트를 전부 다시 돌리고
                 ApiRevision · WorkerRevision 두 벌을 낸다
ManualApproval   사람이 승인 버튼을 누른다
DeployApi        run order 1
DeployWorker     run order 2
```

**API 를 먼저 올리고 확인한 뒤에 Worker 가 나간다.** 두 프로세스를 같이 재시작하지 않는다.

승인 전에 Build 로그 끝에 찍힌 배포판 목록을 본다.

```text
component=api
commit=...
sourceDigest=...        <-- 이 값이 그대로면 그 서비스는 재시작 안 한다
artifactSha256=...      <-- 무결성 확인용
flywayMaxVersion=12
```

## 바뀐 것만 다시 뜬다

`install.sh` 가 새 배포판의 `sourceDigest` 와 지금 도는 판의 값을 대조한다.
다르면 내렸다 올리고, 같으면 그대로 둔다.

`sourceDigest` 는 JAR 이 아니라 **런타임에 들어가는 소스 파일**을 센 값이다.

```text
api     api-app/src/main · api-app/build.gradle
        + common/src/main · common/build.gradle · build.gradle · settings.gradle · gradle-wrapper.properties
worker  worker-app/ 쪽으로 같은 목록
```

**JAR 지문으로는 못 잰다.** `buildInfo()` 가 넣는 `build.time` 때문에 같은 소스를 다시 빌드해도
JAR 의 SHA-256 이 달라진다. 실측으로 확인했다. 소스 지문으로 재면 테스트·문서·프론트만 바뀐 배포에
서비스를 안 건드린다.

## 되돌리기

**대부분 자동으로 된다.** `validate.sh` 가 실패하면 CodeDeploy 가 그 배포 그룹의 직전 성공 판을
다시 배포한다. `salmonbus-api-prod` 와 `salmonbus-worker-prod` 가 따로라
**API 가 실패해도 Worker 는 안 건드린다.** 반대도 같다.

손으로 되돌릴 때는 한쪽만 고른다.

```bash
C=api            # 또는 worker
sudo ln -sfn "$(readlink -f /opt/salmonbus/$C/previous)" /opt/salmonbus/$C/current
sudo systemctl restart salmonbus-$C
curl -s http://127.0.0.1:8082/actuator/info     # api. 지금 도는 판을 확인한다
curl -s http://127.0.0.1:8081/actuator/info     # worker
```

**스키마는 안 돌아온다.** Flyway 마이그레이션은 앞으로만 간다.
새 판이 `V13` 을 돌린 뒤에 되돌리면 코드는 옛 판인데 스키마는 `V13` 이다.
두 앱 다 `ddl-auto: validate` 라 **열을 지우거나 이름을 바꾼 마이그레이션이었으면 되돌린 쪽도 안 뜬다.**

그래서 한 배포에 들어가는 마이그레이션은 **직전 API 와 직전 Worker 가 그대로 뜰 수 있는 것만** 낸다.
열·테이블·제약을 지우거나 이름을 바꾸는 것은, 옛 코드가 그것을 안 쓰게 만든 배포를 먼저 낸 뒤
다음 배포에서 따로 한다.

## 값을 바꿀 때

배포와 상관없이 언제든 할 수 있다. **파일을 고치는 것만으로는 안 바뀐다.**

```bash
sudo vi /etc/salmonbus/worker.env
sudo systemctl restart salmonbus-worker
```

환경변수는 프로세스가 뜰 때 한 번 붙는다.

**`salmonbus-worker` 를 두 벌 띄우지 마라.** 하루 호출 한도가 정확히 두 배로 나간다.
한도 10,000회에 두 노선 수집이 8,556회를 쓴다. `start.sh` 는 `restart` 를 안 쓰고
`stop` 한 뒤 프로세스가 완전히 사라진 것을 보고 나서 `start` 한다.

## 수집을 켜고 끄기

```bash
sudo sed -i 's/^COLLECTION_ENABLED=.*/COLLECTION_ENABLED=true/' /etc/salmonbus/worker.env
sudo systemctl restart salmonbus-worker
```

**켜면 15초마다 Open API 로 실호출이 나간다.** 켠 뒤에는 Worker 배포를 KST 01:00~04:00 에 한다.
다른 시간대는 수집 간격이 15~20초라 Worker 가 내려가 있는 동안 관측이 빈다.

## 계수 파일 갈아 끼우기

```bash
sudo -u salmonbus cp manifest.json weights.safetensors /var/lib/salmonbus/model/current/
sudo sed -i 's/^MODEL_BUNDLE_PROMOTE_ON_START=.*/MODEL_BUNDLE_PROMOTE_ON_START=true/' /etc/salmonbus/worker.env
sudo systemctl restart salmonbus-worker
# 올라간 것을 확인한 뒤 반드시 다시 끈다
sudo sed -i 's/^MODEL_BUNDLE_PROMOTE_ON_START=.*/MODEL_BUNDLE_PROMOTE_ON_START=false/' /etc/salmonbus/worker.env
```

**켠 채로 두면 배포로 재기동할 때마다 디스크에 있는 계수가 올라간다.**
운영 기본값은 `false` 이고 systemd 유닛에도 그렇게 박혀 있다.

## 값을 안 찍고 확인하기

```bash
stat -c '%a' /etc/salmonbus/worker.env                          # 600 이어야 한다
grep -c '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env          # 1 이어야 한다
grep '^GBIS_SERVICE_KEY=' /etc/salmonbus/worker.env | cut -d= -f2- | tr -d '\n' | sha256sum | cut -c1-8
```

마지막 줄의 앞 8자를 포털 화면의 값과 대조한다. `grep -c` 의 `-c` 를 빼면 값이 그대로 찍힌다.

**앱이 떴다는 것은 키가 맞다는 증거가 못 된다.** 틀린 키로도 4초에 뜨고 health 가 200 으로 온다.
게다가 노선 버전이 없는 DB 에서는 수집이 Open API 를 부르지도 않아서 오류도 안 난다.

## 상태 보기

```bash
systemctl status salmonbus-api salmonbus-worker
journalctl -u salmonbus-worker -n 100 --no-pager

curl -s http://127.0.0.1:8082/actuator/health    # api
curl -s http://127.0.0.1:8082/actuator/info      # component · commit · sourcedigest
curl -s http://127.0.0.1:8081/actuator/health    # worker
curl -s http://127.0.0.1:8081/actuator/info
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/v1/routes
```

**`/actuator/health/readiness` 를 쓰지 마라.** DB 가 끊겨도 200 `UP` 이 온다.
그 그룹에는 `readinessState` 하나만 들어 있다. DB 까지 보는 것은 `/actuator/health` 쪽이고
끊기면 503 `DOWN` 이 온다. `validate.sh` 도 그쪽을 본다.

**수집이 실제로 도는지는 health 가 말해주지 않는다.** 지금 Worker 의 health 에는
`db` · `diskSpace` · `ping` · `ssl` 같은 기본 항목뿐이라 API 와 응답이 같다.
수집을 켠 뒤에는 `observation_batch` 의 최근 행을 보고 판단한다.

## 훅 스크립트를 고칠 때

**`set -x` 를 쓰지 마라.** 훅 로그가 인스턴스에 남는다. env 값이 찍히면 파일 권한이 소용없다.

AWS 없이 돌려볼 수 있다. 도커만 있으면 된다.

```bash
bash backend/deploy/rehearsal/run.sh
```

리눅스 컨테이너를 띄워 `systemctl` · `curl` · `java` 를 흉내로 바꿔 끼우고 배포를 여러 번 돌린다.
첫 배포, api 만 바뀐 배포, 아무것도 안 바뀐 배포, 배포끼리 겹칠 때, 옛 프로세스가 응답할 때,
health 가 안 오를 때, 그리고 되돌리기까지다. 검사 34개가 돈다.

흉내는 **모르는 입력에 실패한다.** `systemctl` 이 모르는 명령을 받거나 `curl` 이 모르는 주소를
받으면 거기서 멈춘다. 훅이 포트나 경로를 틀리면 예행연습이 통과하지 않는다.

`digest.sh` 의 `source_digest` 는 `buildspec.yml` 에 있는 것과 같은 함수다. **한쪽을 고치면 둘 다 고친다.**

예행연습의 `systemctl` 은 흉내라서 systemd 가 실제로 유닛을 읽는 것은 못 본다.
그쪽은 systemd 252 를 컨테이너에 띄워 따로 쟀고, 아래 아홉이 확인됐다.

```text
유닛이 systemd-analyze verify 를 통과한다
salmonbus 사용자로 뜬다
0600 root:root 인 /etc/salmonbus/api.env 가 읽힌다
판별용 release.env 도 같이 읽힌다
MANAGEMENT_SERVER_PORT 가 유닛에서 들어간다
current 바로가기의 JAR 로 뜬다
0 이 아닌 코드로 죽으면 Restart=on-failure 가 다시 띄운다
143 으로 끝나면 SuccessExitStatus=143 이 성공으로 본다
```
