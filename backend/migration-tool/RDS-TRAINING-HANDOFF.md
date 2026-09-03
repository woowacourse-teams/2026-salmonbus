# RDS training handoff for v4-1

## Handoff boundary

This document defines the database snapshot and provenance interface needed to retrain a v4-1
bundle from migrated RDS history. It does not build a bundle, fit coefficients, upload a seed,
promote a deployment, or write to academy RDS. Those operations belong to the data-analysis and
approved deployment flows.

Training must run on an approved local or adequately sized environment. The academy EC2 instance has
about 1.8 GiB RAM and no swap and must not run the full training job.

## Dataset authority

The trainer may start only from a terminal dataset seal joined to a COMPLETE terminal import. The
authoritative source identity is:

| role | SHA-256 |
|---|---|
| immutable base inventory | `db47305386c77fd6d28411ce09b5e1633a029027bc15d41c8201139fb9d535b9` |
| terminal partition inventory | `f0decee3446e0e787532c9682bd3c6c627ccf9f10cdba9a992829345fdefa86e` |
| full-history inventory and seal authority | `ad7dca914792eb243008df549fef844e5a32d004a867d7c10c44b6c979b7fad8` |
| data-analysis source closure | `75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7` |

The sealed source authority contains 149,193 imported batches and 2,461,308 imported observations.
The 811 overlap records / 14,924 observations are source-side reconciliation evidence, not training
rows imported into RDS.

The trainer must reject a seal when its import is ROLLED_BACK, its previous base import is not
COMPLETE, its stored inventory differs, or any ordered manifest/receipt digest is missing.

## Snapshot transaction

