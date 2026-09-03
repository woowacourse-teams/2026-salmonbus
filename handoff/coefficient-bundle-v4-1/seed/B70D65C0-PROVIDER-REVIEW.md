# b70d65c0 aggregate-seed provider review

## Review basis

- reviewed at: 2026-09-03 01:36 KST
- provider contract: `seed/SEED-CONTRACT.md`
- production fixture: `seed/cell-hourly-aggregate.json.gz`
- Testcontainers fixture: `seed/fixtures/cell-hourly-aggregate.fixture.json.gz`
- consumer worktree: `/Users/idonghun/.paseo/worktrees/0p640er8/be-s3-rds-migration`

검토한 consumer draft는 `AggregateSeedReader`, `AggregateSeedPayload`, `RdsObservationDeltaBuilder`,
`AggregateSeedCutover`, `V3__aggregate_seed_cutover.sql`, `TemporaryReleaseMaintenance`다.

## 합의된 순서

b70d65c0의 01:26 checkpoint와 provider 정본은 다음 순서로 일치한다.

```text
pre-stage/import COMPLETE
→ temp-pause + shared-writer drain
→ FINAL_CUTOVER_AT/observation high-water/exact temp set freeze
→ exact temp cleanup
→ observation-only full replay at FINAL_CUTOVER_AT
→ merged seed write/read-back
→ two-route official first generation
→ formal one-shot activation last
→ promote-on-start=false 복원
→ unpause
→ serving generation-reference 검증
```

formal activation 전 실패는 temporary deployment id 1을 ACTIVE로 유지하고 seed transaction을 rollback한 뒤
recovery-unpause한다. 이미 완료된 bounded cleanup은 receipt를 보존하고 observation에서 재생하며 marker를
되돌리지 않는다.

## 현재 draft에서 확인된 일치

- production seed gzip/canonical/receipt SHA, bytes, 45,224 rows와 route cutoff를 고정한다.
- canonical JSON과 private regular-file gate를 둔다.
- `FINAL_CUTOVER_AT`과 observation batch high-water를 pause control에 묶는다.
- seed dry-run/apply는 temporary deployment가 sole ACTIVE이고 exact freeze가 CLEANED일 때만 진행한다.
- 공식 generation `data_until`을 `FINAL_CUTOVER_AT`으로 둔다.
- route별 never-used revision을 고르고 seed generation ledger를 남긴다.
- rollback은 seed hourly/generation/statistics를 같은 seed identity로 제거하고 ledger를 보존한다.

## 구현 수용 전 필수 수정

| 항목 | 현재 draft | provider contract | 판정 |
|---|---|---|---|
| numeric fidelity | `SeedHourlyRow`와 DB raw totals가 `double`/`double precision` | JSON lexical number를 `BigDecimal`/`numeric`으로 보존 | BLOCKER |
| RDS input window | `ingestion_origin='LIVE' AND response_received_at>=sourceCutoff` | imported 시작부터 `< FINAL_CUTOVER_AT` full context replay | BLOCKER |
| snapshot isolation | read-only만 설정하고 explicit repeatable-read 확인 없음 | `REPEATABLE READ READ ONLY`와 T/high-water 고정 | 필수 |
| delta 의미 | post-cutoff LIVE aggregate를 source row에 단순 add | `D_T=F_T-S`, `M_T=S+D_T=F_T` full-replay difference | BLOCKER |
| cross-boundary label | cutoff 전 prediction을 query하지 않음 | cutoff 전 prediction이 cutoff 뒤 available된 h1 contribution 포함 | BLOCKER |
| capacity restatement | 새 LIVE contribution에만 T 시점 capacity 사용 | T 전 새 maximum이 기존 source key의 fill/capacity totals에 미친 변화 포함 | BLOCKER |
| replay parity | source cutoff 시점 replay를 수행하지 않음 | route별 `F_C`가 source seed S의 key/count/sums/canonical rows SHA와 exact 일치 | BLOCKER |
| source aggregate verification | row/sample count와 input SHA 중심 | route별/전체 4개 합계, primary-key SHA, canonical rows SHA exact 비교 | 필수 |
| payload structure | production SHA가 맞으면 row order/field type을 별도 강제하지 않음 | schema, exact field set, type, unique natural key, canonical row order 검증 | 필수 |
| seed read-back | row/generation/statistics count 중심 | dry-run과 DB read-back의 key/count/decimal sums/canonical SHA 일치 | 필수 |
| first generation | generation 2개와 statistics >0 | route coverage, 각 cell count/SHA, seed receipt digest, frozen-key intersection 0 | BLOCKER |
| final receipt | apply receipt가 plan SHA와 counts 중심 | `seed-cutover-receipt.schema.json`의 approvals/delta/sums/SHA/cleanup/deployment fields | 필수 |
| serving check | 현재 seed draft 범위에 없음 | formal forecast가 official-first-or-later generation을 참조하고 invalid/frozen reference 0 | BLOCKER |

`double`은 공식 `stop_demand_statistics` cell 계산 결과에는 사용할 수 있다. 그러나 source/merged hourly raw
totals와 그 검증 ledger에는 사용할 수 없다. raw totals를 exact 저장한 뒤 `StopDemandAggregator`에 Java
double로 투영해야 한다.

## Fixture acceptance

Testcontainers는 최소한 다음을 증명해야 한다.

1. provider의 실제 `fixtures/cell-hourly-aggregate.fixture.json.gz`를 읽어 gzip과 canonical SHA, 100 rows,
   두 route 각 50 rows를 exact 검증한다. 별도 2-row 재작성 fixture로 대체하지 않는다.
2. natural key와 canonical row order를 보존한다.
3. `fixtures/receipt.json`의 route별/전체 decimal 합계가 DB read-back과 같다.
4. 같은 key의 delta merge, 새 key, cutoff-crossing SETTLED contribution, capacity restatement를 각각 만든다.
5. dry-run과 apply plan SHA가 같고 두 번째 apply는 no-op이다.
6. exact cleanup 전에는 seed apply가 거절되고 formal activation 전 failure는 temp ACTIVE로 복구된다.
7. 공식 generation 두 route의 cell SHA가 독립 aggregator 결과와 같다.
8. frozen temporary generation key를 참조하는 serving forecast fixture는 fail-closed한다.

이 BLOCKER가 닫히기 전 production seed apply나 formal activation으로 진행하지 않는다.
