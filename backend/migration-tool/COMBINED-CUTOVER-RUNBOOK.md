# S3 역사 이관·임시 모델 교체 합본 runbook

## 상태와 실행 경계

기본 시나리오는 2026-09-03 학원 IP에서 수행하는 낮 합본 작업이다. 아침에 코드 통합과 worker 배포를
한 번 끝내고, 학원 SSH에서 read-only preflight, archive 전송, 무중단 import와 reconciliation을 먼저
완료한다. 그 뒤 배차가 뜸한 구간에 worker를 `FORECAST_ENABLED=false`로 재기동해 파생 쓰기를 멈추고
activation-last 순서로 임시 모델을 `v41b-8194bde56d86f365afd6`로 교체한다.

이 문서는 실행 승인이 아니다. commit, push, PR, merge, worker 배포, SSH, archive 전송, schema/import,
route `valid_from` 확장, cleanup, seed apply, 모델 승격, rollback과 파일 삭제는 각각 해당 승인을 받은 뒤에만
수행한다. 로컬 personal credential로 academy RDS에 연결하거나 장기 키를 복사하지 않는다.

release gate는 `PROVISIONAL_19D (N=19<30)`로 FAIL이다. 사용자가 2026-09-03 01:15 KST에 이 품질 gate만
명시적으로 무시하고 교체를 진행하기로 했다. 이 결정은 digest, replay parity, cleanup, seed, serving
reference 또는 승인 gate를 완화하지 않는다.

## 고정 identity

| 항목 | 고정값 |
|---|---|
| formal release | `v41b-8194bde56d86f365afd6` |
| bundle digest | `9bb1a5ac22a317931d409f98cb5e9b1935c1b346ae86e4ba05d084614c863632` |
| manifest SHA-256 | `8a39fbf8a828e8e490d500d9b99b6235c8fe7cff896f1986e9f186ddee3c33e4` |
| weights SHA-256 | `5b906da96d7b3e4b45c5e9d970df41c499f0d457756b853b613f672f589a3228` |
| seed gzip SHA-256 | `7f3be7dc2c668d1ed4b8665c341c2cda24968d3fa2e6ed2755261a20c3826ec4` |
| seed receipt file SHA-256 | `9985723799bc859cc22b4cda613a50922ff2191b15c7c155d8f1d7e177db8c26` |
| seed canonical JSON SHA-256 | `adcf0d04a4b67dc40bed6f3640f0f78697e9215bf794ff2b262f0ba0ec0a5ada` |
| seed canonical rows SHA-256 | `15c221a149f241555e32a4133ed1f31b3d9eaeedc3062c5a454d6b9505489013` |
| seed primary-key SHA-256 | `30b2ffc79cc64decc7936efd0a03e51602e5e12424e0e8cbdb7ebf7d98a6a73d` |
| seed rows | 45,224 |
| delivery ZIP | `/Users/idonghun/paseo/workspaces/2026-salmonbus/data-analysis/handoff/coefficient-bundle-v4-1/delivery/coefficient-bundle-v4-1-v41b-8194bde56d86f365afd6.zip` |
| delivery ZIP SHA-256 | `0d578f80eaebbeab6eddb545c9554773e2a270d6518df8f1a7f333da0f372a06` |

formal deployment ID와 `activated_at`은 승격 결과에서 읽는다. 미리 가정하거나 `FINAL_CUTOVER_AT` 대신
사용하지 않는다.

## 정본 우선순위

| 범위 | 정본 | 관계 |
|---|---|---|
| 전체 실행 순서 | 이 문서 | activation-last 운영 순서(P0–P10)와 승인 gate |
| seed 의미·검증 | `seed/SEED-CONTRACT.md`, payload/receipt schema | 이 문서는 그 10단계를 CLI로 연결 |
| seed payload 적용 | migration-tool `seed-*` | exact numeric, full replay, apply/rollback/final verify 정본 |
| 임시 세대 장부 | `study-dev-runtime/temp-release/TEMPORARY-RELEASE-LEDGER.md` | 변경하지 않는 identity 증거 |
| cleanup 사전 교차검증 | `study-dev-runtime/temp-release/cleanup-dryrun.sh` | read-only 보조; 출력 SQL 실행 금지 |
| cleanup·freeze | migration-tool `temp-freeze`, `temp-cleanup` | durable boundary와 bounded delete 정본 |
| cleanup 후 교차검증 | `study-dev-runtime/temp-release/post-cleanup-verify.sh` | 보조; migration receipt/high-water 검증이 우선 |

