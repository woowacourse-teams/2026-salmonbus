# SAL-133 D — 변경 범위와 DB 작업 축소 후 설명

- 기준: `256a88d8060172f669aff599dfdd2398dd4085b7` 이후 미커밋 D 작업과 패키지 이동 원복.
- 유지한 기능: 70 초과 편도 전체 제외, 정상 차량/다음 편도 유지, 기존 예측 제공 중단, 보정·통계·학습 재사용 차단, 과거 재판정.
- 상태: 로컬 코드와 격리 PostgreSQL 검증. 운영 DB 접속·운영 마이그레이션·커밋·배포 없음.
- 아래 숫자는 합성 테스트와 코드 비교다. RDS의 실제 처리 시간·메모리·CPU 감소율이 아니다.

## 1. 저장 구조: 새 테이블 5개에서 2개로

관측별 `observation_trip_membership`, 설정용 `observation_quality_policy`, 버전용 `route_quality_revision` 테이블을 제거했다.

| 정보 | 현재 저장 위치 | 이유 |
| --- | --- | --- |
| 관측이 속한 편도 | 기존 `vehicle_observation.vehicle_trip_key` | 원래 편도 연결용으로 마련된 파생 열을 활용한다. 원본 좌석/위치/시각은 바꾸지 않는다. |
| 편도 상태와 제외 근거 | 새 `vehicle_one_way_trip` | 70 초과 발견 시 과거 관측 전체를 UPDATE하지 않고 편도 한 행의 상태를 바꾼다. |
| 선택적 관측 간격과 근거 | 기존 `route_version`의 새 열 두 개 | 노선 판본당 설정 하나이므로 별도 테이블/조인이 필요 없다. |
| 계산 자료 유효 버전 | 기존 `route.quality_revision` | 판정 때마다 버전 행 INSERT를 시도하지 않는다. 기존 노선 잠금 행과 일치한다. |
| 과거 정리 진행 위치 | 새 `trip_quality_rebuild` | 중단/롤백 후 재개와 정리 중 사용 보류를 유지한다. |

편도 ID는 시작 관측 ID의 문자열이고, 편도 테이블에 `start_observation_id`도 보존한다. 기존 키가 숫자가 아니어도 원본 열을 숫자로 강제 변환하지 않는다. `ix_one_way_trip_vehicle`는 작은 편도 테이블에만 추가한다. 기존 대형 관측 테이블에 새 인덱스를 만들지 않는다.

현재 구현도 각 새 관측의 편도 키는 저장한다. 관측마다 별도 연결 INSERT를 호출하는 대신 한 묶음의 UPDATE로 처리하며, 같은 키는 다시 쓰지 않는다. SQL 호출 감소가 수정하는 관측 행 수를 0으로 만든다는 뜻은 아니다.

관련: [V15](../common/src/main/resources/db/migration/V15__one_way_trip_quality.sql), [TripQualityRepository](../worker-app/src/main/java/com/gustler/backend/processor/TripQualityRepository.java).

## 2. 실행 흐름: 별도 스케줄러 제거, 모델과 판정의 독립성 유지

```text
ForecastJob.writeForecasts
  → ForecastBatchWriter.assessRecentBatches
      → TripQualityRepository.assessBatch (관측 묶음별 독립 트랜잭션)
  → 활성 모델 확인
  → 기존 예측 생성 흐름
```

`TripQualityJob`은 제거했다. 활성 노선별 최근 5분, 최대 20묶음이라는 판정 범위는 유지한다. 대상 조회는 노선별 반복 SQL 대신 한 SQL로 수행한다. 5분은 처리 범위이며 편도 연결 임계값이 아니다.

모델이 없어도 먼저 품질을 판정한다. 예측 직전의 재확인도 유지하되 이미 판정한 묶음은 조회 1회로 반환한다. 모델 계산 오류가 나더라도 먼저 커밋된 제외 판정은 유지한다. 일반 SQL/계산 오류를 차량 범위 오류로 바꾸거나 무시하지 않는다.

관련: [ForecastJob](../worker-app/src/main/java/com/gustler/backend/processor/ForecastJob.java), [ForecastBatchWriter](../worker-app/src/main/java/com/gustler/backend/processor/ForecastBatchWriter.java).

## 3. 판정 저장: 차량별 SQL 호출을 묶는다

변경 전에는 차량마다 판정 여부 조회, 직전 원본 조회, 연결 INSERT를 실행했다. 변경 후에는 현재 묶음과 직전 관측들을 함께 읽고 Java에서 판정한 다음, 새 편도/제외 전환/관측 키를 종류별 집합 SQL로 저장한다. 값은 모두 바인딩하며 문자열을 SQL 값으로 직접 붙이지 않는다.

