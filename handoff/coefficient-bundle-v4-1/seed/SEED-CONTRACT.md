# v4-1 aggregate seed load, RDS replay delta and cutover contract

## 상태와 정본

이 문서는 2026-09-03 사용자 결정을 반영한 `ATOMIC_CUTOVER.md`의 구현 sidecar다. 기존 handoff
manifest와 ZIP의 SHA를 보존하기 위해 hash 대상인 `ATOMIC_CUTOVER.md`는 수정하지 않았다.

결정된 단일 순서는 **활성화 마지막**이다. writer를 quiesce한 뒤 `FINAL_CUTOVER_AT`을 먼저 고정하고,
exact temporary cleanup, RDS observation replay, merged seed, 공식 첫 generation을 차례로 끝내 검증한
다음에만 formal bundle을 ACTIVE로 올린다. formal activation의 실제 `activated_at`은 delta upper bound가
아니다.

이 계약은 `COMBINED-CUTOVER-RUNBOOK.md`의 현재 `activate → freeze → seed → cleanup` 순서를 대체한다.
release gate `N<30` override는 이 seed/cutover 계약의 어떤 검증도 완화하지 않는다.

## 전달 payload identity

| 항목 | 값 |
|---|---|
| 파일 | `seed/cell-hourly-aggregate.json.gz` |
| schema | `stop-demand-hourly-aggregate-seed-v1` |
| gzip bytes | 723,046 |
| canonical JSON bytes | 9,450,157 |
| rows | 45,224 |
| gzip SHA-256 | `7f3be7dc2c668d1ed4b8665c341c2cda24968d3fa2e6ed2755261a20c3826ec4` |
| canonical JSON SHA-256 | `adcf0d04a4b67dc40bed6f3640f0f78697e9215bf794ff2b262f0ba0ec0a5ada` |
| source receipt file SHA-256 | `9985723799bc859cc22b4cda613a50922ff2191b15c7c155d8f1d7e177db8c26` |
| canonical rows SHA-256 | `15c221a149f241555e32a4133ed1f31b3d9eaeedc3062c5a454d6b9505489013` |
| primary-key SHA-256 | `30b2ffc79cc64decc7936efd0a03e51602e5e12424e0e8cbdb7ebf7d98a6a73d` |
| gzip mtime | 0 |
| final LF | 없음 |

Machine-readable payload schema는 `stop-demand-hourly-aggregate-seed.schema.json`, final cutover receipt
schema는 `seed-cutover-receipt.schema.json`이다.

## JSON payload schema

최상위 object는 다음 14개 field만 가진다.

| field | JSON type | 계약 |
|---|---|---|
| `backfillPolicyId` | string | `s3-lag60-settle60-kst6h-chronological-v1` |
| `classification` | string | `PRIVACY_SAFE_AGGREGATE_BACKFILL_SEED_DRY_RUN_ONLY` |
| `featureContractVersion` | string | `observed-max-capacity-v1` |
| `generatedAtGuardSeconds` | integer | 60 |
| `lastBackfilledGenerationUtc` | string | UTC instant, `2026-09-02T09:00:00.000000Z` |
| `privacy` | object | 네 flag 모두 `false`, 추가 field 금지 |
| `rdsObservationDeltaContract` | string | `rds-observation-delta-contract.json` |
| `requiresRdsObservationDeltaBeforeFormalCutover` | boolean | `true` |
| `routeReference` | object | `version`, 64자리 lowercase `digest` |
| `rows` | array | 아래 row object |
| `schemaVersion` | string | `stop-demand-hourly-aggregate-seed-v1` |
| `scope` | string | `SOURCE_SIDE_THROUGH_TARGET_AUTHORITY` |
| `settlementAvailabilityGuardSeconds` | integer | 60 |
| `sourceAuthorityThroughExclusiveUtcByRoute` | object | route `1650`, `3330` UTC cutoff만 허용 |

`privacy`의 exact field는 `containsPlateValues`, `containsRawRows`, `containsVehicleHmacs`,
`containsVehicleIdentifiers`다. `routeReference`는 `version=gbis-2026-08-19`, digest
`50568d9a10b567ea0b650cd79ceed39a86947648e303e0d8fd1093840bb54c5e`다.

