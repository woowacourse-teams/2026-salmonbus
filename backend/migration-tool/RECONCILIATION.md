# Migration reconciliation contract

## Purpose

Reconciliation proves that the accepted archive became RDS history exactly once while existing live
collection remained unchanged. It is not a count-only success signal. A COMPLETE import must also
prove route context, origin isolation, private identity continuity in aggregate, derived-data
exclusion, and manifest lineage.

All output is aggregate. Queries must never select or print a vehicle value, plate value, source
object location, raw body, HMAC, credential, or database secret.

## Frozen expectations

| measure | immutable base | terminal delta | combined authority |
|---|---:|---:|---:|
| accepted batches | 142,129 | 7,064 | 149,193 |
| accepted observations | 2,324,399 | 136,909 | 2,461,308 |
| quarantined batches | 27 | 1 | 28 |
| quarantined observations | 545 | 24 | 569 |
| overlap batches | 0 | 811 | 811 |
| overlap observations | 0 | 14,924 | 14,924 |

Combined accepted rows by route are:

| route | batches | observations |
|---|---:|---:|
| 3330 | 74,304 | 1,175,694 |
| 1650 | 74,889 | 1,285,614 |

The supplied old target baseline is 303 batches and 8,896 observations. It is an anchor, not the
current live count. Therefore the minimum arithmetic anchors after import are 149,496 and 2,470,204,
plus all target LIVE rows committed after that baseline.

## Pre-import evidence

`archive-verify` must prove the canonical manifest, manifest digest file, every compressed and
uncompressed shard digest, file size, row count, deterministic per-route/per-date totals, unique
source UUIDs, unique semantic digests, route roster coverage, and 0600/0700 permissions.

`preflight` must run in a read-only database transaction and return:

- both configured target-authority instants;
- both current open route-version `valid_from` values or an earlier value explained by a prior
  COMPLETE import binding;
- first LIVE observation time by route, without returning an identifier;
- required-table presence and transaction read-only state;
- projected database peak bytes and operator-confirmed storage headroom.

The first pre-import execution must see current route versions 1 and 2 with the authority instants in
`FIELD-MAPPING.md`. A terminal delta may see their already-extended `valid_from` values only when the
base COMPLETE ledger proves the original values.

## Staging reconciliation

After `stage`:

- staged batch count equals manifest batch count;
- staged observation count equals manifest observation count;
- every shard checkpoint is between zero and its declared batch count;
- completed shard checkpoints equal their declared batch count;
- `(import_batch_id, semantic_batch_digest)` and source UUID are unique;
- every staged observation has a non-null private vehicle identity and a unique source row number;
- no plate, raw, HMAC, object-location, or credential column exists in staging.

Resume must continue after the last committed line of each shard. It must not assume that process
exit means a shard transaction committed.

## Validation reconciliation

`validate` classifies each staged record exactly once:

- `STAGED`: before its route-specific target authority and not already imported;
- `LIVE_OVERLAP`: at or after the route-specific authority;
- `DUPLICATE_IMPORT`: both source UUID and semantic digest match a prior import;
- failure: either identity matches different content or route preconditions differ.

The route binding transaction must leave `route=2`, `route_version=2`, and `route_stop=174`. It may
change only the two current-version `valid_from` values, and only back to each route's accepted source
minimum. The original values and exact precondition digests/counts remain in the binding ledger.

Every accepted observation must find its `(stop_order, stop_id)` in the bound current route version.
Station mismatch records remain outside the archive and are reported by aggregate quarantine reason.

## Merge reconciliation

Every merge transaction contains at most 10,000 observations and uses one importer. For each merged
record, the following become visible atomically:

1. one `observation_batch` with `ingestion_origin='S3_BACKFILL'`;
2. its normalized `vehicle_observation` rows;
3. one `migration_source_record` provenance row;
4. the ledger transition to `MERGED`.

The merge must not write `seat_forecast`, `stop_demand_statistics`, or `model_deployment`. Imported
batch `forecast_completed_at` remains null. The worker pending-forecast query does not read
`ingestion_origin`; it selects only batches whose `response_received_at` is at or after
`now() - forecast.staleness` (`FORECAST_STALENESS`, default 5 minutes). Every imported row is older
than that window, so the queue never selects one. Such rows keep `forecast_completed_at IS NULL`
permanently, which is expected and gets no closing marker; they are identified by
`forecast_completed_at IS NULL AND response_received_at < now() - interval '5 minutes'`.

Concurrent LIVE inserts are allowed. A concurrent natural-key conflict is accepted only when the
pre-existing provenance has the identical source UUID and semantic digest; otherwise merge fails.