| 조건 | 이전 코드의 SQL 수 | 현재 격리 DB 테스트 |
| --- | --- | --- |
| 30대 모두 기존 편도 계속 | 코드 경로상 97회 | 5회 |
| 1대 모두 기존 편도 계속 | 코드 경로상 10회 | 5회 |
| 1대/30대 중 한 차량이 처음 제외됨 | 차량 수와 분기에 따라 증가 | 각각 7회 |
| 이미 판정한 묶음 재확인 | 공통 조회와 차량별 확인 반복 | 1회, 재저장 없음 |

이는 `assessBatch` 호출 하나의 JDBC SQL 수이며 예측 전체의 SQL 수가 아니다. SQL 내부에서는 차량별 직전 관측 탐색이 남는다.

처음 나타나 판정된 편도가 전혀 없는 차량은 작은 편도 테이블에서 확인하고 큰 과거 관측 탐색을 생략한다. 판정된 편도가 있으면 직전 **원본**부터 확인한다. 그 원본이 미판정이라고 해서 더 오래된 적격 관측으로 건너뛰지 않는다.

## 4. 편도 규칙과 시간 조건

`OneWayTripClassifier`는 DB 없이 입력만 판정한다. 39석 증가 자체는 제외하지 않고, 같은 편도에서 70 초과를 발견하면 그 편도를 제외한다. 같은 편도에서 다시 70 이하가 되어도 제외를 유지한다.

기점/회차지 출발과 방향 전환을 먼저 판정한다. 설정한 시간 제한은 같은 편도로 계속 연결할 때 적용한다. 따라서 긴 공백 뒤 종점 직전→기점 다음 순번 전환도 새 편도 경계로 판단한다. 이 순서는 사용자와 합의했지만 이전 코드에서는 시간 조건 안에 방향 판정이 들어 있어 이번에 바로잡았다.

시간 정책은 여전히 미설정이며 60초/300초를 운영 기본값으로 넣지 않았다. 시간 기준이 없고 전체 왕복 관측이 빠진 경우 같은 방향의 뒤쪽 순번을 이전 편도로 연결할 가능성은 남는다. 편도 경계 확인은 단말기 초기화 확인과 다르다.

관련: [판정기](../worker-app/src/main/java/com/gustler/backend/processor/OneWayTripClassifier.java), [판정 테스트](../worker-app/src/test/java/com/gustler/backend/processor/OneWayTripClassifierTest.java).

## 5. 예측 입력과 API: 적격 조건 유지, 중복 조회 감소

`JdbcVehicleTrajectoryRepository`는 위치를 유지하고 사용 불가 좌석만 `QUALITY_WITHHELD`로 읽는다. 이미 읽은 이 상태로 현재 예측 대상을 결정하므로 별도 적격 ID 조회를 제거했다. 제외 대상 차량의 과거 최대 잔여석 조회도 생략한다. 정상 관측의 좌석 결측과 품질 제외는 계속 구분한다.

API의 예측 조회는 `forecast_eligible_observation` 조건을 유지한다. 기존 예측 행을 삭제하지 않아도 제외된 편도는 새 응답에서 `UNAVAILABLE`이다. 관측 차량, 거리순 최대 3대, 신선도, 노선 판본, 대상 정류장과 `/vehicles`의 원본 현재 좌석은 유지한다.

뷰는 관측→편도를 바로 연결하여 별도 membership 조인을 제거했다. JPQL에서 이 뷰를 참조하는 읽기 전용 엔티티는 유지했다. 이를 없애려고 API의 조회 방식까지 다시 바꾸지는 않았다.

관련: `JdbcVehicleTrajectoryRepository`, `SeatUnknownReason`, `VehicleTrajectoryAssembler`, API `SeatForecastEntityRepository`, `ForecastEligibleObservationJpaEntity`.

## 6. 결과 평가·보정·통계·학습: 기능을 제거하지 않았다

- `ArrivalCandidate`, `PendingForecast`, `ArrivalLabelResolver`: 같은 편도에서만 도착 결과를 연결한다. 미판정은 기다리고 다른 편도/제외 관측은 연결을 중단한다. 기존 90초 공백과 최대 2시간 대기는 유지한다.
- `JdbcArrivalObservationRepository`: 관측 키로 편도를 찾는다. 별도 연결 테이블 조인은 제거했다.
- `JdbcSeatForecastRepository`: 저장 시 현재 품질 버전을 기록하고, 결과 저장 때도 같은 적격 편도인지 확인한다. 당일 보정은 현재 품질 버전만 사용한다.
- `JdbcStopDemandStatisticsRepository`: 유효한 관측/실제 결과로 집계하고 현재 품질 버전의 통계만 읽는다. 버전 조회는 기존 route 행을 사용한다.
- `StopDemandStatisticsWriter`와 Job: 입력 조회부터 통계 저장까지 노선별 트랜잭션으로 유지한다. 품질 변경과 계산이 엇갈리지 않도록 필요한 노선 잠금은 남겼다.
- V15와 historical V4: 기존 학습 제외 조건과 품질 조건을 함께 유지한다.