보조 스크립트와 migration-tool 결과가 다르면 중단한다. 전체 observation count는 수집 때문에 증가할 수
있으므로 cleanup 불변식은 `temp-pause` 때 고정된 `observation_batch_high_water` 이하만 비교한다.

## 교체 창 정지 수단

파생 쓰기는 fence가 아니라 **worker 재기동**으로 멈춘다. `forecast.enabled=false`면
`ForecastScheduleConfig`·`ForecastJob`·`ForecastBatchWriter`·`ArrivalLabelJob`·`StopDemandStatisticsJob`
빈이 `@ConditionalOnProperty`에 걸려 아예 뜨지 않는다. 수집은 `collection.enabled`만 보므로 계속 돈다.
파생 쓰기 3종은 각각 한 transaction 단위(판 하나, 정산 회차 하나, 세대 하나)이고 재기동은
`systemctl stop` → 프로세스 종료 확인 → `start`이므로 반쯤 쓰인 판은 남지 않는다.

`temp-pause`는 이제 **경계 장부**다. 정지 전제 검사를 통과해야만
`forecast_cutover_control.writes_paused=true`와 DB 시계의 `FINAL_CUTOVER_AT`, observation high-water를 한
transaction에서 고정한다. advisory lock `(1920224641, 4041)`은 도구 명령끼리의 상호배제로만 남는다.
worker는 이 lock도 이 표도 읽지 않는다.

정지 전제 검사는 `max(seat_forecast.generated_at)`, `max(seat_forecast.scored_at)`,
`max(stop_demand_statistics.computed_at)` 셋이 모두 `clock_timestamp() − 120초`보다 오래됐을 때만 pause를
허용하고, 하나라도 창 안이면 `TEMP_FORECAST_WRITES_NOT_QUIESCENT`로 멈춘다. fence가 하던 "쓰기 없음"
보장을 이 기계 검사가 대신한다.

| 동작 | 교체 창 중 결과 | 근거 |
|---|---|---|
| raw collection과 `observation_batch`/`vehicle_observation` INSERT | 계속 | collector는 `collection.enabled`만 본다 |
| forecast 생성·`forecast_completed_at` 갱신 | 빈 자체가 없음 | `ForecastScheduleConfig`의 `@ConditionalOnProperty` |
| arrival label settle | 빈 자체가 없음 | `ArrivalLabelJob`이 뜨지 않음 |
| stop-demand 통계 append | 빈 자체가 없음 | `StopDemandStatisticsJob`이 뜨지 않음 |
| route binding, import, seed, cleanup, model activation | 영향 없음 | 각각 migration/activation 승인과 자체 transaction 사용 |

확인 수단은 actuator skip counter가 아니다(그 계기는 fence와 함께 사라졌다). 위 세 max()가 2회차(≥120초)
동안 멈춰 있는지, `observation_batch` 행 수가 계속 느는지, health가 UP인지를 본다.

board API는 raw 최신 batch가 아니라 `forecast_completed_at`이 있는 최신 batch를 읽는다
(`JpaBoardQueryRepository.findLatestForecastCompleted`). freshness 창은 5분이다. 따라서 P0 재기동 직후에는
기존 완료 batch로 200일 수 있지만 약 5분 뒤에는 수집이 계속되어도 `NO_RECENT_OBSERVATION` 503이 정상이다.
P8 재기동 뒤에는 staleness 창(5분) 때문에 교체 창 동안 쌓인 판을 건너뛰고 새 판부터 예보하므로 backlog
처리를 기다리지 않고 **1회차 안에 200으로 돌아온다**.

