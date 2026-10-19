# 로컬 실행

Apple Silicon 맥북의 Docker Desktop과 기본 Bash에서 저장소 루트의 명령을 실행합니다. 호스트에 Java·Gradle·Node를 따로 설치하지 않아도 됩니다. 이미지는 맥북의 CPU에 맞춰 `linux/arm64`로 빌드합니다.

Docker Compose 2.40.0 이상과 Buildx가 필요합니다. Watch의 파일 필터(`include`)와 초기 동기화(`initial_sync`)를 함께 사용하므로, 오래된 버전이면 빌드 전에 업데이트 안내와 함께 중단합니다. `docker compose version`과 `docker buildx version`으로 확인할 수 있습니다.

Apple Silicon·Docker 메모리 약 7.7GiB·Compose 5.0.1에서 첫 기동과 Watch를 검증했고, 기동 후 서비스 메모리 합계는 약 2.4GiB였습니다. 이 값은 순간 측정값이며 빌드·초기 데이터 준비의 최대 사용량은 아닙니다. Docker에 4GiB만 할당한 환경은 아직 검증하지 않았습니다. 다른 컨테이너와 동시에 개발하거나 Watch가 이미지를 다시 빌드할 때는 여유가 더 필요하므로 먼저 `./dev/local.sh doctor`로 할당량과 여유 공간을 확인해 주세요.

```sh
./dev/local.sh up --watch
```

화면은 http://localhost:3000, API는 http://localhost:8080입니다. DB·계정·초기 데이터를 준비하고 첫 예보를 확인한 뒤 프론트를 시작합니다. 프론트 소스 수정은 HMR로 반영하고, API·worker 수정은 해당 컨테이너를 재시작해 반영합니다.

서비스가 정상 기동한 뒤 Watch를 시작합니다. Watch 터미널에는 변경 반영·재빌드 내역을 표시하며 앱 로그는 `./dev/local.sh logs api`처럼 다른 터미널에서 확인합니다.

같은 명령으로 다시 실행하면 기존 의존성 설치 레이어와 개발 데이터를 재사용합니다. 일반 소스 수정은 HMR 또는 컨테이너 재시작으로 반영하고, 패키지·Gradle 설정 변경은 Watch가 이미지를 다시 빌드합니다.

로컬 이미지는 `<프로젝트 이름>-api:latest`처럼 같은 태그를 갱신합니다. 태그가 이동한 뒤 남는 오래된 `<none>` 이미지는 빌드·기동 성공 후와 `stop` 시 현재 프로젝트 안에서 정리합니다. Watch도 재빌드 시 정리를 활성화하고 종료할 때 한 번 더 정리합니다. 현재 태그와 실행 중이거나 중지된 컨테이너가 참조하는 이미지는 유지합니다. 다른 프로젝트의 이미지·DB 볼륨은 정리 대상이 아닙니다.

빌드 캐시는 같은 Git 저장소의 워크트리들이 공유하는 전용 빌더에서 관리합니다. 빌더는 메모리 2GiB·병렬 작업 2개로 제한하고, 캐시가 약 4GB를 넘으면 자동 정리합니다. 자동 정리는 주기적으로 수행하므로 빌드 중 일시적으로 기준을 넘을 수 있습니다. 기본 Docker 빌더나 다른 저장소의 캐시는 변경하지 않습니다.

API·worker의 앱 JVM 힙은 각각 384MiB, Gradle 힙은 256MiB로 제한합니다. 컨테이너 로그는 서비스당 10MB 파일 3개를 보관합니다. 이 값들은 로컬 개발용이며 운영 성능 테스트의 자원 설정으로 사용하지 않습니다.

```sh
./dev/local.sh scenario list
./dev/local.sh scenario showcase
./dev/local.sh scenario mixed 9007
./dev/local.sh scenario normal all
docker compose ps --all
docker compose logs worker
./dev/local.sh stop
./dev/local.sh doctor
```

시나리오는 실행 중인 환경에서 다른 터미널로 선택합니다. 기존 8개 노선의 차량·좌석·통계는 개발 데이터이고, 신규 33개 노선에는 차량 관측·예보를 생성하지 않습니다. 41개 노선 모두 목록·정류장 좌표와 도로 형상을 제공합니다. 선택은 다음 수집(20초 간격)부터 반영합니다. 프론트의 운행 시간 판단은 유지하므로 새벽에는 운행 종료로 표시될 수 있습니다.

노선 형상은 `GET /api/v2/routes/{routeId}/geometry`에서 확인합니다. 개발 worker는 기동 시 카탈로그의 모든 노선을 수집하며, 수집이 끝나면 응답의 `status`는 `READY`, `points`는 도로 형상, `busStops[].point`는 정류장 좌표가 됩니다. `paths`는 기존 방향 분리 검증을 통과할 때만 제공하므로 전체 형상이 있어도 `null`일 수 있습니다. DB에 저장한 형상은 재기동 후에도 유지합니다.

