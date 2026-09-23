# 편도 품질 정비 앱

`maintenance-app`은 RDS에 저장된 관측의 편도 품질을 확인하고, 중단한 조사를 이어서 실행하는 명령행 프로그램이다. 품질 판단과 저장은 `forecasting`이 담당하며 정비 앱은 명령·설정·출력을 처리한다. 완료된 S3→RDS 반입 기능은 포함하지 않는다.

이 문서는 SAL-134의 **V18 스키마와 현재 정비 앱**을 기준으로 한다. 기존 DB는 [SAL-134 전환 절차](../../../../../deploy/migrations/SAL-134/README.md)를 마친 뒤 사용한다. 정비 명령은 스키마를 생성하거나 Flyway를 실행하지 않는다.

## 품질 조사와 저장 책임

정상 수집은 받은 잔여석을 확인하고 별도 편도 조사 SQL을 실행하지 않는다. 현재 모델의 잔여석 상한인 70을 초과하면 해당 차량의 조사 요청을 관측과 같은 트랜잭션에 저장한다. 조사하는 동안 해당 차량의 자료 사용을 보류하며 다른 정상 차량은 계속 사용할 수 있다.

조사는 기점·회차지의 출발 상태(`running_state=2`)와 정류장 순서에 따른 방향 전환을 보고 편도를 구분한다. 같은 정류장의 반복 출발은 기존 편도로 이어질 수 있다. 관측 연결 간격은 기본 10분이며, 이를 넘으면 이전 편도와 연결하지 않는다. 출발이나 방향 전환이 확인되면 간격이 길어도 새 편도로 구분한다. 잔여석 증가량만으로 제외 여부를 결정하지 않는다.

V18에서는 품질 자료를 다음 위치에서 관리한다.

| 테이블 | 저장하는 내용 |
| --- | --- |
| `vehicle_one_way_trip` | 편도의 시작과 판정 상태, 판단에 사용한 규칙 |
| `observation_trip_assignment` | 원 관측과 판정한 편도의 연결 |
| `route_data_quality` | 노선별 `quality_revision`과 품질 변경을 직렬화하는 잠금 |
| `route_version_quality_policy` | 노선 버전별 `maximum_observation_gap_seconds`와 설정 근거 `observation_gap_evidence` |
| `trip_quality_rebuild` | 노선 버전·차량별 조사 단계와 진행 위치. `vehicle_id=''`인 행은 수동으로 이상 자료를 찾는 작업 |

원 관측의 좌석·위치·수신 시각과 발행한 예측값은 보존한다. 관측과 편도의 연결은 `observation_trip_assignment`에 저장하며 기존 `vehicle_trip_key` 컬럼을 갱신하지 않는다. 조사 근거·시작점·진행 위치로 참조한 관측은 해당 수집 시도의 입력을 확정해 재수집으로 삭제되지 않게 한다.

품질 조건이 바뀌면 같은 노선의 품질 버전을 올린다. 예보의 현재 제공 여부와 평가·보정·통계·학습에 사용할 수 있는지는 각 조회 조건으로 판단한다. 이 정비 명령이 기존 예측을 다시 발행하거나 모델을 재학습하지는 않는다.

## 실행 준비

Java 21을 사용한다. `backend` 디렉터리에서 정비 JAR를 빌드한다.

```bash
./gradlew :maintenance-app:bootJar
```

[설정 예시](../../../../config/quality.properties.example)를 복사해 대상 환경과 DB 환경 파일의 절대 경로를 지정한다.

```properties
target.kind=LOCAL
database.env-file=/absolute/path/maintenance.env
```

DB 환경 파일에는 다음 세 값이 필요하다. 예시의 사용자명과 비밀번호는 실제 로컬 DB 값으로 바꾼다.

```dotenv
DB_URL=jdbc:postgresql://127.0.0.1:5432/salmonbus
DB_USERNAME=<DB_USER>
DB_PASSWORD=<DB_PASSWORD>
```

DB 환경 파일과 승인 파일은 소유자만 읽을 수 있는 일반 파일이어야 한다. 허용 권한은 `0600` 또는 `0400`이며 심볼릭 링크는 거절한다. `LOCAL`은 loopback 주소의 DB만 허용한다.