건너뛴 판은 `forecast_completed_at IS NULL`로 영구히 남는다. 정상이며 종결 표시는 하지 않는다. 식별 SQL은
`forecast_completed_at IS NULL AND response_received_at < now() - interval '5 minutes'`다.

## 아침: 코드 통합과 worker 배포 1회

| 단계 | 승인·예상 | 통과 조건 | 실패 시 |
|---|---|---|---|
| M1. 최종 diff/CI | local, 20–40분 | 대상 리허설과 전체 backend build PASS | commit/PR 금지 |
| M2. 3개 commit | commit 승인 | `feat(migration)`, `feat(worker)`, `docs(migration)`만 | 승인 범위 재확인 |
| M3. push/PR/review | 각각 별도 승인 | base/dev delta와 additive 영향 검토 | merge 금지 |
| M4. dev merge | merge 승인 | CI SHA와 merge SHA 일치 | 배포 금지 |
| M5. worker 배포 | deployment 승인, 15–30분 | health UP, temp id 1 sole ACTIVE, `MODEL_BUNDLE_PROMOTE_ON_START=false`, collector count 증가 | 승인된 이전 artifact로 복구; 원격 이관 시작 금지 |

worker는 `forecast_cutover_control`을 읽지 않으므로 historical Flyway V1–V3 적용 여부와 무관하게 평소처럼
작동한다. 배포본에 staleness 창이 들어갔는지 확인한다: `forecast.staleness`(`FORECAST_STALENESS`, 기본
`5m`)가 적용되고 큐 술어가 `response_received_at >= :notBefore`를 쓰는지. `ingestion_origin` 열은 도구와
trainer만 쓰므로 schema는 import 전에 적용하면 된다.

2026-09-03 read-only preflight의 배포 기준선은 worker `d856d108`, api-app `ed2cf742`다. api-app은
d856으로 재배포되지 않았고 DB 기반 두 노선 board는 정상이다.

## 학원 SSH: preflight·전송·무중단 import

학원 IP에서만 SSH한다. 연결은 `BatchMode=yes`, `ConnectTimeout=10`으로 한 번 확인하고 실패하면 반복하지
않는다. 보안그룹 변경 없이 접근할 수 없으면 이 날 원격 작업을 중단한다.

source archive build는 종료 코드 0만으로 통과시키지 않는다. receipt의 `complete=true`와 빈
`rejectsByCode`를 반드시 확인한다. `SOURCE_DUPLICATE_SEMANTIC_BATCH`는 build reject로 강등되므로 이
검사를 생략하면 적재 단계에서야 차단된다.

source closure 정본은
`/Users/idonghun/paseo/workspaces/2026-salmonbus/data-analysis/handoff/coefficient-bundle-v4-1/processed/final-source-closure.json`이다.
감사 폴더에서 찾지 않는다. terminal receipt 작성 전 RUNBOOK의 `jq -e` 명령으로 하드핀
`75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7`과 대조한다.

| 단계 | 승인·예상 | 검증 | 실패/복구 |
|---|---|---|---|
| D1. EC2/RDS read-only preflight | SSH + read-only, 10–20분 | 계정/DB identity, disk, memory, temp id 1, collector freshness | write·전송 금지 |
| D2. archive 전송 | transfer approval, 5–20분 | rsync resume 후 archive hash를 EC2 local에서 재검증 | partial file 유지, 재승인 후 resume |
| D3. archive-bound preflight | read-only, 10–20분 | app Flyway가 정확히 V1–V12, route 두 개 exact, archive seal/공간 | schema/import 금지 |
| D4. additive schema | `ACADEMY_SCHEMA`, 5–10분 | 별도 history의 historical V1–V3, 경계 장부 unpaused, `ingestion_origin` default | 진행 중단·증거 보존 |
| D5. base+terminal stage/validate/merge | `ACADEMY_IMPORT`, 20–90분; 열화 시 최대 4시간 | manifest-bound COMPLETE, quarantine 0, route별 continuity | ledger 기반 resume 또는 별도 rollback |
| D6. reconciliation | read-only, 10–20분 | 149,193 batches, 2,461,308 observations, LIVE 불변, source/live seam 무중복 | 교체 창 시작 금지 |
| D7. statement timeout 상향 | read-only 실측 + 설정 변경, 20–40분 | import 후 246만 행에서 잰 첫 행 시간과 `Sort Method`, 그 배수로 정한 `import.statement-timeout-seconds` | 실측 없이 올리지 않는다; 교체 창 시작 금지 |

