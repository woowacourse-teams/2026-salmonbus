# 노선 채팅 MongoDB 운영 절차

노선 채팅(3330·1650)의 메시지는 api와 같은 EC2 안의 MongoDB에 저장한다.
담당자가 아니어도 이 문서만 보고 설치, 켜기와 끄기, 장애 대응, 삭제 요청 처리, 제거를 할 수 있어야 한다.
설치 절차는 2026-09-30 운영 서버에 실행한 기록을 바탕으로 정리했다. 설정 파일 내용은 그때 서버에 넣은 것과 같다.

배포·롤백·환경변수 파일의 일반 규칙은 [`../RUNBOOK.md`](../RUNBOOK.md)를 따른다.

## 무엇이 어디에 있나

```text
mongod 8.0.32        패키지 mongodb-org-server. systemd 유닛 mongod.service, 실행 사용자 mongod
mongosh 2.12.0       패키지 mongodb-mongosh. 관리 명령을 넣는 셸
127.0.0.1:27017      루프백에만 바인딩한다. 서버 밖에서는 접속할 수 없다
/etc/mongod.conf     설정. RPM이 준 원본은 /etc/mongod.conf.rpm-default
/var/lib/mongo       데이터
/var/log/mongodb/    로그 mongod.log. logrotate가 매일 회전한다
/swapfile            swap 2 GiB. vm.swappiness 1
```

| 항목 | 값 |
| --- | --- |
| DB | `salmonbus_chat` |
| 컬렉션 | `messages` |
| 인덱스 | `room_client_message_unique` `{roomId: 1, clientMessageId: 1}` 고유, `room_recent` `{roomId: 1, createdAt: 1, _id: 1}` |
| 보존 기한 | 없다. TTL 인덱스를 두지 않는다 |
| 백업 | 없다 |
| 관리자 계정 | `admin`. `admin` DB의 `root` 역할. 설치와 유지보수 때 사람이 쓴다 |
| 앱 계정 | `chat`. `salmonbus_chat` DB의 `readWrite` 역할. api가 쓴다 |
| 메모리 | WiredTiger 캐시 0.25 GB. cgroup `MemoryHigh=320M`, `MemoryMax=400M`, `OOMScoreAdjust=500`. 비정상 종료하면 5초 뒤 다시 뜬다 |
| 버전 고정 | `/etc/dnf/dnf.conf`의 `exclude=mongodb-org*,mongodb-mongosh` |
| 앱 연결 | `/etc/salmonbus/api.env`의 `CHAT_MONGODB_URI`. 스위치는 `CHAT_ENABLED` |
| api 경로 | `GET /api/chat/rooms/{routeId}` 켜짐 확인, `/api/chat/rooms/{routeId}/stream` WebSocket. 노선은 3330(`204000057`)과 1650(`234000050`)뿐이다 |

**비밀번호는 레포와 이 문서에 적지 않는다.** 앱 계정 비밀번호는 `api.env`의 `CHAT_MONGODB_URI` 안에만 있고,
관리자 비밀번호는 담당자가 따로 보관한다.

**인덱스는 앱이 만들지 않는다.** 컬렉션과 인덱스는 관리자가 설치 6단계에서 만든다.

## 이 디렉터리의 파일

| 파일 | 서버 경로 | 내용 |
| --- | --- | --- |
| `mongod.conf` | `/etc/mongod.conf` | 파일 로그와 `logRotate: reopen`, 캐시 0.25 GB, `127.0.0.1`, 연결 상한 50, 인증 |
| `mongod.service.d/salmonbus.conf` | `/etc/systemd/system/mongod.service.d/salmonbus.conf` | 메모리 상한, OOM 우선순위, 자동 재시작 |
| `logrotate.d/mongod` | `/etc/logrotate.d/mongod` | 매일 회전, 7개 보관, `SIGUSR1`로 새 로그 파일을 열게 한다 |
| `mongodb-org-8.0.repo` | `/etc/yum.repos.d/mongodb-org-8.0.repo` | MongoDB 공식 저장소(Amazon Linux 2023, aarch64) |
| `90-salmonbus-swap.conf` | `/etc/sysctl.d/90-salmonbus-swap.conf` | `vm.swappiness = 1` |
| `init-chat-db.js` | 서버에 두지 않는다. 쓸 때 `/tmp`에 옮겨 실행하고 지운다 | 컬렉션과 인덱스 생성 |

**이 디렉터리는 배포로 나가지 않는다.** `backend/buildspec.yml`의 `pack`은 JAR와 `deploy/appspec-*.yml`, `deploy/scripts/*.sh`,
`deploy/systemd/salmonbus-*.service`만 배포 패키지에 넣는다. 여기 있는 파일은 사람이 아래 절차대로 서버에 넣는다.
소스 지문 입력(`deploy/runtime-source-inputs.sh`)도 아니어서 이 디렉터리만 바꾼 배포는 api를 재시작하지 않는다.

설치 절차의 heredoc 내용은 이 파일들과 같다. 파일을 고치면 이 문서의 해당 블록도 같이 고친다.
서버에 넣은 뒤에는 아래 명령으로 레포 파일과 같은지 본다.

```bash
sha256sum /etc/mongod.conf /etc/systemd/system/mongod.service.d/salmonbus.conf /etc/logrotate.d/mongod /etc/yum.repos.d/mongodb-org-8.0.repo /etc/sysctl.d/90-salmonbus-swap.conf
```

로컬 레포의 `backend/deploy/chat`에서 같은 순서로 해시를 구한다. 두 출력의 해시가 줄마다 같아야 한다.

```bash
shasum -a 256 mongod.conf mongod.service.d/salmonbus.conf logrotate.d/mongod mongodb-org-8.0.repo 90-salmonbus-swap.conf
```

## SSM 터미널에서 주의할 점

- 모든 명령은 root 셸(`sudo -i`)에서 실행한다.
- **mongosh 명령은 한 줄씩 넣는다.** mongosh 프롬프트에 여러 줄을 한꺼번에 붙이면 `use` 다음 줄이 실행되지 않았다.
  이 문서의 mongosh 명령은 모두 `db.getSiblingDB(...)`를 쓴 한 줄이다. 한 줄 넣고 결과를 본 뒤 다음 줄을 넣는다.
  bash 블록은 한 번에 붙여도 된다.
