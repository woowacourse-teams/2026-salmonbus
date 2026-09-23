# 구현 범위와 검증 기록

2026-09-23(Asia/Seoul) 기준으로 승인된 SAL-134 구현과 검증을 완료했다. 비교 기준은 PR #70이 반영된 `9f9c75db16fcdefc0944f0c6a40fa7c09e4e1d94`다. 변경은 `be/refactor/SAL-134` 브랜치에 작업 단위별로 로컬 커밋했다. 운영 DB 적용·원격 push·PR 게시·게시 문서 사이트 갱신은 수행하지 않았다.

## 구현 결과

- 완료된 S3→RDS 이관 전용 Java 38개와 전용 스크립트·설정·테스트·AWS SDK 의존을 제거했다. 품질 조사 업무는 forecasting으로 옮기고 CLI 실행 지원은 maintenance-app에서 재구성했다.
- 노선 관리·관측 수집·탑승 예보를 3개 경계 컨텍스트로 나눴다. 호출 횟수 예약과 GBIS 통신을 별도 모듈로 두고 실행 앱과 업무 모듈을 분리했다. 실제 Gradle 프로젝트는 9개다.
- 수집 상태 전이와 시도 번호, 계산 입력 확정, 배치당 단일 발행, 평가 확정, 통계 버전 생성, 품질 조사 재개, 모델 활성화를 도메인 행동과 트랜잭션으로 연결했다. 도메인 객체와 JPA 매핑은 분리했다.
- API는 별도 읽기 모델을 사용하며 실행 클래스패스에 업무 쓰기 모듈을 포함하지 않는다. PR #70의 HTTP 응답·오류·품질 필터·신선도·캐시 계약을 유지했다.
- maintenance-app은 `quality-preview`, `quality-rebuild`, `quality-status`만 제공한다. 웹 서버·스케줄·GBIS 인증키 검사·모델 적재·Flyway 자동 실행 없이 필요한 정비 구성만 기동한다.
- 서비스 Flyway V1~V16은 기준 커밋과 바이트 단위로 동일하다. V17에서 저장 구조를 확장하고 V18은 검증 완료 또는 이관 대상이 없는 새 DB일 때만 최종 전환한다.
- 전환 실행기는 별도 sourceSet과 ZIP으로 만들었다. 상시 앱 실행 클래스패스에는 포함하지 않는다. 기존 관측·예측 식별자와 원값, 출처·감사·학습 제외 기록을 보존한다.
- 프론트엔드는 변경하지 않았다. 로컬 설계 문서와 정비·전환 운영 문서는 실제 구현에 맞췄다.

주요 클래스와 실제 연결은 [설계안](00-design.md), 기준 소스별 처리와 현재 파일 배치는 [클래스 배치](03-class-map.md), 동작 변화와 이유는 [백엔드 변경 이력](../../../backend/history/2026-09-23-SAL-134.md)에 정리했다.

## 최종 검증

Java 21과 PostgreSQL 18 Testcontainers 환경에서 다음 명령이 통과했다.

```sh
cd backend
./gradlew build --no-daemon --console=plain --max-workers=1
```

JUnit XML을 실제 9개 프로젝트의 `test`와 common의 `sal134MigrationTest`에서 집계했다. 삭제한 migration-tool의 이전 build 산출물은 집계하지 않았다. 총 **1,364개 테스트**, 실패·오류·스킵은 모두 **0개**다. `clean build`로 68개 작업을 모두 실행했고 결과는 `BUILD SUCCESSFUL`이다. 아래 분포는 업무 테스트를 소유 모듈로 옮긴 뒤의 값이다.

| 검증 대상 | 테스트 수 |
| --- | ---: |
| api-app | 197 |
| worker-app | 51 |
| maintenance-app | 32 |
| common | 49 |
| SAL-134 전환 실행기 | 13 |
| forecasting | 694 |
| observations | 203 |
| route-catalog | 58 |
| api-call-quota | 37 |
| gbis-client | 30 |
| 합계 | 1,364 |

구현 착수 전의 기준 빌드 1,158개와 위 결과는 다른 시점의 결과다. 이관 전용 테스트를 제거하고 업무 규칙·동시성·실행 검증을 추가했으므로 두 수치의 차이를 신규 테스트 수로 해석하지 않는다. 업무 테스트를 소유 모듈로 옮기기 전 같은 명령의 결과는 1,355개였다.