import에는 정지가 필요 없다. importer는 `S3_BACKFILL`로 넣고 collector JPA INSERT는 default `LIVE`지만,
큐가 백필을 배제하는 근거는 origin 술어가 아니라 **staleness 창**이다. 백필 행의 최신
`response_received_at`이 2026-09-02T13:20:01Z 이전이라 T=5분 창 밖이고, worker는 `ingestion_origin`을
읽지도 않는다. reconciliation의 진짜 불변식은 그대로 "imported 행을 가리키는 `seat_forecast` 0건"이다.
import 중 collection·forecast·board는 계속된다. route `valid_from` 확장은 exact current version gate 아래
단일 transaction이며 별도 DB write 승인 대상이다.

## 교체 창 시작 전 go/no-go

- archive import/reconciliation, route별 authority, terminal seal이 모두 PASS다.
- application Flyway history는 이름뿐 아니라 version/name/checksum/success가 기대 V1–V12와 정확히 일치한다.
- historical V1–V3가 적용됐고 `forecast_cutover_control.writes_paused=false`다.
- D7에서 `import.statement-timeout-seconds`를 실측 기반으로 상향했고 그 근거(첫 행 시간, `Sort Method`)를 남겼다.
- delivery ZIP, bundle, manifest, weights, seed gzip/receipt의 digest가 위 표와 일치한다.
- bundle과 seed는 EC2의 private incoming 경로에 stage됐고 free space와 1.8GiB memory 조건을 만족한다.
- temp id 1이 sole ACTIVE이고 forecast/observation counts와 recovery artifact가 기록돼 있다.
- cleanup, seed apply, promotion, unpause 승인 담당자가 교체 창 내내 응답 가능하다.
- P0 재기동이 끝났고 health UP, 두 route raw observation count 증가, 파생 쓰기 3종 시계 정지를 확인했다.
- 141분 운영 buffer를 확보하지 못하면 교체 창을 시작하지 않는다.

## activation-last 교체 창

권장 후보는 peak 07:00–09:00, 17:00–20:00를 피한 13:00–14:30 KST다. 시작 직전 15분 동안 두
route의 observation 간격, `vehiclesInService`, API 요청량을 읽고 실제 배차가 뜸할 때만 시작한다. 14:30
이후에는 17:00 전 buffer가 부족하므로 새 교체 창을 시작하지 않는다.

| 단계 | 승인·예상 | 반드시 남길 증거 | 실패 복구 |
|---|---|---|---|
| P0. `FORECAST_ENABLED=false` 재기동 | 별도 restart 승인, 3–8분 | health UP, `observation_batch` 증가 지속, 파생 쓰기 3종 max()가 2회차(≥120초) 정지 | env 되돌리고 재기동; 교체 창 시작 금지 |
| P1. `temp-pause` | `ACADEMY_TEMP_CLEANUP`, 2–5분 | 정지 전제 검사 통과, pause=true, T/high-water, raw count 계속 증가 | temp sole ACTIVE면 승인된 recovery-unpause |
| P2. freeze dry-run/apply | apply에 cleanup approval, 3–8분 | 같은 T/high-water, exact frozen generation set/SHA | `FORECAST_ENABLED=false` 유지, cleanup 금지 |
| P3. cleanup dry-run | read-only, 3–8분 | exact temp forecast/cell target, observation invariant | 불일치 시 DELETE 금지 |
| P4. bounded cleanup+no-op | dry-run SHA에 묶인 별도 deletion 승인, 10–30분 | deleted=target, no-op 0, observations/markers/lineage 보존 | `FORECAST_ENABLED=false` 유지, receipt-bound resume |
| P5. seed dry-run | read-only, 10–35분 | source `F_C` parity, RR read-only `F_T`, signed `D_T`, `M_T`, exact decimal/count/SHA | temp ACTIVE 유지, recovery-unpause 가능 |
| P6. seed apply | `ACADEMY_SEED_APPLY`, 5–20분 | plan 재검증, numeric read-back, 두 official generation cell SHA, frozen 교집합 0 | transaction rollback 또는 승인된 seed rollback |
| P7. activation 전 generation 검증 | read-only, 2–5분 | route coverage `[1650,3330]`, `data_until=T`, receipt digests | promotion 금지 |
| P8. `PROMOTE_ON_START=true`+`FORECAST_ENABLED=true` 재기동 | 별도 promotion/restart 승인, 8–15분 | 1회 재기동, formal sole ACTIVE, id 1 RETIRED, 즉시 promote 플래그 false 복원(재기동 불필요) | `FORECAST_ENABLED=false`로 되돌림; 별도 temp re-promotion 승인 |
| P9. formal unpause | `ACADEMY_TEMP_CLEANUP`, 2–5분 | exact formal identity와 seed link 후 pause=false | 수동 UPDATE 금지 |
| P10. serving verify | read-only, 2–10분 | 두 route 새 formal forecast, official-or-later revision, frozen 참조 0, board 200 | incident/fallback 승인; 삭제 재실행 금지 |

