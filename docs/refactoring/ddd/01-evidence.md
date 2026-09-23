# 기준 소스의 조사 근거와 구현 대조

기준은 PR #70(SAL-133)이 병합된 `9f9c75d`이며, 확인일은 2026-09-23이다. 최초 기준인 `6f70826`과 비교해 코드·SQL·관련 테스트에서 달라진 동작을 반영했다. 소스 조사 당시에는 빌드·테스트·실서버 검증을 실행하지 않았다. 이후 구현 착수 전 같은 기준 코드의 1158개 테스트가 통과했다. 이 기준선 결과와 재설계 구현의 검증 결과는 [진행 기록](implementation-progress.md)에서 구분한다. 경쟁 상황에 관한 설계 판단도 운영 장애를 재현한 결과는 아니다.

아래 1~7절의 소스 사실과 링크는 재설계 전 `9f9c75d`를 기준으로 한다. 그때의 이름·동작을 현재 구현으로 읽지 않도록 구분한다. 현재 소스와의 차이는 마지막 절과 [용어집](glossary.md)에 정리했다.

[설계안](00-design.md)에서는 이 근거를 DDD 개념과 연결해 설명한다. 수집 결과의 구분은 유비쿼터스 언어, 수집 시도와 모델의 식별은 엔티티·값 객체, 함께 저장해야 하는 규칙은 애그리거트, API별 조회 조건은 CQRS 설계의 근거로 사용한다. 이 문서는 각 선택이 실제 코드의 어떤 동작에서 나왔는지 확인하는 자료다.

## 1. 조사 범위와 한계

조사 범위는 backend 4개 모듈의 production Java 파일 목록과 의존성, 수집·예보·평가·통계·모델·API·이관의 핵심 구현과 관련 테스트, 앱 Flyway V1~V16, historical 이관 스키마 V1~V4, Gradle, 배포 digest·실제 JAR 계약, 프론트엔드의 3개 API 소비 계약이다.

기준 커밋의 production Java 파일은 api-app 97개, worker-app 182개, migration-tool 49개, common 2개로 총 330개다. 최초 기준의 317개에서 13개가 추가됐으며 삭제된 파일은 없다. 초기 문서의 test 146개는 이번 집계에 포함하지 않았다. 구현을 이관할 때는 [전체 클래스 배치표](03-class-map.md)의 현재 파일 목록을 기준으로 삼는다. 이 배치표는 파일 이관의 기준이며 모든 실행 경로의 검증을 의미하지는 않는다.

외부 학습 리포지토리의 전체 코드, 최신 Jira·Confluence 변경, 운영 DB 실제 데이터·사용량·성능은 조사에서 새로 검증하지 않았다. 모델 특징의 일치 여부와 실행 비용은 구현 단계에서 검증해야 한다. 실제 예보 입력의 별도 스냅샷 저장은 이번 구현에서 제외한다.

### PR #70 비교 검토에서 바뀐 판단

PR #70은 모델이 지원하는 좌석 범위를 벗어난 관측을 발견했을 때 편도 구간을 조사하고, 그 자료를 예보·평가·통계·학습에 사용할 수 있는지 구분한다. 관측 저장 사실을 전달하는 사건, 중단 후 이어갈 수 있는 조사 상태, 품질 버전, 용도별 조회 조건이 추가됐다. 보드는 예보가 없는 차량도 표시하고 예보 제공 여부를 별도 값으로 응답한다.

동시성 보강도 반영됐다. 모든 종결 평가가 `PENDING`을 확인하고, 예보 생성·평가·품질 변경·통계 생성이 노선 잠금을 공유한다. 초기 설계안에서 지적했던 평가 조건 누락과 통계 `MAX+1`의 분리 실행을 현재 코드의 미해결 문제로 다시 적지 않는다. 전면 재설계에서는 이 보장을 담당할 객체와 트랜잭션을 명시하고 그대로 유지한다.

온라인 통계에서 historical seed와 live 자료를 합치던 SQL도 제거됐다. 과거 seed를 다시 온라인 입력에 넣는 일은 이번 구조 변경의 일부로 자동 적용할 수 없으며, 별도의 업무 계약 변경이다.

### 완료된 이관 기능에 대한 설계 결정