### 업무 규칙과 경쟁 상황

같은 수집 계획의 경쟁, 늦은 응답, 입력 확정과 재수집의 경쟁, 호출 예약·수집 기록의 트랜잭션을 검사했다. 같은 배치의 동시 발행은 하나만 저장하며 중간 실패는 발행·예측·평가 대기행·입력 확정을 함께 롤백한다. 좌석 범위 오류의 차량별 제외와 0행 발행도 검증했다.

평가의 `PENDING` 조건, 보정 한 번 반영, 초기 집계와 평가의 경쟁, 통계 버전 할당 직렬화, 품질 제외 자료의 집계·학습 제외를 확인했다. 실제 PostgreSQL 잠금 대기를 확인하는 경쟁 테스트를 포함한다. 70/71석·10분 경계·반복 출발·32배치 탐색·조사 재개와 정상 관측의 추가 품질 SQL 0건 계약도 유지했다.

모델 활성화는 전체 식별 정보, 같은 digest의 다른 모델, 기대 활성 버전, 같은 요청 재전송, 같은 요청 ID의 내용 충돌, 저장 실패 롤백을 검사했다. 파일 적재가 트랜잭션 밖에서 수행되는지도 확인했다. 평가 근거의 정류장 순번은 원 관측의 `stop_order`이며, 도착 중 관측의 `passed_stop_order`와 다른 사례로 회귀를 고정했다.

### 구조와 실제 실행 JAR

ArchUnit으로 도메인의 기술 의존, 업무 모듈 간 비공개 구현 참조, 실행 앱을 향하는 역방향 의존, 순환 의존을 검사했다. 각 모듈의 실제 클래스가 검사 대상에 들어오는지도 검증한다.

API·Worker의 실제 Boot JAR를 실행해 API 우선 기동, V18 설치, HTTP·health·readyz, 인증키 누락, 스케줄 비활성화 시 DB 쓰기 없음과 런타임 프로젝트 의존을 확인했다. 정비 앱의 실제 JAR 테스트 5개는 위 maintenance 32개에 포함된다. 폐기 명령 거절, 읽기 전용 조회, 정비 재개, 원 관측 보존, 빈 DB의 자동 DDL 방지, 불필요한 실행 의존 미포함을 검사한다.

최종 정비 문서·SQL 설명 수정 뒤 `:maintenance-app:assemble`도 통과했으며 JAR 안의 문서가 소스와 일치하는지 확인했다. 이 마지막 수정은 Java와 SQL 본문을 바꾸지 않았다.

### DB 전환과 배포 리허설

common의 스키마 전환 테스트 6개와 전환 실행기 테스트 13개는 위 전체 결과에 포함된다. 기존 V16 자료·빈 DB·중단 후 재개·복사 실패 롤백·원본 값 변조·대상 값 차이·검증 후 변경·과거 발행 정보 불일치·학습 제외 보존·없는 품질 근거 참조 거절을 검증했다.

별도로 배포 ZIP을 풀고 Java 21과 동봉 라이브러리만으로 `run.sh`를 실행했다. 임시 PostgreSQL 18.6에서 `inspect → prepare → 일부 backfill → 재개 → verify → finalize → finalize 재실행`을 수행했다. 발행 2건, 평가 1건, 원 관측 2건, `LEGACY_UNKNOWN` 발행 1건과 기존 학습 조회를 확인했다. 모든 체크포인트가 완료됐고 기존 평가 컬럼은 최종 전환 뒤 제거됐다. 임시 컨테이너는 삭제했다.

```sh
bash backend/deploy/rehearsal/run.sh
```

Docker 배포 hook 리허설은 **154개 통과, 실패 0개**다. 새 업무 모듈·GBIS 변경이 Worker digest를 바꾸는지, maintenance만 바뀌면 서버 digest가 유지되는지, 동일 digest의 기존 JAR 재사용과 새 JAR 설치, 기동 실패·검증 실패·복구·비밀값 노출 방지를 확인했다. CI에서도 전체 빌드와 배포 hook 리허설을 각각 실행하며, 전환 실행기의 JUnit 결과도 수집한다.

## 로컬 커밋

업무 모델 정의는 기존 실행을 바꾸지 않고 순서대로 추가했다. 저장 구조가 바뀌는 시점에는 수집·예보·평가·통계·품질·모델 저장소와 API 조회 소비자를 함께 전환했다. DB 전환 실행기와 배포 검증은 별도 커밋이다.