예상 교체 창(P0 재기동부터 P10까지)은 **47–141분**이다. production 246만 관측 full replay는 EC2에서 아직 계측되지 않아 10–35분
범위가 추정치다. 256MiB는 코드가 강제하거나 production에서 입증한 ceiling이 아니라 외부 관측용 stop
threshold다. 35분 또는 256MiB를 넘으면 자동으로 계속하지 말고 `FORECAST_ENABLED=false`를 유지한 채 중단
여부를 결정한다.
replay의 binary64 누적은 순서 의존적이므로 query와 합산 순서를 route/time/batch/source-row/target으로
고정한다. provider cutoff parity가 마지막 자리까지 일치하지 않으면 tolerance 없이 중단한다. 전체 4시간
열화 import는 pause 밖 D5에서만 허용한다.

```bash
<APPROVED_FORECAST_ENABLED_FALSE_RESTART>
<VERIFY_HEALTH_UP_COLLECTION_ADVANCING_AND_DERIVED_CLOCKS_STOPPED>

java -jar "$MIGRATION_JAR" temp-pause \
  --config "$CUTOVER_CONFIG" --approval "$PAUSE_APPROVAL" > "$PAUSE_RESULT"

java -jar "$MIGRATION_JAR" temp-freeze \
  --config "$FREEZE_DRY_CONFIG" --execute false
java -jar "$MIGRATION_JAR" temp-freeze \
  --config "$FREEZE_APPLY_CONFIG" --execute true --approval "$FREEZE_APPROVAL"

java -jar "$MIGRATION_JAR" temp-cleanup \
  --config "$CLEANUP_DRY_CONFIG" --execute false --delete-batch-rows 1000
java -jar "$MIGRATION_JAR" temp-cleanup \
  --config "$CLEANUP_APPLY_CONFIG" --execute true --delete-batch-rows 1000 \
  --dry-run-receipt "$CLEANUP_DRY_RECEIPT" --approval "$CLEANUP_APPROVAL"
java -jar "$MIGRATION_JAR" temp-cleanup \
  --config "$CLEANUP_NOOP_CONFIG" --execute false --delete-batch-rows 1000

java -jar "$MIGRATION_JAR" seed-dry-run \
  --config "$SEED_DRY_CONFIG" --seed "$SEED_GZIP" \
  --seed-receipt "$SEED_SOURCE_RECEIPT" --output "$SEED_PLAN"
java -jar "$MIGRATION_JAR" seed-apply \
  --config "$SEED_APPLY_CONFIG" --seed "$SEED_GZIP" \
  --seed-receipt "$SEED_SOURCE_RECEIPT" --plan "$SEED_PLAN" \
  --approval "$SEED_APPLY_APPROVAL"

<APPROVED_PROMOTE_ON_START_TRUE_AND_FORECAST_ENABLED_TRUE_RESTART>
<VERIFY_FORMAL_ACTIVE_AND_RESTORE_PROMOTE_ON_START_FALSE>

java -jar "$MIGRATION_JAR" temp-unpause \
  --config "$CUTOVER_CONFIG" --recovery false --approval "$UNPAUSE_APPROVAL"

java -jar "$MIGRATION_JAR" seed-verify \
  --config "$VERIFY_CONFIG" --plan "$SEED_PLAN" \
  --cleanup-receipt "$CLEANUP_RECEIPT" --cleanup-noop-receipt "$CLEANUP_NOOP_RECEIPT" \
  --cleanup-approval "$CLEANUP_APPROVAL" --seed-apply-approval "$SEED_APPLY_APPROVAL" \
  --provisional-approval "$PROVISIONAL_APPROVAL" \
  --promotion-approval "$PROMOTION_APPROVAL" --promotion-receipt "$PROMOTION_RECEIPT" \
  --output "$FINAL_SEED_CUTOVER_RECEIPT"
```

