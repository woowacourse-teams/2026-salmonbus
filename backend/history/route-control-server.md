# 서버 노선 운영: 에이전트 공통 안내

**설치 상태: 명령 파일은 준비되었으나 운영 기능은 아직 미배포/비활성이다.** DB route_operation 테이블과 API/worker feature flag 적용 후, 운영자가 확인하여 route-control.json의 ready를 true로 설정하기 전까지 변경 명령은 차단된다. 이 문서를 근거로 에이전트가 자동 배포·마이그레이션·설정 변경을 해서는 안 된다.

이 서버의 노선 운영 명령은 `/usr/local/bin/route-control`이다.

```sh
route-control --help
sudo route-control 4000 status
sudo route-control 5600 status
```

이 문서 열람이나 SSH 접속만으로 노선 상태 변경이 허가되는 것은 아니다. 사용자가 노선과 수집/서비스 ON/OFF를 명시적으로 요청했을 때만 변경한다. 설치 점검에서는 status만 실행한다.

사용자가 “서비스만 꺼, 수집 유지”라고 요청하면:

```sh
sudo route-control 4000 service off
sudo route-control 5600 service off
```

그 외 요청:

```sh
sudo route-control 5600 service on
sudo route-control 5600 collection on
sudo route-control 5600 collection off
sudo route-control 5600 both off
```

- `service off`: 관측 수집을 유지하고 새 예보 생성·공개만 중단한다.
- `service on`: 수집이 ON이어야 한다. WARMING_UP은 요청 접수 상태이며, 새 관측·예보 발행 후 실제 공개 ON이 된다. 이미 ON인 곳에 반복 실행하면 다시 준비 상태로 전환하므로 먼저 status를 확인한다.
- `collection off`: 서비스가 OFF여야 한다. 서비스도 중단하려는 요청이면 `both off`를 명시한다.
- 수집과 서비스를 모두 켜려면 `collection on`, `service on`을 순서대로 실행한다.
- 노선 번호는 root 소유 설정의 대응표로 변환한다. 모르는 번호는 추측하지 않고 운영자에게 확인한다. 검증된 9자리 GBIS ID도 지정할 수 있다.
- 여러 노선을 한 요청으로 받았어도 하나씩 실행하고 결과를 확인한다. 도중 실패 시 성공/실패/미실행 노선을 구분한다. 자동 복구나 revision 변경 재시도를 하지 않는다.

공식 파업 확인 요청에는 `--reason STRIKE --message '안내문' --source-url '공식 URL'`을 사용한다. 기본 사유는 MANUAL이다.

운영 설정은 `/etc/salmonbus/route-control.json`과 `/etc/salmonbus/routes.properties`에 고정되어 있다. DB 환경 파일은 root 소유 `/etc/salmonbus/worker.env`를 사용한다. 자격정보를 출력하거나 설정을 사용자 입력으로 교체하지 않는다. 기동 feature flag는 이 스크립트가 바꾸지 않는다.

기록은 `/var/lib/salmonbus/route-control/<request UUID>.json`에 저장한다. 시간 초과/연결 실패는 DB에 이미 적용됐을 수 있으므로 자동 재시도하지 않는다. 먼저 status와 원래 요청 기록을 확인한다. 이 기록을 통한 maintenance 재시도는 같은 UUID·revision·인자를 그대로 사용한다.

일반 SSH 접속 권한과 노선 변경 sudo 권한은 별개다. 이 명령을 실행할 권한을 가진 계정으로 접속한 에이전트가 사용한다. 이 안내는 계정/키/권한을 새로 부여하지 않는다. Python3, Java, maintenance JAR은 운영자가 설치한 것을 사용하고 현장에서 빌드·재시작·환경 수정으로 우회하지 않는다.

변경 후 같은 노선의 status로 collectionEnabled, serviceRequested, serviceEnabled, revision/appliedRevision, 마지막 관측/발행 시각을 확인한다. 요청 저장과 실제 공개 반영을 구분해 보고한다. 수집 OFF 직전에 시작한 요청은 끝날 수 있다.
