# v4-1 계약 준수 매트릭스

## 판정 기준

사용자 결정에 따라 우선순위는 다음과 같다.

1. 조사 시점 현재 `dev` API 코드·테스트 (`b2396915…`)
2. 현재 dev의 Java 계산/번들 소비 구현
3. 실제 운영 Python 계산과 실제 S3 데이터/관행
4. 사이트 문서는 보조 설명

`MATCH`는 v4-1 권위 또는 그 권위가 요구하는 dev 소비 계약과 일치한다는 뜻이다.
`DIVERGES`는 변환 없이 함께 쓸 수 없음을 뜻한다. `UNKNOWN`은 정본이 답을 정하지 않았거나
실데이터로 확인할 수 없다는 뜻이다. `INTERNAL_CONFLICT`는 dev 내부 설명·구현끼리 충돌한다.

## 매트릭스

| v4-1 요구사항/필드 | 현재 dev 소비 계약 | 현재 계산 구현 | 실제 데이터에서 관측된 값/형태 | 판정 | 영향 | v4-1에 맞추기 위한 후속 조치 |
|---|---|---|---|---|---|---|
| 정본/as-of: 현재 dev API 코드·테스트 | dev `b2396915…`; `/api/v1/routes/{routeId}/board` (`BoardController.java:15-30`) | 운영 Python은 analysis `c5ff99d…` | serving snapshot은 별도 EC2 runtime에서 생성 | MATCH | 권위 범위가 분리됨 | 모든 새 번들 검증을 dev HEAD에 다시 걸 것 |
| 보드 접근 차량 키: `vehicleId`, `horizonStops`, `seatAvailableProbability`, optional `expectedSeats` (`ApproachingVehicleResponse.java:6-22`) | 동일 4필드, `expectedSeats`는 `NON_NULL` | shadow `ApproachingVehicleView`는 네 필드 외 `plateNo`, `plateState`도 가짐 (`runtime/models.py:662-668`) | actual board materialization은 core 4필드를 채우고 extra plate 필드도 가짐 | DIVERGES | dev DTO에 demo 응답을 그대로 역직렬화하면 extra-field 정책에 따라 실패 가능 | v4-1 경계에서 demo extra 필드를 제거하는 adapter를 별도로 두기; coefficient 파일과 혼동 금지 |
| `horizonStops` 1~12 (`V3__forecast.sql:65`, API service 저장값) | manifest도 `[1..12]`, 2번째 tensor 축 12 | Python도 horizons 1~12 | actual manifest `horizons=[1..12]`, 24 route×horizon fit | MATCH | 지평 축 호환 | 없음 |
| 확률 방향: `seatAvailableProbability = 1 - seatFullChance` (`BoardQueryService.java:104-108`) | predictor 내부는 p_full, DB는 `seat_full_chance` | shadow adapter도 `1 - p_full` (`runtime/predictor.py:46-58`) | 실제 evaluation/materialized row에 available probability 저장 | MATCH | 사용자 방향 의미 일치 | 없음 |
| `seatAvailableProbability`는 유한 0~1; p_full 이상 행은 차량 예보 제외 (`BoardQueryService.java:149-156`) | PMF/p_full을 유한하게 계산 | sigmoid clip, Pydantic 0~1 | 8-context runtime 검증에서 전부 유한·범위 내 | MATCH | API 안정성 확보 | 없음 |
| `expectedSeats`는 0 이상 선택 필드; null/비유한/음수면 키만 생략 (`BoardQueryService.java:169-185`, DTO `:10-11`) | 71칸 PMF 평균 | Python `Σ seat*PMF`, actual runtime은 non-null 수를 생산 | 실제 runtime smoke 8건 모두 0~70 | MATCH | 값 의미 일치 | 없음 |
| 승차 불가 정류장은 `approachingVehicles=[]` (`BoardQueryService.java:187-201`) | 예보 대상 생성에서도 boarding stop만 요구 | producer가 non-boarding target을 제외 | protocol과 route reference가 경유 정류장 제외 | MATCH | 경유 오염 방지 | 없음 |
| 정류장당 최대 3대, 지평·vehicle 순 정렬 (`BoardQueryService.java:38-48,128-146`) | bundle과 무관 | shadow도 horizon·vehicleHmac 정렬 후 3대 (`runtime/engine.py:2221-2245`) | 실제 runtime schema max length 3 | MATCH | 화면 결과 순서 일치 | 없음 |
| model 응답: `releaseId`, `trainedThrough` (`ModelInfoResponse.java:6-15`) | manifest `releaseId`, `dataThrough`이 deployment `data_until`이 됨 | Python manifest `release_id`, `data_through` | actual release `a18-a748…`, through `2026-08-23T14:59:56Z` | MATCH | 이름은 boundary별 변환 필요 | loader adapter가 이름을 명시적으로 매핑해야 함 |
| bundle 파일 이름 `manifest.json`, `weights.safetensors` (`BundleFiles.java:18-40`) | 정확히 둘 | 정확히 둘 | actual artifact에서 두 파일 확인 | MATCH | 물리 파일 구성 일치 | 없음 |
| manifest top-level camelCase 20필드, unknown 거절 (`BundleManifestReader.java:43-79`) | `bundleSchemaVersion`, `modelVersion`, `featureContractVersion`, `identityDigest`, golden vector 등 필수 | Python은 snake_case 15필드와 embedded `protocol`, `serving_contract`, `training`, `weights` | actual manifest는 `schema_version`, `model_version`, `release_id`, `horizons` 등 | DIVERGES | dev loader가 JSON 파싱 단계에서 즉시 거절 | dev exporter를 새로 만들 것. 기존 manifest의 단순 rename은 아래 의미 충돌 때문에 금지 |
| canonical UTF-8 JSON, symlink 금지, regular file (`BundleFiles.java:26-59`, reader `:93-119`) | 강제 | Python bundle도 canonical JSON과 regular path 강제 | actual manifest canonical, 파일 SHA 통과 | MATCH | 파일 무결성 일치 | 없음 |
| schema/model version | `a18-live-bundle-v1`, `seat-distribution-a18-v1` (`BundleLoader.java:32-33`) | 동일 문자열 | actual 동일 | MATCH | 같은 schema 이름인데 내부 구조가 다른 위험한 충돌 | dev-compatible 구조에는 새 schema version을 부여하거나 명시적 adapter schema를 정의할 것 |
| route 축 순서 | `[1650,3330]` (`BundleLoader.java:36`) | 동일 | actual 동일 | MATCH | 1축 의미 일치 | 없음 |
| 31 feature 이름·순서 (`SeatForecastDesignMatrix.java:123-154`) | `constant`, `is_morning`, … | Python은 `intercept`, `time_morning`, … | actual manifest에 Python 이름 31개 | DIVERGES | 이름 검사에서 거절되며 계수 좌표도 다름 | dev feature contract로 재학습; 이름만 치환 금지 |
| dev 31열 입력 완결성 | 예보 시점에 31열이 결정돼야 계수 의미가 고정됨 | Java 주석은 9열이 “계수를 기다린다”고 하지만 실제 구현은 0을 둠 (`SeatForecastDesignMatrix.java:16-26,164-169`) | 실제 Python bundle은 그 9열을 별도 runtime material로 채움 | INTERNAL_CONFLICT | 계수 파일 자체가 입력 9열을 대신 만들 수 없어 dev contract가 완결되지 않음 | feature builder와 bundle material 소유권을 먼저 확정 |
| 시간대 기준 | dev API observedAt은 batch `response_received_at`; Java feature도 그 시각 (`ForecastTimeSlot.java:27-32`) | Python은 prediction + frozen median lead | 실제 bundle `lead_seconds` 90.104~1740.189초 | DIVERGES | 경계 시각에서 morning/evening 열과 cell이 달라짐 | 팀이 기준 시각을 확정하고 학습·서빙을 같은 규칙으로 재학습 |
| 열 4 새 시간대 지시자 | Java는 학습 정보가 없어 0 (`SeatForecastDesignMatrix.java:19-24,164-166`) | Python `band_seen`으로 계산 | actual `band_seen` 72/72가 1 | DIVERGES | 현재 데이터 창에서는 값이 우연히 모두 0이지만 계약 재현 재료가 없음 | dev bundle contract에 band metadata를 넣거나 열 제거 후 재학습 |
| 혼잡도 인코딩 | 정규화된 1~4 one-hot, unknown은 모두 0 (`SeatForecastDesignMatrix.java:66-74,228-238`) | 원코드 0~3 one-hot, 4 reference | actual record에는 원코드 0~4, dev DB V4는 1~4만 저장 | DIVERGES | 네 열이 한 칸씩 다른 좌표계 | 원코드 보존 여부를 정하고 새 feature version으로 재학습 |
| full streak 단위 | stop count 원값 (`SeatForecastDesignMatrix.java:85-86,209`) | `min(count,5)/5` | source trajectory로 계산 가능 | DIVERGES | 열 15 scale 5배 차이 | dev 식 또는 Python 식 중 하나를 정하고 재학습 |
| 앞차 좌석 정규화 | `previous / maximumSeats` (`SeatForecastDesignMatrix.java:255-269`) | `previous / 60` | source에 필요한 좌석은 있으나 의미가 다름 | DIVERGES | 열 17 scale이 차량별로 달라짐 | 상수/분모를 feature contract에 명시하고 재학습 |
| route 열 | 노선별 fit이라 Java는 늘 0 (`SeatForecastDesignMatrix.java:25,168-169`) | 3330이면 1 | actual은 route별 별도 fit이면서 route 지시자도 있음 | DIVERGES | 3330 계수의 절편 좌표가 다름 | dev 계약에 맞춰 route 열 0으로 재학습하거나 열을 제거하고 schema bump |
| 위치 열 20 | `stopOrder / largestStopOrder` (`StopPositionOnRoute.java:20-25`) | `station / 60` | actual route max 88/84 | DIVERGES | 두 노선 모두 scale 차이 | 정규화 분모를 명시하고 재학습 |
| spline 8열 | Java는 미결이라 모두 0 (`SeatForecastDesignMatrix.java:20-24`) | 8개 tent basis | actual 계수는 non-zero spline 입력에 적합 | DIVERGES | 기존 계수를 dev에 넣으면 위치 효과가 사라짐 | spline knots/분모를 dev contract에 추가하고 Java 구현 후 재학습 |
| 셀 통계 축·유효기간 | dev DB generation: `(routeVersion,timeSlot,calculationVersion,revision)`, as-of observedAt (`JdbcStopDemandStatisticsRepository.java:50-70`) | Python frozen `(route,horizon,timeBand,station,stat)` | actual `cell_values` 2×12×3×89×2, `cell_seen` | DIVERGES | dev는 지평 축이 없고 새 데이터로 값이 변함; 계수 학습 자와 다름 | dev 방식으로 전체 재학습하거나 frozen cell tensors와 serving을 함께 이식 |
| 셀 날짜 가중 | dev 일별 평균의 단순 평균 (`StopDemandAggregator.java:10-24,58-80`) | Python도 날짜 균등, leave-one-day-out 학습 | actual 10일 창 | MATCH | 축·source 차이는 별도 | 동일 데이터·지평 축으로 재학습 필요 |
| 정원 | dev route-version 전체 관측의 as-of 최대; 예보에서는 trajectory 최대 | Python prior completed days + same-day as-of + current | actual causal full observation stream 사용 | DIVERGES | 판본·journey·날짜 경계가 다를 수 있음 | 정확한 vehicle/journey/route-version 범위를 계약에 고정하고 재학습 |
| tensor 이름과 수 | dev 5개만 허용, unknown tensor 거절 (`BundleTensor.java:17-23`, `BundleLoader.java:192-202`) | Python 9개 | actual extra: `lead_seconds`, `band_seen`, `cell_values`, `cell_seen` | DIVERGES | dev loader가 weights를 거절; 4개를 빼면 feature를 못 재현 | 새 dev schema/exporter/loader를 함께 설계 |
| 공통 coefficient tensor shape | dev 5개 shape는 2×12 축과 일치 | Python 동일 | actual 공통 5개 shape 일치 | MATCH | 의미는 feature 차이로 불일치 | shape 일치만으로 호환 판정하지 말 것 |
| manifest dtype 표기 | dev 선언은 `F64`/`U8` (`TensorDataType`) | Python manifest는 `float64`/`uint8` | actual snake-case manifest에 후자 | DIVERGES | 선언 파싱 단계 실패 | exporter가 dev enum 표기를 내도록 하되 재학습 뒤 수행 |
| 계수 유한성·flag 0/1 | dev 강제 (`BundleLoader.java:239-251`) | Python 강제 | actual 전 tensor 통과; max absolute coefficient=15.1792, flags binary | MATCH | 숫자 안전성 확보 | 없음 |
| 미적합 bin | dev 개별 미적합=1e-6; 방향 전체 미적합=중심으로 반환 (`ResidualDistribution.java`) | Python 동일 | actual fitted 386, unfitted 46 | MATCH | 잔차 fallback 동일 | parity는 dev feature 계약 번들로 다시 확인 |
| PMF 합/범위 | dev 71칸, 합=1, 0칸=p_full (`SeatProbabilityTable.java`) | Python 동일 | 실제 8 context에서 합 1±1e-12, finite, 0~1, p0=p_full | MATCH | 분포 말단 호환 | 더 넓은 dev golden vector를 새 번들에 포함 |
| golden vector | dev manifest 필수, SHA와 1e-9 parity 검증 (`LoadedBundle.java:43-65`) | Python actual manifest에 golden vector 없음 | actual production loader 자체 테스트는 통과하지만 dev golden 없음 | DIVERGES | dev 승격 불가 | dev feature builder로 만든 독립 golden vector를 새 exporter에 추가 |
| identity/version | dev `featureContractVersion`, source commit, route ref, weights, feature names, normalization, policies, golden digest를 `identityDigest`로 묶음 | Python release ID는 dataThrough+protocol+routeRef+training provenance; bundle digest는 manifest bytes와 weights bytes를 순서대로 이은 값 | actual 두 digest 모두 검증됨 | DIVERGES | 같은 release를 서로 다른 신원 규칙으로 부름 | 단일 release receipt mapping을 명문화하고 dev deployment schema에 기록 |
| 유효기간 | API에는 `trainedThrough`; bundle 만료 규칙 없음 | dev ACTIVE deployment가 교체될 때까지 | actual data through 8/23, 9/2에도 ACTIVE | UNKNOWN | 최신 수집과 모델 사이 9일 이상 lag | 재학습 cadence/최대 staleness를 별도 운영 계약으로 결정 |
| dev dummy | v4-1 API 요구사항 아님 | runtime에 쓰지 않음; seeded random loader fixture (`DummyBundle.java:16-23,55-77`) | 실제 runtime 신원은 real release 하나 | NOT_APPLICABLE | “dev가 dummy로 예보 중”이라는 전제가 틀림 | dummy를 기준 성능/번들로 부르지 말고 loader test fixture로만 표기 |
| 민감 원천 처리 | API는 원본 vehicle/plate 노출을 요구하지 않음 | dev source DB는 별도 | private record/raw에 original ID, plate, HMAC 확인; final adapter는 HMAC만 메모리 사용 | MATCH | row raw 전달 불가 | 승인된 in-process adapter를 유지하고 aggregate receipt만 공유 |