각 config의 `import.receipt-output`과 모든 `--output`은 서로 다른 새 0600 경로다. 명령 stdout을 공유
로그에 남기지 않는다. `seed-rollback --execute true`는 formal activation 전, temp sole ACTIVE,
`FORECAST_ENABLED=false` 유지 상태에서만 별도 `ACADEMY_SEED_ROLLBACK` 승인으로 실행한다.

## seed provider 계약 대조

| provider 조건 | 구현·gate | 상태 |
|---|---|---|
| source cutoff parity, cross-boundary SETTLED, capacity restatement | observation-only full replay로 `F_C==S`, `F_T`, `D_T`, `M_T` 계산 | 닫힘 |
| exact numeric와 canonical read-back | gzip lexical number→`BigDecimal`→PostgreSQL `numeric`; apply/final verify에서 count/key/rows/sums SHA | 닫힘 |
| RR read-only full replay | dry-run과 apply 재검증 모두 `REPEATABLE READ READ ONLY` | 닫힘 |
| provider fixture | 100행 fixture와 expected receipt를 Testcontainers에서 사용 | 닫힘 |
| first generation | route별 independent cell SHA, seed receipt digest, frozen-key 교집합 0 | 닫힘 |
| final receipt/serving reference | `seed-verify`가 `v4-1-seed-cutover-receipt-v1` 형태와 formal forecasts를 검증 | 닫힘 |
| activation-last | P0–P10 및 DB state gate | 닫힘 |

## 승격 수단 전례와 선택

임시 모델은 worker startup의 bundle activation 경로를 사용한 기록과 현재 `BundleActivation` transaction
구조가 확인됐다. `MODEL_BUNDLE_PROMOTE_ON_START=false`에서는 파일 교체와 재기동만으로 승격되지 않는다.

| 수단 | 소요 | 장점 | 위험·rollback |
|---|---:|---|---|
| one-shot `true` 재기동 후 즉시 `false` 복원 | 8–15분 | 임시 승격과 같은 코드 경로, 최단, stage/retire/activate 단일 DB transaction | env/current 교체 오류; 활성화 후 복구는 temp bundle의 별도 새 promotion 승인 필요 |
| 팀 deploy hook 확장 | 20–45분+개발 | 반복성·감사성 향상 | 이번 작업 전에 새 hook/권한/리허설 필요 |
| DB 수동 ACTIVE UPDATE | 사용 금지 | 없음 | bundle 검증·lineage·single-active transition 우회 |

기본은 첫 번째이며 아침 최종 승인을 다시 받는다. promotion receipt에는 release/digest, 새 deployment ID와
`activated_at`, id 1 RETIRED, true 사용, false 복원, artifact hash를 기록한다.

## 일반 re-pause 결정

현재 `temp-pause`는 id 1 sole ACTIVE에 고정돼 있다. formal ACTIVE용 일반 re-pause는 구현하지 않는다.
최소 boolean 완화만으로는 identity drift와 cleanup 중 write race를 막지 못한다.

