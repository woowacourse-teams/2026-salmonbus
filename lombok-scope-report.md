# SAL-104 Lombok 적용 분석

## 적용 결과

사용자 승인에 따라 분석에서 안전한 후보로 판정한 `@RequiredArgsConstructor`만 제한 적용했다.

- 적용 파일: 4개
  - `LiveVehicleQueryService`
  - `VehicleFreshnessPolicy`
  - `LiveVehicleController`
  - `JpaVehicleQueryRepository`
- 논리 코드 순절감: 18줄
- 실제 diff 물리 줄 수 절감: 22줄
  - 생성자와 함께 중복 공백 4줄도 제거됨
- `@Slf4j`: 0건 — 실제 로깅 없음
- record: 변경 0건
- getter·setter·JPA ID equality·JPA no-arg 생성자: 적용 0건 — 분석상 실익 또는 의미 안전성 부족
- 테스트 파일 변경·삭제: 0건
- 검증: `./gradlew test --rerun-tasks`, 전체 116개 통과

## 분석 당시 판단

> SAL-104 단독 도입 권고는 “실익 없음”이었으나, 프로젝트 차원의 Lombok 채택 결정이 내려질 경우 `@RequiredArgsConstructor` 4곳만 제한 적용하는 안을 가장 안전한 선택으로 제시했다.

JPA ID의 equality와 선택적 접근자는 명시 코드를 유지하는 편을 권고했고 실제 적용에서도 그대로 유지했다.

## 분석 범위와 방법

- 기준 브랜치: `be/feat/SAL-104`
- 기준 HEAD: `0472a3d`
- 대상: `backend/src/main/java/com/gustler/backend/api/vehicle` 및 대응 테스트
- 파일 수: 33개
  - main Java: 25개
  - test Java: 8개
- 총 줄 수: 1,678줄
- 제외:
  - 스택 베이스 SAL-103 소유 파일
  - `api/vehicle` 밖의 Java
  - Java 외 파일
- 줄 수 산정:
  - 제거 가능한 수기 코드에서 필요한 Lombok import와 annotation 줄을 뺀 순절감
  - 동작·공개 메서드명·노출 범위가 달라지는 경우는 조건부 수치로 분리

`build.gradle`에는 `compileOnly`, `annotationProcessor`, test용 Lombok 의존성이 이미 있다. 적용 전 dev의 main·test Java에는 실제 Lombok import 또는 Lombok annotation 사용이 0건이었고, 적용 후 SAL-104 소유 main Java 4개 파일에서만 사용한다.

## 33개 파일 전수 분류

분류는 파일의 주된 Lombok 검토 사유를 기준으로 한 번씩만 집계했다.

| 주 분류 | 파일 수 | 대상 |
|---|---:|---|
| record 또는 record 포함 타입 — Lombok 불필요 | 8 | `LiveVehicleOverview`, `VehicleSnapshot`, `ObservedVehicle`, `VehicleSeat`, `LiveVehicleResponse`, `ObservationResponse`, `SeatResponse`, `VehicleResponse` |
| 생성자 주입 — `@RequiredArgsConstructor` 적용 | 4 | `LiveVehicleQueryService`, `VehicleFreshnessPolicy`, `LiveVehicleController`, `JpaVehicleQueryRepository` |
| 수기 접근자 또는 equality/hashCode | 3 | `ObservationBatchJpaEntity`, `RouteStopJpaEntity`, `RouteStopJpaId` |
| JPA protected no-arg 생성자만 후보 | 1 | `VehicleObservationJpaEntity` |
| Lombok 후보 없음 | 17 | 아래 목록 |
| 로깅 필드 — `@Slf4j` 후보 | 0 | logger 선언·로그 호출 모두 없음 |

### record 분류 상세

- top-level record 6개
  - `LiveVehicleOverview`
  - `VehicleSnapshot`
  - `ObservedVehicle`
  - `LiveVehicleResponse`
  - `ObservationResponse`
  - `VehicleResponse`
- nested record 3개
  - `VehicleSeat.Exact`
  - `SeatResponse.Exact`
  - `SeatResponse.Unknown`
- 합계: 8개 파일, record 선언 9개

Java record가 생성자·접근자·`equals`·`hashCode`·`toString`을 이미 제공한다. `@Data`, `@Value`, `@Getter`, `@EqualsAndHashCode`를 추가할 이유가 없다.

### Lombok 후보 없음 17개

main 9개:

- `VehicleCachePolicy`
- `VehicleQueryRepository`
- `VehicleDirection`
- `VehicleObservationState`
- `VehiclePhase`
- `VehiclePollOutcome`
- `ObservationBatchEntityRepository`
- `VehicleObservationEntityRepository`
- `VehicleRouteVersionEntityRepository`

test 8개:

- `LiveVehicleQueryServiceTest`
- `VehicleCachePolicyTest`
- `VehicleFreshnessPolicyTest`
- `LiveVehicleApiContractTest`
- `VehiclePhaseTest`
- `VehiclePollOutcomeTest`
- `VehicleSeatTest`
- `JpaVehicleQueryRepositoryTest`

