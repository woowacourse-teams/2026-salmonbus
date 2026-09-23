# 구현에 사용할 업무 용어

기준 소스는 PR #70이 병합된 `9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94`다. 아래 이름은 승인된 재설계의 구현 기준이다. 기존 소스의 이름과 구분하며, 이름을 정했다는 사실이 구현 완료를 뜻하지는 않는다.

## 모델과 상태

| 업무 의미 | 구현 이름 | 책임과 구분 |
| --- | --- | --- |
| 노선 | `Route` | 현재 노선 버전의 교체와 시간표 변경을 관리한다. |
| 노선 버전 | `RouteVersion` | 버전별 정류장 순서·방향·시간표를 표현한다. 시간표만 바뀌면 같은 버전을 수정하는 규칙을 유지한다. |
| 노선 내 정류장 위치 | `RouteStopPosition` | 노선 버전과 정류장 순번으로 구분한다. 왕복 구간의 같은 정류장 ID를 하나로 합치지 않는다. |
| 수집 계획 | `CollectionPlanKey` | 같은 예정 수집과 그 재시도를 연결하는 값이다. 실제 HTTP 호출 횟수와 다르다. |
| 수집 배치 | `CollectionBatch` | 한 수집 계획의 현재 시도와 결과를 관리한다. 예약·미전송·실패·정상 빈 응답도 포함하므로 관측 행이 반드시 존재하는 것은 아니다. 기존 설계의 `ObservationBatch`에 해당한다. |
| 현재 수집 시도 | `AttemptToken` | 배치 ID와 시도 번호로 늦게 도착한 이전 응답을 구분한다. 시도 번호는 새 시도에서만 증가하며 매 DB 갱신마다 바뀌는 버전과 다르다. |
| 입력 확정 | `inputConfirmedAt`, `confirmInput` | 예보 입력으로 확정한 수집 배치의 원 관측을 재수집으로 교체하지 못하게 한다. 별도 `SourceSeal` 객체 대신 배치의 상태와 행동으로 표현한다. 이후 품질 판정으로 사용 여부를 바꾸는 일과는 구분한다. |
| 예보 발행 | `ForecastPublication` | 한 수집 배치의 예보가 언제 어떤 모델로 완성됐는지 기록한다. 예측이 0행이어도 발행은 존재한다. 작업 묶음으로 오해하기 쉬운 `ForecastBatch`는 사용하지 않는다. |
| 차량·정류장별 예측 | `SeatForecast` | 발행에 속하는 확률·기대 잔여석·대상 정류장 등의 불변 결과다. |
| 예보 평가 | `ForecastEvaluation` | 발행된 예측의 평가 대기와 결과 확정을 관리한다. 이전 설계의 `ForecastOutcome`에 해당한다. |
| 평가 결과 | `EvaluationResult` | 좌석 확인·좌석 정보 없음·대상 건너뜀·추적 중단 등의 결과를 구분한다. 기존 `ArrivalLabel`의 업무 의미를 이어받되 단순 도착 시각과 혼동하지 않는다. |
| 통계 버전 | `DemandStatisticsVersion` | 한 번에 계산하고 공개하는 통계 묶음이다. 기존 설계의 `DemandStatisticsGeneration`에 해당한다. |
| 당일 보정 | `DailyCalibration` | 노선·서울 날짜·예보 거리별 평가 누계를 관리한다. 평가 확정과 누계 반영은 함께 커밋한다. |
| 노선 자료 품질 | `RouteDataQuality` | 노선별 계산 자료의 사용 조건과 품질 버전을 관리한다. 예측 정확도 점수를 뜻하지 않는다. |
| 편도 품질 조사 | `TripQualityInvestigation` | 노선 버전·차량별 조사 진행과 재개 위치를 관리한다. |
| 편도 품질 판정 | `OneWayTripAssessment` | 조사한 편도의 사용 가능·경계 미확정·제외 상태와 근거를 기록한다. 정상 관측에 판정 행을 일괄 생성하지 않는다. |
| 모델 식별 정보 | `ModelIdentity` | 릴리스 ID·번들 digest·특징 계산 계약 등 실제 모델을 구분하는 전체 값이다. digest 하나로 대체하지 않는다. |