- **화면이 아니라 서버에 저장된 값으로 확인한다.** SSM 터미널은 줄을 다시 그리면서 글자가 겹쳐 보일 수 있다.
  계정과 인덱스를 만든 뒤에는 설치 7단계의 조회로 실제 값을 본다.
- **비밀번호는 `passwordPrompt()`(mongosh)와 `read -rs`(bash)로만 받는다.** 명령줄에 쓰면 화면, 셸 기록, 세션 로그에 남는다.
- **비밀번호를 묻는 명령은 뒤 줄과 함께 붙이지 않는다.** `read -rs`나 `mongosh -u admin` 뒤에 붙인 줄이 비밀번호 입력으로 들어갈 수 있다.
- Amazon Linux의 "A newer release of Amazon Linux is available" 안내가 나와도 이 작업 중에는 `dnf upgrade`를 하지 않는다.
  커널과 다른 패키지까지 바뀐다.

## 설치

새 서버에 다시 설치할 때 이 순서로 한다. 2026-09-30에는 logrotate 설정을 맨 끝에 넣었고, 여기서는 4단계에 모았다.
한가한 시간에 하고 20분쯤 걸린다. **이 절차 중에 api·worker는 재시작하지 않는다.**

### 1. 사전 확인

```bash
uname -r; df -hT /; free -m; swapon --show; cat /proc/sys/vm/swappiness
systemctl is-active salmonbus-api salmonbus-worker
ls /etc/yum.repos.d/; command -v mongod mongosh || echo "mongo 없음"
```

- 커널이 6.19~7.0.13 구간이면 멈춘다. MongoDB 8.0이 이 구간의 커널을 거부한다.
- 루트 파일시스템이 XFS면 2단계를 그대로 한다.
- swap이 이미 있으면 2단계를 할지 먼저 정한다.
- api·worker는 `active`여야 한다.

2026-09-30 값은 커널 6.18.41, XFS 30G(여유 26G), 메모리 1,841 MiB(가용 627 MiB), swap 없음, swappiness 60이었다.

### 2. swap 2 GiB와 swappiness 1

`/swapfile`이 없는지 먼저 본다.

```bash
test -e /swapfile && echo "이미 /swapfile 있음. 멈춘다" || echo "진행"
```

`진행`이 나오면 아래를 붙인다. 괄호 안의 명령은 하나가 실패하면 거기서 멈춘다.

```bash
(
set -e
cp /etc/fstab /etc/fstab.bak.$(date +%Y%m%d%H%M)
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
printf 'vm.swappiness = 1\n' > /etc/sysctl.d/90-salmonbus-swap.conf
sysctl -p /etc/sysctl.d/90-salmonbus-swap.conf
)
swapon --show; free -m; cat /proc/sys/vm/swappiness; tail -2 /etc/fstab
```

- XFS는 커널 4.18부터 `fallocate`로 만든 swap 파일을 쓸 수 있다. 2 GiB를 디스크에 모두 쓰는 `dd`보다 빨리 끝난다.
- `swapon`이 "swapfile has holes"로 실패하면 `rm /swapfile` 뒤 위 블록의 `fallocate` 줄을
  `dd if=/dev/zero of=/swapfile bs=1M count=2048`로 바꿔 다시 붙인다.
- 끝에 `/swapfile file 2G`와 swappiness `1`이 보여야 한다. swappiness 1은 메모리가 정말 모자랄 때만 swap을 쓰게 한다.

### 3. 저장소 등록과 설치

```bash
cat > /etc/yum.repos.d/mongodb-org-8.0.repo <<'REPO'
[mongodb-org-8.0]
name=MongoDB Repository
baseurl=https://repo.mongodb.org/yum/amazon/2023/mongodb-org/8.0/aarch64/
gpgcheck=1
enabled=1
gpgkey=https://pgp.mongodb.com/server-8.0.asc
REPO
dnf --showduplicates list mongodb-org-server 2>/dev/null | tail -3
dnf install -y mongodb-org-server-8.0.32 mongodb-mongosh
rpm -q mongodb-org-server mongodb-mongosh
systemctl is-enabled mongod; systemctl is-active mongod
```

- 서버와 mongosh만 설치한다. mongos가 들어 있는 메타패키지 `mongodb-org`와 백업 도구 `mongodb-database-tools`는 설치하지 않는다.
- 8.0.32가 목록에 없으면 설치가 실패한다. 그때는 멈추고 버전을 다시 정한다.
- 설치 직후 mongod는 `enabled`, `inactive`다. 기본 설정에는 캐시 상한과 인증이 없으므로 4단계 설정을 넣은 뒤 켠다.
- RPM 유닛은 `Type`을 지정하지 않고(simple) `MONGODB_CONFIG_OVERRIDE_NOFORK=1`로 포그라운드 실행을 강제한다.
  그래서 설정에 `fork`를 넣지 않는다. 유닛에 `Restart=`가 없어서 4단계 드롭인에서 준다.
- 2026-09-30에는 `mongodb-org-server-8.0.32`와 `mongodb-mongosh-2.12.0`이 설치됐다.

### 4. 설정 파일, 버전 고정, 기동

```bash
cp -n /etc/mongod.conf /etc/mongod.conf.rpm-default
cat > /etc/mongod.conf <<'CONF'
systemLog:
  destination: file
  logAppend: true
  logRotate: reopen
  path: /var/log/mongodb/mongod.log
storage:
  dbPath: /var/lib/mongo
  wiredTiger:
    engineConfig:
      cacheSizeGB: 0.25
processManagement:
  timeZoneInfo: /usr/share/zoneinfo
net:
  port: 27017
  bindIp: 127.0.0.1
  maxIncomingConnections: 50
security:
  authorization: enabled
CONF
mkdir -p /etc/systemd/system/mongod.service.d
cat > /etc/systemd/system/mongod.service.d/salmonbus.conf <<'UNIT'
[Service]
MemoryHigh=320M
MemoryMax=400M
OOMScoreAdjust=500
Restart=on-failure
RestartSec=5
UNIT
cat > /etc/logrotate.d/mongod <<'ROT'
/var/log/mongodb/mongod.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    create 0640 mongod mongod
    sharedscripts
    postrotate
        /usr/bin/pkill -USR1 -x mongod || true
    endscript
}
ROT
grep -n '^exclude' /etc/dnf/dnf.conf || echo 'exclude=mongodb-org*,mongodb-mongosh' >> /etc/dnf/dnf.conf
systemctl daemon-reload && systemctl start mongod
sleep 5
systemctl is-active mongod
systemctl show mongod -p MemoryHigh -p MemoryMax -p OOMScoreAdjust -p Restart
ss -ltnp | grep 27017
cat /sys/fs/cgroup/system.slice/mongod.service/memory.current
free -m
tail -1 /etc/dnf/dnf.conf
systemctl is-active logrotate.timer; logrotate -d /etc/logrotate.d/mongod 2>&1 | tail -4
```