Use one PostgreSQL transaction with:

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
```

At transaction start, capture all of the following in one canonical receipt:

- `transaction_timestamp()` and `pg_current_snapshot()`;
- terminal seal row and joined COMPLETE import identity;
- ordered base and terminal archive manifest SHA-256 values;
- source inventory, partition, base, and composite closure digests;
- application Flyway V1..V12 checksums and historical migration V1..V3 checksums;
- maximum included `(observation_batch.response_received_at, observation_batch.id)`;
- batch/observation counts grouped by origin, outcome, route, normalization version, and strategy;
- quarantine, overlap, duplicate, and rollback ledger counts;
- route binding IDs, roster digests, and original/final validity;
- temporary forecast and frozen-generation exclusion policy digests/counts;
- trainer code commit, dependency lock digest, v4-1 contract version, and final bundle digest.

The snapshot high-water predicate is:

```sql
(response_received_at, id) <= (:data_until, :batch_id_tie_breaker)
```

No query may substitute wall-clock `now()` for that cursor after training begins.

## Tables and allowed origins

Read observation history from:

- `route`, `route_version`, `route_stop`;
- `observation_batch`, `vehicle_observation`;
- `migration_source_record` and the COMPLETE import ledgers.

Include both `LIVE` and `S3_BACKFILL` origins under the captured high-water. Use `SUCCESS_ROWS` and
`SUCCESS_EMPTY` for feature materialization, while retaining failed/empty batches in the cadence
timeline so a collection gap is not interpreted as zero vehicles. Quarantined and LIVE_OVERLAP
source records are not database training rows.

Quota-outage LIVE batches remain audit events. They may describe gaps/censoring but do not become
successful feature rows.

## Private identity and route continuity

Inside the private read-only transaction, `vehicle_observation.vehicle_id` is the exact trajectory
identity. It may be used to join observations across the source/target boundary but must not be
written to feature exports, bundle files, receipts, logs, fixtures, or stdout. Plate, source HMAC,
raw response, and source object location are not trainer inputs.

Both historical and live 3330 rows use route version 1. Both historical and live 1650 rows use route
version 2. Resolve roster and stop policy from that version; do not join by content digest alone and
do not create an adjacent duplicate version.

## Temporary lineage exclusion

Read forecast input through `training_eligible_seat_forecast`, never directly from
`seat_forecast`. The temporary deployment identity remains RETIRED for audit, while its forecast rows
are excluded from training and evaluation.

Read historical cells through `training_eligible_stop_demand_statistics`. Before formal cutover,
the view fails closed for candidate temporary generations after temp activation. After cutover it
excludes the exact frozen keys:

```text
(route_version_id, calculation_version, revision, data_until, computed_at)
```

The wall-clock selection rule is `computed_at >= 2026-09-02T11:55:04.729493Z` and
`computed_at < formal model_deployment.activated_at`. `data_until` is an identity/as-of field, not a
second wall-clock-window condition. Official trainer, evaluator, cell backfill, and formal seed reads
must contain zero frozen rows.

Observation rows from the same interval remain eligible. Never filter them merely because a
temporary forecast was generated.

## Causal as-of rules

- Capacity is the maximum known seat count for the same private vehicle and route version at or
  before the prediction two-column cursor.
- Trajectory reads the complete 30-minute batch timeline, including empty/failure batches, and uses
  deterministic batch and source-row order.
- Arrival candidates may look strictly after prediction time only for label resolution; no future
  row enters a feature.
- A statistics generation is eligible only when `data_until <= prediction observed_at`.
- Route roster and boarding policy are those bound to the observation's route version.
- Equal response timestamps use database batch ID order. Do not claim that S3 object order recreates
  a historical database ID order.

## v4-1 contract

The d856 target leaves the Java design matrix, loader, scorer, and Flyway V1..V12 byte-equivalent to
the earlier validated consumer. Verification uses the v4-1 harness under
`be-models/validation/v4-1/` and must include strict loader/golden verification plus an independent
source-to-feature parity check.

The 31-feature order and five-tensor contract come from `bundle-and-fitting-contract.md`. Ten design
columns remain exact positive zero in the current Java contract. The trainer must not reuse the old
Python A18 feature coordinates merely because tensor shapes match.

The formal `featureContractVersion`/statistics calculation version is
`observed-max-capacity-v1`. The temporary value `seat-feature-contract-v4-1-2026-09-02` is forbidden
for the formal bundle. The delivered candidate is release ID `v41b-8194bde56d86f365afd6`, bundle
digest `9bb1a5ac22a317931d409f98cb5e9b1935c1b346ae86e4ba05d084614c863632`, and aggregate seed gzip
SHA-256 `7f3be7dc2c668d1ed4b8665c341c2cda24968d3fa2e6ed2755261a20c3826ec4`. It is
`PROVISIONAL_19D` with `FAIL_N_LT_30`; the user explicitly accepted that release risk at
2026-09-03 01:15 KST. The combined runbook and `seed/SEED-CONTRACT.md` are the authorities for the
implemented activation-last seed/cutover gates.

## Aggregate cell state handoff

A coefficient bundle cannot carry the historical cell state because the loader accepts only five
tensors. Data analysis must provide aggregate-only route-version/stop/arrival-hour/date totals and a
receipt containing source cutoff, route-reference digest, lag/settlement policy, and calculation
version.

The backend seed path verifies the provider gzip and receipt, replays imported and LIVE observations
at each route cutoff to prove `F_C==S`, and then replays the observation-only history through the
paused `FINAL_CUTOVER_AT`. It writes the exact merged `F_T` hourly totals as PostgreSQL `numeric`,
reads back count/key/row/aggregate digests, and materializes one first official generation per route.
The live statistics repository combines that active seed with formal post-cutover inputs rather than
recomputing only from recent rows.

Seed apply is accepted only after exact temporary cleanup and while deployment id 1 remains sole
ACTIVE. Formal activation is last. `seed-verify` then checks the first-generation cell hashes,
provider receipt digest, frozen-key intersection, formal identity and serving forecast generation
references. This worktree consumes the provider contract; it does not duplicate the data-analysis
bundle builder.

## Output receipt and privacy

The training receipt is aggregate-only and must include included/excluded counts by reason, route,
date, schema, origin, and outcome. It records zero emission of vehicle values, plate values, source
HMAC, object locations, raw bodies, credentials, and database secrets.

Model artifacts may contain coefficients, fixed feature metadata, aggregate metrics, route-reference
digests, and source/provenance digests. They may not contain any row-level identity.

## Ready/not-ready decision

`RDS_SNAPSHOT_READY` requires a valid terminal seal, COMPLETE base and terminal imports, repeatable
read receipt, correct route bindings, zero forecast rows over imported batches, and exact temporary
lineage exclusion. Imported batches stay out of the worker forecast queue because that queue admits
only batches whose `response_received_at` is inside the `forecast.staleness` window (default 5
minutes), not because of an origin predicate. They therefore keep `forecast_completed_at IS NULL`
permanently; `reconcile` asserts both that count and the absence of imported-batch forecasts.

`V4_1_BUNDLE_READY` additionally requires the data-analysis final identity, Java strict/golden and
source-to-feature parity, holdout receipts, the aggregate seed contract, a passing seed plan and the
official-generation pre-activation verification. Neither state authorizes bundle promotion, remote
transfer, database write, cleanup, commit, or deployment.
