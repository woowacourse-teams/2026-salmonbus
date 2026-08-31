# SAL-47 Lombok 적용 범위 분석 및 적용 결과

## 1. 범위와 방법

- 분석 기준 HEAD: `42e8692` (`be/feat/SAL-47`)
- 포함: SAL-47이 새로 추가한 `api.board` 메인 35개 + 테스트 8개 = **43개 Java 파일**, 총 **2,368 LOC**
- 제외:
  - 스택 베이스 SAL-103의 `api.route` 파일
  - 베이스에 이미 있던 `ModelOutOfScopeException`, `NoRecentObservationException`
  - Java 외 파일
- 확인 방법:
  - `git diff --diff-filter=A 3740282..HEAD`로 SAL-47 신규 파일 확정
  - record/class/interface/enum, 생성자, 로거, getter/setter/equals/hashCode를 선언 단위로 전수 검색
  - 절감 줄 수는 **현재 삭제 가능한 물리 줄 수 - Lombok import/annotation 추가 줄 수**로 계산
- 빌드 상태: `backend/build.gradle`에 main/test Lombok compileOnly 및 annotationProcessor가 이미 있어 의존성 추가 비용은 0이다.
- 적용 전 사용 상태: `lombok.*` import 및 Lombok annotation은 dev 전체 Java 소스에서 **0건**이었다.

## 2. 43개 파일 전수 분류

| 분류 | 파일 수 | Lombok 판단 |
|---|---:|---|
| 최상위 record | 18 | Java가 생성자·accessor·equals·hashCode·toString을 이미 생성하므로 불필요 |
| enum | 1 | 불필요 |
| Repository/port interface | 5 | 상태·생성자 없음, 불필요 |
| 생성자 주입 production class | 4 | `@RequiredArgsConstructor` 후보 |
| 무상태 component | 1 | 생성자 없음, 불필요 |
| JPA entity/embeddable ID | 6 | broad `@Data`/`@Getter`는 부적합; ID 2개만 `@EqualsAndHashCode` 후보 |
| 테스트/fixture class | 8 | 7개는 후보 없음; fixture 1개만 `@RequiredArgsConstructor` 약한 후보 |
| **합계** | **43** | |

### 2.1 record — 18개 (+ fixture 내부 record 1개)

- application 5개: `BoardOverview`, `BoardSnapshot`, `DepartureSchedule`, `SnapshotObservation`, `StoredPrediction`
- domain 7개: `ApproachingVehicle`, `Board`, `BoardRoute`, `BoardStop`, `DirectionInfo`, `ForecastModel`, `StopState`
- dto 6개: `ApproachingVehicleResponse`, `BoardResponse`, `BoardRouteResponse`, `DirectionInfoResponse`, `ModelInfoResponse`, `StopStateResponse`
- test fixture 내부 record 1개: `BoardDatabaseFixture.RouteContext`

record에는 Lombok `@Value`, `@Getter`, `@EqualsAndHashCode`, `@ToString`을 추가해도 중복이다. 현재 compact constructor의 null 검사와 필드 단위 Jackson annotation도 record가 직접 표현한다. **예상 절감 0줄**이다.

### 2.2 생성자 주입 — production 4개

| 클래스 | final 필드 수 | 수기 생성자 본문 줄(공백 제외) | import+annotation | 예상 순절감 |
|---|---:|---:|---:|---:|
| `BoardFreshnessPolicy` | 1 | 3 | 2 | **1** |
| `BoardQueryService` | 3 | 9 | 2 | **7** |
| `BoardController` | 1 | 3 | 2 | **1** |
| `JpaBoardQueryRepository` | 5 | 13 | 2 | **11** |
| **합계** | **10** | **28** | **8** | **20줄** |

각 클래스에 `lombok.RequiredArgsConstructor` import 1줄과 `@RequiredArgsConstructor` 1줄을 추가하고 수기 생성자를 제거하는 기준이다. Spring의 단일 생성자 주입 동작은 유지된다.

테스트 helper인 `BoardDatabaseFixture`도 final 필드 1개와 수기 생성자 3줄이라 후보이지만, import+annotation 2줄을 더하면 **순절감 1줄**뿐이다. 테스트 가독성과 명시성을 감안하면 적용 실익이 거의 없다.

### 2.3 로깅 — 0개

- logger 필드: 0개
- 로그 호출: 0개
- `@Slf4j` 후보: 0개

현재 합의가 “별도 애플리케이션 로깅 없음”이므로 `@Slf4j`는 보일러플레이트를 줄이지 않고 사용하지 않는 필드를 추가한다. **예상 절감 0줄**이다.

### 2.4 수기 getter/setter — getter 5개, setter 0개

- `RouteStopJpaId`: fluent getter 2개 (`routeVersionId()`, `stopOrder()`)
- `SeatForecastJpaId`: fluent getter 1개 (`targetStopOrder()`)
- `VehicleObservationJpaEntity`: fluent getter 2개 (`sourceRowNumber()`, `vehicleId()`)
- setter: 0개
- 수기 getter 본문: 총 15줄

단순 `@Getter`는 `getStopOrder()` 형태를 생성해 현재 fluent API와 다르다. 정확한 API를 유지하려면 `@Accessors(fluent = true)`와 필드별 `@Getter`가 필요하며, annotation/import 줄이 수기 getter 줄과 거의 같아 **안전한 순절감 0줄**이다. 클래스 수준 `@Getter`는 JPA ID·association 등 현재 노출하지 않는 필드까지 공개하므로 적용하지 않는다.

