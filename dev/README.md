# 로컬 실행

Docker Desktop을 켠 뒤 저장소 루트에서 실행합니다.

```sh
docker compose up --build --watch
```

화면은 http://localhost:3000, API는 http://localhost:8080입니다. DB·계정·초기 데이터를 준비하고 첫 예보를 확인한 뒤 프론트를 시작합니다. 프론트 소스 수정은 HMR로 반영하고, API·worker 수정은 해당 컨테이너를 재시작해 반영합니다.

같은 구성의 재실행은 `docker compose up --watch`를 사용합니다. Dockerfile·의존성·개발 설정을 바꾸면 `--build`를 붙입니다.

```sh
./dev/local.sh scenario list
./dev/local.sh scenario showcase
./dev/local.sh scenario mixed 9007
./dev/local.sh scenario normal all
docker compose ps --all
docker compose logs worker
docker compose stop
```

시나리오는 실행 중인 환경에서 다른 터미널로 선택합니다. 기존 8개 노선의 차량·좌석·통계는 개발 데이터이고, 신규 33개 노선은 목록·정류장만 제공합니다. 선택은 다음 수집(20초 간격)부터 반영합니다. 프론트의 운행 시간 판단은 유지하므로 새벽에는 운행 종료로 표시될 수 있습니다.

`stop` 후에도 DB·계정·모델은 볼륨에 유지됩니다. `settings-data` 볼륨만 따로 삭제하지 마세요. 초기 데이터 버전이나 모델이 맞지 않으면 자동으로 덮어쓰지 않고 중단합니다.