The current code rejects a negative gap but does not impose a generic upper bound on a positive gap.
For this sealed migration the operator gate is exact: 3330 must report 7.075820 seconds and 1650 must
report 1.986941 seconds. Any other positive value stops the run pending audit; it is not accepted
merely because reconciliation emitted it.

## COMPLETE receipt

`reconcile` verifies database counts against ledger counts and stores canonical aggregate JSON in
`historical_import_batch.reconciliation_receipt`. Required receipt content includes:

- import and manifest identity;
- source cutoff and terminal closure digests when applicable;
- merged, duplicate, overlap, rejected, and observation counts;
- imported counts by route and KST date;
- last historical and first LIVE time per route, gap seconds, and shared-vehicle count;
- eligible/excluded temporary forecast and statistics aggregates;
- privacy flags asserting that no sensitive value was emitted.

For 3330, the known source-last/first-LIVE gap is 7.07582 seconds. For 1650, the receipt must record
the actual read-only first-LIVE result; the fixed 1.574299-second value is only source-last to
route-version-open evidence. Any negative gap fails reconciliation.

Vehicle continuity is asserted only as `count(distinct private identity) > 0` in the boundary
windows for each route. The receipt never includes the values.

## Terminal seal

A terminal reconciliation additionally requires:

- `archive_kind='TERMINAL_DELTA'`;
- previous manifest status COMPLETE and previous inventory equal to the immutable-base digest;
- manifest full-history inventory equal to the confirmed dataset-seal digest;
- terminal receipt, partition, base, and source-closure digests equal to their pinned authorities;
- terminal import status COMPLETE in the same transaction that creates the seal.

The single row in `historical_import_dataset_seal` must join to the same import batch and receipt.
Conflicting pre-existing seal identity fails; it is never silently replaced. A trainer must join the
seal to a COMPLETE terminal import before claiming RDS snapshot readiness.

## Rollback reconciliation

`rollback --execute false` reports target batch/observation counts and the LIVE snapshot. It performs
no mutation and requires no write approval. Executed rollback requires a manifest-bound
`ACADEMY_ROLLBACK` receipt.

Rollback is blocked when a non-rolled-back delta depends on the manifest or any imported forecast
exists. An executed rollback removes only that import's provenance and S3_BACKFILL observation rows,
removes its terminal seal if present, marks the ledger ROLLED_BACK, and restores route validity only
as far as remaining imports permit.

Before and after rollback, these LIVE values must be identical:

- LIVE batch count;
- LIVE observation count;
- minimum LIVE `response_received_at`;
- maximum LIVE `response_received_at`.

## Temporary-derived cleanup reconciliation

Observation import and temporary-derived cleanup are separate operations. The formal cutover must
first stop forecast, settlement, and statistics writing by restarting the worker with
`FORECAST_ENABLED=false`; under `forecast.enabled=false` those beans do not start at all
(`@ConditionalOnProperty`), while collection keeps running on `collection.enabled`. `temp-pause`
follows only after `max(seat_forecast.generated_at)`, `max(seat_forecast.scored_at)`, and
`max(stop_demand_statistics.computed_at)` have all stopped advancing; it fixes `FINAL_CUTOVER_AT`
and the observation high-water in one transaction and is a cutover ledger, not a writer block.
After formal deployment activation, the freeze reads its authoritative
`model_deployment.activated_at` and records every temporary-window
generation whose `computed_at` is in
`[2026-09-02T11:55:04.729493Z, formal activated_at)`.

`data_until` is part of exact generation identity but is not required to lie inside that wall-clock
window. The frozen key is `(route_version_id, calculation_version, revision, data_until,
computed_at)`. Before freeze, official reads fail closed on all candidate generations after temp
activation; after freeze, they exclude only exact frozen keys.

Cleanup dry-run and execution must agree on target identity. Execution may delete only temporary
deployment forecast rows and exact frozen statistics rows. It retains all observations,
`model_deployment.id=1` as RETIRED, and every `forecast_completed_at` value.

At the captured observation high-water ID, these values must be identical before and after cleanup:

- batch count;
- observation count;
- minimum and maximum response time;
- count of incomplete forecast markers.

Cleanup is idempotent: a second approved execution reports zero remaining target rows.

## Operational quota evidence

`LIVE + FAILED_UPSTREAM + DAILY_QUOTA_EXCEEDED` batches remain in the target audit timeline. They are
not counted as imported historical success and are never deleted by migration reconciliation. HTTP
429 source overlap records remain S3-only evidence. The academy local quota ledger is not evidence of
shared upstream-key capacity.

## Acceptance result

The migration is data-ready only when both base and terminal receipts pass, the terminal seal exists,
no S3_BACKFILL row enters the online pending queue, and all above invariants hold. Operational
continuity remains separately pending until read-only academy access can confirm current LIVE
collection and both route boundary aggregates.