각 row는 다음 8개 field만 가진다.

| field | JSON type | 계약 |
|---|---|---|
| `modelRoute` | string | `1650` 또는 `3330` |
| `stopOrder` | integer | 1 이상, route roster 안의 boarding stop |
| `arrivalDateKst` | string | `YYYY-MM-DD`, UTC hour를 Asia/Seoul로 바꾼 날짜와 동일 |
| `arrivalHourStartUtc` | string | UTC 정각 `YYYY-MM-DDTHH:00:00Z` |
| `fillRateTotal` | number | finite, `0 <= value <= sampleCount` |
| `netBoardingTotal` | number | finite, `abs(value) <= capacityTotal` |
| `capacityTotal` | number | finite, `> 0` |
| `sampleCount` | integer | `> 0` |

자연키는 `(modelRoute, stopOrder, arrivalHourStartUtc)`이며 중복은 0이어야 한다. payload row 순서는
`(modelRoute, arrivalHourStartUtc, stopOrder)`의 UTF-8/시간/정수 오름차순이다. 현재 route axis는
`1650` 다음 `3330`이다.

decompressed payload는 모든 object key를 UTF-8 이름순으로 정렬하고 whitespace 없이
`separators=(",", ":")`로 쓴 canonical JSON이며 final LF가 없다. gzip은 compression level 9,
mtime 0이다. SHA 검증 전 재직렬화하지 않는다. 숫자는 JSON lexical value를 `BigDecimal`/PostgreSQL
`numeric`으로 읽어 raw aggregate와 receipt 합계를 보존하고, 공식 cell 계산 시에만 Java와 같은
double 연산으로 바꾼다.

## 전달 payload aggregate 기준점

합계는 JSON number의 decimal lexical value를 정확히 더한 값이다.

| 범위 | rows | samples | fillRateTotal | netBoardingTotal | capacityTotal |
|---|---:|---:|---:|---:|---:|
| 1650 | 20,390 | 509,435 | `134018.996217274785257864` | `-3567.0` | `22641833.0` |
| 3330 | 24,834 | 521,792 | `123564.766238173588115856` | `-119362.0` | `25141232.0` |
| 전체 | 45,224 | 1,031,227 | `257583.762455448373373720` | `-122929.0` | `47783065.0` |

source cutoff은 1650 `< 2026-09-02T12:49:33.041299Z`, 3330
`< 2026-09-02T10:27:52.390820Z`다. 이 count·합계·SHA 중 하나라도 다르면 dry-run과 apply를 모두
fail-closed한다.

## `FINAL_CUTOVER_AT`과 RDS replay delta

기호를 다음처럼 고정한다.

```text
C[1650] = 2026-09-02T12:49:33.041299Z
C[3330] = 2026-09-02T10:27:52.390820Z
T       = FINAL_CUTOVER_AT, writer drain 뒤 DB 시계로 고정한 UTC instant
H       = T를 고정한 transaction에서 table lock 아래 캡처한 observation_batch high-water id
S[k]    = 전달 source seed의 hourly aggregate at C[route(k)]
F_T[k]  = canonical RDS observation-only full replay의 hourly aggregate as of T
D_T[k]  = F_T[k] - S[k], 없는 key는 네 합계를 0으로 간주
M_T[k]  = S[k] + D_T[k]
```

`T`는 formal activation 전에 정한다. RDS raw input은 `response_received_at < T`, label availability는
`arrival_response_received_at + 60 seconds <= T`다. source/RDS authority 경계는 source `< C[route]`,
target `>= C[route]`다.

delta builder는 cutoff 이후 row만 읽어 단순 append하지 않는다. journey, h1 label, as-of capacity와 기존
hourly key의 정원 분모 재산정을 위해 import된 시작 시점부터 `T` 미만까지의 canonical RDS observation
history를 읽고 `F_T`를 다시 계산한다. output delta만 `F_T-S`로 제한한다. cutover 전 더 큰 정원이 관측되면
기존 key의 `fillRateTotal`과 `capacityTotal`이 달라질 수 있으므로 이 full-replay difference가 필수다.