아래 값이 나와야 한다.

```text
systemctl is-active    active
systemctl show         MemoryHigh=335544320  MemoryMax=419430400  OOMScoreAdjust=500  Restart=on-failure
ss                     127.0.0.1:27017 에서만 LISTEN
memory.current         바이트 단위. 2026-09-30 기동 직후 182689792(약 174 MiB)
dnf.conf 마지막 줄      exclude=mongodb-org*,mongodb-mongosh
logrotate.timer        active
```

- `grep -n '^exclude'`가 줄을 찍으면 이미 `exclude`가 있어서 추가하지 않은 것이다. 그 줄 끝에 `,mongodb-org*,mongodb-mongosh`를 붙여 한 줄로 합친다.
- `active`가 아니면 더 진행하지 않고 `journalctl -u mongod -n 30 --no-pager`와 `tail -n 30 /var/log/mongodb/mongod.log`를 본다.
- **`cacheSizeGB`는 반드시 적는다.** mongod는 캐시 기본값을 정할 때 systemd `MemoryMax`를 반영하지 않는다.
- `MemoryHigh`를 넘으면 커널이 mongod의 메모리를 회수하며 늦춘다. `MemoryMax`를 넘으면 mongod cgroup 안에서만 OOM이 난다.
  `OOMScoreAdjust=500`은 서버 전체 메모리가 모자랄 때 커널이 api·worker보다 mongod를 먼저 고르게 한다.
- `logRotate: reopen`이라서 logrotate가 파일 이름을 바꾸고 `SIGUSR1`을 보내면 mongod가 새 파일을 연다.
  RPM은 logrotate 설정을 주지 않는다. 회전은 호스트의 `logrotate.timer`가 매일 돌린다.

### 5. 관리자·앱 계정

두 비밀번호는 서버가 아니라 로컬에서 만들고 비밀번호 관리자에 먼저 저장해 둔다.
앱 비밀번호는 접속 주소(URI)에 그대로 들어간다. 로컬에서 `openssl rand -hex 24`처럼 16진수로 만들면 인코딩할 필요가 없다.
다른 문자를 쓰면 8단계에서 `@ : / ? # [ ] %`를 퍼센트 인코딩한 값을 입력한다.

관리자 계정은 인증 없이 접속해 만든다. 사용자가 하나도 없을 때만 되는 localhost 예외다.

```bash
mongosh --quiet
```

프롬프트가 뜨면 아래 한 줄을 넣는다. `Enter password`에 관리자 비밀번호를 넣는다. 화면에는 보이지 않는다.

```js
db.getSiblingDB("admin").createUser({user: "admin", pwd: passwordPrompt(), roles: [{role: "root", db: "admin"}]})
```

`{ ok: 1 }`이 나오면 `exit`로 나온다. 이제부터는 인증해야 데이터를 다룰 수 있다.

앱 계정은 관리자로 접속해 만든다. 관리자 비밀번호를 먼저 묻는다.

```bash
mongosh --quiet -u admin --authenticationDatabase admin
```

아래 한 줄을 넣고 `Enter password`에 앱 비밀번호를 넣는다. 8단계에서 다시 입력한다.

```js
db.getSiblingDB("salmonbus_chat").createUser({user: "chat", pwd: passwordPrompt(), roles: [{role: "readWrite", db: "salmonbus_chat"}]})
```

mongosh는 닫지 않고 6단계로 간다.

### 6. 컬렉션과 인덱스

두 방법 중 하나로 만든다. 결과는 같다. `mongo:8.0`(8.0.32) 컨테이너에서 두 방법의 `getIndexes()` 출력이 같은 것을 확인했다.

**가. 한 줄씩 넣기.** 5단계의 관리자 mongosh에서 한 줄씩 넣는다.

① 컬렉션

```js
db.getSiblingDB("salmonbus_chat").createCollection("messages")
```

② 같은 메시지를 다시 보냈을 때 한 번만 저장하게 하는 고유 인덱스

```js
db.getSiblingDB("salmonbus_chat").messages.createIndex({roomId: 1, clientMessageId: 1}, {unique: true, name: "room_client_message_unique"})
```

③ 최근 메시지 조회 인덱스

```js
db.getSiblingDB("salmonbus_chat").messages.createIndex({roomId: 1, createdAt: 1, _id: 1}, {name: "room_recent"})
```

①은 `{ ok: 1 }`, ②·③은 인덱스 이름이 나오면 된다.

**나. `init-chat-db.js`로 만들기.** 아래 "init-chat-db.js" 절을 따른다.

### 7. 확인

관리자 mongosh에서 한 줄씩 넣는다.

```js
db.getSiblingDB("salmonbus_chat").messages.getIndexes().map(i => JSON.stringify(i.key))
```

```text
[ '{"_id":1}', '{"roomId":1,"clientMessageId":1}', '{"roomId":1,"createdAt":1,"_id":1}' ]
```

```js
db.getSiblingDB("salmonbus_chat").messages.getIndexes().map(i => i.name + (i.unique ? " unique" : ""))
```

```text
[ '_id_', 'room_client_message_unique unique', 'room_recent' ]
```

```js
db.getSiblingDB("admin").getUser("admin").roles
```

```text
[ { role: 'root', db: 'admin' } ]
```

```js
db.getSiblingDB("salmonbus_chat").getUser("chat").roles
```

```text
[ { role: 'readWrite', db: 'salmonbus_chat' } ]
```

출력은 여러 줄로 나뉘어 찍힐 수 있다. 인덱스 순서는 만든 순서를 따른다. 확인이 끝나면 `exit`로 나온다.

앱 계정으로 접속되는지 bash에서 본다. 앱 비밀번호를 묻는다.

