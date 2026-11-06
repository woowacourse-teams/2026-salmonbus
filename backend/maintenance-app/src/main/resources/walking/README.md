# 정류장 도보 거리 넣기

탑승 후보 조회 API(`GET /api/v1/routes/{routeId}/stations/{stationId}/boarding-options`)가 읽는 정류장 쌍 도보 거리는 레포에 두지 않는다. 레포 밖에서 만든 파일을 정비 명령 `walk-distances-import`로 `stop_walk_distance` 테이블에 넣는다. 테이블이 비어 있으면 API는 같은 정류장을 다시 지나는 후보만 돌려준다.

## 파일

| 파일 | 내용 |
| --- | --- |
| `stop-walk-distances.csv.gz` | 머리줄 `station_a,station_b,a_x,a_y,b_x,b_y,walk_m`. 정류장 쌍마다 한 줄이고 `station_a < station_b`, 계산에 쓴 두 좌표와 도보 거리(m) |
| `stop-walk-distances.json` | `osmBase`(계산에 쓴 OpenStreetMap 자료 기준 시각), `sha256`(압축 파일 바이트의 SHA-256), `pairCount` |

두 파일의 `sha256`·`pairCount`가 맞지 않거나, 머리줄·칸 수가 다르거나, 같은 쌍이 두 번 있거나, 거리가 비어 있으면 넣지 않는다. 도보 거리는 OpenStreetMap 파생 자료(© OpenStreetMap contributors, ODbL)다.

## 실행

설정 파일과 DB 환경 파일은 [편도 품질 정비 앱](../quality/README.md)의 「실행 준비」와 같다.

```bash
java -jar maintenance-app/build/libs/maintenance-app-0.0.1-SNAPSHOT.jar \
  walk-distances-import --config /absolute/path/maintenance.properties \
  --distances /absolute/path/stop-walk-distances.csv.gz --metadata /absolute/path/stop-walk-distances.json
```

`ACADEMY`에서는 `--approval /absolute/path/approval.json`도 필요하다. 승인 파일의 action은 `ACADEMY_STOP_WALK_DISTANCE_IMPORT`, `artifactSha256`은 넣을 `csv.gz`의 SHA-256, `databaseIdentitySha256`은 대상 DB의 식별 해시다.

성공하면 `result`에 `outcome`(`LOADED` 또는 `UNCHANGED`), `checksum`, `pairCount`를 출력한다. 지금 들어 있는 파일과 같으면 `UNCHANGED`로 끝나고 쓰지 않는다. 다른 파일이면 한 트랜잭션에서 거리 전체와 적재 기록을 바꾼다. 이전 파일로 되돌릴 때도 그 파일을 다시 넣으면 된다.

실패 코드: 파일을 읽지 못하면 `WALK_DISTANCE_FILE_UNREADABLE`, 검증에 실패하면 `WALK_DISTANCE_FILE_INVALID`, DB 접속·트랜잭션 실패는 `DATABASE_CONNECTION_FAILED`·`DATABASE_TRANSACTION_FAILED`.