| 비교 | 총 pause 추정 | 장점 | 판정 |
|---|---:|---|---|
| 현재 activation-last 단일 pause | 47–141분 | activation 전 실패는 temp ACTIVE에서 recovery 가능 | **채택** |
| 가상 unpause→board probe→formal re-pause | 52–156분 + 3–10분 공개 구간 | cleanup 전 formal board 실검증 가능 | 미구현이며 cleanup-before-activation 정본과 충돌하므로 실행 금지 |

일반화하려면 historical schema phase/lease 40–80 LOC, `TemporaryReleaseMaintenance` 상태 머신 180–300
LOC, approval action 15–35 LOC, CLI 25–50 LOC, race/failure Testcontainers 220–350 LOC가 필요하다. 위험은
formal identity drift, re-pause 실패, 두 pause 사이 derived write, crash phase 복구다. 이번에는 Java
bundle probe/golden 검증, activation 전 official generation 검증과 “정식 실패 시 cleanup을 새로 시작하지
않고 `FORECAST_ENABLED=false` 유지·별도 temp promotion” 규칙으로 위험을 수용한다.

## 실패 복구

자동 finally나 watchdog은 없다. `writes_paused=true`는 durable하게 남지만 이제 그것이 worker를 막지
않으므로, process/SSH가 끊긴 뒤 창을 닫으려면 **장부 상태와 worker가 아직 `FORECAST_ENABLED=false`인지**를
함께 확인해야 한다. 파생 쓰기 3종 시계 정지와 raw observation 증가를 감시하고 반드시 승인된 명령으로 닫는다.

| 실패 시점 | 허용 복구 | 금지 |
|---|---|---|
| pause 후 cleanup 전 | temp id 1 sole ACTIVE 확인 후 `temp-unpause --recovery true` | control row 수동 UPDATE |
| bounded cleanup 뒤 seed 전/중 | receipt 보존, seed transaction rollback, temp ACTIVE 확인, 승인된 recovery-unpause | observation/marker 복원, cleanup 재추정 |
| seed apply 뒤 activation 전 | 필요 시 승인된 `seed-rollback`, 아니면 seed 보존 후 recovery-unpause | 다른 plan 적용 |
| activation transaction 실패 | temp sole ACTIVE 여부 검증; temp이면 recovery-unpause | cleanup 재실행 |
| formal ACTIVE 이후 검증 실패 | `FORECAST_ENABLED=false`로 되돌린 뒤 별도 승인으로 temp bundle을 startup promotion 경로로 재승격 | `--recovery true`, DB 수동 state UPDATE |
| unpause 뒤 board 실패 | final receipt/backup 보존, incident와 fallback 승인 | 삭제된 temp forecast가 파일 rollback으로 복구된다고 주장 |

pre-activation recovery 명령은 다음뿐이다.

```bash
java -jar "$MIGRATION_JAR" temp-unpause \
  --config "$CUTOVER_CONFIG" --recovery true --approval "$RECOVERY_UNPAUSE_APPROVAL"
```

## 부록: 별도 밤 창

낮에 교체 창을 시작하지 못하면 집에서는 SSH가 불가하므로 실행하지 않는다. 밤 창을 쓰려면 학원 SSH 또는
별도 승인된 SSM/보안그룹 접근, operator 2명, 141분+30분 buffer, 모든 artifact/approval이 필요하다.
import와 archive 전송은 낮에 끝내고 P0–P10만 수행한다. 첫차 04:50 전에 board 회복을 확인하려면 늦어도
01:30에 시작하고 04:20 hard stop을 둔다.

## 완료 증거

- base/terminal archive manifest와 full-history seal receipt;
- schema, import ledger, route validity rollback receipt와 reconciliation report;
- P0 재기동 전후 확인 기록과 pause/freeze/cleanup dry-run·execute·no-op receipts;
- seed plan/apply receipt와 final `v4-1-seed-cutover-receipt-v1` receipt;
- promotion 재기동/promote 플래그 false 복원 receipt와 formal serving-reference 검증;
- 교체 창 중 raw observation 증가와 파생 쓰기 3종 시계 정지 기록, unpause 뒤 두 route board 200/freshness;
- exact local/EC2 artifact 목록과 SHA. 삭제는 이 목록에 대한 별도 승인 전까지 하지 않는다.