같은 replay를 먼저 route별 C에서 실행한 `F_C`가 source seed S와 자연키·row count·네 합계·canonical rows
SHA까지 exact 일치해야 한다. 이 parity가 실패하면 RDS 이관 또는 replay 의미가 source builder와 다른 것이므로
T replay를 신뢰하지 않고 cutover를 중단한다. contribution 계산과 합산 순서는
`route, prediction batch response_received_at, batch id, source_row_number, target stop, arrival identity`로
고정하고 Java/Python과 같은 IEEE-754 binary64 연산을 쓴 뒤, hourly row 경계에서 canonical decimal로 바꿔
`numeric`에 보존한다.

다음 구현은 계약 위반으로 거절한다.

- `ingestion_origin='LIVE' AND response_received_at>=C[route]`만 읽어 source seed에 append하는 구현
- cutoff 전 prediction이 cutoff 뒤에 SETTLED/available이 된 cross-boundary contribution을 버리는 구현
- T 이전 새 maximum capacity가 기존 source key의 분모를 바꾼 효과를 restate하지 않는 구현
- source와 delta의 같은 key를 full-replay 비교 없이 단순 합치는 구현
- JSON number를 `double precision`에만 저장해 source decimal 합계와 canonical read-back을 재현하지 못하는 구현

payload reader의 row numeric type과 hourly seed staging/storage는 `BigDecimal`/`numeric`이어야 한다. 공식
`stop_demand_statistics`의 double cell은 검증된 merged raw totals에서 Java 계산 순서로 유도하는 별도 결과다.

허용 input은 route/route_version/route_stop, observation_batch, vehicle_observation뿐이다.
`model_deployment`, `seat_forecast`, `stop_demand_statistics`의 값은 replay input으로 읽지 않는다. vehicle ID는
process-local journey join에만 쓰고 output·receipt·log에 내지 않는다.

full replay query는 `S3_BACKFILL`과 `LIVE` observation을 모두 읽는다. `ingestion_origin`은 source `< C`와
target `>= C` authority 및 중복 0을 검증하는 데 쓰며, post-cutoff LIVE row만 고르는 replay filter로 쓰지 않는다.

결정적 query/replay 의사코드는 다음과 같다.

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

SELECT route_identity,
       batch_id,
       response_received_at,
       semantic_outcome,
       vehicle_observation_fields
FROM canonical_observation_history
WHERE model_route IN ('1650', '3330')
  AND batch_id <= :OBSERVATION_BATCH_HIGH_WATER
  AND response_received_at < :FINAL_CUTOVER_AT
ORDER BY model_route,
         response_received_at,
         batch_id,
         vehicle_observation_order;

COMMIT;
```

```text
assert writer_fence_held_and_shared_writers_drained(T)
assert imported_source_and_live_authority_has_no_gap_or_duplicate_at(C)
history = query_repeatable_read_snapshot_before(T, H)
S = parse_and_verify_exact_source_seed()
F_C = replay_each_route_at_source_cutoff(history, G=60, S=60)
assert canonical_rows(F_C) == canonical_rows(S)
F_T = replay_v4_1_observations(history, G=60, S=60, generation=T)

for k in sorted(union(keys(S), keys(F_T)), route/hour/stop):
    D_T[k] = subtract_four_totals(F_T.get(k, ZERO), S.get(k, ZERO))
    M_T[k] = add_four_totals(S.get(k, ZERO), D_T[k])