```bash
mongosh --quiet -u chat --authenticationDatabase salmonbus_chat salmonbus_chat --eval 'db.runCommand({connectionStatus: 1}).authInfo.authenticatedUsers'
```

`[ { user: 'chat', db: 'salmonbus_chat' } ]`이 나오면 된다. 이어서 메모리를 기록해 둔다.

```bash
cat /sys/fs/cgroup/system.slice/mongod.service/memory.current; free -m
```

2026-09-30 설치를 마친 뒤 mongod는 약 159 MiB, 서버 가용 메모리는 517 MiB, swap 사용은 0이었다.

#### 인덱스를 잘못 만들었을 때

키나 옵션이 다르면 채팅이 꺼져 있을 때 지우고 다시 만든다.
고유 인덱스가 없는 동안 들어온 중복 메시지가 있으면 고유 인덱스를 다시 만들 수 없기 때문이다.

```js
db.getSiblingDB("salmonbus_chat").messages.dropIndex("room_client_message_unique")
```

그다음 6단계 ②나 `init-chat-db.js`로 다시 만들고 7단계로 확인한다. `room_recent`도 같은 방법이다.

### 8. api.env에 접속 주소 넣기

`CHAT_MONGODB_URI` 한 줄만 넣는다. **`CHAT_ENABLED`는 넣지 않고 api도 재시작하지 않는다.**
이 값은 다음에 api가 다시 뜰 때 읽히고, 스위치가 꺼져 있으면 쓰이지 않는다.

① 기존 줄이 없는지 본다. `0`이어야 한다. 아니면 멈춘다.

```bash
grep -c '^CHAT_' /etc/salmonbus/api.env
```

② 파일을 백업하고 비밀번호를 받는다. 이 블록만 붙이고, `채팅 앱 비밀번호:`가 뜨면 앱 비밀번호를 넣는다.

```bash
cp -p /etc/salmonbus/api.env /etc/salmonbus/api.env.bak.$(date +%Y%m%d%H%M)
read -rs -p '채팅 앱 비밀번호: ' CHAT_PW; echo
```

③ 줄을 붙이고 확인한다.

```bash
printf 'CHAT_MONGODB_URI=mongodb://chat:%s@127.0.0.1:27017/salmonbus_chat?authSource=salmonbus_chat&maxPoolSize=5&serverSelectionTimeoutMS=2000&connectTimeoutMS=2000&socketTimeoutMS=5000\n' "$CHAT_PW" >> /etc/salmonbus/api.env
unset CHAT_PW
stat -c '%a %U:%G' /etc/salmonbus/api.env
grep '^CHAT_' /etc/salmonbus/api.env | sed 's#//chat:[^@]*@#//chat:****@#'
CHAT_URI="$(grep '^CHAT_MONGODB_URI=' /etc/salmonbus/api.env | cut -d= -f2-)" mongosh --nodb --quiet --eval 'connect(process.env.CHAT_URI).runCommand({ping: 1}).ok'
```

- `stat`은 `600 root:root`여야 한다. 다르면 api 배포의 `preflight.sh`가 멈춘다. `>>`는 권한과 소유자를 바꾸지 않는다.
- 마지막 줄이 `1`이면 파일에 적힌 주소로 접속된다. URI를 환경변수로 넘겨서 비밀번호가 `ps`의 프로세스 인자에 보이지 않는다.
- 인증 오류가 나면 `sed -i '/^CHAT_MONGODB_URI=/d' /etc/salmonbus/api.env`로 줄을 지우고 ②부터 다시 한다.
- URI 옵션은 연결 풀 5개, 서버 선택·연결 2초, 소켓 5초다. mongod가 멈춰도 요청이 오래 묶이지 않게 한다.
  앱에도 연결 풀 5개, 풀 대기 2초, 서버 선택·연결 2초, 소켓 읽기 5초가 기본값으로 있어 URI에서 빠져도 이 값으로 동작한다.
  URI에 적은 값이 있으면 그 값을 쓴다.

`CHAT_ALLOWED_ORIGINS`는 보통 넣지 않는다. 기본값이 운영 Origin(`https://www.salmonbus.com`) 하나다.
다른 Origin도 허용해야 할 때만 넣는다. 넣으면 기본값을 대신하므로 운영 Origin도 함께 적는다.

```text
CHAT_ALLOWED_ORIGINS=https://www.salmonbus.com,https://<추가 Origin>
```

## init-chat-db.js

컬렉션 `messages`와 인덱스 두 개를 만든다. 컬렉션이 이미 있으면 건너뛰고, 같은 인덱스가 이미 있으면 그대로 둔다.
그래서 여러 번 실행해도 된다. 마지막에 인덱스 목록을 찍는다. 같은 이름에 키나 옵션이 다른 인덱스가 있으면
"An existing index has the same name as the requested index" 오류로 멈추고 종료 코드 1을 낸다.
그때는 위 "인덱스를 잘못 만들었을 때"를 따른다.

**이 스크립트가 인덱스의 정본이다.** api-app 시험이 Mongo 컨테이너에서 이 파일을 실제로 실행해 인덱스를 검증한다.
`ChatMongoContainer`가 실행하고 `MongoChatMessageRepositoryIntegrationTest`가 인덱스 이름·키·고유 여부와 TTL이 없는 것을 확인한다.
인덱스를 바꾸면 이 파일, 그 시험, 계약(`contract/chat-v1.md`의 저장 절), 이 문서의 설치 6·7단계를 같은 PR에서 고친다.

서버에는 레포가 없다. 내용을 `/tmp`에 옮겨 실행하고 지운다.

```bash
cat > /tmp/init-chat-db.js <<'JS'
const chat = db.getSiblingDB("salmonbus_chat");

if (!chat.getCollectionNames().includes("messages")) {
  chat.createCollection("messages");
}

chat.messages.createIndex(
  { roomId: 1, clientMessageId: 1 },
  { unique: true, name: "room_client_message_unique" }
);
chat.messages.createIndex(
  { roomId: 1, createdAt: 1, _id: 1 },
  { name: "room_recent" }
);

printjson(chat.messages.getIndexes());
JS
sha256sum /tmp/init-chat-db.js
```

해시가 로컬 레포의 `shasum -a 256 backend/deploy/chat/init-chat-db.js`와 같은지 본다. 같으면 실행한다. 관리자 비밀번호를 묻는다.