### 2.5 수기 equals/hashCode — 2개 ID 클래스

| 클래스 | 제거 대상 | 현재 줄 | annotation 순증가 | 순절감 |
|---|---|---:|---:|---:|
| `RouteStopJpaId` | equals + hashCode | 16 | 1 | **15** |
| `SeatForecastJpaId` | equals + hashCode | 16 | 1 | **15** |
| **합계** | 4개 메서드 | **32** | **2** | **30줄** |

`java.util.Objects` import를 `lombok.EqualsAndHashCode` import로 교체하므로 import 줄 수는 상쇄되고, 클래스별 `@EqualsAndHashCode` 1줄만 순증가한다. 두 클래스 모두 JPA 복합키의 두 필드만 가지므로 생성 대상 필드가 현재 수기 구현과 일치한다.

### 2.6 JPA no-arg constructor와 broad annotation

- JPA entity/ID 6개에 protected no-arg constructor가 있다.
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)`는 클래스별 constructor 3줄을 없애지만 보통 import 2줄 + annotation 1줄이 필요해 **순절감 0줄**이다.
- `@Data`는 JPA association을 equals/hashCode/toString에 끌어들여 lazy loading, 순환 참조, Entity identity 문제를 만들 수 있으므로 후보에서 제외한다.
- Entity 클래스 수준 `@Getter`/`@Setter`도 필요 이상의 persistence 상태를 공개하므로 제외한다.

## 3. 사전 예상 절감량

| 적용안 | 대상 파일 | 순절감 |
|---|---:|---:|
| production `@RequiredArgsConstructor` | 4 | **20줄** |
| embeddable ID `@EqualsAndHashCode` | 2 | **30줄** |
| test fixture `@RequiredArgsConstructor` (선택하지 않음) | 1 | 1줄 |
| record / `@Slf4j` / getter·setter / no-arg / broad annotation | 나머지 | 0줄 |
| **권고 적용안 합계** | **6개 production 파일** | **50줄** |

- 전체 2,368 LOC 대비 순절감: 약 **2.1%**
- constructor·equals/hashCode 수기 구현에서만 국소적으로 의미가 있고, 43개 파일 전반에 Lombok을 확산할 근거는 없다.

## 4. 권고

> **선택적 도입 실익 있음:** SAL-47에서는 production 4개 클래스의 `@RequiredArgsConstructor`와 복합키 2개의 `@EqualsAndHashCode`만 적용하면 순 50줄을 줄일 수 있으나, record 18개·로깅 0개·fluent getter 5개에는 실익이 없으므로 패키지 전면 Lombok(`@Data`, 광범위 `@Getter/@Setter`, `@Slf4j`) 도입은 권고하지 않는다.

추가 판단 사항: dev 전체에서 Lombok 사용이 0건이었던 적용 전 상태에서는 50줄 절감보다 “첫 사용 패턴을 여는 팀 컨벤션 비용”도 존재한다. 적용한다면 위 2개 annotation으로 허용 범위를 명문화하고, JPA Entity의 `@Data` 금지를 함께 정하는 것이 안전하다.

## 5. 실제 적용 및 검증 결과

### 5.1 적용 범위

- `@RequiredArgsConstructor` 4개:
  - `BoardFreshnessPolicy`
  - `BoardQueryService`
  - `BoardController`
  - `JpaBoardQueryRepository`
- `@EqualsAndHashCode` 2개:
  - `RouteStopJpaId`
  - `SeatForecastJpaId`
- 실제 적용: **production 6개 파일**
- 미적용: record, `@Slf4j`, 수기 fluent getter, JPA no-arg constructor, 테스트 fixture

record는 그대로 유지했고, 로그 필드가 없으므로 `@Slf4j`를 추가하지 않았다. 테스트 소스도 수정하거나 삭제하지 않았다.

### 5.2 실측 절감량

적용 코드 6개 파일의 `git diff --numstat` 합계는 **12줄 추가, 68줄 삭제, 순 56줄 감소**다. 사전 예상 50줄은 보일러플레이트 본문만 세고 구분용 공백 줄을 제외한 값이며, 실제 diff에서는 생성자·메서드 제거 뒤 함께 없어진 공백 6줄까지 반영되어 56줄로 측정됐다. 이 보고서 자체의 변경량은 코드 절감량에서 제외했다.

### 5.3 검증

- 실행: `./gradlew clean test`
- 결과: **BUILD SUCCESSFUL**
- 테스트: **104개 통과**
- 테스트 소스 변경·삭제: **0개**

### 5.4 PR #33 리뷰 반영 후 상태

위 5.1~5.3은 최초 Lombok 적용 커밋 `aa31954` 시점의 실측값이다. PR #33 리뷰 반영 과정에서 시간대 정의를 `ClockConfig`로 모으기 위해 `BoardCachePolicy`에도 `Clock` 생성자 주입이 생겼고, 이 클래스에도 `@RequiredArgsConstructor`를 적용했다.

- 현재 `@RequiredArgsConstructor`: **5개 production 파일**
- 현재 `@EqualsAndHashCode`: **2개 production 파일**
- 현재 Lombok 선택 적용: **production 7개 파일**
- record, `@Slf4j`, fluent getter, JPA no-arg constructor, 테스트 fixture 미적용 원칙은 유지
- 리뷰 반영 후 전체 검증: `./gradlew clean test`, **115개 통과**
