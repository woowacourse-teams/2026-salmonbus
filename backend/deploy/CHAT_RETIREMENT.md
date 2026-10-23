# SAL-256 채팅 종료

웹 채팅 진입점·WebSocket 연결, API의 채팅 HTTP/WebSocket 처리와 MongoDB 드라이버를 제거한다.
앱은 같은 웹 화면을 사용하므로 새 웹 배포를 받으면 채팅을 표시하지 않는다. PostgreSQL,
회원·즐겨찾기, 노선·지도·도착정보, worker 수집·예보·정산은 계속 사용한다.

메시지 DB, Mongo 데이터 디렉터리·Docker volume·백업과 적용된 Flyway 이력은 보존한다.
이 문서는 실행 절차이며 실제 dev·운영 반영을 증명하지 않는다. 커밋·push·PR·머지·배포는
프로젝트 승인 규약을 각각 따른다.

## dev 반영 순서

한 번에 최종 변경을 배포해 Mongo부터 중지하지 않는다. 다음 두 단계의 배포본을 구분한다.

1. **제품 기능 종료:** 프론트엔드는 PR #158(SAL-257)로 먼저 반영한다. 이어서 백엔드 PR로 API 코드·의존성·공개 계약·테스트와 빌드 입력을 반영한다.
   프론트엔드 PR이 사용하는 `contract/examples/chat-v1.json`은 이 단계에서 유지하고, #158 반영 확인 뒤 후속 인프라 정리에서 삭제한다.
   이 단계에서는 기존 Mongo Compose·설정·dev 도구를 유지한다. 새 API는 과거 `CHAT_*`
   설정이 남아 있어도 소비하지 않는다. 웹을 먼저 반영하고 API/worker를 기존 절차대로 배포한다.
2. 실제 dev 웹·API·worker의 배포 SHA/소스 지문·health를 확인한다. 노선 목록, 보드·watch,
   차량, geometry, 도착정보, 로그인·회원·즐겨찾기와 수집·정산을 확인한다. 새 API JAR에
   채팅 클래스·Mongo/WebSocket 라이브러리가 없고 새 웹에 채팅 요청·연결이 없음을 확인한다.
3. **인프라 의존 종료:** Compose, dev 도구, 설정 준비·전환 코드를 반영한 두 번째 배포본을
   준비한다. 기존 basis release ID, 데이터 준비 계약, Mongo 소비자가 채팅뿐인지 확인한다.
   진행 중인 배포·데이터 준비가 있으면 끝날 때까지 기다린다.
4. `salmonbus-dev-infra.service`의 기본 unit과 **실효 drop-in**을 대조한다. 예전
   `ExecStart`가 `mongodb`를 명시하면 새 Compose와 호환되지 않는다. 해당 override의 소유·목적을
   확인한 뒤 승인된 변경으로 제거/교체한다. 파일만 바꾸고 `daemon-reload`를 누락하지 않는다.
5. 새 dev 도구의 `prepare-deployment` / basis 전환 절차를 적용한다. API와 worker를 중지한
   유지보수 구간에 기존 unit이 Mongo를 포함한 infra를 중지할 수 있다. 이후 새 `start-infra`는
   RDS 환경에서 GBIS mock만, 로컬 PostgreSQL 환경에서는 PostgreSQL과 mock만 기동한다.
   PostgreSQL/RDS 데이터·Mongo 데이터에는 쓰지 않는다. 전체 Compose `down -v`를 사용하지 않는다.
6. 새 API/worker를 기동하고 health 및 2번 업무 흐름을 다시 확인한다. 이전 unit의 중지 대상에서
   빠져 남아 있는 채팅 전용 Mongo 프로세스/컨테이너가 있으면 정확한 대상만 확인해 중지한다.
   데이터 디렉터리·volume·백업은 삭제하지 않는다.

`ChatRetirement`는 조사 기준 `d2ee999d`의 Compose·Mongo 설정·초기화 파일과 이 변경의
새 Compose 해시가 정확히 일치하는 전환/원복만 허용한다. 데이터 준비 계약과 모든 seed·모델
파일도 기존 basis guard로 비교한다. 실서버 basis가 다르면 `DEV_DATA_CHANGE_REQUIRES_PLAN`으로
중지한다. allowlist를 늘려 통과시키지 말고 실제 두 manifest를 대조해 별도 전환 검토를 한다.

설정 전환 시 DB 비밀번호·RDS 인증서·도착정보 설정을 유지하고 활성 `api.env`의 `CHAT_*`를
제거한다. 이전 채팅 인증정보는 config 아래 `retired-chat/`에 root 전용 권한(디렉터리 0700,
파일 0600)으로 남겨 원복에 사용한다. 이 디렉터리는 서비스 EnvironmentFile이 아니다.
새 설정 준비에는 `--frontend-origin`이 필요하지 않다. 기존 호출의 옵션은 유효한 값이면 받아들이되 활성 채팅 설정으로 저장하지 않는다.
개인 로컬의 기존 settings volume도 API URI 항목만 제거하고 DB 자격증명·Mongo 파일은 유지한다.

## 캐시·이전 클라이언트

새 웹은 채팅 가용성 조회, WebSocket 연결과 재연결 타이머를 만들지 않는다. 이전 탭·WebView가
기존 JS를 계속 쓰면 가용성 HTTP와 stream 경로는 404가 되고 WebSocket upgrade는 실패한다.
이미 열린 이전 연결은 API 재시작 때 종료된다. 재시도하던 옛 클라이언트는 새로고침하거나
WebView를 다시 열어 새 웹을 받아야 한다. 별도의 채팅 서버나 영구 호환 endpoint는 두지 않는다.
웹 HTML/정적 자산 배포·캐시 갱신 절차도 함께 확인한다. 종료 전 앱 바이너리가 이전 웹 자산을
내장/고정하는 배포가 있는지는 릴리즈 담당자가 별도로 확인한다.

## 원복

제품 종료 1단계에서는 이전 웹·API/worker 배포본으로 되돌릴 수 있다. Mongo는 그대로 유지한다.
2단계 이후 채팅이 포함된 이전 API로 되돌릴 때는 먼저 보존한 basis/unit/config와 Mongo 기동을
복구하고 health를 확인한 뒤 이전 API/worker·웹으로 되돌린다. 기존 basis operation의 config
백업과 `retired-chat`을 유지한다. 설정 복원도 정확한 해시 쌍만 허용한다. basis journal이
남아 있거나 drop-in이 예상과 다르면 자동 복구를 반복하지 말고 기존 recovery 절차로 조사한다.
새 API만 되돌린 뒤 Mongo가 필요 없다고 가정하지 않는다.

## 운영 적용 조건

운영에서는 같은 제품 종료 변경이 **main으로 릴리즈되어 실제 새 웹·API/worker에 반영된 후**에만
Mongo 기동 구성을 정리한다. 운영 배포 SHA·health·주요 업무 API, Mongo 다른 소비자 부재를
확인하고 관찰한 뒤 채팅 전용 mongod/systemd·container·proxy·환경 설정을 별도 승인 범위로
정리한다. dev 도구의 basis 전환은 운영 호스트 관리 절차를 대체하지 않는다.

운영 reverse proxy의 WebSocket upgrade 전용 location, 환경 파일의 `CHAT_*`, mongod unit,
logrotate·모니터링 설정은 실제 호스트에서 확인한다. 저장소에 없는 실효 설정까지 제거됐다고
판정하지 않는다. 운영 데이터·백업 정리는 이 작업에 포함하지 않는다.