```bash
mongosh --quiet -u admin --authenticationDatabase admin --file /tmp/init-chat-db.js
```

인덱스 세 개(`_id_`, `room_client_message_unique`, `room_recent`)가 찍히면 파일을 지우고 설치 7단계로 확인한다.

```bash
rm /tmp/init-chat-db.js
```

## 켜기 전 공개 게이트

채팅을 켜기 전에 네 가지를 모두 확인한다. 하나라도 안 되면 켜지 않는다.

| 순서 | 확인 | 기준 |
| --- | --- | --- |
| 1 | mongod를 켠 채 배포를 한 번 하며 메모리를 잰다 | 배포 중 swap 사용 100 MiB 미만, mongod `memory.events`의 `oom_kill` 0, 커널 OOM 0, `/board` 지연이 평소와 같음 |
| 2 | CloudFront를 거친 WebSocket | `wss://www.salmonbus.com/api/chat/rooms/204000057/stream`이 101로 열리고, 30초 넘게 유지되고, api를 재시작하면 다시 이어진다 |
| 3 | 끄기 연습 | 아래 "끄기"를 한 번 한다. `CHAT_ENABLED=false` → 재시작 → 채팅 404 → 보드 정상 |
| 4 | 켠 뒤 볼 사람 | 켠 뒤 상태를 볼 사람과 시간이 정해져 있다 |

1의 기준을 넘으면 인스턴스 증설을 검토한다.

2와 3은 채팅이 켜져 있어야 확인할 수 있다. 켜 둔 몇 분 동안은 노선 화면에 채팅이 보인다. 그래서 이 순서로 한다(2026-09-30 결정).

1. 코드를 배포하면서 1을 확인한다. 채팅은 꺼 둔다.
2. 이용자가 적은 시간에 아래 "켜기"대로 켠다.
3. 2를 확인한다.
4. 곧바로 아래 "끄기"대로 끈다. 이것이 3이다.
5. 네 가지를 모두 통과했으면 공개하는 날 다시 켠다.

### 1. 배포 중 메모리

배포를 시작하기 전에 SSM 창을 하나 더 열어 아래 한 줄을 띄워 둔다. 배포가 끝나면 `Ctrl-C`로 멈춘다.
배포 전에 찍힌 몇 줄이 `/board`의 평소 지연이다. api가 재시작하는 몇 초 동안은 `000`이 찍힌다.

```bash
while sleep 5; do printf '%s swap_used_MiB=%s mongod_bytes=%s board=%s\n' "$(date +%T)" "$(free -m | awk '/^Swap:/{print $3}')" "$(cat /sys/fs/cgroup/system.slice/mongod.service/memory.current)" "$(curl -s -o /dev/null -m 5 -w '%{http_code}/%{time_total}s' http://127.0.0.1:8080/api/v1/routes/204000057/board)"; done
```

배포가 끝나면 OOM을 본다.

```bash
grep -E '^(high|oom_kill) ' /sys/fs/cgroup/system.slice/mongod.service/memory.events
journalctl -k --since '1 hour ago' --no-pager | grep -i -E 'out of memory|oom-kill' || echo "커널 OOM 없음"
```

`oom_kill`은 0이어야 한다. `high`는 `MemoryHigh`를 넘어 회수가 일어난 횟수이고 몇 번은 괜찮다.

### 2. CloudFront를 거친 WebSocket

브라우저로 3330 노선 화면을 열고 채팅을 연다. 개발자 도구 Network의 WS 항목에서 상태가 101인지 본다.
30초 넘게 두어도 끊기지 않아야 한다. 서버가 25초마다 ping을 보낸다.
그 상태에서 서버에서 `systemctl restart salmonbus-api`를 하면 채팅이 다시 연결돼야 한다.

브라우저에서 101이 나지 않으면 서버 안에서 먼저 본다. 여기서 101이면 문제는 CloudFront 쪽이다.

```bash
curl -s -i -m 3 --http1.1 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H 'Origin: https://www.salmonbus.com' http://127.0.0.1:8080/api/chat/rooms/204000057/stream | head -1
```

`HTTP/1.1 101`로 시작하면 된다. 3초 뒤 `curl`이 시간 초과로 끝나는 것은 정상이다.

#### CloudFront를 거치면 101이 나지 않을 때

서버 안에서는 101인데 브라우저에서 안 되면 CloudFront 설정을 고친다.

1. 채팅을 먼저 끈다(아래 "끄기").
2. CloudFront 배포에 `/api/chat/*` 경로 동작을 새로 만들고, 순서를 `/api/*`보다 앞에 둔다.
   - 원본: `/api/*`와 같은 api 원본
   - 허용 메서드: GET, HEAD
   - 캐시: 레거시 캐시 설정, 최소·기본·최대 TTL 모두 0
   - 원본에 넘길 헤더: `Sec-WebSocket-Key`, `Sec-WebSocket-Version`, `Sec-WebSocket-Extensions`, `Sec-WebSocket-Protocol`, `Origin`
3. 배포 상태가 반영 완료로 바뀔 때까지 기다린다.
4. 채팅을 다시 켜고 2를 처음부터 확인한다. 끝나면 끈다.

되돌릴 때는 새로 만든 `/api/chat/*` 동작만 지운다. `/api/*` 동작은 건드리지 않는다.

## 켜기

공개 게이트를 통과한 뒤에 한다. api가 재시작하는 몇 초 동안 보드 API가 끊긴다.

```bash
systemctl is-active mongod; grep -c '^CHAT_MONGODB_URI=' /etc/salmonbus/api.env
```

`active`와 `1`이어야 한다. `CHAT_ENABLED=true`여도 `CHAT_MONGODB_URI`가 비어 있거나 형식이 틀리면 api는 채팅만 끈 채로 뜨고
`journalctl -u salmonbus-api`에 WARN 한 줄을 남긴다. 이때 채팅 GET은 404다. WARN에는 URI 값을 찍지 않는다.

```bash
if grep -q '^CHAT_ENABLED=' /etc/salmonbus/api.env; then sed -i 's/^CHAT_ENABLED=.*/CHAT_ENABLED=true/' /etc/salmonbus/api.env; else echo 'CHAT_ENABLED=true' >> /etc/salmonbus/api.env; fi
stat -c '%a %U:%G' /etc/salmonbus/api.env; grep '^CHAT_ENABLED=' /etc/salmonbus/api.env
systemctl restart salmonbus-api
```