테스트 fixture의 `private final` 필드와 `static final` 상수는 Lombok 대상이 아니다. 테스트에는 생성자 주입·logger·수기 접근자/equality가 없다.

## 후보별 절감 줄 수

### 1. `@RequiredArgsConstructor`

| 클래스 | 현재 생성자 | Lombok import+annotation | 순절감 |
|---|---:|---:|---:|
| `LiveVehicleQueryService` | 11줄 | 2줄 | 9줄 |
| `VehicleFreshnessPolicy` | 3줄 | 2줄 | 1줄 |
| `LiveVehicleController` | 3줄 | 2줄 | 1줄 |
| `JpaVehicleQueryRepository` | 9줄 | 2줄 | 7줄 |
| **합계** | **26줄** | **8줄** | **18줄** |

네 클래스 모두 의존성 필드가 `private final`이고 Spring 단일 생성자 주입을 사용하므로 의미 변화 없이 적용할 수 있다. 이 범위에서 가장 안전한 Lombok 후보이다.

위 표는 생성자 선언 코드만 센 논리 순절감이다. 실제 적용 diff에서는 생성자 블록 사이의 중복 공백 4줄도 함께 사라져 전체 파일 물리 줄 수는 22줄 감소했다.

### 2. `@Slf4j`

- logger 필드: 0개
- 로그 호출: 0개
- 후보 파일: 0개
- 절감: 0줄

번호판·원문 payload를 로그에 남기지 않는 경계가 있으므로, 현재 로그 요구 없이 `@Slf4j`를 미리 추가하면 사용하지 않는 API만 생긴다.

### 3. 수기 getter·setter

단순 접근자 5개, setter 0개이다.

| 클래스 | 단순 접근자 | 표준 field-level `@Getter` 순절감 | 주의점 |
|---|---:|---:|---|
| `ObservationBatchJpaEntity` | 3개, 9줄 | 5줄 | `id()` 등이 `getId()`로 바뀌어 호출부 수정 필요 |
| `RouteStopJpaEntity` | 2개, 6줄 | 3줄 | `name()`·`direction()` 공개 메서드명이 바뀜 |
| **합계** | **5개, 15줄** | **8줄** | 공개 접근 API 변경 |

현재 메서드는 노출할 필드만 선택하고 record형 이름을 사용한다. class-level `@Getter`는 `routeVersion`, `scheduledAt`, JPA ID 등 불필요한 필드까지 공개한다. `@Accessors(fluent = true)`와 field-level `@Getter`로 이름을 유지하면 순절감은 약 4줄까지 줄고 experimental API가 추가된다. 따라서 실질 후보로 권고하지 않는다.

`effectivePollAt()`과 `toDomain()`은 계산·변환 메서드이므로 getter 후보가 아니다.

### 4. `@EqualsAndHashCode`

| 클래스 | 현재 수기 코드 | Lombok 적용 순절감 | 판단 |
|---|---:|---:|---|
| `RouteStopJpaId` | `equals`·`hashCode` 및 `Objects` import | 약 15줄 | 줄 수 절감은 있으나 명시 구현 유지 권고 |

`RouteStopJpaId`는 JPA 복합 식별자다. equality에 포함되는 필드가 DB 식별자와 정확히 일치하는지가 스키마 의미이므로, 절감 효과보다 명시성이 중요하다. Lombok의 상속·`canEqual` 생성 정책까지 검토해야 해 단순 기계 치환 대상으로 보지 않는다.

### 5. JPA protected no-arg 생성자

대상 4개:

- `ObservationBatchJpaEntity`
- `RouteStopJpaEntity`
- `RouteStopJpaId`
- `VehicleObservationJpaEntity`

각 수기 생성자는 2줄이다. `@NoArgsConstructor(access = AccessLevel.PROTECTED)`는 annotation과 import가 필요해 최선의 경우 순절감 0줄이며, 일반적인 import 형식이면 오히려 총 4줄이 늘어난다. JPA 요구사항을 코드에 직접 드러내는 현재 구현이 낫다.

## 도입 조합별 총효과

| 조합 | 순절감 | 의미·컨벤션 위험 |
|---|---:|---|
| `@RequiredArgsConstructor` 4곳만 | 18줄 | 낮음 |
| 위 + field-level `@Getter` | 최대 26줄 | 메서드명 변경·호출부 수정 |
| 위 + `@EqualsAndHashCode` | 최대 33줄 | JPA 식별자 equality가 annotation에 숨음 |
| no-arg 생성자까지 전부 annotation화 | 추가 절감 0줄 이하 | JPA 요구사항 가독성 저하 |

## 최종 권고 및 적용 한 줄

**프로젝트 차원의 채택 결정에 따라 SAL-104에는 `@RequiredArgsConstructor` 4곳만 제한 적용 — 4개 파일에서 실제 22줄을 줄였고, record·로깅·접근자·JPA 식별자에는 억지 적용하지 않았다.**