`ACADEMY`는 기존 `/etc/salmonbus/worker.env`에서 DB 접속 정보만 읽고, 파일의 root 소유와 비공개 권한을 확인한다. 운영 환경의 `quality-rebuild`에는 `--approval`도 필요하다. [승인 파일 예시](../../../../config/approval.json.example)의 action은 `ACADEMY_TRIP_QUALITY_REBUILD`이고, `artifactSha256`은 `null`, `databaseIdentitySha256`은 대상 DB의 식별 해시다. 만료 시각은 실행 시각보다 뒤여야 한다. 기존 승인 파일과의 호환을 위해 `schemaVersion`은 `salmonbus-migration-approval-v1`을 유지한다.

DB 식별 해시는 접속 URL의 소문자 호스트·포트·DB 경로를 `호스트:포트/DB명`으로 합친 뒤 줄바꿈과 사용자명을 붙여 SHA-256으로 계산한다. 포트를 생략했다면 5432를 사용한다. 승인 파일은 키를 정렬한 canonical JSON이어야 하며, 예시의 자리표시자를 그대로 사용할 수는 없다.

## 명령 실행

아래 명령은 `backend` 디렉터리 기준이다. 노선 버전 `123`, 설정 파일 경로와 `until`은 실행 대상에 맞게 바꾼다.

```bash
java -jar maintenance-app/build/libs/maintenance-app-0.0.1-SNAPSHOT.jar \
  quality-preview --config /absolute/path/quality.properties \
  --route-version 123 --until 2026-09-21T14:00:00Z

java -jar maintenance-app/build/libs/maintenance-app-0.0.1-SNAPSHOT.jar \
  quality-rebuild --config /absolute/path/quality.properties \
  --route-version 123 --until 2026-09-21T14:00:00Z --batch-limit 20

java -jar maintenance-app/build/libs/maintenance-app-0.0.1-SNAPSHOT.jar \
  quality-status --config /absolute/path/quality.properties \
  --route-version 123
```

`ACADEMY`에서 쓰기 명령을 실행할 때는 `quality-rebuild` 인자에 `--approval /absolute/path/approval.json`을 추가한다. `quality-preview`와 `quality-status`는 읽기 전용이다.

| 명령 | 처리 범위와 결과 |
| --- | --- |
| `quality-preview` | `until`까지의 최근 32개 수집 배치를 표본으로 삼아 배치 수·관측 수·70석 초과 관측 수를 반환한다. 전체 이상 건수나 최종 제외 편도 수는 아니다. |
| `quality-rebuild` | 호출당 1~100개 배치에서 이상 차량을 찾고, 대기 중인 차량 한 대의 조사 페이지를 한 번 처리한다. 차량 조사 페이지는 최대 32개 배치다. |
| `quality-status` | 차량별 조사 단계와 저장된 진행 위치를 조회한다. 빈 차량 ID의 행은 이상 자료를 찾는 작업의 진행 상태다. |

성공하면 표준 출력에 `{"status":"succeeded","result":...}`를 기록하고 종료 코드 0으로 끝난다. 실패하면 표준 오류에 `{"status":"failed","code":...}`를 기록하고 종료 코드 1로 끝난다. 삭제한 반입 명령은 `UNKNOWN_COMMAND`로 거절한다.

## 반복 실행과 대기

`quality-rebuild`는 같은 노선 버전·`until`·관측 연결 정책으로 재호출하면 저장된 위치부터 이어서 처리한다. `until`은 이상 자료를 찾는 범위의 끝이며, 차량 조사는 그 이후의 관측도 확인해 정상 편도로 복귀했는지 판단한다. 이미 시작한 작업의 범위나 연결 정책을 바꾸면 거절한다.

응답 필드는 구분해서 읽어야 한다.

- `processedBatches`: 이번 호출에서 이상 자료를 찾기 위해 읽은 배치 수.
- `discoveryCompleted`: 지정 범위의 이상 자료 탐색을 마쳤는지 여부. 페이지 크기만큼 정확히 읽었다면 다음 호출에서 남은 자료가 없는지 확인한다.
- `completed`: 이상 자료 탐색과 차량별 조사를 모두 마쳤는지 여부.
- `waitingForObservations`: 이상 자료 탐색은 끝났지만 차량 조사를 더 진행할 관측이 없어 기다리는지 여부.

`completed=false`만 보고 즉시 무한 반복하지 않는다. `waitingForObservations=true`라면 후속 관측이 저장된 뒤 같은 명령으로 재개한다. 정상 다음 편도를 확인하고 조사가 최신 자료까지 따라잡혀야 차량 보류를 해제한다. 완료한 요청을 다시 실행해도 이미 반영한 결과를 중복 반영하지 않는다. 완료된 수동 탐색을 새 범위나 정책으로 초기화하는 명령은 없다.