| 커밋 | 내용 |
| --- | --- |
| `fe1f32c` | 업무 용어와 재설계 기준 |
| `7d322ec` | 완료된 S3 이관 기능 제거 |
| `4268f7f` | 9개 모듈과 실행 앱 구성 |
| `298fe5c` | GBIS 연동과 호출 한도 분리 |
| `ab4cd11` | 노선 모델과 버전 변경 책임 |
| `63d3aa6` | 수집 배치와 시도 상태 모델 |
| `1a06a88` | 품질 조사 상태와 판정 규칙 |
| `f1349c6` | 발행과 불변 예측 모델 |
| `fe78b8a` | 평가와 통계 모델 |
| `4bd6c01` | 모델 식별과 활성화 요청 계약 |
| `4404b6f` | 업무 모델의 저장·트랜잭션·실행·API 조회 연결, V17/V18 |
| `87bbc1d` | DB 전환 실행기와 전수 검증 |
| `81e2ed9` | 배포 식별과 CI·배포 리허설 연결 |
| `25ed8ee` | 첫 통합 검증과 구현 문서 정리 |
| `8f2029c` | 품질 조사 시작·재시작 규칙을 도메인으로 이동 |
| `ae40068` | 평가 시간 규칙과 저장 조율 책임 분리 |
| `10bdf8e` | GBIS 응답 변환·수집 품질 연동 분리와 구조 검사 보강 |
| `f749b9f` | DDD 책임 분리 보완과 검증 기록 |
| `934e0fb` | 업무 테스트를 소유 모듈로 이관 |
| `9177658` | 예보 도메인 개념 순환 제거 |
| `bf27985` | 평가 확정 판단을 도메인으로 이동 |
| `1c2a92e` | 호출 한도를 API별로 모델링 |
| `bdbd652` | 노선 식별자 이름을 sourceRouteId로 통일 |
| `9cec703` | 수집 단계와 통계 규칙 버전을 도메인에 배치 |

모듈 이동만 반영한 중간 상태는 1,110개 테스트, GBIS·호출 한도 분리는 관련 298개, 노선 변경은 관련 248개 테스트로 확인했다. 수집·품질·발행·평가·통계의 순수 모델도 각 단계에서 검사했다. 첫 통합 검증은 1,311개였고 DDD 책임 분리를 보완한 뒤 `f749b9f`에서 1,355개였다. 업무 테스트 이관 뒤 1,356개였고, 도메인 개념 순환 검사와 새로 만든 계약을 고정하는 테스트를 더한 최종 결과가 위 1,364개다. 문서 커밋에는 이 기록과 설계·운영 문서의 구현 대조 결과를 담는다.

## DDD 재검토 후 보완

처음 구현한 업무 모델에서 도메인으로 옮기지 못한 시작 규칙, 외부 응답 타입 의존, 저장소에 남아 있던 실행 흐름을 다시 검토하고 다음과 같이 수정했다.

- `TripQualityInvestigation.start/restart`가 조사 시작·진행 중 유지·완료 후 재시작을 결정한다. `QualityObservationBatch.firstAnomalies`는 차량별 첫 이상 관측을 고른다. JDBC는 도메인이 정한 상태를 저장한다. 기존 `completed` 값을 복원하므로 단계 이름으로 과거 완료 여부를 추정하지 않는다.
- 수집 응용 코드는 `ObservationSource`와 불변 `ObservationResponse`만 사용한다. `GbisObservationSource`가 HTTP 종료 직후 수신 시각을 기록하고 응답을 분류·정규화한다. 예상하지 못한 호출 오류와 정규화 오류를 구분하는 기존 동작을 유지했다.
- `ObservationLoader`가 품질 잠금 → 수집 배치 저장 → 새 저장 결과의 품질 조사 등록을 조율한다. 저장소는 `StoredObservations`를 반환하며, 동일 결과를 다시 받았을 때 조사 등록을 반복하지 않는다.
- `ForecastEvaluationWriter`가 정렬한 노선 품질 잠금 → 완료 가능 여부 → 도착 배치 잠금·입력 확정 → 평가 CAS → 새 결과의 보정을 처리한다. 평가 생성 시각 이전과 같은 시각의 관측은 `ArrivalLabelResolver`가 판정 전에 제외한다.
- 응용·도메인의 GBIS 타입 의존과 JPA·JDBC 저장 어댑터의 공개 업무 API 호출을 ArchUnit으로 금지했다. 수집·평가의 기존 잠금 순서와 트랜잭션을 유지했으며 DB 스키마와 HTTP 응답 계약은 바꾸지 않았다.

