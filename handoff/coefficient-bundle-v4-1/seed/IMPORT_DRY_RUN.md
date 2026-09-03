# Aggregate seed import dry-run

이 파일은 적용 절차의 검증안이며 DB 변경을 승인하거나 수행하지 않는다. 현재 academy RDS는
`seat_forecast=0`, `stop_demand_statistics=0`, `model_deployment=0`인 cold-start다. 따라서 기존 셀
세대와 충돌하지는 않지만, 현재 Java 코드에는 이 aggregate seed를 읽어 live totals와 합치는 경로가
없다.

권장 backend 후속 구현은 별도 hourly aggregate seed table을 만들고 다음 불변식을 보장하는 것이다.

1. 키는 route reference/version, model route, stop order, UTC arrival-hour start로 유일하다.
2. seed cutoff 이전 값은 seed만, cutoff 이후 값은 live `SETTLED` horizon-1 totals만 사용한다.
3. 두 입력을 `UNION ALL`한 뒤 현재 `StopDemandAggregator`와 똑같이 KST date/time-slot으로 접는다.
4. `featureContractVersion=observed-max-capacity-v1`과 route-reference digest가 다르면 fail closed한다.
5. import dry-run은 transaction read-only 또는 rollback-only staging에서 row count, key uniqueness,
   합계, 날짜 범위, 겹침을 검사한다. 운영 import와 배포는 별도 승인을 받는다.
6. live totals를 합칠 때 `model_deployment.id=1` 또는
   `release_id=salmonbus-d57370be9195520e`에서 나온 forecast/scored 행과 그 파생 cell은 anti-join한다.
   그 배포 기간의 `observation_batch`·`vehicle_observation` 원 관측은 유지한다.
7. temp forecast가 current writer를 통해 만든 `observed-max-capacity-v1` 통계도 temp lineage다.
   activation 이상 formal cutover 미만의 세대를 `(route_version_id, calculation_version, revision,
   data_until, computed_at)` 다섯 값으로 동결한다. calculation version만으로 전체를 고르거나 지우지 않는다.

정식 cutover 순서는 다음과 같다. 각 원격 변경은 이 패키지의 범위 밖이다.

1. forecast/statistics writer를 quiesce하고 formal cutover 시각을 고정한다.
2. `TEMP_EXCLUSION_READ_ONLY.sql`로 pre-cutover inventory를 확인하고,
   `TEMP_CLEANUP_DRY_RUN_CANONICAL.sql`을 실제 cutover 시각과 repeatable-read/read-only transaction으로
   실행하여 temp forecast 및 정확한 오염 generation 집합·건수를 동결한다. 출력은
   `temp-generation-manifest.schema.json`에 맞춰 별도 aggregate receipt로 보존한다.
3. 정식 교체 성공과 별도 사용자 승인을 받은 뒤에만 temp forecast와 동결된 generation tuple만 정리한다.
4. 원 observation과 `forecast_completed_at`은 그대로 두고 deployment 1은 `RETIRED` lineage로 보존한다.
5. aggregate seed merge 경로를 적용하고 공식 계산 버전의 첫 세대를 만든 뒤 정식 bundle을 활성화한다.
6. 이후 live totals는 seed cutoff 뒤의 원 observation에서 만든 공식 forecast만 이어 붙인다.

현재 seed는 S3 원관측과 명시적 chronological backfill만으로 만들어지며 live RDS의
`stop_demand_statistics`는 입력으로 읽지 않는다. 따라서 temp 확률을 직접 쓰지 않는다는 이유뿐 아니라
carrier lineage까지 독립되어 있다.

로컬 파일 검증 명령:

```bash
scripts/validate_seed.py \
  --seed seed/cell-hourly-aggregate.json.gz \
  --receipt seed/receipt.json \
  --output processed/seed-validation.json
```

academy RDS에 대한 이번 작업의 결과는 조회 기준점 기록뿐이다. seed INSERT, schema migration,
bundle 전송, model deployment 행 생성은 모두 수행하지 않았다.
