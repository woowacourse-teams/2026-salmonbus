# 어드민 구현 전 서버 내부 노선 제어

어드민은 별도 구현하고, 그 전에는 서버에서 짧은 명령으로 기존 maintenance-app을 호출한다. `route-control.py`는 SQL을 직접 수정하지 않는다. 기존 revision 충돌 검사·요청 UUID·감사 이력·수집 한도·서비스 준비 상태 검사를 그대로 사용한다.

## 사용

SSH 접속 후 다음과 같이 실행한다.

```sh
sudo route-control 4000 status
sudo route-control 5600 service off
sudo route-control 4000 service off
sudo route-control 5600 service on
sudo route-control 5600 collection on
sudo route-control 5600 both off
```

서비스 OFF는 수집을 유지한다. 수집 OFF는 서비스도 OFF일 때 `collection off`로 실행한다. 서비스가 켜져 있으면 `both off`를 명시해야 한다. 수집 ON은 서비스를 자동으로 켜지 않으며, 서비스 ON은 수집이 ON이어야 한다. 서비스 ON을 다시 실행하면 새 예보 준비를 다시 요청하므로 상태 확인만 필요하면 `status`를 쓴다.

공식 파업 확인 시:

```sh
sudo route-control 4000 service off --reason STRIKE \
  --message '파업으로 예보 제공을 일시 중단합니다.' \
  --source-url 'https://공식공지주소'
```

번호는 설정의 대응표로 변환한다. 초기 예시에는5600·4000·3330이 있으며, 다른 노선은 운영자가 매핑을 등록하거나 확인한 9자리 GBIS ID로 지정한다. 한 번에 한 노선만 실행한다. 5600 성공 뒤 4000 실패 시 5600을 자동 복구하지 않는다.

## 최초 설치: 운영자가 한 번 수행

호환 버전 JAR·V47 및 API/worker 양쪽 `ROUTE_OPERATIONS_ENABLED=true` 적용을 먼저 확인한다. 서버 Python3과 Java가 필요하며 이 절차에서 소스 빌드나 재시작을 수행하지 않는다. 아래는 서버에 파일을 전달한 뒤의 설치 템플릿이다. JAR 경로와 DB 식별값은 실제 배포에서 확인해야 한다.

```sh
sudo install -o root -g root -m 755 route-control.py /usr/local/bin/route-control
sudo install -o root -g root -m 600 route-control.example.json /etc/salmonbus/route-control.json
sudo install -d -o root -g root -m 700 /var/lib/salmonbus/route-control
sudo install -d -o root -g root -m 755 /usr/local/share/salmonbus
sudo install -o root -g root -m 644 route-control-server.md /usr/local/share/salmonbus/route-control.md
```

`/etc/salmonbus/routes.properties`를 root 소유0600으로 준비한다.

```properties
target.kind=ACADEMY
database.env-file=/etc/salmonbus/worker.env
```

최초 한 번 실제 maintenance-app으로 읽기 전용 `routes-status`를 실행한다. 결과의 `databaseIdentity`를 `route-control.json`에 고정한다. 같은 파일에 실제 Java/JAR 절대 경로와 위 properties 경로를 넣는다. 호환 배포·DB 마이그레이션·feature flag 적용 확인 전에는 `ready=false`를 유지하고, 적용 확인 후 운영자가 `ready=true`로 바꾼다. 경로의 실행 파일·JAR·상위 디렉터리는 운영자만 수정할 수 있게 관리한다. 이 설정 파일은 root 소유0600이어야 하며 사용자 입력으로 경로나 DB를 바꿀 수 없다.

운영 쓰기는 호출자가 이미 가진 sudo 권한으로 수행한다. 스크립트는 조회한 DB가 고정 식별값과 일치할 때에만, 기존 `ACADEMY_ROUTE_OPERATIONS` 형식의5분 유효 승인 파일을 임시 생성해 maintenance-app에 전달하고 삭제한다. 별도의 사람 승인을 검증하는 기능은 아니다. 따라서 **이 스크립트 실행 권한은 노선 운영 변경 권한**이다. 권한을 가진 사람에게만 실행을 허용한다. 새로운 팀원 권한 부여나 sudoers 변경은 이 파일 설치만으로 자동 수행하지 않는다.

이 기능을 어드민으로 옮길 때에는 로그인/역할 권한 검사를 서버에서 구현하고, 스크립트는 긴급 운영자용으로 제한한다.

어떤 에이전트든 기존 권한으로 SSH 접속한 뒤 `route-control --help`에서 서버 공통 안내 경로를 찾을 수 있다. 공통 안내는 `/usr/local/share/salmonbus/route-control.md`에 두며, 개인별 AI 설정에 종속되지 않는다. SSH/운영 권한이 없는 에이전트에게 접근을 자동 허용하는 것은 아니다.

## 결과와 실패

호출 시 SUDO_USER(직접 root 접속이면 root)를 actor로 기록한다. root 공유 계정으로 접속하면 개인 구분이 안 되므로 개인 SSH 계정의 sudo를 사용한다.

변경 전에 request-id와 기록 경로를 출력하고, `/var/lib/salmonbus/route-control/<UUID>.json`에 원래 명령 인자·변경 전후 상태를0600으로 저장한다. DB 비밀번호와 임시 승인 파일 내용은 기록하지 않는다.

명령 응답 지연·revision 충돌·오류를 자동 재시도하지 않는다. 이미 DB 변경이 성공했는데 후속 조회만 실패했을 수도 있다. `status`와 원래 요청 기록을 먼저 확인한다. 통신 실패 시 기존 maintenance-app으로 재시도하려면 기록에 남은 UUID·revision·전체 인자를 그대로 사용한다. 충돌을 새 revision으로 덮어쓰지 않는다.

출력의 WARMING_UP은 공개 완료가 아니다. 새 관측과 새 예보가 발행되어 실제 ON으로 바뀌는지 `status`로 확인한다. 수집 OFF 직전 시작한 외부 요청은 완료될 수 있다. 설정 파일·feature flag를 고치거나 프로세스를 재시작하는 기능은 없다.

## 검증 범위

스크립트 인자 구성, 명시적인 동시 중단, 잘못된 DB 차단, 시간 초과 시 재시도하지 않는 동작을 가짜 maintenance 호출로 확인한다. 실제 서버 설치·sudo 권한·운영 DB 적용은 별도 확인 대상이다. 기존 maintenance-app 동작의 로컬 검증과 스크립트의 운영 검증을 구분한다.