별도 읽기 검토에서 위 책임 이동이 실제 호출 경로에 반영됐음을 확인했고 검토한 경로의 기능·잠금 회귀는 발견하지 못했다. 코드 검토와 실행 테스트 결과는 구분한다.

도메인·어댑터 91개 테스트를 먼저 통과한 뒤 전체 빌드를 실행했다. 첫 전체 실행에서는 Worker 929개(이관 전 분포) 중 스케줄 테스트 2개가 테스트용 PostgreSQL 연결 초기화의 EOF 오류로 실패했다. 코드 변경 없이 해당 테스트와 구조 검사 13개를 다시 실행해 통과했고, 이어서 전체 빌드도 통과했다. 그때의 1,355개는 이 전체 실행의 결과이며, 위 표는 그 뒤 테스트를 이관하고 다시 집계한 값이다. 실패 원인을 특정 운영 환경 문제로 단정하거나 테스트의 기대값을 낮춰 통과시키지 않았다.

DB 전환 ZIP과 배포 hook의 구현은 이번 보완에서 변경하지 않았다. 앞서 기록한 ZIP 실행과 154개 배포 리허설은 그때의 검증 결과이며, 이번 전체 빌드에서는 변경한 업무 모듈을 포함한 API·Worker·정비 앱의 실제 JAR 실행도 다시 확인했다.

## 업무 테스트의 소유 모듈 이관

worker-app에 남아 있던 업무 테스트를 각 업무 모듈로 옮겼다. worker-app에는 기동·스케줄·설정과 모듈 연결을 확인하는 테스트만 남는다.

- 테스트 85개와 리소스 1개를 route-catalog·observations·forecasting·api-call-quota·gbis-client로 옮겼다. 옮기기 전후의 테스트 메서드 이름을 전수 대조해 소실이 없음을 확인했다. 이름이 바뀐 여섯 개는 클래스 이름과 import만 달라졌다.

| 옛 이름 | 새 이름 |
| --- | --- |
| `ForecastJobTest` | `PublishPendingForecastsIntegrationTest` |
| `ForecastJobStalenessTest` | `PublishPendingForecastsStalenessTest` |
| `TripQualityInvestigationJobTest` | `InvestigateTripQualityServiceTest` |
| `StopDemandStatisticsJobTest` | `RefreshDemandStatisticsServiceTest` |
| `CollectedObservationsTest` | `GbisCollectedObservationsMapperTest` |
| `ObservationBatchConclusionTest` | `GbisObservationMapperTest` |
- `VehicleObservationTest`의 27개는 GBIS 매퍼 24개, 잔여석 2개, 차량 관측 1개로 나눠 배치했다.
- 각 업무 모듈은 Worker를 기동하지 않고 자기 구성만으로 통합 테스트를 실행한다. observations의 테스트 구성은 각 모듈이 공개한 Spring 구성을 그대로 가져와 운영과 같은 빈 그래프를 쓴다.
- 품질 훅이 있어야 성립하는 모듈 간 시나리오 2개는 observations 단독으로 검증할 수 없다. worker-app의 `ObservationQualityIntegrationTest`가 실제 배선에서 같은 내용을 확인한다. observations에 남기면 검사 대상이 비어 있어 통과하므로 남기지 않았다.
- 모듈별 테스트 수는 worker-app 929→50, forecasting 63→692, observations 28→203, route-catalog 5→58, api-call-quota 12→32, gbis-client 27→30으로 바뀌었다. 합계는 1,355에서 1,356이 됐다. 새로 더한 `ObservationQualityIntegrationTest` 3개가 늘고 위 모듈 간 시나리오 2개가 줄었다. 뒤이은 감사 반영으로 최종 수치는 달라진다.
- 이관 직후 api-call-quota의 테스트 구성이 자동구성을 손으로 나열하면서 Testcontainers 접속 정보를 등록하는 `ServiceConnectionAutoConfiguration`과 `TestcontainersPropertySourceAutoConfiguration`을 빠뜨려 `CallQuotaLedgerTest` 20개가 컨텍스트 적재에 실패했다. 두 자동구성을 넣어 해소했고, 아래 감사 반영에서 형제 모듈과 같은 `@EnableAutoConfiguration` 방식으로 다시 맞췄다.
- 이관이 들여온 불필요한 `project(':common')` 직접 의존 3건을 지우고, 컴파일에서 참조하지 않는 `spring-boot-flyway`를 `testRuntimeOnly`로 내렸다. `RuntimeProcessSeparationTest`의 모듈 목록에서 존재하지 않는 이름 3개를 뺐다. 옮기고 남은 빈 디렉터리도 정리했다.