**수동 재시작은 `validate.sh`를 거치지 않는다.** 아래로 직접 확인한다. 끄기에서도 같은 블록을 쓴다.

```bash
for i in $(seq 1 40); do curl -fsS -m 5 http://127.0.0.1:8080/readyz && break; sleep 3; done; echo
curl -s -m 5 http://127.0.0.1:8082/actuator/health; echo
curl -s -o /dev/null -m 5 -w 'board %{http_code}\n' http://127.0.0.1:8080/api/v1/routes/204000057/board
curl -s -o /dev/null -m 5 -w 'chat 3330 %{http_code}\n' http://127.0.0.1:8080/api/chat/rooms/204000057
curl -s -o /dev/null -m 5 -w 'chat 1650 %{http_code}\n' http://127.0.0.1:8080/api/chat/rooms/234000050
```

```text
/readyz      status가 UP
health       status가 UP
board        평소와 같은 상태 코드
chat         켜면 200, 끄면 404
```

- `stat`은 `600 root:root`여야 한다. `sed -i`는 권한과 소유자를 유지한다.
- 채팅 GET은 MongoDB를 읽지 않는다. MongoDB까지 닿는지는 브라우저에서 채팅을 열어 본다.
  "지금은 채팅을 쓸 수 없어요"가 뜨지 않고 최근 메시지가 뜨면 된다.

## 끄기

```bash
sed -i 's/^CHAT_ENABLED=.*/CHAT_ENABLED=false/' /etc/salmonbus/api.env
stat -c '%a %U:%G' /etc/salmonbus/api.env; grep '^CHAT_ENABLED=' /etc/salmonbus/api.env
systemctl restart salmonbus-api
```

재시작 뒤 "켜기"의 확인 블록을 돌린다. 채팅은 두 노선 모두 404, 보드는 평소와 같아야 한다.

- 프론트는 되돌리지 않는다. 채팅 GET이 404면 화면이 채팅을 스스로 숨긴다.
- 꺼지면 api는 MongoDB에 연결하지 않는다. mongod는 그대로 두어도 된다.
- `CHAT_ENABLED` 줄을 지워도 꺼진다. 기본값이 `false`다.

## 상태 확인

```bash
systemctl status mongod --no-pager
journalctl -u mongod -n 50 --no-pager
tail -n 50 /var/log/mongodb/mongod.log
cat /sys/fs/cgroup/system.slice/mongod.service/memory.current /sys/fs/cgroup/system.slice/mongod.service/memory.peak
cat /sys/fs/cgroup/system.slice/mongod.service/memory.events
free -m; swapon --show
journalctl -k --no-pager | grep -i -E 'out of memory|oom-kill' | tail -5
```

- mongod의 로그는 `/var/log/mongodb/mongod.log`에 있다. `journalctl -u mongod`에는 주로 systemd의 기동·종료 기록이 남는다.
- `memory.current`와 `memory.peak`는 바이트다. 설치를 마친 뒤 `memory.current`는 약 159 MiB였다(2026-09-30).
- `memory.events`의 `high`는 `MemoryHigh`(320M)를 넘어 회수가 일어난 횟수, `oom_kill`은 mongod cgroup 안에서 OOM으로 죽은 횟수다.
  `oom_kill`이 늘었으면 400M 상한에 걸린 것이다.
- `free -m`의 swap 사용은 설치 직후 0이었다. 계속 늘면 서버 메모리가 모자란 것이다.

채팅이 켜져 있으면 api가 1분마다 요약 한 줄을 남긴다. 세션도 활동도 없는 1분은 남기지 않는다.

```bash
journalctl -u salmonbus-api --since '30 min ago' --no-pager | grep '채팅 1분 요약' | tail -5
```

```text
방            접속자가 있는 노선 수
세션          열린 WebSocket 수
보냄          새로 저장한 메시지 수
거절          입력 오류, 속도 제한, 시작 시간 초과 같은 오류 응답 수
저장오류      MongoDB에 닿지 못한 수
연결초과      연결 수 상한으로 닫은 수(1013)
마지막저장오류 그 1분 동안 마지막 저장 오류의 예외 이름
```

본문, 메시지 ID, 세션 ID, IP는 남기지 않는다.

저장된 메시지 수는 관리자 비밀번호를 넣고 본다.

```bash
mongosh --quiet -u admin --authenticationDatabase admin --eval 'db.getSiblingDB("salmonbus_chat").messages.estimatedDocumentCount()'
```

## 장애 때

1. 채팅을 끈다. 위 "끄기"대로 api 재시작까지 한다.
2. 핵심 API를 확인한다. "켜기"의 확인 블록으로 `/readyz`, health, 보드를 본다.
3. 메모리 압박이 남으면(swap 사용이 계속 늘거나 커널 OOM이 나면) mongod를 멈춘다.

```bash
systemctl stop mongod
```

mongod는 `enabled`라서 재부팅하면 다시 뜬다. 원인을 볼 때까지 뜨지 않게 하려면 `systemctl disable --now mongod`를 쓰고,
다시 켤 때 `systemctl enable --now mongod`를 쓴다.

**프론트 롤백은 필요 없다.** 서버가 채팅 GET에 404를 주면 화면이 채팅을 숨긴다.

| 증상 | 볼 것 | 조치 |
| --- | --- | --- |
| 채팅이 "지금은 채팅을 쓸 수 없어요"를 띄우고 다시 연결을 되풀이한다 | `systemctl is-active mongod`, `mongod.log`, api 1분 요약의 `저장오류` | mongod가 멈춰 있으면 `systemctl start mongod`. mongod가 다시 뜨면 화면은 저절로 다시 붙는다 |
| mongod가 죽고 다시 뜨기를 되풀이한다 | `memory.events`의 `oom_kill`, `journalctl -u mongod` | `oom_kill`이 늘었으면 400M 상한에 걸린 것이다. 채팅을 끄고 원인을 본다 |
| 채팅이 다시 연결을 되풀이한다 | 브라우저 개발자 도구 Network의 WS 항목과 종료 코드, api 1분 요약의 `연결초과` | 1013이면 api의 연결 수 상한에 걸린 것이다. 상한은 서버 전체와 IP마다 있고, 바꾸려면 코드를 고쳐 배포한다 |
| 보드 API가 느리거나 끊긴다 | `free -m`, 커널 OOM | 위 순서 1~3 |

