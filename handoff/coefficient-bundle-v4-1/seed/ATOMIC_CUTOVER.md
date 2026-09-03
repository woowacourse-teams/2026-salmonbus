# Atomic cutover preconditions and order

정식 bundle의 `featureContractVersion`과 current Java writer의
`StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION`은 둘 다
`observed-max-capacity-v1`이다. 이 정합성은 필요하지만, temp forecast를 carrier로 만든 같은 이름의
세대를 정식 세대로 오인할 수 있다는 race도 만든다. 모델 파일만 먼저 활성화해서는 안 된다.

현재 확인된 기준점은 temp activation 전 `stop_demand_statistics=0`이고, 추가 확인 시점에도 temp
statistics 및 SETTLED horizon-1 row가 0이었다. 이것은 cutover 시점의 값이 아니므로 cutover 직전에
반드시 다시 동결한다.

## 필수 순서

1. seed merge/import 경로와 정식 bundle을 배포 가능한 위치에 stage하되 forecast worker에서는 아직
   접근·활성화하지 않는다.
2. forecast·settlement·statistics job을 모두 quiesce하고 진행 중 transaction이 없음을 확인한다.
   이 뒤에는 cutover가 끝날 때까지 새 forecast/statistics writer가 돌면 안 된다.
3. `FINAL_CUTOVER_AT`을 UTC instant로 고정한다. read-only verifier로 다음을 함께 동결한다.
   - deployment 1의 정확한 lineage와 temp forecast/scored/horizon-1 건수
   - temp activation 이상 cutover 미만의 statistics generation
   - 각 generation의 `(route_version_id, calculation_version, revision, data_until, computed_at)`과 cell 수
   - observation row 수와 non-null `forecast_completed_at` batch 수
   정본 verifier는 `TEMP_CLEANUP_DRY_RUN_CANONICAL.sql`, generation receipt schema는
   `temp-generation-manifest.schema.json`이다. `TEMP_EXCLUSION_READ_ONLY.sql`은 formal ACTIVE 전에도
   실행 가능한 보조 inventory다.
4. 사용자에게 정확한 대상 집합과 건수를 보여 주고 cleanup/import 승인을 별도로 받는다. 현재 패키지는
   이 승인을 포함하지 않는다.
5. 승인 후 하나의 DB transaction 또는 동일한 격리 효과를 갖는 maintenance barrier 안에서만 다음을
   수행한다.
   - 동결한 집합이 한 행도 변하지 않았음을 재검증한다.
   - deployment 1의 forecast와 동결된 exact generation만 정리한다.
   - `observation_batch`, `vehicle_observation`, `forecast_completed_at`은 변경하지 않는다.
   - source-side aggregate seed 뒤부터 cutover까지의 target RDS 원 observation을 read-only snapshot으로
     독립 재생해 `rds-observation-delta-contract.json`의 aggregate delta를 만든다. temp forecast/statistics는
     읽지 않는다.
   - source seed와 target observation delta를 half-open cutoff로 합쳐 공식 seed store에 넣는다.
   - seed에서 `observed-max-capacity-v1`의 공식 첫 statistics generation을 materialize한다.
   - 공식 generation이 존재하고 contaminated generation 집합과 겹치지 않음을 확인한다.
   - 정식 deployment를 ACTIVE, deployment 1을 RETIRED로 전환한다. deployment 1 행은 삭제하지 않는다.
6. transaction commit 뒤에만 정식 bundle을 든 worker를 시작한다. 새 forecast가 읽은 statistics의
   calculation version, revision, data-until이 공식 첫 세대 또는 그 후속임을 확인한다.
7. 검증 실패 시 worker를 시작하지 않는다. observation과 lineage 행을 되돌리거나 삭제하지 않는다.

## race를 막는 불변식

- 정식 forecast가 한 행이라도 생기기 전에 temp exact generation은 없어야 한다.
- 같은 시점에 공식 seed 연결과 공식 첫 generation이 완료되어야 한다.
- `calculation_version='observed-max-capacity-v1'`만으로 행을 선택하거나 삭제하지 않는다.
- cleanup 후보는 activation/cutover 시간창으로 generation을 열거한 뒤 exact identity로 잠근다.
- seed 생성 입력은 S3/RDS 원 observation의 명시적 replay뿐이다. live RDS statistics와 temp forecast의
  probability/label/cell은 입력으로 쓰지 않는다.
- 이 패키지의 source-side seed만으로 cutover하지 않는다. target authority 시작부터 실제 cutover까지
  늘어난 RDS observation delta가 별도 data release로 동결·검증되어야 한다.

이 문서는 순서 설계만 제공한다. 원격 파일 전송, process stop/start, SQL write, migration, cleanup,
deployment activation은 모두 별도 승인 대상이다.