전체 빌드는 1,364개 통과, 실패·오류·스킵 0이다. 한 차례 forecasting의 `ModelActivationBoundaryTest` 3개가 테스트용 PostgreSQL 접속 시간초과로 실패했고, 코드를 바꾸지 않고 다시 실행해 692개 전부 통과했다. 실패 원인을 코드 결함이 아니라고 단정하지 않고 재실행 결과를 함께 남긴다.

## 전수 감사 지적 반영

이관을 마친 뒤 패키지 배치·이관 충실성·빌드 구성·문서 일치·DDD 잔여 결함을 다시 점검했다. **이번 범위는 구조 리팩토링이므로 동작과 정책은 바꾸지 않는다.** 다음은 동작이 같은 정리다.

- 예보 도메인의 `model`과 `publication`이 서로를 참조하던 패키지 순환을 끊었다. 예측에 넣는 입력 값 12개를 `model`로 모으고, 발행 묶음으로 예보 시간대를 정하는 `ForecastTimeSlot`은 `publication`으로 옮겼다. `TargetStopDemand`가 받던 인자도 정류장 순번과 남은 정류장 수로 바꿔 `statistics`가 `model`에 기대지 않는다. 이제 `statistics ← model ← {publication, evaluation, quality}` 한 방향이며 `PackageBoundaryTest`의 `domainConceptsHaveNoCycles`가 검사한다.
- 노선 식별자 이름을 `sourceRouteId` 하나로 통일했다. 이전에는 포트 계약이 `sourceRouteId`, 구현과 저장소가 `upstreamRouteId`로 갈려 있었다. `V1__collector.sql` 19행이 `public_route_id`를 "Open API 원문 routeId"로 적고 있어 수집 설정부터 모델 노선 선택까지 흐르는 값이 모두 상류 ID다. 용어집에 두 컬럼이 지금 같은 값이라는 사실과 조회가 `public_route_id`를 쓴다는 것을 적었다.
- 업무 모듈 네 개의 통합 테스트 부팅 방식을 모듈 전용 애너테이션으로 통일했다. 각 테스트 구성은 빈 목록을 손으로 적는 대신 모듈이 공개한 Spring 구성을 가져온다. 운영에서 새 빈이 늘어나도 모듈 테스트가 같은 그래프를 쓴다.
- 호출 한도는 활용신청한 API마다 따로 적용되는데 `CallQuotaPolicy`가 한 개의 값만 들고 있었다. API별 한도로 바꾸되 현재처럼 값이 같은 설정은 `sameForEveryApi`로 만들어 계산 결과가 같다.
- 평가를 확정할 수 있는지는 `ArrivalLabel.settles()`가 판단한다. 응용 코드의 `instanceof` 분기를 대신하며 `NotArrivedYet`만 `PENDING`이라 결과가 같다. 도착 후보 적격 판정 SQL이 두 곳에 복사돼 있던 것을 한 상수로 모았고 문장은 그대로다. 쓰이지 않던 `ScoringState.scorable()`을 저장소가 사용한다. `ck_evaluation_state`가 enum 값만 허용하므로 판정 결과는 같다.
- 수집 단계 판정이 주입된 `Clock`의 시간대를 따르던 것을 도메인의 한국 시간대 상수로 바꿨다. 주석이 설명하던 규칙과 코드가 어긋나 있었고, 유일한 호출자인 Worker가 Asia/Seoul `Clock`을 쓰므로 결과는 같다.
- 통계 계산 규칙의 버전 상수를 응용 서비스에서 `DemandStatisticsVersion`으로 옮겼다. 값은 그대로다.
- 삭제된 `collector`·`processor` 패키지를 가리키던 주석 7줄의 모듈 이름을 현재 이름으로 바꿨다. 문장과 의도는 그대로 뒀다.
- `worker-app`의 테스트 의존에서 쓰지 않는 항목을 걷어냈다. Testcontainers·ArchUnit은 common의 testFixtures가 공개한다. 업무 모듈 네 곳의 `spring-boot-flyway` 스코프도 `testRuntimeOnly`로 맞췄다.
- 이번에 새로 생긴 `CallQuotaPolicy`의 API별 한도와 `ArrivalLabel.settles()`를 고정하는 테스트를 더했다.