채팅이 켜진 상태에서 mongod가 멈춰도 api는 뜨고 `/readyz`와 health는 `UP`이다. 채팅만 `CHAT_UNAVAILABLE`로 실패한다.
그래서 mongod 장애 때문에 api 배포가 롤백되지는 않는다.
채팅을 여는 순간 저장소에 닿지 못하면 api는 오류를 보낸 뒤 1011로 닫고, 화면은 간격을 늘려 가며 다시 잇는다.
mongod가 `Restart=on-failure`로 몇 초 뒤 다시 뜨면 새로고침하지 않아도 다시 붙는다.

IP마다 두는 연결 상한은 `X-Forwarded-For`의 마지막 값으로 센다. CloudFront를 거치면 마지막 값이 접속한 사람의 IP다.
8080에 바로 붙는 클라이언트는 이 헤더를 꾸밀 수 있어서 IP 상한을 피할 수 있다. 그래서 실제 보호는 서버 전체 상한이다.
누군가 전체 상한을 채우면 채팅만 새 연결을 받지 못하고 보드 API는 영향이 없다. 그러면 채팅을 끈다.

## 삭제 요청 대응

보존 기한과 백업이 없으므로 메시지는 운영자가 지우기 전까지 남는다.
**삭제 요청이 오면 운영자가 mongosh로 해당 문서를 지운다. 이 경로가 유일하다.** 지운 문서는 되돌릴 수 없다.

관리자로 접속한다.

```bash
mongosh --quiet -u admin --authenticationDatabase admin
```

`roomId`는 노선 ID다. 3330은 `204000057`, 1650은 `234000050`이다. 본문 일부로 찾는다.

```js
db.getSiblingDB("salmonbus_chat").messages.find({roomId: "204000057", body: /찾을 말/}, {authorId: 1, nickname: 1, body: 1, createdAt: 1}).sort({createdAt: -1}).limit(20)
```

시각으로 찾을 수도 있다. `createdAt`은 UTC라서 한국 시각에서 9시간을 뺀다. 아래는 한국 시각 10월 2일 18시~19시다.

```js
db.getSiblingDB("salmonbus_chat").messages.find({roomId: "204000057", createdAt: {$gte: ISODate("2026-10-02T09:00:00Z"), $lt: ISODate("2026-10-02T10:00:00Z")}}, {authorId: 1, nickname: 1, body: 1, createdAt: 1}).sort({createdAt: -1}).limit(20)
```

한 건을 지운다. `_id`는 찾은 결과의 값을 쓴다. `deletedCount: 1`이 나와야 한다.

```js
db.getSiblingDB("salmonbus_chat").messages.deleteOne({_id: ObjectId("<찾은 _id>")})
```

같은 사람이 쓴 여러 건을 지울 때는 `authorId`로 먼저 세고 지운다. `authorId`는 노선과 브라우저 탭마다 다르다.

```js
db.getSiblingDB("salmonbus_chat").messages.countDocuments({roomId: "204000057", authorId: "<찾은 authorId>"})
```

```js
db.getSiblingDB("salmonbus_chat").messages.deleteMany({roomId: "204000057", authorId: "<찾은 authorId>"})
```

`deletedCount`가 앞에서 센 수와 같아야 한다.

지운 메시지는 채팅을 새로 여는 화면의 이력에서 빠진다. 이미 열려 있는 화면은 받은 메시지를 id로 합치기만 하므로
새로 고치기 전까지 보일 수 있다.

## 비밀번호 바꾸기

설치 때 만든 관리자·앱 비밀번호를 그대로 쓴다. 바꿀 일이 생기면 아래대로 한다.

앱 계정 비밀번호를 바꾸면 `api.env`의 줄도 같이 바꾼다. 바꾼 뒤 api가 새로 여는 연결은 옛 비밀번호로 인증에 실패하므로,
채팅이 켜져 있으면 바꾼 뒤 바로 api를 재시작한다.

1. 관리자 mongosh에서 새 비밀번호를 넣는다.

```js
db.getSiblingDB("salmonbus_chat").changeUserPassword("chat", passwordPrompt())
```

2. 설치 8단계 ②(백업과 `read -rs`)를 한다.
3. 기존 줄을 지우고 설치 8단계 ③을 한다.

```bash
sed -i '/^CHAT_MONGODB_URI=/d' /etc/salmonbus/api.env
```

4. 채팅이 켜져 있으면 `systemctl restart salmonbus-api` 뒤 "켜기"의 확인 블록을 돌린다.

관리자 비밀번호는 관리자 mongosh에서 바꾼다.

```js
db.getSiblingDB("admin").changeUserPassword("admin", passwordPrompt())
```

## 나중에 보존 기한을 둘 때

지금은 보존 기한이 없다. 두기로 하면 TTL 인덱스 하나를 만든다. 앱이 `createdAt`을 날짜 타입으로 저장하므로 TTL이 그대로 걸린다.
보존 기한은 계약(`contract/chat-v1.md`의 저장 절)과 화면 안내에도 걸린다. 계약, `init-chat-db.js`, TTL이 없음을 확인하는 시험, 이 문서를 같은 PR에서 고친 뒤 서버에 적용한다.

```js
db.getSiblingDB("salmonbus_chat").messages.createIndex({createdAt: 1}, {expireAfterSeconds: <초>, name: "created_at_ttl"})
```

- `<초>`는 7일이면 `604800`, 30일이면 `2592000`이다.
- 기존 문서에도 적용된다. TTL 작업이 60초마다 돌면서 기한이 지난 문서를 지운다.
- 값을 바꿀 때는 `collMod`를 쓴다. 같은 이름에 다른 값으로 `createIndex`를 하면 "different options" 오류가 난다.

```js
db.getSiblingDB("salmonbus_chat").runCommand({collMod: "messages", index: {name: "created_at_ttl", expireAfterSeconds: <초>}})
```

- 보존 기한을 다시 없앨 때는 `dropIndex("created_at_ttl")`를 쓴다.

## mongod 설정 바꾸기

