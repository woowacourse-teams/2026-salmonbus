# 현재 구현의 업무 용어와 진입점

재설계 전 기준 소스는 PR #70의 `9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94`다. 이 문서는 이 브랜치의 `src/main` 코드의 이름을 정리한다. 초기 제안의 이름과 기준 소스의 파일은 [클래스 배치표](03-class-map.md)에서 구분해 확인할 수 있다. 테스트와 운영 전환 결과는 [진행 기록](implementation-progress.md)을 따른다.

## 모델과 상태

| 업무 의미 | 현재 구현 | 책임 |
| --- | --- | --- |
| 노선과 버전 | `Route`, `RouteVersion` | `Route.accept`가 유지·시간표 수정·새 버전 개설을 결정한다. |
| 노선 식별자 | `sourceRouteId` | 상류 Open API가 주는 노선 ID다. 수집 설정부터 노선 조회·관측 조회·모델 노선 선택까지 이 값 하나가 흐른다. DB의 `public_route_id`와 `source_route_id`는 지금 같은 값이며 `V1__collector.sql` 14~15행이 공개 ID가 확정되면 둘 중 하나를 지운다고 적는다. 현재 판본 조회는 `public_route_id`로 한다. |
| 판 | `CollectionBatch`(수집) / `ModelRelease`(모델) | 한국어로 "판"이라고 부르는 것이 둘이다. 수집을 한 번 돌린 묶음과 학습 산출물의 배포 버전이다. 코드 이름이 다르므로 대화에서는 "수집 판"·"모델 판"으로 구분해 부른다. |
| 노선 내 정류장 | `RouteStop` | 버전별 목록 안에서 순번으로 구분한다. 별도 RouteStopPosition 클래스는 만들지 않았다. |
| 수집 계획 | `CollectionPlan` | routeVersionId·scheduledAt·attemptKey를 담는다. 같은 계획을 재시도할 때 attemptKey를 유지한다. |
| 관측 조회 계약 | `ObservationSource`, `ObservationResponse` | 외부 조회를 도메인값으로 받는다. 응답에는 결론·정규화한 관측·수신 시각이 포함된다. |
| 새 관측 저장 결과 | `StoredObservations` | 이번에 새로 저장한 관측의 ID·차량·좌석 정보를 전달한다. 같은 결과의 재전달이면 저장소가 빈 Optional을 반환한다. |
| 수집 배치 | `CollectionBatch` | 현재 시도와 예약·전송·완료 상태, 행 수, 입력 확정을 관리한다. |
| 현재 시도 식별 | `CollectionAttemptToken` | batchId·attemptNumber로 이전 응답과 현재 시도를 구분한다. 공개 입력 계약은 `CollectionInput`과 `CollectionInputs`다. |
| 입력 확정 | `inputConfirmedAt`, `confirmInput` | 발행·평가·품질 조사의 근거로 사용한 배치가 재수집으로 교체되지 않게 한다. 사후 품질 사용 조건은 별도로 바뀔 수 있다. |
| 발행 사실 | `ForecastPublication`, `PublishedForecast` | 발행 내용과 저장 후 발행 ID·예측 수를 구분한다. 수집 배치당 발행 하나이며 0행도 기록한다. |
| 개별 예측 | `SeatForecast` | 발행에 속하는 차량·대상 정류장별 확률과 기대 잔여석이다. |
| 예보 평가 | `ForecastEvaluation` | 예측키별 평가 상태와 결과를 한 번 확정한다. |
| 후보 판정·확정 결과 | `ArrivalLabel`, `EvaluationResult` | ArrivalLabel은 PENDING도 포함한다. EvaluationResult는 확정 결과만 허용한다. 서로 다른 역할이므로 두 타입을 유지한다. |
| 통계 버전 | `DemandStatisticsVersion` | 계산 기준과 전체 산출 셀을 묶는다. 집계 결과인 `measurements`가 비어 있으면 버전을 생성하지 않는다. |
| 시간대별 통계 조회 | `StopDemandStatistics` | 선택한 통계 버전에서 특정 시간대의 셀을 조회한 계산 입력이다. |
| 당일 보정 | `SameDayFullOutcomeCount`, `SameDayFullOutcomes` | 저장 누계와 모델에 전달할 보정 입력을 표현한다. 별도 DailyCalibration 애그리거트는 만들지 않았다. |
| 호출 한도 | `DailyCallQuota`, `ApiCallQuota` | 날짜·API별 예약 규칙과 공개 예약 기능을 구분한다. |
| 노선 자료 품질 | `RouteDataQuality` | 노선별 계산 자료의 사용 조건과 revision을 관리한다. |
| 편도 조사·발견 | `TripQualityInvestigation`, `TripQualityDiscovery` | 차량별 역탐색·재판정과 수동 정비의 자료 발견 진행을 구분한다. start/restart가 새 조사·재시작을 결정하며 진행 중 조사는 유지한다. |
| 품질 판단 입력 | `QualityObservationBatch` | firstAnomalies가 같은 배치의 차량별 첫 이상 관측을 고른다. |
| 편도 판정 | `OneWayTripAssessment` | 조사한 편도의 상태와 근거를 기록한다. 정상 관측마다 판정 행을 만들지는 않는다. |
| 모델 식별·선택 | `ModelIdentity`, `ModelRelease`, `ActiveModelSlot`, `ModelActivation` | 전체 식별 정보, 배포 이력, 현재 선택, 활성화 요청 결과를 구분한다. |