S3→RDS 이관이 끝났고 관련 일회성 코드를 삭제할 수 있다는 결정을 반영했다. [PR #61](https://github.com/woowacourse-teams/2026-salmonbus/pull/61)에서도 archive 반입 코드는 이관·검증 후 정리할 대상으로 설명했다. 재설계에서는 S3/archive·반입·seed·임시 모델 전환을 제거하고 PR #70의 품질 정비 명령을 남긴다.

따라서 `forecast-math` 분리안도 철회한다. 현재 온라인 평가·통계가 사용하는 기존 12개 계산·값 타입은 forecasting 내부에 배치한다. 아래 소스 근거는 삭제 전 기준 코드의 사실이며, 목표 구조의 존치 여부는 [전체 클래스 배치표](03-class-map.md)에 별도로 표시한다.

## 2. 핵심 근거 파일

경로는 기준 커밋의 저장소 경로다. 파일 이동 후에는 배치표로 새 위치를 찾는다.

| 확인한 사실 | 근거 |
| --- | --- |
| backend는 common/api-app/worker-app/migration-tool 네 Gradle 프로젝트로 구성됨 | [settings.gradle](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/settings.gradle) |
| 이관 도구가 worker 실행 모듈에 직접 의존함 | [migration-tool/build.gradle](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/migration-tool/build.gradle) |
| 공개 화면 API 3개와 JSON 모델 | [routeForecast.api.ts](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/frontend/src/shared/api/routeForecast.api.ts), [routeForecast.types.ts](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/frontend/src/shared/api/routeForecast.types.ts) |
| quota 예약과 배치 기록, 전송 직전의 독립 커밋 | [ObservationBatchLedger.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/collector/ObservationBatchLedger.java) |
| 같은 계획 재열기와 기존 관측 삭제 | [JpaObservationRepository.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/collector/persistence/jpa/JpaObservationRepository.java) |
| 예보 완료 배치는 재열기를 금지하며 그 외에는 상태를 대입함 | [ObservationBatchJpaEntity.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/collector/persistence/jpa/ObservationBatchJpaEntity.java) |
| 현재 batch 조회에는 명시적 lock이 없음 | [CollectorObservationBatchRepository.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/collector/persistence/jpa/CollectorObservationBatchRepository.java) |
| 노선 등록·버전 적재의 실제 TX 위치 | [RouteCatalogLoader.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/collector/RouteCatalogLoader.java) |
| 동일 버전의 시간표 수정 | [RouteVersionLoader.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/collector/RouteVersionLoader.java) |
| 배치마다 예보를 계산·저장하고 완료를 표시함 | [ForecastBatchWriter.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/ForecastBatchWriter.java) |
| 예보 UPSERT와 행별 저장·평가, 모든 종결 평가의 PENDING 검사·품질 조건·노선 잠금 | [JdbcSeatForecastRepository.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/persistence/jdbc/JdbcSeatForecastRepository.java) |
| 평가 회차 전체와 당일 누계를 같은 TX에서 처리함 | [ArrivalLabelJob.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/ArrivalLabelJob.java) |
| 후속 관측의 판정 규칙 | [ArrivalLabelResolver.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/ArrivalLabelResolver.java) |
| 당일 보정 시점·초기 누계·증가 | [SameDayFullOutcomesService.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/SameDayFullOutcomesService.java) |
| 품질 조건을 적용한 통계 입력과 세대 조회, 입력 읽기 전 노선 잠금 | [JdbcStopDemandStatisticsRepository.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/persistence/jdbc/JdbcStopDemandStatisticsRepository.java) |
| 실제 모델 식별 정보 일치 | [ActiveForecastRuntimeResolver.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/seatdistribution/ActiveForecastRuntimeResolver.java) |
| 모델 활성화·메모리 레지스트리 | [BundleActivation.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/seatdistribution/BundleActivation.java), [LoadedBundleHolder.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/seatdistribution/LoadedBundleHolder.java) |
| 새 모듈이 현재 배포 digest 대상에서 빠질 수 있음 | [buildspec.yml](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/buildspec.yml); [install.sh](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/deploy/scripts/install.sh) |
| 기준 배포 절차는 API 먼저, Worker 다음이며 앱별 롤백을 지원함. 재설계 전환은 오프라인 일괄 전환으로 별도 결정 | [deploy/RUNBOOK.md](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/deploy/RUNBOOK.md) |
| historical CLI가 요구하는 앱 V1~V16 및 historical V1~V4 | [HistoricalSchema.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/migration-tool/src/main/java/com/gustler/backend/migration/db/HistoricalSchema.java) |
| 노선별 통계 생성 전체를 하나의 TX로 실행 | [StopDemandStatisticsWriter.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/StopDemandStatisticsWriter.java) |
| 관측 저장과 품질 조사 요청을 같은 TX에서 연결 | [VehicleObservationsStored.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/common/src/main/java/com/gustler/backend/observation/VehicleObservationsStored.java), [TripQualityRepository.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/TripQualityRepository.java) |
| 편도 판정과 역방향 경계 탐색 | [OneWayTripClassifier.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/OneWayTripClassifier.java), [ReverseBoundarySearch.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/worker-app/src/main/java/com/gustler/backend/processor/ReverseBoundarySearch.java) |
| 품질 조사 상태·버전·용도별 view·편도 근거 FK | [V15__one_way_trip_quality.sql](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/common/src/main/resources/db/migration/V15__one_way_trip_quality.sql), [V16__trip_quality_boundary_candidate.sql](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/common/src/main/resources/db/migration/V16__trip_quality_boundary_candidate.sql) |
| 예보와 독립된 접근 차량 선택·모델 표시 | [BoardQueryService.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/api-app/src/main/java/com/gustler/backend/api/board/application/BoardQueryService.java), [BoardVehicleObservationEntityRepository.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/api-app/src/main/java/com/gustler/backend/api/board/persistence/jpa/BoardVehicleObservationEntityRepository.java) |
| 예보 조회의 원천 품질 필터와 AVAILABLE/UNAVAILABLE 응답 | [SeatForecastEntityRepository.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/api-app/src/main/java/com/gustler/backend/api/board/persistence/jpa/SeatForecastEntityRepository.java), [ForecastResponse.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/api-app/src/main/java/com/gustler/backend/api/board/dto/ForecastResponse.java) |
| 이관 CLI의 품질 조사·재개와 업무 구현 직접 호출 | [TripQualityMaintenance.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/migration-tool/src/main/java/com/gustler/backend/migration/quality/TripQualityMaintenance.java) |

## 3. 수집·노선 보존 계약

| ID | 보존할 의미 | 목표 설계의 위치 |
| --- | --- | --- |
| C1 | 실제 HTTP를 보내기 전에 예약·전송 의도를 영속 기록으로 남김 | 관측 응용 서비스와 호출 한도 TX |
| C2 | 호출 한도 부족/전송 전 중단/전송 후 불명/외부 API 오류/정상 빈 응답을 구분 | CollectionBatch 상태와 실패값 |
| C3 | 같은 attemptKey 재실행은 실제 재호출이며 quota를 다시 사용할 수 있음 | CollectionPlanKey와 AttemptToken 분리 |
| C4 | attemptNumber는 HTTP 성공 횟수나 예약한 호출 횟수와 같지 않음 | revision은 식별·충돌 검사에 사용 |
| C5 | SUCCESS_ROWS/EMPTY는 외부 API 응답 행 수 기준. 모든 행이 제외돼도 정상 빈 응답으로 바꾸지 않음 | 정규화 결과에 provider/stored/excluded 수 보존 |
| C6 | 알 수 없는 좌석과 실제 0석은 다름 | RemainingSeats 값 모델 |
| C7 | 정류장 identity는 버전+순번, 관측시각은 batch.response_received_at | 카탈로그 계약, 관측 공개 계약 |
| C8 | 정류장 FK·차량 유일키 오류는 현재 수집 결과 저장 전체를 롤백시킬 수 있음 | 저장 실패와 HTTP 실패를 구분 |
| C9 | 노선정보 HTTP는 버전 저장 TX 밖에서 호출하며 현재 버전이 있으면 bootstrap 호출 생략 | 카탈로그 유스케이스 |
| C10 | digest가 같고 시간표만 다르면 같은 버전을 수정 | Route.reviseTimetable |
| C11 | 슬롯별 수집 주기·서울 자정·외부 API별 한도는 이름 변경과 독립된 정책 | 순수 SchedulePolicy + Worker Trigger + Budget |
| C12 | 관측행 저장과 새 품질 조사 요청을 같은 TX에 남김 | observations 공개 사건 계약과 forecasting의 동기 처리 |

강제 종료 뒤 RESERVED/DISPATCHING 배치를 자동 복구하는 잡은 현재 조사에서 확인하지 못했다. 새 설계에 복구 작업을 넣더라도 경과시간만으로 요청하지 않았다고 단정할 수 없다. 전송 여부가 불명인 배치는 그 상태를 유지하고 새 호출은 새 token으로 호출 횟수를 예약해 처리한다.

## 4. 예보·평가·통계 보존 계약

| ID | 보존할 의미 | 확인/구현 대상 |
| --- | --- | --- |
| F1 | 한 수집 배치의 예측은 같은 모델·계산시각을 사용하며 0행도 완료로 처리 | ForecastPublication |
| F2 | 실제 적재 번들과 ACTIVE 식별 정보가 일치하지 않으면 발행하지 않음 | ModelIdentity·runtime 준비 |
| F3 | 기본 예측 대상은 앞 1~12정류장, 승차 허용·좌석 알려짐 등 기존 조건 | RouteStops, trajectory, 목표 선택 |
| F4 | 관측 이력의 빈 배치·차량 부재·순번 역행을 모두 보존 | ObservationHistoryReader |
| F5 | 차량 정원은 같은 버전의 cutoff 이전 자료 중 품질 조건을 통과한 최대 관측 좌석으로 추정. 모델의 70석 상한과 실제 제원은 구분 | capacity 계산·모델 지원 범위 계약 |
| F6 | 평가 후보는 generatedAt 이후. 대상 통과/출발 관측과 끊김 규칙 유지 | EvaluationPolicy |
| F7 | 이전 버전의 미평가 예보도 처리 | Outcome 저장소 조회 |
| F8 | 새 SETTLED 중 현재 품질 버전에서 보정에 사용할 수 있는 결과만 누계에 반영하며, 평가와 누계는 같은 TX에서 처리 | 노선 품질 잠금·PENDING 조건·성공분 반환 |
| F9 | 당일 누계 key는 노선·서울 도착일·거리이며 버전별로 임의 분리하지 않음 | DailyCalibration |
| F10 | 수요통계는 horizon=1의 평가 자료와 날짜 가중 계산을 사용 | forecasting.domain.statistics 계산 계약 |
| F11 | 세대 번호와 셀 값을 함께 읽고 원 관측시각·현재 품질 버전에 맞는 세대를 선택하며 조사 중인 버전은 제외 | DemandStatisticsVersion 저장소 |
| F12 | 온라인 통계는 현재 품질 조건을 통과한 자료를 사용하며 historical seed를 합산하지 않음 | 통계 입력 계약과 이관 자료의 분리 |
| F13 | 모델 승격 중에도 진행 중 배치의 runtime handle은 바뀌지 않음 | 불변 모델 레지스트리 |
| F14 | 원 관측값·발행 예측의 보존과 현재 자료 이용 가능 여부를 구분 | 품질 판정·공개 조회 계약 |
| F15 | 조사 시작·완료 등 판정 변경으로 품질 버전을 갱신하며 옛 통계·보정값을 그대로 재사용하지 않음 | QualityRevision과 파생 자료 재계산 |
| F16 | 모든 종결 평가는 PENDING과 원천·평가 관측의 품질 조건을 확인 | Outcome 저장소의 조건부 갱신 |
| F17 | 통계 입력 조회부터 세대 번호 할당·저장까지 같은 TX와 노선 잠금을 사용 | 통계 응용 서비스와 품질 잠금 계약 |

현재 수요 통계 계산 계약명은 `observed-max-capacity-v1`이다. `StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION`에서 관리하는 값이며 모델의 특징 계산 계약과 구분한다.

모델 계약 이름·digest만으로는 Java 특징 계산과 외부 학습 과정의 의미가 일치하는지 확인할 수 없다. 정규화 입력부터 특징 생성과 최종 확률까지 연결한 fixture가 필요하다.

### 4.1 품질 판정과 원본 보존

품질 판정에는 `ELIGIBLE`, `BOUNDARY_UNCONFIRMED`, `EXCLUDED`가 있다. 70을 넘는 좌석 관측은 모델 지원 범위를 벗어났다는 근거이며, 실제 버스가 잘못된 값을 보냈다고 단정하는 근거는 아니다. 편도의 시작과 방향, 관측 사이의 간격을 함께 확인한다. 경계를 확인하지 못한 구간을 임의로 정상 처리하지 않는다.

`forecast_eligible_observation`은 제외·경계 미확정 편도, 범위를 벗어난 관측, 조사 중인 차량을 계산에서 제외한다. 모든 정상 관측에 판정행을 만드는 구조는 아니다. 판정행이 없어도 다른 제외 조건에 해당하지 않으면 사용할 수 있다. `trip_quality_rebuild`의 빈 차량 ID는 과거 자료 전체를 조사하는 커서이며, 그 조사가 진행 중이면 해당 노선 버전의 관측을 제외한다.

품질 처리 때문에 원래 좌석값이나 이미 저장한 예측값을 정상값으로 덮어쓰지 않는다. 다만 현재 코드의 `vehicle_trip_key`처럼 재판정하면서 바뀌는 연결 정보는 있다. 따라서 관측행의 모든 열이 이미 불변이라고 설명하면 안 된다. 목표 설계에서는 원 관측값과 당시 계산 근거를 보존하고, 나중에 내려진 판정 및 현재 이용 가능 여부를 별도로 관리한다. 저장된 근거가 불변이라는 이유만으로 재집계에 모두 포함하면 이미 제외된 자료가 다시 통계에 들어갈 수 있다.

용도별 필터도 구분한다. API는 예측의 원천 관측이 현재 예보에 적합한지 확인한다. `quality_eligible_seat_forecast`는 원천·평가 관측의 적합성과 같은 노선 버전·차량·방향을 확인한다. 보정용 view는 현재 품질 버전 일치까지, 학습용 view는 평가 완료 조건까지 적용한다. 온라인 수요 통계는 품질을 통과한 예보·관측에서 다시 계산하며, 옛 품질 버전의 통계 세대는 조회에서 제외한다.

현재 예보 생성은 노선 잠금을 확보한 뒤 당일 누계를 읽거나 초기화한다. 평가는 노선 ID 순으로 같은 잠금을 확보하고, `ArrivalLabelJob`의 TX 안에서 성공한 평가 결과를 누계에 반영한다. 통계는 `StopDemandStatisticsWriter`의 TX 안에서 노선 잠금·입력 조회·`MAX+1`·저장을 수행한다. 새 설계에서 클래스나 테이블을 나누더라도 이 공유 잠금을 중간에 풀어서는 안 된다.

기준 코드에는 historical seed·cutover 테이블과 이관 도구가 남아 있지만, 이번 설계에서는 완료된 S3→RDS 이관·seed·임시 모델 전환 코드를 삭제한다. 현재 온라인 통계는 seed 합계를 읽지 않는다. 이미 적재한 관측, DB 변경 이력, 학습 view와 모델 제외 기록은 코드 삭제와 별도로 다룬다.

## 5. 승객 API는 서로 다른 읽기 모델이다

| 항목 | 예보 보드 /board | 실시간 차량 /vehicles |
| --- | --- | --- |
| 선택 대상 | 현재 버전의 성공+예보 완료 배치 | 현재 버전의 최신 시도 |
| 정렬 | response_received_at DESC, batch id DESC | scheduled_at DESC, attempt_number DESC, id DESC |
| 최신 수집 실패 | 이전 완료 예보가 신선하면 계속 사용 | 200 UNKNOWN, 이전 차량 재사용 금지 |
| 정상 빈 응답 | 예측 0행도 완료로 표현 | NO_VEHICLES_OBSERVED |
| 관측 차량은 있지만 예보 조회 결과는 0행 | 접근 차량을 유지하고 forecast.status는 UNAVAILABLE | 원 관측 차량과 현재 좌석을 유지 |
| 외부 API 응답 행은 있지만 해석 가능한 차량 0 | 저장값/배치 처리 규칙에 따라 예보 조회 | UNKNOWN |
| 5분 초과 | 503 NO_RECENT_OBSERVATION | 200 UNKNOWN |
| 시각 | 선택한 수집 완료 배치의 응답시각 | 마지막 정상 시도의 응답시각 |
| 응답 일관성 | 서비스 REPEATABLE_READ | 서비스 REPEATABLE_READ |

현재시각이 정확히 5분 경계에 있으면 아직 유효하다. /routes는 readOnly지만 현재 서비스에 REPEATABLE_READ 지정은 없다.

추가 보존 사항:

- 접근 차량은 선택한 배치·노선 버전의 원 관측행에서 고른다. `정류장 순번 - passedStopOrder`가 1~12인 차량을 거리순, 차량 ID 오름차순(null은 마지막), `sourceRowNumber`순으로 정렬해 정류장별 최대 3대를 표시한다. 승차 불가 정류장은 빈 목록이다.
- 예보가 없는 가까운 차량도 최대 3대에 포함된다. 예보를 가진 차량을 먼저 고르지 않는다. 예보는 `targetStopOrder + sourceRowNumber`로 연결하며 차량 ID가 없거나 같아도 행을 구분한다.
- 예보 없음·품질 제외·유효하지 않은 확률은 차량을 지우지 않고 `forecast: {"status":"UNAVAILABLE"}`로 표시한다. 이때 확률·기대 잔여석 필드는 생략한다. 실제 확률 0은 `AVAILABLE`이며 `seatAvailableProbability: 0.0`이다.
- 정상 예보의 빈자리 확률은 `1 - 만석확률`이다. 기대 잔여석만 잘못되면 `AVAILABLE`과 확률을 유지하고 `expectedSeats`만 생략한다.
- 모델 표시는 원천 품질 필터를 통과한 예보 조회 결과로 결정한다. 결과가 있으면 그 모델을 표시하며 현재 RETIRED여도 된다. 여러 모델이 섞이면 오류다. 결과가 0행이면 현재 ACTIVE를 표시하므로, 물리 예보행이 남아 있어도 품질 제외 후 ACTIVE로 표시될 수 있다.
- 모델 선택은 확률 검증·거리 제한·최대 3대 선정보다 먼저다. 화면상 모든 차량이 `UNAVAILABLE`이라는 이유만으로 현재 ACTIVE를 표시한다고 해석하지 않는다. 어떤 경우든 ACTIVE가 없으면 현재는 오류다.
- API 예보 조회는 원천의 `forecast_eligible`을 확인하며 `quality_revision`이나 평가 관측의 품질 조건을 직접 검사하지 않는다. 보정·학습 view와 같은 필터로 합치지 않는다.
- `/vehicles`는 원래 관측 좌석을 유지한다. 모델 범위 초과로 예보에서 제외한 82석도 현재 좌석으로는 그대로 응답할 수 있다. 품질 제외는 원 관측이나 저장 예보의 삭제와 다르다.
- /routes의 FORECAST_READY는 전역 ACTIVE 존재 기준이다. 노선별 예보 가능 여부와 같지 않다.
- API id는 현재 sourceRouteId를 유지하며 DB 내부 route id나 publicRouteId로 바꾸지 않는다.
- Board 캐시 구간은 관측시각, Vehicles는 응답 현재시각 KST를 사용한다. 값은 15/20/600초이며 5분 freshness에 맞춰 TTL을 줄이지 않는다.
- /routes는 max-age=300이다. 오류의 code/message/requestId·no-store·Retry-After 부재 계약도 유지한다.
- route 어댑터와 board/vehicle 어댑터의 DB 예외 변환 범위는 현재 다르다. 이 범위를 통일하려면 응답 변경으로 별도 관리해야 한다.

근거 테스트는 `BoardApiContractTest`, `LiveVehicleApiContractTest`, `RouteApiContractTest`, 서비스 테스트, `*DatabaseFailureContractTest`다. 소스 조사와 이후의 기준선 테스트 실행을 구분하며, 이 사실만으로 재설계 후 동시 쓰기의 일관성까지 검증된 것은 아니다.

## 6. 기존 설명에서 수정한 부분

같은 기준 커밋의 클라이언트 호환성에는 확인할 문제가 남아 있다. 백엔드 [ApproachingVehicleResponse.java](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/backend/api-app/src/main/java/com/gustler/backend/api/board/dto/ApproachingVehicleResponse.java)는 확률과 기대 좌석을 `forecast` 객체 안에 반환하지만, 프론트의 [routeForecast.types.ts](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/frontend/src/shared/api/routeForecast.types.ts)와 [arrivalPolicy.ts](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/frontend/src/pages/verdict-board/arrivalPolicy.ts)는 아직 차량의 최상위 필드를 읽는다. [client.ts](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/frontend/src/shared/api/client.ts)는 성공 응답을 타입 단언만 하며 이 구조를 변환하지 않는다. 해당 코드 조합에 새 응답을 전달하면 확률이 undefined로 읽히고 [seatGrade.ts](https://github.com/woowacourse-teams/2026-salmonbus/blob/9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94/frontend/src/pages/verdict-board/seatGrade.ts)의 기본 분기인 veryLow로 처리되는 경로가 있다. 이는 소스로 확인한 계약 불일치이며 운영 화면에서 재현한 결과는 아니다. 프론트엔드 수정과 실제 화면 소비 경로의 검증은 이번 범위에서 제외한다. 백엔드는 PR #70의 응답 계약을 유지한다.

| 종전 설명/암묵적 가정 | 실제 확인 | 설계 영향 |
| --- | --- | --- |
| collector/processor/api가 이미 확정된 DDD 경계 | 처리 단계·실행 역할은 구분돼 있으나 업무 경계는 추가 판단 필요 | 노선·관측·예보 책임으로 재설계 |
| domain 후보는 전부 Spring 의존이 없음 | D안 트리에 Spring Trigger 구현이 포함됨 | scheduler/trigger는 Worker 진입 어댑터 |
| 계층화는 파일 이동만으로 모두 해결 | application이 구체 GBIS 클래스의 호출 횟수 상수도 참조함 | 외부 API 호출 한도 인터페이스까지 분리 |
| 기존 batch의 잠금·상태기계는 완성됨 | 명시적 lock/CAS가 없고 여러 행동은 상태를 단순 대입함 | token 전파·허용 전이·DB 경쟁 검증 |
| 예보는 단일 bulk INSERT/UPDATE | 현재 save/settle은 행별로 SQL을 반복함 | JDBC batch 적용은 가능하나 성능 개선 효과는 별도 측정 |
| 모델/통계 ID만 있으면 과거 예보 재현 가능 | 실제 보정 입력·가용시각·가변 이력의 근거 데이터가 부족함 | 계산 근거 데이터와 backtest 계약 구분 |
| 한 애그리거트는 전체 수명주기가 한 TX | 한 객체는 여러 명령/TX에 걸쳐 변할 수 있음 | 수집 기록의 단계별 커밋 유지 |
| 노선 버전은 완전 불변 | 시간표는 같은 버전에서 수정함 | 복사한 읽기 결과와 저장 이력의 불변성을 구분 |
| 모든 조회 서비스가 RR | RouteQueryService는 readOnly만 지정함 | 실제 계약 기준 검증 |
| historical seed는 온라인 통계의 필수 입력 | PR #70에서 seed+live 결합 제거. 현재는 이관 감사·도구 자료로 잔존 | 온라인 재도입은 별도 계약 변경, 삭제는 남은 소비 코드 확인 후 판단 |
| 기존 이관 CLI의 스키마 기준 | 기준 코드의 HistoricalSchema는 앱 V1~V16과 historical V1~V4 요구 | 이관 전용 Java 검사는 제거하고 운영·학습에 필요한 SQL 자원과 새 정비의 사전 검사 책임은 분리 |
| 비SETTLED 평가에는 PENDING 검사가 없음 | PR #70에서 모든 종결 평가에 조건 추가 | 새 설계에서도 조건부 갱신 유지 |
| 통계 MAX+1과 저장이 서로 다른 TX | 노선 잠금과 통계 Writer TX 안에서 함께 수행 | metadata 도입 후에도 기존 직렬화 보존 |
| 보드의 접근 차량은 예측행에서 선택 | 원 관측으로 차량을 고른 뒤 예보 제공 상태를 연결 | 차량 존재와 예보 이용 가능 여부를 분리 |

## 7. 새 설계에서 보존하거나 보강할 정확성 항목

PR #70에서 이미 보강한 부분과 전면 재설계에서 추가할 책임을 구분한다. 아래 작업도 하나의 재설계 PR에 담되, 단순 이동과 구분해 변경 이유와 검증 결과를 제시한다.

1. 현재의 노선 잠금을 유지하면서 source 시도 식별과 유일 발행을 명시한다. 중복 발행 요청은 저장된 결과를 반환하고, 발행 예측을 UPSERT로 바꾸지 않는 계약으로 옮긴다.
2. 모든 종결 평가의 `PENDING` 조건과 품질 조건을 유지한다. 이 조건은 PR #70에서 추가됐으며 새 설계가 처음 도입하는 보장이 아니다.
3. 누계 초기화·평가·증분을 보호하는 현재의 공유 노선 잠금과 TX를 보존한다. 품질 버전이 바뀐 뒤에는 적합한 자료로 다시 집계하고, 새로 확정됐으며 보정에 사용할 수 있는 결과만 더한다. 호출 경로를 분리한 뒤에도 같은 보장이 유지되는지 검증한다.
4. 통계 입력 조회·`MAX+1`·저장을 직렬화하는 현재 계약을 유지한다. 새 metadata는 세대의 식별·입력 기준·완료 상태를 명시하기 위한 선택이다. 이미 도입된 Writer TX와 노선 잠금을 없는 것으로 취급하지 않는다.
5. 메모리 모델의 digest key와 전체 식별 정보 검사 기준을 맞추고, 활성화 명령은 슬롯 버전·commandId로 구분한다.
6. 불변 평가 근거 데이터를 도입하되 사후 품질 제외를 재집계에 반영한다. 평가 FK만 제거하면 재열기가 안전해지는 것은 아니다. 편도 시작·근거 관측 FK, 조사 cursor·anchor·boundary candidate, 원천 예측 참조까지 확인하고 참조 이관과 삭제 조건을 함께 정한다.
7. 관측 저장 사건은 `observations.api`의 업무 계약으로 옮긴다. 현재의 동기 처리·동일 TX 보장을 유지하며 common을 업무 사건 모음으로 만들지 않는다. 남기는 품질 정비 CLI는 forecasting의 공개 유지보수 계약을 사용하고 Worker 실행 코드나 구체 JDBC 저장소를 직접 생성하지 않는다. 완료된 이관의 계산 공유를 위한 별도 모듈은 만들지 않는다.
8. 새 업무 모듈이 배포 digest에서 빠지지 않도록 모듈 추가와 배포 입력 집합을 같은 단계에서 검증한다. 앱 V1~V16·historical V1~V4에서 다음 스키마로 옮기는 검사도 함께 갱신한다.

도메인 행동·저장소 원자성·DB 제약은 각각 다른 실패를 막으므로 새 설계에서도 DB 제약을 유지한다. 위 항목은 구현·실행 검증을 완료했다는 뜻이 아니다.

## 8. 승인된 구현 범위와 근거의 구분

내부 모델은 [용어집](glossary.md)의 `CollectionBatch`, `ForecastPublication`, `SeatForecast`, `ForecastEvaluation`, `EvaluationResult`, `DemandStatisticsVersion`, `RouteDataQuality`를 기준으로 구현한다. 근거 링크의 기존 소스 이름과 경로는 조사 기준 그대로 둔다.

목표 모듈은 9개이며 호출 한도는 `api-call-quota`, 상시 품질 정비는 `maintenance-app`이 맡는다. 기존 migration-tool의 49개 중 이관 전용 38개는 삭제하고 품질 업무 구현 1개는 forecasting, CLI 지원 10개는 maintenance-app으로 재구성한다. 상세 클래스 배치는 구현 완료 후 다시 대조한다.

운영 전환은 모든 관련 쓰기를 중지한 상태에서 한 번에 수행한다. 이전에 검토한 이중 쓰기·구/신 앱 혼합 운영·앱별 독립 롤백은 채택하지 않았다. 기존 DB 데이터와 적용 이력을 보존하며 실패 시 DB와 대응 앱 릴리스를 함께 복구한다. 실제 예보 입력의 별도 스냅샷 저장과 프론트엔드 수정도 이번 구현에서 제외한다.

## 9. 현재 구현과 대조한 내용

- 수집 계획과 시도는 `CollectionPlan`·`CollectionAttemptToken`이며 상태 규칙은 `CollectionBatch`가 담당한다. 응용 서비스는 `ObservationSource`의 `ObservationResponse`를 사용하고 GBIS 타입은 infrastructure에서만 해석한다. 외부 조회 직후 기록한 수신 시각을 정규화 뒤에도 유지한다.
- 발행은 `ForecastPublicationRepository`, 평가는 `ForecastEvaluationRepository`로 나눴다. `ForecastEvaluationWriter`가 노선별 품질 잠금, 완료 가능 여부, 도착 입력 확정, CAS와 보정을 조율한다. 평가 저장 어댑터는 `CollectionInputs`를 호출하지 않는다. `ArrivalLabelResolver`는 생성 시각과 같거나 이전인 후보를 제외한다.
- V17은 발행·평가·통계 버전·품질·모델 활성화·영구 학습 제외 저장 구조를 추가하고 V18은 새 조회 정의로 전환한다. 원본 값과 기존 migration 이력은 보존한다.
- 실제 평가 근거는 forecast_evaluation에 함께 저장한다. 수집 시도 revision이나 별도 판정 이유 컬럼을 추가한 구조는 아니다. 통계 input_checkpoint는 현재 NULL이며 체크포인트 기반 중복 산출 방지를 구현했다고 보지 않는다.
- 품질 저장 연동은 `ObservationLoader`의 명시적인 `CollectionQualityHook` 호출로 진행한다. 저장소가 새 `StoredObservations`를 반환한 경우에만 저장 후 조사를 등록한다. 조사 시작·재시작과 차량별 첫 이상 선택은 `TripQualityInvestigation`·`QualityObservationBatch`의 정책이다.
- 응용·도메인 계층의 GBIS 타입 의존과 저장 어댑터의 공개 유스케이스 호출을 구조 검사로 제한한다. 모델 전체 식별 정보와 요청 ID·기대 활성 버전은 비교하지만 featureContractVersion 선언과 실제 특징 정책의 의미 일치 강제는 남은 한계다.
- maintenance-app은 최소 Spring context에서 품질 명령 하나를 실행한다. Flyway·스케줄·모델 기동은 실행하지 않으며 별도 DB 스키마 사전 안내 기능은 없다.
- 프론트엔드의 중첩 forecast 계약 불일치는 이번 변경에서 수정하지 않았다. 실제 예보 입력의 별도 저장도 제외해 과거 계산의 정확한 재현을 보장하지 않는다.

현재 코드를 확인할 때는 [V17](../../../backend/common/src/main/resources/db/migration/V17__ddd_storage_expansion.sql), [V18](../../../backend/common/src/main/resources/db/migration/V18__ddd_storage_cutover.sql), [CollectionInputs](../../../backend/business/observations/src/main/java/com/gustler/backend/observations/api/CollectionInputs.java), [ForecastBatchWriter](../../../backend/business/forecasting/src/main/java/com/gustler/backend/forecasting/application/publication/ForecastBatchWriter.java), [평가 응용 서비스](../../../backend/business/forecasting/src/main/java/com/gustler/backend/forecasting/application/evaluation/ForecastEvaluationWriter.java), [평가 저장소](../../../backend/business/forecasting/src/main/java/com/gustler/backend/forecasting/infrastructure/jdbc/JdbcForecastEvaluationRepository.java)를 사용한다. 최종 테스트 수와 실행 결과는 [진행 기록](implementation-progress.md)에서 확인한다.