## 유스케이스와 이름 사용

`PublishForecast`, `EvaluateForecasts`, `BuildDemandStatisticsVersion`, `InvestigateTripQuality`, `PromoteModel`처럼 수행할 업무를 동사로 표현한다. 상태를 임의로 대입하는 setter보다 `startAttempt`, `recordDispatch`, `completeAttempt`, `confirmInput`처럼 변경의 의미가 드러나는 행동을 사용한다.

값을 그대로 읽는 메서드는 record 접근자처럼 `id`, `status`, `attemptNumber` 등 값의 이름을 사용한다. 가능 여부나 상태를 판단하는 메서드는 `canRetry`, `isCompleted`처럼 구분한다. JPA 매핑과 HTTP DTO의 접근자는 기존 매핑·직렬화 계약을 먼저 확인한다.

지역변수는 `batch`, `attempt`, `publication`, `forecasts`, `evaluation`, `statistics`, `quality`, `investigation`처럼 그 문맥의 대상을 나타낸다. 모든 클래스에 접두사나 인터페이스를 일괄 추가하지 않는다.

다음 버전 값은 의미가 다르므로 합치지 않는다.

- `attemptNumber`: 같은 수집 계획의 현재 시도 번호
- `statisticsRevision`: 통계 산출 버전 번호
- `calculationVersion`: 통계 계산 규칙의 버전
- `qualityRevision`: 계산 자료의 사용 조건이 바뀌었음을 나타내는 버전
- 활성 모델 슬롯 버전: 모델 교체 요청이 전제한 ACTIVE 상태를 검사하는 값

## 모듈과 실행 프로그램

목표 Gradle 모듈은 `route-catalog`, `observations`, `forecasting`, `api-call-quota`, `gbis-client`, `api-app`, `worker-app`, `maintenance-app`, `common`의 9개다. 경계 컨텍스트는 노선 카탈로그·관측 수집·탑승 예보의 3개이며, 모듈 개수와 같을 필요는 없다.

`api-call-quota`는 이전 설계의 `upstream-budget`을 대체한다. `maintenance-app`은 상시 품질 정비 CLI다. `forecast-math`는 만들지 않고 온라인에서 사용하는 계산을 forecasting 내부에 둔다. 기존 migration-tool의 일회성 이관 전용 38개 파일은 삭제하고, 품질 업무 구현 1개는 forecasting으로 옮긴다. 나머지 CLI 지원 10개는 명령·설정·DB 연결·정비 권한 확인 역할에 맞춰 maintenance-app에서 재구성한다.

## 기존 식별자와 외부 계약

위 이름은 내부 구현의 기준이다. 기존 HTTP JSON 필드·상태값·ID의 의미를 이름 변경에 맞춰 자동으로 바꾸지 않는다. 프론트엔드 수정은 이번 범위에서 제외한다.

기존 DB 데이터와 Flyway 적용 이력을 보존하면서 필요한 테이블·컬럼을 목표 모델로 전환한다. Java 클래스 이름을 바꿨다는 이유만으로 기존 테이블명과 상태 문자열을 일괄 변경하지 않는다. 기준 커밋을 가리키는 근거 링크와 소스 파일 이름도 조사 기록으로 유지한다.

실제 예보 입력·특징 벡터·보정값의 별도 스냅샷 저장은 이번 구현에서 제외한다. 모델·통계·품질 버전과 발행 식별 정보는 기록하되, 이것만으로 당시 계산을 정확히 재현할 수 있다고 설명하지 않는다. 원 관측과 예측값, 평가에 필요한 근거를 보존하는 일은 별도의 책임으로 유지한다.
