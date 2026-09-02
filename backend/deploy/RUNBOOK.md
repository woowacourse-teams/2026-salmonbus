# 배포와 복구 절차

담당자가 아닌 사람이 이 문서만 보고 배포와 되돌리기를 할 수 있어야 한다.

## 무엇이 어디 있나

```
/opt/salmonbus/releases/<커밋7자>-<빌드번호>/   판마다 따로 쌓인다. 셋만 남기고 지운다
/opt/salmonbus/current                        지금 도는 판을 가리키는 바로가기
/opt/salmonbus/previous                       직전 판을 가리키는 바로가기
/etc/salmonbus/api.env                        api-app 이 읽는 값
/etc/salmonbus/worker.env                     worker-app 이 읽는 값
```

**배포는 `/opt/salmonbus/` 만 갈아엎고 `/etc/salmonbus/` 는 손대지 않는다.**
`appspec.yml` 의 `file_exists_behavior` 가 `OVERWRITE` 라 목적지 안의 파일은 덮어쓴다.
그래서 배포가 지우면 안 되는 것을 목적지 안에 두지 않는다. 계수 파일도 같다.

## 최초 1회. 서버에서 사람이 한다

```bash
sudo mkdir -p /opt/salmonbus /etc/salmonbus
sudo useradd -r -s /sbin/nologin salmonbus

sudo install -m 600 -o root -g root /dev/null /etc/salmonbus/api.env
sudo install -m 600 -o root -g root /dev/null /etc/salmonbus/worker.env
sudo vi /etc/salmonbus/api.env       # DB_URL · DB_USERNAME · DB_PASSWORD
sudo vi /etc/salmonbus/worker.env    # 위 셋 + GBIS_SERVICE_KEY · COLLECTION_ENABLED
```

**`api.env` 에 `GBIS_SERVICE_KEY` 를 넣지 않는다.** `api-app` 은 그 값을 읽는 자리가 없다.
빌드된 JAR 안에 `gbis` 라는 글자가 0개다. 넣으면 안 쓰는 곳에 비밀을 퍼뜨리는 것이다.

자바 21 과 CodeDeploy agent 가 있어야 한다. 없으면 `preflight.sh` 가 배포를 세운다.

systemd 유닛은 배포가 알아서 넣는다. 손으로 넣을 필요가 없다.

### 값을 적을 때 조심할 것

env 파일 값은 셸이 읽는 규칙을 탄다. 실측으로 확인한 것이다.

| 값에 든 것 | 결과 |
| --- | --- |
| `+` `/` `=` `%` | 그대로 간다. 공공데이터포털 인증키는 여기 해당한다 |
| `$` | **뒤가 조용히 잘린다** |
| 따옴표 | 파일 읽기가 통째로 깨진다 |
| 줄 끝이 CRLF | 값 끝에 안 보이는 바이트가 붙는다. 13자가 14자가 된다 |

`vi` 로 직접 치면 안 생기고, 다른 데서 붙여넣을 때 생긴다.

## 배포하기

CodePipeline 화면에서 `Release change` 를 누른다. 단계가 넷이다.

```
Source          GitHub 에서 코드를 받는다
Build           CodeBuild 가 backend/buildspec.yml 을 읽고 테스트와 빌드를 돌린다
ManualApproval   사람이 승인 버튼을 누른다
Deploy          CodeDeploy 가 EC2 에 올린다
```

**승인 전에 Build 로그의 마지막 부분을 본다.** `release-manifest.txt` 가 찍혀 있다.

```
commit=...          이번에 나가는 커밋
apiDigest=...       api-app 의 내용 지문
workerDigest=...    worker-app 의 내용 지문
```

## 바뀐 것만 다시 뜬다

`install.sh` 가 새 목록과 지금 도는 판의 목록을 대조해서, **지문이 다른 서비스만** 다시 띄운다.

```
api 만 고친 배포     salmonbus-api 만 재시작. 수집이 안 끊긴다
common 을 고친 배포   둘 다 재시작
아무것도 안 바뀌면    둘 다 그대로 둔다. 죽어 있으면 올린다
```

지문은 JAR 을 통째로 센 값이 아니다. **`META-INF/build-info.properties` 를 빼고 센다.**
그 안에 빌드 시각이 들어 있어서, 같은 커밋을 두 번 빌드해도 JAR 지문이 달라진다.
빼고 세면 재현돼서 대조가 구실을 한다.

## 되돌리기

**대부분은 자동으로 된다.** `validate.sh` 가 실패하면 CodeDeploy 가 직전 성공 판을 다시 배포한다.
배포 그룹에 `배포 실패 시 롤백` 이 켜져 있어야 한다.

손으로 되돌릴 때는 이렇게 한다.

```bash
sudo ln -sfn "$(readlink -f /opt/salmonbus/previous)" /opt/salmonbus/current
sudo systemctl restart salmonbus-api salmonbus-worker
curl -s http://127.0.0.1:8080/actuator/health
curl -s http://127.0.0.1:8081/actuator/health
```

**스키마는 안 돌아온다.** Flyway 마이그레이션은 앞으로만 간다.
새 판이 `V13` 을 돌린 뒤에 되돌리면 코드는 옛 판인데 스키마는 `V13` 이다.
두 앱 다 `ddl-auto: validate` 라, 열을 지우거나 이름을 바꾼 마이그레이션이었으면 **되돌린 쪽도 안 뜬다.**
그런 마이그레이션을 낼 때는 되돌리기가 구실을 못 한다고 보고 배포해야 한다.

## 값을 바꿀 때

배포와 상관없이 언제든 할 수 있다. **파일을 고치는 것만으로는 안 바뀐다.**

```bash
sudo vi /etc/salmonbus/worker.env
sudo systemctl restart salmonbus-worker
```

환경변수는 프로세스가 뜰 때 한 번 붙는다. 다시 띄워야 새 값을 쓴다.

**`worker-app` 을 두 벌 띄우지 마라.** 하루 호출 한도가 정확히 두 배로 나간다.
한도 10,000회에 두 노선 수집이 8,556회를 쓴다. `systemctl restart` 는 내린 뒤에 올려서 안 겹친다.

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
curl -s http://127.0.0.1:8080/actuator/health      # api. 밖에서도 열린다
curl -s http://127.0.0.1:8081/actuator/health      # worker. 이 기계 안에서만 열린다
cat /opt/salmonbus/current/release-manifest.txt    # 지금 도는 커밋
```

**`/actuator/health/readiness` 를 쓰지 마라.** DB 가 끊겨도 200 `UP` 이 온다.
그 그룹에는 `readinessState` 하나만 들어 있다. DB 까지 보는 것은 `/actuator/health` 쪽이고
끊기면 503 `DOWN` 이 온다. `validate.sh` 도 그쪽을 본다.

## 훅 스크립트를 고칠 때

**`set -x` 를 쓰지 마라.** 훅 로그가 인스턴스에 남는다. env 값이 찍히면 파일 권한이 소용없다.

AWS 없이 돌려볼 수 있다. 도커만 있으면 된다.

```bash
bash backend/deploy/rehearsal/run.sh
```

리눅스 컨테이너를 띄워 `systemctl` · `curl` · `java` 를 흉내로 바꿔 끼우고 배포를 네 번 돌린다.
첫 배포, api 만 바뀐 배포, 아무것도 안 바뀐 배포, health 가 안 오르는 배포다. 검사 28개가 돈다.