## 공개 유스케이스

| 작업 | 공개 타입과 메서드 | 실행 위치 |
| --- | --- | --- |
| 현재 노선 확보 | `CurrentRouteVersion.currentVersionOf` | 수집에서 사용. 현재 버전이 없으면 기존 bootstrap 정책으로 등록 |
| 관측 수집 | `CollectObservations.collectOnce` | Worker 수집 스케줄 |
| 미발행 예보 처리 | `PublishPendingForecasts.writeForecasts` | Worker 예보 스케줄 |
| 실제 결과 평가 | `EvaluateForecasts.settleArrivalLabels` | Worker 평가 스케줄 |
| 통계 갱신 | `RefreshDemandStatistics.recomputeStopDemand` | Worker 통계 스케줄 |
| 온라인 품질 조사 | `InvestigateTripQuality.investigate` | Worker 품질 스케줄 |
| 품질 정비 조회·처리 | `PreviewTripQuality.preview`, `ProcessTripQualityChunk.applyChunk`, `GetTripQualityStatus.status` | maintenance-app |
| 기동 모델 적재 | `LoadConfiguredModel.load` | Worker 기동 구성 |
| 모델 활성화 | `ActivateModel.activate` | 모델 활성화 응용 서비스 |

수집 배치의 실제 행동은 `startAttempt`, `dispatch`, `completeAttempt`, `abandonBeforeSend`, `confirmInput`이다. 수집과 품질 연동 순서는 `ObservationLoader`, 평가의 품질 잠금·입력 확정·저장·보정 순서는 `ForecastEvaluationWriter`가 맡는다. 일반적인 예시 이름과 실제 메서드를 구분한다. getter는 값 조회, `require…`는 조건 검증, `find…`는 조회, 변경 동사는 업무 행동을 나타낸다. JPA·DTO의 접근자는 매핑과 직렬화 계약도 함께 따른다.

## 버전과 식별 정보

- `attemptNumber`는 같은 수집 계획의 현재 시도 번호다.
- 통계의 `revision`은 산출 버전 번호이며 `calculationVersion`은 계산 규칙 버전이다.
- `qualityRevision`은 자료 사용 조건의 버전이다.
- 활성 슬롯의 `version`은 모델 교체 요청의 기대 상태를 검사하는 값이다.

`ModelIdentity`는 releaseId·modelKey·modelVersion·bundleDigest·predictionTargetVersion·calculationVersion·supportedScopeDigest·dataUntil을 비교한다. manifest의 featureContractVersion은 calculationVersion으로 연결된다. 문자열이 비어 있지 않은지 검사하지만 선언한 계약 버전과 실제 특징 정책의 의미가 같은지를 강제하는 검증까지 구현한 것은 아니다.

## 모듈·저장 구조·외부 계약

Gradle 모듈은 route-catalog·observations·forecasting·api-call-quota·gbis-client·api-app·worker-app·maintenance-app·common의 9개다. 업무 컨텍스트는 노선·관측·예보의 3개이며, 상시 실행 앱은 API와 Worker다. maintenance-app은 필요할 때 실행하는 품질 CLI다.

현재 주요 저장 이름은 `forecast_publication`, `forecast_evaluation`, `demand_statistics_version`, `route_data_quality`, `route_version_quality_policy`, `observation_trip_assignment`, `model_active_slot`, `model_activation_request`다. 평가 근거는 forecast_evaluation의 컬럼으로 함께 저장한다. 별도 evidence 테이블이나 실제 예보 입력 스냅샷 저장소는 없다.

내부 이름을 바꿨다는 이유로 HTTP JSON 필드·상태값·ID의 의미를 바꾸지 않는다. 백엔드는 PR #70의 중첩 forecast 응답을 유지하며 프론트엔드 수정은 제외했다. 기존 프론트가 최상위 확률 필드를 읽는 불일치는 남아 있으므로 백엔드 검증을 화면 호환성 검증으로 설명하지 않는다.