감사가 지적한 것 중 **동작이나 정책이 바뀌는 것은 이번에 반영하지 않았다.** 근거를 남기고 후속 범위로 둔다.

- 편도 방향 판정의 조회 view가 첫 정류장을 `stop_order = 1`로 고정한다. 도메인은 판본의 최소 순번을 쓴다. 상류가 주는 순번이 1부터 시작하지 않는 판본에서 두 값이 갈린다. 고치려면 view를 바꾸는 마이그레이션이 필요하다.
- 정원 분모가 서빙과 집계에서 다르다. 서빙은 최대 잔여석이 0석인 차량을 정원을 모르는 것으로 보고 제외하는데, 집계는 `GREATEST(..., 1)`로 1석을 채운다. 맞추면 통계 값이 달라지므로 계산 규칙 버전을 함께 올려야 한다.
- 이상 관측 판정이 온라인 경로는 도메인, 정비 경로는 SQL에 있다. 정비 경로만 `EXCLUDED` 편도를 건너뛰어 같은 구간을 다시 돌려도 목록이 달라진다. 맞추면 `quality-rebuild`가 고르는 대상이 바뀐다.
- 노선정보 조회가 지속 실패하면 수집 주기마다 호출 두 번씩을 태운다. 재시도 간격을 두는 것은 새 운영 정책이다.
- 상류 노선정보의 정류소 행에 정류소 ID나 순번이 없으면 `int` 변환에서 예외가 난다. 실패로 바꾸면 오류 처리 경로가 달라진다.

감사에서 확인한 것 중 다음은 제안 자체를 받지 않았다.

- `GbisTestConfiguration`은 참조가 없어 보이지만 `@JsonTest`가 `@SpringBootConfiguration`으로 찾아 쓴다. 지웠다가 되돌렸다.
- `ForecastTimeSlot`은 예보 시간대를 관측 시각으로 고정하는 장치다. 호출부에서 직접 계산하면 그 규칙이 사라진다.
- `ObservationBatchLedger.abandonBeforeSend`는 두 테스트가 전송 전 포기 경로를 직접 검사한다.
- `RouteDataQualityAccess.lock`을 `currentRevisionOf`로 바꾸자는 제안은 받지 않았다. 이름에서 잠금이라는 사실이 사라진다. 같은 패키지에 있는 `TripQualityStore`·`TripQualityMaintenanceStore`도 같은 자리에 있어 이 포트만 옮기면 오히려 어긋난다.
- `ApiCallQuota`의 세 메서드를 하나로 줄이자는 제안도 받지 않았다. 자정 재예약 규칙이 호출자인 수집 쪽으로 새어 나간다.

## 운영 적용 전에 확인할 것

[전환 실행 절차](../../../backend/deploy/migrations/SAL-134/README.md)에 따라 모든 쓰기를 중지한 이후의 일관된 백업 또는 PITR 복원 지점을 확보해야 한다. 실행기의 `--writers-stopped`와 `--backup-id`는 운영자의 확인을 기록할 뿐, 다른 서버의 작업을 중지하거나 백업의 복원 가능성을 대신 검사하지 않는다.

전환과 전수 대조를 마친 뒤 새 API와 새 Worker를 쓰기 비활성 상태로 기동해 조회·모델·배포 식별 정보를 확인한다. 새 쓰기 재개 전 실패는 백업 DB와 구 JAR를 함께 복원한다. 새 쓰기를 재개한 뒤에는 구 JAR만 되돌리지 않는다. 운영 규모의 처리 시간, 실제 백업 복원, 운영 계정 권한은 이번 로컬 검증에 포함하지 않았다.

프론트의 기존 응답 소비 불일치와 외부 학습 저장소의 직접 SQL 사용은 별도 확인 범위다. 기존 모델 manifest의 정규화·시간·정원·통계 정책 선언을 실제 계산 코드와 모두 대조해 거절하는 기능도 이번에 추가하지 않았다. 모델의 digest·golden 결과 검증과 현재 계산식은 유지했다. 예보 입력 전체나 특징 벡터를 별도 보관하지 않으므로 과거 예보의 완전한 재현을 보장한다고 설명하지 않는다.