개발용 GBIS는 [route-shapes.json](data/route-shapes.json)의 고정 자료를 재생합니다. 기존 8개 노선은 GBIS 도로 형상 API 수신 자료이며, 나머지 33개 노선은 GBIS 공식 웹 지도의 공개 정적 형상을 원본 순서대로 변환한 자료입니다. 각 노선의 `source`에 출처·수신 시각·원문 해시·좌표 변환 근거를 남깁니다. 실시간 도로 갱신을 보장하는 자료는 아니며, 정류장을 직선으로 연결해 만든 경로도 아닙니다. 수록된 정류장 자료의 `contentDigest`에 연결하고, 노선 누락·판본 불일치·잘못된 좌표가 있으면 모의 GBIS 기동을 중단합니다. 노선을 추가하거나 정류장 자료를 갱신할 때는 대응하는 검증된 형상도 함께 갱신해야 합니다.

개인 로컬과 공유 dev 설정에서만 `route-geometry.enabled`와 `include-catalog`를 활성화합니다. 형상 자료는 모의 GBIS 이미지와 basis digest에 포함하며, DB 초기 데이터·기존 모델 판본은 바꾸지 않습니다. 공유 dev 반영에는 새 모의 GBIS basis와 worker의 별도 배포가 필요합니다.

노선 기본정보 응답의 요일별 배차간격은 [route-headways.json](data/route-headways.json)을 재생합니다. 2026-10-19에 GBIS 노선 기본정보 API(`getBusRouteInfoItemv2`)를 41개 노선마다 한 번 호출해 받은 배차 8필드를 원래 값과 타입 그대로 담았고, 노선별 응답 시각과 원문 SHA-256을 함께 기록했습니다. worker가 노선 정보를 다시 읽으면 같은 노선 버전에 채워지고 `GET /api/v1/routes/{routeId}/board`의 `route.headways`에서 확인합니다. 이 파일은 모의 GBIS 이미지와 basis digest에 포함되며 DB 초기 데이터 계약은 바꾸지 않습니다. 값을 다시 받으면 이 파일과 기록을 함께 갱신해야 합니다.

`stop`은 이 프로젝트의 컨테이너와 네트워크를 정리합니다. DB·계정·모델은 이름 있는 볼륨에 유지되며 다음 실행에서 재사용합니다. `settings-data` 볼륨만 따로 삭제하지 마세요. 초기 데이터 버전이나 모델이 맞지 않으면 자동으로 덮어쓰지 않고 중단합니다.

`doctor`는 호스트 여유 공간·Docker 메모리·저장소별 캐시 사용량을 읽기 전용으로 보여 줍니다. 오래된 빌드 캐시를 직접 정리하려면 `./dev/local.sh clean-cache --yes`를 실행합니다. 이 저장소의 전용 빌더에서 24시간 이상 사용하지 않은 캐시만 정리하고 DB 볼륨과 실행 이미지는 유지합니다. 관측·예보 이력은 개발하는 동안 계속 쌓이므로 사용하지 않을 때는 `stop`하고, 필요할 때 아래 명령으로 이력을 정리합니다.

이미지와 빌드 캐시는 별도로 관리합니다. `doctor`는 현재 프로젝트의 이미지도 표시하며, 이미지 정리만 다시 실행하려면 `./dev/local.sh clean-images --yes`를 사용합니다. 공유 dev 배포용 `salmonbus-gbis-replay:<커밋 SHA>`와 과거 별도 검증 프로젝트의 태그는 이 명령으로 삭제하지 않습니다. 배포 버전·롤백 이력과 기존 컨테이너 참조를 확인한 뒤 별도로 정리해야 합니다.

Gradle 모듈을 추가하면 `dev/docker/backend.Dockerfile`의 빌드 설정 복사 목록도 함께 갱신해야 합니다. 실행 이미지에는 오프라인 기동에 필요한 의존성을 남기므로 백엔드의 Gradle 캐시를 빌드 cache mount로만 옮기지 않습니다.

실행 중인 로컬 환경의 이력과 채팅을 비우려면 다른 터미널에서 실행합니다.

```sh
./dev/local.sh reset-data --yes
```

관측·예보·평가와 파생 통계를 정리하고 초기 통계를 복원합니다. 계정·노선·정류장·모델은 유지하며, 기본 운행으로 다시 시작합니다. 완료 후 브라우저를 새로고침해 주세요. 중간에 실패하면 앱은 중지 상태로 남고, 원인을 해결한 뒤 같은 명령으로 다시 실행할 수 있습니다.

로컬 구성 변경의 첫 기동·오프라인 실행·첫 예보·HTTP 검증은 `bash dev/tests/smoke-local.sh`로 실행합니다. 이 검증에는 Python 3도 필요합니다. 별도 프로젝트·빌더·임시 포트·볼륨을 만들고 종료 시 검증 자원만 정리합니다.

이미지 정리 범위는 `bash dev/tests/image-lifecycle.sh`로 검증합니다. 임시 이미지만 만들고 현재 태그·다른 프로젝트·컨테이너가 참조하는 이미지의 보존을 확인합니다.

CI도 ARM64 Linux에서 같은 검증을 실행합니다. macOS 파일 변경 감지와 IDE 사용은 실제 맥북에서 확인해야 합니다.