## INTERNAL_CONFLICT

현재 dev 내부에도 완결되지 않은 계약이 있다.

- `BundleLoader`는 `featureNames` 31개와 계수를 요구하지만 `SeatForecastDesignMatrix`는 9개 열을
  “계수를 기다린다”며 0으로 둔다. 계수 파일이 그 열 값을 제공할 수는 없다. 계수는 입력 생성 규칙이
  아니므로, 새 bundle만 놓는 것으로 열 4와 21~28을 채울 수 없다.
- `BundleCheck` 주석은 정규화 상수·route 열·spline을 미결이라고 인정하면서 loader schema/version은
  이미 `a18-live-bundle-v1`로 고정한다.
- runtime 기본 설정은 bundle directory 비어 있음·forecast disabled이고, 실제 dummy는 test source에만
  있다. 따라서 “dev가 dummy로 실제 예보를 낸다”는 상태 설명과 코드가 충돌한다.

이 항목들은 실제 번들 생성 전에 해결해야 하며, 이번 조사에서는 계약이나 구현을 변경하지 않았다.

## 사이트 문서 드리프트

- `salmonbus-api-docs/site/v4-1/index.html:69-74`는 dev DTO/test를 정본으로 명시하므로 권위 선언은
  이번 사용자 결정과 맞는다. 다만 기준 SHA가 `2cc1b32`로 현재 dev `b2396915`보다 오래됐다.
- `site/models/seat_distribution.html:991-992`는 “서빙에 실을 고정 계수 묶음이 아직 없다”고 적는다.
  2026-09-02 실제 EC2 snapshot은 `a18-a748…`을 사용하므로 운영 사실과 DIVERGES다.
- site v4-1 OpenAPI는 demo 전용 `plateNo`, `plateState`, `dataMode`, `snapshotId`를 계약에 넣지 않는다.
  실제 shadow 응답에는 이 필드가 있으므로 demo 응답 전체와 dev API는 같은 스키마가 아니다.
- `model-api-site`·`model-api-site-2` worktree HEAD에는 tracked v4-1 계약 문서가 없다. 전자의 untracked
  site plan은 provenance가 없어 보조 계획으로만 취급했다.

## 최종 준수 판정

실제 운영 번들은 자기 Python production contract에는 유효하지만 현재 dev v4-1 소비 계약에는
`NON_COMPLIANT_WITH_V4_1`이다. dev에 투입 가능한 최종 번들이 아니다. 필요한 조치는 기존 파일 변환이
아니라 dev feature contract 확정 → 실제 S3 원천으로 재학습 → dev manifest/golden exporter 생성 →
Java loader·API 통합 검증 순서다.