통계 재생성은 Worker의 정기 작업이 담당한다. 정비 앱은 즉시 통계 재집계, 과거 예측 재발행, 모델 재학습을 실행하지 않는다.

## 실행 구성과 트랜잭션

정비 앱은 DB 연결·트랜잭션·관측 입력 확정·품질 기능만 명시적으로 구성한다. 웹 서버, 자동 스케줄러, GBIS 인증키 검사, 모델 파일 적재, Flyway는 시작하지 않는다. 스키마가 없는 DB에 명령을 실행하면 실패하며 테이블을 자동 생성하지 않는다.

한 번의 명령은 `forecasting`의 응용 서비스가 같은 DB 연결과 트랜잭션으로 처리한다. CLI가 별도로 commit하거나 rollback하지 않는다. SQL 문장은 500ms, 잠금 대기는 100ms로 제한하며 실패하면 해당 호출의 변경을 롤백한다. 이 제한은 호출 전체의 CPU·메모리 사용량을 보장하지 않는다.

Worker의 자동 품질 조사는 별도 실행 경로다. 기본 간격은 10초이며 자동 조사 트랜잭션에는 2초 제한이 있다. `forecast.quality-enabled`를 지정하면 그 값을 따르고, 생략하면 수집 또는 예보가 켜졌을 때 조사 스케줄을 등록한다. 수동 정비와 자동 조사는 같은 품질 규칙과 노선 잠금을 사용한다.

## 관측 간격을 조사하는 SQL

[travel-intervals.sql](travel-intervals.sql)은 노선과 원 관측만 읽는다. 품질 판정이나 연결 정책을 변경하지 않는다. 노선 버전 하나와 6시간 범위부터 시작해 수집 간격, 정류장 방문, 기점·회차지 사이의 출발 간격을 확인한다.

```text
\set route_version_id 123
\set from_at '2026-09-21 08:00:00+09:00'
\set until_at '2026-09-21 14:00:00+09:00'
\i /absolute/path/backend/maintenance-app/src/main/resources/quality/travel-intervals.sql
```

이 SQL은 읽기 전용 트랜잭션에서 `statement_timeout=15s`, `lock_timeout=3s`, `max_parallel_workers_per_gather=0`을 설정한다. 테이블·인덱스·임시 테이블을 만들지 않는다. 오류 후 세션이 트랜잭션 안에 남으면 `ROLLBACK;`으로 끝낸다.

같은 정류장에서 반복된 도착·출발 상태는 한 방문으로 합치고, 조회 시작에 걸쳐 출발 시각이 잘렸을 수 있는 첫 방문은 제외한다. A→B 간격에는 종점 대기가 포함된다. A→B→A가 모두 관측된 경우를 왕복 후보로 보고, A→A만 보인 경우는 반대편 출발 누락 후보로 따로 센다. 관측된 최솟값만으로 물리적인 최소 운행 시간을 정하지 말고 날짜·시간대·관측 공백을 함께 확인한다.

[departure-interval-evidence.sql](departure-interval-evidence.sql)은 2026-09-21에 사용한 노선 버전 ID와 시간 범위를 고정한 과거 검토 자료다. 새 분석에는 변수를 받는 `travel-intervals.sql`을 사용한다. 이번 SAL-134 작업에서 두 SQL을 운영 DB에 실행하거나 관측 연결 간격을 다시 결정하지는 않았다.

## 검증과 과거 기록

SAL-134에서는 실제 정비 Boot JAR와 격리된 PostgreSQL 18로 `MaintenanceProcessTest` 5개를 실행했고 모두 통과했다. 폐기 명령 거절, 조회 명령이 데이터를 변경하지 않는지, 페이지별 재개와 완료 후 재호출, 원 관측 보존, 빈 DB의 자동 마이그레이션 방지, 다른 실행 앱·Flyway·이관 SQL·AWS SDK 미포함을 확인했다. 전체 빌드와 다른 테스트의 최종 결과는 [구현·검증 기록](../../../../../../docs/refactoring/ddd/implementation-progress.md)에서 관리한다.

운영 DB 적용과 운영 규모의 처리 시간은 이번 검증에 포함하지 않는다. SAL-133 당시의 정책 근거, V15 작성 경위와 당시 테스트 수치는 [2026-09-22 변경 이력](../../../../../history/2026-09-22-SAL-133.md)에 남아 있다. 그 기록의 배포 상태와 수치는 당시 시점의 기록이다.