1. 이 디렉터리의 파일을 고치고 PR로 합친다.
2. 설치 4단계에서 해당 파일의 heredoc만 다시 붙인다.
3. 드롭인을 바꿨으면 `systemctl daemon-reload`를 먼저 하고, `systemctl restart mongod`로 적용한다.
4. `systemctl is-active mongod`와 "이 디렉터리의 파일"의 해시 대조로 확인한다.

mongod가 재시작하는 몇 초 동안 채팅 저장이 실패한다. 되돌릴 때는 git 이력의 이전 내용으로 같은 절차를 한다.

## 패치 업그레이드

올릴 수 있는 버전을 본다.

```bash
dnf --disableexcludes=main --showduplicates list mongodb-org-server 2>/dev/null | tail -3
```

`V=` 뒤에 고른 버전(예: `8.0.33`)을 적어 붙인다. 비워 두면 아무것도 하지 않는다.

```bash
V=
test -n "$V" && dnf --disableexcludes=main upgrade -y "mongodb-org-server-$V" && systemctl restart mongod
systemctl is-active mongod; rpm -q mongodb-org-server
```

- `--disableexcludes=main`은 그 명령에서만 버전 고정을 푼다. `dnf.conf`의 `exclude` 줄은 그대로 둔다.
- mongosh를 올릴 때는 같은 방법으로 `mongodb-mongosh`를 올린다.
- mongod가 재시작하는 몇 초 동안 채팅 저장이 실패한다.
- **이 작업 중에 전체 `dnf upgrade`를 하지 않는다.** `exclude`가 MongoDB는 막지만 커널과 다른 패키지가 바뀐다.
  OS 업데이트는 따로 하고, 커널이 바뀌면 재부팅 전에 새 커널이 6.19~7.0.13 구간이 아닌지 본다.
- 주 버전(9.0 등)은 이 절차로 올리지 않는다. 저장소 파일과 Java 드라이버 호환을 따로 검토한다.

## 되돌리기와 완전 제거

| 상황 | 방법 |
| --- | --- |
| 급히 끄기 | 위 "끄기" |
| 코드 되돌리기 | 채팅 PR의 squash 커밋을 되돌리는 커밋. RDS 스키마 변경이 없다. 배포되면 api가 한 번 재시작한다 |
| mongod 설정 되돌리기 | "mongod 설정 바꾸기"의 절차로 이전 파일을 넣는다 |

완전히 없앨 때는 아래 순서로 한다. 채팅 코드와 앱 설정을 먼저 빼고, MongoDB와 swap은 그 뒤에 지운다.

**1. 프론트 코드.** `frontend/src/features/chat/`, `App.tsx`의 `ChatGate`, 채팅 E2E와 픽스처, `webpack.dev.cjs`의 `ws: true`.

**2. 서버 코드.** `api/chat` 패키지와 시험, `api-app/build.gradle`에서 채팅 때문에 바꾼 의존성과 시험 입력,
`application.yml`의 `chat` 블록, `ApiPackageBoundaryTest`의 `chat`, `contract/chat-v1.md`와 `contract/examples/chat-v1.json`, `backend/deploy/chat/`.

**3. 앱 설정.** `api.env`의 `CHAT_*` 줄을 지우고 api를 재시작한다.

```bash
sed -i '/^CHAT_/d' /etc/salmonbus/api.env
stat -c '%a %U:%G' /etc/salmonbus/api.env; grep -c '^CHAT_' /etc/salmonbus/api.env
systemctl restart salmonbus-api
```

`600 root:root`와 `0`이어야 한다. 재시작 뒤 "켜기"의 확인 블록으로 `/readyz`와 보드를 본다.
채팅 작업 때 만든 백업(`/etc/salmonbus/api.env.bak.<시각>`)도 지운다.

**4. MongoDB.** `/var/lib/mongo`를 지우면 메시지가 모두 사라진다. 백업이 없으므로 되돌릴 수 없다.

먼저 채팅 설정이 빠졌는지 보고, 남은 메시지 수를 본다. 관리자 비밀번호를 묻는다.

```bash
grep -c '^CHAT_' /etc/salmonbus/api.env
mongosh --quiet -u admin --authenticationDatabase admin --eval 'db.getSiblingDB("salmonbus_chat").messages.estimatedDocumentCount()'
```

첫 줄이 `0`이 아니면 멈추고 3단계를 다시 한다. 메시지를 남겨야 하면 여기서 멈춘다. 둘 다 아니면 아래를 붙인다.

```bash
systemctl disable --now mongod
dnf --disableexcludes=main remove -y 'mongodb-org*' mongodb-mongosh
rm -rf /var/lib/mongo /var/log/mongodb /etc/systemd/system/mongod.service.d
rm -f /etc/mongod.conf /etc/mongod.conf.rpm-default /etc/mongod.conf.rpmsave /etc/logrotate.d/mongod /etc/yum.repos.d/mongodb-org-8.0.repo
sed -i '/^exclude=mongodb-org\*,mongodb-mongosh$/d' /etc/dnf/dnf.conf
systemctl daemon-reload
grep -n mongodb /etc/dnf/dnf.conf; ls /etc/yum.repos.d/; id mongod
```

- `exclude`를 기존 줄에 합쳐 넣었다면 그 줄에서 `mongodb-org*`와 `mongodb-mongosh` 두 항목만 지운다.
- RPM이 만든 `mongod` 사용자가 남아 있으면(`id mongod`가 출력되면) `userdel mongod`로 지운다.

**5. swap.** 채팅과 무관하게도 메모리 여유에 도움이 되므로 남길지는 그때 정한다.
지울 때는 먼저 swap 사용량을 본다.

```bash
free -m
```

`Swap:` 줄의 `used`가 작을 때만 아래를 붙인다. 크면 메모리 사용이 줄어든 뒤에 한다.

```bash
swapoff /swapfile
sed -i '\#^/swapfile swap swap defaults 0 0$#d' /etc/fstab
rm -f /swapfile /etc/sysctl.d/90-salmonbus-swap.conf
sysctl -w vm.swappiness=60
swapon --show; tail -2 /etc/fstab
```

- `swapoff`는 swap에 있던 내용을 메모리로 되돌린다.
- 60은 설치 전 값이다.
- 설치 때 만든 `/etc/fstab.bak.<시각>`도 지운다.

**6. 할 일이 없는 것.** RDS, Flyway, CloudFront, 보안 그룹, 파이프라인, worker, common은 채팅이 건드리지 않았다.