assert M_T == F_T
assert no negative sample count or impossible final aggregate
assert canonical_key_sha(M_T) == dry_run.expected_primary_key_sha
assert canonical_rows_sha(M_T) == dry_run.expected_rows_sha
```

DB apply는 signed delta를 production row schema로 억지 변환하지 않는다. source payload를 검증한 뒤
`F_T`와 동일한 merged final snapshot을 staging에 쓰고, `D_T`는 audit receipt의 key count·합계·hash로
남긴다. apply transaction은 staging read-back의 count·합계·hash가 dry-run과 일치할 때만 seed release를
승격한다.

primary-key SHA는 canonical row 순서에서 다음 UTF-8 line을 이어 붙인 SHA-256이다.

```text
modelRoute<TAB>stopOrder<TAB>arrivalHourStartUtc<LF>
```

canonical rows SHA는 row array만 key-sorted compact JSON으로 쓴 bytes의 SHA-256이다. aggregate 합계는
route별/전체 `rowCount`, `sampleCount`, `fillRateTotal`, `netBoardingTotal`, `capacityTotal`을 decimal string으로
receipt에 기록한다.

## 합의된 activation-last 단일 순서

1. history import/reconciliation/terminal seal, bundle·seed hash, `APPROVAL.md` SHA를 검증하고 private incoming에
   stage한다. temporary deployment id 1은 계속 ACTIVE다.
2. `temp-pause`로 forecast/settlement/statistics shared writer fence를 잡고 진행 transaction을 drain한다.
   collection과 원 observation insert는 계속될 수 있다.
3. fence가 선 DB transaction에서 DB 시계로 `FINAL_CUTOVER_AT=T`를 한 번 고정하고 durable control/receipt에
   기록한다. exact temporary forecast/generation 집합도 같은 T에 freeze한다. formal activation은 아직 없다.
4. exact cleanup target과 count·frozen-set SHA를 사용자에게 보여 주고 cleanup 승인을 받는다.
5. fence 아래에서 cleanup dry-run SHA와 frozen set이 불변임을 재확인한 뒤 deployment id 1의 exact forecast와 frozen
   temporary generation만 cleanup한다. observation, `forecast_completed_at`, deployment lineage는 변경하지
   않는다. bounded transaction을 쓰면 모든 chunk가 T·dry-run SHA·seed release·frozen-set SHA에 묶인 durable
   phase를 공유하고, no-op 재실행까지 끝나기 전에는 다음 단계로 갈 수 없다.
6. cleanup 완료 뒤 `REPEATABLE READ READ ONLY` snapshot에서 `F_T`, `D_T`, `M_T`를 계산한다. source seed
   identity와 final key/count·합계·SHA를 seed dry-run plan에 묶어 보여 주고 별도 seed apply 승인을 받는다.
   같은 plan을 재검증한 뒤 merged seed snapshot `M_T`를 쓰고 read-back 검증하며, `data_until=T`, calculation version
   `observed-max-capacity-v1`의 공식 첫 generation을 두 route에 materialize한다.
7. activation 전에 공식 generation route coverage, seed receipt digest, cell count/hash, frozen generation key와
   무교집합임을 독립 검증한다. 이 시점까지 temporary deployment id 1은 sole ACTIVE이고 derived writer는
   fenced 상태다.
8. formal bundle을 마지막으로 one-shot promotion한다. 새 deployment가 release
   `v41b-8194bde56d86f365afd6`, bundle digest `9bb1a5ac…3632`, calculation version
   `observed-max-capacity-v1`인지 확인하고 id 1은 RETIRED로 보존한다. `promote-on-start`는 즉시 false로 돌린다.
9. formal identity와 공식 generation을 다시 확인한 뒤 fence를 해제한다.
10. 두 route의 새 forecast가 formal deployment를 가리키고, 각 `demand_statistics_revision`이 공식 첫
    generation 또는 그 후속이며, 해당 generation의 calculation version/revision/data-until이 정확하고
    frozen temporary key가 아님을 확인한다. 그 뒤 board freshness를 확인한다.

1~7에서 실패하면 formal activation을 하지 않는다. seed/apply transaction은 rollback하고, 이미 commit된
bounded cleanup이 있으면 idempotent recovery 상태를 보존한다. temporary deployment id 1을 ACTIVE로 둔 채
승인된 recovery-unpause를 수행해 임시 서비스를 복구한다. cleanup된 forecast/statistics는 보존된 observation과
lineage에서 다시 생성되도록 두며 marker를 되돌리지 않는다. activation 도중 또는 이후 실패하면 fence를
유지하고 별도 승인된 temporary bundle 재-promotion을 사용한다. ACTIVE 행을 수동 UPDATE하지 않는다.

## 공식 첫 generation 검증

activation 전 다음을 모두 만족해야 한다.

- route coverage가 정확히 `[1650,3330]`이고 각 route에 하나의 새 revision이 있다.
- `calculation_version='observed-max-capacity-v1'`, `data_until=FINAL_CUTOVER_AT`이다.
- generation ledger가 source seed gzip SHA, source receipt file SHA, merged canonical rows SHA,
  dry-run/apply receipt SHA를 가진다.
- 각 route의 materialized cell count와 canonical cell SHA가 독립 재계산값과 같다.
- generation key `(route_version_id, calculation_version, revision, data_until, computed_at)`가 frozen temporary
  generation 집합과 겹치지 않는다.
- seed staging/read-back의 key count·합계·SHA가 dry-run과 같다.

## serving 후 generation 참조 검증

unpause 뒤 생성된 formal forecast를 두 route에서 최소 한 행 이상 확인한다. 각 행은 다음을 만족해야 한다.

```text
forecast.model_deployment_id = formal deployment id
deployment.release_id = v41b-8194bde56d86f365afd6
deployment.calculation_version = observed-max-capacity-v1
forecast.demand_statistics_revision = referenced generation revision
referenced generation.data_until <= prediction observation response_received_at
referenced generation is official-first or a later non-frozen generation
referenced generation key not in frozen temporary generation set
```

미해결 generation reference, frozen-key reference, route 미포함은 모두 0이어야 한다. board 200만으로 이
검증을 대신하지 않는다.

## Receipt 계약

각 dry-run/apply/cleanup/promotion은 immutable receipt와 SHA를 남기고, 최종 성공 receipt는
`seed-cutover-receipt.schema.json`을 만족해야 한다. 핵심 필드는 다음과 같다.

- `approval`: provisional override sidecar와 각 dry-run/apply/cleanup/promotion 승인 SHA
- `finalCutoverAt`: writer drain 뒤 고정한 T
- `sourceSeed`: 전달 payload의 count·세 SHA·route별/전체 합계
- `rdsReplay`: repeatable-read snapshot, T, changed/new/restated key 수, delta 합계, full replay SHA
- `mergedSeed`: final row count, primary-key SHA, canonical rows SHA, route별/전체 합계
- `databaseWrite`: staged/applied/read-back count와 SHA, transaction commit, unplanned change 0
- `officialGeneration`: 두 route revision/cell count/cell SHA, seed digest match, frozen intersection 0
- `cleanup`: frozen-set SHA, 삭제 count, observation/marker/lineage 변경 0, no-op rerun 0
- `deployment`: activation-last, formal identity, id 1 RETIRED, promotion switch false 복원
- `serving`: 두 route 새 forecast와 generation reference 검증
- `invariants`, `privacy`, `rollback`: 모든 안전 조건과 복구 artifact

DRY_RUN은 DB mutation 없이 같은 canonical plan을 만들고 그 SHA를 approval에 묶는다. APPLY는 그 plan SHA와
현재 snapshot/frozen-set SHA가 같을 때만 실행한다. ROLLBACK은 seed release를 비활성화하되 source payload,
execution receipt, observation, marker, deployment lineage를 삭제하지 않는다. 최종 receipt가 생성되기 전에
실패한 경우에도 완료된 phase receipt는 새 경로에 보존한다.

## Testcontainers fixture

`fixtures/cell-hourly-aggregate.fixture.json.gz`는 production payload와 같은 root/row schema·metadata를 가진
100행 fixture다. source canonical order에서 route별 인덱스
`floor(i*(routeRows-1)/49), i=0..49`를 골라 50행씩 포함한다.

| 항목 | 값 |
|---|---|
| rows | 100, route별 50 |
| gzip bytes | 3,001 |
| canonical JSON bytes | 21,753 |
| gzip SHA-256 | `0972303007e55b729c201dd760626e6cb986c554f80325b53a2e66e806638d94` |
| canonical JSON SHA-256 | `4cd3985cc46d8a7032eae078fbec66c4afe9a294a19823689aa72b0a8d9cea0a` |
| primary-key SHA-256 | `49cd771e06567b1050095f67f45aa9ff5d1efe9c62a8a1108798860e4417c56b` |

정확한 route별 합계와 selection 정보는 `fixtures/receipt.json`에 있다. `.json`과 `.json.gz`는 같은 canonical
payload이며, fixture path 자체가 production apply 대상이 되지 않도록 importer는 별도 test profile에서만
사용한다.