이전 구현의 보수적 정책도 유지했다. 편도 제외 시 같은 노선의 이전 보정/통계 버전을 사용하지 않으며, 편도별 근거가 없는 seed 합계는 섞지 않는다. 이 정책 때문에 정상 자료의 재사용도 일부 보류될 수 있다. 저장 구조를 줄였다고 그 영향까지 없어진 것은 아니다. 기존 모델의 자동 재학습과 이미 전달한 응답의 회수는 구현하지 않았다.

## 7. 과거 자료: 같은 판정기로 제한된 묶음만 처리

`TripQualityMaintenance`와 `MigrationCli`의 preview/rebuild/status는 유지한다. 최대 100묶음 처리, 진행 위치 저장, 롤백 후 재개, 같은 요청의 완료 후 무변경, 진행 중 정책 변경 거부를 유지한다.

연결 테이블 조회/삭제를 기존 관측 키의 조회/해제로 바꿨다. 종료 시각 뒤의 기존 연결은 해제하여 재판정하도록 한다. 원본 좌석과 저장 예측은 삭제하지 않는다. 시간 설정을 읽는 JDBC 정수 타입 오류를 실제 DB 테스트에서 발견해 수정했다.

과거 정리 중 해당 노선 판본은 사용을 보류한다. 통계는 기존 정기 작업으로 다시 만들며 과거 예측을 자동 재발행하지 않는다. 자세한 운영 절차는 [quality README](../migration-tool/src/main/resources/quality/README.md)에 있다.

## 8. 패키지와 테스트

`ForecastBatchWriter`, `SeatForecastModel`은 기존 `processor`로 복귀했다. 신규 `SeatRangeException`, `OneWayTripClassifier`, `TripQualityRepository`와 대응 테스트도 기존 processor 패키지에 둔다. 구체 모델의 `processor.seatdistribution`은 그대로다. 삭제한 이전 경로와 복귀한 경로를 함께 커밋해야 한다.

`ConfirmedTripFixture`는 다른 기능 테스트의 적격 편도 준비를 돕는 용도로 유지하고, 연결 INSERT를 키 UPDATE로 바꿨다. 이 도우미가 실제 편도 판정의 정확성을 보장하지는 않는다. 품질 규칙은 판정기/저장소 테스트에서 별도로 확인한다.

새 회귀 테스트는 given/when/then과 구체적인 이름을 사용한다. SQL 호출 수, 이미 판정한 묶음의 무변경, 전체 편도 제외, 정상 차량 유지, 다음 편도 회복, 모델 없는 판정, 외부 예측 트랜잭션 롤백 후에도 제외 판정 유지, 과거 재판정의 중단/재개를 확인한다.

## 검증 범위

Java 21과 캐시 JAR의 javac/JUnit으로 순차 실행한다. DB 검증은 운영 DB가 아닌 일회용 PostgreSQL 18 컨테이너에서 실행한다. 저장소 테스트 본문은 그대로 사용하고 로컬 실행용 임시 코드에서 테스트 DB 연결 설정만 대체한다. 실제 Gradle/Testcontainers 기본 실행 경로 전체를 검증했다고 표현하지 않는다.

컨테이너는 CPU 1개, 메모리 128MiB로 제한한다. JVM은 ActiveProcessorCount=1, heap 256/384MiB다. JVM 설정만으로 호스트 전체 CPU/메모리 상한을 보장하지 않는다.

SQL 호출 수 테스트는 차량 1대/30대의 합성 자료다. 실행 계획은 500묶음 × 30대 = 15,000개 합성 관측에서 기존 차량과 처음 나타난 차량을 별도로 확인한다. 한 쿼리의 실행 시간/정렬 메모리를 RDS 전체 부하나 쿼리 전체 메모리로 확대 해석하지 않는다.

운영 규모의 긴 관측 공백·대량 과거 재판정·동시 수집과 잠금 대기, 전체 Gradle 빌드, 기존 운영 DB의 V14/V15 적용 순서는 미검증이다. 기능에 필요한 새 관측 키 저장·편도 상태 조회·잠금이 있으므로 추가 DB 비용이 0이라고 주장하지 않는다. 현재 검증 결과는 [변경 이력의 최신 항목](2026-09-21-SAL-133.md)에 기록한다.
